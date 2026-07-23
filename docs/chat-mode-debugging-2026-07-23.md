# Chat Mode 调试复盘 — 2026-07-23

> 状态: **事故复盘 + 经验沉淀**
> 创建: 2026-07-23 10:48 by 普罗米修斯
> 背景: 把 chat-proxy 模式（commit `a0ba17e` / `41bbdb6`）从"前端 STT/TTS"换成"前端纯文字聊天"的过程中，**连续撞了 4 个 gateway 协议相关的墙**。每个 wall 都不是改代码能修的，是协议约定跟我（agent）的臆测不一致。
> 教训: 我应该一开始就查 `/Volumes/ssd/openclaw` 源码 + 用 `understand-anything` 知识图谱，而不是凭 commit 历史瞎猜契约。

---

## 一、目标行为（最终态）

**前端** (`localhost:5174`):
- 聊天框输入 → Enter 发送 → 流式显示 CTO 回复 → 回完后状态变"就绪" → 可继续打字

**链路**:
```
浏览器 (text) → Java WS /ws/audio → chat.send → Gateway → cto agent (LLM)
                                                          ↓
                                                          thinking: "off" 关掉 reasoning
                                                          ↓
浏览器 ← assistant deltas ← turn.done ← Java ← agent events / chat events
```

---

## 二、4 个坑（按出现顺序）

### 坑 1 — `model` 参数被 schema 静默拒绝

**现象**：浏览器发"nihao"，后端 `📤 → Gateway chat.send: nihao` 打出来，但 agent 永远不动，没有任何回复事件。

**根因**：`ChatSendParamsSchema` 是 `additionalProperties: false`（`/Volumes/ssd/openclaw/packages/gateway-protocol/src/schema/logs-chat.ts:78`）。commit `41bbdb6` 加的 `params.put("model", "minimax/MiniMax-M3")` 不在白名单 → gateway 拒绝 → `sendFireAndForget` 不等响应 → 错误被**静默吞掉**。

**修复**：删 `params.put("model", ...)`。

**验证证据**：sessionKey `agent:cto:feishu:direct:...` 在 snapshot 里被刷新（说明 chat.send 至少被分发到 session），但 agent 完全没启动 → 只能是 chat.send 被 reject 了，不是 chat.send 成功但 agent 出错。

**教训**：chat.send schema 是**写死**的，**不能 override model**——要改模型只能改 agent 配置（`~/.openclaw/agents/cto/agent/models.json` 或 `~/.openclaw/openclaw.json`）。任何"force model"在 chat.send 路径上的 hack 都是死路。

---

### 坑 2 — cto agent 默认配 reasoning 模型，agent 无限思考不出文本

**现象**：去掉 `model` 参数后，agent lifecycle 能正常 `phase=start`，但只有 `stream=thinking` 事件持续流出，`stream=assistant` 一次都不出现。

**根因**：cto agent 默认配的是 `gpt-5.2` / `minimax-m2.5:cloud`（看 `~/.openclaw/agents/cto/agent/models.json`，两个都 `"reasoning": true`）。reasoning 模型会一直 thinking，thinking 用完上下文才出 answer——7 秒过去了还在想"用户说'你好'是打招呼还是测试 chat"。

**修复**：加 `params.put("thinking", "off")`。`thinking` 字段是 schema 白名单内的可选值（`/Volumes/ssd/openclaw/packages/gateway-protocol/src/schema/logs-chat.ts:84`，至少 `"off"` / `"xhigh"` 可用），透传到 LLM 层的 `enable_thinking=false`（`/Volumes/ssd/openclaw/src/llm/providers/openai-completions.ts:717`）。

**验证证据**：修复后 `event #6: name=agent, stream=assistant, data={text=你好, delta=你好}` 开始出现——CTO 真的回话了。

**教训**：**所有"agent 启动了但一直不出文本"的问题，第一时间查 agent 的模型配置 `~/.openclaw/agents/<agent>/agent/models.json`，看有没有 `"reasoning": true` 的模型**。如果有，chat.send 加 `thinking: "off"`，或者改 agent 默认模型。

---

### 坑 3 — `stream="response"` 老名字，gateway 已改 `stream="assistant"`

**现象**：agent 事件到了后端（log 里有 `event #6: name=agent, stream=assistant`），但前端浏览器**还是收不到回复**。

**根因**：Java handler 还在查老名字 `"response".equals(stream)`（`VoiceWebSocketHandler.java:94`），但 OpenClaw 2026.7.x 起改成了 `AgentEventStream` 枚举（`/Volumes/ssd/openclaw/src/infra/agent-events.ts`），流名是 `"lifecycle"` / `"thinking"` / `"assistant"` / `"tool"` / `"error"` 等。Java handler 的 `if` 分支永远不命中，事件被丢。

