// kwsClient.ts -- KWS 唤醒词专用 WebSocket 客户端 (v3 新增)
// 跟现有 VoiceClient 平行,连 /ws/kws 而不是 /ws/audio
// 完全独立,不修改任何现有文件

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

  /** 发 PCM 二进制帧 (16kHz mono int16 LE) */
  sendAudio(pcm: Int16Array): void {
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