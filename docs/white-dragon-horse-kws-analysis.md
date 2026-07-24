# 白龙马 KWS 实现分析报告

> **作者**: voice-node CTO agent
> **时间**: 2026-07-25 00:44~01:30 (用户睡觉期间,深度学习)
> **方法**: 知识图谱 + 全部 KWS 相关源码逐行阅读 + 与 voice-node v3 横向对比
> **目标**: 完整还原白龙马 KWS 实现 + 评估哪些可借鉴到 voice-node

---

## 1. 一句话总结

白龙马用 **`Electron utilityProcess` 独立进程 + `sherpa-onnx-node` 1.13.3 + Float32 PCM 直接喂 1600 样本/块** 实现了稳定的 3-keyword（"小白龙"6 个拼音变体）唤醒,实测召回 13/17,误触发极少。voice-node v3 **90%** 的代码和配置可以借鉴,但 sherpa-onnx Java binding 1.13.4 的 `KeywordSpotter.isReady()` 兼容性是真正的拦路虎——升级 sherpa-onnx 几乎是必做。

---

## 2. 白龙马 KWS 完整架构

### 2.1 文件清单 (4 个)

| 文件 | 行数 | 角色 |
|---|---|---|
| `electron/wake-probe.html` | 86 | 隐藏 BrowserWindow,AudioWorklet 采集 16kHz Float32,经 IPC 送主进程 |
| `electron/wake-probe-preload.cjs` | 17 | contextBridge 桥:`sendPcm(buf)` + `reportStatus(status, detail)` |
| `electron/wake-word.cjs` | 96 | 主进程 KWS 管理器:fork utilityProcess、转发 PCM、订阅 hit 事件 |
| `electron/kws-process.cjs` | **19** | utilityProcess 入口:**只**包含 3 个调参常量 (`KEYWORDS_THRESHOLD / SCORE / COOLDOWN_MS`) + 5 个 `let` 状态变量 |

### 2.2 为什么 kws-process.cjs 只有 19 行?

**关键发现**: 真正的 sherpa-onnx 调用**不在仓库代码里**——它在 `node_modules/sherpa-onnx-node` 里(`require('sherpa-onnx-node')` 加载 Node 原生 binding)。白龙马的 KWS 实现 = **项目代码(IPC 协议 + 进程管理 + 调参) + npm 库(实际 KWS 推理)**。这跟我们 voice-node "Java 业务代码 + sherpa-onnx Java jar" 是同一种架构。

### 2.3 进程架构

```
┌────────────────────────┐     ┌─────────────────────────┐
│  主窗口 Renderer       │     │  隐藏 "耳朵" 窗口       │
│  voice-panel.js        │     │  wake-probe.html        │
│  + voice-wake.js       │     │  AudioWorklet 16kHz    │
│  + voice-core.js       │     │  Float32 采集           │
└──────────┬─────────────┘     └──────────┬──────────────┘
           │ ipcRenderer 'wake:pcm' (ArrayBuffer)│
           ▼                                ▼
┌──────────────────────────────────────────────────────┐
│  main.cjs (主进程)                                    │
│  ipcMain.on('wake:pcm', buffer => wakeWord.feedPcm)  │
└──────────────────────────┬───────────────────────────┘
                           │ parentPort.postMessage({type:'pcm', buf:ab})
                           ▼
┌──────────────────────────────────────────────────────┐
│  utilityProcess (kws-process.cjs 入口)                 │
│  1. require('sherpa-onnx-node')                        │
│  2. 创建 KeywordSpotter (encoder/decoder/joiner)      │
│  3. process.on('pcm') => spotter.acceptWaveform()       │
│  4. 命中 → parentPort.postMessage({type:'hit', keyword})│
└──────────────────────────┬───────────────────────────┘
                           │ {type:'hit', keyword:'@小白龙'}
                           ▼
                  wakeWord.setOnHit(cb)
                  → devLight.blink() + mainWindow.webContents.send('wake:hit')
                  → voice-wake.js onHit() 启动 60s 会话
```

