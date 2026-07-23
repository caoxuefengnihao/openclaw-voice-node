# Voice Node v2: STT/TTS 移到 Java 后端 + Gateway TTS 集成

> 状态: **v1 设计稿,等老板拍板**
> 创建: 2026-07-23 09:53 by 普罗米修斯
> 拍板线索: 2026-07-23 09:30 链路重设计 / 09:45 STT 选型讨论 / 09:50 sherpa-onnx + gateway minimax 拍板
> 关联 commit: `a0ba17e` (chat-mode bridge 文本版) / `41bbdb6` (强制飞书 session)

---

## 一、现状事实(代码扒出来的,2026-07-23 09:30 实测)

### 1.1 现有架构:**chat-proxy 模式**(STT/TTS 全在浏览器)

```
浏览器                                       Java 后端                              OpenClaw Gateway
  │                                            │                                        │
  │ Web Speech API (STT)                       │                                        │
  │  ─→ 浏览器识别文本                          │                                        │
  │  ─→ {type:"text", content} ──────────────▶│  chat.send(text) ─────────────────────▶│
  │                                            │  ◀── response delta (text chunks) ───│
  │  ◀── {type:"assistant", text} ─────────────│                                        │
  │  ◀── {type:"turn.done"} ──────────────────│                                        │
  │  SpeechSynthesis API (TTS)                 │                                        │
  │  浏览器自己念                               │                                        │
```

### 1.2 关键代码点

- **前端**:`frontend/src/App.vue:75` 用 `window.SpeechRecognition / webkitSpeechRecognition`
- **前端 TTS**:`frontend/src/App.vue:48` 用 `window.speechSynthesis.speak()`
- **后端**:`api/VoiceWebSocketHandler.java:166` `handleTextMessage` 收 `text` 类型,直接 `chat.send`
- **后端 GatewayClient**:`gateway/GatewayClient.java` 已实现 `sendFireAndForget` / `sendRequest` / `onEvent`
- **后端 ↔ Gateway WS**:已通过 Ed25519 device 鉴权 (`deviceToken=3wEUd...` 已落盘),跑通

### 1.3 已知问题(2026-07-23 09:25 实测,跨设备访问)

- **frpc 反代 HTTP 暴露**前端页面 → 浏览器非 secure context
- 浏览器 Web Speech API 在非 secure context 下直接拒绝 → `recognition.start()` 抛 `error: not-allowed`
- 影响:跨设备(非 localhost)无法用语音

### 1.4 pom.xml 现状

- Spring Boot 3.3.5 / websocket / bouncycastle / java-websocket / jackson / lombok
- **没有 STT/TTS 库**
- 没有 ONNX Runtime 依赖

### 1.5 Python 侧资产(2026-07-23 09:45 实测)

- `openai-whisper 20250625` (Python 包,装在 anaconda3)
- `~/.cache/whisper/base.pt` (139MB,base 多语种,中文 OK)
- `whisper` CLI 可用,但**只支持整段识别**,不能流式

### 1.6 Gateway 侧 TTS 能力(从 hello-ok snapshot 扒出来)

```
methods=[..., tts.status, tts.providers, tts.personas, tts.enable, tts.disable,
         tts.convert, tts.setProvider, tts.setPersona, tts.speak, ...]
plugins={loaded=[browser, deepseek, feishu, memos-tool, microsoft, minimax, ollama, qwen, workboard]}
```

- ✅ 已有 `minimax` 插件(注册了 T2A v2 语音合成)
- ✅ Gateway 暴露 `tts.speak` / `tts.personas` / `tts.providers` 等 RPC
- ✅ 已配 minimax 的国内 endpoint (`api.minimaxi.com`)

---

## 二、老板的拍板思路(2026-07-23 09:35 复述)

> Java 后端收到音频之后直接用 STT 把音频转成文字,
> 然后先把转成的文字在传回到前端,
> 再把文字发送到 Gateway,
> Gateway 返回文字回答之后 Java 后端再调用 TTS 转音频,
> 并把音频和文字共同推送到前端,
> 然后前端播放声音和文字滚动。

### 思路核心 4 点

1. **STT 放 Java 后端**(sherpa-onnx 纯本地,不依赖 Gateway)
2. **识别文本先回显前端**(用户能看到自己说了啥)
3. **chat 走 Gateway**(已有链路,不动)
4. **TTS 走 Gateway minimax**(复用已有插件,不造轮子)

---

## 三、目标行为(老板拍板后落地形态)

### 3.1 新链路图

