#!/usr/bin/env bash
# download-vad-deps.sh
#
# 一次性脚本:下 VAD (Voice Activity Detection) 集成需要的模型文件到本地。
#
# sherpa-onnx JVM jar + native lib 已经在 download-stt-deps.sh 装过了,VAD 复用同库,
# 所以本脚本只下 silero-vad 模型文件 (~1.8MB) 到 models/vad/。
#
# 用法:
#   ./scripts/download-vad-deps.sh
#
# 重跑安全(已有的不重下)。
#
# 模型来源:https://github.com/snakers4/silero-vad
#          v4 版本 (window_size=512) 跟 sherpa-onnx 1.13.4 Vad API 实测可工作

set -uo pipefail  # 注意:不 -e,因为我们要尝试多个 URL

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODELS_DIR="${PROJECT_ROOT}/models/vad"

# VAD 模型候选 URL (按可用性排序)
VAD_MODEL_URLS=(
    # 主:snakers4/silero-vad v4 (1.8MB,windowSize=512,sherpa-onnx 1.13.4 VadProbe 实测可工作)
    "https://github.com/snakers4/silero-vad/raw/v4.0/files/silero_vad.onnx"
    # 备选 1:v3 (旧版本,window_size 不同但兼容)
    "https://github.com/snakers4/silero-vad/raw/v3.0/files/silero_vad.onnx"
    # 备选 2:master 分支 (最新开发版,可能不兼容)
    "https://github.com/snakers4/silero-vad/raw/master/files/silero_vad.onnx"
)

# ===== 1. 创建目录 =====
mkdir -p "${MODELS_DIR}"

# ===== 2. 跳过已存在 =====
if [[ -f "${MODELS_DIR}/silero_vad.onnx" ]]; then
    SIZE=$(stat -f%z "${MODELS_DIR}/silero_vad.onnx" 2>/dev/null || stat -c%s "${MODELS_DIR}/silero_vad.onnx")
    if [[ "${SIZE}" -gt 1048576 ]]; then  # >1MB
        echo "✅ silero-vad 模型已存在,跳过下载"
        ls -lh "${MODELS_DIR}/silero_vad.onnx"
        exit 0
    else
        echo "⚠️ 已存在文件太小 (${SIZE} bytes),删除重下"
        rm -f "${MODELS_DIR}/silero_vad.onnx"
    fi
fi

# ===== 3. 尝试每个 URL =====
for url in "${VAD_MODEL_URLS[@]}"; do
    echo "尝试下载: $url"
    if curl -sSL -o "${MODELS_DIR}/silero_vad.onnx" "$url"; then
        SIZE=$(stat -f%z "${MODELS_DIR}/silero_vad.onnx" 2>/dev/null || stat -c%s "${MODELS_DIR}/silero_vad.onnx")
        if [[ "${SIZE}" -gt 1048576 ]]; then
            echo "✅ 下载成功: $(ls -lh "${MODELS_DIR}/silero_vad.onnx" | awk '{print $5}')"
            # 简单校验:文件不是 HTML 错误页
            HEAD_BYTES=$(head -c 20 "${MODELS_DIR}/silero_vad.onnx" | xxd | head -1)
            echo "  前 20 字节: ${HEAD_BYTES}"
            exit 0
        else
            echo "⚠️ 下载文件太小 (${SIZE} bytes),可能失败,尝试下一个 URL"
            rm -f "${MODELS_DIR}/silero_vad.onnx"
        fi
    fi
done

echo ""
echo "❌ 所有 URL 都失败,请检查网络或手动下载到 ${MODELS_DIR}/silero_vad.onnx"
echo "   手动下载参考 URL:"
for url in "${VAD_MODEL_URLS[@]}"; do
    echo "     $url"
done
exit 1