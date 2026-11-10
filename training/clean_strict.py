"""
clean_strict.py â€” æŠŠ dataset/raw + dataset/raw_dl æ¸…æ´—æˆä¸€ä»½å¯ä¿¡çš„ dataset/clean

è§„åˆ™:
  1. åªæŽ¥å—èƒ½å®Œæ•´è§£ç çš„å›¾ç‰‡ï¼Œç»Ÿä¸€è½¬ RGBã€æŒ‰ EXIF æ‘†æ­£
  2. çŸ­è¾¹ < 400px ä¸¢å¼ƒï¼ˆæ¨¡åž‹è¾“å…¥ 224ï¼Œå¤ªå°çš„å›¾æ”¾å¤§åŽå…¨æ˜¯å™ªç‚¹ï¼‰
  3. æžç«¯é•¿å®½æ¯”ï¼ˆ> 3:1ï¼Œå¹¿å‘Šæ¨ªæ¡ï¼‰ä¸¢å¼ƒ
  4. sha1 ç²¾ç¡®åŽ»é‡ï¼ˆå…¨å±€ï¼‰
  5. dHash æ„ŸçŸ¥åŽ»é‡ï¼ˆæ±‰æ˜Žè·ç¦» <= 6 è§†ä¸ºåŒä¸€å¼ ï¼‰
     - åŒç±»é‡å¤ â†’ ä¸¢åŽå‡ºçŽ°çš„é‚£å¼ 
     - è·¨ç±»é‡å¤ â†’ ä¸¤å¼ éƒ½ä¸¢ï¼Œå› ä¸ºæ ‡ç­¾æœ¬èº«è‡ªç›¸çŸ›ç›¾ï¼Œç•™ç€åªä¼šæ±¡æŸ“æ¨¡åž‹
  6. è¾“å‡º dataset/clean/<class>/ å¹¶æ‰“å°æ¯ä¸ªç±»åˆ«çš„æ•°é‡

ç”¨æ³•: python training/clean_strict.py
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
# æ¨¡åž‹è¾“å…¥åªæœ‰ 224ï¼Œå­˜ 1024px çº¯å±žæµªè´¹ï¼ˆæ•°æ®é›† 267MB -> 90MBï¼‰
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
            # manifest.csv ä¹‹ç±»ä¸æ˜¯ç±»åˆ«ç›®å½•
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
        print(f"!! æ²¡æœ‰ä»»ä½•åŽŸå§‹å›¾ç‰‡ï¼Œå…ˆè·‘ training/fetch_dataset.py")
        return

    seen_sha: set[str] = set()
    # å·²ä¿ç•™å›¾ç‰‡: (class, path, dhash)
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
            # ä¿ç•™åŽŸæ–‡ä»¶åï¼šè¿™æ · test_tflite è¿˜èƒ½å›žæŸ¥ curated_report.csv é‡Œçš„æ¥æº/domain
            out_path = out_dir / p.name
            if out_path.exists():
                out_path = out_dir / f"{p.stem}__{len(list(out_dir.glob('*.jpg'))):04d}{p.suffix.lower()}"
            im.save(out_path, "JPEG", quality=JPEG_QUALITY)
            kept.append((cls, out_path, h))

    print("\n=== æ¸…æ´—ç»“æžœ ===")
    total = 0
    for cls in sorted(by_cls):
        n = len(list((OUT / cls).glob("*.jpg"))) if (OUT / cls).is_dir() else 0
        total += n
        s = stats[cls]
        print(f"  {cls:11s} kept={n:4d}  "
              f"(è¯»å…¥ {s['read']}, åå›¾ {s['bad']}, é‡å¤ {s['dupe']}, è·¨ç±»å†²çª {s['cross']})")
    print(f"  åˆè®¡ {total} å¼  -> {OUT}")

    sizes = [len(list((OUT / c).glob('*.jpg'))) for c in sorted(by_cls)]
    if sizes and max(sizes) > 0:
        ratio = max(sizes) / max(1, min(sizes))
        if ratio > 1.8:
            print(f"  [warn] ç±»åˆ«ä¸å¹³è¡¡ max/min = {ratio:.2f}ï¼Œè®­ç»ƒæ—¶ä¼šç”¨ class_weight è¡¥å¿")


if __name__ == "__main__":
    main()
