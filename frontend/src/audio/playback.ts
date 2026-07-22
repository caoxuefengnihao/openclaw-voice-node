// playback.ts —— 接收 TTS 音频帧（base64 Int16 PCM）按顺序播放
//
// 协议：Java 端把 gateway 的 audio 帧原样转发（base64 Int16 16kHz mono）
// 流程：base64 → Int16Array → Float32Array → AudioBuffer → BufferSource → destination

export class AudioPlayer {
  private ctx: AudioContext
  private queue: AudioBuffer[] = []
  private playing = false
  private gainNode: GainNode

  constructor() {
    this.ctx = new AudioContext()
    this.gainNode = this.ctx.createGain()
    this.gainNode.gain.value = 1.0
    this.gainNode.connect(this.ctx.destination)
  }

  /**
   * 喂一个 TTS 音频帧（base64 Int16 PCM 16kHz mono）
   */
  feed(base64Pcm: string): void {
    if (!base64Pcm) return
    try {
      // base64 → bytes
      const binary = atob(base64Pcm)
      const bytes = new Uint8Array(binary.length)
      for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)

      // bytes → Int16（little-endian）
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
      const samples = new Int16Array(bytes.length / 2)
      for (let i = 0; i < samples.length; i++) {
        samples[i] = view.getInt16(i * 2, true)
      }

      // Int16 → Float32
      const buffer = this.ctx.createBuffer(1, samples.length, 16000)
      const channel = buffer.getChannelData(0)
      for (let i = 0; i < samples.length; i++) {
        channel[i] = samples[i] < 0 ? samples[i] / 0x8000 : samples[i] / 0x7fff
      }

      this.queue.push(buffer)
      this.playNext()
    } catch (e) {
      console.warn('feed audio error', e)
    }
  }

  /**
   * 立即清空队列（用于打断）
   */
  flush(): void {
    this.queue = []
  }

  private playNext(): void {
    if (this.playing || this.queue.length === 0) return
    this.playing = true
    const buffer = this.queue.shift()!
    const source = this.ctx.createBufferSource()
    source.buffer = buffer
    source.connect(this.gainNode)
    source.onended = () => {
      this.playing = false
      this.playNext()
    }
    source.start()
  }

  async close(): Promise<void> {
    this.flush()
    try { await this.ctx.close() } catch {}
  }
}
