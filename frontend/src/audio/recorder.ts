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

    // secure context 检查：浏览器 getUserMedia 需要 HTTPS / localhost / file://
    // (非 secure context 下 navigator.mediaDevices 整个属性为 undefined)
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      const isSecure = window.isSecureContext
      throw new Error(
        '麦克风 API 不可用：当前页面不是 secure context (' +
          (isSecure ? 'isSecureContext=true 但 mediaDevices 不存在' : 'isSecureContext=false') +
          ')。请用 http://localhost:5174/ 或 HTTPS 访问。'
      )
    }

    this.mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        // ⚠️ 不能写 sampleRate: 16000 — Mac/Windows 麦克风默认 44.1/48kHz,很多设备不支持 16kHz 硬约束。
        // Chrome 第一次能 resample 成功,后续会返回静音流(资源/状态问题)。
        // 让浏览器用 native rate,resample 交给 AudioContext (16kHz) 内部自动做。
        //
        // ⚠️ echoCancellation 设为 false — macOS + Chrome + 蓝牙耳机麦克风 是个已知坑:
        // echoCancellation 走 macOS acoustic echo 路径,BT 耳机麦克风走 HFP narrowband,
        // 两者叠加经常返回全 0 samples (就是之前 maxAmp=0 的现象)。
        channelCount: 1,
        echoCancellation: false,  // BT 耳机用户必关!内置 mic 安全
        noiseSuppression: true,
      },
    })

    // 诊断:看默认音轨状态(muted/disabled 在这里能看出来)
    const track = this.mediaStream.getAudioTracks()[0]
    console.log('[rec] 音轨状态: enabled=' + track.enabled,
        'readyState=' + track.readyState,
        'muted=' + track.muted,
        'label="' + track.label + '"')

    // AudioContext 强制 16kHz (内部自动 resample mic 流到这个 rate)
    this.audioContext = new AudioContext({ sampleRate: 16000 })
    await this.audioContext.audioWorklet.addModule('/audio/pcm-worklet.js')

    const source = this.audioContext.createMediaStreamSource(this.mediaStream)
    this.workletNode = new AudioWorkletNode(this.audioContext, 'pcm-worklet')

    // 🔧 BT 麦冷启动延迟 (~250ms) — Chrome 的 BT mic audio pipeline 冷启需要 ~250ms,
    // 之前没等的话,头 ~250ms 是静音 / 异常样本,被 STT 幻觉成 "没有没有没有"。
    // 等 pipeline 完全起来再 connect,worklet 收到的第一帧就是真实语音。
    await new Promise(resolve => setTimeout(resolve, 250))

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
      // 🔧 尾部缓冲延迟 (~250ms) — Chrome BT mic 音频流松开后不会立刻释放最后那 ~200ms 缓冲,
      // 如果立即发 audio.end,后端 STT 会丢用户最后一句的尾音。
      // 延迟 250ms 发 audio.end,让被 capture 的尾部音频先到后端 buffer。
      setTimeout(() => {
        this.client?.sendCommand({ type: 'audio.end' })
      }, 250)
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