package com.example.ginsengidentifier.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 取景器。
 *
 * 关键点：这里只绑 Preview，**不绑 ImageAnalysis** —— 分析永远由 ScannerService
 * 持有。两个 UseCase 绑到不同 LifecycleOwner 上可以同时工作，
 * 但前提是任何一方都不能调用 unbindAll()。
 */
@Composable
fun CameraPreview(modifier: Modifier = Modifier, active: Boolean = true) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val binder = remember { PreviewBinder() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        update = { view ->
            if (active) binder.bind(context, lifecycleOwner, view)
            else binder.unbind()
        },
    )

    DisposableEffect(Unit) {
        onDispose { binder.unbind() }
    }
}

private class PreviewBinder {
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null

    fun bind(
        context: android.content.Context,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        view: PreviewView,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                preview?.let { p.unbind(it) }
                val useCase = Preview.Builder().build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCase)
                preview = useCase
            } catch (_: Throwable) {
                // 相机被别的 App 占用 / 无相机时静默降级，UI 仍然可用
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        try {
            preview?.let { provider?.unbind(it) }
        } catch (_: Throwable) {
        }
        preview = null
    }
}
