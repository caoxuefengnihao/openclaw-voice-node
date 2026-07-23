package com.openclaw.voicenode.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.voicenode.config.TalkProps;
import com.openclaw.voicenode.config.VoiceNodeProperties;
import com.openclaw.voicenode.gateway.GatewayClient;
import com.openclaw.voicenode.gateway.KeyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 浏览器 ↔ Java ↔ OpenClaw Gateway 桥接。
 *
 * 模式：**Chat-proxy（STT/TTS 全在浏览器）**
 * - 浏览器用 Web Speech API 做 STT（识别）
 * - 浏览器用 SpeechSynthesis API 做 TTS（播音）
 * - Java 只是个 chat 代理：把浏览器识别的 text 转发给 Gateway chat.send
 *   再把 Gateway 返回的 agent response text 推回浏览器
 * - 整个链路无音频数据流，最快最稳
 *
 * 上行（浏览器 → Java）：
 *   { type: "text", content: "..." }  → 调 Gateway chat.send
 *   { type: "ping" }
 *
 * 下行（Java → 浏览器）：
 *   { type: "ready", sessionKey }
 *   { type: "assistant", text }        （agent response 流切片）
 *   { type: "turn.done" }              （回复完整，浏览器自己用 speechSynthesis 念）
 *   { type: "error", message }
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
    private static final String ATTR_BUFFER = "assistantBuffer";

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Browser WS connected: {}", session.getId());

        // 1. 连 Gateway（admin scope + cto Feishu sessionKey）
        GatewayClient gw = new GatewayClient(props, keyManager);
        gw.connect();

        // 2. 订阅 agent 事件
        gw.onEvent(msg -> {
            try {
                if (!"event".equals(msg.get("type"))) {
                    // 这里处理的是 res + event 都会走这里
                    String type = (String) msg.get("type");
                    if ("res".equals(type)) {
                        String id = (String) msg.get("id");
                        log.info("📥 收到 res: id={}, ok={}", id, msg.get("ok"));
                    }
                    return;
                }
                String eventName = (String) msg.get("event");
                Map<String, Object> payload = (Map<String, Object>) msg.get("payload");
                // 调试：先看前几个事件详情
                Integer evtCount = (Integer) session.getAttributes().get("evtCount");
                if (evtCount == null) evtCount = 0;
                evtCount++;
                session.getAttributes().put("evtCount", evtCount);
                if (evtCount <= 10) {
                    log.info("📨 event #{}: name={}, stream={}, data={}",
                            evtCount,
                            eventName,
                            payload == null ? "null" : payload.get("stream"),
                            payload == null ? "null" : payload.get("data"));
                }

                if (!"agent".equals(eventName) && !"chat".equals(eventName)) return;
                if (payload == null) return;

                String stream = (String) payload.get("stream");

                if ("assistant".equals(stream) || "response".equals(stream)) {
                    // agent event 的 LLM 响应文本切片
                    // 老 gateway 叫 "response"，新 gateway 改成了 "assistant"（OpenClaw
                    // 2026.7.x 起，看 src/infra/agent-events.ts 的 AgentEventStream 枚举）
                    // 用 delta 不要 text，避免重复发累积
                    Object data = payload.get("data");
                    String text = null;
                    if (data instanceof Map<?, ?> dm) {
                        Object delta = dm.get("delta");
                        Object txt = dm.get("text");
                        // 优先 delta（增量）；没有 delta 就用 text（兼容老格式）
                        if (delta instanceof String s && !s.isEmpty()) {
                            text = s;
                        } else if (txt instanceof String s2 && !s2.isEmpty()) {
                            text = s2;
                        }
                    } else if (data instanceof String s) {
                        text = s;
                    }
                    if (text != null && !text.isEmpty()) {
                        StringBuilder buf = (StringBuilder) session.getAttributes().get(ATTR_BUFFER);
                        if (buf != null) buf.append(text);
                        log.info("📝 响应 delta ({} chars): {}", text.length(),
                                text.length() > 50 ? text.substring(0, 50) + "..." : text);
                        sendToBrowser(session, Map.of("type", "assistant", "text", text));
                    }
                } else if ("done".equals(stream) || "end".equals(stream) || "complete".equals(stream)
                        || "end".equals(String.valueOf(payload.get("kind")))) {
                    // 老 gateway 的 done 信号 (向后兼容)
                    emitTurnDone(session);
                } else if ("lifecycle".equals(stream)) {
                    // 新 gateway (OpenClaw 2026.7.x): agent lifecycle 结束
                    // 看 src/agents/embedded-agent-subscribe.handlers.compaction.ts:154
                    // emitAgentEvent({ stream: "lifecycle", data: { phase: "end", ... } })
                    Object data = payload.get("data");
                    String phase = null;
                    if (data instanceof Map<?, ?> dm) {
                        Object p = dm.get("phase");
                        if (p instanceof String s) phase = s;
                    }
                    if ("end".equals(phase) || "error".equals(phase) || "stop".equals(phase)) {
                        emitTurnDone(session);
                    }
                } else if ("chat".equals(eventName)) {
                    // 新 gateway: chat 事件用 state 字段 (不是 stream)
                    // 看 packages/gateway-protocol/src/schema/logs-chat.ts 的 ChatFinalEventSchema
                    // state: "final" 表示一轮完成
                    Object state = payload.get("state");
                    if ("final".equals(state) || "aborted".equals(state) || "error".equals(state)) {
                        emitTurnDone(session);
                    }
                }
                // thinking 流不转发
            } catch (Exception e) {
                log.warn("处理 gateway event 失败: {}", e.getMessage(), e);
            }
        });

        session.getAttributes().put(ATTR_GATEWAY, gw);
        session.getAttributes().put(ATTR_BUFFER, new StringBuilder());

        // 3. 通知浏览器就绪
        sendToBrowser(session, Map.of("type", "ready", "sessionKey", props.sessionKey()));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        // chat-proxy 模式：完全不用二进制
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

        if ("text".equals(type)) {
            String content = (String) msg.get("content");
            if (content == null || content.isBlank()) return;
            GatewayClient gw = (GatewayClient) session.getAttributes().get(ATTR_GATEWAY);
            if (gw == null) return;

            // 重置 buffer
            StringBuilder buf = (StringBuilder) session.getAttributes().get(ATTR_BUFFER);
            if (buf != null) buf.setLength(0);

            log.info("📤 → Gateway chat.send: {}", content);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("sessionKey", props.sessionKey());
            params.put("message", content);
            params.put("idempotencyKey", UUID.randomUUID().toString());
            // cto agent 默认配的是 reasoning 模型 (gpt-5.2 / minimax-m2.5:cloud, 见
            // /Users/caoxuefeng/.openclaw/agents/cto/agent/models.json),会无限思考不出文本。
            // chat.send 的 thinking 字段 (schema 在 logs-chat.ts:84, 可选值 ["off","xhigh"])
            // 透传到 LLM 层 enable_thinking=false,临时关掉当次 reasoning。
            params.put("thinking", "off");
            gw.sendFireAndForget("chat.send", params);
        } else if ("ping".equals(type)) {
            sendToBrowser(session, Map.of("type", "pong"));
        } else {
            log.debug("未知 msg type: {}", type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Browser WS closed: {} ({})", session.getId(), status);
        GatewayClient gw = (GatewayClient) session.getAttributes().get(ATTR_GATEWAY);
        if (gw != null) gw.close();
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

    /**
     * 一轮回复结束的统一出口。
     * 重置 buffer 推送 turn.done 事件，让前端状态从 thinking 回到 ready（输入框解锁）。
     */
    private void emitTurnDone(WebSocketSession session) {
        StringBuilder buf = (StringBuilder) session.getAttributes().get(ATTR_BUFFER);
        int len = buf == null ? 0 : buf.length();
        if (buf != null) buf.setLength(0);
        log.info("✅ turn done, accumulated text length: {}", len);
        sendToBrowser(session, Map.of("type", "turn.done"));
    }
}
