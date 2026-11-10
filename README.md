# 参三味识别 (Ginseng Identifier)

> 端侧识别 Android 应用：**人参 / 西洋参 / 桔梗**，外加一个 `other` 类专门回答
> **"未检测到相关药材"**（不给错误答案比给错答案重要）。
> Kotlin + Jetpack Compose + TensorFlow Lite，离线推理，模型 1.2 MB。

![arch](https://img.shields.io/badge/Android-24%2B-green) ![kotlin](https://img.shields.io/badge/Kotlin-2.0-purple) ![tflite](https://img.shields.io/badge/TFLite-2.14-orange) ![license](https://img.shields.io/badge/license-MIT-blue)

---

## v2.1 修复（本轮）

| 问题 | 原因 | 处理 |
|---|---|---|
| **实时模式"有画面没结果 / 有结果没画面"** | Activity 绑 `Preview`、Service 绑 `ImageAnalysis`，两个 LifecycleOwner 各自 `bindToLifecycle`，CameraX 互相顶掉 | 抽成 `ScanEngine` + 单次 `bindToLifecycle(Preview, Analysis)`；`uiActive` 状态机保证同一时刻只有一个 owner |
| **拍什么都没有也报 93% 人参** | 三分类 softmax 恒和为 1，没有"什么都不是"的出口 | 加第 4 类 `other`（334 张负样本：货架/食物/招牌/文档 + 纯色/噪声/黑屏/过曝），再加 `FrameGate` 拦掉过暗/过曝/纯色帧 |
| **界面全是乱码（"cantonese"）** | 之前用 PowerShell 的 `Get-Content → Set-Content` 改文件，UTF-8 被按 ANSI 读入再以 UTF-8 写出，**双重编码** | 全仓库扫描并修复（`MainActivity.kt` 15 行、`clean_strict.py` 20 行），删除遗留的乱码脚本 |
| **Logo 丑** | 细线条骨架图，缩小后糊成一团 | 改成实心剪影：浑圆根头 + 收窄主干 + 两条外撇的腿 + 红果；同步换成 adaptive / monochrome 图标 |
| **扫码框** | UI 画了一个 QR 式方框 | 已去掉；识别结果用整屏卡片呈现 |
| **推送要花钱/要账号** | 只有本地通知 | 新增 ntfy.sh 免费通道（无账号、无 Firebase）+ 保留 FCM（凭据用 Gradle 属性注入，不提交密钥） |

> 编码这件事我复核过：仓库里**所有** `.kt/.py/.md/.xml/.json` 现在都不含双重编码，
> 并且 APK 里的 `app_name` 用 `aapt2 dump badging` 验证过是「参三味识别」。

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
    │   ├── ScanEngine.kt             ← 帧→结果 唯一管线（节流/防抖/计数）
    │   ├── FrameGate.kt              ← 过暗/过曝/纯色 帧级拒绝
    │   ├── ScannerService.kt         ← 前台服务：相机 + 常驻通知
    │   ├── OverlayService.kt         ← 悬浮窗
    │   ├── StreamServer.kt           ← 内置 WebSocket 服务端
    │   ├── ResultBus.kt              ← 结果总线（多消费者）
    │   └── ModelHolder.kt            ← 进程内共享 Interpreter
    ├── notify/
    │   ├── Notifications.kt          ← 通知渠道 / 常驻通知 / 提醒
    │   ├── ReminderWorker.kt         ← 每日打卡提醒
    │   ├── NtfySubscriber.kt         ← 免费远程推送（ntfy.sh, SSE）
    │   ├── FirebasePush.kt           ← FCM（Gradle 属性注入凭据）
    │   └── GinsengMessagingService.kt← FCM 消息 → 通知
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

## 推送（两条通道 + 价目）

### 1. 免费通道：ntfy.sh（现在就能用，无需账号）

App 订阅一个随机 topic（例如 `ginseng-k7m2x9pq`），用 SSE 长连接收消息：

```bash
# 任何设备、任何语言，只要能发 HTTP
curl -d "人参快用完了" https://ntfy.sh/ginseng-k7m2x9pq
```

- 首页「远程推送」卡片里可以看到/复制自己的 topic，点「发送测试」立刻验证链路
- topic 名就是密码，所以用随机串
- **价格**：公共服务器免费、无需注册，约 **250 条/天/IP**、消息缓存 12 小时；
  自建 ntfy 完全免费无限；付费档 Supporter **$6/mo**（$5 年付）、Pro **$12/mo**、Business **$25/mo**

### 2. FCM（保留，需要你自己的 Firebase 项目）

代码已经在仓库里：`notify/FirebasePush.kt` + `notify/GinsengMessagingService.kt`，
**没有**提交 `google-services.json`，也没加 google-services 插件（否则 clone 下来直接构建失败）。
凭据走 Gradle 属性注入，运行时手动 `FirebaseApp.initializeApp()`：

```properties
# android/gradle.properties  （已 gitignore）
FCM_PROJECT_ID=your-project
FCM_APP_ID=1:1234567890:android:abcdef
FCM_API_KEY=AIza...
FCM_SENDER_ID=1234567890
```

配置后 App 会自动订阅 `ginseng-alerts` 话题；服务端用 HTTP v1 发到该 topic 即可。

### 3. 价格

| 项目 | 价格 | 限额 |
|---|---|---|
| **FCM** | **$0**（Spark 和 Blaze 都免费） | 不限条数；600k 条/分钟/项目，240 条/分钟/单设备，单条 4 KB |
| ntfy.sh 公共 | **$0**，无需账号 | ~250 条/天/IP，缓存 12h |
| ntfy 自建 | **$0**（Apache-2.0） | 取决于你的服务器 |
| ntfy Supporter / Pro / Business | $6 / $12 / $25 每月（年付 $5 / $10 / $20） | 2.5k / 20k / 50k 条每天，可保留 topic |
| OneSignal（对照） | 免费档很小；$19/mo 起 | 免费档约 1k 订阅者 |

> 结论：**两个通道都不花钱**。FCM 免费但不含"发送端"——你得自己写服务端；
> ntfy 连服务端都省了，一条 `curl` 就能推。要长期稳定/自定义域名再考虑 $6/mo 的 ntfy。

### 4. 本地通知（始终可用）

识别结果提醒（25s 节流）+ 每天 20:00 打卡提醒（WorkManager），不依赖网络。

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
| + `other` 负类 | 334（quarantine 184 + 合成 150） |
| train / val / test | 992 / 212 / 216 |

详见 [`dataset/README.md`](dataset/README.md)。

### 指标

| 项 | 值 |
|---|---|
| Backbone | MobileNetV3Small (alpha=0.75) |
| 输入 | 224×224×3 float32 RGB，值域 [0,255] |
| 输出 | **4 类**（含 `other`） |
| TFLite | float16，**1209.5 KB** |
| val / test（Keras） | 0.8868 / 0.8611 |
| test（TFLite，与 Kotlin 预处理逐像素一致） | **0.8565** |

| 类别 | Precision | Recall | F1 | 样本 |
|---|---:|---:|---:|---:|
| 桔梗 jiegeng | 0.868 | 0.957 | 0.910 | 69 |
| **other（未检测到）** | **0.956** | **0.843** | 0.896 | 51 |
| 人参 renshen | 0.606 | **0.741** | 0.667 | 27 |
| 西洋参 xiyangshen | 0.903 | 0.812 | 0.855 | 69 |

```
混淆矩阵（TFLite，行=真实，列=预测）
                jiegeng     other   renshen  xiyangshen
     jiegeng         66         0         2           1
       other          1        43         7           0
     renshen          1         1        20           5
  xiyangshen          8         1         4          56
```

`other` 的召回 84.3% 意味着：随手拍桌面、货架、食物、白墙，八成会明确告诉你
**"未检测到相关药材"**，而不是硬报一个人参。人参 recall 也从 56% 提到 74% ——
负类把决策边界拉开了。

### ⚠️ 按数据来源拆分

| 来源 | 准确率 |
|---|---|
| iNaturalist + Wikimedia | **0.883**（181/205） |
| 旧版 Bing 商品图 | **0.364**（4/11） |

模型在植物学照片上很准，换到电商白底商品图就明显掉 —— 说明它学到的不只是形态，
还包括背景/拍摄风格。样本只有 11 张，但方向明确：**这不是"拍什么都能认"的模型**。

### 按拍摄域

| 域 | 准确率 |
|---|---|
| 整株 / 叶片 | 0.888（79/89） |
| 干燥根 / 切片 | 0.829（63/76） |
| 负样本（other） | 0.843（43/51） |

---

## 关于「物体检测 / 分割」

你要的是 YOLO 那种框/掩码输出，当前实现是**分类器 + 负类 + 帧级门控**，不是检测器。
原因很直接：训练 detector 需要**框或掩码标注**，而现有数据集只有图像级类别标签
（iNaturalist / Commons 给的是"这张图是某物种"，不是"药材在这个像素范围内"）。

| 能力 | 状态 |
|---|---|
| 判断"画面里有没有相关药材" | ✅ `other` 负类 + `FrameGate`（过暗 / 过曝 / 纯色直接拒绝） |
| 明确输出"未检测到相关药材" | ✅ 不再硬报三类之一 |
| 框出药材位置 | ❌ 未做 —— 没有框标注，硬画一个框只会更糟 |
| 像素级分割 | ❌ 未做 |

要真正补上检测，工程量大致是：

1. 用现有分类器筛高置信样本，用 SAM / GroundingDINO 生成候选掩码，人工复核
2. 约 1500–2000 张框标注（每类 400+，覆盖切片、整根、整株）
3. YOLOv8n-seg 或 MobileNetV3-DeepLab 训练 → TFLite（约 3–5 MB）
4. 端上两阶段：先分割裁 ROI，再分类 —— 顺带能解决"背景偏置 / 商品图 36%"

这需要 GPU 和标注时间，不是一次 commit 能做完的；这一轮先把
**误报**（93% 人参）和**相机互相顶掉**这两个真正影响可用性的问题解决了。

---

## 已知限制

1. **商品图域泛化差**（36%，n=11）—— 当前最大短板，两阶段 ROI 方案最有希望修它。
2. **人参 recall 74%** —— 可用图只有 175 张，切片与西洋参仍易混。
3. **没有框/掩码** —— 见上一节。
4. **WebSocket 无鉴权** —— 局域网演示用。
5. **ntfy 公共 topic 是公开的** —— 名字即密码，别用可猜的 topic 传敏感内容。
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
