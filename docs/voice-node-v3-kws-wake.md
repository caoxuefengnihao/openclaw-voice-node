# Voice Node v3: KWS 唤醒词集成 设计文档

> 状态: **v2 设计稿**,等老板拍板
> 创建: 2026-07-24 08:32 by 普罗米修斯 (CTO agent)
> 修订: 2026-07-24 08:42 -- 改为完全独立端点方案 (方案 B)，不动任何现有代码
> 拍板线索:
>   - 2026-07-24 07:39 桌面机器人讨论 -> "桌面机器人先不考虑了,先搞唤醒"
>   - 2026-07-24 08:24 唤醒路径拍板 -> "肯定是 B,本地 KWS"
>   - 2026-07-24 08:27 sherpa-onnx KWS 选型确认 -> "复用 STT 同一个库"
>   - 2026-07-24 08:42 老板要求 -> "一定要独立于现在的代码,需要 STT/TTS 的可以调现有方法"
> 关联 commit:
>   - `02ca2b1` PR #1 (v2 STT/TTS main)
>   - `14f0936` 后端 M1-B audio.* WS 协议
>   - `5e6059e` 前端 M1-C AudioRecorder

---

## 一、目标与范围

### 1.1 业务目标

把 voice-node 从"点 mic 按钮 -> 说话"升级到"喊一声 -> 自动进入对话"。

具体：

| 维度 | 现在 (v2) | 目标 (v3) |
|---|---|---|
| 触发方式 | 手动点 mic 按钮 + 按住说话 | 喊唤醒词 "嗨 CTO" 自动触发 |
| 浏览器依赖 | 必须开前端页面 | 浏览器常驻即可 (低占用) |
| STT 起点 | 用户点完 mic 后 | 检测到唤醒词后自动开始 |
| TTS 终点 | 用户松 mic 后 | 用户说"拜拜"/超时自动结束 |
| 体验 | 跟 Siri 手动触发类似 | 跟"小爱同学"唤醒后对话类似 |

### 1.2 核心原则

> **完全独立于现有代码。**

- ❌ 不修改 `VoiceWebSocketHandler.java`
- ❌ 不修改 `VoiceNodeProperties.java`
- ❌ 不修改 `application.yml`（新建独立配置文件）
- ❌ 不修改 `App.vue`、`recorder.ts`、`voiceClient.ts`
- ✅ **可以调用** `SttService.recognize(byte[])` 和 `TtsService.synthesize(String)` 的 public 方法
- ✅ 新建独立的 WS 端点 `/ws/kws`
- ✅ 新建独立的 Handler、Service、Config、前端组件

### 1.3 非目标 (本期不做)

- 多唤醒词 (只支持一个)
- 自训练唤醒词 (先用 sherpa-onnx 预训练词 + 占位)
- 嵌入式 KWS (ESP32-S3 上跑) - 桌面版够用
- macOS 系统级 Voice Wake - 跟"本地开发"原则冲突
- 跨用户 (只服务一个 sessionKey,跟 cto agent 绑定)

### 1.4 范围

✅ 包含:
- **独立**后端 KWS WS 端点 (`/ws/kws`) + KwsWebSocketHandler
- **独立**后端 KWS 服务 (sherpa-onnx KeywordSpotter,跟 STT 同库)
- **独立**前端 KwsMonitor 组件 + KwsPage.vue
- 唤醒后自动进入对话流程 (内部调 SttService + TtsService + ChatBridgeService)
- 独立配置 (KwsProps + 独立 application-kws.yml profile)
- 单元测试 + 集成测试 + 真实场景测试

❌ 不包含:
- 自定义唤醒词训练 (放后续 milestone)
- 多客户端并发 KWS (单浏览器够用)
- 对现有 v2 页面 / Handler 的任何修改

---

## 二、现状事实 (基于 2026-07-24 08:30 实测扫代码)

### 2.1 现有架构 (不动)

```
浏览器                                       Java 后端                              OpenClaw Gateway
  │                                            │                                        │
  │ 点 mic 按钮                                │                                        │
  │  ─-> audio.start ─────────────────────────▶│  VoiceWebSocketHandler                 │
  │  ─-> audio.chunk (binary PCM) × N ────────▶│  累积到 buffer                         │
  │  ─-> audio.end ────────────────────────────▶│  SttService.recognize(pcm)              │
  │                                            │  ─-> "今天天气怎样"                     │
  │                                            │  ChatBridgeService -> chat.sendText() ─▶│
  │  ◀── user.text ───────────────────────────│                                        │
  │  ◀── assistant / turn.done ───────────────│  TtsService.synthesize(text)            │
  │  ◀── assistant.audio (mp3 base64) ────────│                                        │
```

### 2.2 现有可复用 Service (只调方法,不改代码)

| Service | 方法签名 | 用途 |
|---|---|---|
| `SttService` | `public synchronized String recognize(byte[] pcm16kMonoInt16LE)` | PCM -> 文本 |
| `TtsService` | `public byte[] synthesize(String text)` | 文本 -> MP3 bytes |
| `ChatBridgeService` | `public ChatSessionHandle open(WebSocketSession session)` | 开 chat session |
| `ChatSessionHandle` | `public void sendText(String content)` | 发文本给 gateway |
| `ChatSessionHandle` | `public void addTurnEndListener(Consumer<String> listener)` | 注册回复结束回调 |

### 2.3 关键代码点 (只读参考,不修改)

- **后端 WS Handler**:`api/VoiceWebSocketHandler.java` -- **不改**
- **STT 服务**:`service/SttService.java` -- **不改,只调 `recognize()`**
- **TTS 服务**:`service/TtsService.java` -- **不改,只调 `synthesize()`**
- **Chat 桥**:`service/ChatBridgeService.java` -- **不改,只调 `open()`**
- **前端录音**:`audio/recorder.ts` -- **不改,KwsMonitor 自己实现录音**
- **前端 WS**:`api/voiceClient.ts` -- **不改,KwsMonitor 用独立 WS 连接**

### 2.4 依赖现状

