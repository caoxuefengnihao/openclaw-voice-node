package com.openclaw.voicenode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KWS 配置 (v3 新增,独立于 STT 配置)。
 * <p>
 * 配置文件: {@code src/main/resources/application-kws.yml}
 * <p>
 * 启动方式: {@code mvn spring-boot:run -Dspring.profiles.active=kws}
 *
 * <ul>
 *   <li>{@code modelDir} — KWS 模型目录(含 encoder.onnx + tokens.txt + keywords.txt)</li>
 *   <li>{@code numThreads} — sherpa-onnx KeywordSpotter 推理用的 CPU 线程数(默认 1)</li>
 *   <li>{@code sampleRate} — 输入 PCM 采样率,固定 16000(KWS 模型训练在这个采样率)</li>
 *   <li>{@code threshold} — 唤醒阈值 0.1~0.9,越高越不灵敏(默认 0.6)</li>
 *   <li>{@code enabled} — false 时 KWS 完全不加载模型也不接受监听(兜底开关)</li>
 * </ul>
 *
 * @see com.openclaw.voicenode.service.KwsService
 */
@ConfigurationProperties(prefix = "openclaw.kws")
public record KwsProps(
        String modelDir,
        int numThreads,
        int sampleRate,
        float threshold,
        boolean enabled,
        /**
         * 使用自训练 CTC 模型（仅 1 个 encoder.onnx）。
         * true: 期望 encoder.onnx + tokens.txt + keywords.txt (CTC 模式)
         * false (默认): 期望 encoder/decoder/joiner + tokens.txt + keywords.txt (transducer 模式)
         */
        boolean useCtc
) {
    public KwsProps {
        if (modelDir == null || modelDir.isBlank()) {
            // 用 ${user.dir} 而非 ${project.basedir},因为后者是 Maven resource-filter 占位符,
            // 运行时不会被 Spring 展开 (跟 STT 配置同理)
            modelDir = "${user.dir}/models/kws";
        }
        if (numThreads <= 0) {
            numThreads = 1;
        }
        if (sampleRate != 16000) {
            // KWS 模型训练在 16kHz,其他采样率需要先重采样
            sampleRate = 16000;
        }
        if (threshold < 0.1f || threshold > 0.9f) {
            threshold = 0.6f;
        }
    }
}