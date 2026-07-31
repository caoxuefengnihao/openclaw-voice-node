package com.openclaw.voicenode;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * KWS 最小隔离测试 (option B).
 *
 * 绕过 voice-node 的 KwsService / 前端 / WebSocket,直接用 sherpa-onnx Java binding
 * 喂 5 秒 440Hz 正弦波,精确观测:
 *  - spotter.isReady() 是否会变 true (任何时刻)
 *  - decode 调用次数
 *  - 是否命中关键词
 *
 * 关键改进: 在 while 循环之前就调一次 isReady(),把"中间状态"打出来。
 * KwsService 现有日志只在 while 循环结束后打,看不到中间变 true 的瞬间。
 *
 * 跑法:
 *   mvn test-compile
 *   java -cp "target/test-classes:target/classes:$(mvn -q dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=/tmp/cp.txt && cat /tmp/cp.txt)" \
 *        com.openclaw.voicenode.KwsIsolatedTest
 */
public class KwsIsolatedTest {

    private static final String MODEL_DIR = "/Volumes/ssd/openclaw-voice-node/models/kws";
    private static final String PREFIX = "epoch-99-avg-1-chunk-16-left-64";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK = 1600;  // 100ms @ 16k

    public static void main(String[] args) throws Exception {
        // ===== 1. 完整复刻 KwsService.init() 的配置 (一行不差) =====
        FeatureConfig featureConfig = FeatureConfig.builder()
            .setSampleRate(SAMPLE_RATE)
            .setFeatureDim(80)
            .build();

        Path encoder = Paths.get(MODEL_DIR, "encoder-" + PREFIX + ".int8.onnx");
        Path decoder = Paths.get(MODEL_DIR, "decoder-" + PREFIX + ".int8.onnx");
        Path joiner = Paths.get(MODEL_DIR, "joiner-" + PREFIX + ".int8.onnx");
        Path tokens = Paths.get(MODEL_DIR, "tokens.txt");
        Path keywords = Paths.get(MODEL_DIR, "keywords.txt");

        System.out.println("[test] model files:");
        System.out.println("  encoder exists: " + encoder.toFile().exists() + " (" + encoder + ")");
        System.out.println("  decoder exists: " + decoder.toFile().exists() + " (" + decoder + ")");
        System.out.println("  joiner exists:  " + joiner.toFile().exists() + " (" + joiner + ")");
        System.out.println("  tokens exists:  " + tokens.toFile().exists() + " (" + tokens + ")");
        System.out.println("  keywords exists:" + keywords.toFile().exists() + " (" + keywords + ")");

        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
            .setTransducer(OnlineTransducerModelConfig.builder()
                .setEncoder(encoder.toString())
                .setDecoder(decoder.toString())
                .setJoiner(joiner.toString())
                .build())
            .setTokens(tokens.toString())
            .setNumThreads(1)
            .setModelType("zipformer2")
            .build();

        KeywordSpotterConfig config = KeywordSpotterConfig.builder()
            .setOnlineModelConfig(modelConfig)
            .setFeatureConfig(featureConfig)
            .setKeywordsFile(keywords.toString())
            .setKeywordsThreshold(0.35f)   // 白龙马
            .setKeywordsScore(3.0f)       // 白龙马
            .setMaxActivePaths(4)
            .setNumTrailingBlanks(1)
            .build();

        System.out.println("[test] creating KeywordSpotter...");
        KeywordSpotter spotter = new KeywordSpotter(config);
        System.out.println("[test] ✅ KeywordSpotter created");

        OnlineStream stream = spotter.createStream();
        System.out.println("[test] ✅ OnlineStream created");

        // ===== 2. 合成 5 秒 440Hz 正弦波 =====
        int totalSamples = SAMPLE_RATE * 5;
        float[] sine = new float[totalSamples];
        for (int i = 0; i < totalSamples; i++) {
            sine[i] = 0.5f * (float) Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE);
        }
        System.out.printf("[test] 合成 %d 样本 (%.1f 秒) 440Hz 正弦波%n",
            totalSamples, totalSamples / (float) SAMPLE_RATE);

        // ===== 3. 喂 100ms chunks + 全程观测 =====
        int chunks = 0;
        long t0 = System.currentTimeMillis();
        int decodeCount = 0;
        boolean firstReady = false;
        long firstReadyAtMs = -1;
        int hits = 0;

        for (int offset = 0; offset < totalSamples; offset += CHUNK) {
            int n = Math.min(CHUNK, totalSamples - offset);
            float[] chunk = new float[n];
            System.arraycopy(sine, offset, chunk, 0, n);

            // acceptWaveform
            stream.acceptWaveform(chunk, SAMPLE_RATE);

            // ⭐ 关键: 在 while 循环之前打 isReady, 看中间状态
            boolean readyBeforeWhile = spotter.isReady(stream);

            int decodesThisChunk = 0;
            while (spotter.isReady(stream)) {
                spotter.decode(stream);
                decodesThisChunk++;
                decodeCount++;
            }

            chunks++;

            if (!firstReady && readyBeforeWhile) {
                firstReady = true;
                firstReadyAtMs = System.currentTimeMillis() - t0;
                System.out.printf("[test] 🎉 第一次 isReady=true 在 chunk#%d (offset=%d 样本, %dms)%n",
                    chunks, offset, firstReadyAtMs);
            }

            // 每 5 块打一次状态 (500ms)
            if (chunks % 5 == 0) {
                long elapsed = System.currentTimeMillis() - t0;
                System.out.printf("[test] ⏱ chunk#%d (offset=%d, t=%dms) ready_before_while=%s decodes_this_chunk=%d cumulative_decodes=%d%n",
                    chunks, offset, elapsed, readyBeforeWhile, decodesThisChunk, decodeCount);
            }

            KeywordSpotterResult result = spotter.getResult(stream);
            if (!result.getKeyword().isEmpty()) {
                hits++;
                long elapsed = System.currentTimeMillis() - t0;
                System.out.printf("[test] 🔥 命中! chunk#%d (offset=%d, t=%dms) keyword=%s%n",
                    chunks, offset, elapsed, result.getKeyword());
                // 重置 stream (跟 KwsService 一样)
                stream.release();
                stream = spotter.createStream();
            }
        }

        // ===== 4. 总结 =====
        long totalElapsed = System.currentTimeMillis() - t0;
        System.out.println();
        System.out.println("=== 测试总结 ===");
        System.out.printf("总喂入:       %d 块 (%dms)%n", chunks, totalElapsed);
        System.out.printf("是否见到 isReady=true: %s%n", firstReady ? "✅ YES" : "❌ NO");
        if (firstReady) {
            System.out.printf("  第一次出现: %dms%n", firstReadyAtMs);
        }
        System.out.printf("decode 调用次数: %d%n", decodeCount);
        System.out.printf("命中次数:      %d%n", hits);

        if (!firstReady) {
            System.out.println();
            System.out.println("❌ 确诊: isReady 始终 false");
            System.out.println("   → 音频到了 native (无异常),但 fbank / 模型 ChunkSize 配合有问题");
            System.out.println("   → 可能根因: model_type 解析 / chunk_size 单位 / fbank 输出 0 帧");
        } else if (hits == 0) {
            System.out.println();
            System.out.println("⚠️ KWS 工作但没命中 440Hz 正弦波 (预期 - 440Hz 不是唤醒词)");
            System.out.println("   → 说明链路完整,问题在前端音频不是唤醒词,或 noise/AGC 破坏了语音特征");
        }

        stream.release();
        spotter.release();
    }
}
