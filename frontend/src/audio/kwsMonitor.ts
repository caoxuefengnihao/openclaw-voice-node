// kwsMonitor.ts -- KWS 持续监听 (v3 新增)
// 跟现有 AudioRecorder 平行,但:
// 1. 页面打开即启 (不需要点按钮)
// 2. 连 /ws/kws (不是 /ws/audio)
// 3. 持续发 audio.chunk,后端 KWS 每帧检测
// 4. 收到 wake.detected -> 前端切到 audio.start 录音模式
//
// 复用现有 /audio/pcm-worklet.js (同一个 AudioWorklet 模块)

import type { KwsClient } from '../api/kwsClient'

export class KwsMonitor {
  private mediaStream: MediaStream | null = null
  private audioContext: AudioContext | null = null
  private workletNode: AudioWorkletNode | null = null
  private client: KwsClient | null = null
  private chunkCount = 0

  async start(client: KwsClient): Promise<void> {
    if (this.audioContext) {
      throw new Error('KWS monitor already started')
    }
    this.client = client
    this.chunkCount = 0

    // secure context 检查 (跟 recorder.ts 一致)
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      const isSecure = window.isSecureContext
      throw new Error(
        '麦克风 API 不可用:当前页面不是 secure context (' +
          (isSecure ? 'isSecureContext=true 但 mediaDevices 不存在' : 'isSecureContext=false') +
          ')。请用 http://localhost:5174/kws.html 或 HTTPS 访问。'
      )
    }

    console.log('[kws-monitor] 请求麦克风权限...')

    // 跟 recorder.ts 一样的 mic 配置 (BT 耳机坑修复)
    this.mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: false,  // BT 耳机必关!
        noiseSuppression: true,
      },
    })

    const track = this.mediaStream.getAudioTracks()[0]
    console.log('[kws-monitor] 音轨状态: enabled=' + track.enabled,
        'readyState=' + track.readyState,
        'muted=' + track.muted,
        'label="' + track.label + '"')

    this.audioContext = new AudioContext({ sampleRate: 16000 })
    await this.audioContext.audioWorklet.addModule('/audio/pcm-worklet.js')
    const source = this.audioContext.createMediaStreamSource(this.mediaStream)
    this.workletNode = new AudioWorkletNode(this.audioContext, 'pcm-worklet')

    source.connect(this.workletNode)

    // AudioWorklet 每 ~100ms 推 PCM,直接发 KWS 客户端 (后端逐帧过 KwsService)
    this.workletNode.port.onmessage = (e: MessageEvent<ArrayBuffer>) => {
      if (this.client) {
        this.client.sendAudio(new Int16Array(e.data))
        this.chunkCount++
        if (this.chunkCount % 50 === 0) {
          console.log(`[kws-monitor] 已发 ${this.chunkCount} 块 PCM`)
        }
      }
    }

    // 通知后端进入 KWS 监听
    client.sendCommand({ type: 'audio.kws.start' })
    console.log('[kws-monitor] ✅ KWS monitoring started')
  }

  stop(): void {
    if (!this.audioContext) {
      console.warn('[kws-monitor] stop() 时 audioContext 已为 null')
      return
    }

    if (this.client) {
      this.client.sendCommand({ type: 'audio.kws.stop' })
    }

    this.mediaStream?.getTracks().forEach(t => t.stop())
    this.audioContext.close().catch(() => {})
    this.audioContext = null
    this.workletNode = null
    this.mediaStream = null
    this.client = null

    console.log('[kws-monitor] ✅ KWS monitoring stopped')
  }
}