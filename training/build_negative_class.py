"""
build_negative_class.py — 造一个 "other"（未检测到相关药材）类

为什么必须要有：softmax 三分类永远输出三个概率之和 1，所以拍一个杯子、
一块黑屏、一张白纸，模型照样会挑一个最像的报 93% 人参。
真正修这个问题只能加负类，让模型有"什么都不是"这个出口。

负样本来源：
  1. dataset/quarantine/  —— filter_clip.py 剔掉的噪声图
     （货架、包装盒、鸡汤、招牌、扫描文档、街景……）
  2. 合成图 —— 纯色、渐变、噪声、过暗、过曝、棋盘格
     这些是"相机对着墙/桌面/黑屏"时的真实分布

用法（必须在 clean_strict.py 之后、prepare_data.py 之前跑）:
  python training/clean_strict.py
  python training/build_negative_class.py
  python training/prepare_data.py
  python training/train.py
"""
from __future__ import annotations

import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter, ImageOps

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
QUARANTINE = ROOT / "dataset" / "quarantine"
OUT = ROOT / "dataset" / "clean" / "other"
SIZE = 512

PALETTE = [
    (245, 245, 240), (30, 30, 32), (200, 198, 190), (120, 120, 125),
    (250, 248, 235), (15, 22, 19), (170, 140, 110), (90, 100, 110),
    (230, 220, 200), (60, 55, 50), (140, 160, 150), (255, 255, 255),
]


def save(im: Image.Image, name: str) -> bool:
    try:
        im = im.convert("RGB")
        if max(im.size) > SIZE:
            im.thumbnail((SIZE, SIZE), Image.LANCZOS)
        im.save(OUT / f"{name}.jpg", "JPEG", quality=88)
        return True
    except Exception:
        return False


def from_quarantine() -> int:
    n = 0
    if not QUARANTINE.is_dir():
        return 0
    for cls_dir in sorted(p for p in QUARANTINE.iterdir() if p.is_dir()):
        for p in sorted(cls_dir.iterdir()):
            if p.suffix.lower() not in {".jpg", ".jpeg", ".png", ".webp"}:
                continue
            try:
                im = ImageOps.exif_transpose(Image.open(p)).convert("RGB")
            except Exception:
                continue
            if save(im, f"q_{cls_dir.name}_{p.stem}"):
                n += 1
    return n


def synthetic(count: int = 150) -> int:
    rng = random.Random(7)
    made = 0
    for i in range(count):
        w = h = rng.choice([224, 320, 384, 512])
        kind = i % 6
        if kind == 0:  # 纯色
            color = rng.choice(PALETTE)
            im = Image.new("RGB", (w, h), color)
        elif kind == 1:  # 竖直渐变
            c1, c2 = rng.choice(PALETTE), rng.choice(PALETTE)
            arr = np.zeros((h, w, 3), dtype=np.uint8)
            for y in range(h):
                t = y / max(1, h - 1)
                arr[y, :, :] = [int(c1[k] * (1 - t) + c2[k] * t) for k in range(3)]
            im = Image.fromarray(arr)
        elif kind == 2:  # 高斯噪声
            base = np.array(rng.choice(PALETTE), dtype=np.int16)
            arr = np.clip(base + np.random.normal(0, 28, (h, w, 3)), 0, 255).astype(np.uint8)
            im = Image.fromarray(arr)
        elif kind == 3:  # 极暗（黑屏）
            base = np.array([rng.randint(0, 18)] * 3, dtype=np.int16)
            arr = np.clip(base + np.random.normal(0, 6, (h, w, 3)), 0, 255).astype(np.uint8)
            im = Image.fromarray(arr)
        elif kind == 4:  # 过曝（对着灯）
            base = np.array([rng.randint(238, 255)] * 3, dtype=np.int16)
            arr = np.clip(base + np.random.normal(0, 5, (h, w, 3)), 0, 255).astype(np.uint8)
            im = Image.fromarray(arr)
        else:  # 棋盘格 / 桌面纹理
            tile = rng.choice([8, 16, 32])
            arr = np.zeros((h, w, 3), dtype=np.uint8)
            c1, c2 = rng.choice(PALETTE), rng.choice(PALETTE)
            for y in range(0, h, tile):
                for x in range(0, w, tile):
                    arr[y:y + tile, x:x + tile] = c1 if ((x // tile + y // tile) % 2 == 0) else c2
            im = Image.fromarray(arr).filter(ImageFilter.GaussianBlur(1.2))
        if save(im, f"syn_{i:04d}"):
            made += 1
    return made


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.jpg"):
        old.unlink()

    q = from_quarantine()
    s = synthetic()
    total = len(list(OUT.glob("*.jpg")))
    print(f"other 类: quarantine {q} + synthetic {s} = {total} 张 -> {OUT}")
    if total < 100:
        print("!! 负样本太少，先跑 filter_clip.py 生成 quarantine")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
