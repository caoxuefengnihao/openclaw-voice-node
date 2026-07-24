# Voice Node v3: KWS 唤醒词集成 设计文档

> 状态: **v1 设计稿**,等老板拍板
> 创建: 2026-07-24 08:32 by 普罗米修斯 (CTO agent)
> 拍板线索:
>   - 2026-07-24 07:39 桌面机器人讨论 → "桌面机器人先不考虑了,先搞唤醒"
>   - 2026-07-24 08:24 唤醒路径拍板 → "肯定是 B,本地 KWS"
>   - 2026-07-24 08:27 sherpa-onnx KWS 选型确认 → "复用 STT 同一个库"
> 关联 commit:
>   - `02ca2b1` PR #1 (v2 STT/TTS main)
>   - `14f0936` 后端 M1-B audio.* WS 协议
>   - `5e6059e` 前端 M1-C AudioRecorder

---

## 一、目标与范围

### 1.1 业务目标

把 voice-node 从"点 mic 按钮 → 说话"升级到"喊一声 → 自动进入对话"。

具体：

| 维度 | 现在 (v2) | 目标 (v3) |
|---|---|---|
| 触发方式 | 手动点 mic 按钮 + 按住说话 | 喊唤醒词 "嗨 CTO" 自动触发 |
| 浏览器依赖 | 必须开前端页面 | 浏览器常驻即可 (低占用) |
| STT 起点 | 用户点完 mic 后 | 检测到唤醒词后自动开始 |
| TTS 终点 | 用户松 mic 后 | 用户说"拜拜"/超时自动结束 |
| 体验 | 跟 Siri 手动触发类似 | 跟"小爱同学"唤醒后对话类似 |

### 1.2 非目标 (本期不做)

- 多唤醒词 (只支持一个)
- 自训练唤醒词 (先用 sherpa-onnx 预训练词 + 占位)
- 嵌入式 KWS (ESP32-S3 上跑) — 桌面版够用
- macOS 系统级 Voice Wake — 跟"本地开发"原则冲突
- 跨用户 (只服务一个 sessionKey,跟 cto agent 绑定)

### 1.3 范围

✅ 包含:
- 后端 KWS 服务 (sherpa-onnx KeywordSpotter,跟 STT 同库)
- WS 协议扩展 (audio.kws.start / wake.detected 等)
- 前端持续监听模式 (低占用 KwsMonitor)
- 唤醒后自动进入对话流程 (复用现有 audio.start/end)
- 配置项 (KwsProps,灵敏度阈值)
- 单元测试 + 集成测试 + 真实场景测试

❌ 不包含:
- 自定义唤醒词训练 (放后续 milestone)
- 多客户端并发 KWS (单浏览器够用)
- 服务端唤醒日志审计 (生产化再补)

---

## 二、现状事实 (基于 2026-07-24 08:30 实测扫代码)

### 2.1 现有架构

```
浏览器                                       Java 后端                              OpenClaw Gateway
  │                                            │                                        │
  │ 点 mic 按钮                                │                                        │
  │  ─→ audio.start ─────────────────────────▶│                                        │
  │  ─→ audio.chunk (binary PCM) × N ────────▶│  累积到 buffer                         │
  │  ─→ audio.end ────────────────────────────▶│  SttService.recognize(pcm)              │
  │                                            │  ─→ "今天天气怎样"                     │
  │                                            │  chat.sendText(text) ──────────────────▶│
  │  ◀── user.text ───────────────────────────│                                        │
  │                                            │  ◀── assistant delta ──────────────────│
  │  ◀── assistant ───────────────────────────│                                        │
  │  ◀── turn.done ───────────────────────────│  TtsService.synthesize(text)            │
  │  ◀── assistant.audio (mp3 base64) ────────│                                        │
  │  浏览器播放 TTS                             │                                        │
```

### 2.2 关键代码点

- **后端 WS Handler**:`src/main/java/com/openclaw/voicenode/api/VoiceWebSocketHandler.java`
  - `audio.start` → 建 buffer
  - `<binary>` → 累积到 buffer (16kHz mono int16 LE PCM)
  - `audio.end` → SttService.recognize() → chat.sendText()
- **STT 服务**:`src/main/java/com/openclaw/voicenode/service/SttService.java`
  - 同步单线程 OfflineRecognizer (synchronized 方法)
  - Paraformer-zh INT8 模型 (~232MB)
- **STT 配置**:`src/main/java/com/openclaw/voicenode/config/SttProps.java`
  - `@ConfigurationProperties(prefix = "openclaw.stt")` record
  - `modelDir / numThreads / sampleRate`