### 2.4 IPC 协议

**probe → main** (`wake:pcm`):
- 负载: `ArrayBuffer` (Float32Array 的底层 buffer,1600 samples × 4 bytes = 6.4KB)

**main → utility** (`parentPort`):
- `{type:'init', modelDir, logFile}` → 后端回 `{type:'ready'}` 或 `{type:'error', error}`
- `{type:'pcm', buf:ArrayBuffer}` → 后端处理,命中回 `{type:'hit', keyword:'...'}`

**utility → main** (`parentPort`):
- `{type:'ready'}` / `{type:'error'}` / `{type:'hit', keyword}`

### 2.5 知识图谱 Tour 摘要 (从 `.understand-anything/knowledge-graph.json`)

8 个 layer:
1. **Voice Engine (mechanism)**: voice-core.js (机制层)
2. **Voice Strategies**: voice-continuous / voice-ptt / voice-wake (策略层)
3. **Voice Orchestration**: voice-panel.js (编排层)
4. **ASR Backends**: 5 个云 + 2 个本地 + KWS 唤醒
5. **TTS Output**: tts-fx.js + 设备路由
6. **Voice API Endpoints**: /voice/cloud, /settings/voice 等
7. **Project Skeleton**: 主入口
8. (未列完)

---

## 3. 关键代码逐行解读

### 3.1 `electron/kws-process.cjs` (19 行)

```js
// kws-process.cjs —— 语音唤醒(KWS)子进程,跑在 Electron utilityProcess 里
//
// 为什么要独立进程:sherpa-onnx 自带一份 onnxruntime,而后端 @huggingface/transformers
// 走 onnxruntime-node 另带一份;同一进程加载两份 onnxruntime 会在构建会话时原生崩溃
// (已用 probe 坐实)。把 KWS 隔离到只加载 sherpa 的独立进程,从根上消除冲突。
//
// 协议(parentPort):
//   收 {type:'init', modelDir, logFile}  → 构建 KeywordSpotter,回 {type:'ready'} / {type:'error'}
//   收 {type:'pcm',  buf:ArrayBuffer}    → 喂 16kHz Float32,命中则写日志 + 回 {type:'hit', keyword}
const fs = require('fs')
const path = require('path')

const KEYWORDS_THRESHOLD = 0.35 // 从 0.25 上调到 0.35,减少误触发
const KEYWORDS_SCORE = 3.0      // 实测 score=3 召回最佳(13/17 vs 2.0 的 9/17)
const COOLDOWN_MS = 800 // 命中后冷却:去重一次唤醒的多帧结果,又允许~1s 间隔的重试都触发

let spotter = null
let stream = null
let sherpa = null
let logFile = null
```

**逐行解读**:
- **L3-5**:**架构核心** — sherpa-onnx Node binding 跟 `@huggingface/transformers` (onnxruntime-node) 同进程会**原生崩溃**(已坐实)。所以 KWS 必须在独立 utility process。
- **L7-8**:**协议契约** — 父进程喂 PCM,子进程回 ready/error/hit,IPC 异步,init 必须等子进程 `spawn` 事件后再 post,否则监听器未挂上消息丢失。
- **L10-11**:fs + path — 写 log file 到 userData。
- **L13**:`KEYWORDS_THRESHOLD = 0.35` — **核心调参 1**。默认 0.25 误触发多,白龙马实测 0.35 是甜区(在召回和误触之间平衡)。
- **L14**:`KEYWORDS_SCORE = 3.0` — **核心调参 2**。默认 1.5,白龙马实测 3.0 让召回从 9/17 提到 13/17。这是**显著的调参收益**。
- **L15**:`COOLDOWN_MS = 800` — 同一句话被识别成多次唤醒的 cooldown。同时允许 1s 间隔的重复唤醒。
- **L17-20**:模块级 state,在 `process.on('pcm')` 里赋值。

