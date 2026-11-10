package com.example.ginsengidentifier.scanner

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.ginsengidentifier.data.ScanStore
import com.example.ginsengidentifier.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 常驻扫描服务：负责"活着"（前台通知、串流、后台相机），
 * 具体推理由 [ScanEngine] 处理。
 *
 * 相机所有权规则：
 *   - 界面在前台（含 PiP）→ UI 绑定 Preview + Analysis，本服务**不绑相机**
 *   - 界面退到后台      → 本服务只绑 Analysis
 * 监听 [ScanEngine.uiActive] 自动切换，避免两边同时 bind 互相顶掉。
 */
class ScannerService : Service() {

    companion object {
        private const val TAG = "ScannerService"
        const val ACTION_START = "com.example.ginsengidentifier.action.START_SCAN"
        const val ACTION_STOP = "com.example.ginsengidentifier.action.STOP_SCAN"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScannerService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScannerService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Throwable) {
                context.stopService(Intent(context, ScannerService::class.java))
            }
        }
    }

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private lateinit var executor: ExecutorService
    private val serviceLifecycle = ServiceLifecycleOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        ScanEngine.init(this)

        // Android 14: 带 camera 类型 startForeground 却没有相机权限会抛 SecurityException
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "没有相机权限，停止服务")
            stopSelf()
            return
        }
        if (!ScanEngine.isReady()) {
            Log.e(TAG, "模型未就绪，停止服务")
            stopSelf()
            return
        }

        executor = Executors.newSingleThreadExecutor()
        serviceLifecycle.resume()

        ServiceCompat.startForeground(
            this,
            Notifications.ID_LIVE,
            Notifications.liveNotification(
                this, null, ScanStore.stats.value, scanning = true, streamClients = 0
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )

        ResultBus.setScanning(true)
        if (ScanStore.isStreamEnabled()) StreamServer.start()

        // 相机所有权切换
        scope.launch {
            ScanEngine.uiActive.collect { uiActive ->
                if (uiActive) unbindAnalysis() else bindAnalysis()
            }
        }
        if (!ScanEngine.uiActive.value) bindAnalysis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        unbindAnalysis()
        if (::executor.isInitialized) executor.shutdown()
        StreamServer.stop()
        ResultBus.setScanning(false)
        serviceLifecycle.destroy()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun bindAnalysis() {
        if (analysis != null) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (ScanEngine.uiActive.value) return@addListener
            try {
                val p = future.get()
                provider = p

                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()

                val useCase = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(executor) { proxy -> ScanEngine.onFrame(proxy) } }

                p.bindToLifecycle(serviceLifecycle, CameraSelector.DEFAULT_BACK_CAMERA, useCase)
                analysis = useCase
                Log.i(TAG, "analysis bound (background)")
            } catch (t: Throwable) {
                Log.e(TAG, "bind analysis failed", t)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindAnalysis() {
        val useCase = analysis ?: return
        analysis = null
        try {
            provider?.unbind(useCase)
            Log.i(TAG, "analysis unbound (ui took over)")
        } catch (t: Throwable) {
            Log.w(TAG, "unbind failed: ${t.message}")
        }
    }

    private class ServiceLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
