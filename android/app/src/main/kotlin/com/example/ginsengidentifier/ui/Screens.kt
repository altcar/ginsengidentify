package com.example.ginsengidentifier.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ginsengidentifier.Classifier
import com.example.ginsengidentifier.R
import com.example.ginsengidentifier.model.ScanResult
import com.example.ginsengidentifier.model.ScanStats
import com.example.ginsengidentifier.ui.theme.ClassJiegeng
import com.example.ginsengidentifier.ui.theme.ClassRenshen
import com.example.ginsengidentifier.ui.theme.ClassUnknown
import com.example.ginsengidentifier.ui.theme.ClassXiyangshen
import android.graphics.Bitmap

@Composable
fun LogoMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_logo_mark),
        contentDescription = "参三味识别",
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
    )
}

@Composable
fun HomeScreen(
    stats: ScanStats,
    modelReady: Boolean,
    subscribed: Boolean,
    streamEnabled: Boolean,
    streamClients: Int,
    overlayEnabled: Boolean,
    onStartScan: () -> Unit,
    onCamera: () -> Unit,
    onPick: () -> Unit,
    onToggleSubscribe: (Boolean) -> Unit,
    onToggleStream: (Boolean) -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ── Hero ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1B7A5E), Color(0xFF0C4033))
                    )
                )
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(Modifier.size(52.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "参三味识别",
                            color = Color(0xFFF4EFE2),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            "人参 · 西洋参 · 桔梗",
                            color = Color(0xFFA9C7BA),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "对准药材，实时出结果",
                    color = Color(0xFFF4EFE2),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "本地推理，不上传照片。结果会同步到通知栏、悬浮窗和桌面小组件。",
                    color = Color(0xFFA9C7BA),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onStartScan,
                    enabled = modelReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8CD5B8),
                        contentColor = Color(0xFF003829),
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始实时扫描", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        if (!modelReady) {
            InfoCard(
                title = "模型加载失败",
                body = "识别不可用。请重新安装 APK 或检查 assets 里的模型文件。",
                accent = MaterialTheme.colorScheme.error,
            )
        }

        // ── 连续打卡 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.LocalFireDepartment,
                        contentDescription = null,
                        tint = ClassRenshen,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "连续 ${stats.streak} 天",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "今日 ${stats.todayCount}/${stats.dailyGoal}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { stats.goalProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClassChip("人参", stats.countOf("renshen"), ClassRenshen, Modifier.weight(1f))
                    ClassChip("西洋参", stats.countOf("xiyangshen"), ClassXiyangshen, Modifier.weight(1f))
                    ClassChip("桔梗", stats.countOf("jiegeng"), ClassJiegeng, Modifier.weight(1f))
                }
            }
        }

        // ── 订阅 / 串流 / 悬浮窗 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = surface),
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                ToggleRow(
                    icon = { Icon(Icons.Filled.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary) },
                    title = "订阅推送",
                    subtitle = "识别结果提醒 + 每天 20:00 打卡提醒",
                    checked = subscribed,
                    onCheckedChange = onToggleSubscribe,
                )
                ToggleRow(
                    icon = { Icon(Icons.Filled.Wifi, null, tint = MaterialTheme.colorScheme.primary) },
                    title = "实时串流 (WebSocket)",
                    subtitle = if (streamEnabled)
                        "ws://手机IP:8765 · $streamClients 个订阅者"
                    else
                        "把结果推给电脑 / 浏览器 / 其他设备",
                    checked = streamEnabled,
                    onCheckedChange = onToggleStream,
                )
                ToggleRow(
                    icon = { Icon(Icons.Filled.PictureInPictureAlt, null, tint = MaterialTheme.colorScheme.primary) },
                    title = "悬浮窗",
                    subtitle = "像 YouTube 小窗一样浮在其他 App 上",
                    checked = overlayEnabled,
                    onCheckedChange = onToggleOverlay,
                )
            }
        }

        // ── 单张识别 ──
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = onCamera,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("拍照")
            }
            FilledTonalButton(
                onClick = onPick,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("相册")
            }
        }

        Text(
            "免责声明：本应用仅作学习演示，不构成医疗或购药建议。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ClassChip(name: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "$count",
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoCard(title: String, body: String, accent: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = accent)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// 实时扫描界面
// ─────────────────────────────────────────────────────────────────────────

@Composable
fun LiveScreen(
    scanning: Boolean,
    latest: ScanResult?,
    stats: ScanStats,
    overlayEnabled: Boolean,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    pipMode: Boolean = false,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(Modifier.fillMaxSize(), active = true)

        if (pipMode) {
            // PiP 里只留一个大号结果 + 置信度条
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xCC0F1512))
                    .padding(14.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    latest?.displayName ?: "识别中…",
                    color = Color(0xFFF4EFE2),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    latest?.confidencePercent ?: "--",
                    color = Color(0xFF8CD5B8),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { latest?.confidence ?: 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = Color(0xFF8CD5B8),
                    trackColor = Color(0x33FFFFFF),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "🔥 连续 ${stats.streak} 天 · ${stats.todayCount}/${stats.dailyGoal}",
                    color = Color(0xFFA9C7BA),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            return@Box
        }

        // 顶部栏
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0x66000000),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, "返回", tint = Color.White)
                }
            }
            Spacer(Modifier.width(10.dp))
            PulsingDot(scanning)
            Spacer(Modifier.width(6.dp))
            Text(
                if (scanning) "实时识别中" else "已暂停",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Surface(shape = CircleShape, color = Color(0x66000000)) {
                IconButton(onClick = onEnterPip) {
                    Icon(Icons.Filled.PictureInPictureAlt, "小窗", tint = Color.White)
                }
            }
        }

        // 取景框
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.72f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0x14FFFFFF)),
        )

        // 底部结果卡
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xF2111815)),
            ) {
                Column(Modifier.padding(18.dp)) {
                    val accent = when {
                        latest == null -> Color(0xFF8A938C)
                        latest.isConfident -> Color(0xFF8CD5B8)
                        else -> ClassUnknown
                    }
                    val hint = if (latest == null) {
                        "把药材放进框内，保持 1-2 秒"
                    } else if (!latest.isConfident) {
                        "置信度偏低，换个角度或放到纯色背景上"
                    } else if (latest.runnerUpLabel.isNotEmpty()) {
                        "次选：${labelCn(latest.runnerUpLabel)} ${
                            "%.0f%%".format(latest.runnerUpConfidence * 100)
                        }"
                    } else {
                        "已确认"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            latest?.displayName ?: "对准药材…",
                            color = Color(0xFFF4EFE2),
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            latest?.confidencePercent ?: "",
                            color = accent,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { latest?.confidence ?: 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        color = accent,
                        trackColor = Color(0x33FFFFFF),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = hint,
                        color = Color(0xFFA9C7BA),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onStop,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0x33F4EFE2),
                                contentColor = Color(0xFFF4EFE2),
                            ),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("停止")
                        }
                        Button(
                            onClick = { onToggleOverlay(!overlayEnabled) },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (overlayEnabled) Color(0xFF8CD5B8) else Color(0x33F4EFE2),
                                contentColor = if (overlayEnabled) Color(0xFF003829) else Color(0xFFF4EFE2),
                            ),
                        ) {
                            Icon(Icons.Filled.PictureInPictureAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("悬浮窗")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsingDot(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 0.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(Color(0xFF8CD5B8).copy(alpha = alpha))
    )
}

// ─────────────────────────────────────────────────────────────────────────
// 单张识别结果
// ─────────────────────────────────────────────────────────────────────────

@Composable
fun ResultScreen(
    bitmap: Bitmap,
    predictions: List<Classifier.Prediction>,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "所选图片",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(24.dp)),
            )
            Spacer(Modifier.height(16.dp))
        }

        if (predictions.isEmpty()) {
            item {
                Text("模型未就绪或推理失败，请重新识别。", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
            }
            return@LazyColumn
        }

        val top = predictions.first()
        val lowConfidence = top.confidence < ScanResult.CONFIDENCE_FLOOR
        val accent = if (lowConfidence) ClassUnknown else classColorOf(top.label)

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = accent.copy(alpha = 0.12f)
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "识别结果",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        top.displayName,
                        style = MaterialTheme.typography.displaySmall,
                        color = accent,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        top.info,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "置信度 ${top.confidencePercent}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { top.confidence },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        color = accent,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (lowConfidence) {
            item {
                InfoCard(
                    title = "置信度偏低",
                    body = "这张图可能不属于这三类。建议换角度重拍，或把药材放在纯色背景上。",
                    accent = ClassUnknown,
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        item {
            Text(
                "全部候选",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 6.dp),
            )
        }

        items(predictions) { p ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(classColorOf(p.label))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        p.displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        p.confidencePercent,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("继续识别")
            }
        }
    }
}

private fun classColorOf(label: String): Color = when (label) {
    "renshen" -> ClassRenshen
    "xiyangshen" -> ClassXiyangshen
    "jiegeng" -> ClassJiegeng
    else -> ClassUnknown
}

private fun labelCn(label: String): String = when (label) {
    "renshen" -> "人参"
    "xiyangshen" -> "西洋参"
    "jiegeng" -> "桔梗"
    else -> label
}

@Composable
fun ErrorScreen(msg: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("出错了：$msg", textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("返回") }
    }
}
