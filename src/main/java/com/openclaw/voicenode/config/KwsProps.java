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
        /**
         * 模型文件名前缀 (不含 encoder-/decoder-/joiner- 前缀和 .int8.onnx 后缀)。
         * 默认 "epoch-99-avg-1-chunk-16-left-64" (原预训练 8 关键词 transducer)。
         * 自训练后改成 e.g. "epoch-20-avg-2-chunk-16-left-64"。
         */
        String modelPrefix,
        int numThreads,
        int sampleRate,
        float threshold,
        boolean enabled,
        /**
         * 使用自训练 CTC 模型（仅 1 个 encoder.onnx）。
         * true: 期望 encoder.onnx + tokens.txt + keywords.txt (CTC 模式)
         * false (默认): 期望 encoder/decoder/joiner + tokens.txt + keywords.txt (transducer 模式)
         */
        boolean useCtc,
        /**
         * 唤醒命中后冷却时间(毫秒)。同一 session 在此时间内再次唤醒会被丢弃,
         * 避免一句话被识别成多次唤醒。借鉴白龙马项目 kws-process.cjs 默认 800ms。
         */
        long cooldownMs,
        /**
         * 唤醒分数偏置(参考 sherpa-onnx keywords_score)。默认 1.5,
         * 白龙马调高到 3.0 实测召回率从 9/17 提升到 13/17。
         */
        float keywordsScore
) {
    public KwsProps {
        if (modelDir == null || modelDir.isBlank()) {
            // 用 ${user.dir} 而非 ${project.basedir},因为后者是 Maven resource-filter 占位符,
            // 运行时不会被 Spring 展开 (跟 STT 配置同理)
            modelDir = "${user.dir}/models/kws";
        }
        if (modelPrefix == null || modelPrefix.isBlank()) {
            // 默认跟原硬编码一致,保持向后兼容
            modelPrefix = "epoch-99-avg-1-chunk-16-left-64";
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
        if (cooldownMs < 0) {
            cooldownMs = 800L;  // 白龙马默认
        }
        if (keywordsScore <= 0) {
            keywordsScore = 1.5f;
        }
    }
}