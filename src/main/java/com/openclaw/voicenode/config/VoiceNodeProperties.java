package com.openclaw.voicenode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 统一配置（对应 application.yml 的 openclaw.* 与 talk.*）
 */
@ConfigurationProperties(prefix = "openclaw")
public record VoiceNodeProperties(
        Gateway gateway,
        User user,
        Agent agent,
        Device device
) {
    public record Gateway(String url, String token, String clientId, String clientMode, String role, String scopes) {}
    public record User(String openId) {}
    public record Agent(String id) {}
    public record Device(String stateFile) {}

    /** talk.* 段在 application.yml 里独立，不走 openclaw 前缀 */
    @ConfigurationProperties(prefix = "talk")
    public record Talk(String mode, String transport, String brain) {}

    public String sessionKey() {
        return "agent:" + agent.id() + ":feishu:direct:" + user.openId();
    }
}
