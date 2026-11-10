# 参三味识别 (Ginseng Identifier)

> 端侧三分类 Android 应用：**人参 / 西洋参 / 桔梗**。
> Kotlin + Jetpack Compose + TensorFlow Lite，离线推理，模型 1.2 MB。

![arch](https://img.shields.io/badge/Android-24%2B-green) ![kotlin](https://img.shields.io/badge/Kotlin-2.0-purple) ![tflite](https://img.shields.io/badge/TFLite-2.14-orange) ![license](https://img.shields.io/badge/license-MIT-blue)

---

## v2.0 新增

| 功能 | 说明 |
|---|---|
| **实时扫描** | 前置前台服务持相机，CameraX `ImageAnalysis` 约 3 fps 连续推理，两帧确认防抖 |
| **常驻通知栏** | 低优先级、静音、不可滑除的 ongoing 通知，实时显示当前结论 + 连续天数 |
| **WebSocket 串流** | 手机内置 `ws://<手机IP>:8765`，一行 JS 就能订阅识别结果 |
| **悬浮窗** | 类似 YouTube 小窗，浮在其他 App 之上，可拖动、可关闭 |
| **画中画 (PiP)** | 退到小窗继续识别，只显示结论 + 置信度条 |
| **推送订阅** | 识别结果提醒 + 每天 20:00 打卡提醒（WorkManager） |
| **Duolingo 风小组件** | 桌面卡：连续天数、今日进度条、三类计数，点一下直接开扫 |
| **新 Logo + 现代主题** | 手绘矢量「人参」标记（含 monochrome 主题图标）；玉色/奶油配色 + Material 3 动态取色 |

> ⚠️ **"推送"的边界**：真正的服务端推送（FCM）需要 Firebase 项目 + `google-services.json`，
> 本仓库没有凭据，所以没有引入 Firebase 插件（否则构建直接失败）。
> 当前实现的是**本地通知**：识别结果提醒、每日打卡提醒，逻辑与 UI 全部就绪。
> 接 FCM 的位置见 [「推送」](#推送) 一节。

---

## 项目结构

```
ginsengidentify/
├── README.md
├── requirements.txt
├── dataset/                          ← 数据 + 来源/许可说明
├── training/                         ← 抓图 / CLIP 清洗 / 训练 / 评估
├── model/
│   ├── ginseng_model.tflite          ← 端上模型（1208.7 KB）
│   ├── ginseng_model.keras
│   ├── metrics.json                  ← 训练指标 + 混淆矩阵
│   └── eval_tflite.json              ← 逐张推理明细
├── scripts/                          ← 同步并校验 assets
└── android/app/src/main/kotlin/com/example/ginsengidentifier/
    ├── MainActivity.kt               ← 导航 / 权限 / PiP
    ├── Classifier.kt                 ← TFLite 推理封装
    ├── model/ScanResult.kt           ← ScanResult + ScanStats
    ├── data/ScanStore.kt             ← 连续天数 / 今日次数 / 开关
    ├── scanner/
    │   ├── ScannerService.kt         ← 前台服务：相机 + 持续推理 + 常驻通知
    │   ├── OverlayService.kt         ← 悬浮窗
    │   ├── StreamServer.kt           ← 内置 WebSocket 服务端
    │   ├── ResultBus.kt              ← 结果总线（多消费者）
    │   └── ModelHolder.kt            ← 进程内共享 Interpreter
    ├── notify/
    │   ├── Notifications.kt          ← 通知渠道 / 常驻通知 / 提醒
    │   └── ReminderWorker.kt         ← 每日打卡提醒
    ├── ui/
    │   ├── Screens.kt                ← Home / Live / Result
    │   ├── CameraPreview.kt          ← 取景器（只绑 Preview）
    │   └── theme/                    ← 新配色 / 字阶 / 形状
    └── widget/                       ← Glance 桌面小组件
```

---

## 实时扫描是怎么跑的

```
        ┌──────────────────────────────┐
        │  ScannerService (前台服务)    │
        │  CameraX ImageAnalysis       │  ~3 fps
        │      ↓ RGBA → Bitmap         │
        │  Classifier (TFLite, 224)    │
        └──────────────┬───────────────┘
                       │ ResultBus (StateFlow)
     ┌─────────────────┼──────────────────┬───────────────┐
     ▼                 ▼                  ▼               ▼
 常驻通知          悬浮窗            WebSocket        Compose UI / 小组件
 (ongoing)       (OverlayService)     (:8765)         (StateFlow 订阅)
```

几个刻意的取舍：

- **相机只有一个持有者**。Activity 只绑 `Preview`（取景器），分析永远在 Service 里。
  两个 UseCase 绑在不同 LifecycleOwner 上是允许的，前提是**谁都不许调用 `unbindAll()`** ——
  这行代码是 v1 里"退出再进来预览黑屏"的根因。
- **两帧一致才认**。单帧结果抖动严重，连续两帧同标签（或置信度 ≥85%）才对外发布。
- **节流**：分析 320ms/帧、广播 750ms、通知 900ms、统计计数 6s（同标签）、推送 25s。
  不节流的话手机会烫得拿不住。
- **计数不是每次推理都 +1**，否则对着药材站 10 秒就能刷满今日目标。

---

## 权限

| 权限 | 用途 | 时机 |
|---|---|---|
| `CAMERA` | 实时扫描 / 拍照 | 点"开始实时扫描"时申请 |
| `POST_NOTIFICATIONS` | 常驻通知、结果提醒 | 打开"订阅推送"时申请（Android 13+） |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 | 打开"悬浮窗"时跳系统设置页 |
| `FOREGROUND_SERVICE_CAMERA` | 后台持续扫码 | 安装即声明 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 悬浮窗前台服务 | 安装即声明 |
| `INTERNET` | WebSocket 串流 | 安装即声明 |

拒绝任何一项都只是关闭对应功能，**不会闪退**（这是 v1 的主要问题）。

---

## WebSocket 串流

在首页打开「实时串流」，然后同一局域网内任意设备：

```js
const ws = new WebSocket('ws://192.168.1.23:8765');   // 换成手机 IP
ws.onmessage = e => console.log(JSON.parse(e.data));
```

```json
{"type":"hello","app":"ginseng-identifier","version":2,"classes":["renshen","xiyangshen","jiegeng"]}
{"label":"renshen","display":"人参","confidence":0.9321,
 "runner_up":"xiyangshen","runner_up_confidence":0.0512,
 "confident":true,"timestamp":1730000000000}
```

- 连上先收到一条 `hello`，之后每识别一次推一条
- 发任意包含 `ping` 的文本会收到 `{"type":"pong"}`
- 端口固定 `8765`，监听 `0.0.0.0`

> 🔒 **没有鉴权**：同一 WiFi 下任何人都能连。这版是局域网演示用途，
> 公网使用前必须加 token / TLS（`Java-WebSocket` 支持 `setWebSocketFactory` 走 wss）。

---

## 悬浮窗 / 画中画

- **PiP**：实时扫描页右上角小窗按钮，或系统手势触发；小窗里显示大号结论 + 置信度条。
- **悬浮窗**：实时扫描页点「悬浮窗」，授权后出现可拖动的结果卡片。
  它**不自己开相机**（避免抢相机），只是 `ResultBus` 的订阅者，所以必须先开扫描。

---

## 推送

当前实现（无需任何账号）：

| 场景 | 实现 |
|---|---|
| 识别结果提醒 | `ScannerService` → `Notifications.notifyResult()`，25s 节流 |
| 每日打卡提醒 | `ReminderWorker`，每天 20:00 检查，未完成目标才提醒 |
| 常驻状态 | 前台服务通知，实时更新 |

接 FCM（需要你自己建 Firebase 项目）：

1. Firebase 控制台建项目 → 添加 Android 应用（包名 `com.example.ginsengidentifier`）
2. 下载 `google-services.json` 放到 `android/app/`
3. `build.gradle.kts` 加 `id("com.google.gms.google-services") version "4.4.2"`
4. 依赖 `implementation("com.google.firebase:firebase-messaging-ktx:24.0.1")`
5. 实现 `FirebaseMessagingService.onMessageReceived()` → 调 `Notifications.notifyResult()`

服务端只负责把消息推给 FCM；识别仍然在端上，不需要上传照片。

---

## 桌面小组件

Glance 实现，4×2 卡片：

- 大字 **连续天数** + 🔥 今日 `n/10`
- 今日目标进度条
- 人参 / 西洋参 / 桔梗 三类累计计数
- 点击整卡 → 直接打开扫描页

数据源与 App 相同（`ScanStore`），每次识别后自动刷新。

---

## 构建与运行

```bash
cd android
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17 + Android SDK 34（`local.properties` 里写 `sdk.dir`，已被 git 忽略）。

### 真机验证

```bash
./gradlew connectedDebugAndroidTest
```

| 测试 | 覆盖 |
|---|---|
| `SmokeTest` | 模型加载、张量契约、三类可达、抽样准确率、极端输入、Activity 启动 |
| `FeatureTest` | 统计累加、常驻通知构建、WebSocket 启停、Service/组件声明、PiP 能力 |

> 本机无连接设备，以上仅完成**编译验证**（`assembleDebugAndroidTest` 通过）。

---

## 数据集与模型

数据来自 iNaturalist（研究级观察，按物种 ID）+ Wikimedia Commons，
CLIP 半自动剔除"提到人参但不是药材图"的噪声，再做感知哈希去重。

| 阶段 | 数量 |
|---|---|
| raw_dl（抓取） | 1175 |
| curated（CLIP 过滤后） | 1184 |
| clean（去重后） | 1086（455 / 175 / 456） |
| train / val / test | 759 / 162 / 165 |

详见 [`dataset/README.md`](dataset/README.md)。

### 指标

| 项 | 值 |
|---|---|
| Backbone | MobileNetV3Small (alpha=0.75) |
| 输入 | 224×224×3 float32 RGB，值域 [0,255] |
| TFLite | float16，**1208.7 KB** |
| val / test（Keras） | 0.8519 / 0.8485 |
| test（TFLite，与 Kotlin 预处理逐像素一致） | **0.8545** |

| 类别 | Precision | Recall | F1 |
|---|---:|---:|---:|
| 桔梗 | 0.877 | 0.928 | 0.901 |
| 人参 | 0.882 | **0.556** | 0.682 |
| 西洋参 | 0.827 | 0.899 | 0.861 |

```
混淆矩阵（TFLite，行=真实，列=预测）
                jiegeng   renshen  xiyangshen
     jiegeng         64         0           5
     renshen          4        15           8
  xiyangshen          5         2          62
```

### ⚠️ 按数据来源拆分

| 来源 | 准确率 |
|---|---|
| iNaturalist + Wikimedia | **0.890**（137/154） |
| 旧版 Bing 商品图 | **0.364**（4/11） |

模型在植物学照片上很准，换到电商白底商品图就明显掉 —— 说明它学到的不只是形态，
还包括背景/拍摄风格。样本只有 11 张，但方向明确：**这不是"拍什么都能认"的模型**。

### 按拍摄域

| 域 | 准确率 |
|---|---|
| 整株 / 叶片 | 0.876（78/89） |
| 干燥根 / 切片 | 0.829（63/76） |

---

## 已知限制

1. **商品图域泛化差**（36%，n=11）—— 当前最大短板。
2. **人参 recall 只有 56%** —— 可用图只有 175 张，且切片与西洋参极像。
3. **没有"未知"类** —— 一定会选三类之一，只能靠 <55% 提示。
4. **WebSocket 无鉴权** —— 局域网演示用。
5. **推送依赖系统通知** —— 没有 FCM 就没有真正的跨设备/离线推送。
6. **持续扫描耗电** —— 3 fps + 前台服务，长时间开会烫；建议按需开启。

### 下一步

1. 补商品图/白底图（每类 200+），单独统计该域准确率
2. 补人参数据（目标 400+）
3. 增加 `other / 未知` 负类，做开集拒识
4. WebSocket 加 token + wss
5. 接 FCM，把"订阅推送"做成真推送

---

## 免责声明

本应用仅用于学习与演示，**不构成医疗或购药建议**。人参 / 西洋参 / 桔梗的鉴别需要结合
显微特征、化学成分（人参皂苷、桔梗皂苷）、气味与产地渠道。仅凭图像识别结果购药、用药存在风险。

## License

MIT
