#!/bin/bash
# Mac mini 本地训练 (M4 Pro + MPS) - 适配版本
set -e
source /Users/caoxuefeng/anaconda3/etc/profile.d/conda.sh
conda activate wake-trainer
export PYTHONPATH=/Volumes/ssd/icefall:$PYTHONPATH
cd /Volumes/ssd/icefall/egs/wenetspeech/KWS

# Mac mini 优化参数 (比 4090 弱, 减少 epoch + max-duration 降负载)
python ./zipformer/finetune.py \
    --world-size 1 \
    --exp-dir zipformer/exp_finetune_mac \
    --use-fp16 0 \
    --num-epochs 15 \
    --lr-epochs 2 \
    --start-epoch 1 \
    --pinyin-type partial_with_tone \
    --causal 1 \
    --lang-dir /Volumes/ssd/openclaw-voice-node/wake-trainer/pretrained/icefall-kws-zipformer-wenetspeech-20240219/data/lang_partial_tone \
    --max-duration 60 \
    --on-the-fly-feats 1 \
    --enable-musan 0 \
    --decoder-dim 320 \
    --joiner-dim 320 \
    --num-encoder-layers 1,1,1,1,1,1 \
    --feedforward-dim 192,192,192,192,192,192 \
    --encoder-dim 128,128,128,128,128,128 \
    --encoder-unmasked-dim 128,128,128,128,128,128 \
    --finetune-ckpt /Volumes/ssd/openclaw-voice-node/wake-trainer/pretrained/icefall-kws-zipformer-wenetspeech-20240219/exp/pretrained.pt 2>&1 | tee /Volumes/ssd/openclaw-voice-node/wake-trainer/train_mac.log