# Voice Node v3: 后端 VAD 自动切分 设计文档

> 状态: **v1 设计稿,等老板拍板**
> 创建: 2026-07-30 14:38 by 普罗米修斯 (CTO agent)
> 拍板线索:
>   - 2026-07-23 23:06 VAD 方案讨论 → 用户选 **B 方案**(保留前端 mic 按钮,后端 audio.end 时先过 VAD 切分)
>   - 2026-07-23 23:43 配套讨论:text 输入跳过 TTS(独立 issue,不在本文档范围)
> 关联 commit / 分支:
>   - `20fcf24` PR #2 (v3 KWS wake main)
>   - `d5aa228` KwsService 3 个 setter 修复(sherpa-onnx 1.13.4 已知坑)
>   - `feat/voice-node-v3-vad` 从 main 拉的新分支,**未开发**

---

## 一、目标与范围

### 1.1 业务目标

把 voice-node 从"用户松 mic → STT 识别"升级到 **"用户松 mic → VAD 自动切分 → 去掉静音段 → STT 识别"**,
解决当前 `audio.end` 时**整段 PCM(含静音/噪声)都送 STT** 导致的两个问题:

| 维度 | 现在 (v2/v3) | 目标 (v3-vad) |
|---|---|---|
| STT 输入 | `audio.end` 时**整段** PCM | VAD 切分后**只含说话段** |
| 静音段处理 | 参与识别,可能误识别成"嗯""啊" | 直接丢弃,不进 STT |
| 端到端延迟 | 1.5~2s (整段 STT) | 0.8~1.5s (切分后 STT 段更短) |
| 用户感知 | 说完要等"嗯嗯"被识别完才回 | 说完立刻回,体感更快 |

### 1.2 核心原则

> **前端零改动,后端 audio.end 时插入 VAD 切分步骤。**

- ✅ 复用现有 `SttService.recognize(byte[])` 公开方法,**不改 SttService**
- ✅ 复用现有 PCM 缓冲链路(`ByteArrayOutputStream`)
- ✅ 复用 sherpa-onnx 库(同 KWS/STT,零新 Maven 依赖)
- ✅ 失败降级:VAD 失败 → 走老的整段 STT,**不阻塞主链路**
- ✅ 前后端协议完全不变(浏览器无感)

### 1.3 不在本文档范围

- ❌ 流式 VAD + 流式 ASR(留给 v4,改动太大)
- ❌ KWS 唤醒链路的 VAD(KWS 走 always-on,跟本文档正交)
- ❌ text 输入跳过 TTS(独立 issue)

---

## 二、现状事实(代码扒出来,2026-07-30 14:30 实测)

### 2.1 当前 audio.end 链路

```
浏览器                              Java 后端                          OpenClaw Gateway
─────                              ────────                          ────────────────
audio.start → mode="recording"      清空 PCM buffer
audio.chunk (binary Int16) →        ByteArrayOutputStream.write()
audio.end                          audio.end 分支:
                                     ├─ buf.toByteArray() = 整段 PCM(含静音)
                                     ├─ sttService.recognize(pcm) ─→ 文本(可能含"嗯")
                                     ├─ sendToBrowser user.text
                                     └─ chat.sendText(text) ─────────────────→
                                                                      ← response delta
                                                                      ← turn.done
                                     ├─ ttsService.synthesize(text) ─→ audio
                                     └─ sendToBrowser assistant.audio
```

**问题点**:`audio.end` 时整段 PCM(含静音/呼吸声/环境噪声)都被 `OfflineRecognizer.decode()` 处理。
静音段会被识别成"嗯""啊"或被噪声干扰出幻觉文字。**整段 STT 也比纯语音 STT 慢**。

### 2.2 现有 sherpa-onnx 资产

- ✅ sherpa-onnx 1.13.4 已装(`pom.xml`)
- ✅ onnxruntime 1.17.1 已装
- ✅ Paraformer-zh STT 已跑通(`SttService`)
- ✅ KeywordSpotter 已跑通(`KwsService`,d5aa228 commit 加了 3 个 setter)

