package com.openclaw.voicenode.service;

/**
 * PCM 音频格式转换工具 (无状态、纯静态方法)。
 *
 * <p>16kHz mono Int16 LE ↔ Float32 [-1.0, 1.0] 双向转换,
 * 供 {@link SttService} (Int16→Float32) 和 {@code VoiceWebSocketHandler} VAD 集成
 * (Float32→Int16 切片回写) 共用。
 *
 * <p>抽出来是避免两个地方各自维护一份相同的字节序转换代码 (跟 Float32 LE/Float32 LE 字节序一致)。
 */
public final class AudioUtil {

    private AudioUtil() {}

    /**
     * 16kHz mono int16 little-endian bytes → float32 [-1.0, 1.0]
     *
     * @param pcm Int16 LE PCM bytes (2 bytes/sample)
     * @return Float32 PCM samples (长度 = pcm.length / 2)
     */
    public static float[] pcmInt16LeToFloat32(byte[] pcm) {
        int n = pcm.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm[2 * i] & 0xff) | ((pcm[2 * i + 1] & 0xff) << 8));
            out[i] = s / 32768f;
        }
        return out;
    }

    /**
     * Float32 数组切片 → 16kHz mono int16 little-endian bytes。
     *
     * <p>clamp 到 [-1.0, 1.0] 后乘 32767 (不用 32768 防止溢出)。
     *
     * @param samples Float32 PCM (范围 [-1.0, 1.0],超出范围会被 clamp)
     * @param offset  起始 sample index (含)
     * @param length  切片长度 (sample 数)
     * @return Int16 LE PCM bytes (长度 = length * 2)
     */
    public static byte[] float32SliceToPcmInt16Le(float[] samples, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > samples.length) {
            throw new IllegalArgumentException(
                    "切片越界: offset=" + offset + " length=" + length
                            + " samples.length=" + samples.length);
        }
        byte[] out = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            float f = samples[offset + i];
            if (f > 1.0f) f = 1.0f;
            if (f < -1.0f) f = -1.0f;
            short s = (short) (f * 32767f);
            out[2 * i] = (byte) (s & 0xff);
            out[2 * i + 1] = (byte) ((s >> 8) & 0xff);
        }
        return out;
    }

    /**
     * Float32 数组整段 → 16kHz mono int16 little-endian bytes
     * (切片语义的便捷方法)
     */
    public static byte[] float32ToPcmInt16Le(float[] samples) {
        return float32SliceToPcmInt16Le(samples, 0, samples.length);
    }
}