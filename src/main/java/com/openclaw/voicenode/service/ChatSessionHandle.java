package com.openclaw.voicenode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.voicenode.gateway.GatewayClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单个浏览器 WS 的 chat 会话句柄。
 *
 * 封装:
 * - 一个 GatewayClient 连接 (P0: 1 browser WS = 1 gateway WS,后续可优化复用)
 * - agent/chat 事件 → 浏览器事件的过滤 + 转换
 * - assistant buffer (累加回复文本)
 *
 * 设计目标: handler 完全不感知 gateway 协议,只调 sendText() / close()。
 * 所有 gateway 协议适配 (event 名 / state / phase) 都收敛在这一个文件里。
 *
 * 协议契约速查:
 * - ChatSendParamsSchema: /Volumes/ssd/openclaw/packages/gateway-protocol/src/schema/logs-chat.ts:78
 *   additionalProperties: false,白名单外字段会被静默拒绝
 * - AgentEventStream: /Volumes/ssd/openclaw/src/infra/agent-events.ts
 *   流名: lifecycle / thinking / assistant / tool / error / item / plan ...
 * - ChatEventSchema: 同上 logs-chat.ts,state 字段 (不是 stream): delta / final / aborted / error
 */
@Slf4j
public class ChatSessionHandle {

    private final WebSocketSession browser;
    private final GatewayClient gateway;
    private final String sessionKey;
    private final ObjectMapper mapper = new ObjectMapper();

    private final StringBuilder assistantBuffer = new StringBuilder();

    /**
     * turn.done 回调接口。音频端点订阅这个用来在 turn 走完后调 TTS 合成回放。
     * 多调用者安全：任意多个 listener 同时收事件。
     */
    @FunctionalInterface
    public interface TurnEndListener {
        void onTurnEnd(String fullAssistantText);
    }

    private final List<TurnEndListener> turnEndListeners = new CopyOnWriteArrayList<>();

    /**
     * 注册 turn.end 回调。一般在 handler / afterConnectionEstablished 调一次。
     */
    public void addTurnEndListener(TurnEndListener l) { turnEndListeners.add(l); }

    /**
     * 移除 turn.end 回调(连接断开清理时用)。
     */
    public void removeTurnEndListener(TurnEndListener l) { turnEndListeners.remove(l); }

    // 性能时间打点 (调试 chat 延迟用)
    private long chatSendAtMs = 0L;       // sendText() 调用时间
    private long firstDeltaAtMs = 0L;     // 第一个 assistant delta 到达时间

    public ChatSessionHandle(WebSocketSession browser, GatewayClient gateway, String sessionKey) {
        this.browser = browser;
        this.gateway = gateway;
        this.sessionKey = sessionKey;
        // 订阅 gateway 事件,所有过滤/转换在这里完成
        gateway.onEvent(this::handleGatewayEvent);
    }

    /**
     * 浏览器发了 text 消息 → 转 chat.send 到 cto agent。
     * cto 默认配 reasoning 模型 (gpt-5.2 / minimax-m2.5:cloud),加 thinking: "off" 关掉。
     */
    public void sendText(String text) {
        if (text == null || text.isBlank()) return;
        // 重置本轮 buffer + 时间打点
        assistantBuffer.setLength(0);
        chatSendAtMs = System.currentTimeMillis();
        firstDeltaAtMs = 0L;

        log.info("📤 → Gateway chat.send: {}", text);
        log.info("⏱️ t=0ms  chat.send fired: \"{}\"", text.length() > 30 ? text.substring(0, 30) + "..." : text);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionKey", sessionKey);
        params.put("message", text);
        params.put("idempotencyKey", UUID.randomUUID().toString());
        // cto agent 默认配的是 reasoning 模型 (gpt-5.2 / minimax-m2.5:cloud, 见
        // /Users/caoxuefeng/.openclaw/agents/cto/agent/models.json),会无限思考不出文本。
        // chat.send 的 thinking 字段 (schema 在 logs-chat.ts:84, 可选值 ["off","xhigh"])
        // 透传到 LLM 层 enable_thinking=false,临时关掉当次 reasoning。
        params.put("thinking", "off");
        gateway.sendFireAndForget("chat.send", params);
    }

    /**
     * 关闭会话 (浏览器 WS 断开时调)。
     */
    public void close() {
        gateway.close();
    }

    /**
     * 通知浏览器 gateway 已 ready,带上 sessionKey。
     */
    public void notifyReady() {
        sendToBrowser(Map.of("type", "ready", "sessionKey", sessionKey));
    }

    // ============== 私有:gateway 事件处理 ==============

