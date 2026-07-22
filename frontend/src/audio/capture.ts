// capture.ts —— getUserMedia + AudioWorklet → Int16Array 块输出
//
// 关键设计：AudioContext({sampleRate: 16000}) 强制浏览器重采样到 16kHz，
// worklet 只需要 Float32→Int16，零算法复杂度。

import { getPcmWorkletUrl } from './pcm-worklet'

export type PcmChunkHandler = (pcm: Int16Array) => void

export class AudioCapture {
  private ctx: AudioContext | null = null
  private stream: MediaStream | null = null
  private worklet: AudioWorkletNode | null = null
  private source: MediaStreamAudioSourceNode | null = null
  private onChunk: PcmChunkHandler
  private running = false

  constructor(onChunk: PcmChunkHandler) {
    this.onChunk = onChunk
  }

  async start(constraints: MediaStreamConstraints = {}): Promise<void> {
    if (this.running) return

    // 16kHz mono Int16 — 与 OpenClaw Talk session 默认 PCM 格式对齐
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        sampleRate: 16000,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: false,
        ...((constraints.audio as MediaTrackConstraints) || {}),
      },
    })

    // AudioContext 强制 16kHz：浏览器做重采样
    this.ctx = new AudioContext({ sampleRate: 16000 })
    await this.ctx.audioWorklet.addModule(getPcmWorkletUrl())

    this.source = this.ctx.createMediaStreamSource(this.stream)
    this.worklet = new AudioWorkletNode(this.ctx, 'pcm-capture', {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      channelCount: 1,
      processorOptions: { chunk: 2048 },
    })

    this.worklet.port.onmessage = (ev) => {
      if (!this.running) return
      const pcm = new Int16Array(ev.data)
      this.onChunk(pcm)
    }

    this.source.connect(this.worklet)
    // 接 destination 是为了拉取 worklet 节点（否则 process() 不会被调用）
    // process 不写 output → 输出静音，不会回放
    this.worklet.connect(this.ctx.destination)

    this.running = true
  }

  stop(): void {
    this.running = false
    try { this.worklet?.disconnect() } catch {}
    try { this.source?.disconnect() } catch {}
    this.stream?.getTracks().forEach((t) => t.stop())
    try { this.ctx?.close() } catch {}
    this.worklet = null
    this.source = null
    this.stream = null
    this.ctx = null
  }

  isRunning(): boolean {
    return this.running
  }
}
