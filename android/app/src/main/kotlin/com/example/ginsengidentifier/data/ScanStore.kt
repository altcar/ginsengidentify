package com.example.ginsengidentifier.data

import android.content.Context
import com.example.ginsengidentifier.model.ScanResult
import com.example.ginsengidentifier.model.ScanStats
import com.example.ginsengidentifier.widget.WidgetUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地统计（SharedPreferences 就够了，数据量很小）。
 *
 * 负责：连续天数 streak、今日次数、每类计数、订阅开关、WebSocket 开关。
 * 首页 / 小组件 / 通知都从这里取数。
 */
object ScanStore {

    private const val PREF = "ginseng_scan"
    private const val KEY_STREAK = "streak"
    private const val KEY_LAST_DAY = "last_day"
    private const val KEY_TODAY = "today_count"
    private const val KEY_TOTAL = "total_count"
    private const val KEY_SUBSCRIBED = "subscribed"
    private const val KEY_WS = "ws_enabled"
    private const val KEY_GOAL = "daily_goal"
    private const val PREFIX_COUNT = "count_"

    private val _stats = MutableStateFlow(ScanStats())
    val stats: StateFlow<ScanStats> = _stats

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    private fun prefs() = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun dayBefore(key: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val d = fmt.parse(key) ?: return ""
        return fmt.format(Date(d.time - 24L * 60 * 60 * 1000))
    }

    fun refresh() {
        if (!::appContext.isInitialized) return
        val p = prefs()
        val today = todayKey()
        // 跨天：今日次数归零；昨天没扫过就断 streak
        var todayCount = p.getInt(KEY_TODAY, 0)
        val lastDay = p.getString(KEY_LAST_DAY, "") ?: ""
        if (lastDay != today) {
            todayCount = 0
            p.edit().putInt(KEY_TODAY, 0).putString(KEY_LAST_DAY, today).apply()
        }
        val known = listOf("renshen", "xiyangshen", "jiegeng")
        val counts = known.associateWith { p.getInt(PREFIX_COUNT + it, 0) }
        _stats.value = ScanStats(
            streak = p.getInt(KEY_STREAK, 0),
            todayCount = todayCount,
            dailyGoal = p.getInt(KEY_GOAL, 10),
            totalCount = p.getInt(KEY_TOTAL, 0),
            counts = counts,
        )
    }

    /** 记录一次扫描。返回最新统计（方便调用方直接拿去发通知/刷新组件）。 */
    fun record(result: ScanResult): ScanStats {
        if (!::appContext.isInitialized) return _stats.value
        val p = prefs()
        val today = todayKey()
        val lastDay = p.getString(KEY_LAST_DAY, "") ?: ""

        var streak = p.getInt(KEY_STREAK, 0)
        if (lastDay != today) {
            streak = when (lastDay) {
                "" -> 1
                dayBefore(today) -> streak + 1
                else -> 1
            }
        } else if (streak == 0) {
            streak = 1
        }

        val todayCount = p.getInt(KEY_TODAY, 0) + 1
        val total = p.getInt(KEY_TOTAL, 0) + 1
        val labelCount = p.getInt(PREFIX_COUNT + result.label, 0) + 1

        p.edit()
            .putInt(KEY_STREAK, streak)
            .putString(KEY_LAST_DAY, today)
            .putInt(KEY_TODAY, todayCount)
            .putInt(KEY_TOTAL, total)
            .putInt(PREFIX_COUNT + result.label, labelCount)
            .apply()

        refresh()
        WidgetUpdater.request(appContext)
        return _stats.value
    }

    fun isSubscribed(): Boolean = prefs().getBoolean(KEY_SUBSCRIBED, false)

    fun setSubscribed(value: Boolean) {
        prefs().edit().putBoolean(KEY_SUBSCRIBED, value).apply()
    }

    fun isStreamEnabled(): Boolean = prefs().getBoolean(KEY_WS, false)

    fun setStreamEnabled(value: Boolean) {
        prefs().edit().putBoolean(KEY_WS, value).apply()
    }

    fun dailyGoal(): Int = prefs().getInt(KEY_GOAL, 10)

    fun setDailyGoal(goal: Int) {
        prefs().edit().putInt(KEY_GOAL, goal.coerceIn(1, 100)).apply()
        refresh()
    }
}