- **前端录音**:`frontend/src/audio/recorder.ts`
  - AudioRecorder:点 mic → getUserMedia → AudioContext(16kHz) → AudioWorklet → 客户端发 binary
- **前端 WS**:`frontend/src/api/voiceClient.ts`
  - VoiceClient:`sendCommand(obj)` 发 JSON, `sendAudio(int16)` 发 binary
- **sherpa-onnx**: `pom.xml` 1.13.4 已集成,Java binding + osx-aarch64 native

### 2.3 依赖现状

- ✅ sherpa-onnx 1.13.4 (STT 用 Paraformer-zh) → 复用 KWS
- ✅ Spring Boot 3.3 + Java 21 + Jackson + Lombok
- ✅ Vue 3 + Vite + TypeScript
- ✅ WebSocket 二进制帧支持 (Spring `@MessageMapping` 风格 + 浏览器 `send(arraybuffer)`)
- ❌ 无 KWS 模型文件 — 需要新下载/训练
- ❌ 前端无持续监听组件 — 需要新写 KwsMonitor

### 2.4 性能预算 (供参考)

- sherpa-onnx KWS RTF (Real-Time Factor): ~0.05 (1s 音频用 50ms 推理)
- Mac mini M1 CPU 占用: < 5% (一个核,16kHz 单声道)
- 检测延迟: 唤醒词时长 + 100ms 推理 + 100ms 网络 = **~500ms 总延迟**
- 网络流量: 持续音频上传 16kHz × 2 bytes = **32 KB/s** per session
- 24h 监听磁盘/内存: 几乎为零 (不持久化音频)

---

## 三、架构设计 (v3)

### 3.1 高层架构

```
浏览器 (低占用 KwsMonitor)              Java 后端                          OpenClaw Gateway
  │                                       │                                      │
  │ 页面打开后自动启 KWS                   │                                      │
  │  ─→ audio.kws.start ────────────────▶│                                      │
  │  ─→ audio.chunk (binary PCM) × N ───▶│  KwsService.acceptFrame() 每帧过 KWS  │
  │  (持续, ~32 KB/s)                     │  KeywordSpotter (sherpa-onnx)        │
  │                                       │                                      │
  │                                       │  [检测到 "嗨 CTO"]                    │
  │  ◀── wake.detected ──────────────────│  发 wake 事件                        │
  │                                       │                                      │
  │ 自动进入对话模式                       │                                      │
  │  ─→ audio.start ─────────────────────▶│                                      │
  │  ─→ audio.chunk × N ────────────────▶│  SttService 流程 (跟 v2 一样)        │
  │  ─→ audio.end ──────────────────────▶│  chat.sendText() ──────────────────▶│
  │                                       │  ◀── assistant / turn.done ──────────│
  │  ◀── assistant / assistant.audio ────│  TtsService (跟 v2 一样)             │
  │                                       │                                      │
  │ 用户说完 → 进 idle 监听模式             │                                      │
  │  KwsMonitor 重启 audio.kws            │                                      │
```

### 3.2 状态机 (前端)

```
        ┌──────────────┐
        │     IDLE     │ (页面打开)
        └──────┬───────┘
               │ audio.kws.start
               ▼
        ┌──────────────┐
        │  LISTENING   │ ←──────────┐
        │  (KWS 检测中) │            │
        └──────┬───────┘            │
               │ wake.detected      │ 说完 + timeout
               ▼                    │
        ┌──────────────┐            │
        │   RECORDING  │ ───────────┘
        │  (用户说话)   │
        └──────┬───────┘
               │ audio.end
               ▼
        ┌──────────────┐
        │  PROCESSING  │
        │ (等 STT/回复) │
        └──────┬───────┘
               │ turn.done + TTS 播完
               ▼
        IDLE (自动回 LISTENING)
```

### 3.3 关键设计决策

| 决策 | 选项 | 选择 | 理由 |
|---|---|---|---|
| KWS 跑前端还是后端 | 前端 WASM / 后端 native | **后端 native** | 复用现有 audio.* WS 协议 + sherpa-onnx Java binding + 浏览器无 CPU 压力 |
| 监听模式触发时机 | 页面打开即启 / 用户主动启 | **页面打开即启** | 体验跟"小爱同学"一致 |
| 唤醒后自动录音 | 自动 / 用户确认 | **自动** | 减少操作;超时 fallback (10s 不说话回 IDLE) |
| 持续监听资源占用 | 高频 (30ms) / 中频 (100ms) | **中频 100ms** | 网络流量减 3 倍,KWS 检测精度足够 |
| KWS 模型 | 预训练 / 自训练 | **预训练先用,自训练后续** | 5 min 跑通流程,自训练加 1 天工作量 |

---

## 四、技术选型

