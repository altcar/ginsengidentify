package com.example.ginsengidentifier.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.ginsengidentifier.MainActivity
import com.example.ginsengidentifier.data.ScanStore
import com.example.ginsengidentifier.model.ScanStats

/**
 * Duolingo 风格桌面小组件：连续天数当主角，今日目标当进度条。
 * 点一下直接进扫描页。
 */
class GinsengWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        ScanStore.init(context)
        // 在 composition 之外取快照（lint: StateFlowValueCalledInComposition）
        val stats = ScanStore.stats.value
        provideContent {
            // 数据变化时由 WidgetUpdater.updateAll() 重新触发 provideGlance
            WidgetContent(stats)
        }
    }
}

@Composable
private fun WidgetContent(stats: ScanStats) {
    val jade = Color(0xFF0F4B3A)
    val jadeLight = Color(0xFF25977A)
    val cream = Color(0xFFF4EFE2)
    val muted = Color(0xFF9FB5AA)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(24.dp)
            .background(ColorProvider(jade))
            .padding(16.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${stats.streak}",
                style = TextStyle(
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorProvider(cream),
                ),
            )
            Spacer(GlanceModifier.width(6.dp))
            Column {
                Text(
                    text = "day streak",
                    style = TextStyle(fontSize = 13.sp, color = ColorProvider(muted)),
                )
                Text(
                    text = "Today ${stats.todayCount}/${stats.dailyGoal}",
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = ColorProvider(cream),
                    ),
                )
            }
        }

        Spacer(GlanceModifier.height(10.dp))
        LinearProgressIndicator(
            progress = stats.goalProgress,
            modifier = GlanceModifier.fillMaxWidth().height(8.dp).cornerRadius(4.dp),
            color = ColorProvider(jadeLight),
            backgroundColor = ColorProvider(Color(0x33F4EFE2)),
        )

        Spacer(GlanceModifier.height(12.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            StatChip("Ginseng", stats.countOf("renshen"), Color(0xFFD4575F))
            Spacer(GlanceModifier.width(8.dp))
            StatChip("American", stats.countOf("xiyangshen"), Color(0xFF2E8B6E))
            Spacer(GlanceModifier.width(8.dp))
            StatChip("Platycodon", stats.countOf("jiegeng"), Color(0xFF7C6BD6))
        }
    }
}

@Composable
private fun StatChip(name: String, count: Int, accent: Color) {
    Column(
        modifier = GlanceModifier
            .cornerRadius(12.dp)
            .background(ColorProvider(Color(0x1AF4EFE2)))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = "$count",
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = ColorProvider(accent),
            ),
        )
        Text(
            text = name,
            style = TextStyle(fontSize = 10.sp, color = ColorProvider(Color(0xFFC0C9C2))),
        )
    }
}
