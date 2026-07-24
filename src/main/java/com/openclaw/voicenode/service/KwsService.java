package com.openclaw.voicenode.service;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;
import com.openclaw.voicenode.config.KwsProps;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KWS (Keyword Spotting) 唤醒词检测服务 (v3 新增)。
 *
 * <p>复用 sherpa-onnx (跟 {@link SttService} 同库不同模型),不需要新加 Maven 依赖。
 * <p>{@link KeywordSpotter} 是单线程的,所有 public 方法都是 {@code synchronized}。
 *
 * <p>每个 WS session 对应一个 {@link OnlineStream}。检测到唤醒词后**重置 stream**,
 * 准备下一轮监听(避免重复触发同一段音频)。
 *
 * <p>调用模式:
 * <pre>
 *   kwsService.startSession(sessionId);
 *   // 每帧 PCM 进来:
 *   String keyword = kwsService.acceptFrame(sessionId, pcmBytes);
 *   if (!keyword.isEmpty()) { ...唤醒命中... }
 *   // session 关闭:
 *   kwsService.stopSession(sessionId);
 * </pre>
 *
 * @see KwsProps 配置
 */
@Slf4j
@Service
public class KwsService {

    private final KwsProps props;
    private KeywordSpotter spotter;

    /**
     * sessionId -> OnlineStream
     * <p>ConcurrentHashMap 因为每个 session 独立 start/stop,acceptFrame 走 synchronized。
     */
    private final Map<String, OnlineStream> sessionStreams = new ConcurrentHashMap<>();

    public KwsService(KwsProps props) {
        this.props = props;
    }

    @PostConstruct
    public void init() throws Exception {
        if (!props.enabled()) {
            log.info("🔇 KWS 已禁用 (kws.enabled=false),跳过模型加载");
            return;
        }

        Path encoderPath = Paths.get(props.modelDir(), "encoder.onnx");
        Path tokensPath = Paths.get(props.modelDir(), "tokens.txt");
        Path keywordsPath = Paths.get(props.modelDir(), "keywords.txt");

        if (!Files.exists(encoderPath) || !Files.exists(tokensPath) || !Files.exists(keywordsPath)) {
            throw new IllegalStateException(
                    "KWS 模型文件不存在:\n"
                            + "  " + encoderPath + "\n"
                            + "  " + tokensPath + "\n"
                            + "  " + keywordsPath + "\n"
                            + "请先跑: ./scripts/download-kws-deps.sh");
        }

        log.info("🔥 加载 KWS 模型: modelDir={}, numThreads={}, threshold={}",
                props.modelDir(), props.numThreads(), props.threshold());

        long t0 = System.currentTimeMillis();

        FeatureConfig featureConfig = FeatureConfig.builder()
                .setSampleRate(props.sampleRate())
                .setFeatureDim(80)
                .build();

        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setZipformer2Ctc(OnlineZipformer2CtcModelConfig.builder()
                        .setModel(encoderPath.toString())
                        .build())
                .setTokens(tokensPath.toString())
                .setNumThreads(props.numThreads())
                .build();

        KeywordSpotterConfig config = KeywordSpotterConfig.builder()
                .setOnlineModelConfig(modelConfig)
                .setFeatureConfig(featureConfig)
                .setKeywordsFile(keywordsPath.toString())
                .setKeywordsThreshold(props.threshold())
                .build();

        this.spotter = new KeywordSpotter(config);
        log.info("✅ KWS 模型加载完成 ({}ms)", System.currentTimeMillis() - t0);
    }

    /**
     * 创建或重置 session 的 KWS stream。
     *
     * @param sessionId WS session id (用 {@code session.getId()})
     */
    public synchronized void startSession(String sessionId) {
        if (spotter == null) {
            log.warn("⚠️ KWS 未启用,startSession({}) 跳过", sessionId);
            return;
        }
        // 先清理旧的 stream(如果有)
        OnlineStream old = sessionStreams.remove(sessionId);
        if (old != null) {
            old.release();
        }
        OnlineStream stream = spotter.createStream();
        sessionStreams.put(sessionId, stream);
        log.info("🔥 KWS session started: {}", sessionId);
    }

    /**
     * 喂 PCM 帧进 KWS。返回检测到的 keyword,空字符串表示未检测到。
     *
     * <p>synchronized 锁住的是 spotter 实例,跟 {@link SttService#recognize} 同样的单线程模式。
     *
     * @param sessionId WS session id
     * @param pcmFrame  16kHz mono int16 little-endian PCM bytes
     * @return 唤醒词文本(如 "嗨小爱"),空字符串表示未检测到
     */
    public synchronized String acceptFrame(String sessionId, byte[] pcmFrame) {
        if (spotter == null) {
            return "";
        }
        OnlineStream stream = sessionStreams.get(sessionId);
        if (stream == null) {
            // session 未启动 KWS (可能是录音模式),不报错,直接返回
            return "";
        }

        float[] samples = pcm16kMonoInt16ToFloat(pcmFrame);
        stream.acceptWaveform(samples, props.sampleRate());

        while (spotter.isReady(stream)) {
            spotter.decode(stream);
        }

        KeywordSpotterResult result = spotter.getResult(stream);
        if (!result.getKeyword().isEmpty()) {
            // 命中 -> 重置 stream,准备下一轮监听
            stream.release();
            sessionStreams.put(sessionId, spotter.createStream());
            log.info("🔥 Wake detected: session={}, keyword={}", sessionId, result.getKeyword());
            return result.getKeyword();
        }
        return "";
    }

    /**
     * 停止并清理 session 的 KWS stream。
     *
     * @param sessionId WS session id
     */
    public synchronized void stopSession(String sessionId) {
        OnlineStream stream = sessionStreams.remove(sessionId);
        if (stream != null) {
            stream.release();
            log.info("🔥 KWS session stopped: {}", sessionId);
        }
    }

    /**
     * 当前是否有 session 在监听(用于健康检查/调试)。
     */
    public int activeSessionCount() {
        return sessionStreams.size();
    }

    /**
     * 返回配置的关键词列表(从 keywords.txt 第一行读出来,简化版)。
     * <p>主要用于给前端展示"我支持哪些唤醒词"。
     */
    public java.util.List<String> keywords() {
        if (!props.enabled()) return java.util.List.of();
        try {
            return Files.readAllLines(Paths.get(props.modelDir(), "keywords.txt")).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> {
                        // keywords.txt 格式: "keyword : weight" 或 "keyword"
                        int colon = s.indexOf(':');
                        return colon > 0 ? s.substring(0, colon).trim() : s;
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("读 keywords.txt 失败: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    @PreDestroy
    public void destroy() {
        sessionStreams.values().forEach(OnlineStream::release);
        sessionStreams.clear();
        if (spotter != null) {
            spotter.release();
            spotter = null;
        }
        log.info("🔇 KWS 已关闭");
    }

    /**
     * PCM 16kHz mono int16 little-endian -> float32 [-1.0, 1.0]
     */
    private static float[] pcm16kMonoInt16ToFloat(byte[] pcm) {
        int n = pcm.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm[2 * i] & 0xff) | ((pcm[2 * i + 1] & 0xff) << 8));
            out[i] = s / 32768f;
        }
        return out;
    }
}