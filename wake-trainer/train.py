#!/usr/bin/env python3
"""
train.py -- jiavs 自训练 KWS (CTC Zipformer, 100 样本适配版)

简化版 icefall wenetspeech/KWS 训练,针对 100 样本小数据集:
- 模型: 小型 Zipformer (2 层 + 256 dim),CTC 损失
- 输出: encoder.onnx + tokens.txt + keywords.txt
- 兼容 sherpa-onnx KeywordSpotter (OnlineZipformer2CtcModelConfig)

用法:
    /Users/caoxuefeng/anaconda3/envs/wake-trainer/bin/python train.py
"""
import os, sys, json, time, math, random
from pathlib import Path
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader

# ---- 路径 ----
ROOT = Path("/Volumes/ssd/openclaw-voice-node/wake-trainer/datasets/jiavs")
DATA = ROOT / "data"
FBANK_DIR = DATA / "fbank"
EGS = DATA / "egs"
LANG = DATA / "lang"
EXP = ROOT / "exp"
EXP.mkdir(parents=True, exist_ok=True)
MODEL_OUT = ROOT / "model"  # 训练输出,不碰现有 models/kws
MODEL_OUT.mkdir(parents=True, exist_ok=True)

# 读 manifest
with open(FBANK_DIR / "manifest.json") as f:
    MANIFEST = json.load(f)
print(f"📦 {len(MANIFEST)} 个 fbank 样本")

# 读 tokens
with open(LANG / "tokens.txt") as f:
    TOKENS = [l.strip() for l in f if l.strip()]
TOKEN2ID = {t: i for i, t in enumerate(TOKENS)}
ID2TOKEN = {i: t for i, t in enumerate(TOKENS)}
print(f"📦 vocab: {len(TOKENS)} tokens: {TOKENS}")

# 关键词 token 序列 (target for CTC)
KEYWORD = "jiavs"
TARGET_TOKENS = ["j", "i", "a", "v", "s"]  # 对应 tokens.txt
TARGET_IDS = [TOKEN2ID[t] for t in TARGET_TOKENS]
print(f"🎯 target: {TARGET_TOKENS} -> IDs {TARGET_IDS}")

# ---- 数据集 ----
class KWSDataset(Dataset):
    def __init__(self, items, max_frames=200):
        self.items = items
        self.max_frames = max_frames

    def __len__(self):
        return len(self.items)

    def __getitem__(self, idx):
        item = self.items[idx]
        feat = np.load(item["fbank_path"])  # (T, 80)
        T = min(feat.shape[0], self.max_frames)
        feat = feat[:T]

        # CTC target: jiavs (5 tokens)
        target = torch.tensor(TARGET_IDS, dtype=torch.long)

        return {
            "feat": torch.from_numpy(feat).float(),
            "T": T,
            "target": target,
            "target_len": len(target),
            "utt_id": item["utt_id"],
        }

def collate(batch):
    feats = [b["feat"] for b in batch]
    targets = [b["target"] for b in batch]
    feat_lens = torch.tensor([b["T"] for b in batch])
    target_lens = torch.tensor([b["target_len"] for b in batch])
    # Pad feats to max T
    max_T = max(b["T"] for b in batch)
    feat_dim = feats[0].shape[1]
    padded = torch.zeros(len(batch), max_T, feat_dim)
    for i, f in enumerate(feats):
        padded[i, :f.shape[0]] = f
    return padded, feat_lens, torch.cat(targets), target_lens

# 拆分 train/val/test
import random
random.seed(42)
random.shuffle(MANIFEST)
train_items = MANIFEST[:80]
val_items = MANIFEST[80:90]
test_items = MANIFEST[90:]
print(f"📊 split: train={len(train_items)}, val={len(val_items)}, test={len(test_items)}")

train_ds = KWSDataset(train_items)
val_ds = KWSDataset(val_items)
test_ds = KWSDataset(test_items)
train_loader = DataLoader(train_ds, batch_size=8, shuffle=True, collate_fn=collate)
val_loader = DataLoader(val_ds, batch_size=8, shuffle=False, collate_fn=collate)
test_loader = DataLoader(test_ds, batch_size=8, shuffle=False, collate_fn=collate)