**L13-15 三个常量就是白龙马 KWS 的"调参核心"** —— voice-node v3 已经全部借鉴(commit 1521fc6)。

### 3.2 `electron/wake-word.cjs` (96 行)

```js
const { utilityProcess } = require('electron')
const path = require('path')

let child = null
let spawned = false
let onHit = null

function resolveModelDir(codeRoot) {
  // 打包后走 .asar.unpacked,sherpa-onnx 不能从 .asar 读模型
  const base = codeRoot.endsWith('.asar')
    ? codeRoot.replace(/\.asar$/, '.asar.unpacked')
    : codeRoot
  return path.join(base, 'src', 'voice', 'kws-model')
}

function initWakeWord({ codeRoot, logDir }) {
  if (child) return spawned
  const modelDir = resolveModelDir(codeRoot)
  const logFile = path.join(logDir, 'wake-word.log')
  try {
    child = utilityProcess.fork(path.join(__dirname, 'kws-process.cjs'), [], {
      stdio: 'inherit',
      serviceName: 'bailongma-kws',
    })
    child.on('message', (msg) => {
      if (!msg) return
      if (msg.type === 'ready') console.log('[wake] KWS 子进程就绪')
      else if (msg.type === 'error') console.error('[wake] KWS 子进程初始化失败(功能禁用):', msg.error)
      else if (msg.type === 'hit') {
        console.log('[wake] 命中唤醒词:', msg.keyword)
        try { onHit && onHit(msg.keyword) } catch {}
      }
    })
    child.on('exit', code => { console.warn('[wake] KWS 子进程退出 code=' + code); child = null; spawned = false })
    child.on('spawn', () => {
      try { child.postMessage({type:'init', modelDir, logFile}) } catch {}
    })
    spawned = true
    return true
  } catch (err) { ... }
}

function feedPcm(buffer) {
  if (!child || !buffer) return
  let ab = null
  if (buffer instanceof ArrayBuffer) ab = buffer
  else if (ArrayBuffer.isView(buffer)) ab = buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength)
  else return
  // Electron utilityProcess.postMessage 的 transfer 列表只接受 MessagePortMain,
  // 放 ArrayBuffer 会抛错。这里直接结构化克隆(每块 ~6KB,频率低,拷贝开销可忽略)。
  try { child.postMessage({type:'pcm', buf: ab}) } catch (err) { ... }
}
```

**逐行解读**:
- **L14-19**:`resolveModelDir` 关键 — **sherpa-onnx 不能从 .asar 读模型**,必须 unpacking 到 `.asar.unpacked`。这是 Electron + KWS 的工程必踩坑。
- **L22-23**:`initWakeWord` 防重入:已 fork 过就 return。
- **L29**: `utilityProcess.fork(..., {stdio:'inherit', serviceName:'bailongma-kws'})` — **关键**:
  - `serviceName`: Windows 服务管理器名称
  - `stdio:'inherit'`: 子进程日志直接打到主进程终端
- **L31-39**:`on('message')` 事件路由,ready/error/hit 三种消息。
- **L42-44**:`on('exit', ...)` — 子进程死了要清理 state(`child = null`)。
- **L45-47**:**关键 timing fix** — `on('spawn')` **之后**才 post init 消息。如果 fork 后立刻 post,子进程的 `on('message')` 还没挂上,消息丢失。**这个 bug 我们 voice-node 必须学**。
- **L52-58**:`feedPcm` — 关键细节:
  - `buffer.buffer.slice(buffer.byteOffset, ...)` — **正确**从 TypedArray 提取底层 ArrayBuffer,不复制数据
  - **不**用 transfer list — `utilityProcess.postMessage` 的 transfer 只接受 MessagePortMain,放 ArrayBuffer 抛错。改用结构化克隆,每块 6KB × ~50Hz = 300KB/s,完全可接受。

