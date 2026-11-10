package com.example.ginsengidentifier.scanner

import android.util.Log
import com.example.ginsengidentifier.model.ScanResult
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置 WebSocket 服务端：把实时识别结果推给任何订阅者。
 *
 * 设计取舍：不依赖任何云端账号，手机自己就是服务器，局域网内可直接订阅：
 *
 *   ws://<手机IP>:8765
 *
 * 浏览器里验证（电脑和手机同一 WiFi）：
 *   const ws = new WebSocket('ws://192.168.1.23:8765');
 *   ws.onmessage = e => console.log(JSON.parse(e.data));
 *
 * 单条消息格式：
 *   {"label":"renshen","display":"人参","confidence":0.9321,
 *    "runner_up":"xiyangshen","runner_up_confidence":0.0512,
 *    "confident":true,"timestamp":1730000000000}
 *
 * 连上时会先收到一条 {"type":"hello",...}，包含类别表和服务信息。
 */
object StreamServer {

    const val PORT = 8765
    private const val TAG = "StreamServer"

    private var server: WebSocketServer? = null
    private val starting = AtomicBoolean(false)

    val isRunning: Boolean get() = server != null

    /** ws://<lan-ip>:8765 for display in settings. */
    fun localUrl(): String = "ws://${localIp() ?: "phone-ip"}:$PORT"

    private fun localIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (t: Throwable) {
        null
    }

    fun clientCount(): Int = try {
        server?.connections?.size ?: 0
    } catch (t: Throwable) {
        0
    }

    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        if (!starting.compareAndSet(false, true)) return false
        return try {
            val s = object : WebSocketServer(InetSocketAddress(PORT)) {
                override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                    Log.i(TAG, "client connected: ${conn.remoteSocketAddress}")
                    conn.send(
                        """{"type":"hello","app":"ginseng-identifier","version":2,""" +
                            """"classes":["renshen","xiyangshen","jiegeng"],""" +
                            """"note":"one JSON per scan result"}"""
                    )
                    ResultBus.latest.value?.let { conn.send(it.toJson()) }
                }

                override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                    Log.i(TAG, "client closed: $code $reason")
                }

                override fun onMessage(conn: WebSocket, message: String) {
                    // 只支持一个 ping，方便前端做心跳
                    if (message.contains("ping")) conn.send("""{"type":"pong"}""")
                }

                override fun onError(conn: WebSocket?, ex: Exception) {
                    Log.w(TAG, "ws error: ${ex.message}")
                }

                override fun onStart() {
                    Log.i(TAG, "listening on :$PORT")
                }
            }
            s.setReuseAddr(true)
            s.start()
            server = s
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            false
        } finally {
            starting.set(false)
        }
    }

    @Synchronized
    fun stop() {
        val s = server ?: return
        server = null
        try {
            s.stop(1000)
        } catch (t: Throwable) {
            Log.w(TAG, "stop failed: ${t.message}")
        }
    }

    fun broadcast(result: ScanResult) {
        val s = server ?: return
        val json = result.toJson()
        try {
            s.connections.forEach { conn ->
                try {
                    if (conn.isOpen) conn.send(json)
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "broadcast failed: ${t.message}")
        }
    }
}