```
浏览器                              Java 后端                                       OpenClaw Gateway
  │                                   │                                                │
  │─ audio.start ───────────────────▶│                                                │
  │─ audio.chunk (WS binary PCM) ──▶│  sherpa-onnx OnlineRecognizer                  │
  │─ audio.end ────────────────────▶│  STT.finalize() → text                          │
  │◀─ {type:"user.text", text} ────│  (识别到的文本立即回显)                          │
  │                                   │── chat.send(text, sessionKey) ────────────────▶│
  │                                   │◀── agent response delta (text chunks) ───────│
  │                                   │  累积到完整文本                                  │
  │                                   │  turn.done 时 → tts.speak(fullText) ────────▶│
  │                                   │◀── audio bytes (MP3 24kHz) ─────────────────│
  │◀─ {type:"assistant.text",text} ─│  流式推文本(delta 累积)                        │
  │◀─ {type:"assistant.audio",mp3} ─│  整段推音频(turn.done 后)                      │
  │  AudioContext 解码 + 播放         │                                                │
  │  文字滚动                          │                                                │
```

### 3.2 浏览器侧行为变化

| 行为 | v1 (chat-proxy) | v2 (audio-bridge) |
|---|---|---|
| STT | 浏览器 `SpeechRecognition` | **后端 sherpa-onnx** |
| TTS | 浏览器 `speechSynthesis` | **后端 gateway tts.speak** |
| 音频采集 | 无(纯文本) | `getUserMedia` + `AudioContext` |
| 推文本 | 用户说完直接发 | 收到 `user.text` 事件后显示 |
| 听响应 | 浏览器自己念 | 收到 `assistant.audio` 后解码播放 |

### 3.3 后端 WebSocket 协议(v2 新增)

**上行(浏览器 → Java)**:
| type | payload | 说明 |
|---|---|---|
| `audio.start` | `{ sampleRate: 16000, channels: 1, encoding: "pcm_s16le" }` | 开始一段音频采集,后端建 STT session |
| `audio.chunk` | **BinaryMessage** (PCM 16kHz mono int16 little-endian) | 音频帧,建议每 100~250ms 一帧 |
| `audio.end` | `{}` | 结束采集,后端 finalize STT → 出文本 |
| `text` | `{ content }` | 兼容老逻辑(直接发文本,跳过 STT) |
| `ping` | `{}` | 心跳 |

**下行(Java → 浏览器)**:
| type | payload | 说明 |
|---|---|---|
| `ready` | `{ sessionKey }` | WS 就绪 |
| `user.text` | `{ text, isFinal }` | 识别到的文本(实时或最终) |
| `assistant.text` | `{ text }` | agent 回复文本切片(delta) |
| `assistant.audio` | `{ audio: base64, format: "mp3" }` | TTS 音频(turn.done 后整段) |
| `turn.done` | `{}` | 一轮回复结束 |
| `error` | `{ message }` | 错误 |
| `pong` | `{}` | 心跳响应 |

---

## 四、技术实现分阶段

### 4.1 M1:STT 音频上行链路(Java 后端能识别浏览器音频)

#### 4.1.1 M1-A:pom.xml + SttService 骨架

**改文件**:
- `pom.xml`(+3 个依赖)
- `config/SttProps.java`(新增,20 行)
- `service/SttService.java`(新增,~150 行)

**pom.xml 新增依赖**:
```xml
<!-- sherpa-onnx 0.27+ (k2-fsa 官方 Java binding) -->
<dependency>
    <groupId>com.k2fsa</groupId>
    <artifactId>sherpa-onnx</artifactId>
    <version>1.10.27</version>
</dependency>
<!-- ONNX Runtime native (sherpa-onnx 传递依赖,但锁定 1.17.1) -->
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>1.17.1</version>
</dependency>
<!-- Audio format detect (读取 WAV header,备用) -->
<dependency>
    <groupId>org.bytedeco</groupId>
    <artifactId>javacv</artifactId>
    <version>1.5.9</version>
</dependency>
```

> 注:实际版本号开工前需查 https://github.com/k2-fsa/sherpa-onnx/releases 确认

**SttProps.java**:
```java
@ConfigurationProperties(prefix = "openclaw.stt")
public record SttProps(
    String modelDir,           // 模型目录,如 /Volumes/ssd/models/sherpa-onnx/paraformer-zh
    int sampleRate,            // 16000
    int maxActiveSessions      // 单后端最大并发 STT session(默认 10)
) {}
```

**application.yml 新增**:
```yaml
openclaw:
  stt:
    model-dir: /Volumes/ssd/models/sherpa-onnx/paraformer-zh-2023-09-14
    sample-rate: 16000
    max-active-sessions: 10
```

