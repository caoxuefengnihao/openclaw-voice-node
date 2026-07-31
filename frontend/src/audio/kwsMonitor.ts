// kwsMonitor.ts -- KWS 持续监听 (v3 新增)
// 跟现有 AudioRecorder 平行,但:
// 1. 页面打开即启 (不需要点按钮)
// 2. 连 /ws/kws (不是 /ws/audio)
// 3. 持续发 Float32 PCM,后端 KWS 每帧检测
// 4. 收到 wake.detected -> 前端切到 audio.start 录音模式
//
// 用独立的 /audio/pcm-worklet-kws.js (Float32 输出,1600 样本/块 = 100ms@16k),
// 不复用 STT 的 Int16 worklet。理由:
//   - KWS 模型训练在 float 上,Float32→Int16 转换会引入量化噪声
//   - 白龙马 wake-probe.html 也是 Float32 直发
//   - 改 STT worklet 会破坏 recorder.ts,得不偿失

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

    // 借鉴白龙马 wake-probe.html:
    //   KWS 要原始音频,所有浏览器音频处理全关
    //   - 模型训练在干净音频上,AEC/NS/AGC 都会扭曲语音、害识别
    //   - 白龙马注释直说: "样本能 100% 命中正因为干净"
    // 之前 voice-node 是 echoCancellation:false + noiseSuppression:true,
    // 这次统一关掉 noiseSuppression + autoGainControl。
    this.mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: false,    // BT 耳机坑 + KWS 都要关
        noiseSuppression: false,    // 借鉴白龙马 — KWS 必须关
        autoGainControl: false,     // 借鉴白龙马 — KWS 必须关
        // ⚠️ 不写 sampleRate: 跟 recorder.ts 一致 — 浏览器自选设备 native rate,
        // AudioContext({sampleRate: 16000}) 负责内部 resample 到 16kHz
        // (硬约束 16k 在 BT HFP 8kHz 设备上会导致 Chrome 返回静音流,
        //  跟 STT 路径行为不一致 → STT 能识别,KWS 不行)
      },
    })

    const track = this.mediaStream.getAudioTracks()[0]
    console.log('[kws-monitor] 音轨状态: enabled=' + track.enabled,
        'readyState=' + track.readyState,
        'muted=' + track.muted,
        'label="' + track.label + '"')

    this.audioContext = new AudioContext({ sampleRate: 16000 })
    // 用 KWS 专用 Float32 worklet (1600 样本/块 ≈ 100ms @16k)
    // 不要 addModule('/audio/pcm-worklet.js') — 那是 Int16 的,给 STT 用
    await this.audioContext.audioWorklet.addModule('/audio/pcm-worklet-kws.js')
    const source = this.audioContext.createMediaStreamSource(this.mediaStream)
    this.workletNode = new AudioWorkletNode(this.audioContext, 'pcm-worklet-kws', {
      processorOptions: { chunk: 1600 },
    })

    source.connect(this.workletNode)

    // AudioWorklet 每 ~100ms 推 Float32 PCM,直接发 KWS 客户端 (后端逐帧过 KwsService)
    this.workletNode.port.onmessage = (e: MessageEvent<ArrayBuffer>) => {
      if (this.client) {
        const pcm = new Float32Array(e.data)
        // 调试: 每 10 块打 maxAmp,看 mic 是否实际拾到音频 (Float32 范围 [-1, 1])
        if (this.chunkCount % 10 === 0) {
          let maxAmp = 0
          for (let i = 0; i < pcm.length; i++) {
            const abs = pcm[i] < 0 ? -pcm[i] : pcm[i]
            if (abs > maxAmp) maxAmp = abs
          }
          console.log(`[kws-monitor] chunk=${this.chunkCount} samples=${pcm.length} maxAmp=${maxAmp.toFixed(4)}/1.0`)
        }
        this.client.sendAudio(pcm)
        this.chunkCount++
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