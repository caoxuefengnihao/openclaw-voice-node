package com.openclaw.voicenode.probe;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VAD API 实测 smoke test (从一次性 probe 转成 JUnit,留作文档 + 回归测试)。
 *
 * <p><b>目的</b>:验证 sherpa-onnx 1.13.4 的 {@link Vad} 类用法,
 * 防止 sherpa-onnx 升级时无声破坏 VAD 集成。
 *
 * <p><b>依赖</b>:需要 {@code models/vad/silero_vad.onnx} 文件,
 * 用 {@link EnabledIf} 跳过没有模型的 CI 环境。
 *
 * <p><b>API 速查</b>:
 * <pre>
 * Vad vad = new Vad(VadModelConfig.builder()
 *     .setSileroVadModelConfig(SileroVadModelConfig.builder()
 *         .setModel(path).setThreshold(0.5f)
 *         .setMinSilenceDuration(0.5f).setMinSpeechDuration(0.25f)
 *         .setWindowSize(512).setMaxSpeechDuration(20.0f).build())
 *     .setSampleRate(16000).setNumThreads(1).build());
 *
 * for (chunk : splitInto512Chunks(samples)) vad.acceptWaveform(chunk);
 * vad.flush();
 * while (!vad.empty()) {
 *     SpeechSegment seg = vad.front();  // getStart() / getSamples()
 *     vad.pop();
 * }
 * vad.release();
 * </pre>
 */
class VadProbeTest {

    private static final String MODEL_PATH = "models/vad/silero_vad.onnx";
    private static final int WINDOW_SIZE = 512;
    private static final int SAMPLE_RATE = 16000;

    private static Vad vad;

    @BeforeAll
    static void setUp() throws Exception {
        SileroVadModelConfig silero = SileroVadModelConfig.builder()
                .setModel(MODEL_PATH)
                .setThreshold(0.5f)
                .setMinSilenceDuration(0.5f)
                .setMinSpeechDuration(0.25f)
                .setWindowSize(WINDOW_SIZE)
                .setMaxSpeechDuration(20.0f)
                .build();
        VadModelConfig config = VadModelConfig.builder()
                .setSileroVadModelConfig(silero)
                .setSampleRate(SAMPLE_RATE)
                .setNumThreads(1)
                .build();
        vad = new Vad(config);
    }

    @AfterAll
    static void tearDown() {
        if (vad != null) vad.release();
    }

    @Test
    @EnabledIf("modelExists")
    void vad_initializesAndAcceptsSilenceWithoutCrash() throws Exception {
        // 静音 → 0 segments, no exception
        feedInChunks(new float[SAMPLE_RATE]); // 1s 静音
        vad.flush();

        List<Integer> starts = drainSegments();
        assertEquals(0, starts.size(), "静音应切出 0 个段");
        assertFalse(vad.isSpeechDetected(), "静音 isSpeechDetected 应为 false");
    }

    @Test
    @EnabledIf("modelExists")
    void vad_detectsRealSpeechInWavFile() throws Exception {
        // 真实语音 WAV → ≥1 segment
        File wav = new File("models/stt/test_wavs/0.wav");
        if (!wav.exists()) {
            System.out.println("跳过:test_wavs/0.wav 不存在");
            return;
        }

        float[] samples = readWavAsFloat32(wav);
        System.out.println("读出 " + samples.length + " samples ("
                + (samples.length / 16) + "ms @16kHz)");
        feedInChunks(samples);
        vad.flush();

        List<Integer> starts = drainSegments();
        System.out.println("切出 " + starts.size() + " 个段");
        assertTrue(starts.size() >= 1, "真实语音应切出 ≥1 个段");

        // 验证 start 索引合理(在样本范围内)
        for (int start : starts) {
            assertTrue(start >= 0 && start < samples.length,
                    "segment start=" + start + " 超出样本范围 [0, " + samples.length + ")");
        }
    }

    @Test
    @EnabledIf("modelExists")
    void vad_doesNotDetectSyntheticSineWave() {
        // 文档价值测试:silero-vad 训练在真实语音上,纯正弦波不识别
        // (频谱动力学不像语音,跟 STT 的"嗯""啊"幻觉类似)
        float[] synth = new float[SAMPLE_RATE * 2]; // 2s
        for (int i = 0; i < synth.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float envelope = (float) Math.sin(Math.PI * (t / 2f));
            float carrier = (float) Math.sin(2 * Math.PI * 200 * t);
            synth[i] = envelope * carrier * 0.5f;
        }
        feedInChunks(synth);
        vad.flush();

        List<Integer> starts = drainSegments();
        assertEquals(0, starts.size(),
                "silero 不应识别合成正弦波 (训练数据无此模式)");
        System.out.println("✅ 文档记录:silero-vad 不识别合成正弦波");
    }

    // ===== 工具方法 =====

    static boolean modelExists() {
        return new File(MODEL_PATH).exists();
    }

    private static void feedInChunks(float[] samples) {
        for (int offset = 0; offset < samples.length; offset += WINDOW_SIZE) {
            int len = Math.min(WINDOW_SIZE, samples.length - offset);
            float[] chunk = new float[len];
            System.arraycopy(samples, offset, chunk, 0, len);
            vad.acceptWaveform(chunk);
        }
        // ⚠️ 不要在这里 reset()/clear() — flush 之前 reset 会清掉已计算的 segment 队列
        // 正确顺序: acceptWaveform → flush → pop segments → reset (下一次调用前)
    }

    private static List<Integer> drainSegments() {
        List<Integer> starts = new ArrayList<>();
        while (!vad.empty()) {
            SpeechSegment seg = vad.front();
            starts.add(seg.getStart());
            vad.pop();
        }
        vad.reset();  // drain 完后 reset,准备下一次测试
        return starts;
    }

    private static float[] readWavAsFloat32(File wav) throws Exception {
        try (FileInputStream fis = new FileInputStream(wav)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            byte[] raw = bos.toByteArray();
            int headerSize = 44;
            int dataSize = raw.length - headerSize;
            int nSamples = dataSize / 2;
            ByteBuffer bb = ByteBuffer.wrap(raw, headerSize, dataSize).order(ByteOrder.LITTLE_ENDIAN);
            float[] out = new float[nSamples];
            for (int i = 0; i < nSamples; i++) {
                out[i] = bb.getShort() / 32768f;
            }
            return out;
        }
    }
}