package com.openclaw.voicenode.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.voicenode.service.ChatBridgeService;
import com.openclaw.voicenode.service.ChatSessionHandle;
import com.openclaw.voicenode.service.SttService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器 ↔ Java ↔ OpenClaw Gateway 桥接 (chat text + audio STT)。
 *
 * 上行(浏览器 → Java):
 *   chat: { type: "text", content }
 *   audio: { type: "audio.start", sampleRate, encoding }
 *          <binary PCM frame>           → 累积到 buffer
 *          { type: "audio.end" }         → SttService.recognize() 推 user.text
 *          { type: "audio.cancel" }      → 清空 buffer
 *   通用: { type: "ping" }
 *
 * 下行(Java → 浏览器):
 *   chat: { type: "ready" / "assistant" / "turn.done" }
 *   audio: { type: "audio.ack" / "user.text" / "error" }
 *
 * chat 协议细节在 ChatSessionHandle,STT 在 SttService,本 handler 只做 WS 路由。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private final ChatBridgeService chatBridge;
    private final SttService sttService;
    private final com.openclaw.voicenode.service.TtsService ttsService;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String ATTR_CHAT = "chat";
    private static final String ATTR_PCM_BUFFER = "pcmBuffer";
    private static final String ATTR_AUDIO_STATS = "audioStats";

    /**
     * 录音过程中的轻量调试统计。判断 mic 到底有没有录到东西:
     * - chunkCount > 10 且 maxAmp > 1000 → mic 正常,在话
     * - chunkCount > 10 但 maxAmp ≈ 0 → 浏览器发了 audio 但 mic 没拾到东西 (静音/权限问题)
     * - chunkCount < 5 → 太短或音频还没传到 STT
     */
    private static class AudioStats {
        int chunkCount = 0;
        long totalBytes = 0;
        int maxAmp = 0;  // max |int16 sample| 0..32767
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Browser WS connected: {}", session.getId());
        ChatSessionHandle chat = chatBridge.open(session);
        session.getAttributes().put(ATTR_CHAT, chat);

        // M2: turn.end 回调 → TTS 合成 → 推 assistant.audio
        // 让 audio.end 后端走的 STT 结果 → chat.sendText → turn.done 时同步触发 TTS
        chat.addTurnEndListener(fullText -> {
            try {
                if (fullText == null || fullText.isBlank()) return;
                log.info("🔊 M2 turn.done 触发 TTS 合 ({} chars)", fullText.length());
                byte[] audio = ttsService.synthesize(fullText);
                sendToBrowser(session, Map.of(
                        "type", "assistant.audio",
                        "audio", Base64.getEncoder().encodeToString(audio),
                        "format", "mp3"
                ));
            } catch (Exception e) {
                log.warn("M2 TTS 合成失败: {}", e.getMessage());
            }
        });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        // audio.chunk:浏览器发来的 PCM 帧 (16kHz mono int16 LE)
        // 累积到 per-session buffer,等 audio.end 时一起送 SttService
        ByteArrayOutputStream buf = (ByteArrayOutputStream) session.getAttributes().get(ATTR_PCM_BUFFER);
        if (buf == null) {
            // 没 audio.start 就直接发 binary,警告但丢弃
            log.warn("⚠️ 收到 binary 但无 audio.start,丢弃 {} bytes", message.getPayloadLength());
            return;
        }
        // 诊断统计: chunk count + bytes + max amplitude
        AudioStats stats = (AudioStats) session.getAttributes().get(ATTR_AUDIO_STATS);
        if (stats == null) {
            stats = new AudioStats();
            session.getAttributes().put(ATTR_AUDIO_STATS, stats);
        }
        byte[] payload = message.getPayload().array();
        stats.chunkCount++;
        stats.totalBytes += payload.length;
        // 高效采样 max amplitude (int16 LE) - 每 80 个样本检查一次
        for (int i = 0; i < payload.length - 1; i += 160) {
            short s = (short) ((payload[i] & 0xff) | ((payload[i + 1] & 0xff) << 8));
            int abs = s < 0 ? -s : s;
            if (abs > stats.maxAmp) stats.maxAmp = abs;
        }
        try {
            buf.write(payload);
        } catch (IOException e) {
            log.warn("写 PCM buffer 失败", e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg;
        try {
            msg = mapper.readValue(message.getPayload(), Map.class);
        } catch (Exception e) {
            log.warn("无法解析浏览器消息: {}", e.getMessage());
            return;
        }

        String type = (String) msg.get("type");
        log.info("📥 收到 msg: type={}", type);

        ChatSessionHandle chat = (ChatSessionHandle) session.getAttributes().get(ATTR_CHAT);

        if ("text".equals(type)) {
            if (chat == null) return;
            chat.sendText((String) msg.get("content"));
        } else if ("audio.start".equals(type)) {
            // 建空 buffer,准备接收 audio.chunk
            session.getAttributes().put(ATTR_PCM_BUFFER, new ByteArrayOutputStream());
            // 重置诊断统计(新录音清零)
            session.getAttributes().put(ATTR_AUDIO_STATS, new AudioStats());
            int sampleRate = ((Number) msg.getOrDefault("sampleRate", 16000)).intValue();
            String encoding = (String) msg.getOrDefault("encoding", "pcm_s16le");
            log.info("🎤 audio.start: sampleRate={}, encoding={}", sampleRate, encoding);
            sendToBrowser(session, Map.of("type", "audio.ack", "state", "recording"));
        } else if ("audio.end".equals(type)) {
            // 累积结束,送 STT
            ByteArrayOutputStream buf = (ByteArrayOutputStream) session.getAttributes().get(ATTR_PCM_BUFFER);
            if (buf == null || buf.size() == 0) {
                log.warn("⚠️ audio.end 但 buffer 空/缺失");
                sendToBrowser(session, Map.of("type", "error", "message", "audio buffer empty"));
                return;
            }
            byte[] pcm = buf.toByteArray();
            session.getAttributes().remove(ATTR_PCM_BUFFER);
            // 诊断统计 (在 STT 之前打,这样如果 STT 幻觉能看出是不是数据问题)
            AudioStats stats = (AudioStats) session.getAttributes().get(ATTR_AUDIO_STATS);
            session.getAttributes().remove(ATTR_AUDIO_STATS);
            log.info("🎤 audio.end: {} bytes PCM ({}ms @16kHz) stats={}",
                    pcm.length, pcm.length / 32,
                    stats != null
                        ? String.format("chunks=%d, bytes=%d, maxAmp=%d/32767",
                                stats.chunkCount, stats.totalBytes, stats.maxAmp)
                        : "(no stats — 可能浏览器没发 audio.chunk)");
            try {
                String text = sttService.recognize(pcm);
                sendToBrowser(session, Map.of(
                        "type", "user.text",
                        "text", text,
                        "isFinal", true
                ));

                // M2: STT 识别的文字当作 chat 输入,触发 cto 回复
                // turn.done 时上面的监听器会自动拿全文字调 TTS 推 assistant.audio
                if (chat != null && !text.isBlank()) {
                    log.info("📤 STT → chat.sendText: \"{}\"", text);
                    chat.sendText(text);
                }
            } catch (SttService.SttException e) {
                log.warn("STT 失败: {}", e.getMessage());
                sendToBrowser(session, Map.of("type", "error", "message", "STT failed: " + e.getMessage()));
            }
        } else if ("audio.cancel".equals(type)) {
            session.getAttributes().remove(ATTR_PCM_BUFFER);
            session.getAttributes().remove(ATTR_AUDIO_STATS);
            log.info("🎤 audio.cancel");
        } else if ("ping".equals(type)) {
            try {
                String json = mapper.writeValueAsString(Map.of("type", "pong"));
                session.sendMessage(new TextMessage(json));
            } catch (Exception e) {
                log.warn("send pong 失败: {}", e.getMessage());
            }
        } else {
            log.debug("未知 msg type: {}", type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Browser WS closed: {} ({})", session.getId(), status);
        ChatSessionHandle chat = (ChatSessionHandle) session.getAttributes().get(ATTR_CHAT);
        if (chat != null) chat.close();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Browser WS transport error", exception);
    }

    private void sendToBrowser(WebSocketSession session, Map<String, Object> data) {
        if (!session.isOpen()) return;
        try {
            // 用 LinkedHashMap 保 key 顺序(JSON 输出友好)
            String json = mapper.writeValueAsString(new LinkedHashMap<>(data));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("sendToBrowser 失败: {}", e.getMessage());
        }
    }
}