### 4.1 为什么选 sherpa-onnx KWS

| 候选 | 优点 | 缺点 | 决策 |
|---|---|---|---|
| **sherpa-onnx KWS** | 你已经在用同一个库 (STT),零学习成本;Apache 2.0;中文友好;支持自定义 keywords 文件 | 模型精度略低于 Porcupine | ✅ **选这个** |
| openWakeWord | 新、Python 原生;支持多唤醒词 | Python-only,跟 Java 后端架构不匹配;需要 Python 进程 | ❌ |
| Picovoice Porcupine | 商业产品级准确率 | 商业授权 $0.05/设备/月;闭源 | ❌ |
| Mycroft Precise | 老牌开源 | Python 2 only,2020 后基本停更 | ❌ |
| Vosk KWS | Vosk 也有 KWS | 跟 STT 重复依赖,模型更大 | ❌ |
| macOS Voice Wake (Speech.framework) | 系统级,零代码 | 跟"本地开发"原则冲突;只支持预设词 | ❌ |

**核心论点:复用已有 sherpa-onnx 投资**。

### 4.2 模型选型

sherpa-onnx 提供的 KWS 模型系列 (GitHub Releases):

| 模型 | 大小 | 词表 | 备注 |
|---|---|---|---|
| `sherpa-onnx-kws-zh-wenet` | ~50MB | "你好米娅"、"嗨小爱" 等 | 通用中文,推荐先用 |
| `sherpa-onnx-kws-zipformer-wenetspeech` | ~100MB | 类似 | 精度更高,Mac mini 也跑得动 |
| 自训练 | 任意 | 任意 | 后续 milestone,本次不做 |

**决策:先用 `sherpa-onnx-kws-zh-wenet`**,5 min 跑通流程。自训练"嗨 CTO"放后续。

### 4.3 关键 API (Java binding)

```java
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;

// 模型文件
String encoderPath = props.modelDir() + "/encoder.onnx";
String decoderPath = props.modelDir() + "/decoder.onnx";  // 可选
String joinerPath = props.modelDir() + "/joiner.onnx";    // 可选
String tokensPath = props.modelDir() + "/tokens.txt";
String keywordsPath = props.modelDir() + "/keywords.txt";

// 构造
FeatureConfig featureConfig = FeatureConfig.builder()
    .setSampleRate(16000)
    .setFeatureDim(80)
    .build();

OnlineModelConfig modelConfig = OnlineModelConfig.builder()
    .setZipformer2Ctc(null)
    .setWenetCtc(com.k2fsa.sherpa.onnx.WenetCtcModelConfig.builder()
        .setModel(encoderPath)
        .setDecoder(decoderPath)  // optional
        .build())
    .setTokens(tokensPath)
    .setNumThreads(props.numThreads())
    .build();

KeywordSpotterConfig config = KeywordSpotterConfig.builder()
    .setOnlineModelConfig(modelConfig)
    .setFeatureConfig(featureConfig)
    .setKeywordsFile(keywordsPath)
    .setKeywordsThreshold(props.threshold())
    .setKeywordsScoreBias(props.scoreBias())
    .build();

KeywordSpotter spotter = new KeywordSpotter(config);

// 流式检测
OnlineStream stream = spotter.createStream();
stream.acceptWaveform(samples, 16000);  // float[] samples
while (spotter.isReady(stream)) {
    spotter.decode(stream);
}
KeywordSpotterResult result = spotter.getResult(stream);
if (!result.getKeyword().isEmpty()) {
    // 检测到唤醒词
    log.info("🔥 Wake detected: {}", result.getKeyword());
}
stream.release();
```

---

## 五、WS 协议扩展

### 5.1 完整协议 (v3 新增部分高亮)

**上行 (浏览器 → Java)**:

| type | 说明 | v3 新增 |
|---|---|---|
| `text` | 文本输入,绕过 KWS/STT 直接发 | - |
| `audio.start` | 开始录音 (单次对话用) | - |
| `<binary>` | PCM chunk (16kHz mono int16 LE) | - |
| `audio.end` | 结束录音,触发 STT | - |
| `audio.cancel` | 取消录音 | - |
| **`audio.kws.start`** | **开始 KWS 持续监听** | ✅ 新增 |
| **`audio.kws.stop`** | **停止 KWS 监听** | ✅ 新增 |
| `ping` | 心跳 | - |

**下行 (Java → 浏览器)**:

