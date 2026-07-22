package com.openclaw.voicenode.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.voicenode.config.VoiceNodeProperties;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * WebSocket 客户端 → OpenClaw Gateway。
 *
 * 一对一对应 custom_node.py 的 GatewayClient：
 * - Ed25519 设备认证 (challenge → sign → connect)
 * - 请求-响应派发（pending map）
 * - 事件订阅（eventListeners）
 *
 * 注意：当前实现每个 browser 连接 = 一个 gateway 连接，简单但浪费。
 * 后续可优化为单一 gateway 连接 + 按 sessionId 多路复用。
 */
@Slf4j
public class GatewayClient {

    private final VoiceNodeProperties props;
    private final KeyManager keyManager;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<Map<String, Object>>> eventListeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger reqCounter = new AtomicInteger();

    private WebSocketClient ws;
    private volatile boolean connected = false;

    public GatewayClient(VoiceNodeProperties props, KeyManager keyManager) {
        this.props = props;
        this.keyManager = keyManager;
    }

    /**
     * 同步连接 + 鉴权（阻塞到 hello-ok 或超时）
     */
    public synchronized void connect() throws Exception {
        if (connected) return;

        CountDownLatch helloLatch = new CountDownLatch(1);
        AtomicReference<Map<String, Object>> helloResult = new AtomicReference<>();
        AtomicReference<Exception> helloError = new AtomicReference<>();

        ws = new WebSocketClient(new URI(props.gateway().url())) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                log.info("WS opened, waiting challenge...");
            }

            @Override
            public void onMessage(String message) {
                try {
                    Map<String, Object> msg = mapper.readValue(message, Map.class);
                    String type = (String) msg.get("type");
                    String id = (String) msg.get("id");
                    String event = (String) msg.get("event");

                    if ("res".equals(type) && id != null) {
                        CompletableFuture<Map<String, Object>> f = pending.remove(id);
                        if (f != null) f.complete(msg);
                    } else if ("event".equals(type)) {
                        if ("connect.challenge".equals(event)) {
                            handleChallenge((Map<String, Object>) msg.get("payload"));
                        } else if (id != null && id.startsWith("connect-")) {
                            // hello-ok 走 pending 派发
                            CompletableFuture<Map<String, Object>> f = pending.remove(id);
                            if (f != null) f.complete(msg);
                        }
                        for (var l : eventListeners) {
                            try { l.accept(msg); } catch (Exception e) { log.warn("event listener error", e); }
                        }
                    }
                } catch (Exception e) {
                    log.error("parse error: {}", message, e);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.warn("WS closed: {} {}", code, reason);
                connected = false;
            }

            @Override
            public void onError(Exception ex) {
                log.error("WS error", ex);
            }
        };

        // 监听 hello-ok
        onEvent(msg -> {
            if (msg.get("id") instanceof String idStr && idStr.startsWith("connect-")) {
                if (Boolean.TRUE.equals(msg.get("ok"))) {
                    helloResult.set(msg);
                } else {
                    helloError.set(new RuntimeException("hello failed: " + msg.get("error")));
                }
                helloLatch.countDown();
            }
        });

        ws.connectBlocking(10, TimeUnit.SECONDS);
        log.info("WS connected, sending connect...");

        // 主动发 connect（因为我们已经在 onMessage 里处理 challenge，但保险起见这里也发）
        // 实际 challenge 已经在 onMessage 里被处理了，这里只等 hello
        if (!helloLatch.await(15, TimeUnit.SECONDS)) {
            ws.closeBlocking();
            throw new RuntimeException("hello-ok timeout");
        }
        if (helloError.get() != null) throw helloError.get();

        Map<String, Object> hello = helloResult.get();
        log.info("✅ connected to gateway: {}", hello.get("payload"));

