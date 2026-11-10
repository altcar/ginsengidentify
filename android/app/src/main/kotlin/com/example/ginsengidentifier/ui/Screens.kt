package com.example.ginsengidentifier.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ginsengidentifier.R
import com.example.ginsengidentifier.model.ScanResult
import com.example.ginsengidentifier.model.ScanStats
import com.example.ginsengidentifier.ui.theme.ClassJiegeng
import com.example.ginsengidentifier.ui.theme.ClassRenshen
import com.example.ginsengidentifier.ui.theme.ClassUnknown
import com.example.ginsengidentifier.ui.theme.ClassXiyangshen

private val JadeTop = Color(0xFF2AA684)
private val JadeBottom = Color(0xFF0B3D2F)
private val Cream = Color(0xFFF7F3E7)
private val Muted = Color(0xFFA9C7BA)

@Composable
fun LogoMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(JadeTop, JadeBottom))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_root),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.78f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Live scanner (the only screen at launch)
// ─────────────────────────────────────────────────────────────────────────

@Composable
fun LiveScreen(
    scanning: Boolean,
    latest: ScanResult?,
    stats: ScanStats,
    overlayEnabled: Boolean,
    pipMode: Boolean = false,
    onOpenSettings: () -> Unit,
    onEnterPip: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        CameraPreview(Modifier.fillMaxSize(), active = scanning)

        if (pipMode) {
            PipContent(latest, stats)
            return@Box
        }

        // Top bar
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(Modifier.size(42.dp), onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, "Settings", tint = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            PulsingDot(scanning)
            Spacer(Modifier.width(7.dp))
            Text(
                if (scanning) "Scanning" else "Paused",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            RoundIcon(Modifier.size(42.dp), onClick = onEnterPip) {
                Icon(Icons.Filled.PictureInPictureAlt, "Picture in picture", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            RoundIcon(
                Modifier.size(42.dp),
                active = overlayEnabled,
                onClick = { onToggleOverlay(!overlayEnabled) },
            ) {
                Icon(Icons.Filled.Bolt, "Floating bubble", tint = Color.White)
            }
        }

        // Result card
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            ResultCard(
                latest = latest,
                scanning = scanning,
                overlayEnabled = overlayEnabled,
                onStart = onStart,
                onStop = onStop,
                onToggleOverlay = onToggleOverlay,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Local inference · photos never leave the device",
                color = Color(0x99FFFFFF),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ResultCard(
    latest: ScanResult?,
    scanning: Boolean,
    overlayEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
) {
    val other = latest?.isOther == true
    val accent = when {
        latest == null -> Color(0xFF8A938C)
        other -> ClassUnknown
        latest.isConfident -> Color(0xFF8CD5B8)
        else -> ClassUnknown
    }
    val hint = if (latest == null) {
        "Point the camera at the herb"
    } else if (other) {
        latest.note.ifEmpty { "Move closer, fill the frame" }
    } else if (!latest.isConfident) {
        "Low confidence — try another angle or a plain background"
    } else if (latest.runnerUpLabel.isNotEmpty()) {
        "Next: ${latest.runnerUpLabel} ${"%.0f%%".format(latest.runnerUpConfidence * 100)}"
    } else {
        "Confirmed"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF2111815)),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    latest?.displayName ?: "Ready",
                    color = if (other) Color(0xFFE8C48A) else Cream,
                    style = if (other) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.displaySmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (other || latest == null) "" else latest.confidencePercent,
                    color = accent,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (other || latest == null) 0f else latest.confidence },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                color = accent,
                trackColor = Color(0x33FFFFFF),
            )
            Spacer(Modifier.height(10.dp))
            Text(hint, color = Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { if (scanning) onStop() else onStart() },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (scanning) Color(0x33F4EFE2) else Color(0xFF8CD5B8),
                        contentColor = if (scanning) Cream else Color(0xFF003829),
                    ),
                ) {
                    Icon(
                        if (scanning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (scanning) "Stop" else "Start")
                }
                Button(
                    onClick = { onToggleOverlay(!overlayEnabled) },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (overlayEnabled) Color(0xFF8CD5B8) else Color(0x33F4EFE2),
                        contentColor = if (overlayEnabled) Color(0xFF003829) else Cream,
                    ),
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Bubble")
                }
            }
        }
    }
}

