#!/usr/bin/env bash
# 把训练产物同步到 Android assets 并校验（推荐用跨平台的 Python 版本）
#
#   python scripts/sync_assets.py          # 复制 + 校验
#   python scripts/sync_assets.py --check  # 只校验
#
# 这个 bash 版本保留给 CI / Unix 环境使用。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/model"
DST="$ROOT/android/app/src/main/assets"
mkdir -p "$DST"
cp -f "$SRC/ginseng_model.tflite" "$DST/"
cp -f "$SRC/labels.txt" "$DST/"
echo "[copy_assets] done -> $DST"
ls -la "$DST"

# 一致性校验：assets 里的模型和 model/ 必须完全相同，
# 否则手机上跑的还是旧模型（旧版 README 62.5% 和实际安装包的模型就对不上）
if command -v sha256sum >/dev/null 2>&1; then
  a=$(sha256sum "$SRC/ginseng_model.tflite" | cut -d' ' -f1)
  b=$(sha256sum "$DST/ginseng_model.tflite" | cut -d' ' -f1)
  [ "$a" = "$b" ] || { echo "!! assets 模型与 model/ 不一致"; exit 1; }
  echo "[copy_assets] sha256 OK: $a"
fi
