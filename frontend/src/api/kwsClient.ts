// kwsClient.ts -- KWS 唤醒词专用 WebSocket 客户端 (v3 新增)
// 跟现有 VoiceClient 平行,连 /ws/kws 而不是 /ws/audio
// 完全独立,不修改任何现有文件
//
// 改动 (2026-07-25): PCM 格式从 Int16 改成 Float32 (直接发,不做 Int16 转换)
//   理由: KWS 模型在 float 上训练,Float32→Int16 会引入量化噪声
//   借鉴白龙马 wake-probe.html + voice-core.js 的 Float32 直发模式

type Handler = (...args: any[]) => void

export class KwsClient {
  private ws: WebSocket | null = null
  private listeners = new Map<string, Handler[]>()
  private url: string

  constructor(url?: string) {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
    this.url = url || `${proto}//${location.host}/ws/kws`
  }

  connect(): void {
    this.ws = new WebSocket(this.url)
    this.ws.binaryType = 'arraybuffer'

    this.ws.onopen = () => this.emit('open')
    this.ws.onclose = (ev) => this.emit('close', ev.code, ev.reason)
    this.ws.onerror = (ev) => this.emit('error', ev)

    this.ws.onmessage = (ev) => {
      if (typeof ev.data === 'string') {
        try {
          const msg = JSON.parse(ev.data)
          this.emit(msg.type || 'message', msg)
        } catch (e) {
          console.warn('[kws] 解析消息失败', e, ev.data)
        }
      }
    }
  }

  /** 发 Float32 PCM 二进制帧 (16kHz mono float32 LE,4 字节/样本)。
   *  借鉴白龙马:KWS 模型在 float 上训练,不做 Int16 转换。 */
  sendAudio(pcm: Float32Array): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(pcm.buffer)
    }
  }

  /** 发 JSON 命令帧 */
  sendCommand(cmd: object): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(cmd))
    }
  }

  on(event: string, handler: Handler): void {
    if (!this.listeners.has(event)) this.listeners.set(event, [])
    this.listeners.get(event)!.push(handler)
  }

  off(event: string, handler: Handler): void {
    const arr = this.listeners.get(event)
    if (arr) this.listeners.set(event, arr.filter((h) => h !== handler))
  }

  private emit(event: string, ...args: any[]): void {
    for (const h of this.listeners.get(event) ?? []) {
      try { h(...args) } catch (e) { console.warn('[kws] handler error', e) }
    }
  }

  close(): void {
    try { this.ws?.close() } catch {}
  }
}