**新增依赖**:**零**——sherpa-onnx 自带 VAD 模型支持(`VadModelConfig` + 统一 `Vad` 类,**没有 `OfflineVad` 类**,API 是 streaming 风格但可一次性喂完整段离线使用)。

> ⚠️ **2026-07-30 14:55 校正**:本文档初稿假设 sherpa-onnx 1.13.4 有 `OfflineVad` / `OfflineVadModelConfig` 类(类似 STT 的 `OfflineRecognizer`)。**实测 javap + VadProbe 验证:1.13.4 只有统一的 `Vad` 类**,接受任意长度 `float[]`,内部按 `windowSize=512` 自动切分。所有 `§4.1.2 / §D3` 都已按实测 API 改正。

### 2.3 silero-vad 模型(目标使用)

| 项 | 值 |
|---|---|
| 模型文件 | `silero-vad.onnx` |
| 大小 | ~2 MB |
| 输入 | 16kHz mono Float32,chunk_size 必须是 512 样本(32ms) |
| 输出 | 每个 chunk 的语音概率 (0~1) |
| 阈值 | 默认 0.5(可调) |
| 推理速度 | 单核 < 1ms/chunk,CPU 实时 |

下载脚本建议新增:`scripts/download-vad-deps.sh`(参考 `download-kws-deps.sh` 风格)

---

## 三、目标行为

### 3.1 新链路图

```
浏览器                              Java 后端                                OpenClaw Gateway
─────                              ────────                                ────────────────
audio.start                        清空 PCM buffer
audio.chunk (binary Int16)         ByteArrayOutputStream.write()
audio.end                          audio.end 分支:
                                     ├─ Int16 → Float32(已在 stt 里做过,抽工具方法)
                                     ├─ vadService.split(float32) ─→ [段1, 段2, ...]
                                     │    (silero-vad 离线切分,返回段起始/结束 sample idx)
                                     ├─ 段1 → sttService.recognize → text1
                                     ├─ 段2 → sttService.recognize → text2
                                     ├─ ... → textN
                                     ├─ text = text1 + text2 + ... + textN(用空格拼接)
                                     ├─ sendToBrowser user.text {text, segments: N}
                                     └─ chat.sendText(text) ───────────────────→
                                                                       ← response delta
                                                                       ← turn.done
                                     ├─ ttsService.synthesize(text) ─→ audio
                                     └─ sendToBrowser assistant.audio
```

### 3.2 行为变化(用户视角)

| 场景 | 现在 (v3) | 目标 (v3-vad) |
|---|---|---|
| 用户说:"今天天气怎么样" | STT 输出:"今天天气怎么样嗯" | STT 输出:"今天天气怎么样" |
| 用户说:"今天" (停顿 1s) "天气怎么样" | STT 输出:"今天啊天气怎么样" | STT 输出:"今天 天气怎么样" |
| 用户说完等 0.5s 松 mic | 整段识别,0.5s 静音也算 | 静音被切掉,只识别"说话段" |
| VAD 失败/模型加载失败 | (不发生) | **降级**:走老路径,整段 STT,不报错 |

---

## 四、技术实现分阶段

### 4.1 M1:VAD 引擎集成(后端能识别"哪些段是说话")

#### 4.1.1 M1-A:模型下载脚本 + VadProps

**新增文件**:
- `scripts/download-vad-deps.sh`(~30 行,参考 `download-kws-deps.sh`)
- `config/VadProps.java`(~50 行)

**`VadProps.java`**:

```java
@ConfigurationProperties(prefix = "openclaw.vad")
public record VadProps(
    String modelDir,            // 模型目录,如 ${user.dir}/models/vad
    int sampleRate,             // 16000(固定)
    float threshold,            // 0.1~0.9,默认 0.5
    int minSpeechDurationMs,    // 最小说话时长,默认 250ms(防短促"嗯"误触发)
    int minSilenceDurationMs,   // 最小静音时长,默认 100ms(切分边界)
    int windowSizeSamples,      // 512(固定,silero-vad 要求)
    boolean enabled             // false 时降级到整段 STT(默认 true)
) { /* 默认值兜底 */ }
```

