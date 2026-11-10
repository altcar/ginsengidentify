# 参三味识别 (Ginseng Identifier)

> 端侧三分类 Android 应用：**人参 / 西洋参 / 桔梗**。
> Kotlin + Jetpack Compose + TensorFlow Lite，离线推理，模型 1.2 MB。

![arch](https://img.shields.io/badge/Android-24%2B-green) ![kotlin](https://img.shields.io/badge/Kotlin-2.0-purple) ![tflite](https://img.shields.io/badge/TFLite-2.14-orange) ![license](https://img.shields.io/badge/license-MIT-blue)

---

## 这一版改了什么

旧版有三个硬伤，这一版逐条修了：

| 问题 | 旧版 | 现在 |
|---|---|---|
| 数据集 | 195 张 Bing 抓图，混着广告/包装盒/重复图，test 只有 32 张 | 1086 张，来自 iNaturalist + Wikimedia Commons，CLIP 过滤 + 感知哈希去重 |
| 准确率 | test **62.5%** | test **84.9%**（Keras）/ **85.5%**（TFLite 端到端） |
| fine-tune 是假的 | `model.layers[2]` 取错了层，解冻的是数据增强层 | 按名字取 backbone，实测 40 层解冻 |
| 点拍照就闪退 | Android 11+ 缺 `<queries>` → `ActivityNotFoundException` | 已补 `<queries>` + 异常兜底 |
| 大图闪退 | 全尺寸 `decodeStream` → OOM | 按 `inSampleSize` 降采样到 1600px，捕获 `OutOfMemoryError` |
| 卡顿/ANR | 解码 + 推理都在主线程 | 全部挪到 `Dispatchers.IO` |
| 预处理不一致 | Android 拉伸变形，训练是等比缩放 | 两端统一「中心裁剪 → 双线性缩放到 224」 |

---

## 项目结构

```
ginsengidentify/
├── README.md
├── requirements.txt
├── dataset/
│   ├── README.md                    ← 数据来源 / 许可 / 规模
│   ├── raw/                         ← 旧版 Bing 图（来源不明，仅作商品图补充）
│   ├── processed/{train,val,test}/  ← 训练用切分
│   └── ...
├── training/
│   ├── fetch_dataset.py             ← 从 iNaturalist / Wikimedia 抓图（可断点续抓）
│   ├── filter_clip.py               ← CLIP 半自动清洗 + 拍摄域标注
│   ├── clean_strict.py              ← 解码校验 + dHash 去重（含跨类冲突）
│   ├── prepare_data.py              ← 分层切分 70/15/15
│   ├── train.py                     ← MobileNetV3Small 两阶段迁移学习
│   ├── export_tflite.py             ← 导出 float16 TFLite
│   └── test_tflite.py               ← 端到端验证（预处理与 Kotlin 完全一致）
├── model/
│   ├── ginseng_model.tflite         ← ⭐ 端上模型
│   ├── ginseng_model.keras          ← 可继续训练
│   ├── labels.txt
│   ├── metrics.json                 ← 训练指标 + 混淆矩阵
│   └── eval_tflite.json             ← 逐张推理明细
├── scripts/
│   ├── sync_assets.py               ← 同步 + 校验 assets
│   └── copy_assets.sh
└── android/
    └── app/src/main/kotlin/com/example/ginsengidentifier/
        ├── MainActivity.kt          ← Compose UI
        ├── Classifier.kt            ← TFLite 推理封装
        └── ui/theme/
```

---

## 快速开始

### 直接构建 APK

```bash
cd android
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
# 产物: android/app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17 + Android SDK 34（`local.properties` 里写 `sdk.dir`，该文件已被 git 忽略）。

### 从零重训

```bash
pip install -r requirements.txt

python training/fetch_dataset.py      # 抓图（约 10 分钟）
python training/filter_clip.py        # CLIP 过滤噪声
python training/clean_strict.py       # 去重
python training/prepare_data.py       # 切分
python training/train.py              # 训练 + 自动导出 tflite（CPU 约 5.5 分钟）
python training/test_tflite.py        # 端到端验证
python scripts/sync_assets.py         # 同步进 Android
```

---

## 数据集

详见 [`dataset/README.md`](dataset/README.md)。要点：

- **来源可信**：iNaturalist 按物种 ID 取图（936 张），Wikimedia Commons 按条目搜索（239 张）
- **清洗不是摆设**：CLIP 把"货架上的包装盒 / 人参鸡汤 / 街头招牌 / 古籍书页"这类
  "提到了人参但不是药材图"剔掉；跨类近重复（解码后 pHash 距离 ≤6）直接丢弃
- **规模**：clean 1086 张（455 / 175 / 456），train 759 / val 162 / test 165
- **两类拍摄域**：整株叶片（≈650 张，主要来自 iNaturalist）与干燥根/切片（≈340 张）

> 为什么必须分域统计：真机拍的是干燥药材，而 iNaturalist 给的是活体植物。
> 只看总准确率会自我感觉良好。

---

## 模型与指标

| 项 | 值 |
|---|---|
| Backbone | MobileNetV3Small (alpha=0.75, ImageNet) |
| 输入 | 224×224×3 float32，RGB，值域 [0,255]（`preprocess_input` 已烧进图里） |
| 输出 | 3 类 softmax |
| TFLite | float16，**1208.7 KB** |
| 训练 | 8 epoch warmup（冻结）+ 25 epoch fine-tune（解冻 40 层），CPU 270.4s |

### test 集（165 张，与训练/验证无重叠）

| 类别 | Precision | Recall | F1 | 样本 |
|---|---:|---:|---:|---:|
| 桔梗 jiegeng | 0.877 | 0.928 | 0.901 | 69 |
| 人参 renshen | 0.882 | **0.556** | 0.682 | 27 |
| 西洋参 xiyangshen | 0.827 | 0.899 | 0.861 | 69 |

```
混淆矩阵（TFLite，行=真实，列=预测）
                jiegeng   renshen  xiyangshen
     jiegeng         64         0           5
     renshen          4        15           8
  xiyangshen          5         2          62
```

**总体 85.5%**（TFLite，预处理与 Kotlin 逐像素一致）。

### ⚠️ 按数据来源拆分 —— 这一张表最重要

同一个 test 集，按图片来源拆开后差距极大：

| 来源 | 准确率 |
|---|---|
| iNaturalist + Wikimedia（新数据） | **0.890**（137/154） |
| 旧版 Bing 商品图（`dataset/raw/`） | **0.364**（4/11） |

也就是说：模型在"植物学照片"上很准，但一换到**电商白底商品图**就明显掉，
说明它学到的不只是药材形态，还包括背景/拍摄风格等域特征。
样本只有 11 张，置信区间很宽，但方向是明确的 —— **这个模型不能当成
"拍什么都能认"，它只在自己见过的拍摄域里可靠。**

### 按拍摄域拆分

| 域 | 准确率 |
|---|---|
| 整株 / 叶片 | 0.876（78/89） |
| 干燥根 / 切片 | 0.829（63/76） |

### 置信度

预测正确时平均置信度 0.917，预测错误时 0.679 —— **错的那批也很自信**，
所以 App 端低于 55% 只给提示、不给"这肯定不是三类之一"的承诺。

---

## 关于"点开就闪退"

代码里逐条修掉的原因（都是真机上必然复现的）：

1. **`<queries>` 缺失（Android 11+）**
   `MediaStore.ACTION_IMAGE_CAPTURE` 受包可见性限制。没有声明时
   `launcher.launch()` 直接抛 `ActivityNotFoundException`，没 try/catch 就是闪退。
   → `AndroidManifest.xml` 补 `<queries>`，`MainActivity` 侧再兜一层异常。

2. **全尺寸解码 OOM**
   相机原图 4000×3000，`BitmapFactory.decodeStream` 一次吃掉 ~48 MB。
   低端机直接 `OutOfMemoryError`（不是 Exception，抓不到）。
   → `decodeSampled()` 先读 bounds 再按 `inSampleSize` 降到 1600px 以内，
   并且 `catch (Throwable)`。

3. **主线程做重活**
   解码 + 旋转 + 推理全在 UI 线程，轻则掉帧重则 ANR。
   → `withContext(Dispatchers.IO)`，主线程只渲染。

4. **输出数组按 `labels.size` 硬编码**
   模型一换、类别数一变就是 `ArrayIndexOutOfBoundsException`。
   → `Classifier.load()` 校验输入 shape / dtype / 输出维度，不一致就返回
   明确中文错误，且 `load()` 捕获 `Throwable`（`UnsatisfiedLinkError` 也能兜住）。

5. **相机 Uri 在配置变更后丢失**
   旋转屏幕/切深色模式后 Activity 重建，`cameraUri` 变 null，回调拿不到图。
   → `rememberSaveable` 存 Uri 字符串。

6. **FileProvider 指向不存在的文件**
   某些相机 App 拿到不存在的 Uri 会失败。
   → `createNewFile()` 后再 `getUriForFile`，并顺手清理 24h 前的缓存。

7. **推理后 `recycle()` 时序**
   回收中间 Bitmap 时若与 Compose 绘制同帧，会 `Canvas: trying to use a recycled bitmap`。
   → 只回收自己创建的中间图，界面正在用的那张不碰。

另外删掉了用不上的 `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`：
相册走 Photo Picker，本来就不需要存储权限。

---

## 验证

### 能自动化跑的部分

```bash
cd android

./gradlew lintDebug            # 0 error（37 个 warning 全是"依赖有新版本"）
./gradlew assembleDebug        # APK 35.2 MB
./gradlew assembleDebugAndroidTest
```

已核对：

- `aapt2 dump xmltree` → 只申请 `CAMERA`，`<queries>` 含 `IMAGE_CAPTURE`，FileProvider authority 正确
- APK 内 `assets/ginseng_model.tflite` 的 sha256 与 `model/ginseng_model.tflite` **完全一致**
- `training/test_tflite.py` 复刻 Kotlin 的预处理（中心裁剪 + 双线性 + [0,255] RGB），
  在 165 张 test 图上跑出 85.5%，说明"电脑上准、手机上乱猜"的预处理错位已消除

### 真机冒烟测试（需要插上手机 / 开模拟器）

```bash
cd android
./gradlew connectedDebugAndroidTest
```

`androidTest/.../SmokeTest.kt` 覆盖：

| # | 用例 | 验证的功能 |
|---|---|---|
| 1 | `modelLoads` | 模型 + labels 能加载 |
| 2 | `predictionsAreWellFormed` | 输出 3 类、降序、softmax 归一化、中文名映射 |
| 3 | `allThreeClassesAreReachable` | 三张代表图至少预测出 2 个不同类别（没退化成常数） |
| 4 | `sampleAccuracyIsReasonable` | 6 张抽样图至少对 4 张 |
| 5 | `handlesDegenerateInputs` | 纯白图 / 1×1 / 800×200 非正方形 / 全透明，都不崩 |
| 6 | `activityLaunchesWithoutCrashing` | Activity 能起来且不立即结束 |

> 本机没有连接设备，所以第 1–6 项目前只完成**编译验证**（`assembleDebugAndroidTest` 通过）。
> 连上手机后跑一条命令即可得到真机结果。
---

## 已知限制（别被 85% 骗了）

1. **商品图域泛化很差**：新来源 89%，旧 Bing 商品图只有 36%（n=11）。
   这是当前最大的问题 —— 模型对"拍摄风格/背景"过拟合。
   想真正用于电商图或白底拍摄，必须补对应域的样本再训。
2. **人参 recall 只有 56%**：人参只有 175 张可用图（*Panax ginseng* 野生记录本来就少），
   且干燥切片与西洋参极易混淆。模型倾向于漏判人参，而不是误报。
3. **干燥切片是硬任务**：三者切片在 RGB 上差异极小，专业鉴别依赖显微结构、气味、皂苷含量。
4. **没有"未知"类**：模型一定在三类里选一个，只能靠 <55% 提示，不能真正拒识。
5. **训练集含旧版 195 张来源不明的 Bing 图**（`dataset/raw/`），若对外分发需先剔除。

### 下一步优先级

1. **补商品图/白底图**（每类 200+），并单独统计该域准确率 —— 目前最大短板
2. 补人参数据（目标 400+，重点补干燥整根与切片）
3. 增加 `other / 未知` 负类，做真正的开集拒识
4. 试 256 或 288 输入分辨率（`Classifier` 已经从张量读输入尺寸，不用改代码）
5. 二阶段：先分「整株 / 干燥根」，再分物种

---

## 免责声明

本应用仅用于学习与演示，**不构成医疗或购药建议**。人参 / 西洋参 / 桔梗的鉴别需要结合
显微特征、化学成分（人参皂苷、桔梗皂苷）、气味与产地渠道。仅凭图像识别结果购药、用药存在风险。

## License

MIT
