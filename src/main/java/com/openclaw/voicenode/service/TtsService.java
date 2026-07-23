package com.openclaw.voicenode.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.voicenode.config.TtsProps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * MiniMax T2A v2 直连服务 (P2)。
 *
 * 不走 OpenClaw gateway 的 tts.convert/tts.speak — 那个路径不成熟 (tts.speak 不存在,
 * tts.convert 返回 file path 不是 bytes,而且 30s timeout)。
 *
 * 直接 HTTP 调 MiniMax 国内 endpoint, hex 编码音频直接解码返回 byte[] (MP3/PCM)。
 *
 * 后续 P2:接在 AudioWebSocketHandler 的 turn.done 分支,合成 → base64 → 推浏览器。
 */
@Slf4j
@Service
public class TtsService {

    private final TtsProps props;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public TtsService(TtsProps props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 同步合成 text → audio bytes (MP3 默认)。
     *
     * @return MP3/PCM 字节 (由 props.format 决定)
     * @throws TtsException 鉴权失败 / 参数无效 / 服务端错误 / 超时
     */
    public byte[] synthesize(String text) {
        if (text == null || text.isBlank()) {
            throw new TtsException("text is empty");
        }

        String apiKey = System.getenv(props.apiKeyEnv());
        if (apiKey == null || apiKey.isBlank()) {
            throw new TtsException(
                    "env var " + props.apiKeyEnv() + " is not set (synthesize aborted)");
        }

        long t0 = System.currentTimeMillis();

        // 1. 构造请求体 (照搬 OpenClaw 那个契约)
        String requestBody;
        try {
            var body = mapper.createObjectNode();
            body.put("model", props.model());
            body.put("text", text);
            body.put("stream", false);
            body.put("output_format", "hex");

            var voiceSetting = mapper.createObjectNode();
            voiceSetting.put("voice_id", props.voiceId());
            voiceSetting.put("speed", props.speed());
            voiceSetting.put("vol", props.vol());
            voiceSetting.put("pitch", props.pitch());
            body.set("voice_setting", voiceSetting);

            var audioSetting = mapper.createObjectNode();
            audioSetting.put("format", props.format());
            audioSetting.put("sample_rate", props.sampleRate());
            body.set("audio_setting", audioSetting);

            requestBody = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new TtsException("build request body failed: " + e.getMessage(), e);
        }

        // 2. HTTP POST
        HttpRequest req;
        HttpResponse<String> resp;
        try {
            req = HttpRequest.newBuilder()
                    .uri(URI.create(props.baseUrl() + "/v1/t2a_v2"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new TtsException("HTTP call failed: " + e.getMessage(), e);
        }

        if (resp.statusCode() / 100 != 2) {
            throw new TtsException("HTTP " + resp.statusCode() + ": " + resp.body());
        }

        // 3. 解析响应:base_resp.status_code 必须为 0,data.audio 是 hex 编码
        try {
            JsonNode root = mapper.readTree(resp.body());

            JsonNode baseResp = root.path("base_resp");
            if (baseResp.isObject()) {
                int statusCode = baseResp.path("status_code").asInt(-1);
                if (statusCode != 0) {
                    String msg = baseResp.path("status_msg").asText("unknown");
                    throw new TtsException("MiniMax API error (" + statusCode + "): " + msg);
                }
            }

            String hex = root.path("data").path("audio").asText();
            if (hex == null || hex.isEmpty()) {
                throw new TtsException("MiniMax returned no audio data");
            }
            byte[] audio = hexToBytes(hex);

            long elapsed = System.currentTimeMillis() - t0;
            log.info("🔊 TTS synthesized {} chars → {} bytes ({}) in {}ms",
                    text.length(), audio.length, props.format(), elapsed);

            return audio;
        } catch (TtsException e) {
            throw e;
        } catch (Exception e) {
            throw new TtsException("parse response failed: " + e.getMessage(), e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        if ((len & 1) != 0) {
            throw new IllegalArgumentException("hex string length not even");
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("non-hex char at " + i);
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** TTS 失败的统一异常,handler 捕获后决定降级还是推错误给浏览器。 */
    public static class TtsException extends RuntimeException {
        public TtsException(String msg) { super(msg); }
        public TtsException(String msg, Throwable cause) { super(msg, cause); }
    }
}