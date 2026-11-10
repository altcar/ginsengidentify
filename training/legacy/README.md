# training/legacy — 已废弃，仅作历史参考

这三个脚本是旧版「Bing 抓图」流水线，产物就是那份 195 张、混着广告和包装盒、
test 准确率 62.5% 的数据集。

| 文件 | 为什么废弃 |
|---|---|
| `fetch_images.py` | 依赖 `bing-image-downloader` / `icrawler`，Bing 图搜接口早已不可用 |
| `fetch_more.py` | 同上，且与 `fetch_images.py` 逻辑重复 |
| `normalize_images.py` | 只做 RGBA→RGB，新的 `clean_strict.py` 已经包含解码校验、EXIF 摆正、缩放和去重 |

替代方案见上层目录的 `fetch_dataset.py` / `filter_clip.py` / `clean_strict.py`。
不要再用这里的脚本生成数据集 —— 标签可靠性无法保证。