**`application.yml` 新增**:

```yaml
openclaw:
  vad:
    model-dir: ${user.dir}/models/vad
    num-threads: 1
    sample-rate: 16000
    threshold: 0.5
    min-speech-duration-ms: 250
    min-silence-duration-ms: 100
    window-size-samples: 512
    enabled: true
```

#### 4.1.2 M1-B:VadService 实现

**新增文件**:`service/VadService.java`(~180 行)

**核心 API**:

```java
@Slf4j
@Service
public class VadService {

    private final VadProps props;
    private Vad vad;  // sherpa-onnx 统一 VAD 类(无 OfflineVad)

    @PostConstruct
    public void init() throws Exception {
        if (!props.enabled()) {
            log.info("🔇 VAD 已禁用,降级到整段 STT");
            return;
        }
        Path modelPath = Paths.get(props.modelDir(), "silero_vad.onnx");
        if (!Files.exists(modelPath)) {
            throw new IllegalStateException("silero-vad 模型不存在: " + modelPath);
        }

        // 1. 配 silero-vad 子配置(silero-vad v4 要求 windowSize=512)
        SileroVadModelConfig silero = SileroVadModelConfig.builder()
                .setModel(modelPath.toString())
                .setThreshold(props.threshold())
                .setMinSilenceDuration(props.minSilenceDurationMs() / 1000f)  // ms → 秒
                .setMinSpeechDuration(props.minSpeechDurationMs() / 1000f)
                .setWindowSize(512)
                .setMaxSpeechDuration(20.0f)
                .build();

        // 2. 配 VadModelConfig(可选 silero / ten-vad / num_threads / provider / debug)
        VadModelConfig config = VadModelConfig.builder()
                .setSileroVadModelConfig(silero)
                .setSampleRate(props.sampleRate())
                .setNumThreads(props.numThreads())
                .setDebug(false)
                .build();

        this.vad = new Vad(config);
        log.info("✅ VAD 模型加载完成: {}", modelPath);
    }

    @PreDestroy
    public void destroy() {
        if (vad != null) {
            vad.release();
            vad = null;
        }
        log.info("🔇 VAD 已关闭");
    }

    /**
     * 把整段 Float32 PCM 切成多个"说话段"。
     *
     * <p>API 用法(实测自 VadProbe 2026-07-30):
     * <ul>
     *   <li>反复 {@code vad.acceptWaveform(chunk)} 喂入(任意长度,512 推荐)</li>
     *   <li>结束时 {@code vad.flush()}</li>
     *   <li>循环 {@code !vad.empty()} → {@code front()} 拿 segment → {@code pop()}</li>
     *   <li>每个 segment 的 {@link SpeechSegment#getStart()} 是相对累计输入的 sample 索引,
     *       {@link SpeechSegment#getSamples()} 是该段的 Float32 音频</li>
     * </ul>
     *
     * @param samples 16kHz mono Float32 PCM
     * @return 段列表,每段是 [startSample, endSample),end 不含
     */
    public synchronized List<VadSegment> split(float[] samples) {
        if (vad == null || !props.enabled()) {
            // 降级:整段当作一段
            return List.of(new VadSegment(0, samples.length));
        }

        // 按 windowSize (512) 分块喂入,匹配 silero-vad v4 训练窗口
        int chunkSize = 512;
        for (int offset = 0; offset < samples.length; offset += chunkSize) {
            int len = Math.min(chunkSize, samples.length - offset);
            float[] chunk = new float[len];
            System.arraycopy(samples, offset, chunk, 0, len);
            vad.acceptWaveform(chunk);
        }
        vad.flush();  // 触发最后一段 segment 输出

        List<VadSegment> result = new java.util.ArrayList<>();
        while (!vad.empty()) {
            SpeechSegment seg = vad.front();
            int startSample = seg.getStart();
            int endSample = startSample + seg.getSamples().length;
            result.add(new VadSegment(startSample, endSample));
            vad.pop();
        }
        // 关键:每次 split 后要 reset,否则下次输入会被当成本次输入的延续(累计偏移)
        vad.reset();
        return result;
    }

    /** 说话段标记,起止用 sample index 标记 */
    public record VadSegment(int startSample, int endSample) {
        public int sampleCount() { return endSample - startSample; }
        public float durationMs() { return sampleCount() * 1000f / 16000f; }
    }
}
```

