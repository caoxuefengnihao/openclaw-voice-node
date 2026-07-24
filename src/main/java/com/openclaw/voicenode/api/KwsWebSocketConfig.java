package com.openclaw.voicenode.api;

import com.openclaw.voicenode.config.KwsProps;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 独立注册 {@code /ws/kws} 端点 (v3 新增)。
 *
 * <p><b>不修改现有 {@code WebSocketConfig.java}</b>。
 * <p>Spring Boot 自动发现所有 {@link WebSocketConfigurer} 实现,合并注册到
 * {@code WebSocketHandlerRegistry},不会冲突。
 *
 * <p>配置 {@link KwsProps} 通过 {@code @EnableConfigurationProperties} 在这里加载,
 * <b>不需要改 {@code VoiceNodeApplication.java}</b>。
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(KwsProps.class)
@RequiredArgsConstructor
public class KwsWebSocketConfig implements WebSocketConfigurer {

    private final KwsWebSocketHandler kwsHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(kwsHandler, "/ws/kws")
                .setAllowedOriginPatterns("*");
    }
}