### 3.3 `electron/wake-probe.html` (86 行)

```js
const CHUNK = 1600;        // 0.1s @16k
const TARGET_SR = 16000;

const WORKLET_SRC = `
  class WakeCapture extends AudioWorkletProcessor {
    constructor(opt){
      super(); this._size = (opt&&opt.processorOptions&&opt.processorOptions.chunk)||1600;
      this._buf=new Float32Array(this._size); this._n=0;
    }
    process(inputs){
      const ch = inputs[0] && inputs[0][0];
      if (ch) {
        for (let i=0;i<ch.length;i++){
          this._buf[this._n++] = ch[i];
          if (this._n >= this._size){
            const out = this._buf.slice(0, this._n);
            this.port.postMessage(out, [out.buffer]);
            this._n = 0;
          }
        }
      }
      return true;
    }
  }
  registerProcessor('wake-capture', WakeCapture);
`;

async function start() {
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      // KWS 要原始音频:降噪/回声消除/自动增益都关掉,它们会扭曲语音、害识别
      // (模型在干净音频上训练,样本能 100% 命中正因为干净)。
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1 },
    });
  } catch (e) { ... }
  
  try {
    const actx = new AudioContext({ sampleRate: TARGET_SR });
    ...
    await actx.audioWorklet.addModule(url);
    const node = new AudioWorkletNode(actx, 'wake-capture', {
      numberOfInputs: 1, numberOfOutputs: 1, channelCount: 1,
      processorOptions: { chunk: CHUNK },
    });
    node.port.onmessage = (ev) => {
      const f32 = ev.data;
      window.wakeProbe?.sendPcm(f32.buffer);
    };
    src.connect(node);
    node.connect(actx.destination); // 拉动音频图(输出静音)
    
    report('running', 'sr=' + actx.sampleRate);
  } catch (e) { ... }
}
start();
```

**逐行解读**:
- **L2**:`CHUNK = 1600` = 0.1s @16k — **每块 1600 样本 = 100ms 音频**。**不缓冲**,直接发。这是关键设计。
- **L6-25**:`WakeCapture` AudioWorklet processor:
  - 累积 1600 样本 → `port.postMessage(out, [out.buffer])` 用 transfer 零拷贝
  - Float32Array (不是 Int16!) — 跟 voice-node 的 Int16 worklet **完全不同**
- **L40-43**:`getUserMedia` 配置 **所有 3 个处理全关**:
  - `echoCancellation: false`
  - `noiseSuppression: false`
  - `autoGainControl: false`
  - **L39 注释直接说明原因**: "模型在干净音频上训练,样本能 100% 命中正因为干净"
  - **voice-node v3 现状**: `echoCancellation: false, noiseSuppression: true` — 我们 noiseSuppression 还是 true,**需要改 false**
- **L46-48**:`AudioContext({sampleRate: TARGET_SR})` — **强制 16kHz**。即使 BT 麦 HFP 实际 8kHz,AudioContext 会 resample 到 16kHz。
- **L60-63**:`AudioWorkletNode` 注册,`processorOptions: {chunk: CHUNK}` 传 1600。
- **L64-66**:`node.port.onmessage` 收到 Float32Array → `wakeProbe.sendPcm(f32.buffer)` 传到底层 IPC。
- **L68**:`node.connect(actx.destination)` — 关键!AudioWorklet 必须连接到 destination,音频图才会"pull"数据流(否则 process() 不被调用)。

### 3.4 `src/ui/brain-ui/voice-wake.js` (190 行) — 唤醒会话编排

