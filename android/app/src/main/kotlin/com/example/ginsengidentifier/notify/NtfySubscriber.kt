package com.example.ginsengidentifier.notify

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 免费远程推送（ntfy.sh）—— 不需要账号、不需要 Firebase、不需要服务器。
 *
 * 机制：App 订阅一个随机 topic（SSE 长连接），任何设备向
 *   https://ntfy.sh/<topic>
 * 发一个 POST，手机就会收到通知。topic 名字本身就是密码，
 * 所以用随机串而不是 "ginseng"。
 *
 * 免费额度（ntfy.sh 公共服务器）：约 250 条/天/IP，12 小时缓存。
 * 自己部署 ntfy 则完全免费无限；付费档 Supporter $6/mo 起。
 */
object NtfySubscriber {

    private const val TAG = "NtfySubscriber"
    private const val BASE = "https://ntfy.sh"

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var topic: String? = null

    val activeTopic: String? get() = if (running) topic else null

    fun start(context: Context, topicName: String) {
        if (topicName.isBlank()) return
        stop()
        topic = topicName
        running = true
        val app = context.applicationContext

        worker = Thread({
            try {
                val conn = (URL("$BASE/$topicName/sse").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    // ntfy 每 ~45s 发一次 keepalive，readLine 会定期返回，不需要 0
                    readTimeout = 120_000
                    setRequestProperty("Accept", "text/event-stream")
                }
                conn.inputStream.bufferedReader().use { reader ->
                    val data = StringBuilder()
                    while (running) {
                        val line = try {
                            reader.readLine()
                        } catch (t: Throwable) {
                            break
                        } ?: break
                        when {
                            line.startsWith("data:") ->
                                data.append(line.removePrefix("data:").trim())
                            line.isEmpty() && data.isNotEmpty() -> {
                                handle(app, data.toString())
                                data.setLength(0)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sse ended: ${t.message}")
            }
        }, "ntfy-sse").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "subscribed to $topicName")
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        topic = null
    }

    private fun handle(context: Context, json: String) {
        try {
            val obj = JSONObject(json)
            val event = obj.optString("event", "message")
            if (event != "message") return
            val title = obj.optString("title", "参三味识别")
            val body = obj.optString("message", "")
            if (body.isBlank() && title.isBlank()) return
            Notifications.notifyPush(context, title, body)
        } catch (t: Throwable) {
            Log.w(TAG, "bad message: ${t.message}")
        }
    }

    /** 发一条测试消息（用手机自己当发送端，验证链路）。 */
    fun publishTest(context: Context, topicName: String, onDone: (Boolean) -> Unit) {
        Thread({
            val ok = try {
                val conn = (URL("$BASE/$topicName").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Title", "参三味识别")
                    setRequestProperty("Tags", "herb")
                }
                conn.outputStream.use { it.write("测试推送 · 链路正常".toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                code in 200..299
            } catch (t: Throwable) {
                Log.w(TAG, "publish failed: ${t.message}")
                false
            }
            onDone(ok)
        }, "ntfy-publish").start()
    }

    fun newTopic(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val suffix = (1..8).map { alphabet.random() }.joinToString("")
        return "ginseng-$suffix"
    }
}
