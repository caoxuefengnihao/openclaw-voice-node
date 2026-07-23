// pcm-worklet.js
// AudioWorkletProcessor:把 mic 抓的 Float32 音频转 Int16 PCM,每 ~100ms 推一次到主线程
// 主线程 (recorder.ts) 再通过 WebSocket 二进制帧发到 Java 后端
//
// 注意:这是个独立 JS 文件,不能 import Vue 或其他 npm 库
// AudioWorkletProcessor 是浏览器在 audio thread 跑的(独立线程,不阻塞 UI)

class PCMProcessor extends AudioWorkletProcessor {
  process(inputs) {
    const input = inputs[0]
    if (!input || !input[0]) return true

    const float32 = input[0]  // Float32Array,length = 128 (default quantum)

    // Float32 [-1, 1] → Int16 [-32768, 32767]
    const int16 = new Int16Array(float32.length)
    for (let i = 0; i < float32.length; i++) {
      const s = Math.max(-1, Math.min(1, float32[i]))
      int16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF
    }

    // postMessage 第 2 个参数是 transferable,把 buffer 所有权转给主线程(零拷贝)
    this.port.postMessage(int16.buffer, [int16.buffer])
    return true
  }
}

registerProcessor('pcm-worklet', PCMProcessor)