        // 保存 deviceToken（如果返回了新的）
        try {
            Map<String, Object> payload = (Map<String, Object>) hello.get("payload");
            if (payload != null) {
                Map<String, Object> auth = (Map<String, Object>) payload.get("auth");
                if (auth != null) {
                    String newToken = (String) auth.get("deviceToken");
                    if (newToken != null && !newToken.equals(keyManager.getDeviceToken())) {
                        keyManager.saveDeviceToken(newToken);
                        log.info("新 deviceToken 已保存");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("保存 deviceToken 失败: {}", e.getMessage());
        }

        // 检查配对
        Map<String, Object> payload = (Map<String, Object>) hello.get("payload");
        Map<String, Object> auth = payload != null ? (Map<String, Object>) payload.get("auth") : null;
        if (auth != null && (!"operator".equals(auth.get("role")) || auth.get("deviceToken") == null)) {
            log.warn("\n" + "=".repeat(60));
            log.warn("⚠️  需要配对！在 Mac mini 上执行：");
            log.warn("    openclaw devices list");
            log.warn("   找到 id 包含 '{}' 的设备后：", keyManager.getDeviceId().substring(0, 16));
            log.warn("    openclaw devices approve <request-id>");
            log.warn("=".repeat(60) + "\n");
            throw new RuntimeException("device not paired");
        }

        connected = true;
    }

    @SuppressWarnings("unchecked")
    private void handleChallenge(Map<String, Object> payload) {
        try {
            String nonce = (String) payload.get("nonce");
            long signedAt = System.currentTimeMillis();
            String authPayload = keyManager.buildAuthPayload(signedAt, props.gateway().token(), nonce);
            String signature = keyManager.sign(authPayload);

            Map<String, Object> device = new LinkedHashMap<>();
            device.put("id", keyManager.getDeviceId());
            device.put("publicKey", keyManager.getPublicKeyB64Url());
            device.put("signature", signature);
            device.put("signedAt", signedAt);
            device.put("nonce", nonce);

            Map<String, Object> auth = new LinkedHashMap<>();
            auth.put("token", props.gateway().token());
            if (keyManager.getDeviceToken() != null) {
                auth.put("deviceToken", keyManager.getDeviceToken());
            }

            Map<String, Object> client = new LinkedHashMap<>();
            client.put("id", props.gateway().clientId());
            client.put("version", "0.1.0");
            client.put("platform", System.getProperty("os.name"));
            client.put("mode", props.gateway().clientMode());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("minProtocol", 4);
            params.put("maxProtocol", 4);
            params.put("client", client);
            params.put("role", props.gateway().role());
            params.put("scopes", Arrays.asList(props.gateway().scopes().split(",")));
            params.put("auth", auth);
            params.put("device", device);

            sendRaw(buildRequestWithId("connect-" + System.currentTimeMillis(), "connect", params));
        } catch (Exception e) {
            log.error("handleChallenge failed", e);
        }
    }

    /**
     * 同步发请求，等响应（带超时）
     */
    public Map<String, Object> sendRequest(String method, Map<String, Object> params, int timeoutSec) {
        if (!connected) throw new IllegalStateException("not connected");
        String id = "req-" + reqCounter.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);

        sendRaw(buildRequestWithId(id, method, params));

        try {
            Map<String, Object> resp = future.get(timeoutSec, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(resp.get("ok"))) {
                throw new RuntimeException(method + " failed: " + resp.get("error"));
            }
            return resp;
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new RuntimeException(method + " timed out", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            throw new RuntimeException(method + " failed", e);
        }
    }

    /**
     * 异步发请求（不阻塞、不等响应）—— 适合 appendAudio 这种高频操作
     */
    public void sendFireAndForget(String method, Map<String, Object> params) {
        if (!connected) return;
        String id = "req-" + reqCounter.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            sendRaw(buildRequestWithId(id, method, params));
        } catch (Exception e) {
            log.warn("send {} failed: {}", method, e.getMessage());
        }
    }

    private String buildRequestWithId(String id, String method, Map<String, Object> params) {
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("type", "req");
            req.put("id", id);
            req.put("method", method);
            req.put("params", params);
            return mapper.writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException("build request failed", e);
        }
    }

    private void sendRaw(String json) {
        if (ws != null && ws.isOpen()) {
            ws.send(json);
        }
    }

    public void onEvent(Consumer<Map<String, Object>> listener) {
        eventListeners.add(listener);
    }

    public boolean isConnected() {
        return connected;
    }

    public void close() {
        connected = false;
        try {
            if (ws != null) ws.closeBlocking();
        } catch (Exception ignore) {
        }
    }
}
