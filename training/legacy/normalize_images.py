"""
normalize_images.py — 把 raw 下所有图转成 RGB JPEG 存回原位（覆盖）
解决: 某些下载图是 RGBA/灰度/CMYK/损坏，导致 tf decode 失败
注意: Windows 大小写不敏感，target == source 时不能 unlink
"""
import os
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "dataset" / "raw"

ALLOWED = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def same_file(a: Path, b: Path) -> bool:
    try:
        return os.path.samefile(str(a), str(b))
    except OSError:
        return str(a).lower() == str(b).lower()


def main():
    total_in, total_out, total_fail = 0, 0, 0
    for cls_dir in sorted(RAW.iterdir()):
        if not cls_dir.is_dir():
            continue
        cls_in, cls_out, cls_fail = 0, 0, 0
        for p in list(cls_dir.glob("*")):
            if not p.is_file():
                continue
            if p.suffix.lower() not in ALLOWED:
                p.unlink(missing_ok=True)
                continue
            cls_in += 1
            try:
                im = Image.open(p)
                if im.mode != "RGB":
                    im = im.convert("RGB")
                if max(im.size) > 1024:
                    im.thumbnail((1024, 1024), Image.LANCZOS)
                # 写到同名的 .jpg（统一小写）
                target = cls_dir / (p.stem + ".jpg")
                if same_file(target, p) and p.suffix.lower() == ".jpg":
                    im.save(target, "JPEG", quality=90)
                else:
                    tmp = cls_dir / (p.stem + ".__tmp.jpg")
                    im.save(tmp, "JPEG", quality=90)
                    if p.exists() and not same_file(p, tmp):
                        p.unlink(missing_ok=True)
                    os.replace(tmp, target)
                cls_out += 1
            except Exception:
                p.unlink(missing_ok=True)
                cls_fail += 1
        total_in += cls_in
        total_out += cls_out
        total_fail += cls_fail
        n = sum(1 for _ in cls_dir.glob("*.jpg"))
        print(f"  {cls_dir.name}: {n} 张 (in={cls_in}, fail={cls_fail})")
    print(f"\n汇总: 输入 {total_in} -> 成功 {total_out}, 失败 {total_fail}")


if __name__ == "__main__":
    main()

