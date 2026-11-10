package com.example.ginsengidentifier.notify

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.ginsengidentifier.data.ScanStore
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 打卡提醒。这就是"订阅推送"在无后端情况下的落地方式：
 * 本地 WorkManager 每天 20:00 检查一次，没扫就提醒。
 *
 * 真正的服务端推送（FCM）需要 Firebase 项目 + google-services.json，
 * 见 README「推送」一节 —— 没有凭据时不引入 Firebase 插件，避免构建直接挂掉。
 */
class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val app = applicationContext
        ScanStore.init(app)
        if (!ScanStore.isSubscribed()) return Result.success()
        Notifications.notifyStreak(app, ScanStore.stats.value)
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "ginseng-daily-reminder"

        fun schedule(context: Context) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 20)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
            }
            val delay = target.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }
    }
}
