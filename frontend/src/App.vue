<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { VoiceClient } from './api/voiceClient'

type Status = 'idle' | 'connecting' | 'ready' | 'listening' | 'thinking' | 'speaking' | 'error'

const status = ref<Status>('idle')
const userTranscript = ref('')
const assistantTranscript = ref('')
const sessionId = ref('')
const errorMsg = ref('')

let client: VoiceClient
// eslint-disable-next-line @typescript-eslint/no-explicit-any
let recognition: any = null

function setStatus(s: Status, msg?: string) {
  status.value = s
  if (msg) errorMsg.value = msg
  else if (s !== 'error') errorMsg.value = ''
}

const statusText = computed(() => ({
  idle: '未连接',
  connecting: '连接中…',
  ready: '就绪',
  listening: '听你说…',
  thinking: '思考中…',
  speaking: '播放中…',
  error: '错误',
}[status.value]))

// ============== 浏览器原生 TTS ==============
let zhVoice: SpeechSynthesisVoice | null = null

function loadVoices() {
  if (!('speechSynthesis' in window)) return
  const voices = window.speechSynthesis.getVoices()
  // 优先选中文声音（macOS 自带 Tingting / Sin-ji / Mei-Jia 等）
  zhVoice = voices.find(v => v.lang === 'zh-CN' || v.lang === 'cmn-Hans-CN')
    || voices.find(v => v.lang?.startsWith('zh'))
    || voices.find(v => v.lang?.startsWith('cmn'))
    || null
  if (zhVoice) console.log('[tts] 使用声音:', zhVoice.name, zhVoice.lang)
}

function speak(text: string) {
  if (!('speechSynthesis' in window) || !text.trim()) return
  // 停掉之前的
  window.speechSynthesis.cancel()
  const u = new SpeechSynthesisUtterance(text)
  u.lang = 'zh-CN'
  if (zhVoice) u.voice = zhVoice
  u.rate = 1.0
  u.pitch = 1.0
  u.volume = 1.0
  u.onstart = () => {
    console.log('[tts] start')
    setStatus('speaking')
  }
  u.onend = () => {
    console.log('[tts] end')
    if (status.value === 'speaking') setStatus('ready')
  }
  u.onerror = (e) => {
    console.warn('[tts] error:', e)
    if (status.value === 'speaking') setStatus('ready')
  }
  window.speechSynthesis.speak(u)
}

// ============== 浏览器原生 STT ==============
function setupRecognition() {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const SR = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
  if (!SR) {
    setStatus('error', '浏览器不支持 Web Speech API（用 Chrome）')
    return null
  }

  const r = new SR()
  r.continuous = false
  r.interimResults = true
  r.lang = 'zh-CN'
  r.maxAlternatives = 1

  r.onstart = () => console.log('[stt] started')

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  r.onresult = (event: any) => {
    let interim = ''
    let final = ''
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const res = event.results[i]
      if (res.isFinal) {
        final += res[0].transcript
      } else {
        interim += res[0].transcript
      }
    }
    if (interim) {
      userTranscript.value = interim
      console.log('[stt] interim:', interim)
    }
    if (final) {
      userTranscript.value = final
      console.log('[stt] final:', final, '→ send to Java')
      client?.sendText(final)
      setStatus('thinking')
    }
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  r.onerror = (event: any) => {
    console.error('[stt] error:', event.error, event.message)
    if (event.error === 'no-speech' || event.error === 'aborted') {
      if (status.value === 'listening') setStatus('ready')
      return
    }
    setStatus('error', '识别错误: ' + event.error)
  }

  r.onend = () => {
    console.log('[stt] ended')
    if (status.value === 'listening') setStatus('ready')
  }

  return r
}

// ============== WS 协议 ==============
function connect() {
  setStatus('connecting')
  client = new VoiceClient()

  client.on('open', () => console.log('[ws] open'))
  client.on('ready', (msg) => {
    setStatus('ready')
    sessionId.value = msg.sessionKey || ''
    console.log('[ws] ready:', msg)
  })

  client.on('assistant', (msg) => {
    assistantTranscript.value = (assistantTranscript.value || '') + (msg.text || '')
    if (status.value === 'ready') setStatus('thinking')
  })

  client.on('turn.done', () => {
    console.log('[ws] turn.done, assistant text length:', assistantTranscript.value.length)
    // 浏览器自己用 speechSynthesis 念
    if (assistantTranscript.value.trim()) {
      speak(assistantTranscript.value)
    } else {
      setStatus('ready')
    }
  })

  client.on('error', (msg) => {
    console.error('[ws] error:', msg)
    setStatus('error', String(msg))
  })

  client.on('close', (code, reason) => {
    console.log('[ws] closed', code, reason)
    if (status.value !== 'error') setStatus('idle')
  })

  client.connect()
}

