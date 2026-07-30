<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { VoiceClient } from './api/voiceClient'
import { AudioRecorder } from './audio/recorder'
import { KwsClient } from './api/kwsClient'
import { KwsMonitor } from './audio/kwsMonitor'

type Status = 'idle' | 'connecting' | 'ready' | 'thinking' | 'error'

type ChatMsg = {
  id: string
  role: 'user' | 'assistant'
  content: string
  streaming?: boolean
  ts: number
}

const status = ref<Status>('idle')
const sessionId = ref('')
const errorMsg = ref('')

const messages = ref<ChatMsg[]>([])
const inputText = ref('')
const chatContainer = ref<HTMLElement | null>(null)

let client: VoiceClient | null = null
const recorder = new AudioRecorder()
const isRecording = ref(false)
const isSpeaking = ref(false)

// v3 KWS 唤醒词集成 (后台运行,不需用户手动启)
const kwsClient = new KwsClient()
const kwsMonitor = new KwsMonitor()
const kwsListening = ref(false)
const kwsKeywords = ref<string[]>([])
let kwsAutoStopTimer: number | null = null  // KWS 唤醒后自动停录音的 10s 超时
let kwsAutoRecording = false  // 标记录音是否由 KWS 触发（用于控制是否要回 KWS 监听）

// ⚠️ Fix 1: mic录音和TTS播音乐合用同一个AudioContext会被状态污染（AudioContext.close后buffer还在引用里）。
//           现在用完全独立的两套：mic录音 = recorder.ts 自己 new 的 (sampleRate 16000),
//           播音 = playCtx(默认 sampleRate)，互不干扰。
let playCtx: AudioContext | null = null
let currentSource: AudioBufferSourceNode | null = null  // 当前播放的 TTS 音频 source
const micCooldown = ref(false)  // Fix 2: micUp 后冷却 1.5s,防 TTS 尾巴被 mic 抓

async function playAssistantAudio(base64Audio: string) {
  try {
    // base64 → ArrayBuffer
    const binary = atob(base64Audio)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)

    // ⚠️ Fix 1: 用独立的 playCtx,不复用 mic 用的 AudioContext
    if (!playCtx || playCtx.state === 'closed') {
      playCtx = new AudioContext()  // 默认 sampleRate (通常是 48kHz)
      console.log('[audio] playCtx created, sampleRate =', playCtx.sampleRate)
    }
    const buffer = await playCtx.decodeAudioData(bytes.buffer)

    // Fix 3: 如果上一段还在播,先停掉 (micDown 也会调,但这里优先)
    if (currentSource) {
      try { currentSource.stop() } catch {}
    }
    const source = playCtx.createBufferSource()
    source.buffer = buffer
    source.connect(playCtx.destination)
    currentSource = source  // 记住,micDown 时可以暂停

    isSpeaking.value = true
    source.start()
    source.onended = () => {
      isSpeaking.value = false
      if (currentSource === source) currentSource = null
      // v3: TTS 播完,如果上次录音是 KWS 触发的,重启 KWS 监听
      if (kwsAutoRecording) {
        kwsAutoRecording = false
        console.log('[kws] TTS 播完,重启 KWS 监听')
        startKwsListening()
      }
    }
    console.log('[audio] 播放 CTO 回复:', buffer.duration.toFixed(2), '秒')
  } catch (e: any) {
    isSpeaking.value = false
    console.error('[audio] 播放失败:', e)
  }
}

function setStatus(s: Status, msg?: string) {
  status.value = s
  if (msg) errorMsg.value = msg
  else if (s !== 'error') errorMsg.value = ''
}

const statusText = computed(() => ({
  idle: '未连接',
  connecting: '连接中…',
  ready: '就绪',
  thinking: 'CTO 思考中…',
  error: '错误',
}[status.value]))

const canSend = computed(() => status.value === 'ready' && inputText.value.trim().length > 0)

