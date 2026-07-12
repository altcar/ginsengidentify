package com.example.ginsengidentifier

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TFLite 分类器：加载 assets/ginseng_model.tflite + labels.txt，
 * 对输入 Bitmap 跑推理，返回 (label, confidence) 列表。
 *
 * 关键点：
 *  - 模型导出时已包含 mobilenet_v3.preprocess_input 和数据增强层（训练态才生效）
 *  - 推理时输入是 [0, 255] 范围的 float32 RGB，shape=(1, 224, 224, 3)
 *  - 这里只做 resize + 像素读取
 */
class Classifier(
    private val context: Context,
    private val modelName: String = "ginseng_model.tflite",
    private val labelsName: String = "labels.txt",
) {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private val imgSize = 224

    // 中文显示名（顺序与 labels.txt 一致）
    private val displayNames = mapOf(
        "jiegeng" to "桔梗",
        "renshen" to "人参",
        "xiyangshen" to "西洋参",
    )

    private val displayInfo = mapOf(
        "renshen" to "Panax ginseng · 五加科人参属 · 药性: 微苦、微甘，温",
        "xiyangshen" to "Panax quinquefolius · 五加科人参属 · 药性: 苦、微甘，凉",
        "jiegeng" to "Platycodon grandiflorus · 桔梗科桔梗属 · 药性: 苦、辛，平",
    )

    fun load(): Boolean {
        return try {
            val model = loadModelFile()
            val opts = Interpreter.Options().apply {
                setNumThreads(2)
            }
            interpreter = Interpreter(model, opts)
            labels = context.assets.open(labelsName).bufferedReader().readLines()
                .filter { it.isNotBlank() }
            Log.i(TAG, "Model loaded. labels=$labels")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Load model failed: ${e.message}", e)
            false
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    fun isReady(): Boolean = interpreter != null && labels.isNotEmpty()

    /**
     * 推理。返回按置信度降序排列的预测列表。
     */
    fun classify(bitmap: Bitmap): List<Prediction> {
        val itp = interpreter ?: return emptyList()
        if (labels.isEmpty()) return emptyList()

        val resized = Bitmap.createScaledBitmap(bitmap, imgSize, imgSize, true)
        val input = ByteBuffer.allocateDirect(1 * imgSize * imgSize * 3 * 4)
            .order(ByteOrder.nativeOrder())
        for (y in 0 until imgSize) {
            for (x in 0 until imgSize) {
                val px = resized.getPixel(x, y)
                val r = ((px shr 16) and 0xFF).toFloat()
                val g = ((px shr 8) and 0xFF).toFloat()
                val b = (px and 0xFF).toFloat()
                input.putFloat(r)
                input.putFloat(g)
                input.putFloat(b)
            }
        }
        input.rewind()

        val output = Array(1) { FloatArray(labels.size) }
        itp.run(input, output)

        val probs = output[0]
        return probs.mapIndexed { i, p ->
            val key = labels[i]
            Prediction(
                label = key,
                displayName = displayNames[key] ?: key,
                info = displayInfo[key] ?: "",
                confidence = p,
            )
        }.sortedByDescending { it.confidence }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fd = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength,
        )
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
    }
}
