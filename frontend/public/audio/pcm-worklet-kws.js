// pcm-worklet-kws.js
// KWS 专用 AudioWorkletProcessor:输出 Float32 (不转 Int16),每 1600 样本(100ms @16k)推一次
//
// 借鉴白龙马 wake-probe.html 的 WakeCapture 设计:
//   - 不做 Float32→Int16 转换 (KWS 模型训练在 float 上,转换会引入量化噪声)
//   - 累积到 CHUNK 样本再 postMessage,降低主线程消息压力
//   - transferable 零拷贝
//
// 与 pcm-worklet.js (Int16, 给 STT/recorder 用) 独立,两个 worklet 共存。

class PcmKwsProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super()
    this._size = (options && options.processorOptions && options.processorOptions.chunk) || 1600
    this._buf = new Float32Array(this._size)
    this._n = 0
  }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0]
    if (ch) {
      for (let i = 0; i < ch.length; i++) {
        this._buf[this._n++] = ch[i]
        if (this._n >= this._size) {
          // slice 拷贝 — 不能用 transfer 因为同一 buffer 还要继续填充
          const out = this._buf.slice(0, this._n)
          this.port.postMessage(out.buffer, [out.buffer])
          this._n = 0
        }
      }
    }
    return true
  }
}

registerProcessor('pcm-worklet-kws', PcmKwsProcessor)