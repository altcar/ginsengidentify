#!/usr/bin/env bash
# 训练完后执行这个，把 model/ 里的 tflite + labels 拷到 android assets
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/model"
DST="$ROOT/android/app/src/main/assets"
mkdir -p "$DST"
cp -f "$SRC/ginseng_model.tflite" "$DST/"
cp -f "$SRC/labels.txt" "$DST/"
echo "[copy_assets] done -> $DST"
ls -la "$DST"