- ✅ sherpa-onnx 1.13.4 (STT 用 Paraformer-zh) -> 复用同一个 Maven 依赖,KWS 不需要新增 pom.xml 依赖
- ✅ Spring Boot 3.3 + Java 21 + Jackson + Lombok
- ✅ Vue 3 + Vite + TypeScript
- ✅ WebSocket 二进制帧支持
- ❌ 无 KWS 模型文件 - 需要新下载
- ❌ 前端无持续监听组件 - 需要新写

### 2.5 性能预算

- sherpa-onnx KWS RTF: ~0.05 (1s 音频用 50ms 推理)
- Mac mini M1 CPU 占用: < 5% (一个核,16kHz 单声道)
- 检测延迟: 唤醒词时长 + 100ms 推理 + 100ms 网络 = **~500ms 总延迟**
- 网络流量: 持续音频上传 16kHz × 2 bytes = **32 KB/s** per session
- 24h 监听磁盘/内存: 几乎为零 (不持久化音频)

---

## 三、架构设计 (v3 -- 独立端点方案)

### 3.1 高层架构

```
浏览器 (KwsPage.vue)                    Java 后端                              OpenClaw Gateway
  │                                       │                                      │
  │ 页面打开后自动连 /ws/kws              │                                      │
  │  ─-> audio.kws.start ────────────────▶│  KwsWebSocketHandler (独立端点)       │
  │  ─-> audio.chunk (binary PCM) × N ───▶│  KwsService.acceptFrame() 每帧过 KWS  │
  │  (持续, ~32 KB/s)                     │  KeywordSpotter (sherpa-onnx)        │
  │                                       │                                      │
  │                                       │  [检测到 "嗨小爱"]                    │
  │  ◀── wake.detected ──────────────────│  发 wake 事件                        │
  │                                       │                                      │
  │ 前端自动进入录音模式                   │                                      │
  │  ─-> audio.start ─────────────────────▶│  KwsWebSocketHandler 切到录音模式     │
  │  ─-> audio.chunk × N ────────────────▶│  累积 PCM buffer                     │
  │  ─-> audio.end ──────────────────────▶│  SttService.recognize(pcm)  [调现有] │
  │                                       │  ChatBridgeService.open()   [调现有] │
  │                                       │  chatSession.sendText(text) [调现有] │
  │                                       │  ◀── assistant / turn.done ──────────│
  │  ◀── assistant / assistant.audio ────│  TtsService.synthesize(text) [调现有] │
  │                                       │                                      │
  │ TTS 播完 -> 前端自动回 KWS 监听         │                                      │
  │  ─-> audio.kws.start ────────────────▶│  KwsService 重启监听                  │
```

### 3.2 与现有代码的边界

```
┌─────────────────────────────────────────────────────────────────┐
│                    Java 后端                                     │
│                                                                  │
│  ┌──────────────────────┐    ┌──────────────────────────────┐  │
│  │  现有 v2 (不动)       │    │  v3 新增 (独立)               │  │
│  │                      │    │                              │  │
│  │  /ws/audio            │    │  /ws/kws                     │  │
│  │  VoiceWebSocketHandler│    │  KwsWebSocketHandler          │  │
│  │  SttService ──────────┼────┼─> recognize()  [调用]        │  │
│  │  TtsService ──────────┼────┼─> synthesize()  [调用]       │  │
│  │  ChatBridgeService ───┼────┼─> open()  [调用]             │  │
│  │  ChatSessionHandle    │    │  KwsService (新)              │  │
│  │                      │    │  KwsProps (新)                │  │
│  │  application.yml      │    │  application-kws.yml (新)     │  │
│  └──────────────────────┘    └──────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  共享 Spring 容器 (同一进程,同一端口)                       │   │
│  │  WebSocketConfig: 注册 /ws/audio (现有) + /ws/kws (新)    │   │
│  │  ↑ WebSocketConfig 只加一行 registerWebSocketHandler     │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

> **唯一例外**: `WebSocketConfig.java` 需要加一行注册 `/ws/kws` 端点。
> 如果连这都不想动,可以用 `@Configuration` + `WebSocketHandlerRegistry` 自动注册 (Spring 4.2+),
> 但那样代码更绕。**建议接受这一行改动**,或者用 Spring Boot auto-config 独立配置类。

### 3.3 状态机 (前端)

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

### 3.4 关键设计决策

| 决策 | 选项 | 选择 | 理由 |
|---|---|---|---|
| KWS 跑前端还是后端 | 前端 WASM / 后端 native | **后端 native** | 复用 sherpa-onnx Java binding + 浏览器 0 CPU |
| 独立端点 vs 改现有 Handler | 独立 `/ws/kws` / 改 `/ws/audio` | **独立端点** | 老板要求不动现有代码 |
| 监听模式触发时机 | 页面打开即启 / 用户主动启 | **页面打开即启** | 体验跟"小爱同学"一致 |
| 唤醒后自动录音 | 自动 / 用户确认 | **自动** | 减少操作;超时 fallback (10s 不说话回 IDLE) |
| 持续监听帧间隔 | 高频 (30ms) / 中频 (100ms) | **中频 100ms** | 网络流量减 3 倍,KWS 检测精度足够 |
| KWS 模型 | 预训练 / 自训练 | **预训练先用** | 5 min 跑通流程,自训练加 2 天工作量 |

---

## 四、技术选型

### 4.1 为什么选 sherpa-onnx KWS

| 候选 | 优点 | 缺点 | 决策 |
|---|---|---|---|
| **sherpa-onnx KWS** | 已在用同库 (STT),零学习成本;Apache 2.0;中文友好 | 模型精度略低于 Porcupine | ✅ **选这个** |
| openWakeWord | 新、Python 原生;支持多唤醒词 | Python-only,跟 Java 后端架构不匹配 | ❌ |
| Picovoice Porcupine | 商业产品级准确率 | 商业授权;闭源 | ❌ |
| Mycroft Precise | 老牌开源 | Python 2 only,已停更 | ❌ |
| Vosk KWS | Vosk 也有 KWS | 跟 STT 重复依赖,模型更大 | ❌ |
| macOS Voice Wake | 系统级,零代码 | 跟"本地开发"原则冲突;只支持预设词 | ❌ |

**核心论点:复用已有 sherpa-onnx Maven 依赖,不需要改 pom.xml。**

### 4.2 模型选型

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

// 构造
FeatureConfig featureConfig = FeatureConfig.builder()
    .setSampleRate(16000)
    .setFeatureDim(80)
    .build();

OnlineModelConfig modelConfig = OnlineModelConfig.builder()
    .setWenetCtc(com.k2fsa.sherpa.onnx.WenetCtcModelConfig.builder()
        .setModel(encoderPath)
        .build())
    .setTokens(tokensPath)
    .setNumThreads(props.numThreads())
    .build();

KeywordSpotterConfig config = KeywordSpotterConfig.builder()
    .setOnlineModelConfig(modelConfig)
    .setFeatureConfig(featureConfig)
    .setKeywordsFile(keywordsPath)
    .setKeywordsThreshold(props.threshold())
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
    log.info("🔥 Wake detected: {}", result.getKeyword());
}
stream.release();
```

