package com.example.ginsengidentifier

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.ginsengidentifier.data.ScanStore
import com.example.ginsengidentifier.notify.FirebasePush
import com.example.ginsengidentifier.notify.Notifications
import com.example.ginsengidentifier.notify.NtfySubscriber
import com.example.ginsengidentifier.notify.ReminderWorker
import com.example.ginsengidentifier.scanner.OverlayService
import com.example.ginsengidentifier.scanner.ResultBus
import com.example.ginsengidentifier.scanner.ScannerService
import com.example.ginsengidentifier.scanner.StreamServer
import com.example.ginsengidentifier.ui.LiveScreen
import com.example.ginsengidentifier.ui.SettingsScreen
import com.example.ginsengidentifier.ui.theme.GinsengTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScanStore.init(this)
        Notifications.ensureChannels(this)

        setContent {
            GinsengTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(
                        inPip = inPip,
                        onEnterPip = { enterPip() },
                    )
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(9, 16))
                    .build()
            )
        } catch (t: Throwable) {
            Log.w("MainActivity", "PiP unavailable: ${t.message}")
        }
    }
}

private fun hasCamera(context: Context) = ContextCompat.checkSelfPermission(
    context, Manifest.permission.CAMERA
) == PackageManager.PERMISSION_GRANTED

/**
 * The app has exactly two screens: the live scanner (default) and settings.
 * No gallery, no still-photo mode — the whole point is continuous detection.
 */
@Composable
fun AppRoot(
    inPip: Boolean,
    onEnterPip: () -> Unit,
) {
    val context = LocalContext.current

    val stats by ScanStore.stats.collectAsState()
    val latest by ResultBus.latest.collectAsState()
    val scanning by ResultBus.scanning.collectAsState()

    var settingsOpen by remember { mutableStateOf(false) }
    var overlayEnabled by remember { mutableStateOf(false) }
    var subscribed by remember { mutableStateOf(ScanStore.isSubscribed()) }
    var streamEnabled by remember { mutableStateOf(ScanStore.isStreamEnabled()) }
    var ntfyEnabled by remember { mutableStateOf(ScanStore.isNtfyEnabled()) }
    var streamClients by remember { mutableIntStateOf(0) }
    var dailyGoal by remember { mutableIntStateOf(ScanStore.dailyGoal()) }

    val ntfyTopic = remember { ScanStore.ntfyTopic() }
    val fcmConfigured = remember { FirebasePush.isConfigured }

    val cameraPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ScannerService.start(context)
        } else {
            Toast.makeText(context, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ScanStore.setSubscribed(true)
            subscribed = true
            ReminderWorker.schedule(context)
        } else {
            ScanStore.setSubscribed(false)
            subscribed = false
        }
    }

    val overlayPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (OverlayService.canDraw(context)) {
            OverlayService.show(context)
            overlayEnabled = true
        }
    }

    fun startScanning() {
        if (hasCamera(context)) ScannerService.start(context)
        else cameraPerm.launch(Manifest.permission.CAMERA)
    }

    fun setOverlay(want: Boolean) {
        if (!want) {
            OverlayService.hide(context)
            overlayEnabled = false
        } else if (OverlayService.canDraw(context)) {
            OverlayService.show(context)
            overlayEnabled = true
        } else {
            try {
                overlayPerm.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
            } catch (t: Throwable) {
                Toast.makeText(context, "Cannot open overlay settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Launch straight into scanning.
    LaunchedEffect(Unit) {
        if (hasCamera(context)) ScannerService.start(context)
        else cameraPerm.launch(Manifest.permission.CAMERA)

        if (ntfyEnabled) NtfySubscriber.start(context, ntfyTopic)
        if (ScanStore.isFcmEnabled() && fcmConfigured) FirebasePush.init(context)
    }

    LaunchedEffect(streamEnabled) {
        while (true) {
            streamClients = if (streamEnabled) StreamServer.clientCount() else 0
            delay(1200)
        }
    }

    if (settingsOpen && !inPip) {
        SettingsScreen(
            stats = stats,
            subscribed = subscribed,
            streamEnabled = streamEnabled,
            streamClients = streamClients,
            streamUrl = StreamServer.localUrl(),
            ntfyEnabled = ntfyEnabled,
            ntfyTopic = ntfyTopic,
            fcmConfigured = fcmConfigured,
            overlayEnabled = overlayEnabled,
            dailyGoal = dailyGoal,
            onBack = { settingsOpen = false },
            onToggleSubscribe = { want ->
                if (!want) {
                    ScanStore.setSubscribed(false)
                    subscribed = false
                    ReminderWorker.cancel(context)
                } else if (Notifications.canPost(context)) {
                    ScanStore.setSubscribed(true)
                    subscribed = true
                    ReminderWorker.schedule(context)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    ScanStore.setSubscribed(true)
                    subscribed = true
                    ReminderWorker.schedule(context)
                }
            },
            onTestNotification = {
                Notifications.notifyPush(context, "Herb ID", "Test notification — this is how alerts look.")
            },
            onToggleStream = { want ->
                ScanStore.setStreamEnabled(want)
                streamEnabled = want
                if (want) StreamServer.start() else StreamServer.stop()
            },
            onToggleNtfy = { want ->
                ScanStore.setNtfyEnabled(want)
                ntfyEnabled = want
                if (want) {
                    NtfySubscriber.start(context, ntfyTopic)
                    Toast.makeText(context, "Subscribed to $ntfyTopic", Toast.LENGTH_SHORT).show()
                } else {
                    NtfySubscriber.stop()
                }
            },
            onCopyTopic = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("ntfy topic", ntfyTopic))
                Toast.makeText(context, "Topic copied", Toast.LENGTH_SHORT).show()
            },
            onTestPush = {
                NtfySubscriber.publishTest(context, ntfyTopic) { ok ->
                    Toast.makeText(
                        context,
                        if (ok) "Sent — check your notifications" else "Send failed (network or rate limit)",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onToggleOverlay = { setOverlay(it) },
            onDailyGoal = { goal ->
                ScanStore.setDailyGoal(goal)
                dailyGoal = ScanStore.dailyGoal()
            },
        )
    } else {
        LiveScreen(
            scanning = scanning,
            latest = latest,
            stats = stats,
            overlayEnabled = overlayEnabled,
            pipMode = inPip,
            onOpenSettings = { settingsOpen = true },
            onEnterPip = onEnterPip,
            onStart = { startScanning() },
            onStop = {
                ScannerService.stop(context)
                OverlayService.hide(context)
                overlayEnabled = false
            },
            onToggleOverlay = { setOverlay(it) },
        )
    }
}
