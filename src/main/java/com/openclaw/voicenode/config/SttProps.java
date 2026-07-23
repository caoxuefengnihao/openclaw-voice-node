package com.openclaw.voicenode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * STT 配置 (M1 用)。
 * - model-dir 指向 Paraformer-zh 解压目录(含 model.int8.onnx + tokens.txt)
 * - num-threads 后端推理用的 CPU 线程数
 * - sample-rate 输入 PCM 期望采样率(16kHz mono int16 LE)
 */
@ConfigurationProperties(prefix = "openclaw.stt")
public record SttProps(
        String modelDir,
        int numThreads,
        int sampleRate
) {
    public SttProps {
        if (modelDir == null || modelDir.isBlank()) {
            modelDir = "${project.basedir}/models/stt";
        }
        if (numThreads <= 0) {
            numThreads = 2;
        }
        if (sampleRate != 16000) {
            // Paraformer-zh 训练在 16kHz,其他采样率需要先重采样
            sampleRate = 16000;
        }
    }
}