---

## 五、WS 协议设计 (独立端点 /ws/kws)

### 5.1 协议总览

`/ws/kws` 是**独立** WS 端点,跟现有 `/ws/audio` 完全隔离。前端 KwsPage.vue 只连 `/ws/kws`,不连 `/ws/audio`。

### 5.2 上行 (浏览器 -> Java)

| type | 说明 |
|---|---|
| `audio.kws.start` | 开始 KWS 持续监听 |
| `audio.kws.stop` | 停止 KWS 监听 |
| `audio.start` | 唤醒后开始录音 (单次对话用) |
| `<binary>` | PCM chunk (16kHz mono int16 LE),KWS 模式过 KwsService,录音模式累积 buffer |
| `audio.end` | 结束录音,触发 STT -> chat -> TTS |
| `audio.cancel` | 取消录音 |
| `ping` | 心跳 |

### 5.3 下行 (Java -> 浏览器)

| type | 说明 |
|---|---|
| `kws.ack` | KWS 监听已启动 |
| `wake.detected` | 检测到唤醒词 |
| `kws.error` | KWS 检测出错 |
| `audio.ack` | 录音/STT ack |
| `user.text` | STT 识别文本 |
| `assistant` | LLM 回复文本 (流式) |
| `turn.done` | LLM 回复结束 |
| `assistant.audio` | TTS 音频 (base64 mp3) |
| `error` | 通用错误 |
| `pong` | 心跳回复 |

### 5.4 消息 schema (JSON)

```json
// 上行: 启动 KWS 监听
{ "type": "audio.kws.start", "threshold": 0.6 }
// 上行: 停止 KWS 监听
{ "type": "audio.kws.stop" }

// 下行: KWS 监听启动 ack
{ "type": "kws.ack", "state": "listening", "keywords": ["嗨小爱"] }
// 下行: 检测到唤醒词
{
  "type": "wake.detected",
  "keyword": "嗨小爱",
  "score": 0.82,
  "timestamp": 1721801234567
}
// 下行: KWS 出错
{ "type": "kws.error", "message": "..." }
```

---

## 六、后端详细设计

### 6.1 文件清单 (全部新增)

| 文件 | 说明 |
|---|---|
| `config/KwsProps.java` | KWS 配置 record |
| `service/KwsService.java` | KWS 检测服务 |
| `api/KwsWebSocketHandler.java` | 独立 WS Handler (`/ws/kws`) |
| `api/KwsWebSocketConfig.java` | 独立 WS 配置类 (注册端点,不改 WebSocketConfig) |

> **KwsWebSocketConfig 用 `@Configuration` + `implements WebSocketConfigurer` 自动注册**,
> Spring Boot 会自动发现,不需要改现有的 `WebSocketConfig.java`。

### 6.2 KwsProps.java

```java
package com.openclaw.voicenode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KWS 配置 (v3 新增,独立于 STT 配置)。
 * - model-dir 指向 KWS 模型目录 (含 encoder.onnx + tokens.txt + keywords.txt)
 * - threshold 越高越不灵敏 (0.1~0.9)
 * - enabled false 可完全关闭 KWS (兜底)
 */
@ConfigurationProperties(prefix = "kws")
public record KwsProps(
    String modelDir,
    int numThreads,
    int sampleRate,
    float threshold,
    boolean enabled
) {
    public KwsProps {
        if (modelDir == null || modelDir.isBlank())
            modelDir = "${project.basedir}/models/kws";
        if (numThreads <= 0) numThreads = 1;
        if (sampleRate != 16000) sampleRate = 16000;
        if (threshold < 0.1f || threshold > 0.9f) threshold = 0.6f;
    }
}
```

配置文件: **新建 `src/main/resources/application-kws.yml`** (Spring profile,不碰现有 application.yml):

```yaml
kws:
  model-dir: ${project.basedir}/models/kws
  num-threads: 1
  threshold: 0.6
  enabled: true
```

启动方式: `mvn spring-boot:run -Dspring.profiles.active=kws` 或在现有启动脚本里加 profile。

### 6.3 KwsService.java

