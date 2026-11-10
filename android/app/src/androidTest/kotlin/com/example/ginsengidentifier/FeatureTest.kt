package com.example.ginsengidentifier

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ginsengidentifier.data.ScanStore
import com.example.ginsengidentifier.model.ScanResult
import com.example.ginsengidentifier.notify.Notifications
import com.example.ginsengidentifier.scanner.OverlayService
import com.example.ginsengidentifier.scanner.ScannerService
import com.example.ginsengidentifier.scanner.StreamServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 新增功能的真机验证：串流服务、通知、统计、组件声明、PiP。
 * 只验证"能起来、声明正确、逻辑正确"，摄像头画面需要人工确认。
 */
@RunWith(AndroidJUnit4::class)
class FeatureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ScanStore.init(context)
        Notifications.ensureChannels(context)
    }

    @Test
    fun scanStatsRecordIncrements() {
        val before = ScanStore.stats.value
        val result = ScanResult(
            label = "renshen",
            displayName = "人参",
            confidence = 0.91f,
        )
        val after = ScanStore.record(result)
        assertEquals(before.todayCount + 1, after.todayCount)
        assertEquals(before.totalCount + 1, after.totalCount)
        assertTrue("连续天数应为正", after.streak >= 1)
        assertTrue(after.countOf("renshen") >= 1)
    }

    @Test
    fun liveNotificationBuilds() {
        val n = Notifications.liveNotification(
            context,
            ScanResult("renshen", "人参", 0.93f, "xiyangshen", 0.05f),
            ScanStore.stats.value,
            scanning = true,
            streamClients = 2,
        )
        assertNotNull(n)
        assertTrue("常驻通知必须 ongoing", n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun streamServerStartsAndStops() {
        assertTrue("WebSocket 服务应能启动", StreamServer.start())
        assertTrue(StreamServer.isRunning)
        assertEquals(0, StreamServer.clientCount())
        StreamServer.stop()
        assertFalse(StreamServer.isRunning)
    }

    @Test
    fun servicesAreDeclared() {
        val pm = context.packageManager
        val scanner = pm.getServiceInfo(ComponentName(context, ScannerService::class.java), 0)
        assertNotNull(scanner)
        val overlay = pm.getServiceInfo(ComponentName(context, OverlayService::class.java), 0)
        assertNotNull(overlay)
    }

    @Test
    fun widgetReceiverIsDeclared() {
        val pm = context.packageManager
        val info = pm.getReceiverInfo(
            ComponentName(context, com.example.ginsengidentifier.widget.GinsengWidgetReceiver::class.java),
            PackageManager.GET_META_DATA,
        )
        assertNotNull(info)
        assertNotNull("小组件缺少 appwidget provider 元数据", info.metaData)
    }

    @Test
    fun pipFeatureReportedByDevice() {
        // 声明是否生效在打包时已用 aapt2 dump 校验；这里只记录设备能力
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            println(
                "PiP system feature = " +
                    pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            )
        }
    }
}
