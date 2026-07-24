<script setup lang="ts">
// KwsPage.vue -- KWS 唤醒词独立页面 (v3 新增)
// 跟现有 App.vue 平行,完全不依赖它
//
// 流程:
//   页面打开 -> 连 /ws/kws -> 启 KwsMonitor (持续监听)
//   喊"小爱同学" -> 后端 KWS 检测 -> 发 wake.detected
//   自动切录音模式: audio.start -> 说话 -> audio.end -> STT -> chat -> TTS
//   TTS 播完 -> 自动回 KWS 监听模式

import { ref, onMounted, onBeforeUnmount } from 'vue'
import { KwsClient } from './api/kwsClient'
import { KwsMonitor } from './audio/kwsMonitor'

type Status = 'idle' | 'connecting' | 'kws-listening' | 'recording' | 'processing'

interface ChatMsg {
  role: 'user' | 'assistant'
  content: string
  ts: number
}

const status = ref<Status>('idle')
const errorMsg = ref('')
const keywords = ref<string[]>([])
const messages = ref<ChatMsg[]>([])

let client: KwsClient | null = null
const monitor = new KwsMonitor()

// TTS 播放 (独立 AudioContext,不跟 mic 抢)
let playCtx: AudioContext | null = null
let currentSource: AudioBufferSourceNode | null = null

// 唤醒后自动录音的 10s 超时
let autoStopTimer: number | null = null

function clearAutoStop() {
  if (autoStopTimer !== null) {
    clearTimeout(autoStopTimer)
    autoStopTimer = null
  }
}

function setupHandlers(c: KwsClient) {
  c.on('open', () => {
    status.value = 'connecting'
    console.log('[kws-page] WS open')
  })

  c.on('close', (code: number, reason: string) => {
    console.log('[kws-page] WS close', code, reason)
    errorMsg.value = `WS 断开 (${code} ${reason})`
    status.value = 'idle'
  })

  c.on('error', (ev: Event) => {
    console.warn('[kws-page] WS error', ev)
    errorMsg.value = 'WS 连接错误'
  })

  c.on('kws.ack', (msg: any) => {
    status.value = 'kws-listening'
    keywords.value = msg.keywords || []
    console.log('[kws-page] KWS ack, keywords:', keywords.value)
  })

  c.on('wake.detected', async (msg: any) => {
    console.log('[kws-page] 🔥 wake detected:', msg.keyword)
    // 1. 停 KWS monitor (后端 KwsService 已自动停)
    monitor.stop()
    // 2. 通知后端切录音模式 + 发 audio.start
    c.sendCommand({ type: 'audio.start', sampleRate: 16000, encoding: 'pcm_s16le' })
    status.value = 'recording'
    // 3. 启 10s 自动停超时 (防一直录)
    clearAutoStop()
    autoStopTimer = window.setTimeout(() => {
      if (status.value === 'recording') {
        console.log('[kws-page] 10s 超时,自动停录音')
        stopRecording()
      }
    }, 10000)
  })

  c.on('user.text', (msg: any) => {
    if (msg.text) {
      messages.value.push({ role: 'user', content: msg.text, ts: Date.now() })
    }
  })

  c.on('assistant', (msg: any) => {
    // 流式响应,简化处理:final 时追加
    if (msg.final !== false && msg.text) {
      messages.value.push({ role: 'assistant', content: msg.text, ts: Date.now() })
    } else if (msg.text && messages.value.length > 0) {
      // 简化:累加到最后一条 assistant
      const last = messages.value[messages.value.length - 1]
      if (last.role === 'assistant') {
        last.content += msg.text
      }
    }
  })

  c.on('assistant.audio', async (msg: any) => {
    status.value = 'processing'
    if (msg.audio) {
      await playTts(msg.audio)
    }
    // TTS 播完 -> 自动回 KWS 监听
    await resumeKws()
  })

  c.on('error', (msg: any) => {
    errorMsg.value = msg.message || 'unknown error'
  })
}