#### 4.1.3 M1-C:VoiceWebSocketHandler 集成 VAD

**改文件**:`api/VoiceWebSocketHandler.java`(`audio.end` 分支,+~30 行)

**当前代码**(简化):

```java
} else if ("audio.end".equals(type)) {
    byte[] pcm = buf.toByteArray();
    String text = sttService.recognize(pcm);
    sendToBrowser(session, Map.of("type", "user.text", "text", text, "isFinal", true));
    chat.sendText(text);
}
```

**目标代码**:

```java
} else if ("audio.end".equals(type)) {
    byte[] pcmInt16 = buf.toByteArray();
    float[] pcmFloat32 = pcmInt16LeToFloat32(pcmInt16);  // 抽工具方法复用

    // VAD 切分
    List<VadService.VadSegment> segments = vadService.split(pcmFloat32);
    log.info("🎙️ VAD 切分: {} 段 (总 {}ms)",
        segments.size(),
        (int)(pcmFloat32.length * 1000f / 16000));

    // 每段 STT → 拼接
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < segments.size(); i++) {
        VadService.VadSegment seg = segments.get(i);
        byte[] segInt16 = float32ToInt16Le(pcmFloat32, seg.startSample(), seg.sampleCount());
        try {
            String text = sttService.recognize(segInt16);
            if (!text.isBlank()) {
                if (sb.length() > 0) sb.append(' ');  // 段间空格
                sb.append(text);
            }
        } catch (SttService.SttException e) {
            log.warn("段 {} STT 失败: {}", i, e.getMessage());
        }
    }

    String finalText = sb.toString().trim();
    sendToBrowser(session, Map.of(
        "type", "user.text",
        "text", finalText,
        "isFinal", true,
        "vadSegments", segments.size()  // 新增字段,前端可显示"已切分 N 段"
    ));
    if (chat != null && !finalText.isBlank()) {
        chat.sendText(finalText);
    }
}
```

#### 4.1.4 M1-D:测试

**新增文件**:`test/.../service/VadServiceTest.java`(~150 行,参考 `KwsServiceTest` 风格)

测试用例:
1. **静音输入**:全 0 Float32 → 切出 0 段或 1 个空段(取决于 silero 行为)
2. **纯语音输入**:录制 "今天天气怎么样" → 切出 1 段
3. **多段语音输入**:录 "今天" (停顿 1s) "天气怎么样" → 切出 2 段
4. **降级路径**:`enabled=false` → 整段当作 1 段返回
5. **超长音频**:30s 连续说话 → 切出 N 段(具体 N 取决于 silero 行为)

### 4.2 M2:联调验证

- 启动后端 + 前端
- 浏览器 mic 按钮按下 → 说话(故意中间停顿) → 松开
- 观察后端日志:打印 VAD 切分结果,看段数/段长是否合理
- 检查 STT 输出是否比之前干净(没"嗯""啊"幻觉)
- 检查延迟是否缩短

---

## 五、设计决策表

