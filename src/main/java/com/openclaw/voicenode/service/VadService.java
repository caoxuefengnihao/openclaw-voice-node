package com.openclaw.voicenode.service;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import com.openclaw.voicenode.config.VadProps;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * VAD (Voice Activity Detection) 服务 (v3-vad 新增)。
 *
 * <p>基于 sherpa-onnx 1.13.4 的统一 {@link Vad} 类 (<b>无 OfflineVad 区分</b>)。
 * 复用同库 (跟 {@link KwsService} / {@link SttService} 同一 jar + native lib,零新依赖)。
 *
 * <p>输入 PCM 格式: <b>16kHz mono Float32</b> (范围 [-1.0, 1.0])。
 * 跟 {@code SttService} 共用 Int16→Float32 转换 (抽到工具方法复用)。
 *
 * <p>{@link Vad} 实例是单线程的 (跟 {@link KwsService} 同样模式),
 * 所有 public 方法都是 {@code synchronized}。
 *
 * <p>调用模式:
 * <pre>
 *   List&lt;VadSegment&gt; segments = vadService.split(float32Pcm);
 *   for (VadSegment seg : segments) {
 *       // seg.startSample() / seg.endSample() / seg.sampleCount() / seg.durationMs()
 *       // 用原始 Float32 buffer 切出该段 → Int16 → sttService.recognize()
 *   }
 * </pre>
 *
 * @see VadProps 配置
 * @see com.openclaw.voicenode.probe.VadProbeTest 实测 API 用法
 */
@Slf4j
@Service
public class VadService {

    private final VadProps props;
    private Vad vad;

    /** silero-vad v4 要求的 window size (32ms @16kHz),跟 {@code VadProps.windowSize} 必须一致 */
    private static final int SILERO_WINDOW_SIZE = 512;

    public VadService(VadProps props) {
        this.props = props;
    }

    @PostConstruct
    public void init() throws Exception {
        if (!props.enabled()) {
            log.info("🔇 VAD 已禁用 (vad.enabled=false),降级到整段 STT");
            return;
        }

        Path modelPath = Paths.get(props.modelDir(), "silero_vad.onnx");
        if (!Files.exists(modelPath)) {
            throw new IllegalStateException(
                    "silero-vad 模型不存在: " + modelPath + "\n"
                            + "请跑: ./scripts/download-vad-deps.sh");
        }

        log.info("🔥 加载 VAD 模型: modelDir={}, sampleRate={}, threshold={}, windowSize={}",
                props.modelDir(), props.sampleRate(), props.threshold(), SILERO_WINDOW_SIZE);

        long t0 = System.currentTimeMillis();

        // 1. silero-vad 子配置
        SileroVadModelConfig silero = SileroVadModelConfig.builder()
                .setModel(modelPath.toString())
                .setThreshold(props.threshold())
                .setMinSilenceDuration(props.minSilenceDurationMs() / 1000f)  // ms → 秒
                .setMinSpeechDuration(props.minSpeechDurationMs() / 1000f)
                .setWindowSize(SILERO_WINDOW_SIZE)  // 固定 512
                .setMaxSpeechDuration(20.0f)
                .build();

        // 2. VadModelConfig (选 silero 模型 + sample rate + 线程数)
        VadModelConfig config = VadModelConfig.builder()
                .setSileroVadModelConfig(silero)
                .setSampleRate(props.sampleRate())
                .setNumThreads(props.numThreads())
                .setDebug(false)
                .build();

        this.vad = new Vad(config);
        log.info("✅ VAD 模型加载完成 ({}ms)", System.currentTimeMillis() - t0);
    }

    /**
     * 把整段 Float32 PCM 切成多个"说话段"。
     *
     * <p>API 调用顺序 (实测自 {@code VadProbe} 2026-07-30):
     * <ol>
     *   <li>反复 {@code vad.acceptWaveform(chunk)} 喂入,每次 512 samples (silero window)</li>
     *   <li>结束时 {@code vad.flush()}</li>
     *   <li>循环 {@code !vad.empty()} → {@code front()} 拿 segment → {@code pop()}</li>
     *   <li>每个 segment 的 {@link SpeechSegment#getStart()} 是相对累计输入的 sample 索引</li>
     * </ol>
     *
     * <p>⚠️ <b>重要</b>: {@code vad.reset()} 必须在 drain 完之后调用,否则会清掉未处理的 segment。
     * 每次 {@code split} 后自动 reset,保证下次调用独立 (startSample 重新从 0 开始)。
     *
     * @param samples 16kHz mono Float32 PCM (范围 [-1.0, 1.0])
     * @return 段列表,每段是 [startSample, endSample) 半开区间
     */
    public synchronized List<VadSegment> split(float[] samples) {
        if (vad == null || !props.enabled()) {
            // 降级:整段当作一段
            return List.of(new VadSegment(0, samples.length));
        }
        if (samples == null || samples.length == 0) {
            return List.of();
        }

        // 1. 按 windowSize (512) 分块喂入
        for (int offset = 0; offset < samples.length; offset += SILERO_WINDOW_SIZE) {
            int len = Math.min(SILERO_WINDOW_SIZE, samples.length - offset);
            float[] chunk = new float[len];
            System.arraycopy(samples, offset, chunk, 0, len);
            vad.acceptWaveform(chunk);
        }

        // 2. flush 触发最后一段 segment 输出
        vad.flush();

        // 3. 弹出所有 segments
        List<VadSegment> result = new ArrayList<>();
        while (!vad.empty()) {
            SpeechSegment seg = vad.front();
            int startSample = seg.getStart();
            int endSample = startSample + seg.getSamples().length;
            result.add(new VadSegment(startSample, endSample));
            vad.pop();
        }

        // 4. reset 准备下次调用 (必须在 drain 之后,否则会清掉未处理的 segment)
        vad.reset();

        log.debug("🎙️ VAD split: {} samples → {} 段", samples.length, result.size());
        return result;
    }

    /** 当前 VAD 是否启用(模型加载成功且配置 enabled=true) */
    public boolean isEnabled() {
        return props.enabled() && vad != null;
    }

    @PreDestroy
    public void destroy() {
        if (vad != null) {
            vad.release();
            vad = null;
        }
        log.info("🔇 VAD 已关闭");
    }

    /**
     * 说话段标记,起止用 sample index 标记 (半开区间 [start, end))。
     *
     * @param startSample 段起始 sample index (含)
     * @param endSample   段结束 sample index (不含)
     */
    public record VadSegment(int startSample, int endSample) {
        public int sampleCount() {
            return endSample - startSample;
        }

        /** 段时长(毫秒),基于 16kHz 采样率 */
        public float durationMs() {
            return sampleCount() * 1000f / 16000f;
        }
    }
}