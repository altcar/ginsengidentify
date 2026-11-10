package com.example.ginsengidentifier.scanner

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.ginsengidentifier.R
import com.example.ginsengidentifier.data.ScanStore
import com.example.ginsengidentifier.model.ScanResult
import com.example.ginsengidentifier.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 悬浮窗（YouTube 式小窗）：扫别的 App 时，识别结果一直浮在上面。
 *
 * 注意它**不自己开相机**——相机由 ScannerService 独占，这里只是 ResultBus 的订阅者。
 * 好处是省电、不会出现"两个进程抢相机"，坏处是扫描必须先启动。
 */
class OverlayService : Service() {

    companion object {
        private const val ID_OVERLAY = 1004
        const val ACTION_SHOW = "com.example.ginsengidentifier.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.example.ginsengidentifier.action.HIDE_OVERLAY"

        fun show(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, OverlayService::class.java).setAction(ACTION_SHOW)
            )
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)
    }

    private lateinit var windowManager: WindowManager
    private var root: View? = null
    private var titleView: TextView? = null
    private var bodyView: TextView? = null
    private var bar: ProgressBar? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var params: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        ScanStore.init(this)
        Notifications.ensureChannels(this)
        if (!canDraw(this)) {
            stopSelf()
            return
        }
        startAsForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlay()
        scope.launch {
            ResultBus.latest.collectLatest { render(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        root?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Throwable) {
            }
        }
        root = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    @SuppressLint("InlinedApi")
    private fun startAsForeground() {
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_stat_ginseng)
            .setContentTitle("悬浮窗已开启")
            .setContentText("识别结果会一直浮在屏幕上")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        // specialUse 类型只有 API 34 才认识，低版本传 0 表示不指定
        val type = if (Build.VERSION.SDK_INT >= 34) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, ID_OVERLAY, n as Notification, type)
    }

    // ── 悬浮窗 ──────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun addOverlay() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F2111815"))
                setStroke(dp(1), Color.parseColor("#33F4EFE2"))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = TextView(this).apply {
            text = "●"
            setTextColor(Color.parseColor("#8CD5B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        val label = TextView(this).apply {
            text = "  参三味 · 实时"
            setTextColor(Color.parseColor("#8A938C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#F4EFE2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { stopSelf() }
        }
        header.addView(dot)
        header.addView(label)
        header.addView(
            View(this),
            LinearLayout.LayoutParams(0, 0, 1f),
        )
        header.addView(close)

        titleView = TextView(this).apply {
            text = "等待识别…"
            setTextColor(Color.parseColor("#F4EFE2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }

        bodyView = TextView(this).apply {
            text = "把药材对准取景框"
            setTextColor(Color.parseColor("#C0C9C2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#8CD5B8"))
            minimumHeight = dp(4)
        }

        container.addView(header)
        container.addView(titleView)
        container.addView(bodyView)
        container.addView(bar, LinearLayout.LayoutParams(dp(150), dp(10)).apply { topMargin = dp(8) })

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(120)
        }

        // 拖动
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    try {
                        windowManager.updateViewLayout(container, params)
                    } catch (_: Throwable) {
                    }
                    true
                }
                else -> false
            }
        }

        root = container
        try {
            windowManager.addView(container, params)
        } catch (t: Throwable) {
            stopSelf()
        }
    }

    private fun render(result: ScanResult?) {
        if (result == null) {
            titleView?.text = "等待识别…"
            bodyView?.text = "把药材对准取景框"
            bar?.progress = 0
            return
        }
        titleView?.text = result.displayName
        titleView?.setTextColor(
            Color.parseColor(if (result.isConfident) "#F4EFE2" else "#FFB2BE")
        )
        bodyView?.text = if (result.isConfident) {
            "置信度 ${result.confidencePercent} · 连拍中"
        } else {
            "不太确定（${result.confidencePercent}）"
        }
        bar?.progress = (result.confidence * 100).toInt()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
