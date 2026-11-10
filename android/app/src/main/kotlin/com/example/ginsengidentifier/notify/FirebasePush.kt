package com.example.ginsengidentifier.notify

import android.content.Context
import android.util.Log
import com.example.ginsengidentifier.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * FCM 推送（保留）。
 *
 * 没有引入 google-services 插件，也没有提交 google-services.json：
 * 凭据通过 Gradle 属性注入 BuildConfig，在运行时手动 initializeApp。
 * 这样仓库里没有密钥，构建也不会因为缺文件而失败。
 *
 * 配置方法（android/gradle.properties，已 gitignore）:
 *   FCM_PROJECT_ID=your-project
 *   FCM_APP_ID=1:1234567890:android:abcdef
 *   FCM_API_KEY=AIza...
 *   FCM_SENDER_ID=1234567890
 *
 * 费用：FCM 本身免费且不限条数（配额 600k 条/分钟/项目）。
 */
object FirebasePush {

    private const val TAG = "FirebasePush"
    private const val TOPIC = "ginseng-alerts"

    val isConfigured: Boolean
        get() = BuildConfig.FCM_PROJECT_ID.isNotBlank() && BuildConfig.FCM_APP_ID.isNotBlank()

    @Volatile
    private var initialized = false

    fun init(context: Context): Boolean {
        if (!isConfigured) return false
        if (initialized) return true
        return try {
            val app = context.applicationContext
            if (FirebaseApp.getApps(app).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FCM_PROJECT_ID)
                    .setApplicationId(BuildConfig.FCM_APP_ID)
                    .setApiKey(BuildConfig.FCM_API_KEY)
                    .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                    .build()
                FirebaseApp.initializeApp(app, options)
            }
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
                .addOnCompleteListener { task ->
                    Log.i(TAG, "subscribe $TOPIC: ${task.isSuccessful}")
                }
            initialized = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "FCM init failed: ${t.message}")
            false
        }
    }

    fun unsubscribe() {
        if (!initialized) return
        try {
            FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC)
        } catch (_: Throwable) {
        }
        initialized = false
    }
}
