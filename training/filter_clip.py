"""
filter_clip.py — 用 CLIP 做半自动标注 / 清洗（这是 README 里建议但一直没做的第 2 步）

为什么必须做这一步：
  抓回来的图**来源标注是对的**（iNaturalist 按物种 ID，Commons 按条目），
  但里面混着大量"提到了人参但不是药材图"的东西：
    · 货架上的商品包装盒   · 韩国街头的招牌
    · 人参鸡汤               · 扫描的古籍书页
    · 城市雕塑/建筑照片
  这些图直接拿去训练，模型学的是"包装盒 = 人参"，真机上当然乱猜。

做法：
  1. 用 CLIP (ViT-B-32) 对每张图算 3 个目标类 prompt + 一堆负样本 prompt 的相似度
  2. 本类 prompt 打不过负样本 -> 丢掉（进 quarantine）
  3. 跨类判定默认**不启用**（--cross-drop 才开）：三个类在 CLIP 语义空间里
     高度重叠，"ginseng" 会把 american ginseng 全吸走，按跨类删会把西洋参清空
  4. 剩下的进 dataset/curated/<class>/

用法:
  python training/filter_clip.py
  python training/filter_clip.py --margin 0.15 --batch 32
  python training/filter_clip.py --device cpu
"""
from __future__ import annotations

import argparse
import csv
import shutil
import sys
from pathlib import Path

from _fs import safe_rmtree

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
SOURCES = [ROOT / "dataset" / "raw_dl", ROOT / "dataset" / "raw"]
OUT = ROOT / "dataset" / "curated"
QUARANTINE = ROOT / "dataset" / "quarantine"
REPORT = ROOT / "dataset" / "curated_report.csv"
ALLOWED = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}

PROMPTS: dict[str, list[str]] = {
    "renshen": [
        "a photograph of a ginseng root",
        "dried ginseng roots lying on a surface",
        "sliced ginseng roots",
        "a ginseng plant with green leaves",
        "a ginseng plant with red berries",
    ],
    "xiyangshen": [
        "a photograph of american ginseng roots",
        "dried american ginseng root slices",
        "sliced american ginseng root",
        "american ginseng roots in a bowl",
        "a Panax quinquefolius plant with green leaves",
        "a Panax quinquefolius plant with red berries",
    ],
    "jiegeng": [
        "a balloon flower plant with purple flowers",
        "dried platycodon roots",
        "platycodon root slices",
        "a platycodon plant with green leaves",
    ],
}

DOMAIN_PLANT = [
    "a photograph of a green plant growing outdoors",
    "leaves of a herbaceous plant in a forest",
    "a plant with flowers in a garden",
]

DOMAIN_ROOT = [
    "dried roots of a medicinal herb on a table",
    "sliced dried roots in a bowl",
    "a bundle of dried roots",
    "ginseng roots on a white background",
]

NEGATIVE = [    "a product box on a store shelf",
    "rows of packaged products in a shop",
    "a market or shop interior",
    "a sign with text on it",
    "a scanned document or book page",
    "a bowl of soup or cooked food",
    "a city street with buildings",
    "a logo or illustration",
    "a person holding something",
    "a screenshot or poster",
    "a bottle of medicine or supplements",
    "a map",
    "a bag of packaged goods",
    "a plate of cooked meat",
    "a museum display cabinet",
    "a website screenshot",
]


