"""
clean_strict.py — 把 dataset/raw + dataset/raw_dl 清洗成一份可信的 dataset/clean

规则:
  1. 只接受能完整解码的图片，统一转 RGB、按 EXIF 摆正
  2. 短边 < 400px 丢弃（模型输入 224，太小的图放大后全是噪点）
  3. 极端长宽比（> 3:1，广告横条）丢弃
  4. sha1 精确去重（全局）
  5. dHash 感知去重（汉明距离 <= 6 视为同一张）
     - 同类重复 → 丢后出现的那张
     - 跨类重复 → 两张都丢，因为标签本身自相矛盾，留着只会污染模型
  6. 输出 dataset/clean/<class>/ 并打印每个类别的数量

用法: python training/clean_strict.py
"""
from __future__ import annotations

import hashlib
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

from _fs import safe_rmtree

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
CURATED = ROOT / "dataset" / "curated"
SOURCES = [CURATED] if CURATED.is_dir() else [
    ROOT / "dataset" / "raw_dl",
    ROOT / "dataset" / "raw",
]
OUT = ROOT / "dataset" / "clean"

MIN_SIDE = 400
MAX_ASPECT = 3.0
DUPE_HAMMING = 6
ALLOWED = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
# 模型输入只有 224，存 1024px 纯属浪费（数据集 267MB -> 90MB）
MAX_SIDE = 768
JPEG_QUALITY = 88


def dhash(im: Image.Image, size: int = 8) -> int:
    g = im.convert("L").resize((size + 1, size), Image.LANCZOS)
    arr = np.asarray(g, dtype=np.int16)
    bits = (arr[:, 1:] > arr[:, :-1]).flatten()
    return int("".join("1" if b else "0" for b in bits), 2)


def hamming(a: int, b: int) -> int:
    return bin(a ^ b).count("1")


def load_image(p: Path) -> Image.Image | None:
    try:
        im = Image.open(p)
        im.load()
        im = ImageOps.exif_transpose(im)
        im = im.convert("RGB")
    except Exception:
        return None
    if min(im.size) < MIN_SIDE:
        return None
    if max(im.size) / max(1, min(im.size)) > MAX_ASPECT:
        return None
    if max(im.size) > MAX_SIDE:
        im.thumbnail((MAX_SIDE, MAX_SIDE), Image.LANCZOS)
    return im


def source_files() -> dict[str, list[Path]]:
    by_cls: dict[str, list[Path]] = {}
    for src in SOURCES:
        if not src.is_dir():
            continue
        for cls_dir in sorted(p for p in src.iterdir() if p.is_dir()):
            # manifest.csv 之类不是类别目录
            if cls_dir.name.startswith(".") or cls_dir.name == "processed":
                continue
            files = [
                p for p in sorted(cls_dir.iterdir())
                if p.is_file() and p.suffix.lower() in ALLOWED
            ]
            by_cls.setdefault(cls_dir.name, []).extend(files)
    return by_cls


def main():
    if OUT.exists():
        safe_rmtree(OUT)
    OUT.mkdir(parents=True)

    by_cls = source_files()
    if not by_cls:
        print(f"!! 没有任何原始图片，先跑 training/fetch_dataset.py")
        return

    seen_sha: set[str] = set()
    # 已保留图片: (class, path, dhash)
    kept: list[tuple[str, Path, int]] = []
    stats = {c: {"read": 0, "bad": 0, "dupe": 0, "cross": 0} for c in by_cls}
    dropped_cross: set[Path] = set()

    for cls in sorted(by_cls):
        for p in by_cls[cls]:
            stats[cls]["read"] += 1
            im = load_image(p)
            if im is None:
                stats[cls]["bad"] += 1
                continue
            sha = hashlib.sha1(p.read_bytes()).hexdigest()
            if sha in seen_sha:
                stats[cls]["dupe"] += 1
                continue
            h = dhash(im)

            dup_of_same = any(c == cls and hamming(hh, h) <= DUPE_HAMMING for c, _, hh in kept)
            if dup_of_same:
                stats[cls]["dupe"] += 1
                continue

            cross = [(c, pp) for c, pp, hh in kept
                     if c != cls and hamming(hh, h) <= DUPE_HAMMING]
            if cross:
                stats[cls]["cross"] += 1
                dropped_cross.add(p)
                continue

            seen_sha.add(sha)
            out_dir = OUT / cls
            out_dir.mkdir(parents=True, exist_ok=True)
            # 保留原文件名：这样 test_tflite 还能回查 curated_report.csv 里的来源/domain
            out_path = out_dir / p.name
            if out_path.exists():
                out_path = out_dir / f"{p.stem}__{len(list(out_dir.glob('*.jpg'))):04d}{p.suffix.lower()}"
            im.save(out_path, "JPEG", quality=JPEG_QUALITY)
            kept.append((cls, out_path, h))

    print("\n=== 清洗结果 ===")
    total = 0
    for cls in sorted(by_cls):
        n = len(list((OUT / cls).glob("*.jpg"))) if (OUT / cls).is_dir() else 0
        total += n
        s = stats[cls]
        print(f"  {cls:11s} kept={n:4d}  "
              f"(读入 {s['read']}, 坏图 {s['bad']}, 重复 {s['dupe']}, 跨类冲突 {s['cross']})")
    print(f"  合计 {total} 张 -> {OUT}")

    sizes = [len(list((OUT / c).glob('*.jpg'))) for c in sorted(by_cls)]
    if sizes and max(sizes) > 0:
        ratio = max(sizes) / max(1, min(sizes))
        if ratio > 1.8:
            print(f"  [warn] 类别不平衡 max/min = {ratio:.2f}，训练时会用 class_weight 补偿")


if __name__ == "__main__":
    main()
