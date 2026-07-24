#!/usr/bin/env bash
# prepare.sh -- jiavs 数据准备 (fbank + 词典)
# 基于 icefall wenetspeech/KWS 模板,简化适配 100 样本场景
#
# 输入: wake-trainer/datasets/jiavs/wav/*.wav
# 输出: wake-trainer/datasets/jiavs/data/{fbank,lang,egs}/

set -euo pipefail

ROOT="/Volumes/ssd/openclaw-voice-node/wake-trainer/datasets/jiavs"
DATA="$ROOT/data"
WAV_DIR="$ROOT/wav"
TRAIN_DIR="$ROOT/train"
KEYWORD="jiavs"

mkdir -p "$DATA/fbank" "$DATA/lang" "$DATA/egs"

PY="/Users/caoxuefeng/anaconda3/envs/wake-trainer/bin/python"

echo "=== $(date '+%H:%M:%S') Step 1: 提取 fbank 特征 (80-dim, sherpa-onnx 兼容) ==="
"$PY" - <<'PYEOF'
from pathlib import Path
import json
import torch
import torchaudio
import numpy as np

ROOT = Path("/Volumes/ssd/openclaw-voice-node/wake-trainer/datasets/jiavs")
WAV_DIR = ROOT / "wav"
FBANK_DIR = ROOT / "data" / "fbank"
FBANK_DIR.mkdir(parents=True, exist_ok=True)

manifest = []
for wav_path in sorted(WAV_DIR.glob("*.wav")):
    utt_id = wav_path.stem
    waveform, sr = torchaudio.load(str(wav_path))
    assert sr == 16000, f"{wav_path}: sample rate {sr}"

    # 80-dim fbank, 25ms window, 10ms shift (跟 sherpa-onnx KWS 配置一致)
    feats = torchaudio.compliance.kaldi.fbank(
        waveform=waveform,
        sample_frequency=16000,
        num_mel_bins=80,
        frame_length=25.0,
        frame_shift=10.0,
        use_energy=False,
    )  # (T, 80)

    out_path = FBANK_DIR / f"{utt_id}.npy"
    np.save(out_path, feats.numpy().astype(np.float32))
    manifest.append({
        "utt_id": utt_id,
        "fbank_path": str(out_path),
        "frames": int(feats.shape[0]),
        "label": "jiavs",
    })

with open(FBANK_DIR / "manifest.json", "w") as f:
    json.dump(manifest, f, indent=2)

print(f"✅ 提取 {len(manifest)} 个 fbank")
print(f"   帧数范围: {min(m['frames'] for m in manifest)} - {max(m['frames'] for m in manifest)}")
PYEOF

echo ""
echo "=== $(date '+%H:%M:%S') Step 2: 生成 lang dir (tokens + keywords) ==="
mkdir -p "$DATA/lang"

# tokens.txt: sherpa-onnx 风格,每行一个 token
# 包含 jiavs 关键词 + 必要的 special tokens
cat > "$DATA/lang/tokens.txt" <<EOF
<blk>
j
i
a
v
s
</s>
<s>
EOF

# keywords.txt: 拼音 tokens @汉字 (跟 sherpa-onnx 现有格式一致)
cat > "$DATA/lang/keywords.txt" <<EOF
j i a v s @${KEYWORD}
EOF

echo "tokens.txt:"
cat "$DATA/lang/tokens.txt"
echo ""
echo "keywords.txt:"
cat "$DATA/lang/keywords.txt"

echo ""
echo "=== $(date '+%H:%M:%S') Step 3: 复制 train/val/test 列表 ==="
cp "$TRAIN_DIR/wav_train.scp" "$DATA/egs/wav_train.scp"
cp "$TRAIN_DIR/wav_val.scp"   "$DATA/egs/wav_val.scp"
cp "$TRAIN_DIR/wav_test.scp"  "$DATA/egs/wav_test.scp"
cp "$TRAIN_DIR/text_train"    "$DATA/egs/text_train"
cp "$TRAIN_DIR/text_val"      "$DATA/egs/text_val"
cp "$TRAIN_DIR/text_test"     "$DATA/egs/text_test"
cp "$TRAIN_DIR/utt2dur_train" "$DATA/egs/utt2dur_train"
cp "$TRAIN_DIR/utt2dur_val"   "$DATA/egs/utt2dur_val"
cp "$TRAIN_DIR/utt2dur_test"  "$DATA/egs/utt2dur_test"

echo "✅ 数据准备完成"
ls "$DATA/"