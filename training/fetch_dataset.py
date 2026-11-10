"""
fetch_dataset.py — 从权威公开图库抓取"真人参 / 西洋参 / 桔梗"图片

数据源（真实、可溯源、标注可信）:
  1. iNaturalist     — 研究级观察记录，按分类学 ID 精确取图
  2. Wikimedia Commons — 按条目/分类搜索，带许可与作者信息

过滤:
  - 图谱/示意图/图标/地图 等非照片
  - 短边 < 400px、极端长宽比
  - 全局 sha1 去重

用法:
  python training/fetch_dataset.py                  # 全量
  python training/fetch_dataset.py --class renshen  # 只抓一类
  python training/fetch_dataset.py --limit 400      # 每类上限
  python training/fetch_dataset.py --workers 12     # 并发下载数

输出:
  dataset/raw_dl/<class>/*.jpg
  dataset/raw_dl/manifest.csv     ← 来源 / 许可 / 作者
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import requests
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "dataset" / "raw_dl"
MANIFEST = OUT / "manifest.csv"

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

UA = "ginseng-identifier-dataset/1.0 (educational; UWE coursework)"
SESSION = requests.Session()
SESSION.headers.update({"User-Agent": UA})

MIN_SIDE = 400
MAX_ASPECT = 3.0
MAX_SIDE = 1024
BAD_WORDS = re.compile(
    r"(idiogram|diagram|illustration|drawing|logo\b|icon\b|map\b|chart|"
    r"clipart|cartoon|pictogram|seal\b|stamp\b|banknote|coin\b|"
    r"distribution|range\b|habitat\b)",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class Spec:
    cls: str
    inat_taxon: int
    commons_queries: tuple[str, ...]


SPECS = (
    Spec(
        cls="renshen",
        inat_taxon=366498,  # Panax ginseng
        commons_queries=(
            "Panax ginseng",
            "Panax ginseng root",
            "ginseng root dried",
            "ginseng root slices",
            "red ginseng root",
            "Korean ginseng",
            "人参",
            "人参 切片",
            "高丽参",
        ),
    ),
    Spec(
        cls="xiyangshen",
        inat_taxon=116333,  # Panax quinquefolius
        commons_queries=(
            "Panax quinquefolius",
            "American ginseng root",
            "American ginseng",
            "西洋参",
            "花旗参",
        ),
    ),
    Spec(
        cls="jiegeng",
        inat_taxon=141623,  # Platycodon grandiflorus
        commons_queries=(
            "Platycodon grandiflorus",
            "Platycodon root",
            "balloon flower",
            "Chinese bellflower",
            "桔梗",
            "桔梗 根",
        ),
    ),
)


def _get(url: str, **kw) -> requests.Response:
    last: Exception | None = None
    for attempt in range(4):
        try:
            r = SESSION.get(url, timeout=45, **kw)
            if r.status_code == 200:
                return r
            if r.status_code in (429, 500, 502, 503, 504):
                time.sleep(1.5 + attempt * 2)
                continue
            r.raise_for_status()
        except requests.RequestException as e:
            last = e
            time.sleep(1.5 + attempt * 2)
    raise RuntimeError(f"GET failed: {url} ({last})")


def _normalize(data: bytes) -> Image.Image | None:
    try:
        im = Image.open(io.BytesIO(data))
        im.verify()
        im = Image.open(io.BytesIO(data)).convert("RGB")
    except Exception:
        return None
    w, h = im.size
    if min(w, h) < MIN_SIDE:
        return None
    if max(w, h) / max(1, min(w, h)) > MAX_ASPECT:
        return None
    if max(im.size) > MAX_SIDE:
        im.thumbnail((MAX_SIDE, MAX_SIDE), Image.LANCZOS)
    return im


class Store:
    def __init__(self, out: Path):
        self.out = out
        self.lock = threading.Lock()
        self.seen_sha: set[str] = set()
        self.rows: list[dict] = []
        self.counts: dict[str, int] = {}
        self._load_existing()

    def _load_existing(self):
        """支持断点续抓：已经下过的图不重下、不覆盖。"""
        if not self.out.is_dir():
            return
        for d in self.out.iterdir():
            if d.is_dir():
                self.counts[d.name] = sum(1 for _ in d.glob("*.jpg"))
        for p in self.out.rglob("*.jpg"):
            try:
                self.seen_sha.add(hashlib.sha1(p.read_bytes()).hexdigest())
            except OSError:
                pass
        if MANIFEST.exists():
            try:
                with MANIFEST.open(encoding="utf-8", newline="") as f:
                    self.rows = list(csv.DictReader(f))
            except OSError:
                self.rows = []

    def add(self, cls: str, data: bytes, meta: dict) -> bool:
        im = _normalize(data)
        if im is None:
            return False
        sha = hashlib.sha1(data).hexdigest()
        with self.lock:
            if sha in self.seen_sha:
                return False
            self.seen_sha.add(sha)
            n = self.counts.get(cls, 0)
            self.counts[cls] = n + 1
            cls_dir = self.out / cls
            cls_dir.mkdir(parents=True, exist_ok=True)
            name = f"{cls}_{n:05d}.jpg"
            im.save(cls_dir / name, "JPEG", quality=92)
            self.rows.append({"class": cls, "file": f"{cls}/{name}", "sha1": sha,
                              "width": im.width, "height": im.height, **meta})
        return True

    def flush_manifest(self):
        MANIFEST.parent.mkdir(parents=True, exist_ok=True)
        fields = ["class", "file", "sha1", "width", "height",
                  "source", "url", "license", "author", "title"]
        with self.lock:
            rows = list(self.rows)
        with MANIFEST.open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
            w.writeheader()
            w.writerows(rows)


# --------------------------------------------------------------------------- #
def inat_candidates(spec: Spec, limit: int, pages: int = 6) -> list[tuple[str, dict]]:
    out: list[tuple[str, dict]] = []
    for page in range(1, pages + 1):
        if len(out) >= limit * 2:
            break
        params = {
            "taxon_id": spec.inat_taxon,
            "quality_grade": "research",
            "photos": "true",
            "per_page": 200,
            "page": page,
            "order_by": "votes",
        }
        try:
            r = _get("https://api.inaturalist.org/v1/observations", params=params)
        except Exception as e:
            print(f"  [inat] {spec.cls} page {page} failed: {e}")
            break
        results = r.json().get("results", [])
        if not results:
            break
        for obs in results:
            for photo in obs.get("photos") or []:
                url = (photo.get("url") or "").replace("/square.", "/large.")
                if not url:
                    continue
                out.append((
                    url,
                    {
                        "source": "iNaturalist",
                        "url": url,
                        "license": photo.get("license_code") or "all-rights-reserved",
                        "author": re.sub(r"<[^>]+>", "",
                                         photo.get("attribution") or "")[:160],
                        "title": f"observation {obs.get('id')}",
                    },
                ))
        time.sleep(0.6)
    return out


def commons_candidates(spec: Spec, limit: int) -> list[tuple[str, dict]]:
    out: list[tuple[str, dict]] = []
    per_query = max(40, limit // max(1, len(spec.commons_queries)))
    for q in spec.commons_queries:
        if len(out) >= limit * 2:
            break
        offset = 0
        got = 0
        while got < per_query:
            params = {
                "action": "query",
                "generator": "search",
                "gsrsearch": f"{q} filetype:bitmap",
                "gsrnamespace": 6,
                "gsrlimit": 50,
                "gsroffset": offset,
                "prop": "imageinfo",
                "iiprop": "url|size|extmetadata",
                "iiurlwidth": MAX_SIDE,
                "format": "json",
            }
            try:
                r = _get("https://commons.wikimedia.org/w/api.php", params=params)
            except Exception as e:
                print(f"  [commons] '{q}' failed: {e}")
                break
            pages = (r.json().get("query") or {}).get("pages") or {}
            if not pages:
                break
            for page in pages.values():
                info = (page.get("imageinfo") or [{}])[0]
                url = info.get("thumburl") or info.get("url")
                title = page.get("title", "")
                if not url or BAD_WORDS.search(title):
                    continue
                meta = info.get("extmetadata") or {}
                author = re.sub(r"<[^>]+>", "",
                                (meta.get("Artist") or {}).get("value", ""))[:160]
                out.append((
                    url,
                    {
                        "source": "Wikimedia Commons",
                        "url": url,
                        "license": (meta.get("LicenseShortName") or {}).get("value", "unknown"),
                        "author": author,
                        "title": title[:160],
                    },
                ))
                got += 1
            offset += 50
            time.sleep(0.25)
    return out


def download_all(spec: Spec, cands: list[tuple[str, dict]], limit: int,
                 store: Store, workers: int) -> int:
    got = 0
    stop = threading.Event()

    def work(item):
        nonlocal got
        if stop.is_set():
            return
        url, meta = item
        try:
            data = _get(url).content
        except Exception:
            return
        if store.add(spec.cls, data, meta):
            got += 1
            if got >= limit:
                stop.set()

    with ThreadPoolExecutor(max_workers=workers) as ex:
        list(ex.map(work, cands))
    return got


def main(argv: Iterable[str] | None = None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--class", dest="cls", choices=[s.cls for s in SPECS], default=None)
    ap.add_argument("--limit", type=int, default=420)
    ap.add_argument("--workers", type=int, default=10)
    ap.add_argument("--inat-only", action="store_true")
    ap.add_argument("--commons-only", action="store_true")
    args = ap.parse_args(argv)

    OUT.mkdir(parents=True, exist_ok=True)
    store = Store(OUT)
    specs = [s for s in SPECS if args.cls in (None, s.cls)]

    for spec in specs:
        print(f"\n=== {spec.cls} (target {args.limit}) ===", flush=True)
        cands: list[tuple[str, dict]] = []
        if not args.commons_only:
            cands += inat_candidates(spec, args.limit)
            print(f"  iNaturalist 候选: {len(cands)}", flush=True)
        if not args.inat_only:
            c = commons_candidates(spec, args.limit)
            cands += c
            print(f"  Commons 候选: {len(c)}", flush=True)
        n_before = store.counts.get(spec.cls, 0)
        download_all(spec, cands, args.limit - n_before, store, args.workers)
        store.flush_manifest()
        print(f"  {spec.cls}: {store.counts.get(spec.cls, 0)} 张", flush=True)

    store.flush_manifest()
    print("\n=== 汇总 ===")
    for spec in specs:
        print(f"  {spec.cls}: {store.counts.get(spec.cls, 0)}")
    print(f"  manifest: {MANIFEST}")


if __name__ == "__main__":
    main()