function genId(): string {
  return Math.random().toString(36).slice(2, 10) + '-' + Date.now().toString(36)
}

function scrollToBottom() {
  nextTick(() => {
    if (chatContainer.value) {
      chatContainer.value.scrollTop = chatContainer.value.scrollHeight
    }
  })
}

function sendText() {
  const text = inputText.value.trim()
  if (!text || !client || status.value !== 'ready') return

  // 1. 推用户消息
  messages.value.push({
    id: genId(),
    role: 'user',
    content: text,
    ts: Date.now(),
  })

  // 2. 推占位 assistant 消息（streaming）
  messages.value.push({
    id: genId(),
    role: 'assistant',
    content: '',
    streaming: true,
    ts: Date.now(),
  })

  inputText.value = ''
  scrollToBottom()
  setStatus('thinking')

  // 3. 发到后端
  console.log('[chat] → Java:', text)
  client.sendText(text)
}

async function micDown() {
  // Fix 2: 冷却中不开 mic (给 TTS 播放尾巴留时间)
  if (micCooldown.value) {
    console.log('[mic] 冷却中,忽略')
    return
  }
  if (!client || status.value !== 'ready' || isRecording.value) return

  // Fix 3: 打开 mic 前先停掉当前的 TTS 播放(mic 别录到扬声器)
  if (currentSource) {
    try {
      currentSource.stop()
      console.log('[mic] 已停当前 TTS 播放,防 mic 录到扬声器')
    } catch {}
  }

  try {
    // 先 await start 成功,再标 isRecording=true
    // 避免 start 还没完成(等 getUserMedia 中)就被 pointerleave 触发的 micUp stop 掉,
    // 导致 audioContext 为 null 的报警循环
    await recorder.start(client)
    isRecording.value = true
  } catch (e: any) {
    isRecording.value = false
    console.error('[mic] start failed:', e)
    alert('麦克风权限被拒绝或不可用：' + (e?.message || e))
  }
}

function micUp() {
  if (!isRecording.value) return
  isRecording.value = false
  recorder.stop()

  // Fix 2: micUp 后冷却 1.5s — TTS 还没播完时会闪现重复 capture
  micCooldown.value = true
  setTimeout(() => {
    micCooldown.value = false
    console.log('[mic] 冷却结束,接受下次 mic 输入')
  }, 1500)
}

function clearChat() {
  if (messages.value.length === 0) return
  if (!confirm('清空聊天记录？')) return
  messages.value = []
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendText()
  }
}

// ============== WS 协议 ==============
function setupClientHandlers(c: VoiceClient) {
  // 🔧 handler 只注册一次 — 之前 connect() 每次都 client = new VoiceClient() + 注册 9 个 handler,
  // 点"重连"按钮调多次会生成多个 VoiceClient 实例 + 累积 handler → assistant.audio 播多次。
  c.on('open', () => console.log('[ws] open'))
  c.on('ready', (msg: any) => {
    setStatus('ready')
    sessionId.value = msg.sessionKey || ''
    console.log('[ws] ready:', msg)
  })

  c.on('assistant', (msg: any) => {
    const delta = msg.text || ''
    if (!delta) return

    // 找到最后一条 streaming 的 assistant 消息,追加内容
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const m = messages.value[i]
      if (m.role === 'assistant' && m.streaming) {
        m.content += delta
        scrollToBottom()
        return
      }
    }
    // 没找到 streaming assistant → 创建一条
    messages.value.push({
      id: genId(),
      role: 'assistant',
      content: delta,
      streaming: true,
      ts: Date.now(),
    })
    scrollToBottom()
  })

  c.on('turn.done', () => {
    console.log('[ws] turn.done')
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const m = messages.value[i]
      if (m.role === 'assistant' && m.streaming) {
        m.streaming = false
        break
      }
    }
    setStatus('ready')
  })

  c.on('error', (msg: any) => {
    console.error('[ws] error:', msg)
    setStatus('error', String(msg?.message || msg))
  })

  // STT 识别结果 (来自 Java 后端 audio.end 后)
  c.on('user.text', (msg: any) => {
    const text = msg.text || ''
    if (!text || !msg.isFinal) return
    console.log('[ws] user.text:', text)
    // 推到聊天界面作为 user 消息(供用户看到自己说的什么)
    messages.value.push({
      id: genId(),
      role: 'user',
      content: text,
      ts: Date.now(),
    })
    scrollToBottom()
  })

  // 后端确认录音开始
  c.on('audio.ack', (msg: any) => {
    console.log('[ws] audio.ack:', msg)
  })

  // M2: 收到后端 TTS 合成的音频 → AudioContext 播放
  c.on('assistant.audio', (msg: any) => {
    const audio = msg.audio
    if (!audio) return
    console.log('[ws] assistant.audio 收到', (audio.length / 4 * 3 / 1024).toFixed(1), 'KB MP3')
    playAssistantAudio(audio)
  })

  c.on('close', (code: number, reason: string) => {
    console.log('[ws] closed', code, reason)
    if (status.value !== 'error') setStatus('idle')
  })
}

