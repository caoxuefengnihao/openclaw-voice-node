# openclaw-voice-node

Web 端 OpenClaw Gateway 语音节点。前端 Vue 3 + Vite，后端 Spring Boot 3.3 + Java 21。

```
┌──────────────┐   WS /ws/audio      ┌─────────────────┐   WS            ┌──────────────────┐
│  Vue 浏览器  │ ──────────────────> │ Java 后端       │ ──────────────> │ OpenClaw Gateway │
│  AudioWorklet│ <────────────────── │ (持 talk session│ <────────────── │ 106.14.164.36    │
│  16kHz PCM   │  翻译后的事件 JSON  │  转发 + 鉴权)   │  talk.event 流  │ :60013           │
└──────────────┘                     └─────────────────┘                 └──────────────────┘
```

## 跑通流程

### 0. 前置条件

- **JDK 21+**（`java -version`）
- **Maven 3.9+**（`mvn -version`）
- **Node.js 20+**（`node -v`）
- 已配好的 OpenClaw Gateway（你 Mac mini 上的）
- Gateway token（从 `~/.openclaw/openclaw.json` 的 `gateway.auth.token` 读）

### 1. 配 Gateway token

```bash
export OPENCLAW_TOKEN="<从 ~/.openclaw/openclaw.json 读 gateway.auth.token>"
```

或直接编辑 `src/main/resources/application.yml` 把 `token:` 改成实际值（不推荐，token 会进 git）。

### 2. 启动 Java 后端

```bash
cd /Volumes/ssd/openclaw-voice-node
mvn spring-boot:run
```

第一次跑会：
1. 在 `~/.openclaw-voice-node/device.json` 生成 Ed25519 密钥（持久化）
2. 连 Gateway，触发 challenge → 签名 → connect

**第一次会卡在 connect**，因为设备需要配对。日志会打印：

```
⚠️  需要配对！在 Mac mini 上执行：
    openclaw devices list
   找到 id 包含 'xxxxxxxxxxxxxxxx' 的设备后：
    openclaw devices approve <request-id>
```

去 Mac mini 配对：

```bash
openclaw devices list
# 找到 deviceId 短前缀匹配的那行
openclaw devices approve <request-id>
```

Java 后端日志会出 `✅ connected to gateway`，deviceToken 会自动保存到 `~/.openclaw-voice-node/token.json`，下次连接自动跳过配对。

### 3. 启动 Vue 前端

另开一个终端：

```bash
cd /Volumes/ssd/openclaw-voice-node/frontend
npm install
npm run dev
```

打开 `http://localhost:5173`。

**注意**：AudioWorklet 要求 secure context（HTTPS 或 localhost）。`localhost` 是 OK 的，**远程 IP 不行**——开发时务必从 `http://localhost:5173` 访问，不要用 `http://<你的IP>:5173`。

### 4. 说话

点"按住说话"按钮，**对着麦克风说话**，松开后 Java 把 PCM 转发给 Gateway 的 talk session，识别文字会显示在"你说"那行，CTO 的回复会显示在"CTO"那行，并自动播放 TTS。

## 关键文件

### 后端
| 文件 | 作用 |
|---|---|
| `pom.xml` | Maven 配置（Spring Boot 3.3 + Java 21 + BouncyCastle + Java-WebSocket）|
| `application.yml` | Gateway URL/token/agent/session 配置 |
| `VoiceNodeApplication.java` | Spring Boot 入口 |
| `config/VoiceNodeProperties.java` | 强类型配置（openclaw.*）|
| `config/TalkProps.java` | 强类型配置（talk.*）|
| `gateway/KeyManager.java` | Ed25519 设备密钥管理（持久化到 `~/.openclaw-voice-node/`）|
| `gateway/GatewayClient.java` | WebSocket 客户端 → OpenClaw Gateway（1:1 对应 `custom_node.py`）|
| `api/WebSocketConfig.java` | Spring WebSocket 配置（注册 `/ws/audio`）|
| `api/VoiceWebSocketHandler.java` | 浏览器 ↔ Java ↔ Gateway 桥接 |
| `api/StatusController.java` | GET `/api/status` 查看设备/会话状态 |

### 前端
| 文件 | 作用 |
|---|---|
| `package.json` | Vue 3 + Vite + TypeScript |
| `vite.config.ts` | dev server + 代理 `/ws` `/api` 到 Java |
| `src/main.ts` | Vue 入口 |
| `src/App.vue` | 主 UI：PTT 按钮 + 转写 + TTS 播放 |
| `src/audio/pcm-worklet.ts` | AudioWorklet 处理器（Float32→Int16，**直接抄白龙马**）|
| `src/audio/capture.ts` | getUserMedia + AudioWorklet → Int16Array 块 |
| `src/audio/playback.ts` | TTS 帧 → Web Audio 顺序播放 |
| `src/api/voiceClient.ts` | 浏览器 ↔ Java 的 WebSocket 客户端 |

## 协议速查

### 浏览器 → Java

| 帧类型 | 含义 |
|---|---|
| Binary | PCM Int16 16kHz mono（每块 2048 样本 ≈ 128ms）|
| Text `{cmd:"startTurn"}` | 通知 gateway 开始一段语音 turn |
| Text `{cmd:"endTurn"}` | 通知 gateway 结束 turn，触发 LLM 推理 |
| Text `{cmd:"cancelTurn"}` | 取消当前 turn（打断场景）|
| Text `{cmd:"ping"}` | 心跳 |

### Java → 浏览器

| 事件 | 含义 |
|---|---|
| `{type:"ready", sessionId, ...}` | talk session 创建好，可以开始说话 |
| `{type:"transcript.delta", text}` | STT 中间识别文字 |
| `{type:"transcript.done", text}` | STT 最终识别文字 |
| `{type:"assistant", text, final?}` | LLM 回复的文本 |
| `{type:"audio", data:"<b64>"}` | TTS 音频帧（Int16 PCM 16kHz mono）|
| `{type:"turn.done"}` | 当前 turn 全部完成 |
| `{type:"error", message}` | 错误 |

## 后续优化（按需）

- **多路复用**：当前每个浏览器连接 = 一个 gateway 连接。生产环境改成 1 个 gateway 连接 + sessionId 多路复用
- **TTS 打断**：如果用了 continuous / stt-tts 默认模式，TTS 期间可以在浏览器检测用户声音调 `cmd: "cancelTurn"`
- **设备适配**：用 Web Bluetooth API 让浏览器选蓝牙耳机（仅 Chrome/Edge 支持）
- **视觉反馈**：加 AudioWorklet analyser 做音量条 / 点云球（白龙马 voice-orb.html 那套）
- **打包发布**：`mvn package` 打 jar，`java -jar` 跑；前端 `npm run build` 出 dist/，用 Spring 静态资源托管

## 调试技巧

- **后端日志**：`mvn spring-boot:run` 会打 `com.openclaw.voicenode: DEBUG`
- **前端日志**：浏览器 DevTools Console，会看到 `[ready]` `sessionId=...` 等
- **Gateway 侧**：`openclaw logs` 看 Gateway 收到的请求
- **手动测 Gateway**：
  ```bash
  curl http://localhost:8080/api/status
  # 返回 {gateway, agent, sessionKey, deviceId, devicePaired, talk}
  ```
