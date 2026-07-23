// recorder.ts
// AudioRecorder:浏览器端录音 → AudioContext 16kHz → AudioWorklet 转 PCM → WebSocket 发给后端
//
// 用法:
//   const recorder = new AudioRecorder()
//   await recorder.start(client)      // 调 mic 权限,启 AudioContext,发 audio.start
//   recorder.stop()                   // 发 audio.end,清资源
//
// WebSocket 消息:
//   上行: {type:"audio.start", sampleRate, encoding} → <binary PCM chunks> → {type:"audio.end"}
//   下行: {type:"audio.ack"} {type:"user.text"} {type:"error"}

import type { VoiceClient } from '../api/voiceClient'

export class AudioRecorder {
  private mediaStream: MediaStream | null = null
  private audioContext: AudioContext | null = null
  private workletNode: AudioWorkletNode | null = null
  private client: VoiceClient | null = null
  private chunkCount = 0

  async start(client: VoiceClient): Promise<void> {
    if (this.audioContext) {
      throw new Error('recorder already started')
    }
    this.client = client
    this.chunkCount = 0

    console.log('[rec] 请求麦克风权限...')
    this.mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        sampleRate: 16000,
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
      },
    })

    // AudioContext 强制 16kHz (浏览器会自动 resample 到这个 rate)
    this.audioContext = new AudioContext({ sampleRate: 16000 })
    await this.audioContext.audioWorklet.addModule('/audio/pcm-worklet.js')

    const source = this.audioContext.createMediaStreamSource(this.mediaStream)
    this.workletNode = new AudioWorkletNode(this.audioContext, 'pcm-worklet')
    source.connect(this.workletNode)

    // AudioWorklet 每 ~100ms 推一块 Int16 PCM buffer 到主线程
    this.workletNode.port.onmessage = (e: MessageEvent<ArrayBuffer>) => {
      const pcm = e.data
      if (this.client) {
        // 二进制帧发送(后端 handleBinaryMessage 收)
        this.client.sendAudio(new Int16Array(pcm))
        this.chunkCount++
        if (this.chunkCount % 10 === 0) {
          console.log(`[rec] 已发 ${this.chunkCount} 块 PCM (${(pcm.byteLength / 1024).toFixed(1)} KB/块)`)
        }
      }
    }

    // 通知后端开始录音(通过 VoiceClient 发 text 帧)
    client.sendCommand({
      type: 'audio.start',
      sampleRate: 16000,
      encoding: 'pcm_s16le',
    })
    console.log('[rec] ✅ 录音开始 (16kHz mono Int16 LE)')
  }

  stop(): void {
    if (!this.audioContext) {
      console.warn('[rec] stop() 时 audioContext 已为 null,可能已经 stop 过')
      return
    }

    // 通知后端结束(后端会 flush STT)
    if (this.client) {
      this.client.sendCommand({ type: 'audio.end' })
    }

    // 释放浏览器资源
    this.mediaStream?.getTracks().forEach(t => t.stop())
    this.audioContext.close().catch(() => { /* ignore */ })
    this.audioContext = null
    this.workletNode = null
    this.mediaStream = null
    this.client = null

    console.log('[rec] ✅ 录音停止')
  }

  cancel(): void {
    if (this.client) {
      this.client.sendCommand({ type: 'audio.cancel' })
    }
    this.mediaStream?.getTracks().forEach(t => t.stop())
    this.audioContext?.close().catch(() => {})
    this.audioContext = null
    this.workletNode = null
    this.mediaStream = null
    this.client = null
  }
}