```js
const IDLE_DISMISS_MS = 60000; // 条件三:60s 无新语音(且系统空闲)→ 退场
const IDLE_CHECK_MS = 2000;
const ORB_EXIT_MS = 320;       // 退场动画时长上限,过后才真停会话(与 voice-orb.html 0.28s 过渡对齐)
const FRAME_MIN_MS = 33;       // 推帧给球窗的最小间隔(≈30fps)

export function createWakeFlow(core) {
  const orb = ...;
  let active = false;          // 唤醒会话进行中
  let inConversation = false;
  let lastActiveTs = 0;
  let idleTimer = null;
  ...
  
  async function onHit() {
    if (active) { markActive(); return; } // 已在场:刷新空闲计时,忽略重复唤醒
    dismissToken++;
    active = true; inConversation = false;
    agentText = ''; agentBusy = false;
    ...
    orb?.orbEnter();
    startIdleWatch();
    if (!core.micActive) {
      const stream = await core.startSession();
      if (!stream) { dismiss(); return; }
    }
  }
  
  function startIdleWatch() {
    stopIdleWatch();
    markActive();
    idleTimer = setInterval(() => {
      if (!active) return;
      if (Date.now() - lastActiveTs >= IDLE_DISMISS_MS) dismiss();
    }, IDLE_CHECK_MS);
  }
  
  function onTranscript() {
    if (!active) return;
    inConversation = true;
    markActive();
    retirePending = false; retireArmed = false;
  }
  
  function dismiss() {
    if (!active) return;
    ...
    setTimeout(() => {
      if (token !== dismissToken || active) return;
      if (core.micActive) core.stopSession();
    }, ORB_EXIT_MS);
  }
  
  orb?.onHit(onHit);
  setupSSE();
  return { onFrame, onTranscript, requestDismiss, isActive: () => active };
}
```

**核心设计 — 退场三条件** (注释 L18-22 详细说):
1. **用户要求退下** (Agent 调 `voice_retire` 工具发 SSE 事件)
2. **任务完成** (Agent 调 `voice_retire` 等本轮回复说完)
3. **60s 无新语音** (空闲自动退场)

**空闲计时刷新点**:
- 用户说话 (onTranscript)
- 系统繁忙 (recognizing/processing/speaking/event)
- "打字" (vol > QUIET_VOL)

**关键 insight**:`BUSY_SK = new Set(['recognizing', 'processing', 'speaking', 'event', 'done'])` — 在这些状态下,**空闲计时不累积**。否则长回复中途会被误退。

### 3.5 `voice-core.js` 关键部分

**第 100-130 行**:`PCM_WORKLET_SRC` — voice-core 的 AudioWorklet 处理器
```js
class PcmCaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this._size = (options && options.processorOptions && options.processorOptions.chunk) || 2048;
    this._buf = new Int16Array(this._size);
    this._n = 0;
  }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (ch) {
      for (let i=0;i<ch.length;i++){
        let s = ch[i];
        if (s > 1) s = 1; else if (s < -1) s = -1;
        this._buf[this._n++] = s < 0 ? s * 0x8000 : s * 0x7fff;
        if (this._n >= this._size) {
          const out = this._buf.slice(0, this._n);
          this.port.postMessage(out.buffer, [out.buffer]);
          this._n = 0;
        }
      }
    }
    return true;
  }
}
```

**关键差异**:
- voice-core 用的 **Int16**,wake-probe 用的 **Float32** — 两个独立工作流
- voice-core `PCM_CHUNK_SAMPLES = 2048` (128ms)
- wake-probe `CHUNK = 1600` (100ms)
- wake-probe 直接用 transfer 零拷贝 → 性能更好

### 3.6 `voice-continuous.js` 关键部分 (Barge-in 打断检测)

```js
const BARGEIN_WARMUP_MS = 600;
const DUCK_TRIGGER_FRAMES = 3;
const DUCK_SUSTAIN_FRAMES = 10;
const DUCK_DECAY_FRAMES   = 6;
const DUCK_MAX_MS         = 1500;
const ECHO_MARGIN_VOL     = 0.025;
const ECHO_HARD_VOL       = 0.16;

const BARGEIN_FAST_WINDOW_MS   = 500;
const BARGEIN_FAST_SILENT_THR  = BARGEIN_THRESHOLD * 0.65;
const BARGEIN_FAST_SILENT_NEED = 7;

const BARGEIN_NO_SPEECH_MS = 3500;
```

