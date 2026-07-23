package com.openclaw.voicenode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MiniMax T2A v2 直连配置 (P2 用,绕过 gateway)。
 *
 * API 契约来自 OpenClaw minimax 插件的 dist:
 * /Volumes/ssd/nodejs/node-v26.5.0-darwin-arm64/lib/node_modules/openclaw/dist/tts-BJz6m5ah.js
 *
 * 端点:POST {baseUrl}/v1/t2a_v2
 * 鉴权:Authorization: Bearer <apiKey>
 * 请求体:{ model, text, stream:false, output_format:"hex",
 *          voice_setting:{ voice_id, speed, vol, pitch },
 *          audio_setting:{ format, sample_rate } }
 * 响应:{ base_resp:{status_code,...}, data:{ audio:"<hex>" } }
 */
@ConfigurationProperties(prefix = "openclaw.tts")
public record TtsProps(
        String baseUrl,           // "https://api.minimaxi.com"
        String apiKeyEnv,          // "TTS_API_KEY" (env var 名,不存明文)
        String model,              // "speech-2.8-hd"
        String voiceId,            // "English_expressive_narrator"
        String format,             // "mp3"
        int sampleRate,            // 32000
        float speed,               // 1.0
        float vol,                 // 1.0
        int pitch                  // 0
) {
    public TtsProps {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.minimaxi.com";
        }
        if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
            apiKeyEnv = "TTS_API_KEY";
        }
        if (model == null || model.isBlank()) {
            model = "speech-2.8-hd";
        }
        if (voiceId == null || voiceId.isBlank()) {
            voiceId = "English_expressive_narrator";
        }
        if (format == null || format.isBlank()) {
            format = "mp3";
        }
        if (sampleRate <= 0) {
            sampleRate = 32000;
        }
        if (speed <= 0f) {
            speed = 1.0f;
        }
        if (vol <= 0f) {
            vol = 1.0f;
        }
    }
}