async function playTts(base64Audio: string): Promise<void> {
  if (!playCtx) playCtx = new AudioContext()
  const binary = atob(base64Audio)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  const buf = await playCtx.decodeAudioData(bytes.buffer)
  // 停掉当前播放
  if (currentSource) {
    try { currentSource.stop() } catch {}
  }
  currentSource = playCtx.createBufferSource()
  currentSource.buffer = buf
  currentSource.connect(playCtx.destination)
  return new Promise<void>((resolve) => {
    currentSource!.onended = () => resolve()
    currentSource!.start()
  })
}

async function resumeKws() {
  if (!client) return
  status.value = 'kws-listening'
  await monitor.start(client)
}

function stopRecording() {
  clearAutoStop()
  if (client && status.value === 'recording') {
    client.sendCommand({ type: 'audio.end' })
    status.value = 'processing'
  }
}

onMounted(async () => {
  client = new KwsClient()
  setupHandlers(client)
  client.connect()

  // 等 open 后启 KWS 监听
  client.on('open', async () => {
    if (client) {
      try {
        await monitor.start(client)
      } catch (e: any) {
        errorMsg.value = `启动 KWS 失败: ${e.message}`
        status.value = 'idle'
      }
    }
  })
})

onBeforeUnmount(() => {
  clearAutoStop()
  monitor.stop()
  if (currentSource) {
    try { currentSource.stop() } catch {}
  }
  playCtx?.close().catch(() => {})
  client?.close()
})
</script>

<template>
  <div class="kws-page">
    <h1>🎙️ OpenClaw 语音唤醒</h1>

    <div class="status-card" :class="status">
      <div class="status-icon">
        <span v-if="status === 'kws-listening'">🎧</span>
        <span v-else-if="status === 'recording'">🎤</span>
        <span v-else-if="status === 'processing'">⏳</span>
        <span v-else-if="status === 'connecting'">🔌</span>
        <span v-else>💤</span>
      </div>
      <div class="status-text">
        <template v-if="status === 'kws-listening'">
          监听中... 喊
          <span v-for="(kw, i) in keywords" :key="kw" class="keyword">
            "{{ kw.replace(/\s+/g, '') }}"<span v-if="i < keywords.length - 1"> / </span>
          </span>
        </template>
        <template v-else-if="status === 'recording'">我在听,请说</template>
        <template v-else-if="status === 'processing'">思考中...</template>
        <template v-else-if="status === 'connecting'">连接中...</template>
        <template v-else>待机</template>
      </div>
    </div>

    <button
      v-if="status === 'recording'"
      class="stop-btn"
      @click="stopRecording"
    >说完</button>

    <div v-if="errorMsg" class="error">⚠️ {{ errorMsg }}</div>

    <div class="chat">
      <div
        v-for="(msg, i) in messages"
        :key="msg.ts + '-' + i"
        class="chat-msg"
        :class="msg.role"
      >
        <span class="role">{{ msg.role === 'user' ? '你' : 'CTO' }}</span>
        <span class="content">{{ msg.content }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.kws-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 24px;
  font-family: -apple-system, BlinkMacSystemFont, sans-serif;
}

h1 {
  font-size: 24px;
  margin-bottom: 24px;
}

.status-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  border-radius: 12px;
  background: #f5f5f7;
  margin-bottom: 16px;
  transition: all 0.3s;
}
.status-card.kws-listening {
  background: #e8f4ff;
  animation: pulse 2s ease-in-out infinite;
}
.status-card.recording {
  background: #ffe8e8;
}
.status-card.processing {
  background: #fff4e8;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.status-icon {
  font-size: 32px;
}
.status-text {
  font-size: 18px;
}
.keyword {
  color: #007aff;
  font-weight: 600;
}

.stop-btn {
  display: block;
  width: 100%;
  padding: 12px;
  font-size: 16px;
  background: #ff3b30;
  color: white;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 16px;
}

.error {
  color: #ff3b30;
  padding: 12px;
  background: #ffe8e8;
  border-radius: 8px;
  margin-bottom: 16px;
}

.chat {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.chat-msg {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-radius: 8px;
}
.chat-msg.user {
  background: #e8f4ff;
  flex-direction: row-reverse;
}
.chat-msg.assistant {
  background: #f5f5f7;
}
.role {
  font-weight: 600;
  color: #666;
  flex-shrink: 0;
}
.content {
  flex: 1;
  word-break: break-word;
}
</style>