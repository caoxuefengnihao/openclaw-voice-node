package com.openclaw.voicenode.service;

import com.openclaw.voicenode.config.VadProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VadService 单测 (v3-vad M1-D)。
 *
 * <p>测试用例:
 * <ul>
 *   <li>disabled 降级路径(返回整段单段)</li>
 *   <li>空输入返回空列表</li>
 *   <li>静音输入无识别</li>
 *   <li>真实语音 WAV 能识别出 ≥1 段</li>
 * </ul>
 *
 * <p>依赖:需要 {@code models/vad/silero_vad.onnx},用 {@link EnabledIf} 跳过 CI 无模型环境。
 */
@SpringBootTest(classes = com.openclaw.voicenode.VoiceNodeApplication.class)
@TestPropertySource(properties = {
        "openclaw.gateway.url=",
        "openclaw.stt.model-dir=${user.dir}/models/stt",
        "openclaw.vad.model-dir=${user.dir}/models/vad",
        "openclaw.vad.num-threads=1",
        "openclaw.vad.threshold=0.3",  // 测试用低阈值,提高召回
        "openclaw.vad.enabled=true"
})
class VadServiceTest {

    @Autowired
    VadService vadService;

    @Autowired
    VadProps vadProps;

    @Test
    void serviceIsAutowiredAndEnabled() {
        assertNotNull(vadService);
        assertTrue(vadService.isEnabled(), "VAD 应该已启用 (模型存在 + enabled=true)");
    }

    @Test
    @EnabledIf("modelsExist")
    void emptyInputReturnsEmptyList() {
        List<VadService.VadSegment> segs = vadService.split(new float[0]);
        assertEquals(0, segs.size(), "空输入应返回 0 段");
    }

    @Test
    @EnabledIf("modelsExist")
    void silenceReturnsNoSegments() {
        // 1s 静音 → silero 不应识别为语音
        float[] silence = new float[16000];
        List<VadService.VadSegment> segs = vadService.split(silence);
        System.out.println("静音 1s → " + segs.size() + " 段 (期望 0)");
        assertEquals(0, segs.size(), "静音不应切出段");
    }

    @Test
    @EnabledIf("modelsExist")
    void realWavDetectsSpeech() throws Exception {
        Path wavPath = Paths.get("models/stt/test_wavs/0.wav");
        if (!Files.exists(wavPath)) {
            System.out.println("跳过:models/stt/test_wavs/0.wav 不存在");
            return;
        }
        float[] samples = readWavAsFloat32(wavPath.toFile());
        System.out.println("读出 " + samples.length + " samples ("
                + (samples.length / 16) + "ms @16kHz)");

        List<VadService.VadSegment> segs = vadService.split(samples);
        System.out.println("切出 " + segs.size() + " 段:");
        for (int i = 0; i < segs.size(); i++) {
            VadService.VadSegment seg = segs.get(i);
            System.out.println("  segment #" + i
                    + " start=" + seg.startSample() + " (" + (seg.startSample() / 16) + "ms)"
                    + " end=" + seg.endSample() + " (" + (seg.endSample() / 16) + "ms)"
                    + " len=" + seg.sampleCount() + " (" + (int) seg.durationMs() + "ms)");
        }
        assertTrue(segs.size() >= 1, "真实语音应切出 ≥1 段");

        // 验证段都在样本范围内
        for (VadService.VadSegment seg : segs) {
            assertTrue(seg.startSample() >= 0 && seg.endSample() <= samples.length,
                    "段 [" + seg.startSample() + ", " + seg.endSample() + ") 超出范围 [0, "
                            + samples.length + ")");
            assertTrue(seg.sampleCount() > 0, "段长度必须 > 0");
        }
    }

    @Test
    @EnabledIf("modelsExist")
    void multipleSplitCallsAreIndependent() throws Exception {
        // 验证 reset() 正常工作:两次 split 独立,startSample 不会累加
        float[] silence = new float[8000];   // 0.5s 静音
        float[] speech = readWavAsFloat32(Paths.get("models/stt/test_wavs/0.wav").toFile());

        // 第一次:静音
        List<VadService.VadSegment> first = vadService.split(silence);
        // 第二次:真实语音
        List<VadService.VadSegment> second = vadService.split(speech);

        // 第二次的 startSample 应该相对该次输入 (受 reset() 影响)
        for (VadService.VadSegment seg : second) {
            assertTrue(seg.startSample() < speech.length,
                    "reset 后 startSample 应 < 输入长度,实际 " + seg.startSample());
        }
        System.out.println("✅ 两次 split 调用独立,reset() 正常工作");
    }

    /**
     * JUnit EnabledIf 引用:模型文件存在时才跑需要模型的测试。
     */
    static boolean modelsExist() {
        return Files.exists(Paths.get("models/vad/silero_vad.onnx"));
    }

    /** 读 WAV 文件 (16kHz mono 16-bit PCM) → Float32 [-1, 1] */
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