# 参三味识别 (Ginseng Identifier)

> 一款基于 **Kotlin + Jetpack Compose + TensorFlow Lite** 的端侧三分类 Android 应用：
> **人参 / 西洋参 / 桔梗**。无需联网，本地推理，模型仅 ~1.2 MB。

![arch](https://img.shields.io/badge/Android-24%2B-green) ![kotlin](https://img.shields.io/badge/Kotlin-2.0-purple) ![tflite](https://img.shields.io/badge/TFLite-2.14-orange) ![license](https://img.shields.io/badge/license-MIT-blue)

---

## 📦 项目结构

```
ginseng-identifier/
├── README.md                       ← 本文件
├── requirements.txt                ← Python 训练侧依赖
├── scripts/
│   └── copy_assets.sh              ← 训练完同步模型到 Android assets
├── dataset/                        ← 训练数据 (raw + processed)
│   ├── raw/{renshen,xiyangshen,jiegeng}/
│   └── processed/{train,val,test}/<class>/
├── training/                       ← Python 训练流水线
│   ├── fetch_images.py             ← Bing 抓图（中文+英文+学名多关键词）
│   ├── fetch_more.py               ← 补抓通道（部分失败时用）
│   ├── normalize_images.py         ← RGBA/灰度统一转 RGB JPEG
│   ├── clean_strict.py             ← 严格清洗（pHash 去重）
│   ├── prepare_data.py             ← train/val/test 切分
│   ├── train.py                    ← MobileNetV3 迁移学习 + 增强
│   ├── export_tflite.py            ← Keras → TFLite (float16)
│   └── test_tflite.py              ← 端到端验证 TFLite 推理
├── model/                          ← 训练产物
│   ├── ginseng_model.tflite        ← ⭐ Android 端用这个
│   ├── ginseng_model.keras         ← Keras 完整模型（再训练用）
│   ├── best_warmup.keras           ← warmup 阶段最优
│   ├── best_finetune.keras         ← finetune 阶段最优
│   ├── labels.txt                  ← 类别标签
│   └── metrics.json                ← 训练指标
└── android/                        ← ⭐ Android Studio 工程
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle.properties
    ├── gradle/{libs.versions.toml, wrapper/}
    └── app/
        ├── build.gradle.kts
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml
            ├── assets/
            │   ├── ginseng_model.tflite  ← (从 model/ 复制)
            │   └── labels.txt            ← (从 model/ 复制)
            ├── kotlin/com/example/ginsengidentifier/
            │   ├── GinsengApp.kt
            │   ├── MainActivity.kt      ← Compose UI（拍照/相册/结果）
            │   ├── Classifier.kt        ← TFLite 推理封装
            │   └── ui/theme/{Color.kt, Theme.kt}
            └── res/...
```

---

## 🚀 快速开始

### 前置要求
- **Android Studio** Hedgehog (2023.1) 或更新
- **Android SDK** 34
- **Python** 3.11+（仅训练用）
- 真机调试更佳（模拟器无相机）

### 方式 A：直接使用预训练模型
1. 用 Android Studio 打开 `android/` 目录
2. Sync Gradle，等依赖下载
3. Run → 选真机
4. **app/src/main/assets/** 里已经有 `ginseng_model.tflite` 和 `labels.txt`

### 方式 B：从零训练
```bash
# 1. 装依赖
pip install -r requirements.txt

# 2. 抓图（首跑可能要 5-10 分钟）
python training/fetch_images.py --per-keyword 50
python training/normalize_images.py
python training/fetch_more.py
python training/normalize_images.py

# 3. 划分 + 训练
python training/prepare_data.py
python training/train.py              # ~2 分钟（CPU）
python training/export_tflite.py
python training/test_tflite.py        # 验证模型

# 4. 同步到 Android
bash scripts/copy_assets.sh
```

---

## 📊 模型说明

| 项 | 值 |
|---|---|
| Backbone | MobileNetV3Small (alpha=0.75) |
| 预训练 | ImageNet |
| 输入 | 224×224×3 RGB, float32 [0, 255] |
| 输出 | 3 类 softmax |
| 参数量 | ~584K (2.23 MB) |
| TFLite 大小 | **~1.2 MB (float16)** |
| 推理速度 | 真机 ~10-30 ms (CPU) |
| 推理设备 | CPU（NNAPI 可选加速） |

### 类别与显示

| labels.txt | 中文显示 | 学名 | 科属 |
|---|---|---|---|
| `renshen` | 人参 | *Panax ginseng* | 五加科人参属 |
| `xiyangshen` | 西洋参 | *Panax quinquefolius* | 五加科人参属 |
| `jiegeng` | 桔梗 | *Platycodon grandiflorus* | 桔梗科桔梗属 |

---

## 📈 实测数据

当前模型在 **test 集（32 张）** 上的表现：

| 指标 | 数值 |
|---|---|
| Test accuracy | **62.5%** |
| Test loss | 0.93 |
| 训练耗时 | 111.5 秒（CPU） |

**混淆矩阵（test 集）**：

| true \ pred | jiegeng | renshen | xiyangshen |
|---|---|---|---|
| **jiegeng** | 5 | 2 | 4 |
| **renshen** | 0 | 6 | 5 |
| **xiyangshen** | 1 | 1 | 8 |

---

## ⚠️ 已知限制

### 数据是最大瓶颈
- 训练集仅 134 张（45/类），验证/测试 9-11/类
- 网络抓回的图含噪音（GIF、广告图、错误标注）
- **三类视觉非常相似**（特别是干燥切片形态）
- 实际应用中 60-70% 的准确率是预期范围

### 改进方向

**优先做**（影响最大）：
1. **扩充数据集** — 每类目标 300+ 张
   - 多角度拍摄（横切、纵切、整枝、粉末）
   - 不同光照、背景、相机型号
   - 手动清洗噪音图（用 `dataset/raw/` 下的 `*.jpg`）
2. **半自动标注** — 用 CLIP/GroundingDINO 过滤抓回来的图

**进阶做**：
3. 切分问题为「切片识别」+「整枝识别」两阶段
4. 试 EfficientNetV2-S（精度更好，~9MB TFLite）
5. 加 KAN/Attention 头让模型关注细节

---

## 🛠️ 关键技术点

### Android 端推理流程（`Classifier.kt`）
```kotlin
val input = ByteBuffer.allocateDirect(1 * 224 * 224 * 3 * 4)
    .order(ByteOrder.nativeOrder())
// 喂入 [0, 255] float32 RGB（模型内含 preprocess_input）
// 输出 3 类 softmax
```

### Python 训练 pipeline（`train.py`）
- **数据增强**：翻转/旋转/缩放/平移/对比度
- **两阶段训练**：先冻结 backbone warmup 5 epoch，再解冻后 30 层 finetune 15 epoch
- **MobileNetV3Small alpha=0.75**：速度/精度平衡

### TFLite 导出（`export_tflite.py`）
```python
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_types = [tf.float16]  # 减半体积
```

---

## 📜 免责声明

本应用为学习/演示目的，**不构成医疗或购药建议**。
人参/西洋参/桔梗的鉴别需结合：
- 显微特征（横切面结构、粉末特征）
- 化学成分（人参皂苷、桔梗皂苷等）
- 气味（西洋参特有清凉感、桔梗微腥）
- 产地与采购渠道

仅凭图像识别结果购药/用药存在风险。中药事关健康，请咨询专业中药师。

---

## License

MIT
