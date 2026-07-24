# Wake Word Trainer (v3.1)

桌面机器人 / voice-node 的**唤醒词自训练工具链**。换关键词只需 4 步：

```
改关键词 → 录音 → 训练 → 部署
```

---

## 快速开始 (jiavs)

```bash
# 1. 改关键词 (新建一个 txt 文件)
echo "jiavs" > wake-trainer/keywords/jiavs.txt

# 2. 浏览器打开录音页
# http://localhost:5175/wake-trainer/record.html?keyword=jiavs
# 录 100 次 (约 6-8 分钟,自动化)

# 3. 下载 zip, 解压到
unzip jiavs_recordings_*.zip -d wake-trainer/datasets/jiavs/wav/

# 4. 训练 + 部署 (一键)
cd wake-trainer
./deploy.sh jiavs

# 5. 验证: 重启后端,喊 "jiavs" 看是否触发
```

---

## 目录结构

```
wake-trainer/
├── record.html             # 浏览器录音页面 (URL 参数 ?keyword=xxx)
├── keywords/               # 关键词定义 (每个换的词一个 txt)
│   ├── jiavs.txt
│   └── (未来) alfred.txt
├── datasets/               # 录音数据 (gitignore)
│   └── jiavs/
│       ├── wav/            # 100 个 .wav 文件
│       └── train/          # 训练数据 (prepare.py 生成)
├── prepare.py              # wav → kaldi 格式数据准备 (TODO)
├── train.py                # icefall 训练 (TODO)
├── export.py               # 导出 ONNX (TODO)
├── deploy.sh               # 一键:训练 → 导出 → 替换 → 重启 (TODO)
└── README.md               # 本文件
```

---

## 录音页面用法

打开:
```
http://localhost:5175/wake-trainer/record.html?keyword=jiavs
```

- 自动用浏览器 `getUserMedia` 拿 mic
- BT 麦坑已处理: `echoCancellation: false`
- 自动循环 100 次,每次 1.5 秒 + 1 秒间隔
- 完成后下载 zip

如果服务跑在别的端口,把 URL 换一下。

---

## 录音数据规范

每个 wav 文件要求:
- ✅ **16kHz mono int16 LE** (浏览器自动处理)
- ✅ **长度 0.5-2 秒** (1.5 秒最佳)
- ✅ **包含完整关键词 + 自然停顿**
- ❌ **不要纯静音 / 不要音乐 / 不要其他人声音**

---

## 切换关键词 (未来)

```bash
# 加新关键词
echo "alfred" > keywords/alfred.txt

# 录新数据
http://localhost:5175/wake-trainer/record.html?keyword=alfred

# 训练 + 部署
./deploy.sh alfred

# 旧 jiavs 模型保留在 datasets/,可来回切换
```

---

## 服务启动 (录音页用)

```bash
cd wake-trainer
python3 -m http.server 5175
```

或在 voice-node 根目录:
```bash
python3 -m http.server 5175 --directory wake-trainer
```

---

## 跟 voice-node 集成

训练输出会替换:
```
models/kws-pretrained/         # 备份预训练模型
models/kws/                    # 当前使用的 (训练后的)
├── encoder-*.int8.onnx
├── decoder-*.int8.onnx
├── joiner-*.int8.onnx
├── tokens.txt
└── keywords.txt
```

`application.yml` 不需要改 (路径不变)。

---

## TODO

- [x] 录音页面 (record.html)
- [x] 关键词配置 (keywords/*.txt)
- [ ] 数据准备脚本 (prepare.py)
- [ ] 训练脚本 (train.py) — 用 icefall
- [ ] ONNX 导出 (export.py)
- [ ] 一键部署 (deploy.sh)
- [ ] README 加冰山 (icefall) 装步骤
- [ ] 误触发率自测脚本

---

## 相关 commit

- `feat(voice): v3 KWS 唤醒词集成设计文档` (Phase 1-3)
- `feat(kws): 唤醒词自训练工具链` (v3.1 本目录)