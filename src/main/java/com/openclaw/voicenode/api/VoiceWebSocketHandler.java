package com.openclaw.voicenode.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.voicenode.service.ChatBridgeService;
import com.openclaw.voicenode.service.ChatSessionHandle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.util.Map;

/**
 * 浏览器 ↔ Java ↔ OpenClaw Gateway 桥接 (chat text 模式)。
 *
 * 模式:**Text-chat-proxy**
 * - 浏览器发送文字 → Java 转 chat.send → cto agent
 * - agent 回复通过 gateway events 流式推回浏览器
 * - STT/TTS 不在这条链路 (P1/P2 会在新端点 /ws/voice 里加)
 *
 * 上行(浏览器 → Java):
 *   { type: "text", content: "..." }  → ChatBridgeService.sendText()
 *   { type: "ping" }
 *
 * 下行(Java → 浏览器):
 *   { type: "ready", sessionKey }
 *   { type: "assistant", text }
 *   { type: "turn.done" }
 *
 * 本 handler 只做 WS 协议转换,gateway 协议细节全部在 ChatSessionHandle 里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private final ChatBridgeService chatBridge;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String ATTR_CHAT = "chat";

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Browser WS connected: {}", session.getId());
        ChatSessionHandle chat = chatBridge.open(session);
        session.getAttributes().put(ATTR_CHAT, chat);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        // chat text 模式不用二进制。audio 协议在 P1 的 /ws/voice 端点里。
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
            String content = (String) msg.get("content");
            chat.sendText(content);
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
}