| type | 说明 | v3 新增 |
|---|---|---|
| `ready` | WS 连接 ready | - |
| `audio.ack` | 录音/STT ack | - |
| `user.text` | STT 识别文本 | - |
| `assistant` | LLM 回复文本 (流式) | - |
| `turn.done` | LLM 回复结束 | - |
| `assistant.audio` | TTS 音频 (base64 mp3) | - |
| `error` | 错误 | - |
| **`kws.ack`** | **KWS 监听已启动** | ✅ 新增 |
| **`wake.detected`** | **检测到唤醒词** | ✅ 新增 |
| **`kws.error`** | **KWS 检测出错** | ✅ 新增 |

### 5.2 新增消息 schema (JSON)

**上行**:

```json
// 启动 KWS 监听
{ "type": "audio.kws.start", "threshold": 0.6 }
// 停止 KWS 监听
{ "type": "audio.kws.stop" }
```

**下行**:

```json
// KWS 监听启动 ack
{ "type": "kws.ack", "state": "listening", "keywords": ["嗨 CTO"] }
// 检测到唤醒词
{
  "type": "wake.detected",
  "keyword": "嗨 CTO",
  "score": 0.82,
  "timestamp": 1721801234567
}
// KWS 出错
{ "type": "kws.error", "message": "..." }
```

---

## 六、后端详细设计

### 6.1 KwsService.java (新)

**类结构**:

```java
@Service
@Slf4j
public class KwsService {

    private final KwsProps props;
    private KeywordSpotter spotter;
    private final Map<String, OnlineStream> sessionStreams = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws Exception { /* 加载模型,类似 SttService.init() */ }

    @PreDestroy
    public void destroy() {
        spotter.release();
    }

    /** 创建或重置 session 的 KWS stream */
    public void startSession(String sessionId) {
        sessionStreams.put(sessionId, spotter.createStream());
    }

    /** 喂 PCM 帧进 KWS。返回检测到的 keyword,空表示未检测到。 */
    public synchronized String acceptFrame(String sessionId, byte[] pcmFrame) {
        OnlineStream stream = sessionStreams.get(sessionId);
        if (stream == null) return "";
        float[] samples = pcmToFloat(pcmFrame);
        stream.acceptWaveform(samples, 16000);
        while (spotter.isReady(stream)) {
            spotter.decode(stream);
        }
        KeywordSpotterResult result = spotter.getResult(stream);
        if (!result.getKeyword().isEmpty()) {
            // 命中 → 重置 stream,准备下一轮
            stream.release();
            sessionStreams.put(sessionId, spotter.createStream());
            return result.getKeyword();
        }
        return "";
    }

    public void stopSession(String sessionId) {
        OnlineStream stream = sessionStreams.remove(sessionId);
        if (stream != null) stream.release();
    }
}
```

**关键点**:
- 跟 `SttService` 一样,`acceptFrame` 是 `synchronized` —— sherpa-onnx 的 `KeywordSpotter` 是单线程
- 每次唤醒命中后**重置 stream** —— 避免重复触发同一段音频
- `sessionId` 用 WebSocket session id (`session.getId()`)
- 帧大小: 复用浏览器现有 `recorder.ts` 的 PCM chunk 大小 (~100ms = 1600 samples)

### 6.2 KwsProps.java (新)

```java
@ConfigurationProperties(prefix = "openclaw.kws")
public record KwsProps(
    String modelDir,        // 默认 ${project.basedir}/models/kws
    int numThreads,         // 默认 1 (跟 STT 共用 CPU)
    int sampleRate,         // 固定 16000
    float threshold,        // 默认 0.6 (越高越不灵敏)
    float scoreBias,        // 默认 1.0 (越高越倾向触发)
    boolean enabled         // 默认 true (允许 application.yml 关掉)
) {
    public KwsProps {
        if (modelDir == null || modelDir.isBlank())
            modelDir = "${project.basedir}/models/kws";
        if (numThreads <= 0) numThreads = 1;
        if (sampleRate != 16000) sampleRate = 16000;
        if (threshold < 0.1f || threshold > 0.9f) threshold = 0.6f;
        if (scoreBias < 0.5f || scoreBias > 2.0f) scoreBias = 1.0f;
    }
}
```

### 6.3 application.yml 新增

```yaml
openclaw:
  # ... 现有配置 ...
  kws:
    model-dir: ${project.basedir}/models/kws
    num-threads: 1
    threshold: 0.6
    score-bias: 1.0
    enabled: true
```

### 6.4 VoiceNodeProperties.java 嵌套更新

新增 `Kws kws` 字段,跟现有 `Gateway gateway, User user, ...` 并列。

### 6.5 VoiceWebSocketHandler.java 改造

**新增属性**:

```java
private final KwsService kwsService;
private static final String ATTR_KWS_ACTIVE = "kwsActive";  // boolean
```

**新增消息处理分支**:

