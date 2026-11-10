"""
fetch_images.py — 下载人参 / 西洋参 / 桔梗 三类图片

策略:
  - 每个类用 5 组关键词（中/英/学名/形态）抓
  - bing-image-downloader 简单稳定，免 API key
  - 去重（hash）+ 尺寸/格式过滤
  - 失败自动 fallback 到 icrawler (Baidu)
  - 限定每组关键词 ~60 张，三类合计 ~900 张

用法:
  python training/fetch_images.py            # 正常抓
  python training/fetch_images.py --per-keyword 30
"""
import argparse
import hashlib
import os
import sys
import time
from pathlib import Path
from typing import List

from PIL import Image
from icrawler import ImageDownloader
from icrawler.builtin import BingImageCrawler

# ---------- 配置 ----------
ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "dataset" / "raw"
ALLOWED_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
MIN_W, MIN_H = 160, 160  # 太小的图扔掉

# 关键词矩阵：覆盖原植物 / 干燥药材 / 切片 / 商品图
QUERIES = {
    "renshen": [
        "人参 中药", "人参 整枝", "人参 切片", "鲜人参",
        "长白山人参", "人参 药材 特写", "生晒参",
        "ginseng root", "fresh ginseng whole", "panax ginseng root",
        "korean red ginseng", "ginseng dried slice",
    ],
    "xiyangshen": [
        "西洋参 整枝", "西洋参 切片", "西洋参 特写", "花旗参",
        "西洋参 药材", "西洋参片", "进口西洋参",
        "american ginseng root", "panax quinquefolius", "wisconsin ginseng",
        "american ginseng slice", "canadian ginseng",
    ],
    "jiegeng": [
        "桔梗 药材", "桔梗 切片", "桔梗 整根", "桔梗 特写",
        "桔梗 中药 饮片", "桔梗 干", "野生桔梗",
        "platycodon root", "platycodon grandiflorus root", "dried platycodon",
        "balloon flower root dried", "doraji root",
    ],
}


def sha1_of_file(p: Path) -> str:
    h = hashlib.sha1()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def clean_dir(d: Path) -> None:
    """去重 + 过滤太小的图 + 转换格式异常。"""
    seen = {}
    phashes = set()
    removed = 0
    # 尝试 perceptual hash 可选
    try:
        import imagehash  # type: ignore
        USE_PHASH = True
    except ImportError:
        USE_PHASH = False

    for p in d.glob("*"):
        if not p.is_file():
            continue
        if p.suffix.lower() not in ALLOWED_EXTS:
            p.unlink(missing_ok=True)
            removed += 1
            continue
        try:
            with Image.open(p) as im:
                w, h = im.size
                if w < MIN_W or h < MIN_H:
                    p.unlink(missing_ok=True)
                    removed += 1
                    continue
                im.verify()  # 损坏检测
                if USE_PHASH:
                    im2 = Image.open(p)
                    ph = str(imagehash.phash(im2, hash_size=8))
                    if ph in phashes:
                        p.unlink(missing_ok=True)
                        removed += 1
                        continue
                    phashes.add(ph)
        except Exception:
            p.unlink(missing_ok=True)
            removed += 1
            continue
        h = sha1_of_file(p)
        if h in seen:
            p.unlink(missing_ok=True)
            removed += 1
        else:
            seen[h] = p.name
    if removed:
        print(f"  [clean] 移除 {removed} 张 (重复/损坏/太小)")


def fetch_with_bing(query: str, out_dir: Path, n: int) -> int:
    """用 bing-image-downloader 抓一组关键词。"""
    try:
        from bing_image_downloader import downloader
    except ImportError:
        print("  ! bing-image-downloader 不可用，跳过")
        return 0
    out_dir.mkdir(parents=True, exist_ok=True)
    try:
        downloader.download(
            query,
            limit=n,
            output_dir=str(out_dir.parent),
            adult_filter_off=True,
            force_replace=False,
            timeout=15,
        )
    except Exception as e:
        print(f"  ! bing 抓取失败 [{query}]: {e}")
        return 0
    # bing-image-downloader 把图存到 out_dir.parent/<query>
    src = out_dir.parent / query
    if not src.exists():
        return 0
    moved = 0
    for p in src.glob("*"):
        if p.is_file() and p.suffix.lower() in ALLOWED_EXTS:
            target = out_dir / p.name
            try:
                if target.exists():
                    target.unlink()
                p.rename(target)
                moved += 1
            except OSError:
                pass
    # 清空原始 query 目录
    try:
        for p in src.iterdir():
            p.unlink(missing_ok=True)
        src.rmdir()
    except OSError:
        pass
    return moved


def fetch_with_icrawler(query: str, out_dir: Path, n: int) -> int:
    """用 icrawler 抓 Bing（备用通道）。"""
    out_dir.mkdir(parents=True, exist_ok=True)
    crawler = BingImageCrawler(
        downloader_threads=4,
        storage={"root_dir": str(out_dir)},
        feeder_threads=1,
    )
    try:
        crawler.crawl(keyword=query, max_num=n, file_idx_offset=0)
    except Exception as e:
        print(f"  ! icrawler 抓取失败 [{query}]: {e}")
        return 0
    return sum(1 for _ in out_dir.glob("*") if _.is_file())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--per-keyword", type=int, default=60)
    parser.add_argument("--clean-only", action="store_true")
    args = parser.parse_args()

    if args.clean_only:
        for cls in QUERIES:
            d = RAW_DIR / cls
            if d.exists():
                clean_dir(d)
        return

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    print(f"目标目录: {RAW_DIR}")
    print(f"每组关键词抓 {args.per_keyword} 张")

    for cls, queries in QUERIES.items():
        cls_dir = RAW_DIR / cls
        cls_dir.mkdir(parents=True, exist_ok=True)
        print(f"\n=== {cls} ===")
        for q in queries:
            t0 = time.time()
            n = fetch_with_bing(q, cls_dir, args.per_keyword)
            dt = time.time() - t0
            print(f"  [{q}] -> +{n} ({dt:.1f}s)")
            # 抓完一组立刻清洗
            clean_dir(cls_dir)
        # 兜底：bing 失败时用 icrawler
        if not any(cls_dir.glob("*")):
            print("  ! bing 全失败，尝试 icrawler 兜底")
            fetch_with_icrawler(queries[0], cls_dir, args.per_keyword)
            clean_dir(cls_dir)

    print("\n=== 抓取完成 ===")
    for cls in QUERIES:
        d = RAW_DIR / cls
        n = sum(1 for _ in d.glob("*") if _.is_file()) if d.exists() else 0
        print(f"  {cls}: {n} 张")


if __name__ == "__main__":
    main()