**SttService.java 核心 API**:
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class SttService {
    private final SttProps props;
    private OfflineRecognizer recognizer;  // 用 OfflineRecognizer 简化(整段识别)
    // 后续可升级 OnlineRecognizer 流式

    @PostConstruct
    public void init() throws Exception {
        log.info("加载 sherpa-onnx STT 模型: {}", props.modelDir());
        recognizer = new OfflineRecognizer.Builder()
            .setModelPath(props.modelDir() + "/model.int8.onnx")
            .setTokensPath(props.modelDir() + "/tokens.txt")
            .setNumThreads(2)
            .build();
        log.info("✅ STT 模型加载完成");
    }

    /** 整段识别:输入完整 PCM 字节 → 输出文本 */
    public String recognize(byte[] pcmBytes) {
        try {
            float[] samples = pcmToFloat(pcmBytes);
            String text = recognizer.decode(samples);
            return text.trim();
        } catch (Exception e) {
            log.error("STT 失败", e);
            return "";
        }
    }

    private float[] pcmToFloat(byte[] pcm) {
        // PCM 16-bit signed little-endian → float32 [-1.0, 1.0]
        int n = pcm.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm[2*i] & 0xff) | (pcm[2*i+1] << 8));
            out[i] = s / 32768f;
        }
        return out;
    }
}
```

**为啥用 OfflineRecognizer 而不是 Online**:
- ✅ 简单(整段识别,无需状态管理)
- ✅ 准确率比流式稍高(可看更多上下文)
- ⚠️ 用户说完到出文本有 ~500ms~1s 延迟(可以接受)
- 后续若需要"边说边识别"再升级 OnlineRecognizer

#### 4.1.2 M1-B:WebSocket audio 协议 + handler

**改文件**:
- `api/VoiceWebSocketHandler.java`(+80 行)

**新增字段**:
```java
private final SttService stt;
private static final String ATTR_AUDIO_BUFFER = "audioBuffer";  // ByteArrayOutputStream
```

**handleBinaryMessage** (新增):
```java
@Override
protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    ByteArrayOutputStream buf = (ByteArrayOutputStream) session.getAttributes()
        .computeIfAbsent(ATTR_AUDIO_BUFFER, k -> new ByteArrayOutputStream());
    try {
        buf.write(message.getPayload().array());
    } catch (IOException e) {
        log.warn("写入音频 buffer 失败", e);
    }
}
```

**handleTextMessage** (扩展):
```java
} else if ("audio.start".equals(type)) {
    // 初始化 buffer
    session.getAttributes().put(ATTR_AUDIO_BUFFER, new ByteArrayOutputStream());
    log.info("🎤 音频采集开始: sessionId={}", session.getId());
    sendToBrowser(session, Map.of("type", "audio.ack", "state", "recording"));
} else if ("audio.end".equals(type)) {
    // finalize
    ByteArrayOutputStream buf = (ByteArrayOutputStream) session.getAttributes()
        .get(ATTR_AUDIO_BUFFER);
    if (buf == null || buf.size() == 0) {
        log.warn("audio.end 但 buffer 为空");
        return;
    }
    byte[] pcm = buf.toByteArray();
    log.info("🎤 音频采集结束: {} bytes ({}ms @16kHz)",
        pcm.length, pcm.length / 32);  // 16kHz * 2 bytes = 32 bytes/ms

    String text = stt.recognize(pcm);
    log.info("📝 STT 识别结果: {}", text);

    // 回显前端
    sendToBrowser(session, Map.of("type", "user.text", "text", text, "isFinal", true));

    if (text.isBlank()) {
        sendToBrowser(session, Map.of("type", "error", "message", "未识别到语音"));
        return;
    }

    // 走 chat 链路(已有逻辑)
    sendToChat(session, text);
} else if ("audio.cancel".equals(type)) {
    // 用户中途取消
    session.getAttributes().remove(ATTR_AUDIO_BUFFER);
    log.info("🎤 音频采集取消");
}
```

**重构 sendToChat** (从 handleTextMessage 抽出):
```java
private void sendToChat(WebSocketSession session, String text) {
    GatewayClient gw = (GatewayClient) session.getAttributes().get(ATTR_GATEWAY);
    if (gw == null) return;

    // 重置 buffer
    StringBuilder buf = (StringBuilder) session.getAttributes().get(ATTR_BUFFER);
    if (buf != null) buf.setLength(0);

    log.info("📤 → Gateway chat.send: {}", text);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("sessionKey", props.sessionKey());
    params.put("message", text);
    params.put("idempotencyKey", UUID.randomUUID().toString());
    params.put("model", "qwen-vision-model");  // 保留 commit 41bbdb6 的临时 force
    gw.sendFireAndForget("chat.send", params);
}
```

#### 4.1.3 M1-C:前端 MediaRecorder + 音频上行

**改文件**:
- `frontend/src/audio/recorder.ts`(新增,~100 行)
- `frontend/src/App.vue`(改 setupRecognition → setupRecorder)

**recorder.ts 核心 API**:
```typescript
export class AudioRecorder {
  private mediaStream: MediaStream | null = null
  private audioContext: AudioContext | null = null
  private ws: WebSocket | null = null
  private workletNode: AudioWorkletNode | null = null