```java
} else if ("audio.kws.start".equals(type)) {
    // 启动 KWS 监听
    kwsService.startSession(session.getId());
    session.getAttributes().put(ATTR_KWS_ACTIVE, true);
    sendToBrowser(session, Map.of(
        "type", "kws.ack",
        "state", "listening",
        "keywords", List.of("嗨 CTO")  // 从 KwsProps 读
    ));

} else if ("audio.kws.stop".equals(type)) {
    kwsService.stopSession(session.getId());
    session.getAttributes().remove(ATTR_KWS_ACTIVE);
}
```

**修改 handleBinaryMessage**:

```java
@Override
protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    Boolean kwsActive = (Boolean) session.getAttributes().get(ATTR_KWS_ACTIVE);
    byte[] payload = message.getPayload().array();

    if (Boolean.TRUE.equals(kwsActive)) {
        // KWS 模式:每帧过 KWS
        String keyword = kwsService.acceptFrame(session.getId(), payload);
        if (!keyword.isEmpty()) {
            log.info("🔥 Wake detected: session={}, keyword={}", session.getId(), keyword);
            kwsService.stopSession(session.getId());
            session.getAttributes().remove(ATTR_KWS_ACTIVE);
            sendToBrowser(session, Map.of(
                "type", "wake.detected",
                "keyword", keyword,
                "score", 0.82  // 实际从 result 读
            ));
        }
    } else {
        // 录音模式:累积到 buffer (现有逻辑)
        // ... 跟原来一样 ...
    }
}
```

**afterConnectionClosed 清理**:

```java
@Override
public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    kwsService.stopSession(session.getId());
    // ... 现有逻辑 ...
}
```

---

## 七、前端详细设计

### 7.1 KwsMonitor.ts (新)

**职责**:持续录音 + 发 audio.chunk,接收 wake.detected 触发对话。

```typescript
// audio/kwsMonitor.ts
import type { VoiceClient } from '../api/voiceClient'

export class KwsMonitor {
  private mediaStream: MediaStream | null = null
  private audioContext: AudioContext | null = null
  private workletNode: AudioWorkletNode | null = null
  private client: VoiceClient | null = null
  private chunkCount = 0

  async start(client: VoiceClient): Promise<void> {
    if (this.audioContext) throw new Error('KWS already started')
    this.client = client

    // 复用 AudioRecorder 的 mic 拿法
    this.mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: false,
        noiseSuppression: true,
      },
    })

    this.audioContext = new AudioContext({ sampleRate: 16000 })
    await this.audioContext.audioWorklet.addModule('/audio/pcm-worklet.js')
    const source = this.audioContext.createMediaStreamSource(this.mediaStream)
    this.workletNode = new AudioWorkletNode(this.audioContext, 'pcm-worklet')

    source.connect(this.workletNode)
    this.workletNode.port.onmessage = (e: MessageEvent<ArrayBuffer>) => {
      if (this.client) {
        this.client.sendAudio(new Int16Array(e.data))
        this.chunkCount++
      }
    }

    // 通知后端进入 KWS 监听
    client.sendCommand({ type: 'audio.kws.start' })
    console.log('[kws] ✅ KWS monitoring started')
  }

  stop(): void {
    if (!this.audioContext) return
    this.client?.sendCommand({ type: 'audio.kws.stop' })
    this.mediaStream?.getTracks().forEach(t => t.stop())
    this.audioContext.close().catch(() => {})
    this.audioContext = null
    this.workletNode = null
    this.mediaStream = null
    this.client = null
  }
}
```

### 7.2 App.vue 改造

**新增状态**:

```typescript
type Status = 'idle' | 'kws-listening' | 'recording' | 'processing'

const status = ref<Status>('idle')
const kwsMonitor = new KwsMonitor()
const recorder = new AudioRecorder()

// 唤醒后自动录音的 10s 超时
let autoStopTimer: number | null = null
```

**页面打开即启 KWS** (onMounted):

```typescript
onMounted(async () => {
  client = new VoiceClient()
  setupClientHandlers(client)  // 抽函数防 handler 累积
  client.connect()

  // 监听 wake.detected
  client.on('wake.detected', async (msg) => {
    log.info('🔥 wake detected', msg.keyword)
    // 1. 停 KWS monitor
    kwsMonitor.stop()
    // 2. 启 AudioRecorder (复用现有录音)
    await recorder.start(client)
    status.value = 'recording'
    // 3. 启 10s 自动停超时
    autoStopTimer = window.setTimeout(() => {
      if (status.value === 'recording') {
        recorder.stop()
      }
    }, 10000)
  })

  // 等 ready 后启 KWS
  client.on('ready', async () => {
    status.value = 'kws-listening'
    await kwsMonitor.start(client)
  })
})
```

