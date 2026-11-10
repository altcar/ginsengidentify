package com.example.ginsengidentifier

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TFLite 分类器：加载 assets/ginseng_model.tflite + labels.txt，
 * 对输入 Bitmap 跑推理，返回按置信度降序的 Prediction 列表。
 *
 * 输入约定（和 training/export_tflite.py 严格对应）：
 *   - shape (1, 224, 224, 3)，FLOAT32
 *   - 值域 [0, 255] 的 RGB（preprocess_input 已烧进 TFLite 图里）
 *   - 预处理：中心裁剪成正方形 -> 双线性缩放 224 -> 读像素
 *     不能用 Bitmap.createScaledBitmap 直接把 4:3 拉成 1:1，会把人参拉变形
 *
 * 这个类不做任何"猜"：张量 shape / dtype 不对就直接报错交给 UI，
 * 而不是像旧版那样硬编码 labels.size，model 一换就 ArrayIndexOutOfBounds。
 */
class Classifier(
    private val context: Context,
    private val modelName: String = "ginseng_model.tflite",
    private val labelsName: String = "labels.txt",
) {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var inputBuffer: ByteBuffer? = null
    private var inputSize = DEFAULT_SIZE
    private var outputSize = 0
    private var closed = false

    /** 加载失败时的原因，UI 可以显示出来，而不是默默不可用。 */
    @Volatile
    var lastError: String? = null
        private set

    private val displayNames = mapOf(
        "jiegeng" to "桔梗",
        "renshen" to "人参",
        "xiyangshen" to "西洋参",
        "other" to "未检测到相关药材",
    )

    private val displayInfo = mapOf(
        "renshen" to "Panax ginseng · 五加科人参属 · 药性: 微苦、微甘，温",
        "xiyangshen" to "Panax quinquefolius · 五加科人参属 · 药性: 苦、微甘，凉",
        "jiegeng" to "Platycodon grandiflorus · 桔梗科桔梗属 · 药性: 苦、辛，平",
        "other" to "把药材放在纯色背景上，占据画面一半以上",
    )

    /** 加载模型。返回 true 表示可以推理。 */
    @Synchronized
    fun load(): Boolean {
        if (interpreter != null) return true
        return try {
            val model = loadModelFile()
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            val opts = Interpreter.Options().apply { setNumThreads(threads) }
            val itp = Interpreter(model, opts)

            val inTensor = itp.getInputTensor(0)
            val outTensor = itp.getOutputTensor(0)
            val inShape = inTensor.shape()
            val outShape = outTensor.shape()

            if (inShape.size != 4 || inShape[0] != 1 || inShape[3] != 3) {
                throw IllegalStateException("模型输入 shape 不支持: ${inShape.contentToString()}")
            }
            if (inTensor.dataType() != DataType.FLOAT32) {
                throw IllegalStateException("模型输入必须为 float32，实际 ${inTensor.dataType()}")
            }
            if (outShape.size != 2 || outShape[0] != 1) {
                throw IllegalStateException("模型输出 shape 不支持: ${outShape.contentToString()}")
            }

            val loadedLabels = context.assets.open(labelsName).bufferedReader().use { r ->
                r.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }
            if (loadedLabels.isEmpty()) throw IllegalStateException("labels.txt 为空")
            if (outShape[1] != loadedLabels.size) {
                throw IllegalStateException(
                    "labels.txt 有 ${loadedLabels.size} 类，但模型输出 ${outShape[1]} 类"
                )
            }

            inputSize = inShape[1]
            outputSize = outShape[1]
            inputBuffer = ByteBuffer
                .allocateDirect(1 * inputSize * inputSize * 3 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())

            labels = loadedLabels
            interpreter = itp
            lastError = null
            Log.i(TAG, "Model loaded: in=${inShape.contentToString()} out=${outShape.contentToString()} labels=$labels threads=$threads")
            true
        } catch (t: Throwable) {
            // 这里是 Throwable 而不是 Exception：
            // UnsatisfiedLinkError / OutOfMemoryError 也必须被接住，否则直接闪退
            lastError = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "Load model failed: ${t.message}", t)
            interpreter?.close()
            interpreter = null
            inputBuffer = null
            labels = emptyList()
            false
        }
    }

    @Synchronized
    fun close() {
        closed = true
        runCatching { interpreter?.close() }
        interpreter = null
        inputBuffer = null
        labels = emptyList()
    }

    fun isReady(): Boolean = !closed && interpreter != null && labels.isNotEmpty()

    /**
     * 推理。返回按置信度降序排列的预测列表；模型不可用时返回空列表。
     */
    @Synchronized
    fun classify(bitmap: Bitmap): List<Prediction> {
        val itp = interpreter ?: return emptyList()
        val buffer = inputBuffer ?: return emptyList()
        if (closed || labels.isEmpty()) return emptyList()

        val square = toSquare(bitmap, inputSize)
        try {
            val pixels = IntArray(inputSize * inputSize)
            square.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

            buffer.rewind()
            val floats = buffer.asFloatBuffer()
            for (px in pixels) {
                floats.put(((px shr 16) and 0xFF).toFloat())
                floats.put(((px shr 8) and 0xFF).toFloat())
                floats.put((px and 0xFF).toFloat())
            }
            buffer.rewind()

            val output = Array(1) { FloatArray(outputSize) }
            itp.run(buffer, output)

            return output[0].mapIndexed { i, p ->
                val key = labels[i]
                Prediction(
                    label = key,
                    displayName = displayNames[key] ?: key,
                    info = displayInfo[key] ?: "",
                    confidence = p,
                )
            }.sortedByDescending { it.confidence }
        } catch (t: Throwable) {
            Log.e(TAG, "classify failed", t)
            lastError = t.message ?: t.javaClass.simpleName
            return emptyList()
        } finally {
            if (square !== bitmap) square.recycle()
        }
    }

    /**
     * 中心裁剪成正方形再缩放。
     * 和 training/test_tflite.py 的 android_preprocess() 一一对应。
     */
    private fun toSquare(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        if (side <= 0) throw IllegalArgumentException("bitmap 尺寸为 0")
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = if (src.width == side && src.height == side) src
        else Bitmap.createBitmap(src, x, y, side, side)
        val scaled = if (cropped.width == size && cropped.height == size) cropped
        else Bitmap.createScaledBitmap(cropped, size, size, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        return scaled
    }

    private fun loadModelFile(): MappedByteBuffer {
        context.assets.openFd(modelName).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength,
                )
            }
        }
    }

    data class Prediction(
        val label: String,
        val displayName: String,
        val info: String,
        val confidence: Float,
    ) {
        val confidencePercent: String
            get() = "%.1f%%".format(confidence * 100)
    }

    companion object {
        private const val TAG = "Classifier"
        private const val DEFAULT_SIZE = 224

        /** 低于这个置信度就提示"可能不属于这三类"，而不是硬报一个结果。 */
        const val LOW_CONFIDENCE = 0.55f
    }
}