| # | 决策项 | 选项 | 推荐 | 理由 |
|---|---|---|---|---|
| D1 | VAD 引擎 | A) silero-vad (ONNX) / B) webrtcvad (C) / C) sherpa-onnx 自带 VAD / D) pyannote (Python) | **✅ A silero-vad** | 业界事实标准、~2MB 极轻、CPU 实时、中文 OK、准确率远高于 webrtcvad |
| D2 | VAD 集成方式 | A) 直 ONNX Runtime Java / B) sherpa-onnx `VadModelConfig` + `Vad` / C) Python subprocess | **✅ B sherpa-onnx** | 跟 STT/KWS 同 Java binding,零新依赖,API 风格统一(1.13.4 只有统一 `Vad` 类) |
| D3 | VAD 模式 | A) 流式 (audio.start 后持续监听,边说边切) / B) 离线 (audio.end 后整段切分,reset 后下次) | **✅ B 离线用法** | audio.end 时一次性 acceptWaveform + flush + pop,符合现有 v2/v3 audio.end 时机;流式留给 v4(流式 ASR) |
| D4 | 切分粒度 | A) 整段单段 (去静音但仍是一段) / B) 多段独立切分 (按说话间隔分开) | **✅ B 多段独立** | 真实对话有自然停顿,多段切分后 STT 更准;拼接保留语义 |
| D5 | STT 调用方式 | A) 每段独立 STT / B) 切分后拼接 + 单次 STT | **A 每段独立** | 每段更短 → STT 更准;段间空格拼接保留语义 |
| D6 | 阈值 (threshold) | A) 默认 silero 0.5 / B) 自调高 0.6 (更严格) / C) 自调低 0.4 (更宽松) | **⏳ 默认 0.5** | 实测再调,silero 默认值已经过大量验证 |
| D7 | 最小说话时长 | A) 默认 250ms / B) 自调 500ms / C) 自调 100ms | **⏳ 默认 250ms** | 防短促"嗯"误触发,但太短又切不出自然停顿 |
| D8 | 最小静音时长(切分边界) | A) 默认 100ms / B) 自调 300ms / C) 自调 500ms | **⏳ 默认 100ms** | 太短 → 切太碎;太长 → 段间停顿被吞 |
| D9 | 数据格式兼容 | A) Float32 直送(`vad.acceptWaveform(float[])`) / B) Int16 转换 / C) 重采样 | **✅ A Float32** | stt 已经是 Int16→Float32,VAD 用同源 Float32 零成本;Vad API 只接受 `float[]`,不直接接 Int16 |
| D10 | 失败降级 | A) VAD 失败 → 整段 STT / B) VAD 失败 → 报错 | **✅ A 降级** | VAD 是优化项,失败不该阻塞主链路 |
| D11 | 跟 KWS 的关系 | A) VAD 串在 KWS 后 / B) KWS 不接 VAD | **✅ B 正交** | KWS 是 always-on(短音频),VAD 是录音模式(长音频),互不干扰 |
| D12 | 配置文件 | A) 合进 application.yml / B) 独立 application-vad.yml | **✅ A 合进** | KWS 已经合进,保持一致;字段不多,无拆分必要 |
| D13 | 前端是否需要改 | A) 需要 (UI 提示) / B) 不需要 (透明) | **✅ B 不需要** | 前端只多收一个 `vadSegments` 字段,可忽略 |

---

## 六、待拍板项(老板必看)

### T1. VAD 阈值 ⏳ 待实测

**默认**:`0.5`(silero-vad 出厂值)

**需要实测的边界**:
- BT 麦克风(用户主用)有底噪,阈值可能要调高到 0.55~0.6
- Mac 内置麦相对干净,0.5 应该够

### T2. 最小说话/静音时长 ⏳ 待实测

**默认**:`minSpeechDurationMs=250`,`minSilenceDurationMs=100`

**调整方向**:
- 短促"嗯"算不算说话 → `minSpeechDurationMs` 决定
- 多短停顿算"说话结束" → `minSilenceDurationMs` 决定

### T3. VAD 启用开关 ⏳ 待拍板

**默认**:`enabled=true`

**决策点**:上线后是否需要保留快速关闭开关?KWS 已经有 `enabled=false` 兜底,VAD 也加上以备不测。

### T4. 是否给前端推送 `vadSegments` 字段 ⏳ 待拍板

**当前设计**:推 `{type:"user.text", text, isFinal, vadSegments: N}`

**决策点**:
- 推 → 前端可以显示"已识别 N 段",调试有用
- 不推 → 协议最简,前端零感知

---

## 七、PR 拆分建议