**turn.done 后回 KWS 模式**:

```typescript
client.on('turn.done', async () => {
  // ... 现有 TTS 播完逻辑 ...
  status.value = 'kws-listening'
  await kwsMonitor.start(client)  // 自动恢复监听
})
```

### 7.3 UI 提示

- 状态文本:`监听中...` / `我在听,请说` / `思考中...`
- mic 图标:KWS 模式灰色脉动;RECORDING 模式红色常亮
- 唤醒反馈:屏幕闪一下 + "嗨" 文字淡入淡出

---

## 八、配置总览 (application.yml)

```yaml
openclaw:
  gateway:
    url: ws://106.14.164.36:60013
    token: ${OPENCLAW_TOKEN}
    # ...
  stt:
    model-dir: ${project.basedir}/models/stt
    num-threads: 2
  # v3 新增 ↓
  kws:
    model-dir: ${project.basedir}/models/kws
    num-threads: 1
    threshold: 0.6          # 唤醒阈值 0.1~0.9,越高越不灵敏
    score-bias: 1.0         # 唤醒偏置 0.5~2.0
    enabled: true           # 紧急关闭 KWS 听 STT 流程 (兜底)
```

---

## 九、唤醒词设计

### 9.1 预训练词 vs 自训练

| 维度 | 预训练 "嗨小爱"/"你好米娅" | 自训练 "嗨 CTO" |
|---|---|---|
| 工作量 | 5 min | 半天录音 + 1-2h 训练 |
| 准确率 | 8x% (官方数据) | 9x% (自己训) |
| 误触发 | 中 (依赖阈值) | 低 (个人化声学) |
| 唤醒词长度 | 4 音节 | 3-4 音节自由设计 |

**决策:本期用预训练** (用 "嗨小爱" 占位,等流程跑通再训自定义词)。

### 9.2 自训练步骤 (后续 milestone)

1. **录音**: Mac mini 自录 50-100 次 "嗨 CTO" (用 `rec` 命令或浏览器 AudioRecorder)
2. **加噪声**: 加 10 种背景噪声 × 5 种 SNR (sherpa-onnx 训练脚本支持)
3. **训练**: 用 `sherpa-onnx/cli/kws_train.py`,模型从 zipformer2-ctc 初始化
4. **测试**: 留 20% 样本做测试集,目标 FAR < 1 次/小时, FRR < 5%
5. **导出**: 训好的 encoder.onnx + keywords.txt (一行 "嗨 CTO /权重")

工作量:半天录音 + 1-2h 训练 + 1h 测试 = **2 天**。

---

## 十、实现步骤 (commit 拆分)

### Phase 1: 后端 KWS 骨架 (半天)

| commit | 内容 | 验证 |
|---|---|---|
| `feat(backend): 下载 sherpa-onnx KWS 模型` | 写 `scripts/download-kws-deps.sh`,下 `sherpa-onnx-kws-zh-wenet` (~50MB) 到 `models/kws/` | `ls models/kws/` 看到 encoder.onnx + tokens.txt + keywords.txt |
| `feat(backend): KwsProps + VoiceNodeProperties 嵌套` | 加 `KwsProps` record + 嵌套字段 | `mvn compile` 通过 |
| `feat(backend): KwsService 初版` | 包装 sherpa-onnx KeywordSpotter,实现 init/startSession/acceptFrame/stopSession | `mvn test` 通过 + 手测 `kwsService.acceptFrame()` |
| `feat(backend): VoiceWebSocketHandler 处理 audio.kws.*` | 新增分支处理 `audio.kws.start/stop` + binary 帧分发 KWS vs STT | 手动 WS 客户端测试 |
| `chore(backend): application.yml 加 kws 段` | 配置入口 | 重启后日志看到 KWS 模型加载 |

### Phase 2: 前端 KWS 监听 (半天)

| commit | 内容 | 验证 |
|---|---|---|
| `feat(frontend): KwsMonitor.ts` | 持续录音 + 发 audio.chunk,跟 AudioRecorder 共用 worklet | 浏览器开 devtools 看 audio.chunk 在发 |
| `feat(frontend): VoiceClient.on('wake.detected')` | 在 App.vue 注册 wake.detected 处理器 | 手动发测试消息验证回调 |
| `feat(frontend): 页面打开即启 KWS + 唤醒后自动录音` | onMounted → 等 ready → 启 KwsMonitor; wake.detected → 自动 AudioRecorder.start() | 喊 "嗨小爱" → mic 自动开始 |
| `fix(frontend): turn.done 后回 KWS 监听` | TTS 播完后重启 KwsMonitor | 完整流程跑通 |

### Phase 3: 端到端测试 (半天)