function connect() {
  setStatus('connecting')
  if (!client) {
    // 第一次: 建 client + 注册 handler (只一次)
    client = new VoiceClient()
    setupClientHandlers(client)
  }
  // 之后 (点"重连"按钮): client 已存在,只重建 WebSocket,handler 不重复注册
  client.connect()
}

// ====== v3 KWS 唤醒词逻辑 ======

function clearKwsAutoStop() {
  if (kwsAutoStopTimer !== null) {
    clearTimeout(kwsAutoStopTimer)
    kwsAutoStopTimer = null
  }
}

async function startKwsListening() {
  // 互斥: KWS 监听不能跟手动录音同时
  if (isRecording.value) return
  if (kwsListening.value) return
  try {
    await kwsMonitor.start(kwsClient)
    kwsListening.value = true
    console.log('[kws] 🎧 监听启动')
  } catch (e: any) {
    kwsListening.value = false
    console.error('[kws] 启动失败:', e?.message || e)
    alert('KWS 启动失败:' + (e?.message || e) + '\n请检查麦克风权限')
  }
}

function stopKwsListening() {
  if (!kwsListening.value) return
  kwsMonitor.stop()
  kwsListening.value = false
  console.log('[kws] 🎧 监听停止')
}

function setupKwsHandlers() {
  kwsClient.on('open', () => console.log('[kws] WS connected'))

  kwsClient.on('close', () => {
    console.log('[kws] WS closed')
    kwsListening.value = false
  })

  kwsClient.on('error', (ev: Event) => console.warn('[kws] WS error', ev))

  kwsClient.on('kws.ack', (msg: any) => {
    kwsKeywords.value = msg.keywords || []
    console.log('[kws] 关键词:', kwsKeywords.value)
  })

  kwsClient.on('wake.detected', async (msg: any) => {
    console.log('[kws] 🔥 唤醒词检测到:', msg.keyword)
    // 后端 KwsService 已自动停止监听 + 发送 wake.detected
    kwsListening.value = false
    // 互斥: 不在用户手动录音中时才接管
    if (isRecording.value) {
      console.log('[kws] 用户正在手动录音,忽略本次唤醒')
      return
    }
    kwsAutoRecording = true
    try {
      if (recorder && client) {
        // 停 TTS (防 KWS 录音录到扬声器)
        if (currentSource) {
          try { currentSource.stop() } catch {}
        }
        await recorder.start(client)
        isRecording.value = true
        // 10s 自动停超时
        clearKwsAutoStop()
        kwsAutoStopTimer = window.setTimeout(() => {
          if (kwsAutoRecording && isRecording.value) {
            console.log('[kws] 10s 超时,自动停录音')
            micUp()  // 复用 micUp 流程 (录音停止 + 冷却)
            // micUp 会清 isRecording 和设 micCooldown,但不会重启 KWS
            // KWS 重启由 turn.done 后的 playAssistantAudio.onended 触发
          }
        }, 10000)
      }
    } catch (e: any) {
      console.error('[kws] 自动录音失败:', e?.message || e)
      kwsAutoRecording = false
    }
  })
}

