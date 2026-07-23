<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { VoiceClient } from './api/voiceClient'

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
function connect() {
  setStatus('connecting')
  client = new VoiceClient()

  client.on('open', () => console.log('[ws] open'))
  client.on('ready', (msg: any) => {
    setStatus('ready')
    sessionId.value = msg.sessionKey || ''
    console.log('[ws] ready:', msg)
  })

  client.on('assistant', (msg: any) => {
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

  client.on('turn.done', () => {
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

  client.on('error', (msg: any) => {
    console.error('[ws] error:', msg)
    setStatus('error', String(msg?.message || msg))
  })

  client.on('close', (code: number, reason: string) => {
    console.log('[ws] closed', code, reason)
    if (status.value !== 'error') setStatus('idle')
  })

  client.connect()
}

onMounted(() => {
  connect()
})

onBeforeUnmount(() => {
  client?.close()
})
</script>

<template>
  <div class="app">
    <header>
      <h1>OpenClaw Voice Node · 文字聊天</h1>
      <div class="header-right">
        <span class="status" :class="status">{{ statusText }}</span>
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
        :disabled="status !== 'ready'"
        autofocus
      />
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

footer { text-align: center; color: var(--ink-dim); margin-top: auto; padding-top: 8px; font-size: 11px; }
</style>