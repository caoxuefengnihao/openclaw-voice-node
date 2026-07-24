#!/usr/bin/env bash
# download-kws-deps.sh
#
# 一次性脚本:下 KWS (Keyword Spotting) 集成需要的模型文件到本地。
#
# sherpa-onnx JVM jar + native lib 已经在 download-stt-deps.sh 装过了,KWS 复用同库,
# 所以本脚本只下 KWS 模型文件 (~50MB) 到 models/kws/。
#
# 用法:
#   ./scripts/download-kws-deps.sh
#
# 重跑安全(已有的不重下)。
#
# 模型来源:https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
#          搜索 "sherpa-onnx-kws-zh-wenet" 找最新日期版本

set -uo pipefail  # 注意:不 -e,因为我们要尝试多个 URL

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODELS_DIR="${PROJECT_ROOT}/models/kws"

# KWS 模型候选 URL (按发布日期)
# sherpa-onnx 团队会不定期更新 wenet encoder,这里列已知可用版本
KWS_MODEL_URLS=(
    # zipformer-wenetspeech 3.3M (2024-01-01) — 用户验证可下,精度高
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2"
    # 备选 1: kws-models tag 下其他模型
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zh-wenet-20240117.tar.bz2"
    # 备选 2: asr-models tag 下 wenet 版本
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-kws-zh-wenet-20240117.tar.bz2"
)

# ===== 1. 创建目录 =====
mkdir -p "${MODELS_DIR}"

# ===== 2. 跳过已存在 =====
if [[ -f "${MODELS_DIR}/encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx" \
   && -f "${MODELS_DIR}/tokens.txt" \
   && -f "${MODELS_DIR}/keywords.txt" ]]; then
    echo "✅ KWS 模型已存在,跳过下载"
    ls -lh "${MODELS_DIR}/encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx" \
           "${MODELS_DIR}/tokens.txt" \
           "${MODELS_DIR}/keywords.txt"
    exit 0
fi

# ===== 3. 尝试每个 URL =====
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

downloaded=""
for url in "${KWS_MODEL_URLS[@]}"; do
    echo "⬇️  试 $url"
    fname="${WORK_DIR}/$(basename "$url")"
    http_code=$(curl -sL --max-time 120 -w "%{http_code}" -o "${fname}" "$url" 2>&1 | tail -c 4)
    if [[ -s "${fname}" && "${fname}" != *.html ]]; then
        size=$(stat -f%z "${fname}" 2>/dev/null || stat -c%s "${fname}" 2>/dev/null)
        echo "   ✅ 200, ${size} bytes"
        downloaded="${fname}"
        break
    else
        echo "   ❌ 失败 (HTTP ${http_code})"
        rm -f "${fname}"
    fi
done

if [[ -z "${downloaded}" ]]; then
    echo ""
    echo "❌ 所有候选 URL 都失败了。请手动:"
    echo "   1. 打开 https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models"
    echo "   2. 搜索 'kws-zh-wenet'"
    echo "   3. 下载最新的 .tar.bz2 到 ${MODELS_DIR}/kws.tar.bz2"
    echo "   4. 解压: tar -xjf kws.tar.bz2 -C ${MODELS_DIR} --strip-components=1"
    exit 1
fi

# ===== 4. 解压 =====
echo "📦 解压到 ${MODELS_DIR}/"
tar -xjf "${downloaded}" -C "${MODELS_DIR}" --strip-components=1 2>&1 | head -5 || {
    echo "❌ 解压失败,请检查 .tar.bz2 文件"
    exit 1
}

# ===== 5. 验证关键文件 =====
echo ""
echo "🔍 验证模型文件:"
for f in encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx decoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx joiner-epoch-99-avg-1-chunk-16-left-64.int8.onnx tokens.txt keywords.txt; do
    if [[ -f "${MODELS_DIR}/${f}" ]]; then
        size=$(stat -f%z "${MODELS_DIR}/${f}" 2>/dev/null || stat -c%s "${MODELS_DIR}/${f}" 2>/dev/null)
        echo "   ✅ ${f} (${size} bytes)"
    else
        echo "   ❌ ${f} 缺失"
        exit 1
    fi
done

echo ""
echo "✅ KWS 依赖就绪: ${MODELS_DIR}/"
ls -lh "${MODELS_DIR}/"