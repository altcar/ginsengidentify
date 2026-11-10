package com.example.ginsengidentifier.scanner

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * 送进模型之前先做一次"画面里到底有没有东西"的廉价判断。
 *
 * 三分类 softmax 永远会给一个高概率，所以手机对着黑屏、白墙、桌面时
 * 旧版照样报 93% 人参。/pipeline 加了 other 负类之后主要靠模型，
 * 但纯色/过暗这种极端帧先在这里拦掉，省一次推理也更稳。
 */
object FrameGate {

    private const val SIZE = 64

    /** 返回 null 表示画面可用；否则返回给用户看的原因。 */
    fun reject(bitmap: Bitmap): String? {
        val small = try {
            Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        } catch (t: Throwable) {
            return null
        }
        val pixels = IntArray(SIZE * SIZE)
        small.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (small !== bitmap) small.recycle()

        var sum = 0.0
        var sumSq = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            sum += luma
            sumSq += luma * luma
        }
        val n = pixels.size.toDouble()
        val mean = sum / n
        val std = sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))

        return when {
            mean < 24.0 -> "Too dark — move to better light"
            mean > 246.0 -> "Overexposed — avoid direct light"
            std < 7.0 -> "Nothing in frame — point at the herb"
            else -> null
        }
    }
}