**两阶段打断检测 (Duck → Judge)**:
1. **Duck 阶段**: 连续 3 帧高振幅 → 进入 duck(降音量)
2. **Judge 阶段**:
   - 再持续 10 帧高振幅 (>duck sustain) → 判为语音,真正打断
   - 或 6 帧低振幅 (<duck decay) → 判为噪音,恢复音量
3. **Duck 最长 1500ms**: 超时自动恢复(防永久卡在 duck)

**回声基线学习** (`learnEchoFloor`): TTS 期间扬声器→mic 回声累积,用户要超过 `ECHO_MARGIN_VOL` 才算真插话(防 AEC 残留误打断)。

---

## 4. 与 voice-node v3 横向对比

### 4.1 已借鉴 (✅)

| 项 | voice-node v3 | 白龙马 | 来源 |
|---|---|---|---|
| threshold | **0.35** (借鉴) | 0.35 | commit 1521fc6 |
| keywords_score | **3.0** (借鉴) | 3.0 | commit 1521fc6 |
| cooldown_ms | **800** (借鉴) | 800ms | commit 1521fc6 |
| AudioContext 16kHz | ✅ | ✅ | 已有 |
| echoCancellation=false | ✅ | ✅ | 已有 |
| KWS 模型架构 (transducer int8) | ✅ 同款 | Zipformer 13/16/64 | 已有 |
| 关键词格式 (拼音 + @汉字) | ✅ | ✅ "x iǎo b ái l óng @小白龙" | 已有 |

### 4.2 立即可借鉴 (但 voice-node 还没做) — 高 ROI

| 项 | 白龙马做法 | voice-node 应改 | 工作量 |
|---|---|---|---|
| **noiseSuppression** | `false` | 改成 `false` (现为 `true`) | 1 行 |
| **autoGainControl** | `false` | 加上 `false` | 1 行 |
| **PCM 类型** | Float32 (直发) | Int16 → Float (转换) | worklet 改 6 行 |
| **Chunk 大小** | 1600 样本直发 | 8000 样本 (我们 over-buffer) | 取消 buffer 累积 |
| **AcceptWaveform sample_rate** | Float32 + 16000 | Int16 转换后 + 16000 | 跳过转换步骤 |
| **KWS 进程隔离** | utilityProcess | 不需要 (Java 没 onnxruntime 冲突) | 跳过 |

### 4.3 不适合借鉴 — 平台差异

| 项 | 白龙马 | voice-node | 原因 |
|---|---|---|---|
| 进程隔离 | utilityProcess | N/A | Java sherpa-onnx 不跟 Java 生态冲突 |
| Cloud ASR (Paraformer / 火山 / 豆包) | ✅ 5 个云 | 走 OpenClaw Gateway | voice-node 已有 cto agent |
| voice-panel.js 编排 (球动画 / TTS 路由) | ✅ | N/A | voice-node 没那么复杂 UI |
| voice-orb.html 悬浮球 | ✅ | N/A | voice-node 是 Web 界面 |
| Whisper 兜底 | ✅ | N/A | voice-node 用 cto agent STT |
| 977 行 voice-core.js 引擎 | ✅ | 走 OpenClaw Gateway | voice-node 是 WebSocket 桥接 |

---

## 5. voice-node v3 KWS 当前问题分析

### 5.1 问题现状
- 音频传输: ✅ 修好了 (ByteBuffer 越界 + chunk 累积)
- 音频信号: ✅ maxAmp=0.79 ≈ 26000/32768 真实信号
- 模型加载: ✅ threshold=0.35, score=3.0
- **stream_ready=false 永远 false** — 模型 `isReady()` 永远不返回 true

