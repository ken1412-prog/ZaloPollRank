package com.example.zalopollrank

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Bắt tin Zalo khi app/nhóm không mở, miễn Zalo thực sự đăng Android notification
 * có chứa dòng "<Tên> tham gia cuộc bình chọn: ...".
 */
class ZaloNotificationListenerService : NotificationListenerService() {

    private var lastConversationIntent: PendingIntent? = null

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_USER_PRESENT) return
            if (AutoSendQueue.peek(this@ZaloNotificationListenerService) == null) return
            lastConversationIntent?.let {
                AutoSendCoordinator.openConversationFromNotification(
                    this@ZaloNotificationListenerService,
                    it
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(unlockReceiver, filter)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockReceiver) }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != "com.zing.zalo") return

        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(MainActivity.KEY_ACTIVE, false)) return

        val sessionId = prefs.getLong(MainActivity.KEY_SESSION_ID, -1L)
        if (sessionId <= 0L) return
        val targetPoll = prefs.getString(MainActivity.KEY_POLL_TITLE, "")?.trim().orEmpty()

        val n = sbn.notification
        if (n.contentIntent != null) lastConversationIntent = n.contentIntent

        val candidates = LinkedHashSet<String>()
        n.tickerText?.toString()?.let(candidates::add)

        val extras = n.extras
        listOf(
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT
        ).forEach { key ->
            extras.getCharSequence(key)?.toString()?.let(candidates::add)
        }

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        lines?.forEach { it?.toString()?.let(candidates::add) }
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let(candidates::add)
        extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.let(candidates::add)

        for (raw in candidates) {
            val parsed = PollParser.parse(raw, targetPoll, notificationMode = true) ?: continue
            val entry = Storage.addIfNew(
                context = this,
                sessionId = sessionId,
                name = parsed.name,
                poll = parsed.poll,
                source = "notification",
                observedAt = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
            ) ?: continue

            if (!AutoSendCoordinator.isEnabled(this)) continue

            val outgoing = AutoSendCoordinator.buildMessageForEntry(this, entry)
            val directSent = DirectReplySender.trySend(this, n, outgoing)
            if (directSent) {
                AutoSendCoordinator.setStatus(
                    this,
                    "Đã tự gửi nền bằng action Trả lời của notification Zalo. Không cần mở nhóm."
                )
            } else {
                val notificationGroup = listOf(
                    extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
                    extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

                AutoSendCoordinator.queueForUiFallback(
                    context = this,
                    entry = entry,
                    text = outgoing,
                    conversationIntent = n.contentIntent,
                    expectedGroupOverride = notificationGroup
                )
            }
        }
    }
}
