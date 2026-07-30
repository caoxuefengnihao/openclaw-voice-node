# Voice Node v4: 流式 VAD + 流式 ASR + 流式 TTS 设计文档

> 状态: **v1 设计稿(草稿),等老板拍板**
> 创建: 2026-07-30 15:56 by 普罗米修斯 (CTO agent)
> 起源: 2026-07-30 15:47 用户提"流式 VAD + 流式 ASR + 流式 TTS 能实现么" → 选 🅱 token 级流式
> 关联:
>   - `7e531f0` v3-vad M1-C (VAD 离线切分已落地,作为 v4 流式基础)
>   - `f5eefa9` v3-vad M1-B + M1-D (VadService 实现)
>   - `dbe6266` v3-vad M1-A (设计文档 + 配置)
>   - `20fcf24` PR #2 KWS wake
>   - `docs/voice-node-v3-vad.md` (v3 设计文档)
>   - `docs/voice-node-v3-kws-wake.md` (KWS 设计文档)

---

## 一、目标与范围

### 1.1 业务目标

把 voice-node 从"一次性唤醒→录音→STT→chat→TTS→回 KWS"升级到 **"唤醒一次,连续多轮对话,全链路低延迟(<500ms)"**,达到"小爱同学" / GPT-4o Realtime 的体验级别。

| 维度 | 现在 (v3) | 目标 (v4) |
|---|---|---|
| 唤醒方式 | 喊一次 → 一次性交互 → **再喊一次**才能问下一句 | 喊一次 → **连续多轮对话** → 30s+ 无活动自动退出 |
| 字幕反馈 | ❌ 无（用户说完才出文字） | ✅ 实时 partial 字幕(用户边说边看到自己的话) |
| LLM 回复 | 已经流式 (gateway delta) ✅ | 不变 |
| TTS 播放 | 攒完整句合成后播放 | 句子级 chunked 流式合成,听感延迟 <500ms |
| 用户打断 | ❌ 不支持（TTS 播放时 mic 仍录音但不响应） | ✅ barge-in（TTS 期间用户说话立即停播放） |

### 1.2 核心原则

> **复用 v3 资产,流式化最小改动。**

- ✅ 复用 sherpa-onnx 同库(zero 新 Maven 依赖)
  - `OfflineRecognizer` → `OnlineRecognizer`
  - `OfflineParaformerModelConfig` → `OnlineParaformerModelConfig`
  - silero-vad 本来就是 streaming API,复用 `Vad.acceptWaveform` 改成"持续监听不 reset"
- ✅ 复用 MiniMax T2A v2 stream mode(API 已支持)
- ✅ 复用 chat.sendText + gateway delta(LLM 流式已就绪)
- ✅ 复用现有 WebSocket 协议(`/ws` 端点不变),仅扩展消息类型
- ❌ 不引入新第三方(不接 OpenAI Realtime / WebRTC VAD)

### 1.3 不在本文档范围

- ❌ OpenClaw gateway 协议改造(gateway 已经是 delta 流式,不动)
- ❌ STT 模型升级(Paraformer-zh 流式版够用)
- ❌ TTS 音色切换(本期不变)
- ❌ 多语言切换(本期只做中文)

---

## 二、现状事实(代码扒出来,2026-07-30 15:50 实测)

### 2.1 v3 当前链路

```
浏览器                              Java 后端                                OpenClaw Gateway
─────                              ────────                                ────────────────
audio.start                        清空 PCM buffer
audio.chunk (binary Int16) →       ByteArrayOutputStream.write()
                                   ↓ (累积,等 audio.end)
audio.end                          audio.end 分支:
                                     ├─ Int16 → Float32 (AudioUtil)
                                     ├─ vadService.split() → [段1, 段2, ...]
                                     ├─ 每段 STT → text1, text2, ...
                                     ├─ 拼接 finalText
                                     └─ chat.sendText(finalText) ─────────→
                                                                       ← delta
                                                                       ← turn.done
                                     ├─ ttsService.synthesize(text)
                                     └─ sendToBrowser assistant.audio
                                   ↓ (回到 idle,等下次 audio.start 或 KWS 唤醒)
```

