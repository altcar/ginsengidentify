package com.example.ginsengidentifier

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 真机冒烟测试：把 app 的每个功能点都跑一遍。
 *
 * 运行（需要连着手机或开模拟器）:
 *   cd android
 *   gradlew connectedDebugAndroidTest
 *
 * 或只跑这个类:
 *   gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.ginsengidentifier.SmokeTest
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private lateinit var classifier: Classifier

    @Before
    fun setUp() {
        classifier = Classifier(appContext)
        assertTrue("模型加载失败: ${classifier.lastError}", classifier.load())
    }

    @After
    fun tearDown() {
        if (::classifier.isInitialized) classifier.close()
    }

    // ---------- 1. 模型 / 标签 ----------
    @Test
    fun modelLoads() {
        assertTrue(classifier.isReady())
    }

    // ---------- 2. 推理输出契约 ----------
    @Test
    fun predictionsAreWellFormed() {
        val bitmap = loadSample("jiegeng_1.jpg")
        val preds = classifier.classify(bitmap)
        assertEquals("应输出 3 个类别的概率", 3, preds.size)
        assertTrue("应按置信度降序", preds[0].confidence >= preds[1].confidence)
        assertTrue("置信度应在 [0,1]", preds.all { it.confidence in 0f..1f })
        val sum = preds.sumOf { it.confidence.toDouble() }
        assertTrue("softmax 归一化后应约等于 1，实际 $sum", abs(sum - 1.0) < 0.05)
        assertTrue("应能映射中文名", preds.all { it.displayName.isNotBlank() })
    }

    // ---------- 3. 三个类别都真的在预测（不是永远吐同一个） ----------
    @Test
    fun allThreeClassesAreReachable() {
        val seen = mutableSetOf<String>()
        listOf("renshen_1.jpg", "xiyangshen_1.jpg", "jiegeng_1.jpg").forEach { name ->
            seen += classifier.classify(loadSample(name)).first().label
        }
        // 三张代表图至少应预测出 2 个不同类别（说明模型不是退化成常数输出）
        assertTrue("三张不同类别的图只预测出 $seen", seen.size >= 2)
    }

    // ---------- 4. 测试集抽样准确率 ----------
    // 只用"新来源"(iNaturalist / Commons) 的图。旧版 Bing 商品图域泛化很差
    // （见 README「按来源拆分」），拿它做冒烟测试只会让门禁随机变红。
    @Test
    fun sampleAccuracyIsReasonable() {
        val samples = listOf(
            "renshen_1.jpg" to "renshen",
            "renshen_2.jpg" to "renshen",
            "renshen_3.jpg" to "renshen",
            "xiyangshen_1.jpg" to "xiyangshen",
            "xiyangshen_2.jpg" to "xiyangshen",
            "xiyangshen_3.jpg" to "xiyangshen",
            "jiegeng_1.jpg" to "jiegeng",
            "jiegeng_2.jpg" to "jiegeng",
            "jiegeng_3.jpg" to "jiegeng",
        )
        var correct = 0
        val details = StringBuilder()
        for ((file, expected) in samples) {
            val top = classifier.classify(loadSample(file)).first()
            val ok = top.label == expected
            if (ok) correct++
            details.append("\n  $file -> ${top.label} (${top.confidencePercent}) expected=$expected")
        }
        // 9 张里至少对 5 张：既能挡住"模型退化成常数"，又不会因为单张难例误报
        assertTrue(
            "抽样准确率 $correct/${samples.size}, 至少要 5/9:$details",
            correct >= 5,
        )
    }

    // ---------- 5. 极端输入不崩 ----------
    @Test
    fun handlesDegenerateInputs() {
        // 纯白图
        val white = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        assertEquals(3, classifier.classify(white).size)

        // 1x1
        val tiny = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        assertEquals(3, classifier.classify(tiny).size)

        // 非正方形（中心裁剪路径）
        val wide = Bitmap.createBitmap(800, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GRAY)
        }
        assertEquals(3, classifier.classify(wide).size)

        // 透明图
        val transparent = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        assertEquals(3, classifier.classify(transparent).size)
    }

    // ---------- 6. 界面能起来，且不闪退 ----------
    @Test
    fun activityLaunchesWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
                assertFalse("Activity 启动后立刻结束，说明 onCreate 崩了", activity.isFinishing)
            }
        }
    }

    private fun loadSample(name: String): Bitmap {
        val stream = testContext.assets.open("samples/$name")
        val bmp = stream.use { BitmapFactory.decodeStream(it) }
        assertNotNull("测试图片缺失: $name", bmp)
        return bmp!!
    }
}
