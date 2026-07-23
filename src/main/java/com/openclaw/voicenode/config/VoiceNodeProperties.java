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
    public record Agent(String id, String sessionKeyOverride) {}
    public record Device(String stateFile) {}

    /** talk.* 段在 application.yml 里独立，不走 openclaw 前缀 */
    @ConfigurationProperties(prefix = "talk")
    public record Talk(String mode, String transport, String brain) {}

    public String sessionKey() {
        // 优先用 sessionKeyOverride（用飞书 session 时配）
        if (agent.sessionKeyOverride() != null && !agent.sessionKeyOverride().isBlank()) {
            return agent.sessionKeyOverride();
        }
        // 默认：agent 的 main session
        return "agent:" + agent.id() + ":main";
    }
}
