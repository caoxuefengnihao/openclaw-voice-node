<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { VoiceClient } from './api/voiceClient'
import { AudioCapture } from './audio/capture'
import { AudioPlayer } from './audio/playback'

type Status = 'idle' | 'connecting' | 'ready' | 'listening' | 'speaking' | 'error'

const status = ref<Status>('idle')
const statusText = computed(() => ({
  idle: '未连接',
  connecting: '连接中…',
  ready: '就绪',
  listening: '听你说…',
  speaking: 'CTO 在说…',
  error: '错误',
})[status.value])

const userTranscript = ref('')
const assistantTranscript = ref('')
const sessionId = ref('')
const errorMsg = ref('')

let client: VoiceClient
let capture: AudioCapture | null = null
let player: AudioPlayer

function setStatus(s: Status, msg?: string) {
  status.value = s
  if (msg) errorMsg.value = msg
  else if (s !== 'error') errorMsg.value = ''
}

function connect() {
  setStatus('connecting')
  client = new VoiceClient()

  client.on('open', () => {
    console.log('WS open')
  })

  client.on('ready', (msg) => {
    setStatus('ready')
    sessionId.value = msg.sessionId
    console.log('Talk session ready:', msg)
  })

  client.on('transcript.delta', (msg) => {
    userTranscript.value = msg.text
    setStatus('listening')
  })

  client.on('transcript.done', (msg) => {
    userTranscript.value = msg.text
  })

  client.on('assistant', (msg) => {
    assistantTranscript.value = msg.text
    setStatus('speaking')
  })

  client.on('audio', (msg) => {
    player.feed(msg.data)
  })

  client.on('turn.done', () => {
    setStatus('ready')
  })

  client.on('error', (msg) => {
    console.error('WS error', msg)
    setStatus('error', String(msg))
  })

  client.on('close', (code, reason) => {
    console.log('WS closed', code, reason)
    setStatus('idle')
  })

  client.connect()
}

async function onPttDown() {
  if (status.value !== 'ready') return
  userTranscript.value = ''
  assistantTranscript.value = ''
  player.flush()
  client.sendCommand({ cmd: 'startTurn' })
  capture = new AudioCapture((pcm) => {
    client.sendAudio(pcm)
  })
  try {
    await capture.start()
    setStatus('listening')
  } catch (e) {
    setStatus('error', '麦克风权限被拒: ' + String(e))
    capture = null
  }
}

function onPttUp() {
  if (capture) {
    capture.stop()
    capture = null
  }
  if (status.value === 'listening') {
    client.sendCommand({ cmd: 'endTurn' })
    setStatus('ready')
  }
}

onMounted(() => {
  player = new AudioPlayer()
  connect()
})

onBeforeUnmount(() => {
  capture?.stop()
  client?.close()
  player?.close()
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
      session: <code>{{ sessionId.slice(0, 12) }}…</code>
    </section>

    <section class="controls">
      <button
        v-if="status === 'ready' || status === 'listening' || status === 'speaking'"
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
      <small>Vue + Spring Boot + OpenClaw Gateway · stt-tts / managed-room</small>
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
.status.speaking { background: var(--accent-hot); color: #2a1a00; }
.status.error { background: var(--red); color: white; }
.status.connecting { background: var(--accent); color: white; opacity: 0.6; }

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
