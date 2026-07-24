// pcm-worklet.ts —— AudioWorklet 处理器源码集合
//
// 两个独立 worklet,共存于浏览器:
//   - PCM_WORKLET_SRC     Int16 输出,给 STT/recorder.ts 用 (浏览器必须做 Float32→Int16)
//   - PCM_KWS_WORKLET_SRC Float32 输出,给 KWS/kwsMonitor.ts 用 (KWS 模型在 float 上训练,转换会引入量化噪声)
//
// 两个都从白龙马 wake-probe.html / voice-core.js 的 PCM_WORKLET_SRC 模式抄过来:
//   - AudioContext 强制 16kHz(让浏览器做重采样)
//   - postMessage 传 ArrayBuffer(零拷贝转移)
//
// 块大小:
//   - Int16/STT: 2048 样本 ≈ 128ms@16kHz,平衡延迟与网络
//   - Float32/KWS: 1600 样本 ≈ 100ms@16kHz(借鉴白龙马 wake-probe.html 的 CHUNK=1600)
//
// 用法:
//   // STT
//   const audioCtx = new AudioContext({ sampleRate: 16000 })
//   await audioCtx.audioWorklet.addModule(getPcmWorkletUrl())
//   const worklet = new AudioWorkletNode(audioCtx, 'pcm-capture', {
//     processorOptions: { chunk: 2048 }
//   })
//   worklet.port.onmessage = (ev) => {
//     const int16 = new Int16Array(ev.data)
//     // → WebSocket 发到后端
//   }
//
//   // KWS
//   await audioCtx.audioWorklet.addModule(getKwsWorkletUrl())
//   const kwsWorklet = new AudioWorkletNode(audioCtx, 'pcm-kws', {
//     processorOptions: { chunk: 1600 }
//   })
//   kwsWorklet.port.onmessage = (ev) => {
//     const float32 = new Float32Array(ev.data)
//     // → WebSocket 发到后端 (二进制 4 字节/样本,LE)
//   }

// ─── KWS 专用: Float32 输出,1600 样本/块 ───
// 借鉴白龙马 wake-probe.html:
//   - 不做 Float32→Int16 转换
//   - 累积到 CHUNK 样本再 postMessage
export const PCM_KWS_WORKLET_SRC = `
class PcmKwsProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this._size = (options && options.processorOptions && options.processorOptions.chunk) || 1600;
    this._buf = new Float32Array(this._size);
    this._n = 0;
  }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (ch) {
      for (let i = 0; i < ch.length; i++) {
        this._buf[this._n++] = ch[i];
        if (this._n >= this._size) {
          const out = this._buf.slice(0, this._n);
          this.port.postMessage(out.buffer, [out.buffer]);
          this._n = 0;
        }
      }
    }
    return true;
  }
}
registerProcessor('pcm-kws', PcmKwsProcessor);
`

export const PCM_WORKLET_SRC = `
class PcmCaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this._size = (options && options.processorOptions && options.processorOptions.chunk) || 2048;
    this._buf = new Int16Array(this._size);
    this._n = 0;
  }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (ch) {
      for (let i = 0; i < ch.length; i++) {
        let s = ch[i];
        if (s > 1) s = 1; else if (s < -1) s = -1;
        this._buf[this._n++] = s < 0 ? s * 0x8000 : s * 0x7fff;
        if (this._n >= this._size) {
          const out = this._buf.slice(0);
          this.port.postMessage(out.buffer, [out.buffer]);
          this._n = 0;
        }
      }
    }
    return true;
  }
}
registerProcessor('pcm-capture', PcmCaptureProcessor);
`

// Worklet 源码作为 Blob URL 加载（绕开 Electron 打包后 file:// 路径问题，浏览器里也用同样的兜底）
let _workletUrl: string | null = null
export function getPcmWorkletUrl(): string {
  if (!_workletUrl) {
    const blob = new Blob([PCM_WORKLET_SRC], { type: 'application/javascript' })
    _workletUrl = URL.createObjectURL(blob)
  }
  return _workletUrl
}

let _kwsWorkletUrl: string | null = null
export function getKwsWorkletUrl(): string {
  if (!_kwsWorkletUrl) {
    const blob = new Blob([PCM_KWS_WORKLET_SRC], { type: 'application/javascript' })
    _kwsWorkletUrl = URL.createObjectURL(blob)
  }
  return _kwsWorkletUrl
}