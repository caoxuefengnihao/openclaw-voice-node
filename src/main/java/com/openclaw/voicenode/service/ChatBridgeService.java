package com.openclaw.voicenode.service;

import com.openclaw.voicenode.config.VoiceNodeProperties;
import com.openclaw.voicenode.gateway.GatewayClient;
import com.openclaw.voicenode.gateway.KeyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

/**
 * Chat bridge 工厂:为每个浏览器 WS 创建一个 ChatSessionHandle。
 *
 * 一个 handler 一个 service 实例,handler 完全不感知 gateway 协议细节。
 *
 * 当前实现:1 个 browser WS = 1 个 gateway WS (简单但浪费连接)。
 * 后续优化:共享 gateway WS,按 sessionId 多路复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatBridgeService {

    private final VoiceNodeProperties props;
    private final KeyManager keyManager;

    /**
     * 给浏览器 WS 开一个 chat 会话:建 gateway 连接 + 订阅事件 + 通知浏览器 ready。
     */
    public ChatSessionHandle open(WebSocketSession browserSession) throws Exception {
        log.info("Chat bridge opening for browser session: {}", browserSession.getId());

        GatewayClient gw = new GatewayClient(props, keyManager);
        gw.connect();

        ChatSessionHandle handle = new ChatSessionHandle(
                browserSession, gw, props.sessionKey());

        // 浏览器 WS 收到 ready 后才能发 text (前端依赖这个状态切换)
        handle.notifyReady();

        return handle;
    }
}