  async start(ws: WebSocket) {
    this.ws = ws
    this.mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        sampleRate: 16000,
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
      },
    })

    this.audioContext = new AudioContext({ sampleRate: 16000 })
    await this.audioContext.audioWorklet.addModule('/audio/pcm-worklet.js')

    const source = this.audioContext.createMediaStreamSource(this.mediaStream)
    this.workletNode = new AudioWorkletNode(this.audioContext, 'pcm-worklet')
    source.connect(this.workletNode)

    // worklet 每 100ms 输出 PCM chunk
    this.workletNode.port.onmessage = (e) => {
      const pcm: ArrayBuffer = e.data
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send(pcm)  // binary 帧
      }
    }

    // 通知后端 audio.start
    ws.send(JSON.stringify({
      type: 'audio.start',
      sampleRate: 16000,
      channels: 1,
      encoding: 'pcm_s16le',
    }))
  }

  stop() {
    // 通知后端 audio.end
    this.ws?.send(JSON.stringify({ type: 'audio.end' }))
    this.mediaStream?.getTracks().forEach(t => t.stop())
    this.audioContext?.close()
    this.workletNode = null
  }
}
```

**pcm-worklet.js** (AudioWorkletProcessor,新文件):
```javascript
class PCMProcessor extends AudioWorkletProcessor {
  process(inputs) {
    const input = inputs[0]
    if (input && input[0]) {
      const float32 = input[0]
      // float32 → int16 PCM
      const int16 = new Int16Array(float32.length)
      for (let i = 0; i < float32.length; i++) {
        const s = Math.max(-1, Math.min(1, float32[i]))
        int16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF
      }
      this.port.postMessage(int16.buffer, [int16.buffer])
    }
    return true
  }
}
registerProcessor('pcm-worklet', PCMProcessor)
```

**App.vue 改造**:
- 删 `setupRecognition()` (75-127 行)
- 加 `setupRecorder()` (调用 `AudioRecorder`)
- "开始说话" 按钮:调 `recorder.start(ws)`
- "停止说话" 按钮:调 `recorder.stop()`
- 监听 `user.text` 事件 → 显示识别文本
- 监听 `error` 事件 → 提示重试

**前端依赖新增**:无新依赖,用原生 `AudioContext` + `AudioWorklet`

---

### 4.2 M2:TTS 下行链路(Java 后端能调 gateway 合成音频推回浏览器)

#### 4.2.1 M2-A:TtsService + Gateway 集成

**新增文件**:
- `service/TtsService.java`(~100 行)
- `config/TtsProps.java`(15 行)

**TtsProps.java**:
```java
@ConfigurationProperties(prefix = "openclaw.tts")
public record TtsProps(
    String provider,      // "minimax"
    String persona,       // 默认 persona id
    int sampleRate,       // 24000
    String format         // "mp3"
) {}
```

**application.yml 新增**:
```yaml
openclaw:
  tts:
    provider: minimax
    persona: english_expressive_narrator  # 占位,T2 拍板
    sample-rate: 24000
    format: mp3
```

**TtsService.java 核心 API**:
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class TtsService {
    private final TtsProps props;

    /** 同步调 gateway tts.speak → 返音频字节 */
    public byte[] synthesize(GatewayClient gw, String text) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("text", text);
            params.put("provider", props.provider());
            params.put("persona", props.persona());
            params.put("format", props.format());
            params.put("sampleRate", props.sampleRate());

            Map<String, Object> resp = gw.sendRequest("tts.speak", params, 30);
            // 假设返回结构:{ ok:true, payload:{ audio: "<base64>" } }
            String audioB64 = (String) ((Map) resp.get("payload")).get("audio");
            return Base64.getDecoder().decode(audioB64);
        } catch (Exception e) {
            log.error("TTS 失败: {}", text, e);
            throw new RuntimeException("TTS failed", e);
        }
    }
}
```

**⚠️ tts.speak 返回结构待实测**:开工前需 `chat.send tts.speak {text:"hi"}` 跑一次看响应

