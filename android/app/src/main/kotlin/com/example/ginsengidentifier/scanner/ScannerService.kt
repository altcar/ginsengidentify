package com.example.ginsengidentifier.scanner

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.ginsengidentifier.Classifier
import com.example.ginsengidentifier.data.ScanStore
import com.example.ginsengidentifier.model.ScanResult
import com.example.ginsengidentifier.notify.Notifications
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 常驻实时扫描服务。
 *
 * 相机只有一个持有者：本服务。结果通过 [ResultBus] 分发给
 * 通知栏 / 悬浮窗 / WebSocket / Compose UI / 桌面小组件。
 *
 * 为什么不把相机放在 Activity 里：进 PiP、退到后台、锁屏后
 * 仍然要出结果，只有前台服务能保证这一点（Android 10+ 后台不能用相机）。
 */
class ScannerService : Service() {

    companion object {
        private const val TAG = "ScannerService"

        const val ACTION_START = "com.example.ginsengidentifier.action.START_SCAN"
        const val ACTION_STOP = "com.example.ginsengidentifier.action.STOP_SCAN"

        /** 每帧分析间隔：约 3 fps 足够，再快只会发热。 */
        private const val FRAME_INTERVAL_MS = 320L

        /** 结果对外广播的最小间隔，避免 UI/通知疯狂刷新。 */
        private const val PUBLISH_INTERVAL_MS = 750L

        /** 同一次"识别"重复计数的最小间隔。 */
        private const val RECORD_COOLDOWN_MS = 6000L

        /** 订阅推送的最小间隔。 */
        private const val NOTIFY_COOLDOWN_MS = 25_000L

        fun start(context: Context) {
            val intent = Intent(context, ScannerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScannerService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Throwable) {
                context.stopService(Intent(context, ScannerService::class.java))
            }
        }
    }

    private lateinit var classifier: Classifier
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private lateinit var analysisExecutor: ExecutorService
    private val serviceLifecycle = ServiceLifecycleOwner()

    private var lastFrameAt = 0L
    private var lastPublishAt = 0L
    private var lastNotifyAt = 0L
    private var lastLiveNotifAt = 0L
    private var lastRecordAt = 0L
    private var lastRecordedLabel = ""
    private var pendingLabel = ""
    private var pendingCount = 0

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        ScanStore.init(this)

        // Android 14 起，带 camera 类型调 startForeground 却没有相机权限会直接
        // 抛 SecurityException（闪退）。必须先检查，再 startForeground。
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "没有相机权限，不启动前台服务")
            stopSelf()
            return
        }

        analysisExecutor = Executors.newSingleThreadExecutor()
        classifier = ModelHolder.get(this)
        if (!classifier.isReady()) {
            Log.e(TAG, "模型加载失败: ${classifier.lastError}")
            stopSelf()
            return
        }

        serviceLifecycle.resume()
        ServiceCompat.startForeground(
            this,
            Notifications.ID_LIVE,
            Notifications.liveNotification(
                this, null, ScanStore.stats.value, scanning = true, streamClients = 0
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )

        ResultBus.setScanning(true)
        if (ScanStore.isStreamEnabled()) {
            StreamServer.start()
        }
        bindCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            analysisUseCase?.let { cameraProvider?.unbind(it) }
        } catch (_: Throwable) {
        }
        analysisUseCase = null
        cameraProvider = null
        if (::analysisExecutor.isInitialized) analysisExecutor.shutdown()
        // classifier 由 ModelHolder 共享，这里不能 close，否则前台预览/AI 会被连带关掉
        StreamServer.stop()
        ResultBus.setScanning(false)
        serviceLifecycle.destroy()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ── 相机 ────────────────────────────────────────────────────────────

    private fun bindCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "没有相机权限，停止服务")
            stopSelf()
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { proxy -> onFrame(proxy) }

                // 注意：不能 unbindAll()，否则会把前台 Compose 预览一起解绑。
                // 只解绑自己上一次的分析用例。
                try {
                    analysisUseCase?.let { provider.unbind(it) }
                } catch (_: Throwable) {
                }
                provider.bindToLifecycle(
                    serviceLifecycle,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis,
                )
                analysisUseCase = analysis
                Log.i(TAG, "camera bound")
            } catch (t: Throwable) {
                Log.e(TAG, "bind camera failed", t)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < FRAME_INTERVAL_MS) {
            proxy.close()
            return
        }
        lastFrameAt = now

        var source: Bitmap? = null
        var rotated: Bitmap? = null
        try {
            source = proxy.toBitmap()
            val degrees = proxy.imageInfo.rotationDegrees
            val input = if (degrees == 0) source else rotate(source, degrees)
            if (input !== source) rotated = input

            val preds = classifier.classify(input)
            if (preds.isNotEmpty()) handlePredictions(preds, now)
        } catch (t: Throwable) {
            Log.w(TAG, "frame failed: ${t.message}")
        } finally {
            rotated?.let { if (!it.isRecycled) it.recycle() }
            source?.let { if (!it.isRecycled) it.recycle() }
            proxy.close()
        }
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun handlePredictions(preds: List<Classifier.Prediction>, now: Long) {
        val top = preds.first()
        val second = preds.getOrNull(1)

        // 两帧一致才认，避免镜头晃动时标签乱跳
        if (top.label == pendingLabel) {
            pendingCount++
        } else {
            pendingLabel = top.label
            pendingCount = 1
        }
        val confirmed = pendingCount >= 2 || top.confidence >= 0.85f
        if (!confirmed) return
        if (now - lastPublishAt < PUBLISH_INTERVAL_MS) return
        lastPublishAt = now

        val result = ScanResult(
            label = top.label,
            displayName = top.displayName,
            confidence = top.confidence,
            runnerUpLabel = second?.label ?: "",
            runnerUpConfidence = second?.confidence ?: 0f,
        )
        ResultBus.publish(result)
        maybeRecord(result, now)
        updateLiveNotification(result, now)
    }

    private fun maybeRecord(result: ScanResult, now: Long) {
        if (!result.isConfident) return
        val changed = result.label != lastRecordedLabel
        if (!changed && now - lastRecordAt < RECORD_COOLDOWN_MS) return

        lastRecordedLabel = result.label
        lastRecordAt = now
        val stats = ScanStore.record(result)

        if (ScanStore.isSubscribed() && now - lastNotifyAt >= NOTIFY_COOLDOWN_MS) {
            lastNotifyAt = now
            Notifications.notifyResult(this, result, stats)
        }
    }

    private fun updateLiveNotification(result: ScanResult, now: Long) {
        // 常驻通知刷太勤会被系统限流，1 秒一次足够
        if (now - lastLiveNotifAt < 900L && lastLiveNotifAt != 0L) return
        lastLiveNotifAt = now
        val n = Notifications.liveNotification(
            this, result, ScanStore.stats.value, scanning = true,
            streamClients = StreamServer.clientCount(),
        )
        try {
            getSystemService(android.app.NotificationManager::class.java)
                ?.notify(Notifications.ID_LIVE, n)
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed: ${t.message}")
        }
    }

    /** 让 CameraX 可以绑定到 Service（Service 本身不是 LifecycleOwner）。 */
    private class ServiceLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
