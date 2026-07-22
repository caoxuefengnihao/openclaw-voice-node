package com.openclaw.voicenode.api;

import com.openclaw.voicenode.config.TalkProps;
import com.openclaw.voicenode.config.VoiceNodeProperties;
import com.openclaw.voicenode.gateway.KeyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简单的状态查询接口
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StatusController {

    private final VoiceNodeProperties props;
    private final TalkProps talkProps;
    private final KeyManager keyManager;

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("gateway", props.gateway().url());
        info.put("agent", props.agent().id());
        info.put("sessionKey", props.sessionKey());
        info.put("deviceId", keyManager.getDeviceId());
        info.put("deviceIdShort", keyManager.getDeviceId().substring(0, 16) + "...");
        info.put("devicePaired", keyManager.getDeviceToken() != null);
        info.put("talk", Map.of(
                "mode", talkProps.mode(),
                "transport", talkProps.transport(),
                "brain", talkProps.brain()
        ));
        return info;
    }
}
