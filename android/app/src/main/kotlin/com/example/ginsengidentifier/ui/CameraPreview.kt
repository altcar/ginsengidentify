package com.example.ginsengidentifier.ui

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ginsengidentifier.scanner.ScanEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 取景器 + 分析，**同一次 bindToLifecycle 绑两个 UseCase**。
 *
 * 旧实现是 Activity 绑 Preview、Service 绑 Analysis，两个 LifecycleOwner 各自
 * 调 bindToLifecycle，CameraX 会把对方的用例顶掉：
 *   要么有画面没结果，要么有结果没画面。
 * 现在界面在前台时由这里一次绑好 Preview + Analysis；界面退到后台后
 * [com.example.ginsengidentifier.scanner.ScannerService] 才接管 Analysis。
 */
@Composable
fun CameraPreview(modifier: Modifier = Modifier, active: Boolean = true) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val binder = remember { CameraBinder() }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(lifecycleOwner, active) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (active) {
                    ScanEngine.uiActive.value = true
                    previewView?.let { binder.bind(context, lifecycleOwner, it) }
                }
                Lifecycle.Event.ON_STOP -> {
                    binder.unbind()
                    ScanEngine.uiActive.value = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        if (active && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            ScanEngine.uiActive.value = true
            previewView?.let { binder.bind(context, lifecycleOwner, it) }
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            binder.unbind()
            ScanEngine.uiActive.value = false
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }.also { previewView = it }
        },
        update = { view ->
            previewView = view
            if (active && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                binder.bind(context, lifecycleOwner, view)
            } else {
                binder.unbind()
            }
        },
    )
}

private class CameraBinder {
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var executor: ExecutorService? = null
    private var boundOwner: LifecycleOwner? = null

    fun bind(context: Context, owner: LifecycleOwner, view: PreviewView) {
        if (boundOwner === owner && preview != null) {
            // 已经有绑定，只更新 surface
            preview?.setSurfaceProvider(view.surfaceProvider)
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                release()

                val ex = Executors.newSingleThreadExecutor()
                executor = ex

                val previewUseCase = Preview.Builder().build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }

                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()

                val analysisUseCase = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(ex) { proxy -> ScanEngine.onFrame(proxy) } }

                p.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    analysisUseCase,
                )

                preview = previewUseCase
                analysis = analysisUseCase
                boundOwner = owner
                Log.i("CameraPreview", "preview+analysis bound to ${owner::class.simpleName}")
            } catch (t: Throwable) {
                Log.e("CameraPreview", "bind failed: ${t.message}", t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        release()
        preview = null
        analysis = null
        boundOwner = null
    }

    private fun release() {
        val p = provider
        val cases = listOfNotNull(preview, analysis)
        if (p != null && cases.isNotEmpty()) {
            try {
                p.unbind(*cases.toTypedArray())
            } catch (_: Throwable) {
            }
        }
        executor?.shutdown()
        executor = null
    }
}