| commit | 内容 | 验证 |
|---|---|---|
| `test(backend): KwsServiceTest` | 喂预录的 "嗨小爱" PCM,断言检测到 | `mvn test` |
| `test(backend): VoiceWebSocketHandlerKwsTest` | mock WS session,验证 wake.detected 推送 | `mvn test` |
| `test(e2e): KWS 唤醒到 TTS 全链路` | (手动跑) | 喊 → mic 亮 → 说话 → cto 回复 → TTS → 回监听 |

### Phase 4: 体验打磨 (半天,可推迟)

| commit | 内容 |
|---|---|
| `feat(frontend): KWS 模式 UI 状态` | mic 图标脉动 + 状态文本 |
| `fix(frontend): 唤醒后 10s 超时自动停` | 防一直录 |
| `chore(backend): KWS 命中后日志更详细` | keyword + score + 耗时 |

**总工作量:1-2 天** (Phase 1-3 必做,Phase 4 看精力)。

---

## 十一、测试方案

### 11.1 单元测试 (后端)

```java
class KwsServiceTest {
    @Autowired KwsService kws;
    @Autowired KwsProps props;

    @Test
    void detectWakeWordFromPcm() throws Exception {
        // 从 src/test/resources/audio/wake-hi-xiaoai.pcm 加载
        byte[] pcm = Files.readAllBytes(Paths.get("src/test/resources/audio/wake-hi-xiaoai.pcm"));
        kws.startSession("test");
        // 分帧送入 (跟实际浏览器 chunk 大小一致)
        int frameSize = 1600 * 2;  // 100ms @ 16kHz int16
        for (int i = 0; i < pcm.length; i += frameSize) {
            int end = Math.min(i + frameSize, pcm.length);
            byte[] frame = Arrays.copyOfRange(pcm, i, end);
            String keyword = kws.acceptFrame("test", frame);
            if (!keyword.isEmpty()) {
                assertEquals("嗨小爱", keyword);
                return;
            }
        }
        fail("should detect wake word");
    }
}
```

### 11.2 集成测试 (WS 协议)

```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class KwsWebSocketIT {
    @LocalServerPort int port;

    @Test
    void wakeDetectedEventFires() throws Exception {
        WebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
            new WebSocketHttpHeaders(),
            URI.create("ws://localhost:" + port + "/ws/audio")
        ).get();

        session.sendMessage(new TextMessage("{\"type\":\"audio.kws.start\"}"));
        // 读 kws.ack
        // 喂预录 PCM frames
        // 验证 wake.detected 收到
        // 关 session
    }
}
```

### 11.3 手动端到端测试清单

- [ ] 打开 `localhost:5174` 页面,DevTools Network 看 audio.chunk 持续在发
- [ ] 页面 mic 权限一次性授予
- [ ] 后端日志看到 KWS 模型加载 (Phase 1 commit 后)
- [ ] 后端日志看到 `audio.kws.start` 处理
- [ ] 喊 "嗨小爱" → 后端日志 `🔥 Wake detected`
- [ ] 浏览器收到 wake.detected → mic 图标变红
- [ ] 喊 "今天天气怎样" → cto 文字 + TTS 回复
- [ ] TTS 播完后 mic 图标变灰脉动,回到 KWS 监听
- [ ] 关浏览器页面 → 后端日志 KWS session 清理

### 11.4 性能测试 (可选)

- 24h 持续监听 CPU 占用:`top -pid $(pgrep -f voice-node)`
- 网络流量统计:`iftop` 或后端打日志算 bytes/s
- 误触发率:安静环境 1 小时 → 期望 0 次误触发 (threshold 0.6)

---

## 十二、风险与权衡

### 12.1 已知风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| sherpa-onnx KWS 预训练词不支持 "嗨 CTO" | 唤醒词只能是 "嗨小爱"/"你好米娅" 等 | 用占位词跑通;后续自训练 (Milestone 2) |
| 浏览器页面没开 → 唤醒不工作 | 桌面机器人场景需常驻标签页 | 短期妥协;长期 PWA 或独立 app |
| 麦克风权限未授予 | 完全失效 | UI 启动时强提示授权 |
| macOS 26+ 麦克风权限可能因 TTS/系统通知反复弹 | UX 差 | 申请一次,后端记录,免后续弹 |
| 安静环境误触发 (电视/音乐/对话) | 体验差 | 提高 threshold 到 0.7+;后续自训练降低 FAR |
| 后端 KWS 单线程,多浏览器并发会排队 | 多人同时用会卡 | 单 session 够用;并发需求加池 |
| 持续音频上传带宽 (32 KB/s per session) | 跨网时心疼流量 | 内网穿透下无压力;远程场景再优化 |

