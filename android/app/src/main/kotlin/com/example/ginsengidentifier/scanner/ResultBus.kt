package com.example.ginsengidentifier.scanner

import com.example.ginsengidentifier.model.ScanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 进程内的"结果总线"。
 *
 * 相机只有一个持有者（ScannerService），其他消费者都是订阅者：
 * 通知栏 / 悬浮窗 / WebSocket / 桌面小组件 / Compose UI。
 * 这样不会出现两个 CameraX 抢相机导致 bind 失败的情况。
 */
object ResultBus {

    private val _latest = MutableStateFlow<ScanResult?>(null)
    val latest: StateFlow<ScanResult?> = _latest

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** 每识别一次就 publish；会同时推给 WebSocket 订阅者。 */
    fun publish(result: ScanResult) {
        _latest.value = result
        StreamServer.broadcast(result)
    }

    fun setScanning(active: Boolean) {
        _scanning.value = active
    }

    fun clear() {
        _latest.value = null
    }
}
