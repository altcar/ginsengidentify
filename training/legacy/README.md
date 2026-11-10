# training/legacy — 已删除，仅留说明

这里原本有 `fetch_images.py` / `fetch_more.py` / `normalize_images.py` 三个 Bing 抓图脚本，
产出就是那份 195 张、混着广告和包装盒、test 准确率 62.5% 的数据集。
它们的文件头本身也是乱码，所以 v2.1 一并删掉了（git 历史里还能查到）。

现在的流水线在上一级目录：

| 步骤 | 文件 |
|---|---|
| 抓图 | `fetch_dataset.py`（iNaturalist + Wikimedia） |
| CLIP 清洗 | `filter_clip.py` |
| 去重 | `clean_strict.py` |
| 负类 | `build_negative_class.py` |
| 切分 | `prepare_data.py` |
| 训练 / 导出 / 评估 | `train.py` / `export_tflite.py` / `test_tflite.py` |

不要再用 Bing 抓图那条路 —— 标签可靠性无法保证。
