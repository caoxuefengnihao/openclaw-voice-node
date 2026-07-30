package com.openclaw.voicenode.service;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.openclaw.voicenode.config.SttProps;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * STT 服务 (M1-A,Paraformer-zh INT8 + sherpa-onnx Java binding)。
 *
 * 一次性 @PostConstruct 加载模型,后续 recognize(byte[]) 同步调用。
 * sherpa-onnx OfflineRecognizer 是**单线程**的——decode() 不能并发。
 * 当前前端 status guard 同一时刻只发一条 audio.end,所以这够用。
 * 后续并发需求加池。
 *
 * 输入:16kHz mono int16 LE PCM bytes (浏览器 MediaRecorder 出来的格式)
 * 输出:识别文本
 */
@Slf4j
@Service
public class SttService {

    private final SttProps props;
    private OfflineRecognizer recognizer;

    public SttService(SttProps props) {
        this.props = props;
    }

    @PostConstruct
    public void init() throws Exception {
        Path modelPath = Paths.get(props.modelDir(), "model.int8.onnx");
        Path tokensPath = Paths.get(props.modelDir(), "tokens.txt");

        if (!Files.exists(modelPath) || !Files.exists(tokensPath)) {
            throw new IllegalStateException(
                    "STT 模型文件不存在: " + modelPath + " / " + tokensPath + "\n"
                            + "请先跑: ./scripts/download-stt-deps.sh");
        }

        log.info("🎤 加载 STT 模型: modelDir={}, numThreads={}", props.modelDir(), props.numThreads());

        long t0 = System.currentTimeMillis();
        OfflineParaformerModelConfig paraformerConfig =
                OfflineParaformerModelConfig.builder()
                        .setModel(modelPath.toString())
                        .build();
        OfflineModelConfig modelConfig =
                OfflineModelConfig.builder()
                        .setParaformer(paraformerConfig)
                        .setTokens(tokensPath.toString())
                        .setNumThreads(props.numThreads())
                        .build();
        OfflineRecognizerConfig config =
                OfflineRecognizerConfig.builder()
                        .setOfflineModelConfig(modelConfig)
                        .build();

        this.recognizer = new OfflineRecognizer(config);
        log.info("✅ STT 模型加载完成 ({}ms)", System.currentTimeMillis() - t0);
    }

    @PreDestroy
    public void destroy() {
        if (recognizer != null) {
            recognizer.release();
        }
    }

    /**
     * 同步识别 PCM 音频。
     *
     * @param pcm16kMonoInt16LE 16kHz mono int16 little-endian PCM bytes
     * @return 识别出的中文文本
     * @throws SttException 识别失败(模型未加载 / 输入为空 / 解码错)
     */
    public synchronized String recognize(byte[] pcm16kMonoInt16LE) {
        if (recognizer == null) {
            throw new SttException("recognizer 未初始化(看启动日志)");
        }
        if (pcm16kMonoInt16LE == null || pcm16kMonoInt16LE.length < 1600) {
            // 至少 100ms 音频才识别(< 1600 samples @16kHz)
            throw new SttException("音频太短: " + (pcm16kMonoInt16LE == null ? 0 : pcm16kMonoInt16LE.length) + " bytes");
        }

        float[] samples = AudioUtil.pcmInt16LeToFloat32(pcm16kMonoInt16LE);

        long t0 = System.currentTimeMillis();
        OfflineStream stream = recognizer.createStream();
        try {
            // acceptWaveform(float[] samples, int sampleRate) - 参数顺序反的(看 javap)
            stream.acceptWaveform(samples, props.sampleRate());
            recognizer.decode(stream);
            OfflineRecognizerResult result = recognizer.getResult(stream);
            String text = result.getText();

            long elapsed = System.currentTimeMillis() - t0;
            log.info("🎤 STT {} bytes → \"{}\" ({}ms)",
                    pcm16kMonoInt16LE.length, text.trim(), elapsed);
            return text;
        } catch (Exception e) {
            throw new SttException("decode 失败: " + e.getMessage(), e);
        } finally {
            stream.release();
        }
    }

    /** STT 失败的统一异常,handler 捕获后决定降级还是推错误给浏览器。 */
    public static class SttException extends RuntimeException {
        public SttException(String msg) { super(msg); }
        public SttException(String msg, Throwable cause) { super(msg, cause); }
    }
}