#!/usr/bin/env python3
"""
prepare.py -- 把 jiavs wav 录音转成 icefall KWS 训练格式

输入: wake-trainer/datasets/jiavs/wav/001_jiavs.wav ... 100_jiavs.wav
输出: wake-trainer/datasets/jiavs/train/
   - wav.scp          (wav 路径列表)
   - text             (每行的 label: "jiavs" 或 "<NEG>" 负样本)
   - keywords.txt     (jiavs)
   - segments         (utterance_id, start, duration)
   - utt2dur          (utterance_id -> duration)
"""
import os
import json
import random
from pathlib import Path
import soundfile as sf

random.seed(42)

ROOT = Path("/Volumes/ssd/openclaw-voice-node/wake-trainer/datasets/jiavs")
WAV_DIR = ROOT / "wav"
OUT_DIR = ROOT / "train"
OUT_DIR.mkdir(parents=True, exist_ok=True)

KEYWORD = "jiavs"

# 1. 收集所有 jiavs 正样本 (100 个)
pos_files = sorted(WAV_DIR.glob(f"*_{KEYWORD}.wav"))
print(f"正样本 (jiavs): {len(pos_files)} 个")

if len(pos_files) < 50:
    raise RuntimeError(f"正样本太少 ({len(pos_files)}), 需要至少 50 个")

# 2. 收集负样本 (从其他公开数据集,或用静音/杂音)
#    这里简化:从 MUSAN 等数据集下载会很重,先用 OpenClaw 录音数据里的"非唤醒词"做负样本
#    实际 icefall 支持 kaldi-style data dir,我们可以先只跑正样本训练,负样本用数据增强生成
#    但冰山岭 (icefall) KWS 训练标准做法是同时需要正负样本
#    简化: 先只用正样本,后期加噪声

# 3. 生成 train/val/test split (80/10/10)
random.shuffle(pos_files)
n = len(pos_files)
train_files = pos_files[:int(n * 0.8)]
val_files = pos_files[int(n * 0.8):int(n * 0.9)]
test_files = pos_files[int(n * 0.9):]

print(f"split: train={len(train_files)}, val={len(val_files)}, test={len(test_files)}")

# 4. 写文件
def write_split(files, split_name):
    wav_scp = OUT_DIR / f"wav_{split_name}.scp"
    text = OUT_DIR / f"text_{split_name}"
    utt2dur = OUT_DIR / f"utt2dur_{split_name}"
    
    with open(wav_scp, "w") as f1, open(text, "w") as f2, open(utt2dur, "w") as f3:
        for wav_path in files:
            utt_id = wav_path.stem  # 001_jiavs
            # wav.scp 格式: utt_id <wav_path>
            f1.write(f"{utt_id} {wav_path.absolute()}\n")
            # text 格式: utt_id <label> (jiavs 是 keyword, label 就是 KEYWORD)
            f2.write(f"{utt_id} {KEYWORD}\n")
            # duration
            try:
                info = sf.info(str(wav_path))
                dur = info.duration
            except Exception:
                dur = 1.5  # fallback
            f3.write(f"{utt_id} {dur:.3f}\n")
    
    print(f"  {split_name}: {len(files)} 个 -> {wav_scp.parent}")

write_split(train_files, "train")
write_split(val_files, "val")
write_split(test_files, "test")

# 5. 写 keywords.txt (icefall 需要)
(OUT_DIR / "keywords.txt").write_text(f"{KEYWORD}\n")
print(f"\n✅ 数据准备完成 -> {OUT_DIR}")
print(f"   wav 路径: {WAV_DIR}")
print(f"   输出: {OUT_DIR}")
