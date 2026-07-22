// pcm-worklet.ts —— AudioWorklet 处理器，把麦克风 Float32 转 Int16 PCM 块
//
// 直接从白龙马 voice-core.js 的 PCM_WORKLET_SRC 抄过来（同一套 Web Audio API）：
//   - AudioContext 强制 16kHz（让浏览器做重采样，worklet 只做 Float32→Int16）
//   - 2048 样本/块 ≈ 128ms@16kHz，平衡延迟与消息/网络开销
//   - postMessage 传 ArrayBuffer（零拷贝转移）
//
// 用法：
//   const audioCtx = new AudioContext({ sampleRate: 16000 })
//   await audioCtx.audioWorklet.addModule(pcmWorkletUrl)  // 需先 import 这个文件
//   const worklet = new AudioWorkletNode(audioCtx, 'pcm-capture', {
//     numberOfInputs: 1, numberOfOutputs: 1, channelCount: 1,
//     processorOptions: { chunk: 2048 }
//   })
//   worklet.port.onmessage = (ev) => {
//     const int16 = new Int16Array(ev.data)
//     // → 通过 WebSocket 发到 Java 后端
//   }

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
