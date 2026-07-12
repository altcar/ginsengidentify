"""
prepare_data.py — 把 dataset/raw/<class>/* 切分成 train/val/test

输出: dataset/processed/{train,val,test}/<class>/*
比例: 70 / 15 / 15
策略: 随机抽样（种子固定），保持类的分布
"""
import argparse
import random
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "dataset" / "raw"
OUT = ROOT / "dataset" / "processed"
SPLITS = ("train", "val", "test")
RATIOS = {"train": 0.70, "val": 0.15, "test": 0.15}
ALLOWED_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def list_class_imgs(cls_dir: Path) -> list[Path]:
    if not cls_dir.exists():
        return []
    return sorted(p for p in cls_dir.iterdir()
                  if p.is_file() and p.suffix.lower() in ALLOWED_EXTS)


def split_indices(n: int, ratios: dict) -> dict:
    rng = random.Random(42)
    idx = list(range(n))
    rng.shuffle(idx)
    out = {"train": [], "val": [], "test": []}
    cut_train = int(n * ratios["train"])
    cut_val = cut_train + int(n * ratios["val"])
    out["train"] = idx[:cut_train]
    out["val"] = idx[cut_train:cut_val]
    out["test"] = idx[cut_val:]
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    if not RAW.exists():
        print(f"!! 找不到原始目录 {RAW}，先跑 fetch_images.py")
        return

    if OUT.exists():
        shutil.rmtree(OUT)
    for s in SPLITS:
        (OUT / s).mkdir(parents=True)

    classes = sorted(p.name for p in RAW.iterdir() if p.is_dir())
    print(f"发现类别: {classes}")

    summary = {}
    for cls in classes:
        imgs = list_class_imgs(RAW / cls)
        n = len(imgs)
        if n == 0:
            print(f"  [warn] {cls}: 0 张图，跳过")
            continue
        splits = split_indices(n, RATIOS)
        for s, idxs in splits.items():
            target = OUT / s / cls
            target.mkdir(parents=True, exist_ok=True)
            for i in idxs:
                shutil.copy2(imgs[i], target / imgs[i].name)
        summary[cls] = {s: len(splits[s]) for s in SPLITS}
        print(f"  {cls}: total={n}, "
              + ", ".join(f"{s}={len(splits[s])}" for s in SPLITS))

    print("\n=== 数据准备完成 ===")
    print(f"  输出: {OUT}")
    print(f"  类别数: {len(classes)}  ->  {classes}")
    if OUT.joinpath("train").exists():
        total_train = sum(1 for _ in OUT.glob("train/*/*"))
        total_val = sum(1 for _ in OUT.glob("val/*/*"))
        total_test = sum(1 for _ in OUT.glob("test/*/*"))
        print(f"  train: {total_train}, val: {total_val}, test: {total_test}")


if __name__ == "__main__":
    main()