onMounted(() => {
  connect()
  // v3: 页面打开即启 KWS 唤醒监听 (后台,不干扰现有 chat 流程)
  setupKwsHandlers()
  kwsClient.connect()
  kwsClient.on('open', () => {
    startKwsListening()
  })
})

onBeforeUnmount(() => {
  if (isRecording.value) recorder.cancel()
  clearKwsAutoStop()
  stopKwsListening()
  kwsClient.close()
  client?.close()
})
</script>

<template>
  <div class="app">
    <header>
      <h1>OpenClaw Voice Node · 文字聊天</h1>
      <div class="header-right">
        <span v-if="kwsListening" class="status kws-listening" title="KWS 唤醒词监听中">🎧 唤醒监听中</span>
        <span class="status" :class="status">{{ statusText }}</span>
        <span v-if="isSpeaking" class="status speaking">🔊 说话中…</span>
        <button v-if="status === 'idle' || status === 'error'" class="link" @click="connect">
          {{ status === 'error' ? '重连' : '连接' }}
        </button>
        <button v-if="messages.length > 0" class="link" @click="clearChat">清空</button>
      </div>
    </header>

    <section v-if="errorMsg" class="error">{{ errorMsg }}</section>

    <section v-if="sessionId" class="meta">
      session: <code>{{ sessionId.slice(0, 50) }}…</code>
    </section>

    <section ref="chatContainer" class="chat">
      <div v-if="messages.length === 0" class="empty">
        <div class="empty-icon">💬</div>
        <div class="empty-title">开始跟 CTO 聊天</div>
        <div class="empty-hint">输入消息,按 Enter 发送</div>
      </div>

      <div
        v-for="msg in messages"
        :key="msg.id"
        :class="['msg', msg.role, msg.streaming ? 'streaming' : '']"
      >
        <div class="bubble">
          <span v-if="msg.streaming && !msg.content" class="thinking-dots">
            <span></span><span></span><span></span>
          </span>
          <template v-else>{{ msg.content }}</template>
          <span v-if="msg.streaming && msg.content" class="cursor">▍</span>
        </div>
      </div>
    </section>

    <section class="composer">
      <textarea
        v-model="inputText"
        @keydown="onKeydown"
        placeholder="输入消息… (Enter 发送, Shift+Enter 换行)"
        rows="2"
        :disabled="status !== 'ready' || isRecording"
        autofocus
      />
      <button
        class="mic-btn"
        :class="{ recording: isRecording }"
        :disabled="status !== 'ready'"
        @pointerdown.prevent="micDown"
        @pointerup.prevent="micUp"
        @click.prevent
        :title="isRecording ? '松开发送' : '按住说话'"
      >
        {{ isRecording ? '🎙️ 松开发送' : '🎙️' }}
      </button>
      <button
        class="send-btn"
        :disabled="!canSend"
        @click="sendText"
      >
        发送
      </button>
    </section>

    <footer>
      <small>Vue + Spring Boot + OpenClaw · 文字 chat 代理 · gateway cto agent</small>
    </footer>
  </div>
</template>

<style scoped>
.app {
  max-width: 800px;
  margin: 0 auto;
  padding: 24px 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 100vh;
  box-sizing: border-box;
}

header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid var(--border);
  padding-bottom: 14px;
}
header h1 { margin: 0; font-size: 18px; font-weight: 600; }

.header-right { display: flex; gap: 12px; align-items: center; }