# ---- 模型: 小型 Zipformer (CTC) ----
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
    """Conv2d subsampling, stride=2 -> 4x 时间下采样"""
    def __init__(self, in_dim, out_dim):
        super().__init__()
        self.conv = nn.Sequential(
            nn.Conv2d(1, out_dim, 3, stride=2, padding=1),
            nn.ReLU(),
            nn.Conv2d(out_dim, out_dim, 3, stride=2, padding=1),
            nn.ReLU(),
        )
        # 80 mel bins -> 40 -> 20
        self.linear = nn.Linear(out_dim * 20, out_dim)

    def forward(self, x, lens):
        # x: (B, T, 80), lens: (B,)
        B, T, F = x.shape
        x = x.unsqueeze(1)  # (B, 1, T, 80)
        x = self.conv(x)    # (B, C, T/4, F/4)
        B, C, T2, F2 = x.shape
        x = x.permute(0, 2, 1, 3).contiguous().view(B, T2, C * F2)
        x = self.linear(x)  # (B, T/4, d_model)
        # 调整 lens
        new_lens = ((lens - 1) // 2 + 1 - 1) // 2 + 1
        new_lens = torch.clamp(new_lens, min=1, max=T2)
        return x, new_lens

class ZipformerBlock(nn.Module):
    """简化 Zipformer block: self-attn + FFN"""
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

    def forward(self, x, mask=None):
        h = self.norm1(x)
        a, _ = self.attn(h, h, h, attn_mask=mask, need_weights=False)
        x = x + self.dropout(a)
        x = x + self.dropout(self.ff(self.norm2(x)))
        return x

class ZipformerKWS(nn.Module):
    """小型 Zipformer encoder + CTC head"""
    def __init__(self, in_dim=80, d_model=256, n_layers=4, nhead=4, ff=512, vocab_size=9):
        super().__init__()
        self.subsampling = ConvSubsampling(in_dim, d_model)
        self.pos_enc = PosEnc(d_model)
        self.layers = nn.ModuleList([
            ZipformerBlock(d_model, nhead, ff) for _ in range(n_layers)
        ])
        self.norm_out = nn.LayerNorm(d_model)
        self.ctc_head = nn.Linear(d_model, vocab_size)

    def forward(self, x, lens):
        # x: (B, T, 80), lens: (B,)
        x, lens = self.subsampling(x, lens)  # (B, T/4, d_model)
        x = self.pos_enc(x)
        # 因果 mask (简化:全连接,实际 Zipformer 是 causal)
        for layer in self.layers:
            x = layer(x)
        x = self.norm_out(x)
        logits = self.ctc_head(x)  # (B, T/4, V)
        return logits

# ---- 训练 ----
device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
print(f"🖥  device: {device}")

model = ZipformerKWS(vocab_size=len(TOKENS)).to(device)
optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-4)
scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=30)
ctc_loss = nn.CTCLoss(blank=TOKEN2ID["<blk>"], zero_infinity=True)

print(f"\n🚀 训练 30 epochs (batch_size=8)")
print(f"{'epoch':>6} {'loss':>10} {'val_loss':>10} {'time':>8}")
print("-" * 40)

best_val = float("inf")
for epoch in range(1, 31):
    t0 = time.time()
    model.train()
    train_loss = 0
    n = 0
    for feats, feat_lens, targets, target_lens in train_loader:
        feats = feats.to(device)
        feat_lens = feat_lens.to(device)
        targets = targets.to(device)
        target_lens = target_lens.to(device)

        logits = model(feats, feat_lens)  # (B, T, V)
        log_probs = F.log_softmax(logits, dim=-1).transpose(0, 1)  # (T, B, V)
        # CTC 期望 (T, B, V), log_probs

        loss = ctc_loss(log_probs, targets, feat_lens // 4, target_lens)
        optimizer.zero_grad()
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        train_loss += loss.item()
        n += 1
    train_loss /= n

    # Val
    model.eval()
    val_loss = 0
    n = 0
    with torch.no_grad():
        for feats, feat_lens, targets, target_lens in val_loader:
            feats = feats.to(device); feat_lens = feat_lens.to(device)
            targets = targets.to(device); target_lens = target_lens.to(device)
            logits = model(feats, feat_lens)
            log_probs = F.log_softmax(logits, dim=-1).transpose(0, 1)
            loss = ctc_loss(log_probs, targets, feat_lens // 4, target_lens)
            val_loss += loss.item(); n += 1
    val_loss /= n

    scheduler.step()
    elapsed = time.time() - t0

    print(f"{epoch:>6} {train_loss:>10.4f} {val_loss:>10.4f} {elapsed:>7.1f}s")

    if val_loss < best_val:
        best_val = val_loss
        torch.save(model.state_dict(), MODEL_OUT / "encoder.pt")
        print(f"   💾 saved best (val_loss={val_loss:.4f})")

print(f"\n✅ 训练完成,best val_loss={best_val:.4f}")
print(f"   模型: {MODEL_OUT}/encoder.pt")

# ---- 测试 ----
print(f"\n🧪 测试 (10 样本):")
model.eval()
correct = 0
total = 0
with torch.no_grad():
    for feats, feat_lens, targets, target_lens in test_loader:
        feats = feats.to(device); feat_lens = feat_lens.to(device)
        logits = model(feats, feat_lens)
        preds = logits.argmax(dim=-1)  # (B, T)
        # 简单评估:对每个样本,看模型输出的去重 token 序列是否包含 jiavs
        for b in range(preds.size(0)):
            T_b = (feat_lens[b] // 4).item()
            seq = preds[b, :T_b].cpu().tolist()
            # 去重连续重复 (CTC greedy decode 简化)
            decoded = []
            prev = -1
            for t in seq:
                if t != prev and t != TOKEN2ID["<blk>"]:
                    decoded.append(t)
                prev = t
            # 检查是否包含目标
            target_set = set(TARGET_IDS)
            decoded_set = set(decoded)
            hit = target_set.issubset(decoded_set) or decoded == TARGET_IDS
            if hit:
                correct += 1
            total += 1
            print(f"  {'✅' if hit else '❌'} {decoded} (want {TARGET_IDS})")
print(f"\n准确率: {correct}/{total} = {correct/total*100:.1f}%")