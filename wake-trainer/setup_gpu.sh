#!/bin/bash
# 在 AutoDL 4090 容器内运行: 装环境 + 下预训练底座
# 用法: bash setup_gpu.sh
set -e

echo "=== Step 1: 装依赖 ==="
pip install k2 -f https://k2-fsa.github.io/k2/cpu.html 1>/dev/null || {
  echo "k2 cpu index 失败, 尝试 torch index..."
  pip install k2 -f https://k2-fsa.github.io/k2/torch.html
}
pip install lhotse icefall 2>&1 | tail -3

echo "=== Step 2: 下预训练底座 ==="
mkdir -p /root/pretrained
cd /root/pretrained
if [ ! -d "icefall-kws-zipformer-wenetspeech-20240219" ]; then
  wget -q https://github.com/pkufool/keyword-spotting-models/releases/download/v0.11/icefall-kws-zipformer-wenetspeech-20240219.tar.gz
  tar -xzf icefall-kws-zipformer-wenetspeech-20240219.tar.gz
fi
ls /root/pretrained/exp/12-epoch.avg-2.pt && echo "预训练底座 OK"

echo "=== Step 3: 解压训练数据 (scp 上传后再解压) ==="
ls ~/kws_train_jarvis.tar.gz || { echo "请先 scp 上传 ~/kws_train_jarvis.tar.gz"; exit 1; }
mkdir -p /root/jarvis_data
tar -xzf ~/kws_train_jarvis.tar.gz -C /root/jarvis_data/
ls /root/jarvis_data/cuts.jsonl && echo "训练数据 OK"

echo "=== Step 4: 克隆 icefall (如不存在) ==="
if [ ! -d "/root/icefall" ]; then
  git clone --depth 1 https://github.com/k2-fsa/icefall.git /root/icefall
fi
export PYTHONPATH=/root/icefall:$PYTHONPATH
echo 'export PYTHONPATH=/root/icefall:$PYTHONPATH' >> ~/.bashrc

echo "=== 全部完成 ==="
echo "接下来: bash train.sh"