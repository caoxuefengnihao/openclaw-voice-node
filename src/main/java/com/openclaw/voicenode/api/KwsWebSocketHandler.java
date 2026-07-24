package com.openclaw.voicenode.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.voicenode.service.ChatBridgeService;
import com.openclaw.voicenode.service.ChatSessionHandle;
import com.openclaw.voicenode.service.KwsService;
import com.openclaw.voicenode.service.SttService;
import com.openclaw.voicenode.service.TtsService;
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
import java.util.List;
import java.util.Map;

/**
 * KWS 独立 WS Handler (v3 新增),挂 {@code /ws/kws} 端点。
 *
 * <p><b>完全独立于 {@link VoiceWebSocketHandler}</b>,不碰现有 v2 代码。
 *
 * <p>两种模式(由 session attribute {@code mode} 区分):
 * <ul>
 *   <li><b>KWS 监听模式 ({@code mode="kws"})</b>: binary 帧送 {@link KwsService#acceptFrame},
 *       检测到唤醒词 -> 发 {@code wake.detected} 事件,自动切到录音模式</li>
 *   <li><b>录音对话模式 ({@code mode="recording"})</b>: binary 帧累积到 buffer,
 *       {@code audio.end} 时 -> 调 {@link SttService#recognize} -> 调
 *       {@link ChatBridgeService#open} 开 session -> 调
 *       {@link ChatSessionHandle#sendText} 发文本给 gateway ->
 *       turn.done 时调 {@link TtsService#synthesize} 发 {@code assistant.audio}</li>
 * </ul>
 *
 * <p>复用的现有 service (只调 public 方法,不改):
 * <ul>
 *   <li>{@link SttService#recognize(byte[])} — PCM 转文本</li>
 *   <li>{@link TtsService#synthesize(String)} — 文本转 MP3</li>
 *   <li>{@link ChatBridgeService#open(WebSocketSession)} — 开 chat session</li>
 *   <li>{@link ChatSessionHandle#sendText(String)} — 发给 gateway</li>
 *   <li>{@link ChatSessionHandle#addTurnEndListener(java.util.function.Consumer)} — 回复结束回调</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KwsWebSocketHandler extends AbstractWebSocketHandler {

    private final KwsService kwsService;
    private final SttService sttService;          // 复用现有
    private final TtsService ttsService;          // 复用现有
    private final ChatBridgeService chatBridge;   // 复用现有
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String ATTR_CHAT = "chat";
    private static final String ATTR_PCM_BUFFER = "pcmBuffer";
    private static final String ATTR_MODE = "mode";   // "kws" | "recording"

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("KWS WS connected: {}", session.getId());

        // 复用现有 ChatBridgeService 开 chat session
        ChatSessionHandle chat = chatBridge.open(session);
        session.getAttributes().put(ATTR_CHAT, chat);

        // 注册 turn.end 回调 -> 调现有 TtsService
        chat.addTurnEndListener(fullText -> {
            try {
                if (fullText == null || fullText.isBlank()) return;
                log.info("🔊 KWS turn.done -> TTS ({} chars)", fullText.length());
                byte[] audio = ttsService.synthesize(fullText);  // 调现有方法
                sendToBrowser(session, Map.of(
                        "type", "assistant.audio",
                        "audio", Base64.getEncoder().encodeToString(audio),
                        "format", "mp3"
                ));
            } catch (Exception e) {
                log.warn("KWS TTS 失败: {}", e.getMessage());
            }
        });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String mode = (String) session.getAttributes().get(ATTR_MODE);
        byte[] payload = message.getPayload().array();

        if ("kws".equals(mode)) {
            // KWS 模式:每帧过 KwsService
            String keyword = kwsService.acceptFrame(session.getId(), payload);
            if (!keyword.isEmpty()) {
                log.info("🔥 Wake detected: session={}, keyword={}", session.getId(), keyword);
                // 命中 -> 停 KWS,发 wake.detected,等前端切到录音模式
                kwsService.stopSession(session.getId());
                session.getAttributes().remove(ATTR_MODE);
                sendToBrowser(session, Map.of(
                        "type", "wake.detected",
                        "keyword", keyword,
                        "timestamp", System.currentTimeMillis()
                ));
            }
        } else {
            // 录音模式或其他:累积到 buffer
            ByteArrayOutputStream buf = (ByteArrayOutputStream) session.getAttributes().get(ATTR_PCM_BUFFER);
            if (buf == null) {
                log.warn("⚠️ KWS Handler 收到 binary 但无 audio.start,丢弃 {} bytes", payload.length);
                return;
            }
            try {
                buf.write(payload);
            } catch (IOException e) {
                log.warn("写 PCM buffer 失败", e);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg;
        try {
            msg = mapper.readValue(message.getPayload(), Map.class);
        } catch (Exception e) {
            log.warn("无法解析 KWS 消息: {}", e.getMessage());
            return;
        }

        String type = (String) msg.get("type");
        log.info("📥 KWS msg: type={}", type);

        ChatSessionHandle chat = (ChatSessionHandle) session.getAttributes().get(ATTR_CHAT);

        if ("audio.kws.start".equals(type)) {
            // 进入 KWS 监听模式
            kwsService.startSession(session.getId());
            session.getAttributes().put(ATTR_MODE, "kws");
            sendToBrowser(session, Map.of(
                    "type", "kws.ack",
                    "state", "listening",
                    "keywords", kwsService.keywords()
            ));

        } else if ("audio.kws.stop".equals(type)) {
            kwsService.stopSession(session.getId());
            session.getAttributes().remove(ATTR_MODE);

        } else if ("audio.start".equals(type)) {
            // 切到录音模式 (唤醒后前端发这个)
            session.getAttributes().put(ATTR_MODE, "recording");
            session.getAttributes().put(ATTR_PCM_BUFFER, new ByteArrayOutputStream());
            int sampleRate = ((Number) msg.getOrDefault("sampleRate", 16000)).intValue();
            String encoding = (String) msg.getOrDefault("encoding", "pcm_s16le");
            log.info("🎤 KWS audio.start: sampleRate={}, encoding={}", sampleRate, encoding);
            sendToBrowser(session, Map.of("type", "audio.ack", "state", "recording"));

        } else if ("audio.end".equals(type)) {
            // 累积结束 -> STT -> chat -> (TTS 在 turn.done 回调里)
            ByteArrayOutputStream buf = (ByteArrayOutputStream) session.getAttributes().get(ATTR_PCM_BUFFER);
            if (buf == null || buf.size() == 0) {
                sendToBrowser(session, Map.of("type", "error", "message", "audio buffer empty"));
                return;
            }
            byte[] pcm = buf.toByteArray();
            session.getAttributes().remove(ATTR_PCM_BUFFER);
            session.getAttributes().remove(ATTR_MODE);
            log.info("🎤 KWS audio.end: {} bytes PCM ({}ms @16kHz)",
                    pcm.length, pcm.length / 32);
            try {
                // 调现有 SttService
                String text = sttService.recognize(pcm);
                sendToBrowser(session, Map.of(
                        "type", "user.text",
                        "text", text,
                        "isFinal", true
                ));
                // 调现有 ChatSessionHandle
                if (chat != null && !text.isBlank()) {
                    log.info("📤 KWS STT -> chat.sendText: \"{}\"", text);
                    chat.sendText(text);
                }
            } catch (SttService.SttException e) {
                log.warn("KWS STT 失败: {}", e.getMessage());
                sendToBrowser(session, Map.of("type", "error", "message", "STT failed: " + e.getMessage()));
            }

        } else if ("audio.cancel".equals(type)) {
            session.getAttributes().remove(ATTR_PCM_BUFFER);
            session.getAttributes().remove(ATTR_MODE);
            log.info("🎤 KWS audio.cancel");

        } else if ("ping".equals(type)) {
            try {
                String json = mapper.writeValueAsString(Map.of("type", "pong"));
                session.sendMessage(new TextMessage(json));
            } catch (Exception e) {
                log.warn("KWS send pong 失败", e);
            }

        } else {
            log.debug("KWS 未知 msg type: {}", type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("KWS WS closed: {} ({})", session.getId(), status);
        kwsService.stopSession(session.getId());
        ChatSessionHandle chat = (ChatSessionHandle) session.getAttributes().get(ATTR_CHAT);
        if (chat != null) chat.close();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("KWS WS transport error", exception);
    }

    private void sendToBrowser(WebSocketSession session, Map<String, Object> data) {
        if (!session.isOpen()) return;
        try {
            String json = mapper.writeValueAsString(new LinkedHashMap<>(data));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("KWS sendToBrowser 失败: {}", e.getMessage());
        }
    }
}