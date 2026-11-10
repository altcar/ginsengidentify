package com.example.ginsengidentifier

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.TakePicture
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.ginsengidentifier.ui.theme.GinsengTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var classifier: Classifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        classifier = Classifier(this)
        classifier.load()
        setContent {
            GinsengTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(classifier)
                }
            }
        }
    }

    override fun onDestroy() {
        classifier.close()
        super.onDestroy()
    }
}

private sealed interface Screen {
    data object Home : Screen
    data class Result(val bitmap: Bitmap, val predictions: List<Classifier.Prediction>) : Screen
    data class Error(val msg: String) : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(classifier: Classifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var loading by remember { mutableStateOf(false) }
    // 用 String 存，配置变更（旋转/深色模式切换）后相机回来的 Uri 不会丢
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val modelReady = remember { classifier.isReady() }

    fun startClassify(uri: Uri) {
        if (!classifier.isReady()) {
            screen = Screen.Error(classifier.lastError ?: "模型未加载")
            loading = false
            return
        }
        loading = true
        // 解码 + 推理都在 IO 线程，主线程只负责渲染，避免 ANR
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                classifyFromUri(context, classifier, uri)
            }
            screen = result
            loading = false
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(TakePicture()) { success ->
        val uri = cameraUri?.let(Uri::parse)
        if (success && uri != null) {
            startClassify(uri)
        } else {
            loading = false
        }
    }

    fun launchCamera() {
        try {
            val uri = createCameraUri(context)
            cameraUri = uri.toString()
            cameraLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            // Android 11+ 没有 <queries> 声明时，这里会抛 ActivityNotFoundException 直接闪退
            Log.e("MainActivity", "no camera app", e)
            screen = Screen.Error("没有找到可用的相机应用")
        } catch (t: Throwable) {
            Log.e("MainActivity", "launch camera failed", t)
            screen = Screen.Error("无法打开相机：${t.message ?: t.javaClass.simpleName}")
        }
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera() else screen = Screen.Error("需要相机权限才能拍照识别")
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) startClassify(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("人参 · 西洋参 · 桔梗 识别") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when (val s = screen) {
                Screen.Home -> HomeContent(
                    modelReady = modelReady,
                    onCamera = {
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            launchCamera()
                        } else {
                            cameraPermLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onPick = {
                        pickLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                )

                is Screen.Result -> ResultContent(
                    bitmap = s.bitmap,
                    predictions = s.predictions,
                    onBack = { screen = Screen.Home },
                )

                is Screen.Error -> ErrorContent(s.msg) { screen = Screen.Home }
            }
            if (loading) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun HomeContent(
    modelReady: Boolean,
    onCamera: () -> Unit,
    onPick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "端侧识别三味常见中药材",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "支持识别：人参、西洋参、桔梗\n模型本地运行，无需联网",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!modelReady) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "模型加载失败，识别不可用",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onCamera,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("拍照识别", fontSize = 18.sp)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onPick,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("从相册选择", fontSize = 18.sp)
        }
        Spacer(Modifier.height(48.dp))
        Text(
            "提示：将药材放在白纸上，自然光下拍摄效果最好。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ResultContent(
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
                contentDescription = "Selected image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.height(20.dp))
        }

        if (predictions.isEmpty()) {
            item {
                Text("模型未就绪或推理失败，请重新识别。", textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("返回")
                }
            }
            return@LazyColumn
        }

        val top = predictions.first()
        val lowConfidence = top.confidence < Classifier.LOW_CONFIDENCE

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "Top-1 识别结果",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        top.displayName,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        top.info,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "置信度 ${top.confidencePercent}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { top.confidence },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (lowConfidence) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "置信度偏低，这张图可能不属于这三类。建议换个角度重拍，" +
                                "或把药材放在纯色背景上。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        item {
            Text(
                "全部候选",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        items(predictions) { p ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                elevation = CardDefaults.cardElevation(1.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        p.displayName,
                        modifier = Modifier.weight(1f),
                        fontSize = 16.sp,
                    )
                    Text(
                        p.confidencePercent,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("再拍一张")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "免责声明：本应用仅作学习演示，不构成医疗或购药建议。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorContent(msg: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("出错了：$msg", textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack) { Text("返回") }
    }
}

// ---------- helpers ----------

/**
 * 解码 + 旋转 + 推理。全程在 IO 线程执行。
 * 任何异常都转成 Screen.Error，绝不让异常逃到主线程。
 */
private fun classifyFromUri(
    context: Context,
    classifier: Classifier,
    uri: Uri,
): Screen {
    return try {
        val bitmap = decodeSampled(context, uri)
            ?: return Screen.Error("图片解码失败，或格式不受支持")
        val rotated = fixRotation(context, uri, bitmap)
        val preds = classifier.classify(rotated)
        if (preds.isEmpty()) {
            Screen.Error(classifier.lastError ?: "推理失败")
        } else {
            Screen.Result(rotated, preds)
        }
    } catch (t: Throwable) {
        // OutOfMemoryError 也要接住，否则就是"点一下突然闪退"
        Log.e("MainActivity", "classify failed", t)
        Screen.Error(t.message ?: t.javaClass.simpleName)
    }
}

/**
 * 带降采样的解码：相机原图动辄 4000x3000，直接 decodeStream 会吃掉 48MB，
 * 低端机直接 OOM。这里先把短边压到 1600 以内，模型只需要 224。
 */
private fun decodeSampled(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
        sample *= 2
    }
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
        Log.w("MainActivity", "exif read failed: ${t.message}")
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
        Log.w("MainActivity", "rotate failed: ${t.message}")
        bitmap
    }
}

/**
 * 建一个 FileProvider 能授权的缓存文件。
 * 注意：必须先 createNewFile()，否则某些相机应用拿到不存在的 Uri 会直接失败。
 */
private fun createCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    dir.listFiles()?.forEach { f ->
        if (f.isFile && f.lastModified() < cutoff) f.delete()
    }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val file = File(dir, "IMG_$stamp.jpg").apply { createNewFile() }
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}