def collect() -> dict[str, list[Path]]:
    by_cls: dict[str, list[Path]] = {}
    for src in SOURCES:
        if not src.is_dir():
            continue
        for d in sorted(p for p in src.iterdir() if p.is_dir()):
            files = [p for p in sorted(d.iterdir())
                     if p.is_file() and p.suffix.lower() in ALLOWED]
            if files:
                by_cls.setdefault(d.name, []).extend(files)
    return by_cls


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--margin", type=float, default=0.12,
                    help="被判成别的类且优势超过该值才丢弃（默认关闭，见 --cross-drop）")
    ap.add_argument("--cross-drop", action="store_true",
                    help="允许按『更像别的类』丢弃。默认关闭：平级的三个类在 CLIP 里"
                         "语义高度重叠（ginseng 会把 american ginseng 全吃掉），"
                         "开这个会把整个西洋参类误删")
    ap.add_argument("--neg-margin", type=float, default=0.0,
                    help="目标 prompt 必须比负样本高出这么多才保留")
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--model", default="ViT-B-32")
    ap.add_argument("--pretrained", default="laion2b_s34b_b79k")
    args = ap.parse_args()

    import open_clip
    import torch
    from PIL import Image
    from tqdm import tqdm

    print(f"加载 CLIP {args.model} / {args.pretrained} ...", flush=True)
    model, _, preprocess = open_clip.create_model_and_transforms(
        args.model, pretrained=args.pretrained, device=args.device
    )
    model.eval()
    tokenizer = open_clip.get_tokenizer(args.model)

    groups = list(PROMPTS) + ["negative"]
    flat: list[str] = []
    owner: list[str] = []
    for g in groups:
        for p in (PROMPTS.get(g) or NEGATIVE):
            flat.append(p)
            owner.append(g)

    with torch.no_grad():
        tok = tokenizer(flat)
        text = model.encode_text(tok.to(args.device))
        text /= text.norm(dim=-1, keepdim=True)
        t_plant = model.encode_text(tokenizer(DOMAIN_PLANT).to(args.device))
        t_plant /= t_plant.norm(dim=-1, keepdim=True)
        t_root = model.encode_text(tokenizer(DOMAIN_ROOT).to(args.device))
        t_root /= t_root.norm(dim=-1, keepdim=True)

    by_cls = collect()
    if not by_cls:
        print("!! 没有可筛选的图片")
        return

    for d in (OUT, QUARANTINE):
        safe_rmtree(d)
        d.mkdir(parents=True, exist_ok=True)

    rows = []
    totals = {c: {"keep": 0, "drop": 0} for c in by_cls}

    for cls, files in sorted(by_cls.items()):
        print(f"\n=== {cls}: {len(files)} 张 ===", flush=True)
        kept = 0
        for start in tqdm(range(0, len(files), args.batch), disable=None):
            chunk = files[start:start + args.batch]
            imgs, paths = [], []
            for p in chunk:
                try:
                    imgs.append(preprocess(Image.open(p).convert("RGB")))
                    paths.append(p)
                except Exception:
                    continue
            if not imgs:
                continue
            with torch.no_grad():
                batch = torch.stack(imgs).to(args.device)
                feat = model.encode_image(batch)
                feat /= feat.norm(dim=-1, keepdim=True)
                logits = 100.0 * feat @ text.T
                probs = logits.softmax(dim=-1).cpu()
                dp = (100.0 * feat @ t_plant.T).softmax(dim=-1).max(dim=-1).values.cpu()
                dr = (100.0 * feat @ t_root.T).softmax(dim=-1).max(dim=-1).values.cpu()

            for path, pr, sp, sr in zip(paths, probs, dp, dr):
                domain = "plant" if float(sp) >= float(sr) else "root"
                scores = {g: 0.0 for g in groups}
                for p_idx, g in enumerate(owner):
                    scores[g] = max(scores[g], float(pr[p_idx]))
                best = max(scores, key=scores.get)
                my = scores.get(cls, 0.0)
                other = scores.get(best, 0.0)

                action = "keep"
                if my < scores["negative"] + args.neg_margin:
                    action = "drop_junk"
                elif args.cross_drop and best != cls and (other - my) >= args.margin:
                    action = f"drop_as_{best}"

                target = OUT if action == "keep" else QUARANTINE
                out_dir = target / cls
                out_dir.mkdir(parents=True, exist_ok=True)
                dst = out_dir / path.name
                if dst.exists():
                    dst = out_dir / f"{path.stem}_{abs(hash(str(path))) % 100000}.jpg"
                shutil.copy2(path, dst)
                if action == "keep":
                    kept += 1
                    totals[cls]["keep"] += 1
                else:
                    totals[cls]["drop"] += 1
                rows.append({
                    "class": cls, "file": path.name, "action": action,
                    "best": best, "score_self": round(my, 4),
                    "score_best": round(other, 4),
                    "domain": domain,
                    "s_plant": round(float(sp), 4), "s_root": round(float(sr), 4),
                    **{f"s_{g}": round(scores[g], 4) for g in groups},
                })
        print(f"  {cls}: 保留 {kept}/{len(files)}", flush=True)

    with REPORT.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    print("\n=== 汇总 ===")
    for cls, s in totals.items():
        print(f"  {cls:11s} keep={s['keep']:4d}  drop={s['drop']:4d}")
    print(f"  -> {OUT}")
    print(f"  -> 被丢弃的在 {QUARANTINE}（可以人工看一眼再决定）")
    print(f"  -> 明细 {REPORT}")


if __name__ == "__main__":
    main()
