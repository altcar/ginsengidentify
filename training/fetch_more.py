"""
fetch_more.py — 额外补抓图片（Baidu 通道 + 备用关键词）

用法:  python training/fetch_more.py
"""
import hashlib
import time
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "dataset" / "raw"
ALLOWED = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}

# Baidu 优先（Bing 中文结果有时不准）
BAIDU_QUERIES = {
    "renshen": ["人参特写", "人参 500克", "野山参", "园参", "高丽参", "红参"],
    "xiyangshen": ["西洋参 特写", "花旗参 整枝", "西洋参 500g", "西洋参 礼盒"],
    "jiegeng": ["桔梗 干 特写", "桔梗 切片 500g", "桔梗 中药材 整根", "桔梗饮片"],
}

# 备用 Bing
BING_FALLBACK = {
    "renshen": ["ginseng whole root photo", "panax ginseng dried", "人参 药材 切片"],
    "xiyangshen": ["american ginseng dried root", "西洋参 整枝 切片"],
    "jiegeng": ["platycodon root dried sliced", "doraji root sliced", "桔梗 切片 特写"],
}


def clean(d: Path):
    seen = set()
    n = 0
    for p in d.glob("*"):
        if not p.is_file() or p.suffix.lower() not in ALLOWED:
            p.unlink(missing_ok=True)
            n += 1
            continue
        try:
            with Image.open(p) as im:
                w, h = im.size
                if w < 160 or h < 160:
                    p.unlink(missing_ok=True)
                    n += 1
                    continue
                im.verify()
        except Exception:
            p.unlink(missing_ok=True)
            n += 1
            continue
        h = hashlib.sha1(p.read_bytes()).hexdigest()
        if h in seen:
            p.unlink(missing_ok=True)
            n += 1
        else:
            seen.add(h)
    return n


def fetch_baidu(query: str, out: Path, n: int) -> int:
    try:
        from icrawler import ImageDownloader
        from icrawler.builtin import BaiduImageCrawler
    except Exception as e:
        print(f"  ! Baidu crawler 不可用: {e}")
        return 0
    out.mkdir(parents=True, exist_ok=True)
    c = BaiduImageCrawler(storage={"root_dir": str(out)}, downloader_threads=4)
    try:
        c.crawl(keyword=query, max_num=n)
    except Exception as e:
        print(f"  ! baidu fail [{query}]: {e}")
        return 0
    return sum(1 for _ in out.glob("*") if _.is_file())


def fetch_bing(query: str, out: Path, n: int) -> int:
    from bing_image_downloader import downloader
    out.mkdir(parents=True, exist_ok=True)
    try:
        downloader.download(query, limit=n, output_dir=str(out.parent),
                            adult_filter_off=True, force_replace=False, timeout=15)
    except Exception as e:
        print(f"  ! bing fail [{query}]: {e}")
        return 0
    src = out.parent / query
    if not src.exists():
        return 0
    moved = 0
    for p in src.glob("*"):
        if p.is_file() and p.suffix.lower() in ALLOWED:
            tgt = out / p.name
            try:
                if tgt.exists():
                    tgt.unlink()
                p.rename(tgt)
                moved += 1
            except OSError:
                pass
    try:
        for p in src.iterdir():
            p.unlink(missing_ok=True)
        src.rmdir()
    except OSError:
        pass
    return moved


def main():
    for cls, queries in BAIDU_QUERIES.items():
        out = RAW / cls
        print(f"\n=== {cls} (Baidu) ===")
        for q in queries:
            t0 = time.time()
            n = fetch_baidu(q, out, 30)
            print(f"  [baidu:{q}] +{n} ({time.time()-t0:.1f}s)")
            clean(out)

    for cls, queries in BING_FALLBACK.items():
        out = RAW / cls
        print(f"\n=== {cls} (Bing fallback) ===")
        for q in queries:
            t0 = time.time()
            n = fetch_bing(q, out, 25)
            print(f"  [bing:{q}] +{n} ({time.time()-t0:.1f}s)")
            clean(out)

    print("\n=== 最终统计 ===")
    for cls in BAIDU_QUERIES:
        d = RAW / cls
        n = sum(1 for _ in d.glob("*") if _.is_file())
        print(f"  {cls}: {n}")


if __name__ == "__main__":
    main()
