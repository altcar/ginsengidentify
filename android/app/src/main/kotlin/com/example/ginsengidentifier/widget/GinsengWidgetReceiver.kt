package com.example.ginsengidentifier.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GinsengWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GinsengWidget()
}

/** 数据变化后主动刷新所有小组件实例。 */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun request(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                GinsengWidget().updateAll(app)
            } catch (_: Throwable) {
                // 没有桌面小组件时 updateAll 也会正常返回，这里只是兜底
            }
        }
    }
}
