#!/usr/bin/env python3
"""验证导出的 ONNX 模型 metadata 是否完整 (昨晚踩过的坑)"""
import sys
import onnx

REQUIRED = [
    ("model_type", "zipformer2"),
    ("T", "45"),
    ("decode_chunk_len", "32"),
]

def verify(path):
    print(f"\n=== {path} ===")
    try:
        m = onnx.load(path)
    except Exception as e:
        print(f"❌ LOAD FAILED: {e}")
        return False
    props = {p.key: p.value for p in m.metadata_props}
    print(f"metadata_props: {props}")
    ok = True
    for key, expected in REQUIRED:
        actual = props.get(key, None)
        if actual is None:
            print(f"❌ 缺字段: {key}")
            ok = False
        elif str(actual) != expected:
            print(f"⚠️  字段 {key}: 期望 {expected!r}, 实际 {actual!r}")
            ok = False
        else:
            print(f"✅ {key}={actual}")
    return ok

if __name__ == "__main__":
    files = sys.argv[1:] or [
        "encoder-epoch-20-avg-2-chunk-16-left-64.int8.onnx",
    ]
    all_ok = True
    for f in files:
        all_ok &= verify(f)
    print(f"\n{'✅ ALL PASS' if all_ok else '❌ SOME FAILED'}")
    sys.exit(0 if all_ok else 1)