#### 4.2.2 M2-B:WebSocket 推 audio + text

**改文件**:`api/VoiceWebSocketHandler.java`(+30 行)

**GatewayClient.sendRequest 同步版本**:
- 已有,直接用(见 `gateway/GatewayClient.java:152`)

**handler onEvent 改造** (在现有 chat response delta 处理后):
```java
} else if ("done".equals(stream) || "end".equals(stream) || "complete".equals(stream)) {
    // turn 完成
    StringBuilder buf = (StringBuilder) session.getAttributes().get(ATTR_BUFFER);
    String fullText = buf == null ? "" : buf.toString();
    if (buf != null) buf.setLength(0);

    log.info("✅ turn done, full text length: {}", fullText.length());

    // 推 assistant.text 完成事件(已经在 delta 流式推了,这里只推 done)
    sendToBrowser(session, Map.of("type", "turn.done"));

    if (fullText.isBlank()) return;

    // 调 TTS
    GatewayClient gw = (GatewayClient) session.getAttributes().get(ATTR_GATEWAY);
    try {
        byte[] audio = tts.synthesize(gw, fullText);
        log.info("🔊 TTS 音频: {} bytes ({}s @mp3 24k)",
            audio.length, audio.length / 48000);

        // 推音频(base64 编码,跟现有 JSON 通道复用)
        sendToBrowser(session, Map.of(
            "type", "assistant.audio",
            "audio", Base64.getEncoder().encodeToString(audio),
            "format", "mp3"
        ));
    } catch (Exception e) {
        log.warn("TTS 失败,只推文本: {}", e.getMessage());
        // 降级:不推 audio,前端用浏览器 TTS 兜底
    }
}
```

#### 4.2.3 M2-C:前端 AudioContext 播放

**改文件**:`frontend/src/App.vue`(+50 行)

**新增 playback 模块**:
```typescript
async function playAudio(base64Audio: string, format = 'mp3') {
  const audioCtx = new AudioContext()
  const bytes = Uint8Array.from(atob(base64Audio), c => c.charCodeAt(0))
  const buffer = await audioCtx.decodeAudioData(bytes.buffer)
  const source = audioCtx.createBufferSource()
  source.buffer = buffer
  source.connect(audioCtx.destination)
  source.start()
  return new Promise<void>(resolve => {
    source.onended = () => {
      audioCtx.close()
      resolve()
    }
  })
}
```

**App.vue 改造**:
- 删 `speakText()` (`speechSynthesis.speak` 路径)
- 监听 `assistant.audio` → 调 `playAudio(base64)`
- 监听 `assistant.text` → 累加到 `assistantTranscript.value`(已经在做)
- 状态机:`ready → thinking → speaking → ready`
  - `assistant.text` 累积中:`thinking`
  - `turn.done` 后:`speaking`(播放音频)
  - 音频播放完:`ready`

---

### 4.3 M3:联调验证 + 跨设备 demo

- 部署 frpc HTTPS 反代(选项 A 路径)解决跨设备 `getUserMedia` secure context
- 或者先用 localhost 联调验证,跨设备问题留到 M4
- 完整链路压测:从按钮点击 → 说话 → 识别 → chat → TTS → 播放,各环节延迟打点

---

## 五、设计决策表

