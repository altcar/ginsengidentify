# 数据集说明

> 旧版数据集：**每类 65 张、共 195 张 Bing 抓图**，混着广告、包装盒、重复图，
> test 集只有 32 张，准确率 62.5%。本文件描述的是重做后的数据集。

## 流水线

```
fetch_dataset.py        ->  dataset/raw_dl/     从 iNaturalist + Wikimedia Commons 按物种/条目抓图
filter_clip.py          ->  dataset/curated/    用 CLIP 剔除"提到人参但不是药材图"的噪声（并标 plant/root 域）
clean_strict.py         ->  dataset/clean/      解码校验 + dHash 去重（含跨类冲突）
build_negative_class.py ->  dataset/clean/other 负类：quarantine 噪声 + 纯色/噪声/黑屏/过曝合成图
prepare_data.py         ->  dataset/processed/  分层切分 70/15/15
```

每一步都可复现，`dataset/raw_dl/manifest.csv` 记录每张图的来源、许可、作者、原始链接。

## 规模

| 阶段 | jiegeng 桔梗 | renshen 人参 | xiyangshen 西洋参 | other 未检测到 | 合计 |
|---|---:|---:|---:|---:|---:|
| raw_dl（抓取） | 429 | 315 | 431 | — | 1175 |
| curated（CLIP 过滤后） | 461 | 263 | 460 | — | 1184 |
| clean（去重后） | 455 | 175 | 456 | 334 | 1420 |
| processed / train | 318 | 122 | 319 | 233 | 992 |
| processed / val | 68 | 26 | 68 | 50 | 212 |
| processed / test | 69 | 27 | 69 | 51 | 216 |

`other` 来自两处：`dataset/quarantine/`（CLIP 判为货架/食物/招牌/文档的噪声图 184 张）
和合成图 150 张（纯色、渐变、噪声、极暗、过曝、棋盘格）。
它的作用是给模型一个"什么都不是"的出口 —— 没有它，softmax 会把任意画面硬塞进三类之一。

> curated 里包含 `dataset/raw/` 的 195 张旧图（去重后并入），所以列和 raw_dl 不完全对应。

图片统一缩到最长边 768px、JPEG q88（模型输入只有 224，存 1024px 纯属浪费），
`dataset/processed/` 合计约 135 MB。

> ⚠️ 旧图（`dataset/raw/`）和其余数据**不是同一个拍摄域**：它们多为电商白底商品图。
> 实测模型在旧图上的准确率只有 36%，在新来源上是 89%。详见根目录 README。

## 来源

| 来源 | 张数 | 说明 |
|---|---:|---|
| iNaturalist（研究级观察） | 936 | 按分类学 ID 抓取，标签由物种鉴定保证 |
| Wikimedia Commons | 239 | 按条目/分类搜索，带许可与作者 |
| 旧版 Bing 抓图（`dataset/raw/`） | 195 | **来源与许可不明**，仅作商品图域的补充 |

许可分布（manifest.csv）：`cc-by-nc` 631、`all-rights-reserved` 214、
`CC BY-SA 4.0` 79、`CC BY-SA 3.0` 48、`cc-by` 47、`Public domain` 30、`CC0` 38 …

> ⚠️ **许可提示**：这是一个课程/学习项目。图片版权归原作者，逐张的许可、作者、
> 原始链接都在 `dataset/raw_dl/manifest.csv` 里。若要对外分发数据集或商用，
> 请先按 manifest 过滤掉 `all-rights-reserved` 和 `dataset/raw/` 的旧图。

## 两个拍摄域

药材识别有两条互不相同的视觉分布，评测时分开统计（`training/test_tflite.py` 会分别报告）：

| 拍摄域 | 含义 | jiegeng | renshen | xiyangshen |
|---|---|---:|---:|---:|
| plant | 整株 / 叶片 / 花 | 272 | 113 | 268 |
| root | 干燥根 / 切片 / 药材商品图 | 189 | 150 | 192 |

整株照片主要来自 iNaturalist；干燥根/切片来自 Commons 与旧版商品图。
只看总准确率会偏乐观 —— 真机拍的是干燥药材，所以必须看 `root` 那一列。

## 已知局限

1. **人参只有 175 张**：*Panax ginseng* 野生观察记录本来就少（iNaturalist 仅 77 条），
   Commons 的「人参」检索结果又有大量商品包装/药膳图，被 CLIP 过滤掉了。
2. **干燥切片类间差异极小**：人参 / 西洋参切片在 RGB 图像上高度相似，
   真正的鉴别依赖显微结构、气味、皂苷含量。
3. **没有 "其他/未知" 类**：模型一定会在三类里选一个。App 端因此加了
   低置信度提示（< 55%），但这不是拒绝预测，只是提示。
4. CLIP 过滤对平级类别不可靠（"ginseng" 会吸走 "american ginseng"），
   所以默认只做**负样本过滤**，不做跨类重标注。

## 扩展数据集

```bash
python training/fetch_dataset.py --class renshen --limit 800   # 支持断点续抓
python training/filter_clip.py                                  # 重新过滤
python training/clean_strict.py
python training/prepare_data.py
python training/train.py
```
