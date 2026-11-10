package com.example.ginsengidentifier.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.ginsengidentifier.MainActivity
import com.example.ginsengidentifier.R
import com.example.ginsengidentifier.model.ScanResult
import com.example.ginsengidentifier.model.ScanStats
import com.example.ginsengidentifier.scanner.ScannerService

/**
 * 通知中心：常驻通知（扫描中）+ 结果提醒 + 断签提醒。
 *
 * 常驻通知是"永远挂着"的那条：低优先级、静音、不可滑动清除，
 * 内容随识别结果实时更新。
 */
object Notifications {

    const val CHANNEL_LIVE = "live_scan_v2"
    const val CHANNEL_ALERTS = "alerts_v2"

    const val ID_LIVE = 1001
    const val ID_RESULT = 1002
    const val ID_STREAK = 1003
    const val ID_PUSH = 1005

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val live = NotificationChannel(
            CHANNEL_LIVE,
            "实时扫描",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "扫描时常驻显示当前识别结果"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "识别提醒 / 连续打卡",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "识别完成提醒、连续打卡提醒"
            setShowBadge(true)
        }

        nm.createNotificationChannel(live)
        nm.createNotificationChannel(alerts)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_SCANNER, true)
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 常驻通知：扫描中一直挂着，实时刷新结果。 */
    fun liveNotification(
        context: Context,
        result: ScanResult?,
        stats: ScanStats,
        scanning: Boolean,
        streamClients: Int,
    ): Notification {
        val title = when {
            !scanning -> "扫描已暂停"
            result == null -> "正在识别…把药材对准取景框"
            result.isOther -> "未检测到相关药材"
            result.isConfident -> "${result.displayName} · ${result.confidencePercent}"
            else -> "不太确定（${result.displayName} ${result.confidencePercent}）"
        }

        val bits = buildList {
            add("连续 ${stats.streak} 天")
            add("今日 ${stats.todayCount}/${stats.dailyGoal}")
            if (streamClients > 0) add("$streamClients 个订阅者")
        }

        val stopIntent = PendingIntent.getService(
            context, 1,
            Intent(context, ScannerService::class.java).setAction(ScannerService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_stat_ginseng)
            .setContentTitle(title)
            .setContentText(bits.joinToString(" · "))
            .setSubText("参三味识别 · 实时")
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stat_stop, "停止扫描", stopIntent)
            .build()
    }

    /** 订阅推送：识别结果提醒 / 打卡提醒。 */
    fun notifyResult(context: Context, result: ScanResult, stats: ScanStats) {
        if (!canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val text = if (result.isConfident) {
            "置信度 ${result.confidencePercent} · 今日 ${stats.todayCount}/${stats.dailyGoal}"
        } else {
            "置信度偏低，可能不属于这三类"
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_ginseng)
            .setContentTitle("识别到 ${result.displayName}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        nm.notify(ID_RESULT, n)
    }

    fun notifyStreak(context: Context, stats: ScanStats) {
        if (!canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val (title, text) = when {
            stats.todayCount == 0 && stats.streak > 0 ->
                "别断了连续 ${stats.streak} 天" to "今天还没扫过，花 10 秒保持一下？"
            stats.todayCount == 0 -> "今天还没开始" to "拍一张，开始你的识别连续记录"
            stats.todayCount >= stats.dailyGoal ->
                "今日目标已完成 🎉" to "连续 ${stats.streak} 天 · ${stats.todayCount}/${stats.dailyGoal}"
            else ->
                "还差 ${stats.dailyGoal - stats.todayCount} 张就到今天的目标" to
                    "连续 ${stats.streak} 天"
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_ginseng)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        nm.notify(ID_STREAK, n)
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    /** 远程推送（ntfy / FCM）统一入口。 */
    fun notifyPush(context: Context, title: String, body: String) {
        if (!canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_ginseng)
            .setContentTitle(title.ifBlank { "参三味识别" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        nm.notify(ID_PUSH, n)
    }
}