```java
package com.openclaw.voicenode.service;

import com.k2fsa.sherpa.onnx.*;
import com.openclaw.voicenode.config.KwsProps;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KWS 唤醒词检测服务 (v3 新增)。
 *
 * 复用 sherpa-onnx (跟 SttService 同库不同模型)。
 * KeywordSpotter 是单线程的,acceptFrame 用 synchronized。
 *
 * 每个 WS session 对应一个 OnlineStream,检测到唤醒词后重置 stream。
 */
@Slf4j
@Service
public class KwsService {

    private final KwsProps props;
    private KeywordSpotter spotter;
    private final Map<String, OnlineStream> sessionStreams = new ConcurrentHashMap<>();

    public KwsService(KwsProps props) {
        this.props = props;
    }

    @PostConstruct
    public void init() throws Exception {
        if (!props.enabled()) {
            log.info("🔇 KWS 已禁用 (kws.enabled=false)");
            return;
        }

        Path encoderPath = Paths.get(props.modelDir(), "encoder.onnx");
        Path tokensPath = Paths.get(props.modelDir(), "tokens.txt");
        Path keywordsPath = Paths.get(props.modelDir(), "keywords.txt");

        if (!Files.exists(encoderPath) || !Files.exists(tokensPath)) {
            throw new IllegalStateException(
                "KWS 模型文件不存在: " + encoderPath + "\n"
                + "请先跑: ./scripts/download-kws-deps.sh");
        }

        log.info("🔥 加载 KWS 模型: modelDir={}, threshold={}", props.modelDir(), props.threshold());

        long t0 = System.currentTimeMillis();

        FeatureConfig featureConfig = FeatureConfig.builder()
            .setSampleRate(props.sampleRate())
            .setFeatureDim(80)
            .build();

        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
            .setWenetCtc(WenetCtcModelConfig.builder()
                .setModel(encoderPath.toString())
                .build())
            .setTokens(tokensPath.toString())
            .setNumThreads(props.numThreads())
            .build();

        KeywordSpotterConfig config = KeywordSpotterConfig.builder()
            .setOnlineModelConfig(modelConfig)
            .setFeatureConfig(featureConfig)
            .setKeywordsFile(keywordsPath.toString())
            .setKeywordsThreshold(props.threshold())
            .build();

        this.spotter = new KeywordSpotter(config);
        log.info("✅ KWS 模型加载完成 ({}ms)", System.currentTimeMillis() - t0);
    }

    /** 创建或重置 session 的 KWS stream */
    public void startSession(String sessionId) {
        if (spotter == null) return;
        sessionStreams.put(sessionId, spotter.createStream());
        log.info("🔥 KWS session started: {}", sessionId);
    }

    /**
     * 喂 PCM 帧进 KWS。返回检测到的 keyword,空表示未检测到。
     *
     * @param sessionId WS session id
     * @param pcmFrame  16kHz mono int16 LE PCM bytes
     * @return 唤醒词文本,空字符串表示未检测到
     */
    public synchronized String acceptFrame(String sessionId, byte[] pcmFrame) {
        if (spotter == null) return "";

        OnlineStream stream = sessionStreams.get(sessionId);
        if (stream == null) return "";

        float[] samples = pcmToFloat(pcmFrame);
        stream.acceptWaveform(samples, props.sampleRate());

        while (spotter.isReady(stream)) {
            spotter.decode(stream);
        }

        KeywordSpotterResult result = spotter.getResult(stream);
        if (!result.getKeyword().isEmpty()) {
            // 命中 -> 重置 stream,准备下一轮
            stream.release();
            sessionStreams.put(sessionId, spotter.createStream());
            log.info("🔥 Wake detected: session={}, keyword={}", sessionId, result.getKeyword());
            return result.getKeyword();
        }
        return "";
    }

    public void stopSession(String sessionId) {
        OnlineStream stream = sessionStreams.remove(sessionId);
        if (stream != null) {
            stream.release();
            log.info("🔥 KWS session stopped: {}", sessionId);
        }
    }

    @PreDestroy
    public void destroy() {
        sessionStreams.values().forEach(OnlineStream::release);
        sessionStreams.clear();
        if (spotter != null) spotter.release();
    }

    /** PCM 16kHz mono int16 LE -> float32 [-1.0, 1.0] */
    private static float[] pcmToFloat(byte[] pcm) {
        int n = pcm.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm[2 * i] & 0xff) | ((pcm[2 * i + 1] & 0xff) << 8));
            out[i] = s / 32768f;
        }
        return out;
    }
}
```

### 6.4 KwsWebSocketHandler.java

