package com.openclaw.voicenode.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.voicenode.config.TalkProps;
import com.openclaw.voicenode.config.VoiceNodeProperties;
import com.openclaw.voicenode.gateway.GatewayClient;
import com.openclaw.voicenode.gateway.KeyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器 ↔ Java ↔ OpenClaw Gateway 的桥接。
 *
 * 每个 browser 连接 = 一个 GatewayClient + 一个 talk session。
 *
 * 浏览器 → Java:
 *   - Binary frame:  PCM Int16 16kHz mono（直接转发给 gateway 的 talk.session.appendAudio）
 *   - Text frame:    {cmd: "startTurn" | "endTurn" | "cancelTurn" | "close"}
 *
 * Java → 浏览器:
 *   - {type: "ready", sessionId}
 *   - {type: "transcript.delta", text}    中间识别文字
 *   - {type: "transcript.done", text}     最终识别文字
 *   - {type: "assistant", text}           assistant 回复文字
 *   - {type: "audio", data: "<b64>"}      TTS 音频帧
 *   - {type: "turn.done"}                 当前 turn 结束
 *   - {type: "error", message}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private final VoiceNodeProperties props;
    private final TalkProps talkProps;
    private final KeyManager keyManager;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String ATTR_GATEWAY = "gateway";
    private static final String ATTR_TALK_SESSION = "talkSessionId";

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Browser WS connected: {}", session.getId());

        // 1. 连 gateway
        GatewayClient gw = new GatewayClient(props, keyManager);
        gw.connect();

        // 2. 创建 talk session
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mode", talkProps.mode());
        params.put("transport", talkProps.transport());
        params.put("brain", talkProps.brain());
        params.put("sessionKey", props.sessionKey());

        Map<String, Object> resp = gw.sendRequest("talk.session.create", params, 30);
        Map<String, Object> payload = (Map<String, Object>) resp.get("payload");
        String talkSessionId = (String) payload.get("sessionId");
        log.info("Talk session created: {}", talkSessionId);

        // 3. 订阅 gateway 事件，按 sessionId 过滤后转发到浏览器
        gw.onEvent(msg -> {
            try {
                if (!"event".equals(msg.get("type"))) return;
                String eventName = (String) msg.get("event");
                if (!"talk.event".equals(eventName)) return;

                Map<String, Object> evPayload = (Map<String, Object>) msg.get("payload");
                if (evPayload == null) return;
                String sid = (String) evPayload.get("sessionId");
                if (sid == null || !sid.equals(talkSessionId)) return;

                // 翻译成浏览器友好的事件
                String type = (String) evPayload.get("type");
                Object data = evPayload.get("data");
                if (data instanceof Map<?, ?> dm) {
                    Map<String, Object> dataMap = (Map<String, Object>) dm;
                    switch (type == null ? "" : type) {
                        case "transcript.delta" -> sendToBrowser(session, Map.of(
                                "type", "transcript.delta",
                                "text", dataMap.getOrDefault("text", "")
                        ));
                        case "transcript.done" -> sendToBrowser(session, Map.of(
                                "type", "transcript.done",
                                "text", dataMap.getOrDefault("text", "")
                        ));
                        case "assistant.delta" -> sendToBrowser(session, Map.of(
                                "type", "assistant",
                                "text", dataMap.getOrDefault("text", "")
                        ));
                        case "assistant.done" -> sendToBrowser(session, Map.of(
                                "type", "assistant",
                                "text", dataMap.getOrDefault("text", ""),
                                "final", true
                        ));
                        case "audio" -> {
                            Object audio = dataMap.get("audio");
                            if (audio != null) {
                                sendToBrowser(session, Map.of(
                                        "type", "audio",
                                        "data", audio.toString()
                                ));
                            }
                        }
                        case "turn.done", "done" -> sendToBrowser(session, Map.of("type", "turn.done"));
                        default -> log.debug("未处理 talk event type={}", type);
                    }
                }
            } catch (Exception e) {
                log.warn("转发 talk event 失败: {}", e.getMessage());
            }
        });

        // 4. 存到 session attributes
        session.getAttributes().put(ATTR_GATEWAY, gw);
        session.getAttributes().put(ATTR_TALK_SESSION, talkSessionId);

        // 5. 通知浏览器准备好了
        sendToBrowser(session, Map.of(
                "type", "ready",
                "sessionId", talkSessionId,
                "transport", talkProps.transport(),
                "mode", talkProps.mode()
        ));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        GatewayClient gw = (GatewayClient) session.getAttributes().get(ATTR_GATEWAY);
        String talkSessionId = (String) session.getAttributes().get(ATTR_TALK_SESSION);
        if (gw == null || talkSessionId == null) {
            log.warn("收到 PCM 但 talk session 未就绪");
            return;
        }

        ByteBuffer buf = message.getPayload();
        byte[] pcm = new byte[buf.remaining()];
        buf.get(pcm);
        String base64 = Base64.getEncoder().encodeToString(pcm);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", talkSessionId);
        params.put("audio", base64);
        gw.sendFireAndForget("talk.session.appendAudio", params);
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

        GatewayClient gw = (GatewayClient) session.getAttributes().get(ATTR_GATEWAY);
        String talkSessionId = (String) session.getAttributes().get(ATTR_TALK_SESSION);
        if (gw == null || talkSessionId == null) return;

        String cmd = (String) msg.get("cmd");
        if (cmd == null) return;

        switch (cmd) {
            case "startTurn" -> gw.sendFireAndForget("talk.session.startTurn", Map.of("sessionId", talkSessionId));
            case "endTurn" -> gw.sendFireAndForget("talk.session.endTurn", Map.of("sessionId", talkSessionId));
            case "cancelTurn" -> gw.sendFireAndForget("talk.session.cancelTurn", Map.of("sessionId", talkSessionId));
            case "ping" -> sendToBrowser(session, Map.of("type", "pong"));
            default -> log.debug("未知 cmd: {}", cmd);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Browser WS closed: {} ({})", session.getId(), status);
        GatewayClient gw = (GatewayClient) session.getAttributes().get(ATTR_GATEWAY);
        String talkSessionId = (String) session.getAttributes().get(ATTR_TALK_SESSION);

        if (gw != null && talkSessionId != null) {
            try {
                gw.sendFireAndForget("talk.session.close", Map.of("sessionId", talkSessionId));
            } catch (Exception ignore) {
            }
        }
        if (gw != null) {
            gw.close();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Browser WS transport error", exception);
    }

    private void sendToBrowser(WebSocketSession session, Map<String, Object> data) {
        if (!session.isOpen()) return;
        try {
            String json = mapper.writeValueAsString(data);
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("sendToBrowser 失败: {}", e.getMessage());
        }
    }
}
