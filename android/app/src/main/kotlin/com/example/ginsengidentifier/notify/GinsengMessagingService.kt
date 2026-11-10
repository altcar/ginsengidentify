package com.example.ginsengidentifier.notify

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** FCM 消息落地成通知。未配置 Firebase 时不会收到任何消息，进程内也不会初始化。 */
class GinsengMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "参三味识别"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: return
        Notifications.notifyPush(this, title, body)
    }

    override fun onNewToken(token: String) {
        // 服务端要用这个 token 定向推送；这里只打日志，接后端时再上报
        super.onNewToken(token)
    }
}