    private void handleGatewayEvent(Map<String, Object> msg) {
        try {
            String type = (String) msg.get("type");
            if (!"event".equals(type)) {
                if ("res".equals(type)) {
                    log.info("📥 收到 res: id={}, ok={}", msg.get("id"), msg.get("ok"));
                }
                return;
            }

            String eventName = (String) msg.get("event");
            Map<String, Object> payload = (Map<String, Object>) msg.get("payload");

            // 调试:前 10 个事件打详情
            Integer evtCount = (Integer) browser.getAttributes().get("evtCount");
            if (evtCount == null) evtCount = 0;
            evtCount++;
            browser.getAttributes().put("evtCount", evtCount);
            if (evtCount <= 10) {
                log.info("📨 event #{}: name={}, stream={}, data={}",
                        evtCount,
                        eventName,
                        payload == null ? "null" : payload.get("stream"),
                        payload == null ? "null" : payload.get("data"));
            }

            // 只关心 agent / chat 事件
            if (!"agent".equals(eventName) && !"chat".equals(eventName)) return;
            if (payload == null) return;

            String stream = (String) payload.get("stream");

            // 路径 1: agent 事件 + stream=assistant (新) 或 stream=response (老) → 文本切片
            if ("assistant".equals(stream) || "response".equals(stream)) {
                String text = extractAssistantDelta(payload);
                if (text != null && !text.isEmpty()) {
                    assistantBuffer.append(text);
                    // 记录首个 delta 时间 (后续 delta 不重复记录)
                    if (firstDeltaAtMs == 0L && chatSendAtMs > 0L) {
                        firstDeltaAtMs = System.currentTimeMillis();
                        long latency = firstDeltaAtMs - chatSendAtMs;
                        log.info("⏱️ t={}ms  first assistant delta ({} chars, latency from chat.send: {}ms)",
                                latency, text.length(), latency);
                    }
                    log.info("📝 响应 delta ({} chars): {}",
                            text.length(),
                            text.length() > 50 ? text.substring(0, 50) + "..." : text);
                    sendToBrowser(Map.of("type", "assistant", "text", text));
                }
                return;
            }

            // 路径 2: 老 gateway 的 done 信号 (向后兼容)
            if ("done".equals(stream) || "end".equals(stream) || "complete".equals(stream)
                    || "end".equals(String.valueOf(payload.get("kind")))) {
                emitTurnDone();
                return;
            }

            // 路径 3: 新 gateway (OpenClaw 2026.7.x) agent lifecycle 结束
            // emitAgentEvent({ stream: "lifecycle", data: { phase: "end", ... } })
            if ("lifecycle".equals(stream)) {
                String phase = extractStringField(payload, "data", "phase");
                if ("end".equals(phase) || "error".equals(phase) || "stop".equals(phase)) {
                    emitTurnDone();
                }
                return;
            }

            // 路径 4: 新 gateway chat 事件,用 state 字段 (不是 stream)
            // ChatFinalEventSchema.state = "final" / "aborted" / "error"
            if ("chat".equals(eventName)) {
                Object state = payload.get("state");
                if ("final".equals(state) || "aborted".equals(state) || "error".equals(state)) {
                    emitTurnDone();
                }
                return;
            }

            // thinking 流 / 其他流不转发
        } catch (Exception e) {
            log.warn("处理 gateway event 失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 提取 agent 事件里的 assistant 文本 delta。
     * 优先 data.delta (增量),回退 data.text (整段累积)。
     */
    private String extractAssistantDelta(Map<String, Object> payload) {
        Object data = payload.get("data");
        if (data instanceof Map<?, ?> dm) {
            Object delta = dm.get("delta");
            if (delta instanceof String s && !s.isEmpty()) return s;
            Object txt = dm.get("text");
            if (txt instanceof String s2 && !s2.isEmpty()) return s2;
        } else if (data instanceof String s) {
            return s;
        }
        return null;
    }

    /**
     * 从嵌套 Map 里拿字符串字段: payload.data.phase 这种。
     */
    private String extractStringField(Map<String, Object> payload, String... keys) {
        Object cur = payload;
        for (String key : keys) {
            if (!(cur instanceof Map<?, ?>)) return null;
            cur = ((Map<?, ?>) cur).get(key);
        }
        return cur instanceof String s ? s : null;
    }

    private void emitTurnDone() {
        String fullText = assistantBuffer.toString();  // 先抓,再 clear
        int len = fullText.length();
        assistantBuffer.setLength(0);
        long nowMs = System.currentTimeMillis();
        long totalMs = (chatSendAtMs > 0L) ? (nowMs - chatSendAtMs) : -1L;
        if (totalMs >= 0) {
            log.info("✅ turn done, total {}ms (from chat.send to turn.done, accumulated text {} chars)",
                    totalMs, len);
        } else {
            log.info("✅ turn done, accumulated text length: {}", len);
        }
        sendToBrowser(Map.of("type", "turn.done"));

        // 邀请所有 listener(M2 音频端点订阅这里调 TTS)
        if (!turnEndListeners.isEmpty()) {
            for (TurnEndListener l : turnEndListeners) {
                try {
                    l.onTurnEnd(fullText);
                } catch (Exception e) {
                    log.warn("turnEndListener invoke failed: {}", e.getMessage());
                }
            }
        }
    }

    private void sendToBrowser(Map<String, Object> data) {
        if (!browser.isOpen()) return;
        try {
            String json = mapper.writeValueAsString(data);
            browser.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("sendToBrowser 失败: {}", e.getMessage());
        }
    }
}