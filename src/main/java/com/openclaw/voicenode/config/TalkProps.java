package com.openclaw.voicenode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "talk")
public record TalkProps(String mode, String transport, String brain) {}