### 12.2 设计权衡 (记录下来)

1. **后端 KWS vs 前端 WASM KWS** → 选后端。理由:复用现有 audio.* 协议 + sherpa-onnx Java binding;浏览器 0 CPU;但增加 32 KB/s 上行流量。内网环境零负担,远程场景需要重新评估。

2. **持续监听 vs 点按钮启 KWS** → 选持续监听。理由:体验更接近小爱同学;但浏览器必须保持页面打开。妥协:前端用 Page Visibility API 在标签隐藏时降频到 500ms 帧。

3. **唤醒后自动录音 vs 用户确认** → 选自动。理由:流畅;但需 10s 超时防一直录。

4. **预训练词 vs 自训练** → 选预训练先用。理由:5 min 跑通;自训练加 2 天工作量,延后到 Milestone 2。

---

## 十三、后续 Milestone

### Milestone 2: 自训练唤醒词 (2 天)
- 录 50-100 次 "嗨 CTO"
- 用 sherpa-onnx 训练脚本训 encoder
- 替换预训练模型
- 误触发率 < 1 次/小时目标

### Milestone 3: PWA 化 (1 周)
- vite-plugin-pwa 改造前端
- Service Worker 保持 WS 连接
- 桌面图标一键启动
- 离线 fallback:本地 STT 缓存

### Milestone 4: 多客户端并发 (3 天)
- KwsService 改成池 (per-thread KeywordSpotter)
- session load balancer
- CPU 占用动态调整

### Milestone 5: 嵌入式 KWS (1 周)
- ESP32-S3 跑 KWS (TFLite Micro)
- WiFi 通知 Mac mini (HTTP RPC)
- 24h 监听功耗 < 0.5W
- 桌面机器人 + 嵌入式 KWS 整合

---

## 十四、附录

### 14.1 文件改动清单 (本次 PR 涉及)

**新增**:
- `src/main/java/com/openclaw/voicenode/service/KwsService.java`
- `src/main/java/com/openclaw/voicenode/config/KwsProps.java`
- `frontend/src/audio/kwsMonitor.ts`
- `scripts/download-kws-deps.sh`
- `models/kws/encoder.onnx` (+ tokens.txt, keywords.txt)
- `src/test/resources/audio/wake-hi-xiaoai.pcm`
- `src/test/java/.../service/KwsServiceTest.java`
- `src/test/java/.../api/KwsWebSocketIT.java`

**修改**:
- `pom.xml` — (大概率不需要,sherpa-onnx 已有)
- `src/main/java/com/openclaw/voicenode/config/VoiceNodeProperties.java` — 嵌套 Kws 字段
- `src/main/java/com/openclaw/voicenode/api/VoiceWebSocketHandler.java` — 新增分支
- `src/main/resources/application.yml` — 加 kws 段
- `frontend/src/App.vue` — 启 KWS + wake 回调
- `frontend/src/api/voiceClient.ts` — 无 (复用 sendAudio/sendCommand)

### 14.2 参考文档

- sherpa-onnx KWS 文档: https://k2-fsa.github.io/sherpa/onnx/kws.html
- sherpa-onnx KWS Java API: https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/java-api.md
- 模型下载: https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models (搜 kws-zh-wenet)
- 现有 STT 集成参考: `src/main/java/com/openclaw/voicenode/service/SttService.java`
- 现有 WS 协议参考: `src/main/java/com/openclaw/voicenode/api/VoiceWebSocketHandler.java`

### 14.3 FAQ

**Q: 为什么 KWS 用后端不用前端 WASM?**
A: 复用现有 sherpa-onnx Java binding + 浏览器 0 CPU。代价是 32 KB/s 上行,内网环境无压力。

**Q: 为什么不直接用 macOS Voice Wake?**
A: 跟"本地开发"原则冲突,要依赖系统;且唤醒词受 Siri 词库限制。

**Q: 自训练唤醒词要多久?**
A: 半天录音 + 1-2h 训练 + 1h 测试 = 2 天。但本期先用预训练词跑通流程,自训练放 Milestone 2。

**Q: 唤醒词必须中文吗?**
A: 不限,sherpa-onnx 支持任意语言,但训练数据决定可用性。

**Q: KWS 阈值怎么调?**
A: application.yml 的 `threshold` 字段 (0.1~0.9)。安静环境 0.6;嘈杂环境 0.7+;过于灵敏 (误触发多) 就调高。

**Q: 不用浏览器能唤醒吗?**
A: 本期不行 (依赖浏览器 getUserMedia)。Milestone 5 (ESP32-S3) 解决。

---

> 拍板请求:老板看完后说 "干" 或 "改 X" 我就开工。