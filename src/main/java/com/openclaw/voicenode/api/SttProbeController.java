package com.openclaw.voicenode.api;

import com.openclaw.voicenode.service.SttService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 临时 probe:实测 SttService.recognize() 能否识别中文。
 * M1-A 集成后验证,M1-B (WS 协议) 接入前删。
 *
 * 用法:
 *   GET /api/test/stt-recognize                         (默认测 test_wavs/0.wav)
 *   GET /api/test/stt-recognize?wav=1.wav              (测 test_wavs/1.wav)
 *
 * 返回 { ok, text, audioLengthBytes, audioLengthMs, durationMs }
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class SttProbeController {

    private final SttService sttService;

    @Value("${openclaw.stt.model-dir}")
    private String modelDir;

    @GetMapping("/stt-recognize")
    public Map<String, Object> recognize(
            @RequestParam(defaultValue = "0.wav") String wav) {

        Map<String, Object> result = new LinkedHashMap<>();
        Path wavPath = Paths.get(modelDir, "test_wavs", wav);

        if (!Files.exists(wavPath)) {
            result.put("ok", false);
            result.put("error", "测试音频不存在: " + wavPath);
            return result;
        }

        byte[] pcm;
        try {
            pcm = readWavToPcm(wavPath);
        } catch (IOException e) {
            log.error("[probe] 读 WAV 失败", e);
            result.put("ok", false);
            result.put("error", "读 WAV 失败: " + e.getMessage());
            return result;
        }

        long t0 = System.currentTimeMillis();
        try {
            String text = sttService.recognize(pcm);
            long elapsed = System.currentTimeMillis() - t0;

            result.put("ok", true);
            result.put("text", text);
            result.put("audioLengthBytes", pcm.length);
            result.put("audioLengthMs", pcm.length / 32);  // 16kHz * 2 bytes/sample = 32 bytes/ms
            result.put("durationMs", elapsed);
            return result;
        } catch (Exception e) {
            log.error("[probe] SttService 失败", e);
            result.put("ok", false);
            result.put("error", e.getMessage());
            result.put("errorType", e.getClass().getSimpleName());
            result.put("durationMs", System.currentTimeMillis() - t0);
            return result;
        }
    }

    /**
     * 16kHz mono PCM int16 little-endian WAV → 去掉 header 的纯 PCM bytes。
     * SttService.recognize() 接收的就是这种 PCM。
     * (跟 sherpa-onnx 的 hello world 同样的读法)
     */
    private static byte[] readWavToPcm(Path wav) throws IOException {
        byte[] all = Files.readAllBytes(wav);
        int dataOffset = -1, dataSize = 0;
        int sampleRate = 16000, channels = 1;

        // 找 'data' chunk
        for (int i = 12; i < all.length - 8; i++) {
            if (all[i] == 'd' && all[i+1] == 'a' && all[i+2] == 't' && all[i+3] == 'a') {
                dataOffset = i + 8;
                dataSize = (all[i+4] & 0xff) | ((all[i+5] & 0xff) << 8)
                        | ((all[i+6] & 0xff) << 16) | ((all[i+7] & 0xff) << 24);
                break;
            }
        }
        if (dataOffset < 0) throw new IOException("WAV no 'data' chunk");

        // 找 'fmt ' chunk 确认 sample rate / channels
        for (int i = 12; i < dataOffset - 8; i++) {
            if (all[i] == 'f' && all[i+1] == 'm' && all[i+2] == 't' && all[i+3] == ' ') {
                int fmtSize = (all[i+4] & 0xff) | ((all[i+5] & 0xff) << 8);
                if (fmtSize >= 16) {
                    channels = (all[i+10] & 0xff) | ((all[i+11] & 0xff) << 8);
                    sampleRate = (all[i+12] & 0xff) | ((all[i+13] & 0xff) << 8)
                            | ((all[i+14] & 0xff) << 16) | ((all[i+15] & 0xff) << 24);
                }
                break;
            }
        }

        if (sampleRate != 16000 || channels != 1) {
            throw new IOException("需要 16kHz mono,实际 " + sampleRate + "Hz/" + channels + "ch");
        }

        byte[] pcm = new byte[dataSize];
        System.arraycopy(all, dataOffset, pcm, 0, dataSize);
        return pcm;
    }
}