**3 个核心瓶颈**：

1. **批量 STT** — 整段识别,用户说完等 1-2s 才出文字
2. **批量 TTS** — 整段合成,LLM 出全文字后才开始念
3. **一次性交互** — TTS 播完就 idle,要问下一句得重新唤醒

### 2.2 现有 sherpa-onnx 资产

- ✅ sherpa-onnx 1.13.4 已装(jar + native lib)
- ✅ Paraformer-zh INT8 (OfflineRecognizer 工作正常)
- ✅ silero-vad v4 (Vad 类工作正常,已实测识别真实语音)
- ✅ KWS KeywordSpotter 已集成(跟流式链路正交)

**新增依赖**: **零**——所有流式 API 都在同库:
- `com.k2fsa.sherpa.onnx.OnlineRecognizer` (流式 ASR)
- `com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig`
- `com.k2fsa.sherpa.onnx.OnlineStream` (流式 stream,跟 OfflineStream 同 lifecycle)

### 2.3 MiniMax T2A v2 stream API(2026-07-30 文档实测)

请求体加 `stream: true`:
```json
{
  "model": "speech-2.8-hd",
  "text": "你好,我是 cto。",
  "stream": true,
  "voice_setting": {...},
  "audio_setting": {...}
}
```

响应: chunked transfer encoding,每段 ~100-200ms MP3 bytes,最后一段带结束标记。

限制:
- 单次请求 text 长度上限 ~5000 chars
- 流式 chunk size 不可控(服务端决定)
- rate limit: 60 req/min (跟非流式共享配额)

---

## 三、目标行为

### 3.1 新链路图

```
浏览器                                Java 后端                                  OpenClaw Gateway
──────                                ────────                                  ────────────────
audio.start (mode=streaming)         初始化 OnlineRecognizer + Vad session
audio.chunk (binary) ──────────────▶ ├─ [VAD 在线] 每 100ms acceptWaveform
                                     │   detect segment → emit {type:"vad.segment"}
                                     │
                                     ├─ [ASR 在线] 每帧 acceptWaveform → OnlineStream
                                     │   decode → partial text → emit {type:"user.partial"}
                                     │
                                     │  ... 持续推送 partial 给前端(用户看到字幕滚动) ...
                                     │
                                     ├─ [端点检测] VAD 检测到静音 ≥ 300ms
                                     │   → ASR finalize → final text
                                     │   → emit {type:"user.text", isFinal:true}
                                     │   → chat.sendText(finalText) ─────────────────→
                                     │                                            ← delta(已流式)
                                     │                                            ← delta
                                     │                                            ← ...
                                     │                                            ← turn.done
                                     │
                                     ├─ [TTS 流式] delta 累积到 sentence 边界(。"！？)
                                     │   对每句 chunked 请求 MiniMax stream API
                                     │   → 每段 MP3 → emit {type:"assistant.audio.chunk"}
                                     │
                                     ▼ (回到 streaming 模式监听,不需要重新唤醒)
audio.chunk (binary) ──────────────▶ [barge-in 检测] mic 说话 → 立即停 TTS → 回到 ASR 流式
                                     ↓
                                     ... 持续对话 ...
                                     ↓
audio.stop (前端主动结束)             30s+ 无活动 → 自动回到 KWS 监听
```

### 3.2 行为变化(用户视角)