**当前分支**:`feat/voice-node-v3-vad`(从 main 拉,**未开发**)

| PR | 标题 | 工作量 | 依赖 | 改动文件 |
|---|---|---|---|---|
| **M1-A** | 后端 VAD 配置 + 模型下载脚本 | ~80 行 | 无 | `scripts/download-vad-deps.sh` / `VadProps.java` / `application.yml` |
| **M1-B** | VadService 实现(sherpa-onnx OfflineVad 包装) | ~180 行 | M1-A | `service/VadService.java` |
| **M1-C** | VoiceWebSocketHandler.audio.end 集成 VAD | ~50 行 | M1-B | `api/VoiceWebSocketHandler.java` |
| **M1-D** | VadService 单测 | ~150 行 | M1-B | `test/.../service/VadServiceTest.java` |
| **M2** | 联调验证 + 日志调优 | 半天 | M1 | (无新代码) |

**分支策略**:
- 不在 main 上直接改(避免破坏现有 v2/v3)
- Phase VAD 改动收敛在一个分支,merge 时用 squash

**push 策略**:沿用之前节奏,每个 M1-* PR 本地 commit 后立即 push

---

## 八、验证清单

### 8.1 M1-A 验证(VAD 配置)

- [ ] `download-vad-deps.sh` 跑完,`models/vad/silero-vad.onnx` 存在(~2MB)
- [ ] `application.yml` 启动时 `VadProps` 绑定成功(日志看字段值)
- [ ] `enabled=false` 启动不加载模型

### 8.2 M1-B 验证(VadService)

- [ ] `VadService.init()` 加载 silero-vad.onnx 成功(< 1s)
- [ ] `split()` 单调输入一段静音 → 返回空段或单空段(不抛异常)
- [ ] `split()` 单调输入 "今天天气怎么样" PCM → 返回 ≥1 段
- [ ] `split()` 输入 "今天" (停顿 1s) "天气怎么样" → 返回 ≥2 段
- [ ] `split()` 输入 `enabled=false` 时 → 整段当作 1 段

### 8.3 M1-C 验证(Handler 集成)

- [ ] 浏览器 mic 按钮按下 → 说话 → 松开
- [ ] 后端日志:`🎙️ VAD 切分: N 段 (总 Xms)`
- [ ] 浏览器收到 `user.text`:`{text, isFinal, vadSegments: N}`
- [ ] STT 输出比 v3 (无 VAD) 干净,没"嗯""啊"幻觉
- [ ] 端到端延迟比 v3 短(主观感受)

### 8.4 M1-D 验证(单测)

- [ ] 5 个测试用例全过(静音/单段/多段/降级/超长)
- [ ] 模型文件不存在时测试 skip(`@EnabledIf`)

### 8.5 M2 验证(联调)

- [ ] 浏览器 mic 按钮 + 说话 + 中间停顿 + 松开
- [ ] STT 输出语义正确,无幻觉
- [ ] chat.sendText 收到的文本是 VAD 拼接后的最终文本
- [ ] TTS 正常合成 → 浏览器播放

---

## 九、风险评估

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| silero-vad.onnx 跟 sherpa-onnx 版本不兼容 | 中 | VAD 加载失败 → 降级到整段 STT(可接受) | M1-B 开工前先跑 hello world 验证 |
| sherpa-onnx 1.13.4 OfflineVad API 跟示例不一致 | 中 | M1-B 卡住 | 开工前查 sherpa-onnx 1.13.4 Java API doc,实测一个 sample |
| BT 麦克风底噪导致 VAD 阈值不合适 | 中 | 误识别静音/漏识别语音 | 配 `vad.threshold` 可调,M2 实测调 |
| 多段切分后 STT 上下文丢失(段间停顿被吃) | 低 | 语义不连贯 | D5 已选每段独立 STT + 空格拼接,实测验证 |
| VAD 把一个长句切碎(每段太短,STT 不准) | 中 | 识别率下降 | `minSpeechDurationMs=250` 兜底,实测调 |
| audio.end 时 audio buffer 太大(>10MB)导致 VAD 慢 | 低 | 延迟增加 | OfflineVad 整段处理,~10s 音频 < 100ms,实测验证 |
| VAD 切分后 STT 调用次数变多,RTF 累加 | 中 | 延迟增加 | 实测每段 STT 时间,确认总耗时 ≤ 老路径 |