**修复**：
```java
if ("assistant".equals(stream) || "response".equals(stream)) {
    // 老 gateway 叫 "response"，新 gateway 改成了 "assistant"
    // 用 delta 不要 text，避免重复发累积
```

**验证证据**：修复后浏览器能收到 assistant 文本。

**教训**：**所有"gateway 事件流处理"逻辑，事件名 / 流名 / 字段名都要跟当前 OpenClaw 的 schema 对齐**，不要 commit 历史写了什么就用什么。**看 `hello-ok` snapshot 里的 `events=[...]` 字段**就能看到当前的真实事件名列表。

---

### 坑 4 — `done` 信号换了位置 + 换了字段

**现象**：agent 回完话后，前端状态**永远卡在"CTO 思考中"**，`▍` 光标一直在闪，**输入框锁死**打不了字。

**根因**：Java handler 只认老 `stream=done/end/complete` 或 `kind=end`，但新 gateway 的 done 信号换了：

| 老 gateway | 新 gateway |
|---|---|
| `stream=done` | `stream=lifecycle, data.phase=end` (agent 事件) |
|  | `state=final` (chat 事件，用 state 不是 stream) |

Java handler 完全没认 → 永远不发 `{type: "turn.done"}` → 前端状态机卡在 thinking。

**修复**（`VoiceWebSocketHandler.java:121-143`）：
```java
// 老 done 信号（向后兼容）
} else if ("done".equals(stream) || "end".equals(stream) || "complete".equals(stream)
        || "end".equals(String.valueOf(payload.get("kind")))) {
    emitTurnDone(session);
// 新 gateway: agent lifecycle 结束
} else if ("lifecycle".equals(stream)) {
    Object data = payload.get("data");
    String phase = null;
    if (data instanceof Map<?, ?> dm) {
        Object p = dm.get("phase");
        if (p instanceof String s) phase = s;
    }
    if ("end".equals(phase) || "error".equals(phase) || "stop".equals(phase)) {
        emitTurnDone(session);
    }
// 新 gateway: chat 事件用 state 字段（不是 stream）
} else if ("chat".equals(eventName)) {
    Object state = payload.get("state");
    if ("final".equals(state) || "aborted".equals(state) || "error".equals(state)) {
        emitTurnDone(session);
    }
}
```

`emitTurnDone` helper（`VoiceWebSocketHandler.java:232`）做 3 件事：
- 重置 assistant buffer
- 打 log
- 推 `{type: "turn.done"}` 给浏览器

**验证证据**：修复后状态回"就绪"、光标消失、输入框解锁。

**教训**：**chat 事件用 `state` 字段（delta/final/aborted/error），agent 事件用 `stream` + `data.phase` 字段——两套体系不同**。看 `ChatEventSchema`（`/Volumes/ssd/openclaw/packages/gateway-protocol/src/schema/logs-chat.ts`）就能确认。

---

## 三、经验教训（最重要的部分）

### 教训 1: 调试 gateway 集成前**必查**这 4 件事

| # | 查什么 | 在哪查 | 怎么用 |
|---|---|---|---|
| 1 | **OpenClaw 源码** | `/Volumes/ssd/openclaw/` | 协议、字段名、流名直接看 `src/` 下的真实实现，不靠 commit 记忆 |
| 2 | **understand-anything 知识图谱** | 已有（用户 2026-06-29 构建过 OpenClaw KG） | 用 `understand-chat` skill 提问，比如"chat.send RPC 接受哪些参数"、"agent lifecycle 事件流" |
| 3 | **schema 文件** | `/Volumes/ssd/openclaw/packages/gateway-protocol/src/schema/` | 每个 RPC 的真实验证规则，必看 `additionalProperties` |
| 4 | **hello-ok snapshot** | 后端日志里 hello-ok 的 `events=[...]` 字段 | 当前 gateway 实际 emit 哪些事件一目了然 |

### 教训 2: 不要相信 commit 历史里的"工作代码"

commit `41bbdb6` 的"force `qwen-vision-model`"和"force `minimax/MiniMax-M3`"看起来在工作（log 里有输出），实际：
- `model` 字段被静默拒绝 → agent 永远不动
- 老名字 `stream="response"` 看着像在工作，实际是因为 agent 根本没启动、没事件流出、所以"分支永远进不去但也没事"

**commit 工作 ≠ 协议正确**。要把 hello-ok + 完整事件流 + 浏览器实际收到回复**三段都验证完**，才算"端到端通"。

### 教训 3: `sendFireAndForget` 的陷阱

`GatewayClient.sendFireAndForget`（`GatewayClient.java:170`）只发不等响应。**任何 `chat.send` 被 reject 的错误都被吞掉**——只能从后续事件流"没出来"反推"被拒绝了"。