| 场景 | 现在 (v3) | 目标 (v4) |
|---|---|---|
| 用户说:"今天天气" | 说完等 1-2s 出文字 | 边说边出 partial:"今" → "今天" → "今天天气" |
| LLM 回复长句 | 等 LLM 出全文字 + 整段 TTS,延迟 2-3s | LLM 出第一句立即 TTS chunked,听感延迟 <500ms |
| 用户打断 TTS | ❌ TTS 继续播,用户得听完 | ✅ 用户开口立即停 TTS,接听用户输入 |
| 连续对话 | ❌ 每句都得唤醒一次 | ✅ 唤醒一次,连续 N 轮 |
| 异常退出 | 唤醒无响应 → 重新唤醒 | 30s 无活动自动回 KWS,可再次唤醒 |

---

## 四、技术实现分阶段

### 4.1 M1:VAD + ASR 流式化(底座)

#### 4.1.1 M1-A:VAD 在线监听(改 VadService)

**目标**: `VadService` 加 `startStreaming()` / `acceptFrame()` / `stopStreaming()`,持续监听不 reset。

**新增方法**:
```java
public void startStreaming() {
    // 跟 init() 一样,但 vad 实例不释放
}

public synchronized void onSegmentDetected(Consumer<VadSegment> callback) {
    // 每 100ms 检查 vad.empty(),有 segment 则回调
    // 用 ScheduledExecutorService 定时 poll
}

public synchronized void acceptFrame(float[] chunk) {
    vad.acceptWaveform(chunk);
    // 不 flush,不 reset,持续累积
}

public void stopStreaming() {
    vad.flush();
    vad.reset();
}
```

**M1-A 复杂度**: ~80 行改动,基本复用 v3 VadService 框架。

#### 4.1.2 M1-B:ASR 替换为 OnlineRecognizer(新建 OnlineSttService)

**新建文件**: `service/OnlineSttService.java` (~300 行)

**核心 API**:
```java
@Slf4j
@Service
public class OnlineSttService {
    private OnlineRecognizer recognizer;

    @PostConstruct
    public void init() {
        OnlineParaformerModelConfig paraformer = OnlineParaformerModelConfig.builder()
            .setModel(modelPath).build();
        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
            .setParaformer(paraformer).setTokens(tokensPath)
            .setNumThreads(2).build();
        OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
            .setOnlineModelConfig(modelConfig).build();
        recognizer = new OnlineRecognizer(config);
    }

    public OnlineSttSession createSession(String sessionId) {
        OnlineStream stream = recognizer.createStream();
        return new OnlineSttSession(sessionId, stream, recognizer);
    }
}

public class OnlineSttSession {
    private String sessionId;
    private OnlineStream stream;
    private OnlineRecognizer recognizer;
    private String lastPartial = "";  // 去重用,只推变化的 partial

    public void acceptFrame(float[] samples, int sampleRate) {
        stream.acceptWaveform(samples, sampleRate);
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream);
        }
        String text = recognizer.getResult(stream).getText();
        if (!text.equals(lastPartial)) {
            // 推 partial 给前端(去重避免重复推)
            callback.onPartial(text);
            lastPartial = text;
        }
    }

    public String finalize() {
        stream.acceptWaveform(endMarker, sampleRate);  // 触发最后 decode
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream);
        }
        String finalText = recognizer.getResult(stream).getText();
        stream.release();
        return finalText;
    }
}
```

**M1-B 复杂度**: ~300 行新建,核心是 partial 去重和 finalize 触发逻辑。

#### 4.1.3 M1-C:WebSocketHandler 流式分支(改 handler)

**目标**: `VoiceWebSocketHandler.audio.start` 时,mode="streaming",持续推送 partial 给前端,端点检测时自动 finalize。

**改文件**: `api/VoiceWebSocketHandler.java` (~150 行改动)

