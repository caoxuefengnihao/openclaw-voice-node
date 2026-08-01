#!/bin/bash
# 一键: 训练 → 导出 → 验证 → 替换模型 → 重启
# 在本地 voice-node 机器上跑 (不是 GPU 机器!)
set -e

WORK_DIR="$(cd "$(dirname "$0")" && pwd)"
KEYWORD="贾维斯"
MODEL_DIR="/Volumes/ssd/openclaw-voice-node/models/kws_custom"
LOCAL_BACKUP="/Volumes/ssd/openclaw-voice-node/models/kws-pretrained"

echo "=== Step 1: 从 GPU 下载模型 (需先 scp 下来) ==="
GPU_HOST="${GPU_HOST:-root@localhost}"
GPU_PORT="${GPU_PORT:-22}"
GPU_DIR="${GPU_DIR:-/root/icefall/egs/wenetspeech/KWS/zipformer/exp_finetune}"

echo "请先把 GPU 训练产物 scp 到 ${WORK_DIR}/exp_finetune/:"
echo "  scp -P ${GPU_PORT} -r ${GPU_HOST}:${GPU_DIR} ${WORK_DIR}/exp_finetune"
read -p "确认已下载? (y/n) " -n 1 -r; echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then exit 1; fi

echo "=== Step 2: 导出 ONNX ==="
cd ${WORK_DIR}/exp_finetune
python /root/icefall/egs/wenetspeech/KWS/zipformer/export-onnx-streaming.py \
    --epoch 20 --avg 2 \
    --exp-dir . \
    --causal 1 \
    --chunk-size 16 --left-context-frames 64 \
    --tokens /root/icefall/egs/wenetspeech/KWS/data/lang_partial_tone/tokens.txt

echo "=== Step 3: 验证 ONNX metadata ==="
python ${WORK_DIR}/verify_onnx.py \
    encoder-epoch-20-avg-2-chunk-16-left-64.int8.onnx \
    decoder-epoch-20-avg-2-chunk-16-left-64.int8.onnx \
    joiner-epoch-20-avg-2-chunk-16-left-64.int8.onnx || exit 1

echo "=== Step 4: 替换 voice-node 模型 ==="
mkdir -p ${MODEL_DIR}
# 备份现役模型 (如有)
[ -d ${LOCAL_BACKUP} ] || cp -r /Volumes/ssd/openclaw-voice-node/models/kws ${LOCAL_BACKUP} 2>/dev/null || true

cp encoder-epoch-20-avg-2-chunk-16-left-64.int8.onnx ${MODEL_DIR}/
cp decoder-epoch-20-avg-2-chunk-16-left-64.int8.onnx ${MODEL_DIR}/
cp joiner-epoch-20-avg-2-chunk-16-left-64.int8.onnx ${MODEL_DIR}/
cp /root/icefall/egs/wenetspeech/KWS/data/lang_partial_tone/tokens.txt ${MODEL_DIR}/
cp ${WORK_DIR}/datasets/${KEYWORD}/keywords.txt ${MODEL_DIR}/

echo "=== Step 5: 重启 voice-node ==="
cd /Volumes/ssd/openclaw-voice-node && ./scripts/start.sh

echo "=== 完成 ==="
echo "喊 '贾维斯' 测试唤醒 + STT + chat + TTS 全链路"