@Composable
private fun PipContent(latest: ScanResult?, stats: ScanStats) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xE60F1512))
            .padding(14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            latest?.displayName ?: "Scanning…",
            color = Cream,
            fontSize = if (latest?.isOther == true) 20.sp else 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (latest?.isOther == true) (latest.note.ifEmpty { "No herb" })
            else latest?.confidencePercent ?: "--",
            color = Color(0xFF8CD5B8),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { if (latest?.isOther == true) 0f else latest?.confidence ?: 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = Color(0xFF8CD5B8),
            trackColor = Color(0x33FFFFFF),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Streak ${stats.streak}d · ${stats.todayCount}/${stats.dailyGoal}",
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun RoundIcon(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (active) Color(0xCC8CD5B8) else Color(0x66000000),
    ) {
        IconButton(onClick = onClick) { content() }
    }
}

@Composable
private fun PulsingDot(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 0.25f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
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
// Settings
// ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    stats: ScanStats,
    subscribed: Boolean,
    streamEnabled: Boolean,
    streamClients: Int,
    streamUrl: String,
    ntfyEnabled: Boolean,
    ntfyTopic: String,
    fcmConfigured: Boolean,
    overlayEnabled: Boolean,
    dailyGoal: Int,
    onBack: () -> Unit,
    onToggleSubscribe: (Boolean) -> Unit,
    onTestNotification: () -> Unit,
    onToggleStream: (Boolean) -> Unit,
    onToggleNtfy: (Boolean) -> Unit,
    onCopyTopic: () -> Unit,
    onTestPush: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onDailyGoal: (Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatsCard(stats, dailyGoal, onDailyGoal) }

            item {
                SectionCard("Notifications") {
                    ToggleRow(
                        title = "Detection alerts",
                        subtitle = "Result alerts plus a daily reminder at 20:00",
                        checked = subscribed,
                        onCheckedChange = onToggleSubscribe,
                    )
                    TextButton(onClick = onTestNotification) {
                        Text("Send a test notification")
                    }
                }
            }

            item {
                SectionCard("Remote push") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Free channel · ntfy.sh", style = MaterialTheme.typography.titleSmall)
                            Text(
                                ntfyTopic,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Switch(checked = ntfyEnabled, onCheckedChange = onToggleNtfy)
                    }
                    Text(
                        "POST anything to https://ntfy.sh/$ntfyTopic and this phone gets a notification. No account, no Firebase.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = onCopyTopic) { Text("Copy topic") }
                        TextButton(onClick = onTestPush) { Text("Send test") }
                    }
                    Text(
                        if (fcmConfigured) "Firebase Cloud Messaging: configured"
                        else "Firebase Cloud Messaging: not configured (add FCM_* to gradle.properties)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fcmConfigured) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    )
                }
            }

            item {
                SectionCard("Live stream") {
                    ToggleRow(
                        title = "WebSocket stream",
                        subtitle = if (streamEnabled)
                            "$streamUrl · $streamClients subscriber(s)"
                        else
                            "Push results to a laptop, browser or other device",
                        checked = streamEnabled,
                        onCheckedChange = onToggleStream,
                    )
                }
            }

            item {
                SectionCard("Floating bubble") {
                    ToggleRow(
                        title = "Show over other apps",
                        subtitle = "Needs the draw-over-apps permission",
                        checked = overlayEnabled,
                        onCheckedChange = onToggleOverlay,
                    )
                }
            }

            item {
                SectionCard("About") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LogoMark(Modifier.size(44.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Herb ID", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Realtime ginseng / American ginseng / platycodon",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "On-device TFLite model, 4 classes (the 4th is “no herb detected”). " +
                            "Icon derived from a CC BY-SA 2.0 photo of Panax ginseng on Wikimedia Commons.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Educational demo only — not medical or purchase advice.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsCard(stats: ScanStats, dailyGoal: Int, onDailyGoal: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                Text("${stats.streak} day streak", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    "${stats.todayCount}/$dailyGoal today",
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
                CountChip("Ginseng", stats.countOf("renshen"), ClassRenshen, Modifier.weight(1f))
                CountChip("American", stats.countOf("xiyangshen"), ClassXiyangshen, Modifier.weight(1f))
                CountChip("Platycodon", stats.countOf("jiegeng"), ClassJiegeng, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Daily goal",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 20).forEach { goal ->
                    FilterChip(
                        selected = dailyGoal == goal,
                        onClick = { onDailyGoal(goal) },
                        label = { Text("$goal") },
                    )
                }
            }
        }
    }
}

@Composable
private fun CountChip(name: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$count", style = MaterialTheme.typography.titleMedium, color = color)
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