**新增消息类型**:
| type | 方向 | payload | 说明 |
|---|---|---|---|
| `user.partial` | Java → 浏览器 | `{text, isFinal:false}` | 流式 STT partial 结果,前端做字幕滚动 |
| `vad.segment` | Java → 浏览器 | `{startMs, endMs}` | VAD 检测到说话段(可选,调试用) |
| `assistant.audio.chunk` | Java → 浏览器 | `{audio:base64, format:"mp3"}` | 流式 TTS chunk,前端 AudioContext 边收边播 |
| `barge_in` | 浏览器 → Java | `{}` | 用户打断 TTS(可选) |
| `streaming.stop` | 浏览器 → Java | `{}` | 主动结束流式会话 |

**M1-C 复杂度**: 协议扩展 + handler 多一个 streaming 分支,~150 行。

### 4.2 M2:TTS 流式 + 句子切分

#### 4.2.1 M2-A:TtsService stream 模式(改 TtsService)

**新增方法**:
```java
/**
 * 流式合成:输入文本分多次推送(每次一句),输出 chunked MP3 bytes。
 * @param sentenceProvider 提供句子边界标记
 * @return OutputStream<MP3 bytes> 每次推一段 ~100-200ms 音频
 */
public void synthesizeStream(Consumer<byte[]> chunkConsumer, String initialText) {
    // 调 MiniMax stream=true API
    // 用 HttpClient.sendAsync() + BodyHandlers.ofLines() 流式读 SSE
    // 每段 hex 解码 → bytes → chunkConsumer.accept(bytes)
}
```

**M2-A 复杂度**: ~120 行,主要是 HTTP 流式响应的 Java 处理。

#### 4.2.2 M2-B:句子边界检测(LLM delta 处理)

**目标**: LLM 流式 delta 累积时,识别句子边界(中文 "。" "！" "？"),触发 TTS chunk。

**实现位置**: `ChatSessionHandle` 或 `VoiceWebSocketHandler` 加一个 `SentenceBuffer`:

```java
public class SentenceBuffer {
    private StringBuilder buf = new StringBuilder();
    
    public List<String> onDelta(String delta) {
        buf.append(delta);
        List<String> sentences = new ArrayList<>();
        // 找中文标点边界
        int boundary;
        while ((boundary = findSentenceBoundary(buf)) != -1) {
            sentences.add(buf.substring(0, boundary + 1));
            buf.delete(0, boundary + 1);
        }
        return sentences;  // 0-N 个完整句子
    }
    
    private int findSentenceBoundary(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n') return i;
        }
        return -1;
    }
}
```

**M2-B 复杂度**: ~50 行新工具类 + handler 集成 ~30 行。

### 4.3 M3:打断(barge-in)

#### 4.3.1 M3-A:前端 TTS 播放器可中断

**改文件**: `frontend/src/App.vue` + 新建 `frontend/src/audio/tts-player.ts`

**核心**:
```typescript
export class TtsPlayer {
  private audioContext: AudioContext;
  private queue: AudioBuffer[] = [];
  private isPlaying = false;
  private interrupted = false;

  async playChunk(mp3Bytes: Uint8Array) {
    const buffer = await this.audioContext.decodeAudioData(mp3Bytes);
    this.queue.push(buffer);
    if (!this.isPlaying) this.playNext();
  }

  interrupt() {
    this.interrupted = true;
    this.queue = [];
    // 停当前正在播的 source node
    this.currentSource?.stop();
    // 通知后端停 TTS
  }
}
```

#### 4.3.2 M3-B:mic 检测到说话触发打断

**前端逻辑**:
```typescript
recorder.onVolumeThreshold = (rms) => {
  if (rms > 0.05 && ttsPlayer.isPlaying) {
    ttsPlayer.interrupt();
    ws.send({type:"barge_in"});
  }
};
```

**后端收到 barge_in**: 立即停当前 TTS HTTP 请求 + 切换回 ASR 流式。

**M3 复杂度**: 前端 ~150 行 + 后端 ~30 行,**但前端 AudioContext 改造涉及整体重写,风险最高**。

### 4.4 M4:端点检测 + 激活窗口

#### 4.4.1 M4-A:VAD 端点检测

