"""
clean_strict.py — 严格清洗已抓图片
  - 删除 < 200x200
  - perceptual hash 去重
  - 文件 hash 去重
  - 损坏检测
"""
import hashlib
from pathlib import Path
from PIL import Image
import imagehash

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "dataset" / "raw"
ALLOWED = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
MIN_W, MIN_H = 200, 200


def clean_one(d: Path) -> int:
    if not d.exists():
        return 0
    seen_h, seen_ph = set(), set()
    removed = 0
    for p in d.glob("*"):
        if not p.is_file():
            continue
        if p.suffix.lower() not in ALLOWED:
            p.unlink(missing_ok=True)
            removed += 1
            continue
        try:
            im = Image.open(p)
            w, h = im.size
            if w < MIN_W or h < MIN_H:
                p.unlink(missing_ok=True)
                removed += 1
                continue
            im.verify()
            im2 = Image.open(p)
            ph = str(imagehash.phash(im2, hash_size=8))
            if ph in seen_ph:
                p.unlink(missing_ok=True)
                removed += 1
                continue
            seen_ph.add(ph)
            hh = hashlib.sha1(p.read_bytes()).hexdigest()
            if hh in seen_h:
                p.unlink(missing_ok=True)
                removed += 1
                continue
            seen_h.add(hh)
        except Exception:
            p.unlink(missing_ok=True)
            removed += 1
    return removed


def main():
    for cls in ("renshen", "xiyangshen", "jiegeng"):
        d = RAW / cls
        n_before = sum(1 for _ in d.glob("*") if _.is_file())
        n_removed = clean_one(d)
        n_after = sum(1 for _ in d.glob("*") if _.is_file())
        print(f"{cls}: {n_before} -> {n_after} (removed {n_removed})")


if __name__ == "__main__":
    main()