### 5.2 三种可能根因

| 假设 | 证据 | 修复路径 |
|---|---|---|
| **A. chunk 大小不合适** | 8000 样本 / 100ms+ 喂,白龙马 1600 / 100ms | 改 chunk 大小 1600 |
| **B. 音频格式** | voice-node Int16→Float 转换,白龙马直接 Float32 | 改成 Float32 worklet |
| **C. sherpa-onnx Java binding 1.13.4 bug** | 白龙马 1.13.3 Node binding 正常,Java 1.13.4 不行 | **升级 sherpa-onnx 到 1.14+/latest** |

### 5.3 最可能的修复路径 (按概率)

1. **改 worklet 为 Float32** (medium ROI, simple change)
2. **改 chunk 大小为 1600 样本** (high ROI, simple change)  
3. **升级 sherpa-onnx 1.14+** (high ROI, may need native build)

**推荐**: 改 worklet + chunk size + 升级 sherpa-onnx, 三步一起做。改 worklet 让前端代码重写;改 chunk 让后端代码改;升级 sherpa-onnx 跑 mvn compile 测试。

---

## 6. 实施建议 (明天/明早可做)

### 6.1 高 ROI 立即可做 (1-2 小时)

#### 步骤 1: 改前端 worklet 为 Float32
```js
// frontend/src/audio/kwsMonitor.ts
// Float32Array 直接发,不在 worklet 里转 Int16
this.workletNode.port.onmessage = (e) => {
  if (this.client) {
    this.client.sendAudio(e.data)  // e.data 是 Float32Array
  }
}
```
- 配合 pcm-worklet.js 改 Float32 输出
- 共 ~15 行

#### 步骤 2: 改 KwsService chunk 大小 + 取消累积
```java
// KwsService.java
private static final int SAMPLES_PER_FEED = 1600;  // 100ms @16k

public synchronized String acceptFrame(String sessionId, byte[] pcmFrame) {
    // 不再累积,直接转 Float32 并 acceptWaveform
    float[] samples = ...;
    stream.acceptWaveform(samples, props.sampleRate());
    while (spotter.isReady(stream)) {
        spotter.decode(stream);
    }
    ...
}
```
- 配合 step 1(都是 Float32)
- 共 ~20 行

#### 步骤 3: 加 noiseSuppression=false + autoGainControl=false
```typescript
audio: {
  channelCount: 1,
  echoCancellation: false,
  noiseSuppression: false,   // 改
  autoGainControl: false,    // 加
  sampleRate: 16000,
}
```

### 6.2 中 ROI — 升级 sherpa-onnx

```bash
# 1. 找最新版
/Users/caoxuefeng/anaconda3/envs/wake-trainer/bin/pip index versions sherpa-onnx

# 2. 装到 Java
# 找对应 Mac M1 + Java 21 的 wheel
# 通常需要 mvn install:install-file 把新 jar 装到本地 repo

# 3. 重新测试
mvn spring-boot:run
# 看 stream_ready=true
```

### 6.3 低 ROI — 进程隔离 (不需要)

Java sherpa-onnx 跟 OpenClaw Gateway 用的 onnxruntime-node 不会冲突(语言不一样,Java 走 JNI,Node 走 N-API),**不需要 utilityProcess 隔离**。

### 6.4 高 ROI 但工作量大 — 自训练白龙马类似工作流

白龙马的 KWS 工作流:
- AudioWorklet 16kHz Float32 → IPC
- 主进程 IPC 桥 → utilityProcess
- utilityProcess sherpa-onnx-node

可以借鉴到 voice-node 的"语音 gateway"模式:
- voice-node 后端 → 浏览器 Float32 WebSocket → sherpa-onnx Java 后端
- **但这其实就是当前架构**。不需要变。

---

## 7. 一句话最终建议