**silero-vad 在线模式的 segment 间隔** ≥ 300ms = 用户说完。

集成到 `OnlineSttSession`:
```java
// vad 检测到 segment end → 等 300ms → 没新 segment → 触发 finalize
ScheduledExecutorService scheduler = ...;
scheduler.schedule(() -> {
    if (lastSegmentAt + 300 < now()) {
        String final = finalize();
        callback.onFinal(final);
    }
}, 300, MILLISECONDS);
```

#### 4.4.2 M4-B:激活窗口超时

**handler 加 `ATTR_LAST_ACTIVE_AT`**:
- 每个 binary 帧 / chat 事件 → 更新 `lastActiveAt`
- 定期检查 (60s 没活动) → 自动 stop streaming + 回到 KWS 监听

**M4 复杂度**: ~50 行 handler 改造。

---

## 五、设计决策表

| # | 决策项 | 选项 | 推荐 | 理由 |
|---|---|---|---|---|
| D1 | VAD 流式引擎 | A) silero-vad 在线模式 / B) WebRTC VAD | **✅ A silero-vad** | v3 已用,同库,准确率更高 |
| D2 | 流式 ASR 引擎 | A) sherpa-onnx OnlineRecognizer / B) OpenAI Whisper API streaming / C) faster-whisper streaming | **✅ A sherpa-onnx** | 零新依赖,中文 Paraformer-zh 流式 SOTA,本地无 API 费用 |
| D3 | 流式 TTS | A) MiniMax T2A v2 stream / B) 自部署 CosyVoice streaming | **✅ A MiniMax** | 现成 API,中文天花板级,1-2 天集成 vs CosyVoice 流式 1-2 周 |
| D4 | Partial 推送频率 | A) 每帧推 / B) 每 100ms / C) 每 500ms / D) 仅变化时推 | **✅ D 仅变化时** | 减协议开销,前端不刷屏;VAD 段切分时强制推 |
| D5 | 端点检测方式 | A) VAD 静音 300ms / B) 静音 500ms / C) 前端按钮结束 | **✅ B 静音 500ms** | 太短误切,太长延迟;500ms 平衡 |
| D6 | 句子边界规则 | A) 中文标点(。"！？) / B) LLM 自带 stop token / C) 自定义正则 | **✅ A 中文标点** | 简单可靠,LLM 偶尔会在中段出标点但可接受 |
| D7 | Barge-in 打断 | A) v4 做 / B) v5 做 / C) 不做 | **⏳ B v5 做** | v4 工作量大,打断是锦上添花非必须 |
| D8 | 流式 STT fallback | A) 始终用流式 / B) 流式准确率低时切整段 | **✅ A 始终流式** | v4 一致性优先,fallback 复杂度高 |
| D9 | 激活窗口时长 | A) 30s / B) 60s / C) 120s / D) 无限 | **✅ B 60s** | 太短打断体验,太长浪费;60s 平衡 |
| D10 | 流式链路状态切换 | A) 前端控 / B) 后端控 | **✅ A 前端控** | 现有架构一致,前端决定何时开始/结束 |
| D11 | 流式链路跟 KWS 关系 | A) 流式期间 KWS off / B) 流式期间 KWS 监听但禁用 | **✅ A KWS off** | 跟 v3 一致,KWS 只在 idle 监听 |
| D12 | 流式 STT vs v3 STT 共存 | A) v4 替换 / B) 配置文件切换 / C) 同时存在 | **✅ B 配置文件切换** | 灰度上线,v3/v4 并行一段时间 |

---

## 六、待拍板项(老板必看)

### T1. v4 范围 ⏳ 待拍板

**默认**: M1 + M2 全做 (VAD/ASR/TTS 流式化),M3 (barge-in) 推到 v5

**决策点**: 今晚联调完 v3-vad 后,是否立刻启动 v4?

### T2. 流式 ASR 模型选择 ⏳ 待拍板

