#!/bin/bash
# 启动微调训练 (20 epochs, ~4 小时)
set -e
export PYTHONPATH=/root/icefall:$PYTHONPATH
cd /root/icefall/egs/wenetspeech/KWS

python ./zipformer/finetune.py \
    --world-size 1 \
    --exp-dir zipformer/exp_finetune \
    --use-fp16 1 \
    --num-epochs 20 \
    --lr-epochs 2 \
    --start-epoch 1 \
    --pinyin-type partial_with_tone \
    --causal 1 \
    --lang-dir data/lang_partial_tone \
    --max-duration 200 \
    --checkpoint /root/pretrained/exp/12-epoch.avg-2.pt \
    --train-cut /root/jarvis_data/cuts.jsonl \
    --valid-cut /root/jarvis_data/cuts.jsonl 2>&1 | tee /root/train.log