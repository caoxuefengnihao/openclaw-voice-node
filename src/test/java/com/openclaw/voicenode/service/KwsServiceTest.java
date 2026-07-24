package com.openclaw.voicenode.service;

import com.openclaw.voicenode.config.KwsProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KwsService 单测 (v3 新增)。
 *
 * <p>用 models/kws/test_wavs/0.wav 作为输入 (~175KB,16kHz mono PCM),
 * 转 Float32 LE 后喂给 KwsService.acceptFrame(),断言不触发当前模型关键词。
 *
 * <p><b>依赖模型文件</b>:跑过 {@code ./scripts/download-kws-deps.sh} 后才会下载。
 * 用 {@link EnabledIf} 跳过没有模型的 CI 环境。
 */
@SpringBootTest(classes = com.openclaw.voicenode.VoiceNodeApplication.class)
@TestPropertySource(properties = {
        "openclaw.gateway.url=",
        "openclaw.stt.model-dir=${user.dir}/models/stt",
        "openclaw.kws.model-dir=${user.dir}/models/kws",
        "openclaw.kws.num-threads=1",
        "openclaw.kws.threshold=0.3",  // 测试用低阈值,提高召回
        "openclaw.kws.enabled=true"
})
class KwsServiceTest {

    @Autowired
    KwsService kwsService;

    @Autowired
    KwsProps kwsProps;

    @Test
    @EnabledIf("modelsExist")
    void noFalsePositiveOnUnrelatedAudio() throws Exception {
        // test_wavs 里是历史模型的关键词 ("文森特卡索/周望军" 等),
        // 跟当前 keywords.txt 里的 ("你好军哥/小爱同学" 等) 不匹配。
        // 所以这个测试的预期:喂任何一段 wav,都不应该误检测当前模型的关键词。
        // (正向检测需要人工录制 "小爱同学" 等样本,后续里程碑再加。)
        Path wavPath = Paths.get(kwsProps.modelDir(), "test_wavs/0.wav");
        assertTrue(Files.exists(wavPath), "test_wavs/0.wav 不存在");

        // 读 WAV 跳 44 字节头 — Int16 LE PCM
        byte[] wav = Files.readAllBytes(wavPath);
        byte[] int16Pcm = new byte[wav.length - 44];
        System.arraycopy(wav, 44, int16Pcm, 0, int16Pcm.length);

        // Int16 LE → Float32 LE (适配新格式)
        byte[] float32Pcm = int16LeToFloat32Le(int16Pcm);

        // 一块 1600 样本 (100ms @16kHz) = 6400 字节 Float32
        int frameSize = 6400;
        String detected = "";
        kwsService.startSession("test-session");
        int frameCount = 0;
        for (int offset = 0; offset + frameSize <= float32Pcm.length; offset += frameSize) {
            byte[] frame = new byte[frameSize];
            System.arraycopy(float32Pcm, offset, frame, 0, frameSize);
            String r = kwsService.acceptFrame("test-session", frame);
            frameCount++;
            if (!r.isEmpty()) {
                detected = r;
                break;
            }
        }
        kwsService.stopSession("test-session");

        String failMsg = "❌ 误触发!送了 " + frameCount + " 帧无关音频却检测到: \"" + detected + "\"";
        assertTrue(detected.isEmpty(), failMsg);
        System.out.println("✅ 送 " + frameCount + " 帧无关音频,无误触发 (FAR 验证)");
    }

    @Test
    @EnabledIf("modelsExist")
    void noFalsePositiveOnSilence() {
        // 送静音 (全 0,Float32),不应触发任何 keyword
        byte[] silence = new byte[64000]; // 1s @16kHz Float32 = 16000 样本 × 4 字节
        kwsService.startSession("silence");
        for (int i = 0; i < 10; i++) {
            String r = kwsService.acceptFrame("silence", silence);
            assertTrue(r.isEmpty(), "❌ 静音误触发: \"" + r + "\"");
        }
        kwsService.stopSession("silence");
        System.out.println("✅ 10s 静音,无触发");
    }

    @Test
    @EnabledIf("modelsExist")
    void keywordsListReadFromFile() {
        // 验证 keywords() 从 keywords.txt 读出非空列表
        var keywords = kwsService.keywords();
        assertFalse(keywords.isEmpty(), "keywords() 返回空,keywords.txt 未读出");
        System.out.println("✅ keywords.txt 内容 (" + keywords.size() + " 个): " + keywords);
    }

    @Test
    @EnabledIf("modelsExist")
    void activeSessionCountTracksSessions() {
        kwsService.startSession("s1");
        kwsService.startSession("s2");
        assertEquals(2, kwsService.activeSessionCount());

        kwsService.stopSession("s1");
        assertEquals(1, kwsService.activeSessionCount());

        kwsService.stopSession("s2");
        assertEquals(0, kwsService.activeSessionCount());
    }

    /**
     * Int16 LE PCM bytes → Float32 LE PCM bytes。WAV 测试文件是 Int16,后端要 Float32。
     */
    private static byte[] int16LeToFloat32Le(byte[] int16Le) {
        int nSamples = int16Le.length / 2;
        ByteBuffer in = ByteBuffer.wrap(int16Le).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer out = ByteBuffer.allocate(nSamples * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < nSamples; i++) {
            short s = in.getShort();
            out.putFloat(s / 32768f);
        }
        return out.array();
    }

    /**
     * JUnit EnabledIf 引用:模型文件存在时才跑测试,否则 skip。
     */
    static boolean modelsExist() {
        return Files.exists(Paths.get("models/kws/encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx"))
                && Files.exists(Paths.get("models/kws/test_wavs/0.wav"));
    }
}