**候选**:
- A) Paraformer-zh INT8 Online 版本 (~230MB,RTF ~0.1,中文 SOTA)
- B) SenseVoice Small (~230MB,RTF ~0.05,多语言,自带 VAD)
- C) 流式 Whisper (~150MB,准确率低 5-10%)

**默认**: A) Paraformer-zh,跟 v3 STT 模型一致,零替换成本

### T3. 句子边界 fallback ⏳ 待拍板

**默认**: 累积 800ms 没新 delta 也算一句(避免 LLM 长句一直不出标点时 TTS 卡住)

**决策点**: 800ms 太短? 1.5s? 跟端点检测时长联动?

### T4. 流式 ASR 模型存放路径 ⏳ 待拍板

**候选**:
- A) 跟 v3 一样 `${user.dir}/models/stt`(同一个目录,模型文件共用)
- B) 单独 `${user.dir}/models/stt-online`(隔离)

**默认**: A) 共用,但文件名区分(`model-online.int8.onnx`)

### T5. 流式链路超时 ⏳ 待拍板

**默认**:
- ASR partial 超过 30s 没新结果 → 强制 finalize (防止卡死)
- LLM delta 超过 30s 没新消息 → 强制 turn.done
- 整条链路 60s 无活动 → 自动回 KWS

**决策点**: 太短/太长? 跟用户实际对话节奏相关

### T6. v4 是否分批实现 ⏳ 待拍板

**候选**:
- A) 一次性做 M1+M2 (~1.5 周)
- B) 分两批: M1 先(底座 ~1 周),稳了再做 M2(TTS 流式 ~3-4 天)

**默认**: B) 分批,降低风险

---

## 七、PR 拆分建议

**当前分支**: `feat/voice-node-v3-vad`(从 main 拉,可继续用 / 或新建 `feat/voice-node-v4-streaming`)

| PR | 标题 | 工作量 | 依赖 | 改动文件 |
|---|---|---|---|---|
| **M1-A** | VadService 流式化 (startStreaming / onSegment) | ~80 行 | 无(纯重构) | `service/VadService.java` |
| **M1-B** | OnlineSttService 新建 (OnlineRecognizer 包装) | ~300 行 | 无(新建) | `service/OnlineSttService.java` / `config/OnlineSttProps.java` |
| **M1-C** | WebSocketHandler 流式分支 (audio.start mode=streaming) | ~150 行 | M1-A, M1-B | `api/VoiceWebSocketHandler.java` |
| **M2-A** | TtsService stream 模式 (MiniMax stream=true) | ~120 行 | 无(独立) | `service/TtsService.java` |
| **M2-B** | SentenceBuffer + delta 处理 | ~50 行 + ~30 行 | M2-A | 新建 `util/SentenceBuffer.java` + handler 集成 |
| **M3-A** | 前端 TtsPlayer 可中断 | ~150 行 | 无 | `frontend/src/audio/tts-player.ts` |
| **M3-B** | mic barge-in 检测 | ~30 行 + 前端 ~80 行 | M3-A | handler + 前端 |
| **M4** | 端点检测 + 激活窗口 | ~50 行 | M1-C | handler |

**总工作量估计**: ~960 行新代码 + 重构(其中前端 ~230 行)

**分批建议**:
- **PR 1**: M1-A + M1-B (底座,~1 周)
- **PR 2**: M1-C (handler 集成,~3-4 天)
- **PR 3**: M2-A + M2-B (TTS 流式,~3-4 天)
- **PR 4**: M3 + M4 (打断 + 端点,~1 周,**风险最高,放最后**)

---

## 八、验证清单

### 8.1 M1-A 验证(VAD 流式)

- [ ] `VadService.startStreaming()` 不释放 native handle
- [ ] `acceptFrame()` 持续调用无异常
- [ ] `onSegmentDetected` callback 在 segment 准备好时被调用
- [ ] 跟 v3 离线 `split()` 结果一致(同样的 WAV 输入)

