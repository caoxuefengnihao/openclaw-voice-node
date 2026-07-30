package com.openclaw.voicenode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * VAD 配置 (v3-vad 新增,独立于 STT/KWS 配置)。
 *
 * <p>配置文件: {@code src/main/resources/application.yml} 的 {@code openclaw.vad.*} 段。
 * <p>设计文档: {@code docs/voice-node-v3-vad.md}
 *
 * <ul>
 *   <li>{@code modelDir} — silero-vad 模型目录(含 silero_vad.onnx),默认 {@code ${user.dir}/models/vad}</li>
 *   <li>{@code numThreads} — sherpa-onnx Vad 推理用的 CPU 线程数(默认 1)</li>
 *   <li>{@code sampleRate} — 输入 PCM 采样率,固定 16000(silero-vad v4 训练在这个采样率)</li>
 *   <li>{@code threshold} — 语音概率阈值 0.1~0.9,默认 0.5(silero 出厂值)</li>
 *   <li>{@code minSpeechDurationMs} — 最小说话时长(毫秒),低于此不算说话,默认 250ms</li>
 *   <li>{@code minSilenceDurationMs} — 最小静音时长(毫秒),超过此算段边界,默认 100ms</li>
 *   <li>{@code windowSize} — silero-vad 滑动窗口(样本数),固定 512(silero-vad v4 要求,改了会破坏识别)</li>
 *   <li>{@code enabled} — false 时 VAD 完全不加载模型也不接受监听(降级到整段 STT,兜底开关)</li>
 * </ul>
 *
 * @see com.openclaw.voicenode.service.VadService
 */
@ConfigurationProperties(prefix = "openclaw.vad")
public record VadProps(
        String modelDir,
        int numThreads,
        int sampleRate,
        float threshold,
        int minSpeechDurationMs,
        int minSilenceDurationMs,
        int windowSize,
        boolean enabled
) {
    public VadProps {
        if (modelDir == null || modelDir.isBlank()) {
            // 用 ${user.dir} 而非 ${project.basedir},后者是 Maven resource-filter 占位符,
            // 运行时不会被 Spring 展开(跟 STT/KWS 配置同理)
            modelDir = "${user.dir}/models/vad";
        }
        if (numThreads <= 0) {
            numThreads = 1;
        }
        if (sampleRate != 16000) {
            // silero-vad v4 训练在 16kHz,其他采样率需要先重采样
            sampleRate = 16000;
        }
        if (threshold < 0.1f || threshold > 0.9f) {
            threshold = 0.5f;
        }
        if (minSpeechDurationMs < 0) {
            minSpeechDurationMs = 250;
        }
        if (minSilenceDurationMs < 0) {
            minSilenceDurationMs = 100;
        }
        if (windowSize != 512) {
            // silero-vad v4 训练在 512-sample window,改了会破坏识别
            windowSize = 512;
        }
        // enabled 默认值由 Spring 配置文件提供(true)
    }
}