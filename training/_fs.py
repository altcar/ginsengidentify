"""训练脚本共用的小工具：Windows + OneDrive 下删除目录会偶发 PermissionError。"""
from __future__ import annotations

import shutil
import time
from pathlib import Path


def safe_rmtree(p: Path) -> None:
    if not p.exists():
        return
    for _ in range(8):
        try:
            shutil.rmtree(p)
            return
        except (PermissionError, OSError):
            time.sleep(0.6)
    shutil.rmtree(p, ignore_errors=True)
    if p.exists():
        raise SystemExit(f"!! 无法清空 {p}，请手动删除后重试（可能被 OneDrive / 杀软占用）")