### 8.2 M1-B 验证(流式 ASR)

- [ ] OnlineRecognizer 加载 Paraformer-zh 流式模型
- [ ] partial 文本增量推(不重复推相同文本)
- [ ] finalize 触发后返回完整文本
- [ ] 流式 STT 准确率 vs 离线 STT (差值 < 10%)

### 8.3 M1-C 验证(handler 流式分支)

- [ ] `audio.start` mode=streaming 后,binary 帧触发 VAD + ASR
- [ ] 前端收到 `user.partial` 事件(字幕滚动)
- [ ] VAD 端点检测 500ms 静音后自动 finalize
- [ ] 浏览器收到 `user.text` isFinal=true + chat.sendText 触发

### 8.4 M2-A 验证(流式 TTS)

- [ ] MiniMax stream API 调通(看响应是 chunked)
- [ ] 每 chunk ~100-200ms MP3
- [ ] 多句连续合成无中断

### 8.5 M2-B 验证(句子切分)

- [ ] LLM delta "你好,我是 cto。今天" → 拆 ["你好,我是 cto。", "今天"]
- [ ] 句子累积超过 800ms 强制切分(防止卡住)
- [ ] 每句独立 TTS chunked 推前端

### 8.6 M3 验证(打断)

- [ ] TTS 播放时 mic 检测到声音 → 前端发 `barge_in`
- [ ] 后端立即停当前 TTS HTTP 请求
- [ ] 切换回 ASR 流式监听
- [ ] 用户输入流畅(无残音 / 卡顿)

### 8.7 M4 验证(端点 + 窗口)

- [ ] VAD 静音 500ms 自动 finalize
- [ ] 60s 无活动自动 stop streaming + 回 KWS
- [ ] 用户在窗口内可连续 N 轮

---

## 九、风险评估

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 流式 ASR 准确率 < 离线 5-10% | 高 | 用户体验下降 | M1-B 验收时实测对比 v3,差太多就降级用 v3 整段 |
| LLM delta 中段出标点导致切碎 | 中 | TTS chunk 过碎,听感断裂 | 句子切分 + 800ms fallback,综合决策 |
| MiniMax stream API rate limit | 中 | 60 req/min 可能不够(对话频繁时) | 申请提额 或 fallback 非流式 |
| 前端 AudioContext 打断重构 | **高** | M3 工作量失控 | M3 推到 v5,v4 只做 M1+M2 |
| barge-in 误触发(噪声) | 高 | 用户没说话 TTS 莫名停 | mic RMS 阈值调优,需要实测 BT 麦底噪 |
| 端点检测 500ms 太长 | 中 | 用户说完等 0.5s 才响应 | 实测调优,可能调到 300ms |
| 流式链路 CPU 开销持续 | 中 | 桌面端没事,机器人端可能有问题 | 桌面机器人方向(Reachy Mini)再评估 |

---

## 十、参考资料

### 10.1 内部文档 / commit

- `docs/voice-node-v3-vad.md` (v3 设计,VAD 离线基础)
- `docs/voice-node-v3-kws-wake.md` (KWS 设计,跟流式链路正交)
- `docs/voice-node-v2-stt-tts.md` (v2 STT/TTS 设计)
- `7e531f0` v3-vad M1-C (已落地的 VAD 集成)
- `f5eefa9` v3-vad M1-B (VadService 实现)
- `dbe6266` v3-vad M1-A (设计 + 配置)

### 10.2 外部文档

- sherpa-onnx Java API:
  - `OnlineRecognizer`:https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/java-api/src/main/java/com/k2fsa/sherpa/onnx/OnlineRecognizer.java
  - `OnlineParaformerModelConfig`:同目录
- MiniMax T2A v2 stream API:https://platform.MiniMax.com/docs/api-reference/llms/t2a-v2-stream
- silero-vad 在线模式:https://github.com/snakers4/silero-vad

