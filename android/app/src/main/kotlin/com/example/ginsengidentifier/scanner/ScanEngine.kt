package com.example.ginsengidentifier.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.ginsengidentifier.Classifier
import com.example.ginsengidentifier.data.ScanStore
import com.example.ginsengidentifier.model.ScanResult
import com.example.ginsengidentifier.notify.Notifications
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 帧 -> 结果 的唯一处理管线。
 *
 * 为什么要抽出来：之前 Activity 绑 Preview、Service 绑 Analysis，两个
 * LifecycleOwner 各自 bindToLifecycle，结果互相顶掉 —— 要么有画面没结果，
 * 要么有结果没画面。现在同一时刻只有一个 owner，并且 Preview 和 Analysis
 * 在**同一次** bindToLifecycle 里一起绑定。
 *
 * 谁在跑：
 *   - 界面前台（含 PiP）: CameraPreview 绑 Preview + Analysis
 *   - 界面退到后台:      ScannerService 只绑 Analysis
 * 两边都调用 [onFrame]，节流/防抖状态也只有这一份，不会重复计数。
 */
object ScanEngine {

    private const val TAG = "ScanEngine"
    private const val FRAME_INTERVAL_MS = 320L
    private const val PUBLISH_INTERVAL_MS = 750L
    private const val RECORD_COOLDOWN_MS = 6000L
    private const val NOTIFY_COOLDOWN_MS = 25_000L
    private const val LIVE_NOTIFY_INTERVAL_MS = 900L

    /** true = 界面持有相机；Service 看到它就不要再绑。 */
    val uiActive = MutableStateFlow(false)

    private var appContext: Context? = null
    private var classifier: Classifier? = null

    private var lastFrameAt = 0L
    private var lastPublishAt = 0L
    private var lastRecordAt = 0L
    private var lastPushAt = 0L
    private var lastLiveNotifyAt = 0L
    private var lastRecordedLabel = ""
    private var pendingLabel = ""
    private var pendingCount = 0

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        ScanStore.init(app)
        classifier = ModelHolder.get(app)
    }

    fun isReady(): Boolean = classifier?.isReady() == true

    fun reset() {
        pendingLabel = ""
        pendingCount = 0
        lastFrameAt = 0L
    }

    @Synchronized
    fun onFrame(proxy: ImageProxy) {
        val ctx = appContext ?: run { proxy.close(); return }
        val clf = classifier ?: run { proxy.close(); return }

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

            // 先拦掉黑屏/白墙/纯色桌面
            val rejectReason = FrameGate.reject(input)
            if (rejectReason != null) {
                publishNoDetection(ctx, rejectReason, now)
                return
            }

            val preds = clf.classify(input)
            if (preds.isNotEmpty()) handle(ctx, preds, now)
        } catch (t: Throwable) {
            Log.w(TAG, "frame failed: ${t.message}")
        } finally {
            rotated?.let { if (!it.isRecycled) it.recycle() }
            source?.let { if (!it.isRecycled) it.recycle() }
            proxy.close()
        }
    }

    private fun handle(ctx: Context, preds: List<Classifier.Prediction>, now: Long) {
        val top = preds.first()
        val second = preds.getOrNull(1)

        // 两帧一致才认，避免镜头晃动时标签乱跳
        if (top.label == pendingLabel) pendingCount++ else {
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

        if (result.isOther) {
            // 模型自己判成"未检测到"：不计数、不推送
            val none = result.copy(note = "看起来不像这三类药材")
            if (now - lastPublishAt < PUBLISH_INTERVAL_MS) return
            lastPublishAt = now
            ResultBus.publish(none)
            updateLiveNotification(ctx, none, now)
            return
        }

        ResultBus.publish(result)
        maybeRecord(ctx, result, now)
        updateLiveNotification(ctx, result, now)
    }

    /** 画面不可用（太暗/过曝/纯色）：只更新 UI 和通知，不计数。 */
    private fun publishNoDetection(ctx: Context, reason: String, now: Long) {
        if (now - lastPublishAt < PUBLISH_INTERVAL_MS) return
        lastPublishAt = now
        pendingLabel = ""
        pendingCount = 0
        val result = ScanResult.none(reason)
        ResultBus.publish(result)
        updateLiveNotification(ctx, result, now)
    }

    private fun maybeRecord(ctx: Context, result: ScanResult, now: Long) {
        if (!result.isConfident) return
        val changed = result.label != lastRecordedLabel
        if (!changed && now - lastRecordAt < RECORD_COOLDOWN_MS) return

        lastRecordedLabel = result.label
        lastRecordAt = now
        val stats = ScanStore.record(result)

        if (ScanStore.isSubscribed() && now - lastPushAt >= NOTIFY_COOLDOWN_MS) {
            lastPushAt = now
            Notifications.notifyResult(ctx, result, stats)
        }
    }

    private fun updateLiveNotification(ctx: Context, result: ScanResult, now: Long) {
        if (now - lastLiveNotifyAt < LIVE_NOTIFY_INTERVAL_MS && lastLiveNotifyAt != 0L) return
        lastLiveNotifyAt = now
        val n = Notifications.liveNotification(
            ctx, result, ScanStore.stats.value, scanning = true,
            streamClients = StreamServer.clientCount(),
        )
        try {
            ctx.getSystemService(android.app.NotificationManager::class.java)
                ?.notify(Notifications.ID_LIVE, n)
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed: ${t.message}")
        }
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
}