| # | 决策项 | 选项 | 推荐 | 理由 |
|---|---|---|---|---|
| D1 | STT 引擎 | A) sherpa-onnx / B) Python openai-whisper daemon / C) faster-whisper / D) 云 STT | **✅ A sherpa-onnx(已拍板 09:50)** | Java-native、流式友好、中文 Paraformer-zh 质量好、不用 Python daemon |
| D2 | TTS 引擎 | A) sherpa-onnx / B) gateway minimax / C) 直接 HTTP 调 MiniMax / D) 本地引擎 | **✅ B gateway minimax(已拍板 09:50)** | 复用已有插件、商用音质、支持 persona、不造轮子 |
| D3 | 音频输入格式 | A) PCM 16kHz mono int16 / B) WebM/Opus / C) WAV | **✅ A PCM(已拍板 09:50)** | sherpa-onnx 原生输入、浏览器 AudioWorklet 直接产、无需解码 |
| D4 | 音频输出格式 | A) MP3 24kHz / B) Opus / C) PCM | **A MP3** | gateway minimax 默认输出、浏览器 AudioContext 直接 decode、压缩比合适 |
| D5 | STT 模式 | A) OfflineRecognizer(整段) / B) OnlineRecognizer(流式) | **A 整段先上** | 简单、500ms~1s 延迟可接受、后续可升级流式 |
| D6 | TTS 触发时机 | A) turn.done 后整段 / B) 流式每句 | **A 整段** | 简单、MP3 编码需要完整文本、gateway tts.speak 是同步调用 |
| D7 | VAD / 端点检测 | A) 前端按钮控制 / B) 后端能量检测 / C) silero-vad | **A 按钮先上** | 跑通链路优先、后续可升级 |
| D8 | 用户文本回显时机 | A) STT finalize 后 / B) 流式 partial | **A finalize 后** | 跟 STT 模式一致、避免回显后被改 |
| D9 | 跨设备 demo 方式 | A) frpc HTTPS / B) 仅 localhost | **B 优先,M3 再上 A** | STT 移到后端后,localhost 就能跑完整链路;跨设备等 M3 单独 issue |
| D10 | STT 模型选择 | A) Paraformer-zh INT8 230MB / B) SenseVoice 230MB / C) Whisper base 150MB | **✅ A Paraformer-zh INT8(已拍板 09:50)** | 中文 SOTA、非自回归快、中文场景最合适 |
| D11 | 音频 chunk 大小 | A) 100ms / B) 250ms / C) 500ms | **B 250ms** | 平衡延迟和包开销(每 250ms 一个 binary 帧) |
| D12 | 前端 AudioWorklet vs ScriptProcessor | A) AudioWorklet / B) ScriptProcessorNode | **A AudioWorklet** | 主线程不卡、现代 API、deprecated ScriptProcessor |
| D13 | 后端 audio 处理降级 | A) STT 失败重试 / B) 提示用户重说 / C) 切老 text 通道 | **B 提示重说** | 简单、明确 |
| D14 | Gateway tts.speak 返回结构 | 待实测 | — | M2-A 开工前必做 |

---

## 六、待拍板项(老板必看)

### T1. STT 引擎 ✅ 已拍板(2026-07-23 09:50)

**老板拍板**:**sherpa-onnx**

**含义**:
- Java 后端加 sherpa-onnx + onnxruntime 依赖
- 下载 Paraformer-zh INT8 模型(~230MB)到 `/Volumes/ssd/models/sherpa-onnx/paraformer-zh-2023-09-14/`
- 用 OfflineRecognizer(整段识别)

### T2. TTS persona 默认值 ✅ 已拍板(2026-07-23 13:04)

**老板拍板**:`English_expressive_narrator`(英伦男声,接近 Jarvis)。

**含义**:
- `application.yml` 配 `openclaw.tts.persona: English_expressive_narrator`
- 前端 UI 加 persona 切换不在 M2 范围(后续迭代)

### T3. STT 模型确认路径 ✅ 已拍板(2026-07-23 13:04)

**老板拍板**:**B 项目内** `/Volumes/ssd/openclaw-voice-node/models/stt/`,**不推到 github**。

**实现要点**:
- 模型目录加入 `.gitignore`(避免误推)
- `application.yml` 配 `openclaw.stt.model-dir: ${project.basedir}/models/stt/paraformer-zh-2023-09-14`
- 首次开发者跑 `scripts/download-stt-model.sh` 下载模型
- 项目部署文档需明确说明"模型文件需要手动下载"

### T4. frpc HTTPS 反代 ⏳ 推到 M3,不在 M1/M2 范围