---

## 十、参考资料

### 10.1 相关 commit / PR

- `20fcf24` PR #2 KWS wake(同 ecosystem 参考)
- `d5aa228` KwsService 3 个 setter 修复(sherpa-onnx 已知坑教训)
- `02ca2b1` PR #1 v2 STT/TTS
- `14f0936` 后端 audio.* WS 协议(M1-B VAD 集成参考)

### 10.2 相关文档

- `docs/voice-node-v2-stt-tts.md`(D5/D7 等决策表风格参考)
- `docs/voice-node-v3-kws-wake.md`(独立模块设计风格参考)
- `memory/2026-07-23.md`(VAD 方案 B 的来源)

### 10.3 外部参考

- **sherpa-onnx Java API**:https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/java-api/src/main/java/com/k2fsa/sherpa/onnx/OfflineVadModelConfig.java
- **silero-vad ONNX 模型**:https://github.com/snakers4/silero-vad
- **download-kws-deps.sh**:`scripts/` 同目录可参考模型下载风格

### 10.4 sherpa-onnx VAD API 备注

> ⚠️ **开工前必做**:查 sherpa-onnx 1.13.4 Java API,确认 `OfflineVadModelConfig` 的具体方法签名和 `acceptWaveform()` 返回结构。本文 4.1.2 给的是大致的接口形状,以实测为准。

---

## 十一、开工前必做清单

### 11.1 实测 sherpa-onnx 1.13.4 OfflineVad API

```bash
# 1. 临时写个 hello world Java
# 2. 下载 silero-vad.onnx 到 models/vad/
# 3. 调 OfflineVadModelConfig + OfflineVad
# 4. 喂一段 5s 静音 + 5s 语音的 PCM
# 5. 确认返回的 segments 结构
# 6. 把实测结果填到本文档 §4.1.2
```

### 11.2 实测 silero-vad BT 麦克风底噪

```bash
# 1. 用 BT 麦克风(Mi Bluetooth Neckband Earphones Basic)录 10s "环境音"
# 2. 喂给 OfflineVad,看默认 0.5 阈值是否误识别为语音
# 3. 如果误识别,调阈值到 0.55~0.6
```

### 11.3 工作区现状(2026-07-30 14:35 实测)

```
On branch feat/sherpa-onnx-1.13.3-downgrade
Your branch is up to date with 'origin/feat/sherpa-onnx-1.13.3-downgrade'.

nothing to commit, working tree clean
(注:用户后续会切到 feat/voice-node-v3-vad 开始 VAD 开发)
```

---

## 拍板进度跟踪

- ✅ **D1 (VAD 引擎)** — silero-vad
- ✅ **D2 (集成方式)** — sherpa-onnx
- ✅ **D3 (VAD 模式)** — offline
- ✅ **D4 (切分粒度)** — 多段独立
- ✅ **D5 (STT 调用)** — 每段独立 + 拼接
- ✅ **D9 (数据格式)** — Float32
- ✅ **D10 (失败降级)** — 整段 STT
- ✅ **D11 (跟 KWS 关系)** — 正交
- ✅ **D12 (配置文件)** — 合进 application.yml
- ✅ **D13 (前端改动)** — 零改动
- ⏳ **D6 (threshold)** — 默认 0.5,待实测
- ⏳ **D7 (minSpeechDuration)** — 默认 250ms,待实测
- ⏳ **D8 (minSilenceDuration)** — 默认 100ms,待实测
- ⏳ **T1-T4** — 待开工实测

---

**下一步**:老板 review 设计稿 → 确认 D6/D7/D8/T1-T4 → 切 `feat/voice-node-v3-vad` 分支 → M1-A 开工