```java
package com.openclaw.voicenode.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.voicenode.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * KWS 独立 WS Handler (v3 新增,挂 /ws/kws 端点)。
 *
 * 完全独立于 VoiceWebSocketHandler,不碰 /ws/audio。
 *
 * 两种模式:
 * 1. KWS 监听模式: audio.kws.start -> 每帧过 KwsService -> wake.detected
 * 2. 录音对话模式: audio.start -> 累积 PCM -> audio.end -> SttService + ChatBridge + TtsService
 *
 * 模式切换:
 * - wake.detected 后前端发 audio.start,Handler 自动从 KWS 模式切到录音模式
 * - turn.done 后前端发 audio.kws.start,Handler 切回 KWS 模式
 *
 * 复用现有 Service (只调方法):
 * - SttService.recognize(byte[]) -> PCM 转文本
 * - TtsService.synthesize(String) -> 文本转 MP3
 * - ChatBridgeService.open(session) -> 开 chat session
 * - ChatSessionHandle.sendText(text) -> 发给 gateway
 * - ChatSessionHandle.addTurnEndListener(cb) -> 回复结束回调
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KwsWebSocketHandler extends AbstractWebSocketHandler {

    private final KwsService kwsService;
    private final SttService sttService;        // 复用现有
    private final TtsService ttsService;        // 复用现有
    private final ChatBridgeService chatBridge;  // 复用现有
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String ATTR_CHAT = "chat";
    private static final String ATTR_PCM_BUFFER = "pcmBuffer";
    private static final String ATTR_MODE = "mode";  // "kws" | "recording"

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("KWS WS connected: {}", session.getId());
        ChatSessionHandle chat = chatBridge.open(session);
        session.getAttributes().put(ATTR_CHAT, chat);

        // turn.done -> TTS 合成 (跟 VoiceWebSocketHandler 一样,但独立注册)
        chat.addTurnEndListener(fullText -> {
            try {
                if (fullText == null || fullText.isBlank()) return;
                log.info("🔊 KWS turn.done -> TTS ({} chars)", fullText.length());
                byte[] audio = ttsService.synthesize(fullText);  // 调现有 TtsService
                sendToBrowser(session, Map.of(
                    "type", "assistant.audio",
                    "audio", Base64.getEncoder().encodeToString(audio),
                    "format", "mp3"
                ));
            } catch (Exception e) {
                log.warn("KWS TTS 失败: {}", e.getMessage());
            }
        });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String mode = (String) session.getAttributes().get(ATTR_MODE);
        byte[] payload = message.getPayload().array();

        if ("kws".equals(mode)) {
            // KWS 模式:每帧过 KWS
            String keyword = kwsService.acceptFrame(session.getId(), payload);
            if (!keyword.isEmpty()) {
                log.info("🔥 Wake detected: session={}, keyword={}", session.getId(), keyword);
                kwsService.stopSession(session.getId());
                sendToBrowser(session, Map.of(
                    "type", "wake.detected",
                    "keyword", keyword,
                    "timestamp", System.currentTimeMillis()
                ));
            }
        } else {
            // 录音模式:累积到 buffer (跟 VoiceWebSocketHandler 一样)
            ByteArrayOutputStream buf = (ByteArrayOutputStream) session.getAttributes().get(ATTR_PCM_BUFFER);
            if (buf == null) {
                log.warn("⚠️ KWS Handler 收到 binary 但无 audio.start,丢弃");
                return;
            }
            try {
                buf.write(payload);
            } catch (IOException e) {
                log.warn("写 PCM buffer 失败", e);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg = mapper.readValue(message.getPayload(), Map.class);
        String type = (String) msg.get("type");
        log.info("📥 KWS msg: type={}", type);

        ChatSessionHandle chat = (ChatSessionHandle) session.getAttributes().get(ATTR_CHAT);

        if ("audio.kws.start".equals(type)) {
            // 进入 KWS 监听模式
            kwsService.startSession(session.getId());
            session.getAttributes().put(ATTR_MODE, "kws");
            sendToBrowser(session, Map.of(
                "type", "kws.ack",
                "state", "listening",
                "keywords", List.of("嗨小爱")  // 从模型 keywords.txt 读
            ));

        } else if ("audio.kws.stop".equals(type)) {
            kwsService.stopSession(session.getId());
            session.getAttributes().remove(ATTR_MODE);

        } else if ("audio.start".equals(type)) {
            // 切到录音模式
            session.getAttributes().put(ATTR_MODE, "recording");
            session.getAttributes().put(ATTR_PCM_BUFFER, new ByteArrayOutputStream());
            sendToBrowser(session, Map.of("type", "audio.ack", "state", "recording"));

        } else if ("audio.end".equals(type)) {
            // 累积结束 -> STT -> chat -> TTS
            ByteArrayOutputStream buf = (ByteArrayOutputStream) session.getAttributes().get(ATTR_PCM_BUFFER);
            if (buf == null || buf.size() == 0) {
                sendToBrowser(session, Map.of("type", "error", "message", "audio buffer empty"));
                return;
            }
            byte[] pcm = buf.toByteArray();
            session.getAttributes().remove(ATTR_PCM_BUFFER);
            session.getAttributes().remove(ATTR_MODE);

            try {
                // 调现有 SttService
                String text = sttService.recognize(pcm);
                sendToBrowser(session, Map.of("type", "user.text", "text", text, "isFinal", true));

                // 调现有 ChatBridge
                if (chat != null && !text.isBlank()) {
                    log.info("📤 KWS STT -> chat.sendText: \"{}\"", text);
                    chat.sendText(text);
                }
            } catch (SttService.SttException e) {
                log.warn("KWS STT 失败: {}", e.getMessage());
                sendToBrowser(session, Map.of("type", "error", "message", "STT failed: " + e.getMessage()));
            }

        } else if ("audio.cancel".equals(type)) {
            session.getAttributes().remove(ATTR_PCM_BUFFER);
            session.getAttributes().remove(ATTR_MODE);
            log.info("🎤 KWS audio.cancel");

        } else if ("ping".equals(type)) {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of("type", "pong"))));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("KWS WS closed: {} ({})", session.getId(), status);
        kwsService.stopSession(session.getId());
        ChatSessionHandle chat = (ChatSessionHandle) session.getAttributes().get(ATTR_CHAT);
        if (chat != null) chat.close();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("KWS WS transport error", exception);
    }

    private void sendToBrowser(WebSocketSession session, Map<String, Object> data) {
        if (!session.isOpen()) return;
        try {
            String json = mapper.writeValueAsString(new LinkedHashMap<>(data));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("KWS sendToBrowser 失败: {}", e.getMessage());
        }
    }
}
```

### 6.5 KwsWebSocketConfig.java

```java
package com.openclaw.voicenode.api;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

/**
 * 独立注册 /ws/kws 端点。
 * 不修改现有 WebSocketConfig.java。
 * Spring Boot 自动发现 @Configuration 类,合并到 WebSocketHandlerRegistry。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class KwsWebSocketConfig implements WebSocketConfigurer {

    private final KwsWebSocketHandler kwsHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(kwsHandler, "/ws/kws")
                .setAllowedOrigins("*");
    }
}
```

> ⚠️ 如果现有 `WebSocketConfig` 也用了 `@EnableWebSocket`,Spring 不会冲突--
> 多个 `WebSocketConfigurer` 实现会合并注册。这是 Spring 设计支持的用法。

---

## 七、前端详细设计

### 7.1 文件清单 (全部新增)

| 文件 | 说明 |
|---|---|
| `frontend/src/audio/kwsMonitor.ts` | 持续录音 + 发 audio.chunk + 接 wake.detected |
| `frontend/src/api/kwsClient.ts` | 独立 WS 客户端 (连 /ws/kws) |
| `frontend/src/KwsPage.vue` | 独立页面组件 (路由 /kws) |
| `frontend/src/audio/pcm-worklet.js` | **复用现有** (recorder.ts 用的同一个) |

### 7.2 kwsClient.ts (独立 WS 客户端)

```typescript
// api/kwsClient.ts -- 独立 WS 客户端,连 /ws/kws
// 跟现有 VoiceClient 平行,不复用

type Handler = (...args: any[]) => void

export class KwsClient {
  private ws: WebSocket | null = null
  private listeners = new Map<string, Handler[]>()
  private url: string

  constructor(url?: string) {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
    this.url = url || `${proto}//${location.host}/ws/kws`
  }

  connect(): void {
    this.ws = new WebSocket(this.url)
    this.ws.binaryType = 'arraybuffer'

    this.ws.onopen = () => this.emit('open')
    this.ws.onclose = (ev) => this.emit('close', ev.code, ev.reason)
    this.ws.onerror = (ev) => this.emit('error', ev)

    this.ws.onmessage = (ev) => {
      if (typeof ev.data === 'string') {
        try {
          const msg = JSON.parse(ev.data)
          this.emit(msg.type || 'message', msg)
        } catch (e) {
          console.warn('[kws] 解析消息失败', e, ev.data)
        }
      }
    }
  }

  sendAudio(pcm: Int16Array): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(pcm.buffer)
    }
  }

  sendCommand(cmd: object): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(cmd))
    }
  }

  on(event: string, handler: Handler): void {
    if (!this.listeners.has(event)) this.listeners.set(event, [])
    this.listeners.get(event)!.push(handler)
  }

  off(event: string, handler: Handler): void {
    const arr = this.listeners.get(event)
    if (arr) this.listeners.set(event, arr.filter((h) => h !== handler))
  }

  private emit(event: string, ...args: any[]): void {
    for (const h of this.listeners.get(event) ?? []) {
      try { h(...args) } catch (e) { console.warn('[kws] handler error', e) }
    }
  }

  close(): void {
    try { this.ws?.close() } catch {}
  }
}
```

### 7.3 kwsMonitor.ts (持续监听)

```typescript
// audio/kwsMonitor.ts -- 持续录音 + 发 audio.chunk + 接 wake.detected
//
// 跟现有 AudioRecorder 平行,但:
// 1. 页面打开即启,不需要点按钮
// 2. 连 /ws/kws (不是 /ws/audio)
// 3. 持续发 audio.chunk,后端 KWS 每帧检测
// 4. 收到 wake.detected -> 自动切到录音模式
//
// 复用现有 pcm-worklet.js (同一个 AudioWorklet 模块)