### 10.3 参考架构(非直接采用)

- **GPT-4o Realtime API**: 全双工流式,WebSocket 双向 audio,延迟 ~300ms
  - 参考点:协议设计 (server→client audio chunks + client→server mic chunks)
- **WebRTC VAD**: 嵌入式 VAD,极轻量,准确率一般
- **OpenAI Whisper streaming**: 准确率高但 API 费用 + 延迟高

---

## 十一、开工前必做

### 11.1 实测 sherpa-onnx OnlineRecognizer + Paraformer-zh 流式

```bash
# 1. 下载 Paraformer-zh 流式模型 (跟 v3 同一个,只是不同文件)
#    路径:models/stt/model-online.int8.onnx
# 2. 写 hello world 类跟 VadProbeTest 类似
# 3. 喂真实语音 + 打印 partial 结果
# 4. 验证准确率 vs OfflineRecognizer (差值 < 10%)
```

### 11.2 实测 MiniMax T2A v2 stream API

```bash
# 1. 用 curl 测 stream=true 响应
curl -X POST https://api.MiniMax.com/v1/t2a_v2 \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"speech-2.8-hd","text":"你好,我是 cto。今天天气很好。","stream":true,...}'
# 2. 看响应是 chunked transfer encoding + 每段 MP3 字节
# 3. Java HttpClient.sendAsync + BodyHandlers.ofLines 的处理方式验证
```

### 11.3 浏览器 AudioContext 边收边播 调研

```bash
# 1. 看现有 frontend/src/App.vue TTS 播放逻辑
# 2. 设计可中断 TtsPlayer 类的接口
# 3. 评估重构成本(是改 1 个文件还是 N 个文件)
```

---

## 十二、状态更新

### 12.1 依赖 v3-vad 联调结果

| v3 联调结果 | v4 启动决策 |
|---|---|
| VAD 切分效果差(段数过多/过少) | 调优 v3 阈值,**暂不启动 v4**,先稳 v3 |
| VAD 切分效果好(用户满意) | **立即启动 v4**,目标 2 周内出 PR 1(M1-A + M1-B) |

### 12.2 v4 整体节奏

```
今晚: v3-vad 联调,看效果
明早: 拍板 v4 启动 + T1-T6 待拍板项
本周: M1-A (VadService 流式化,80 行)
下周: M1-B (OnlineSttService,300 行) — 底座完成
第三周: M1-C (handler 流式分支,150 行) — 第一版可演示
第四周: M2-A + M2-B (TTS 流式 + 句子切分,200 行) — 全链路流式
(可选)第五周: M3 + M4 (打断 + 端点,~230 行) — 完整小爱同学体验
```

---

## 拍板进度跟踪

- ✅ **D1** silero-vad 在线
- ✅ **D2** sherpa-onnx OnlineRecognizer
- ✅ **D3** MiniMax T2A v2 stream
- ✅ **D4** partial 仅变化时推
- ✅ **D5** VAD 静音 500ms
- ✅ **D6** 中文标点切分
- ✅ **D8** 始终流式(无 fallback)
- ✅ **D9** 60s 激活窗口
- ✅ **D10** 前端控模式
- ✅ **D11** 流式期间 KWS off
- ✅ **D12** 配置文件切换 v3/v4
- ⏳ **D7** barge-in 推到 v5
- ⏳ **T1** v4 范围(待 v3 联调结果)
- ⏳ **T2** 流式 ASR 模型选择
- ⏳ **T3** 句子边界 fallback 时长
- ⏳ **T4** 流式模型存放路径
- ⏳ **T5** 流式链路超时时长
- ⏳ **T6** v4 是否分批实现

---

**下一步**:老板 review 设计稿 → 拍板 D7 + T1-T6 → 等今晚 v3-vad 联调结果 → 决定是否启动 v4