.status {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--panel);
  color: var(--ink-dim);
}
.status.ready { background: var(--green); color: #0a2a14; }
.status.thinking { background: var(--accent); color: white; }
.status.error { background: var(--red); color: white; }
.status.kws-listening {
  background: rgba(52, 199, 89, 0.15);
  color: var(--green, #34c759);
  animation: kws-pulse 2s ease-in-out infinite;
}
@keyframes kws-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}
.status.connecting { background: var(--accent); color: white; opacity: 0.6; }
.status.idle { background: var(--panel); color: var(--ink-dim); }

.link {
  background: none;
  border: 1px solid var(--border);
  color: var(--ink-dim);
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
}
.link:hover { color: var(--ink); border-color: var(--ink-dim); }

.meta { font-size: 11px; color: var(--ink-dim); }
.meta code { background: var(--panel); padding: 2px 6px; border-radius: 4px; }

.error {
  background: rgba(224, 100, 100, 0.12);
  border: 1px solid var(--red);
  color: var(--red);
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13px;
}

.chat {
  flex: 1;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
  min-height: 360px;
  max-height: calc(100vh - 280px);
}

.empty {
  margin: auto;
  text-align: center;
  color: var(--ink-dim);
  padding: 40px 20px;
}
.empty-icon { font-size: 36px; margin-bottom: 12px; }
.empty-title { font-size: 16px; color: var(--ink); margin-bottom: 6px; }
.empty-hint { font-size: 12px; }

.msg { display: flex; }
.msg.user { justify-content: flex-end; }
.msg.assistant { justify-content: flex-start; }

.bubble {
  max-width: 75%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.55;
  word-wrap: break-word;
  white-space: pre-wrap;
}

.msg.user .bubble {
  background: var(--accent);
  color: white;
  border-bottom-right-radius: 4px;
}

.msg.assistant .bubble {
  background: var(--bg);
  color: var(--ink);
  border: 1px solid var(--border);
  border-bottom-left-radius: 4px;
}

.cursor {
  display: inline-block;
  margin-left: 2px;
  animation: blink 1s steps(2) infinite;
}
@keyframes blink { 50% { opacity: 0; } }

.thinking-dots {
  display: inline-flex;
  gap: 4px;
  padding: 4px 0;
}
.thinking-dots span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ink-dim);
  animation: dot-pulse 1.2s ease-in-out infinite;
}
.thinking-dots span:nth-child(2) { animation-delay: 0.2s; }
.thinking-dots span:nth-child(3) { animation-delay: 0.4s; }
@keyframes dot-pulse {
  0%, 60%, 100% { transform: scale(0.7); opacity: 0.4; }
  30% { transform: scale(1); opacity: 1; }
}

.composer {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.composer textarea {
  flex: 1;
  resize: none;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg);
  color: var(--ink);
  font-family: inherit;
  font-size: 14px;
  line-height: 1.5;
  outline: none;
  transition: border-color 0.15s;
}
.composer textarea:focus { border-color: var(--accent); }
.composer textarea:disabled { opacity: 0.5; cursor: not-allowed; }

.send-btn {
  padding: 10px 22px;
  background: var(--accent);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  align-self: stretch;
  transition: opacity 0.15s;
}
.send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.send-btn:not(:disabled):hover { opacity: 0.85; }

.speaking {
  background: #5b8def !important;
  color: white !important;
  animation: speaking-pulse 1.2s ease-in-out infinite;
}
@keyframes speaking-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.mic-btn {
  padding: 0 16px;
  background: var(--panel);
  color: var(--ink);
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 18px;
  cursor: pointer;
  transition: all 0.15s;
  align-self: stretch;
  user-select: none;
  -webkit-user-select: none;
  touch-action: none;
  min-width: 56px;
}
.mic-btn:hover:not(:disabled) { border-color: var(--ink-dim); }
.mic-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.mic-btn.recording {
  background: var(--red);
  color: white;
  border-color: var(--red);
  animation: mic-pulse 1s ease-in-out infinite;
}
@keyframes mic-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

footer { text-align: center; color: var(--ink-dim); margin-top: auto; padding-top: 8px; font-size: 11px; }
</style>