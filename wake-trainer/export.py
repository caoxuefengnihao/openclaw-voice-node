#!/usr/bin/env python3
"""
export.py -- 导出 encoder.onnx (CTC Zipformer, 兼容 sherpa-onnx KWS)

输入: encoder.pt (训练好的 PyTorch state_dict)
输出: model/encoder.onnx + model/tokens.txt + model/keywords.txt
      (直接放进 wake-trainer/model/,不碰现有 models/kws/)

兼容 sherpa-onnx 的 OnlineZipformer2CtcModelConfig:
- 输入: (B, T, 80) fbank 特征 (16kHz, 25ms window, 10ms shift)
- 输出: (B, T, V) logits

注意: 简化版模型架构跟 icefall 完整 zipformer 不同,sherpa-onnx 加载可能需要手动配置
      优先看测试结果再说
"""
import json, sys
from pathlib import Path
import torch
import torch.nn as nn
import numpy as np

ROOT = Path("/Volumes/ssd/openclaw-voice-node/wake-trainer/datasets/jiavs")
MODEL_OUT = ROOT / "model"
MODEL_OUT.mkdir(parents=True, exist_ok=True)

# ---- 读 tokens ----
TOKENS = [l.strip() for l in (ROOT / "data/lang/tokens.txt").read_text().splitlines() if l.strip()]
VOCAB_SIZE = len(TOKENS)
print(f"📦 vocab: {VOCAB_SIZE} tokens: {TOKENS}")

# ---- 模型架构 (跟 train.py 一致) ----
import math

class PosEnc(nn.Module):
    def __init__(self, d_model, max_len=5000):
        super().__init__()
        pe = torch.zeros(max_len, d_model)
        pos = torch.arange(0, max_len).unsqueeze(1).float()
        div = torch.exp(torch.arange(0, d_model, 2).float() * -(math.log(10000.0) / d_model))
        pe[:, 0::2] = torch.sin(pos * div)
        pe[:, 1::2] = torch.cos(pos * div)
        self.register_buffer("pe", pe)
    def forward(self, x):
        return x + self.pe[:x.size(1)].unsqueeze(0)

class ConvSubsampling(nn.Module):
    def __init__(self, in_dim, out_dim):
        super().__init__()
        self.conv = nn.Sequential(
            nn.Conv2d(1, out_dim, 3, stride=2, padding=1),
            nn.ReLU(),
            nn.Conv2d(out_dim, out_dim, 3, stride=2, padding=1),
            nn.ReLU(),
        )
        self.linear = nn.Linear(out_dim * 20, out_dim)

    def forward(self, x, lens):
        B, T, F = x.shape
        x = x.unsqueeze(1)
        x = self.conv(x)
        B, C, T2, F2 = x.shape
        x = x.permute(0, 2, 1, 3).contiguous().view(B, T2, C * F2)
        x = self.linear(x)
        new_lens = torch.clamp(((lens - 1) // 2 + 1 - 1) // 2 + 1, min=1, max=T2)
        return x, new_lens

class ZipformerBlock(nn.Module):
    def __init__(self, d_model, nhead=4, ff=512, dropout=0.1):
        super().__init__()
        self.norm1 = nn.LayerNorm(d_model)
        self.attn = nn.MultiheadAttention(d_model, nhead, dropout=dropout, batch_first=True)
        self.norm2 = nn.LayerNorm(d_model)
        self.ff = nn.Sequential(
            nn.Linear(d_model, ff),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(ff, d_model),
        )
        self.dropout = nn.Dropout(dropout)
    def forward(self, x):
        h = self.norm1(x)
        a, _ = self.attn(h, h, h, need_weights=False)
        x = x + self.dropout(a)
        x = x + self.dropout(self.ff(self.norm2(x)))
        return x

class ZipformerKWS(nn.Module):
    def __init__(self, in_dim=80, d_model=256, n_layers=4, nhead=4, ff=512, vocab_size=9):
        super().__init__()
        self.subsampling = ConvSubsampling(in_dim, d_model)
        self.pos_enc = PosEnc(d_model)
        self.layers = nn.ModuleList([ZipformerBlock(d_model, nhead, ff) for _ in range(n_layers)])
        self.norm_out = nn.LayerNorm(d_model)
        self.ctc_head = nn.Linear(d_model, vocab_size)
    def forward(self, x, lens):
        x, lens = self.subsampling(x, lens)
        x = self.pos_enc(x)
        for layer in self.layers:
            x = layer(x)
        x = self.norm_out(x)
        logits = self.ctc_head(x)
        return logits

# ---- 加载权重 ----
device = torch.device("cpu")  # 导出时用 CPU 即可
model = ZipformerKWS(vocab_size=VOCAB_SIZE).to(device)
state_dict = torch.load(ROOT / "model/encoder.pt", map_location=device, weights_only=True)
model.load_state_dict(state_dict)
model.eval()
print(f"✅ 加载训练权重")

# ---- 导出 ONNX ----
# dummy input: (B=1, T=200, 80) fbank + lens
dummy_feat = torch.randn(1, 200, 80)
dummy_lens = torch.tensor([200], dtype=torch.long)
onnx_path = MODEL_OUT / "encoder.onnx"

print(f"📤 导出 ONNX -> {onnx_path}")
torch.onnx.export(
    model,
    (dummy_feat, dummy_lens),
    onnx_path.as_posix(),
    input_names=["features", "feature_lengths"],
    output_names=["logits"],
    dynamic_axes={
        "features": {0: "N", 1: "T"},
        "feature_lengths": {0: "N"},
        "logits": {0: "N", 1: "T_out"},
    },
    opset_version=14,
    do_constant_folding=True,
)
print(f"✅ ONNX 导出完成: {onnx_path} ({onnx_path.stat().st_size / 1024:.1f} KB)")

# ---- 复制配套文件 ----
import shutil
shutil.copy(ROOT / "data/lang/tokens.txt", MODEL_OUT / "tokens.txt")
keywords_src = ROOT / "data/lang/keywords.txt"
keywords_dst = MODEL_OUT / "keywords.txt"
# 简化 keywords.txt:只保留 "jiavs" 一行 (含 tokens + @汉字)
keywords_dst.write_text("j i a v s @jiavs\n")
print(f"✅ keywords.txt: {keywords_dst}")
print(f"✅ tokens.txt: {MODEL_OUT / 'tokens.txt'}")

# ---- 输出汇总 ----
print(f"\n📦 模型包:")
for f in sorted(MODEL_OUT.iterdir()):
    print(f"   {f.name}: {f.stat().st_size / 1024:.1f} KB")