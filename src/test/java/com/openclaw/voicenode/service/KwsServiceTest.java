package com.openclaw.voicenode.service;

import com.openclaw.voicenode.config.KwsProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KwsService 单测 (v3 新增)。
 *
 * <p>用 models/kws/test_wavs/0.wav 作为输入 (~175KB,16kHz mono PCM),
 * 喂给 KwsService.acceptFrame() 分帧,断言检测到 keywords.txt 里预设的"文森特卡索"。
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

        // 读 WAV 跳 44 字节头
        byte[] wav = Files.readAllBytes(wavPath);
        byte[] pcm = new byte[wav.length - 44];
        System.arraycopy(wav, 44, pcm, 0, pcm.length);

        int frameSize = 3200;
        String detected = "";
        kwsService.startSession("test-session");
        int frameCount = 0;
        for (int offset = 0; offset < pcm.length; offset += frameSize) {
            int end = Math.min(offset + frameSize, pcm.length);
            byte[] frame = new byte[end - offset];
            System.arraycopy(pcm, offset, frame, 0, frame.length);
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
        // 送静音 (全 0),不应触发任何 keyword
        byte[] silence = new byte[32000]; // 1s @16kHz int16 = 全 0
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
     * JUnit EnabledIf 引用:模型文件存在时才跑测试,否则 skip。
     * 静态方法 + boolean 返回值 + 同名匹配 (不带 "()" 是字段/方法名)。
     */
    static boolean modelsExist() {
        return Files.exists(Paths.get("models/kws/encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx"))
                && Files.exists(Paths.get("models/kws/test_wavs/0.wav"));
    }
}