**含义**:
- M1/M2 完成后,先用 localhost 验证完整链路
- M3 单独做 frpc HTTPS plugin 配置(自签证书或 Let's Encrypt)
- 不阻塞 STT/TTS 主链路开发

### T5. STT 性能预期 ✅ 已拍板(2026-07-23 13:04)

**老板拍板**: **500ms~1s 可以接受**。

**含义**:
- 起步用 `OfflineRecognizer`(整段识别),不作流式优化
- 如果将来用户反馈“需实时识别中间结果”,再考虑升 `OnlineRecognizer`
- 评估后的占用 (Paraformer-zh INT8): 后端启动时 ~2s 加载,运行后常驻内存 ~500MB

---

## 七、PR 拆分建议(按老板 M-N 习惯)

### 7.1 分支策略

**当前分支状态**(2026-07-23 09:30 实测):
- 分支:`main`
- 最新 commit:`41bbdb6` (feat: bridge 强制飞书 session)
- 工作区干净(刚 push 完)

**新分支**:`feat/voice-node-v2-stt-tts`(从 main 拉)
- 不在 main 上直接改(避免破坏 chat-proxy 兼容老前端)
- Phase B/D 的老 chat-proxy `text` 消息保留,新老前端都能用

### 7.2 PR 拆分

| PR | 标题 | 工作量 | 依赖 | 改动文件 |
|---|---|---|---|---|
| **M1-A** | 后端加 sherpa-onnx 依赖 + SttService 骨架 | ~180 行 | 无 | `pom.xml` / `SttProps.java` / `SttService.java` / `application.yml` |
| **M1-B** | WebSocket 加 audio.start/chunk/end 协议 | ~80 行 | M1-A | `VoiceWebSocketHandler.java` |
| **M1-C** | 前端 MediaRecorder + 音频上行 | ~150 行 | M1-B | `App.vue` / `audio/recorder.ts` / `audio/pcm-worklet.js` |
| **M2-A** | 后端加 TtsService + gateway tts.speak 集成 | ~120 行 | 无(独立) | `TtsProps.java` / `TtsService.java` / `application.yml` |
| **M2-B** | WebSocket 推 assistant.audio + 完整文本 | ~50 行 | M2-A | `VoiceWebSocketHandler.java` |
| **M2-C** | 前端 AudioContext 播放 + 状态机 | ~80 行 | M2-B | `App.vue` |
| **M3-A** | 联调 + Happy path 验证 | 半天 | M1+M2 | — |
| **M3-B** | frpc HTTPS 配置(独立 issue) | 半天 | M3-A | `frpc_ssh.ini` / certs |

**分批提交**:M1-A/B/C + M2-A/B/C + M3-A 各 1 commit,共 7 commit

**push 策略**(沿用之前节奏):
- 每个 PR 本地 commit 后立即 push
- 验证:`git log origin/main..HEAD --oneline` 对得上

---

## 八、验证清单(老板拍板后落地用)

### 8.1 M1-A 验证(SttService)

- [ ] `pom.xml` 编译通过,无依赖冲突
- [ ] 启动时 `SttService.init()` 日志显示模型加载完成
- [ ] 单元测试:喂一段 16kHz PCM 中文音频 → 识别出对应文本
- [ ] 测试音频:`curl -s https://example.com/test-zh.wav` → 转 PCM → 喂给 recognizer → 输出文本

### 8.2 M1-B 验证(WebSocket audio 协议)

- [ ] `audio.start` → 后端日志 `🎤 音频采集开始`
- [ ] `audio.chunk` (binary,每 250ms) → 后端写入 buffer
- [ ] `audio.end` → 后端日志 `🎤 音频采集结束: N bytes`
- [ ] STT 识别 → 推 `{type:"user.text", text:"...", isFinal:true}` 到浏览器
- [ ] 文本非空 → 自动 `chat.send`
- [ ] 文本为空 → 推 `error` 提示重说
- [ ] `audio.cancel` → 清空 buffer

### 8.3 M1-C 验证(前端录音)

- [ ] 浏览器 localhost 访问(secure context OK)
- [ ] 点"开始说话" → 浏览器请求麦克风权限
- [ ] 授权后 → AudioContext 启动 → 每 250ms 发 binary 帧
- [ ] 点"停止说话" → 发 `audio.end` → 收 `user.text` 事件 → 显示文本
- [ ] 麦克风权限被拒绝 → 友好错误提示

### 8.4 M2-A 验证(TtsService)

- [ ] `gateway tts.speak {text:"hello", provider:"minimax"}` 实测返回结构
- [ ] TtsService.synthesize 调通 → 返音频字节
- [ ] 音频格式验证:文件能播放、用 ffprobe 看 sample_rate / channels / codec

### 8.5 M2-B 验证(WebSocket 推音频)

- [ ] agent response 累积到 turn.done
- [ ] 调 TtsService.synthesize → 拿到音频字节
- [ ] 推 `{type:"assistant.audio", audio:"<base64>", format:"mp3"}` 到浏览器
- [ ] TTS 失败时降级:只推文本,不推音频(前端用浏览器 TTS 兜底)

### 8.6 M2-C 验证(前端播放)

- [ ] 收 `assistant.audio` → AudioContext.decodeAudioData 成功
- [ ] 播放完整段音频
- [ ] 播放期间状态显示 `speaking`
- [ ] 播放完状态回到 `ready`

### 8.7 Happy path 完整链路

- [ ] localhost 访问
- [ ] 点"开始说话" → 说"今天上海天气怎么样"
- [ ] 浏览器实时显示识别文本 "今天上海天气怎么样"
- [ ] gateway cto agent 回复 "今天上海..."
- [ ] 浏览器文本滚动显示回复
- [ ] TTS 合成音频 → 浏览器播放
- [ ] 听到 cto 念回复内容

### 8.8 跨设备 demo(放到 M3)

- [ ] frpc HTTPS plugin 配置完成
- [ ] 域名/IP 访问 → secure context ✅
- [ ] `getUserMedia` 权限请求正常
- [ ] 完整链路在跨设备跑通

---

## 九、风险评估

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| sherpa-onnx Mac M-series ARM 兼容问题 | 中 | 启动失败 | M1-A 开工前先验证,跑 hello world demo |
| 模型下载慢/失败 | 低 | M1 卡住 | 用 gh-proxy 镜像;预留 fallback 路径 |
| gateway tts.speak 返回结构跟预期不符 | 中 | M2-A 卡住 | M2-A 开工前先实测一次,确认返回结构 |
| minimax T2A 配额/限流 | 低 | TTS 失败 | 应用层做降级,失败时用浏览器 TTS |
| 音频 chunk 不连续导致 STT 识别质量差 | 中 | 用户重说率高 | AudioWorklet 强制 buffer 连续 + 监控 STT 识别置信度 |
| STT 整段识别延迟 > 1.5s | 低 | 用户体验差 | 升级到 OnlineRecognizer 流式识别 |
| 浏览器 getUserMedia 在 frpc HTTP 下被拒 | 高 | 跨设备无法用 | M3 单独做 frpc HTTPS(不在 M1/M2 阻塞) |
| 前端 AudioWorklet 在 Safari 不支持 | 中 | Safari 用户不能用 | AudioWorklet Chrome/Edge/Safari 14.1+ 都支持;Firefox 不支持但用户用 Chrome |
| sherpa-onnx 多 session 内存占用 | 低 | OOM | `max-active-sessions: 10` 限流;监控 heap |

---

## 十、参考资料

- 2026-07-23 commit `a0ba17e` chat-mode bridge(文本版基础)
- 2026-07-23 commit `41bbdb6` 强制 cto 飞书 session
- `frontend/src/App.vue:75` Web Speech API(将被 AudioRecorder 替换)
- `api/VoiceWebSocketHandler.java` chat-proxy(将被 audio-bridge 扩展)
- `gateway/GatewayClient.java` sendRequest / sendFireAndForget(tts.speak 复用)
- 7/18 讨论 voice satellite 架构(memory/2026-07-11.md)
- 2026-07-23 09:25 frpc 反代 IPv6 only bug → host:true 修复
- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx
- MiniMax T2A v2 文档(待查 `minimax` 插件暴露的 RPC 接口)
- Gateway methods 列表(从 hello-ok snapshot 扒出来,见 §1.6)

---

**拍板进度:**
- ✅ **D1(STT 引擎)** — 老板 2026-07-23 09:50 拍板:sherpa-onnx
- ✅ **D2(TTS 引擎)** — 老板 2026-07-23 09:50 拍板:gateway minimax
- ✅ **D3(音频输入格式)** — 老板 2026-07-23 09:50 拍板:PCM 16kHz mono
- ✅ **D10(STT 模型)** — 老板 2026-07-23 09:50 拍板:Paraformer-zh INT8 230MB
- ✅ **T1(STT 引擎选 sherpa-onnx)** — 老板 2026-07-23 09:50 拍板
- ⏳ **T2(TTS persona 默认值)** — 待拍板(候选见 §六)
- ⏳ **T3(STT 模型存放路径)** — 待拍板(候选见 §六)
- ✅ **T4(frpc HTTPS 推到 M3)** — 老板 2026-07-23 09:50 默认
- ⏳ **T5(STT 性能预期)** — 老板确认 500ms~1s 可接受

---

## 十一、Phase v2-A 开工前必做清单

### 11.1 实测 gateway tts.speak 返回结构(M2-A 前)

```bash
# 1. 用现有 chat-proxy 后端 + custom_node.py 思路,直接连 gateway 发 tts.speak
# 2. 准备一个最小测试请求:
{"text":"hello","provider":"minimax","persona":"english_expressive_narrator"}
# 3. 看返回 payload 结构,确认 audio 字段名(audio / audioBase64 / data 等)
# 4. 把实测结果填到 D14 决策表
```

### 11.2 实测 sherpa-onnx Mac M-series 兼容性(M1-A 前)

```bash
# 1. 临时建个 hello world Java 项目
# 2. 加 sherpa-onnx 依赖
# 3. 下载 Paraformer-zh INT8 模型
# 4. 跑 recognizer.decode() 喂一段测试音频
# 5. 确认输出中文文本 → 写到本文档 §1.4 现状补充
```

### 11.3 工作区现状(2026-07-23 09:53 实测)

```
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

✅ 工作区干净,从 main 拉新分支 `feat/voice-node-v2-stt-tts` 直接开干。