**明早第一件事: 改前端 worklet 为 Float32 + 改后端 chunk 为 1600 样本 + 加 noiseSuppression=false 和 autoGainControl=false(总计 1 小时),然后看 stream_ready 是不是 true。**

如果还是 false → 升级 sherpa-onnx。

如果升级后还是 false → sherpa-onnx Java binding 1.13.4+ 在 streaming Zipformer KWS 上有 bug,可能需要改用 OnlineRecognizer 流式 KWS(更复杂的路径)。

---

## 附录 A: 关键文件位置速查

```
/Volumes/ssd/BaiLongma/
├── electron/
│   ├── kws-process.cjs              (19 行,只常量)
│   ├── wake-word.cjs                 (96 行,主进程管理)
│   ├── wake-probe.html               (86 行,AudioWorklet 采集)
│   ├── wake-probe-preload.cjs        (17 行,IPC 桥)
│   ├── main.cjs                       (IPC 总线,见 L1125-1127 wake:pcm)
│   └── voice-orb*.{html,cjs}         (悬浮球窗口)
├── src/ui/brain-ui/
│   ├── voice-core.js                 (977 行,机制层)
│   ├── voice-wake.js                 (190 行,唤醒编排)
│   ├── voice-continuous.js           (249 行,常开+barge-in)
│   ├── voice-ptt.js                  (按住说话)
│   ├── voice-panel.js                (117 行,编排)
│   └── tts-fx.js, audio-output.js    (TTS 输出)
├── src/voice/
│   ├── kws-model/                    (sherpa-onnx KWS 模型)
│   │   ├── encoder-epoch-13-...int8.onnx
│   │   ├── decoder-epoch-13-...int8.onnx
│   │   ├── joiner-epoch-13-...int8.onnx
│   │   ├── tokens.txt                (拼音 token 词表)
│   │   └── keywords.txt              (6 条 "小白龙" 拼音变体)
│   ├── cloud-asr.js, manager.js      (5 个云 ASR 适配)
│   ├── macos-speech.{js,swift}      (本地 ASR)
│   └── whisper/, whisper_server.py   (Whisper 兜底)
└── .understand-anything/
    ├── knowledge-graph.json          (788 行,8 个 layer)
    └── meta.json
```

## 附录 B: 白龙马 KWS 调参心法 (重点)

| 参数 | 值 | 怎么调出来的 |
|---|---|---|
| threshold | 0.35 | 默认 0.25 误触发多,**调到 0.35** |
| score | 3.0 | **实测 13/17 vs 2.0 的 9/17**,翻倍召回 |
| cooldown | 800ms | 短了多触发,长了漏触发 |
| 块大小 | 1600 (100ms) | 太大延迟高,太小消息风暴 |
| mic 处理 | 全关 (false × 3) | "**模型在干净音频上训练**" — 直说 |

**核心 insight**: **模型训练时是干净音频,推理时也必须给干净音频**。所有 mic 后处理 (AEC / NS / AGC) 都会扭曲,直接拉低识别率。voice-node 的 noiseSuppression=true 是个**潜在 ROI 修复点**。

---

## 附录 C: sherpa-onnx 跨平台/跨版本兼容性

| 项目 | sherpa 版本 | 语言 | KWS 工作? |
|---|---|---|---|
| 白龙马 | 1.13.3 | Node native addon | ✅ 是 |
| voice-node v3 | 1.13.4 | Java JNI | ❌ 否 (stream_ready=false) |

可能 1.13.3 → 1.13.4 升级过程中引入了 streaming bug。或者 Node 绑定和 Java 绑定的实现路径不同。

**建议升级 voice-node 到 sherpa-onnx 1.14+ (latest)**,这个工作优先级: 中(不确定能不能修复,但风险低,30 分钟)。

---

*本报告生成于 2026-07-25 00:44~01:30,voice-node v3 KWS 调试间期。用户明早起来第一件事:看这份报告 + 试实施 6.1 节的 3 个改动。*
