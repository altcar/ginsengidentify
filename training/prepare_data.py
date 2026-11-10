"""
prepare_data.py — 把 dataset/clean/<class>/* 切成 train/val/test

- 分层抽样，每类单独切 70 / 15 / 15，固定随机种子
- 每类至少保证 1 张 val、1 张 test
- 先跑 training/clean_strict.py（它已经做过跨类去重，切分不会泄漏）

输出: dataset/processed/{train,val,test}/<class>/*
用法: python training/prepare_data.py [--seed 42] [--ratios 0.7,0.15,0.15]
"""
from __future__ import annotations

import argparse
import random
import shutil
import sys
from pathlib import Path

from _fs import safe_rmtree

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
CLEAN = ROOT / "dataset" / "clean"
FALLBACK = ROOT / "dataset" / "raw"
OUT = ROOT / "dataset" / "processed"
SPLITS = ("train", "val", "test")
ALLOWED = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def pick_source() -> Path:
    """只认 dataset/clean。

    旧版这里在 clean 缺失时"退回 dataset/raw"，结果清洗失败时会静默用回
    那 195 张旧图，切出一个 134/27/32 的假数据集，还照样提示"完成"。
    现在宁可报错退出。
    """
    if CLEAN.is_dir() and any(CLEAN.glob("*/*.jpg")):
        return CLEAN
    print(f"!! 找不到 {CLEAN}（或为空），请先跑 clean_strict.py")
    print("   完整顺序: filter_clip.py -> clean_strict.py -> prepare_data.py")
    raise SystemExit(1)


def split_indices(n: int, ratios: tuple[float, float, float], rng: random.Random):
    idx = list(range(n))
    rng.shuffle(idx)
    n_train = max(1, int(n * ratios[0]))
    n_val = max(1, int(n * ratios[1])) if n - n_train > 1 else 0
    n_test = n - n_train - n_val
    if n_test < 1:
        n_test = 1
        n_val = max(0, n - n_train - n_test)
    return {
        "train": idx[:n_train],
        "val": idx[n_train:n_train + n_val],
        "test": idx[n_train + n_val:],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--ratios", default="0.7,0.15,0.15")
    args = ap.parse_args()
    ratios = tuple(float(x) for x in args.ratios.split(","))
    assert len(ratios) == 3

    src = pick_source()
    if not src.is_dir():
        print(f"!! 找不到 {src}")
        return

    if OUT.exists():
        safe_rmtree(OUT)
    for s in SPLITS:
        (OUT / s).mkdir(parents=True)

    classes = sorted(p.name for p in src.iterdir() if p.is_dir())
    print(f"来源: {src}")
    print(f"类别: {classes}")

    summary = {}
    for cls in classes:
        imgs = sorted(p for p in (src / cls).iterdir()
                      if p.is_file() and p.suffix.lower() in ALLOWED)
        n = len(imgs)
        if n == 0:
            print(f"  [warn] {cls}: 0 张，跳过")
            continue
        rng = random.Random(f"{args.seed}-{cls}")
        splits = split_indices(n, ratios, rng)
        for s, idxs in splits.items():
            target = OUT / s / cls
            target.mkdir(parents=True, exist_ok=True)
            for i in idxs:
                shutil.copy2(imgs[i], target / imgs[i].name)
        summary[cls] = {s: len(splits[s]) for s in SPLITS}
        print(f"  {cls:11s} total={n:4d}  "
              + "  ".join(f"{s}={len(splits[s]):4d}" for s in SPLITS))

    print("\n=== 完成 ===")
    for s in SPLITS:
        print(f"  {s}: {sum(1 for _ in OUT.glob(f'{s}/*/*'))}")
    print(f"  输出: {OUT}")

    counts = {c: sum(summary[c].values()) for c in summary}
    if counts:
        lo = min(counts.values())
        hi = max(counts.values())
        if hi / max(1, lo) > 1.8:
            print(f"  [warn] 类别不平衡 {lo}..{hi}，train.py 会用 class_weight 补偿")


if __name__ == "__main__":
    main()
