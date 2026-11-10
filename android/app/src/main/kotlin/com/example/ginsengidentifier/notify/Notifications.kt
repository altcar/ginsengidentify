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
 * Notification centre: the sticky "scanning" notification, result alerts,
 * streak reminders and the shared remote-push entry point.
 *
 * All user-facing copy is English so it renders identically on every device
 * regardless of the installed CJK fonts.
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
            "Live scanning",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Always-on notification showing the current detection"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "Alerts & streaks",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Detection alerts, daily streak reminders and remote push"
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
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Sticky notification: always visible while scanning, updates live. */
    fun liveNotification(
        context: Context,
        result: ScanResult?,
        stats: ScanStats,
        scanning: Boolean,
        streamClients: Int,
    ): Notification {
        val title = when {
            !scanning -> "Scanning paused"
            result == null -> "Scanning… point at the herb"
            result.isOther -> "No herb detected"
            result.isConfident -> "${result.displayName} · ${result.confidencePercent}"
            else -> "Not sure (${result.displayName} ${result.confidencePercent})"
        }

        val bits = buildList {
            add("Streak ${stats.streak}d")
            add("Today ${stats.todayCount}/${stats.dailyGoal}")
            if (streamClients > 0) add("$streamClients subscriber(s)")
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
            .setSubText("Herb ID · live")
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stat_stop, "Stop", stopIntent)
            .build()
    }

    fun notifyResult(context: Context, result: ScanResult, stats: ScanStats) {
        if (!canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val text = if (result.isConfident) {
            "Confidence ${result.confidencePercent} · today ${stats.todayCount}/${stats.dailyGoal}"
        } else {
            "Low confidence — this may not be one of the three herbs"
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_ginseng)
            .setContentTitle("Detected ${result.displayName}")
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
                "Don't break your ${stats.streak}-day streak" to "No scans today yet — takes 10 seconds"
            stats.todayCount == 0 ->
                "Start your streak" to "Scan one herb to begin"
            stats.todayCount >= stats.dailyGoal ->
                "Daily goal complete" to "Streak ${stats.streak}d · ${stats.todayCount}/${stats.dailyGoal}"
            else ->
                "${stats.dailyGoal - stats.todayCount} to go today" to "Streak ${stats.streak}d"
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

    fun notifyPush(context: Context, title: String, body: String) {
        if (!canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_ginseng)
            .setContentTitle(title.ifBlank { "Herb ID" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        nm.notify(ID_PUSH, n)
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
    }
}
