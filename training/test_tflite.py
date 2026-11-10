"""
test_tflite.py — 端到端验证导出的 TFLite 模型

最重要的一点：预处理必须和 Android 的 Classifier.kt 完全一致，否则
"电脑上 90%，手机上乱猜"。

Android 端做的事:
  1. 中心裁剪成正方形（不是拉伸变形）
  2. 双线性缩放到 224x224
  3. 逐像素写入 float32 RGB，范围 [0, 255]
  4. 输入 shape (1, 224, 224, 3)

输出: 准确率 / 每类 precision·recall / 混淆矩阵 / 置信度分布
用法: python training/test_tflite.py [--min-acc 0.85]
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
MODEL_DIR = ROOT / "model"
TEST_DIR = ROOT / "dataset" / "processed" / "test"
CURATED_REPORT = ROOT / "dataset" / "curated_report.csv"
IMG_SIZE = 224

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import tensorflow as tf


def android_preprocess(path: Path) -> np.ndarray:
    im = Image.open(path)
    im = im.convert("RGB")
    w, h = im.size
    side = min(w, h)
    left, top = (w - side) // 2, (h - side) // 2
    im = im.crop((left, top, left + side, top + side))
    im = im.resize((IMG_SIZE, IMG_SIZE), Image.BILINEAR)
    arr = np.asarray(im, dtype=np.float32)  # [0, 255]
    return arr[np.newaxis, ...]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=str(MODEL_DIR / "ginseng_model.tflite"))
    ap.add_argument("--min-acc", type=float, default=0.0,
                    help="低于该准确率则 exit 1（CI 用）")
    ap.add_argument("--dump", default=str(MODEL_DIR / "eval_tflite.json"))
    args = ap.parse_args()

    labels = (MODEL_DIR / "labels.txt").read_text(encoding="utf-8").strip().splitlines()
    itp = tf.lite.Interpreter(model_path=args.model)
    itp.allocate_tensors()
    in_det = itp.get_input_details()[0]
    out_det = itp.get_output_details()[0]
    print(f"model  : {args.model}")
    print(f"input  : {in_det['shape'].tolist()} {in_det['dtype'].__name__}")
    print(f"output : {out_det['shape'].tolist()} {out_det['dtype'].__name__}")
    print(f"labels : {labels}")

    assert in_det["shape"].tolist() == [1, IMG_SIZE, IMG_SIZE, 3], "输入 shape 不符合端上约定"
    assert in_det["dtype"] == np.float32, "输入 dtype 不是 float32"
    assert out_det["shape"].tolist()[0] == 1 and out_det["shape"].tolist()[1] == len(labels), \
        "输出维度与 labels.txt 不一致"

    n = len(labels)
    cm = np.zeros((n, n), dtype=int)
    confs_ok, confs_bad = [], []
    rows = []

    # 回查每张图的拍摄场景（整株植物 vs 干燥根/切片），用来分开报告准确率
    domain_of: dict[tuple[str, str], str] = {}
    if CURATED_REPORT.exists():
        with CURATED_REPORT.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                if r.get("action") == "keep":
                    domain_of[(r["class"], r["file"])] = r.get("domain", "?")

    for cls_dir in sorted(TEST_DIR.iterdir()):
        if not cls_dir.is_dir():
            continue
        true = cls_dir.name
        if true not in labels:
            print(f"[warn] 跳过未在 labels.txt 中的目录 {true}")
            continue
        for p in sorted(cls_dir.iterdir()):
            if p.suffix.lower() not in {".jpg", ".jpeg", ".png", ".webp"}:
                continue
            x = android_preprocess(p)
            itp.set_tensor(in_det["index"], x)
            itp.invoke()
            y = itp.get_tensor(out_det["index"])[0].astype(np.float32)
            pred = labels[int(np.argmax(y))]
            cm[labels.index(true), labels.index(pred)] += 1
            conf = float(np.max(y))
            domain = domain_of.get((true, p.name), "?")
            rows.append({"file": f"{true}/{p.name}", "true": true, "domain": domain,
                         "pred": pred, "conf": round(conf, 4)})
            (confs_ok if pred == true else confs_bad).append(conf)

    total = int(cm.sum())
    correct = int(cm.diagonal().sum())
    acc = correct / total if total else 0.0

    print(f"\n=== 结果 ({total} 张) ===")
    print(f"准确率: {acc:.4f}  ({correct}/{total})")
    print("\n混淆矩阵 (行=真实, 列=预测):")
    print("            " + "".join(f"{c:>12}" for c in labels))
    for i, c in enumerate(labels):
        print(f"{c:>12}" + "".join(f"{cm[i, j]:>12d}" for j in range(n)))

    print("\n每类指标:")
    per_class = {}
    for i, c in enumerate(labels):
        tp = int(cm[i, i])
        fn = int(cm[i].sum() - tp)
        fp = int(cm[:, i].sum() - tp)
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
        per_class[c] = {"precision": round(prec, 4), "recall": round(rec, 4),
                        "f1": round(f1, 4), "support": int(cm[i].sum())}
        print(f"  {c:11s} P={prec:.3f}  R={rec:.3f}  F1={f1:.3f}  n={int(cm[i].sum())}")

    print("\n按拍摄场景拆分（整株植物 vs 干燥根/切片）:")
    by_domain: dict[str, list[int]] = {}
    for r in rows:
        d = r["domain"]
        by_domain.setdefault(d, [0, 0])
        by_domain[d][0] += 1
        if r["pred"] == r["true"]:
            by_domain[d][1] += 1
    for d, (tot, ok) in sorted(by_domain.items()):
        name = {"plant": "整株/叶片", "root": "干燥根/切片", "?": "未标注"}.get(d, d)
        print(f"  {name:12s} {ok:4d}/{tot:<4d} = {ok / tot:.3f}")

    mean_ok = float(np.mean(confs_ok)) if confs_ok else 0.0
    mean_bad = float(np.mean(confs_bad)) if confs_bad else 0.0
    print(f"\n置信度: 预测正确均值={mean_ok:.3f}  预测错误均值={mean_bad:.3f}")
    for thr in (0.5, 0.6, 0.7, 0.8):
        low = [r for r in rows if r["conf"] < thr]
        wrong_high = [r for r in rows if r["conf"] >= thr and r["pred"] != r["true"]]
        print(f"  阈值 {thr:.1f}: 会被判为「不确定」{len(low):3d}/{total}"
              f"   高置信度但错误 {len(wrong_high):3d}")

    result = {
        "model": str(Path(args.model).relative_to(ROOT)),
        "accuracy": round(acc, 4),
        "total": total,
        "labels": labels,
        "confusion_matrix": cm.tolist(),
        "per_class": per_class,
        "confidence": {"correct_mean": round(mean_ok, 4),
                       "wrong_mean": round(mean_bad, 4)},
        "files": rows,
    }
    Path(args.dump).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n明细 -> {args.dump}")

    if acc < args.min_acc:
        print(f"!! 准确率 {acc:.4f} 低于要求 {args.min_acc}")
        sys.exit(1)


if __name__ == "__main__":
    main()
