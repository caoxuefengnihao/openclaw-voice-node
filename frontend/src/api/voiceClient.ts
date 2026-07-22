// voiceClient.ts —— 浏览器 ↔ Java 后端的 WebSocket 客户端
//
// 上行:
//   - Binary frame: PCM Int16 16kHz mono（直接转发到 gateway appendAudio）
//   - Text frame:   {cmd: "startTurn"|"endTurn"|"cancelTurn"|"ping"}
//
// 下行（来自 Java 翻译过的 talk event）:
//   - {type: "ready", sessionId, transport, mode}
//   - {type: "transcript.delta"|"transcript.done", text}
//   - {type: "assistant", text, final?}
//   - {type: "audio", data: <base64 PCM>}
//   - {type: "turn.done"}
//   - {type: "error", message}

type Handler = (...args: any[]) => void

export class VoiceClient {
  private ws: WebSocket | null = null
  private listeners = new Map<string, Handler[]>()
  private url: string

  constructor(url?: string) {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
    this.url = url || `${proto}//${location.host}/ws/audio`
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
          console.warn('解析消息失败', e, ev.data)
        }
      }
    }
  }

  sendAudio(pcm: Int16Array): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(pcm.buffer)
    }
  }

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
      try { h(...args) } catch (e) { console.warn('handler error', e) }
    }
  }

  close(): void {
    try { this.ws?.close() } catch {}
  }
}