import type { KwsClient } from '../api/kwsClient'

export class KwsMonitor {
  private mediaStream: MediaStream | null = null
  private audioContext: AudioContext | null = null
  private workletNode: AudioWorkletNode | null = null
  private client: KwsClient | null = null
  private chunkCount = 0

  async start(client: KwsClient): Promise<void> {
    if (this.audioContext) throw new Error('KWS already started')
    this.client = client
    this.chunkCount = 0

    // 复用 AudioRecorder 的 mic 拿法 (BT 耳机坑的修复也一起继承)
    this.mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: false,  // BT 耳机必关
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
        if (this.chunkCount % 50 === 0) {
          console.log(`[kws] 已发 ${this.chunkCount} 块 PCM`)
        }
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

### 7.4 KwsPage.vue (独立页面)

```vue
<!-- KwsPage.vue -- 独立 KWS 唤醒页面
  路由: /kws (跟现有 / 首页平行)
  不修改 App.vue
-->
<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { KwsClient } from './api/kwsClient'
import { KwsMonitor } from './audio/kwsMonitor'

type Status = 'idle' | 'kws-listening' | 'recording' | 'processing'

const status = ref<Status>('idle')
const errorMsg = ref('')
const messages = ref<Array<{ role: 'user' | 'assistant'; content: string; ts: number }>>([])

let client: KwsClient | null = null
const kwsMonitor = new KwsMonitor()

// 录音模式用的 PCM 播放 (跟 App.vue 一样的逻辑,但独立实现)
let playCtx: AudioContext | null = null
let autoStopTimer: number | null = null

function setupHandlers(c: KwsClient) {
  c.on('open', () => console.log('[kws-page] WS open'))
  c.on('close', (code: number, reason: string) => {
    console.log('[kws-page] WS close', code, reason)
    status.value = 'idle'
  })

  c.on('kws.ack', () => {
    status.value = 'kws-listening'
    console.log('[kws-page] KWS listening...')
  })

  c.on('wake.detected', async (msg: any) => {
    console.log('[kws-page] 🔥 wake detected:', msg.keyword)
    // 1. 停 KWS monitor
    kwsMonitor.stop()
    // 2. 通知后端切录音模式 + 发 audio.start
    c.sendCommand({ type: 'audio.start', sampleRate: 16000, encoding: 'pcm_s16le' })
    status.value = 'recording'
    // 3. 启 10s 自动停超时
    autoStopTimer = window.setTimeout(() => {
      if (status.value === 'recording') {
        c.sendCommand({ type: 'audio.end' })
        status.value = 'processing'
      }
    }, 10000)
  })

  c.on('user.text', (msg: any) => {
    if (msg.text) {
      messages.value.push({ role: 'user', content: msg.text, ts: Date.now() })
    }
  })

  c.on('assistant', (msg: any) => {
    // 流式追加 (简化:最后一条 assistant 更新)
    if (msg.final) {
      messages.value.push({ role: 'assistant', content: msg.text, ts: Date.now() })
    }
  })

  c.on('turn.done', async () => {
    // TTS 播放在 assistant.audio 事件
  })

  c.on('assistant.audio', async (msg: any) => {
    // 播放 TTS (独立 AudioContext)
    await playTts(msg.audio)
    // TTS 播完 -> 回 KWS 监听
    status.value = 'kws-listening'
    await kwsMonitor.start(c)
  })

  c.on('error', (msg: any) => {
    errorMsg.value = msg.message || 'unknown error'
    status.value = 'idle'
  })
}

async function playTts(base64Audio: string) {
  if (!playCtx) playCtx = new AudioContext()
  const binary = atob(base64Audio)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  const buf = await playCtx.decodeAudioData(bytes.buffer)
  const src = playCtx.createBufferSource()
  src.buffer = buf
  src.connect(playCtx.destination)
  return new Promise<void>((resolve) => {
    src.onended = () => resolve()
    src.start()
  })
}

// 手动停止录音
function stopRecording() {
  if (autoStopTimer) {
    clearTimeout(autoStopTimer)
    autoStopTimer = null
  }
  if (client) {
    client.sendCommand({ type: 'audio.end' })
    status.value = 'processing'
  }
}

onMounted(async () => {
  client = new KwsClient()
  setupHandlers(client)
  client.connect()

  // 等 open 后启 KWS
  client.on('open', async () => {
    await kwsMonitor.start(client!)
  })
})

onBeforeUnmount(() => {
  kwsMonitor.stop()
  client?.close()
  playCtx?.close().catch(() => {})
})
</script>

<template>
  <div class="kws-page">
    <h1>语音唤醒</h1>
    <div class="status" :class="status">
      {{ status === 'kws-listening' ? '🎧 监听中... 喊"嗨小爱"' :
         status === 'recording' ? '🎤 我在听,请说' :
         status === 'processing' ? '⏳ 思考中...' : '💤 待机' }}
    </div>
    <div v-if="errorMsg" class="error">{{ errorMsg }}</div>
    <button v-if="status === 'recording'" @click="stopRecording">说完</button>
    <div class="messages">
      <div v-for="msg in messages" :key="msg.ts" :class="msg.role">
        <strong>{{ msg.role === 'user' ? '你' : 'CTO' }}:</strong> {{ msg.content }}
      </div>
    </div>
  </div>
</template>
```

### 7.5 前端路由

在 `frontend/src/main.ts` 或路由配置中加 `/kws` 路由指向 `KwsPage.vue`。

> 如果没有 vue-router,可以新建 `kws.html` 入口,或直接在 `App.vue` 里用 query param `?kws=1` 切组件。
> **不碰 App.vue 的现有逻辑** -- 只在外层加一个 if 判断渲染 KwsPage 还是现有页面。

---

## 八、配置总览

### 8.1 独立配置文件

**新建 `src/main/resources/application-kws.yml`** (Spring profile):

```yaml
kws:
  model-dir: ${project.basedir}/models/kws
  num-threads: 1
  threshold: 0.6          # 唤醒阈值 0.1~0.9,越高越不灵敏
  enabled: true           # 紧急关闭 KWS (兜底)
```

**不修改现有 `application.yml`。**

启动: `mvn spring-boot:run -Dspring.profiles.active=kws`

### 8.2 配置加载

在 `VoiceNodeApplication.java` 的 `@SpringBootApplication` 上加 `@ConfigurationPropertiesScan` 包含 `KwsProps`。

> 如果不想动 `VoiceNodeApplication.java`,可以在 `KwsWebSocketConfig` 上加 `@ConfigurationPropertiesScan` 或用 `@EnableConfigurationProperties(KwsProps.class)`。

```java
@Configuration
@EnableConfigurationProperties(KwsProps.class)
public class KwsWebSocketConfig implements WebSocketConfigurer {
    // ...
}
```

这样完全不需要动现有 Application 类。

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

1. **录音**: Mac mini 自录 50-100 次 "嗨 CTO"
2. **加噪声**: 加 10 种背景噪声 × 5 种 SNR
3. **训练**: 用 `sherpa-onnx/cli/kws_train.py`,从 zipformer2-ctc 初始化
4. **测试**: 留 20% 样本做测试集,目标 FAR < 1 次/小时, FRR < 5%
5. **导出**: encoder.onnx + keywords.txt

工作量:2 天。

---

## 十、实现步骤 (commit 拆分)

### Phase 1: 后端 KWS 骨架 (半天)

| commit | 内容 | 验证 |
|---|---|---|
| `feat(backend): 下载 sherpa-onnx KWS 模型脚本` | 写 `scripts/download-kws-deps.sh`,下 `sherpa-onnx-kws-zh-wenet` (~50MB) 到 `models/kws/` | `ls models/kws/` 看到 encoder.onnx + tokens.txt + keywords.txt |
| `feat(backend): KwsProps + application-kws.yml` | 新建 `KwsProps` record + 独立配置文件 | `mvn compile` 通过 |
| `feat(backend): KwsService 初版` | 包装 sherpa-onnx KeywordSpotter,实现 init/startSession/acceptFrame/stopSession | `mvn test` 通过 |
| `feat(backend): KwsWebSocketHandler + KwsWebSocketConfig` | 独立 Handler + 端点注册 | `mvn compile` + 手动 WS 客户端测试 |

### Phase 2: 前端 KWS 监听 (半天)

| commit | 内容 | 验证 |
|---|---|---|
| `feat(frontend): kwsClient.ts + kwsMonitor.ts` | 独立 WS 客户端 + 持续录音组件 | 浏览器 devtools 看 audio.chunk 在发 |
| `feat(frontend): KwsPage.vue` | 独立页面,唤醒后自动录音 + TTS 播放 | 喊 "嗨小爱" -> mic 自动开始 |
| `feat(frontend): 路由 /kws` | 加路由入口 | `localhost:5174/kws` 打开 |

### Phase 3: 端到端测试 (半天)

| commit | 内容 | 验证 |
|---|---|---|
| `test(backend): KwsServiceTest` | 喂预录的 "嗨小爱" PCM,断言检测到 | `mvn test` |
| `test(backend): KwsWebSocketHandlerIT` | mock WS session,验证 wake.detected 推送 | `mvn test` |
| `test(e2e): KWS 唤醒到 TTS 全链路` | (手动跑) | 喊 -> mic 亮 -> 说话 -> cto 回复 -> TTS -> 回监听 |

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
        byte[] pcm = Files.readAllBytes(
            Paths.get("src/test/resources/audio/wake-hi-xiaoai.pcm"));
        kws.startSession("test");
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

### 11.2 手动端到端测试清单

- [ ] 打开 `localhost:5174/kws` 页面,DevTools Network 看 WS 连 `/ws/kws`
- [ ] 页面 mic 权限一次性授予
- [ ] 后端日志看到 KWS 模型加载
- [ ] 后端日志看到 `audio.kws.start` 处理 + `kws.ack` 返回
- [ ] 喊 "嗨小爱" -> 后端日志 `🔥 Wake detected`
- [ ] 浏览器收到 wake.detected -> 状态变 "recording"
- [ ] 喊 "今天天气怎样" -> cto 文字 + TTS 回复
- [ ] TTS 播完后自动回 KWS 监听
- [ ] 现有 `localhost:5174/` (v2 页面) 功能不受影响
- [ ] 关 KWS 页面 -> 后端日志 KWS session 清理

### 11.3 回归验证 (确保不动现有代码)

- [ ] `localhost:5174/` (v2 首页) 点 mic 说话 -> STT + TTS 正常
- [ ] `/ws/audio` 端点不受 `/ws/kws` 影响
- [ ] 后端日志无 KWS 相关错误 (kws.enabled=false 时不加载模型)

---

## 十二、风险与权衡

### 12.1 已知风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| sherpa-onnx KWS 预训练词不支持 "嗨 CTO" | 唤醒词只能是 "嗨小爱" 等 | 用占位词跑通;后续自训练 (Milestone 2) |
| 浏览器页面没开 -> 唤醒不工作 | 桌面机器人场景需常驻标签页 | 短期妥协;长期 PWA 或独立 app |
| 麦克风权限未授予 | 完全失效 | UI 启动时强提示授权 |
| 安静环境误触发 (电视/音乐/对话) | 体验差 | 提高 threshold 到 0.7+ |
| 后端 KWS 单线程,多浏览器并发会排队 | 多人同时用会卡 | 单 session 够用;并发需求加池 |
| 持续音频上传带宽 (32 KB/s) | 跨网时心疼流量 | 内网穿透下无压力;远程场景再优化 |
| 两个 WS 端点同时连 (用户同时开 / 和 /kws) | 两路音频流 | 前端做互斥 (KwsPage 和 App 不同时打开) |

### 12.2 设计权衡

1. **独立端点 vs 改现有 Handler** -> 选独立。理由:老板要求不动现有代码;代价是多一个 WS 连接 (成本可忽略) + KwsWebSocketHandler 有一部分逻辑跟 VoiceWebSocketHandler 重复 (audio.start/end + STT + TTS),约 60 行。**值得**。

2. **后端 KWS vs 前端 WASM KWS** -> 选后端。理由:复用 sherpa-onnx Java binding;浏览器 0 CPU;代价是 32 KB/s 上行流量。

3. **持续监听 vs 点按钮启 KWS** -> 选持续监听。理由:体验更接近小爱同学;代价是浏览器必须保持页面打开。

4. **预训练词 vs 自训练** -> 选预训练先用。理由:5 min 跑通;自训练延后到 Milestone 2。

5. **独立配置文件 vs 改 application.yml** -> 选独立 `application-kws.yml` profile。理由:不碰现有配置;代价是启动时要加 `--spring.profiles.active=kws`。

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

### Milestone 4: 多客户端并发 (3 天)
- KwsService 改成池 (per-thread KeywordSpotter)
- session load balancer

### Milestone 5: 嵌入式 KWS (1 周)
- ESP32-S3 跑 KWS (TFLite Micro)
- WiFi 通知 Mac mini
- 桌面机器人 + 嵌入式 KWS 整合

---

## 十四、附录

### 14.1 文件改动清单

**新增 (后端)**:
- `src/main/java/com/openclaw/voicenode/config/KwsProps.java`
- `src/main/java/com/openclaw/voicenode/service/KwsService.java`
- `src/main/java/com/openclaw/voicenode/api/KwsWebSocketHandler.java`
- `src/main/java/com/openclaw/voicenode/api/KwsWebSocketConfig.java`
- `src/main/resources/application-kws.yml`
- `scripts/download-kws-deps.sh`
- `models/kws/encoder.onnx` (+ tokens.txt, keywords.txt)
- `src/test/java/.../service/KwsServiceTest.java`
- `src/test/java/.../api/KwsWebSocketHandlerIT.java`
- `src/test/resources/audio/wake-hi-xiaoai.pcm`

**新增 (前端)**:
- `frontend/src/api/kwsClient.ts`
- `frontend/src/audio/kwsMonitor.ts`
- `frontend/src/KwsPage.vue`

**修改 (现有文件)**:
- ~~无~~
- > 唯一可能需要动的是前端路由配置 (加 `/kws` 路由),但可以通过新建 `kws.html` Vite 入口完全避免。

### 14.2 现有文件不动清单 (核心承诺)

| 文件 | 状态 |
|---|---|
| `api/VoiceWebSocketHandler.java` | ✅ 不动 |
| `api/WebSocketConfig.java` | ✅ 不动 |
| `config/VoiceNodeProperties.java` | ✅ 不动 |
| `config/SttProps.java` | ✅ 不动 |
| `config/TtsProps.java` | ✅ 不动 |
| `service/SttService.java` | ✅ 不动 (只调 `recognize()`) |
| `service/TtsService.java` | ✅ 不动 (只调 `synthesize()`) |
| `service/ChatBridgeService.java` | ✅ 不动 (只调 `open()`) |
| `service/ChatSessionHandle.java` | ✅ 不动 (只调 `sendText()` / `addTurnEndListener()`) |
| `gateway/GatewayClient.java` | ✅ 不动 |
| `gateway/KeyManager.java` | ✅ 不动 |
| `VoiceNodeApplication.java` | ✅ 不动 |
| `pom.xml` | ✅ 不动 (sherpa-onnx 已有) |
| `application.yml` | ✅ 不动 |
| `frontend/src/App.vue` | ✅ 不动 |
| `frontend/src/audio/recorder.ts` | ✅ 不动 |
| `frontend/src/api/voiceClient.ts` | ✅ 不动 |
| `frontend/src/audio/pcm-worklet.js` | ✅ 不动 (KwsMonitor 复用) |
| `frontend/package.json` | ✅ 不动 |

### 14.3 参考文档

- sherpa-onnx KWS 文档: https://k2-fsa.github.io/sherpa/onnx/kws.html
- sherpa-onnx KWS Java API: https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/java-api.md
- 模型下载: https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models (搜 kws-zh-wenet)
- 现有 STT 集成参考: `src/main/java/com/openclaw/voicenode/service/SttService.java` (只读)
- 现有 WS Handler 参考: `src/main/java/com/openclaw/voicenode/api/VoiceWebSocketHandler.java` (只读)

### 14.4 FAQ

**Q: 为什么不直接改 VoiceWebSocketHandler?**
A: 老板要求完全独立。独立端点 + 独立 Handler 保证现有 v2 功能零风险。

**Q: KwsWebSocketHandler 跟 VoiceWebSocketHandler 有重复代码怎么办?**
A: ~60 行重复 (audio.start/end + STT + TTS)。可以后续抽公共基类,但本期不动。

**Q: 两个 WS 端点同时连会冲突吗?**
A: 不会。`/ws/audio` 和 `/ws/kws` 是独立端点,独立 session。但前端不应同时打开两个页面 (两路音频流)。

**Q: 为什么 KWS 用后端不用前端 WASM?**
A: 复用 sherpa-onnx Java binding + 浏览器 0 CPU。代价是 32 KB/s 上行。

**Q: 自训练唤醒词要多久?**
A: 2 天。但本期先用预训练词跑通,自训练放 Milestone 2。

**Q: KWS 阈值怎么调?**
A: `application-kws.yml` 的 `threshold` 字段 (0.1~0.9)。安静环境 0.6;嘈杂环境 0.7+。

---

> 拍板请求:老板看完后说 "干" 或 "改 X" 我就开工。