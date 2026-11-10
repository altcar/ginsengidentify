package com.example.ginsengidentifier

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.TakePicture
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.ginsengidentifier.data.ScanStore
import com.example.ginsengidentifier.notify.Notifications
import com.example.ginsengidentifier.notify.ReminderWorker
import com.example.ginsengidentifier.scanner.ModelHolder
import com.example.ginsengidentifier.scanner.OverlayService
import com.example.ginsengidentifier.scanner.ResultBus
import com.example.ginsengidentifier.scanner.ScannerService
import com.example.ginsengidentifier.scanner.StreamServer
import com.example.ginsengidentifier.ui.ErrorScreen
import com.example.ginsengidentifier.ui.HomeScreen
import com.example.ginsengidentifier.ui.LiveScreen
import com.example.ginsengidentifier.ui.ResultScreen
import com.example.ginsengidentifier.ui.theme.GinsengTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_SCANNER = "open_scanner"
    }

    private lateinit var classifier: Classifier
    private var inPip by mutableStateOf(false)
    private var requestScan by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScanStore.init(this)
        Notifications.ensureChannels(this)
        classifier = ModelHolder.get(this)

        requestScan = intent?.getBooleanExtra(EXTRA_OPEN_SCANNER, false) ?: false

        setContent {
            GinsengTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(
                        classifier = classifier,
                        autoScan = requestScan,
                        onAutoScanConsumed = { requestScan = false },
                        inPip = inPip,
                        onEnterPip = { enterPip() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SCANNER, false)) requestScan = true
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
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(9, 16))
                .build()
            enterPictureInPictureMode(params)
        } catch (t: Throwable) {
            Log.w("MainActivity", "PiP ä¸å¯ç”¨: ${t.message}")
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Live : Screen
    data class Result(val bitmap: Bitmap, val predictions: List<Classifier.Prediction>) : Screen
    data class Error(val msg: String) : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    classifier: Classifier,
    autoScan: Boolean,
    onAutoScanConsumed: () -> Unit,
    inPip: Boolean,
    onEnterPip: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val stats by ScanStore.stats.collectAsState()
    val latest by ResultBus.latest.collectAsState()
    val scanning by ResultBus.scanning.collectAsState()

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var loading by remember { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var subscribed by remember { mutableStateOf(ScanStore.isSubscribed()) }
    var streamEnabled by remember { mutableStateOf(ScanStore.isStreamEnabled()) }
    var overlayEnabled by remember { mutableStateOf(false) }
    var streamClients by remember { mutableIntStateOf(0) }
    val modelReady = remember { classifier.isReady() }

    // è®¢é˜…è€…æ•°é‡è½®è¯¢ï¼ˆWebSocket å®¢æˆ·ç«¯ï¼‰
    LaunchedEffect(streamEnabled, scanning) {
        while (true) {
            streamClients = if (streamEnabled) StreamServer.clientCount() else 0
            delay(1200)
        }
    }

    fun startScan() {
        if (!classifier.isReady()) {
            screen = Screen.Error(classifier.lastError ?: "æ¨¡åž‹æœªåŠ è½½")
            return
        }
        ScannerService.start(context)
        screen = Screen.Live
    }

    fun stopScan() {
        ScannerService.stop(context)
        OverlayService.hide(context)
        overlayEnabled = false
        screen = Screen.Home
    }

    fun startClassify(uri: Uri) {
        if (!classifier.isReady()) {
            screen = Screen.Error(classifier.lastError ?: "æ¨¡åž‹æœªåŠ è½½")
            loading = false
            return
        }
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { classifyFromUri(context, classifier, uri) }
            screen = result
            loading = false
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(TakePicture()) { success ->
        val uri = cameraUri?.let(Uri::parse)
        if (success && uri != null) startClassify(uri) else loading = false
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startScan() else screen = Screen.Error("éœ€è¦ç›¸æœºæƒé™æ‰èƒ½å®žæ—¶æ‰«æ")
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
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

    val overlayPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (OverlayService.canDraw(context)) {
            OverlayService.show(context)
            overlayEnabled = true
        } else {
            overlayEnabled = false
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) startClassify(uri) }

    fun launchCameraShot() {
        try {
            val uri = createCameraUri(context)
            cameraUri = uri.toString()
            cameraLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            screen = Screen.Error("æ²¡æœ‰æ‰¾åˆ°å¯ç”¨çš„ç›¸æœºåº”ç”¨")
        } catch (t: Throwable) {
            screen = Screen.Error("æ— æ³•æ‰“å¼€ç›¸æœºï¼š${t.message}")
        }
    }

    // é€šçŸ¥ç‚¹è¿›æ¥ / å°ç»„ä»¶ç‚¹è¿›æ¥ -> ç›´æŽ¥å¼€æ‰«æ
    LaunchedEffect(autoScan) {
        if (autoScan) {
            onAutoScanConsumed()
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) startScan() else cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (inPip || screen is Screen.Live) {
        LiveScreen(
            scanning = scanning,
            latest = latest,
            stats = stats,
            overlayEnabled = overlayEnabled,
            pipMode = inPip,
            onStop = { stopScan() },
            onBack = { stopScan() },
            onEnterPip = onEnterPip,
            onToggleOverlay = { want ->
                if (want) {
                    if (OverlayService.canDraw(context)) {
                        OverlayService.show(context)
                        overlayEnabled = true
                    } else {
                        try {
                            overlayPermLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            )
                        } catch (t: Throwable) {
                            screen = Screen.Error("æ— æ³•æ‰“å¼€æ‚¬æµ®çª—æƒé™è®¾ç½®")
                        }
                    }
                } else {
                    OverlayService.hide(context)
                    overlayEnabled = false
                }
            },
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("å‚ä¸‰å‘³è¯†åˆ«") },
                    navigationIcon = {
                        com.example.ginsengidentifier.ui.LogoMark(
                            Modifier
                                .padding(start = 16.dp)
                                .size(30.dp)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { inner ->
            Box(Modifier.padding(inner)) {
                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        stats = stats,
                        modelReady = modelReady,
                        subscribed = subscribed,
                        streamEnabled = streamEnabled,
                        streamClients = streamClients,
                        overlayEnabled = overlayEnabled,
                        onStartScan = {
                            if (ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                            ) startScan()
                            else cameraPermLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onCamera = { launchCameraShot() },
                        onPick = {
                            pickLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
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
                                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                ScanStore.setSubscribed(true)
                                subscribed = true
                                ReminderWorker.schedule(context)
                            }
                        },
                        onToggleStream = { want ->
                            ScanStore.setStreamEnabled(want)
                            streamEnabled = want
                            if (want) StreamServer.start() else StreamServer.stop()
                        },
                        onToggleOverlay = { want ->
                            if (!want) {
                                OverlayService.hide(context)
                                overlayEnabled = false
                            } else if (OverlayService.canDraw(context)) {
                                OverlayService.show(context)
                                overlayEnabled = true
                            } else {
                                try {
                                    overlayPermLauncher.launch(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        )
                                    )
                                } catch (t: Throwable) {
                                    screen = Screen.Error("æ— æ³•æ‰“å¼€æ‚¬æµ®çª—æƒé™è®¾ç½®")
                                }
                            }
                        },
                    )

                    is Screen.Result -> ResultScreen(
                        bitmap = s.bitmap,
                        predictions = s.predictions,
                        onBack = { screen = Screen.Home },
                    )

                    is Screen.Error -> ErrorScreen(s.msg) { screen = Screen.Home }
                    Screen.Live -> Unit
                }

                if (loading) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** é¡¶æ å›¾æ ‡ç”¨å°å†…è¾¹è·ï¼Œé¿å…å†å¼•å…¥ Dp å¸¸é‡ã€‚ */

/** é¡¶æ å›¾æ ‡ç”¨å°å†…è¾¹è·ï¼Œé¿å…å†å¼•å…¥ Dp å¸¸é‡ã€‚ */

// ---------- helpers ----------

private fun classifyFromUri(context: Context, classifier: Classifier, uri: Uri): Screen {
    return try {
        val bitmap = decodeSampled(context, uri)
            ?: return Screen.Error("å›¾ç‰‡è§£ç å¤±è´¥ï¼Œæˆ–æ ¼å¼ä¸å—æ”¯æŒ")
        val rotated = fixRotation(context, uri, bitmap)
        val preds = classifier.classify(rotated)
        if (preds.isEmpty()) Screen.Error(classifier.lastError ?: "æŽ¨ç†å¤±è´¥")
        else Screen.Result(rotated, preds)
    } catch (t: Throwable) {
        Log.e("MainActivity", "classify failed", t)
        Screen.Error(t.message ?: t.javaClass.simpleName)
    }
}

private fun decodeSampled(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    }
}

private fun fixRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (t: Throwable) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bitmap
    return try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply {
            postRotate(degrees)
        }, true)
    } catch (t: Throwable) {
        bitmap
    }
}

private fun createCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    dir.listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < cutoff) f.delete() }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val file = File(dir, "IMG_$stamp.jpg").apply { createNewFile() }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