async function onPttDown() {
  if (status.value !== 'ready') {
    console.warn('Not ready, status=', status.value)
    return
  }
  if (!recognition) {
    setStatus('error', '未初始化 STT')
    return
  }
  userTranscript.value = ''
  assistantTranscript.value = ''

  try {
    recognition.start()
    setStatus('listening')
  } catch (e) {
    console.error('[stt] start failed:', e)
    setStatus('error', '无法启动识别: ' + e)
  }
}

function onPttUp() {
  if (recognition && status.value === 'listening') {
    try {
      recognition.stop()
    } catch (e) {
      console.warn('[stt] stop error:', e)
    }
  }
}

onMounted(() => {
  // 加载声音列表（macOS 异步）
  if ('speechSynthesis' in window) {
    loadVoices()
    window.speechSynthesis.onvoiceschanged = loadVoices
  }
  recognition = setupRecognition()
  if (!recognition) return
  connect()
})

onBeforeUnmount(() => {
  if (recognition) {
    try { recognition.abort() } catch { /* ignore */ }
  }
  if ('speechSynthesis' in window) {
    try { window.speechSynthesis.cancel() } catch { /* ignore */ }
  }
  client?.close()
})
</script>

<template>
  <div class="app">
    <header>
      <h1>OpenClaw Voice Node</h1>
      <span class="status" :class="status">{{ statusText }}</span>
    </header>

    <section v-if="errorMsg" class="error">{{ errorMsg }}</section>

    <section v-if="sessionId" class="meta">
      session: <code>{{ sessionId.slice(0, 40) }}…</code>
    </section>

    <section class="controls">
      <button
        v-if="status === 'ready' || status === 'listening' || status === 'speaking' || status === 'thinking'"
        :class="status === 'listening' ? 'hot' : 'primary'"
        @pointerdown="onPttDown"
        @pointerup="onPttUp"
        @pointerleave="onPttUp"
        @pointercancel="onPttUp"
      >
        {{ status === 'listening' ? '🎙️ 正在听…松开结束' : '按住说话' }}
      </button>
      <button v-else-if="status === 'connecting'" disabled>连接中…</button>
      <button v-else @click="connect">重连</button>
    </section>

    <section class="transcript">
      <div class="line user">
        <span class="label">你说：</span>
        <span>{{ userTranscript || '（空）' }}</span>
      </div>
      <div class="line assistant">
        <span class="label">CTO：</span>
        <span>{{ assistantTranscript || '（等待回复…）' }}</span>
      </div>
    </section>

    <footer>
      <small>Vue + Spring Boot + OpenClaw · 浏览器 STT/TTS · 纯文本 chat 代理</small>
    </footer>
  </div>
</template>

<style scoped>
.app {
  max-width: 720px;
  margin: 0 auto;
  padding: 32px 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-height: 100vh;
}

header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid var(--border);
  padding-bottom: 16px;
}
header h1 { margin: 0; font-size: 20px; }

.status {
  font-size: 13px;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--panel);
  color: var(--ink-dim);
}
.status.ready { background: var(--green); color: #0a2a14; }
.status.listening { background: var(--accent); color: white; }
.status.thinking { background: var(--accent); color: white; opacity: 0.7; }
.status.speaking { background: var(--accent-hot); color: #2a1a00; }
.status.error { background: var(--red); color: white; }
.status.connecting { background: var(--accent); color: white; opacity: 0.6; }
.status.idle { background: var(--panel); color: var(--ink-dim); }

.meta { font-size: 12px; color: var(--ink-dim); }
.meta code { background: var(--panel); padding: 2px 6px; border-radius: 4px; }

.error {
  background: rgba(224, 100, 100, 0.12);
  border: 1px solid var(--red);
  color: var(--red);
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13px;
}

.controls {
  display: flex;
  justify-content: center;
  padding: 24px 0;
}
.controls button {
  font-size: 16px;
  padding: 16px 36px;
  min-width: 200px;
  user-select: none;
  -webkit-user-select: none;
  touch-action: none;
}

.transcript {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 200px;
}
.line { font-size: 14px; line-height: 1.6; }
.label {
  display: inline-block;
  min-width: 56px;
  color: var(--ink-dim);
  font-size: 12px;
  margin-right: 8px;
}
.line.user { color: var(--ink); }
.line.assistant { color: var(--green); }

footer { text-align: center; color: var(--ink-dim); margin-top: auto; }
</style>