调试时如果用了 fire-and-forget，**至少在关键路径上加临时 log 确认请求真的被接收了**。或者临时改成 `sendRequest`（同步）等 res 验证 ok=true。

### 教训 4: cto agent 的"配置孤岛"

cto agent 配置在 `~/.openclaw/agents/cto/agent/`，不在 OpenClaw 主仓库里。我之前完全没看这个目录，直接假设 cto 配的是某个合理模型——结果撞上 reasoning 模型墙。

**任何时候做 agent 行为相关的 debug，第一步先看 `~/.openclaw/agents/<agent>/agent/models.json` + `auth-profiles.json`**。

---

## 四、当前契约速查

### chat.send 参数（白名单）

```ts
// /Volumes/ssd/openclaw/packages/gateway-protocol/src/schema/logs-chat.ts:78
{
  sessionKey: ChatSendSessionKeyString,  // 必填
  message: Type.String(),                // 必填
  idempotencyKey: NonEmptyString,        // 必填
  // 可选：
  agentId / sessionId / thinking / fastMode / fastAutoOnSeconds / deliver /
  originatingChannel / originatingTo / originatingAccountId / originatingThreadId /
  attachments / timeoutMs / systemInputProvenance / systemProvenanceReceipt /
  suppressCommandInterpretation
}
{ additionalProperties: false }  ← 不能加任何 schema 外的字段！
```

### agent event stream 名

```ts
// /Volumes/ssd/openclaw/src/infra/agent-events.ts
type AgentEventStream =
  | "lifecycle"  // data: { phase: "start" | "update" | "end" | "error" | ... }
  | "tool"
  | "assistant"  // data: { text, delta }  ← 关键！老名字是 "response"
  | "thinking"   // data: { text, delta }  ← 当前不转发给前端
  | "error"
  | "item"
  | "plan"
  | "approval"
  | "command_output"
  | "patch"
  | "compaction";
```

### chat event（不是 agent event！）字段

```ts
// ChatEventSchema 在 logs-chat.ts
ChatDeltaEventSchema: { state: "delta",   deltaText, ... }
ChatFinalEventSchema: { state: "final",   ... }
ChatAbortedEventSchema: { state: "aborted", ... }
ChatErrorEventSchema:  { state: "error",   errorMessage, ... }
```

注意：chat event 用 **`state` 字段**，不是 `stream`。

---

## 五、commit 历史（修复轨迹）

```
47378e8  fix(frontend): vite config host:true (fix frpc 反代 IPv6 only bug)
8c705c9  docs: voice-node v2 STT/TTS 方案文档
41bbdb6  feat(bridge): 强制走 cto 飞书 session + chat.send 临时用 vision-model  ← 坑的源头
a0ba17e  feat: chat-mode bridge (browser STT + chat.send + speechSynthesis TTS)
a60a639  Initial commit: OpenClaw voice node (Vue 3 + Spring Boot 3)
```

下一步 commit（待写）应该：
- **拆掉 `41bbdb6` 的 `model` 强制**（已修，但 commit 留着误导后人，需要 revert 或 refactor commit）
- **加 chat-mode 调试发现**：3 个新 gateway 协议适配 + 详细注释指向 OpenClaw 真实 schema
- 关联本复盘文档

---

## 六、验证清单（再次出问题时跑一遍）

- [ ] 后端日志 10:44:40 之后有 `📤 → Gateway chat.send: <text>` ✅
- [ ] 紧接着有 `📨 event: name=agent, stream=lifecycle, data={phase=start}` ✅
- [ ] 后续有 `name=agent, stream=assistant, data={text=..., delta=...}` ✅
- [ ] 最终有 `name=agent, stream=lifecycle, data={phase=end}` **OR** `name=chat, state=final` ✅
- [ ] 浏览器里 CTO 回复流式出现
- [ ] 回完后状态回"就绪"绿色
- [ ] 输入框解锁、能继续打字

7 个全打勾 = 端到端通了。任何一项没勾，回到本复盘文档对应章节。

---

## 七、给未来接手的人

1. **不要相信旧 commit 里写的协议细节**——OpenClaw gateway 协议会演进，看 hello-ok snapshot + 当前 schema 文件
2. **任何 `model` / `provider` / `agentId` 之类的"魔法字段"硬编码，先 grep `/Volumes/ssd/openclaw/packages/gateway-protocol/src/schema/` 确认在白名单**
3. **agent 没回复** → 先看 `~/.openclaw/agents/<agent>/agent/models.json` 里有没有 `reasoning: true` 的模型
4. **前端看不到回复** → 后端日志里看 agent event 流名是什么，老名字不一定适用
5. **状态卡住** → 检查 done 信号，老 `stream=done` 不一定对

调试卡住超过 5 分钟就用 `understand-chat` skill 直接问 OpenClaw 知识图谱（已在 `~/.understand-anything/` 索引过）。