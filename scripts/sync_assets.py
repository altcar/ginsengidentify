"""
sync_assets.py — 把训练产物同步到 Android assets，并校验一致性

注意 assets 里的模型是"打包进 APK 的"，如果它和 model/ 下的不一致，
手机上跑的就是旧模型 —— 旧版 README 说 62.5% 但装上去的可能又是另一份。

用法:
  python scripts/sync_assets.py          # 复制并校验
  python scripts/sync_assets.py --check  # 只检查，不写
"""
from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / "model"
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
FILES = ("ginseng_model.tflite", "labels.txt")

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()[:16]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    ASSETS.mkdir(parents=True, exist_ok=True)
    ok = True
    for name in FILES:
        src = MODEL / name
        dst = ASSETS / name
        if not src.exists():
            print(f"!! 缺少 {src}")
            ok = False
            continue
        if args.check:
            if not dst.exists():
                print(f"!! assets 缺少 {name}")
                ok = False
                continue
            same = sha(src) == sha(dst)
            print(f"{'OK  ' if same else 'DIFF'} {name:22s} model={sha(src)} assets={sha(dst)}")
            ok = ok and same
        else:
            shutil.copy2(src, dst)
            print(f"同步 {name:22s} {src.stat().st_size/1024:8.1f} KB  sha={sha(dst)}")

    if not ok:
        sys.exit(1)
    if args.check:
        print("\nassets 与 model/ 完全一致")


if __name__ == "__main__":
    main()
