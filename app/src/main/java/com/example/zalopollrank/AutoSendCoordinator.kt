package com.example.zalopollrank

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build

object AutoSendCoordinator {

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_AUTO_SEND, false)

    fun buildMessageForEntry(context: Context, entry: Storage.Entry): String {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val fullList = prefs.getBoolean(MainActivity.KEY_SEND_FULL_LIST, true)
        return if (fullList) buildFullListMessage(context, entry.sessionId, entry.poll)
        else "%02d. %s".format(entry.rank, entry.name)
    }

    fun onNewEntry(
        context: Context,
        entry: Storage.Entry,
        conversationIntent: PendingIntent? = null,
        expectedGroupOverride: String? = null
    ) {
        if (!isEnabled(context)) return
        val text = buildMessageForEntry(context, entry)
        queueForUiFallback(
            context = context,
            entry = entry,
            text = text,
            conversationIntent = conversationIntent,
            expectedGroupOverride = expectedGroupOverride
        )
    }

    fun queueForUiFallback(
        context: Context,
        entry: Storage.Entry,
        text: String,
        conversationIntent: PendingIntent? = null,
        expectedGroupOverride: String? = null
    ) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val configured = prefs.getString(MainActivity.KEY_GROUP_NAME, "")?.trim().orEmpty()
        val group = configured.ifBlank { expectedGroupOverride?.trim().orEmpty() }
        if (group.isBlank()) {
            setStatus(context, "Đã bắt người tham gia nhưng không xác định được tên nhóm để fallback tự bấm Gửi.")
            return
        }

        AutoSendQueue.enqueue(
            context = context,
            sessionId = entry.sessionId,
            expectedGroup = group,
            text = text
        )
        setStatus(context, "Đã xếp hàng fallback. Còn ${AutoSendQueue.size(context)} tin.")

        if (conversationIntent != null) {
            openConversationFromNotification(context, conversationIntent)
        }
    }

    fun openConversationFromNotification(context: Context, pendingIntent: PendingIntent): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isDeviceLocked) {
            setStatus(context, "Máy đang khóa: nội dung đã lưu, chờ mở khóa để fallback gửi Zalo.")
            return false
        }

        return runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    .toBundle()
                pendingIntent.send(context, 0, null, null, null, null, options)
            } else {
                pendingIntent.send()
            }
            setStatus(context, "Zalo không có Trả lời nhanh; đang mở cuộc trò chuyện để fallback gửi...")
            true
        }.getOrElse {
            setStatus(context, "Không tự mở được nhóm: ${it.javaClass.simpleName}. Tin vẫn đang chờ.")
            false
        }
    }

    fun setStatus(context: Context, value: String) {
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(MainActivity.KEY_LAST_SEND_STATUS, value)
            .putLong(MainActivity.KEY_LAST_SEND_AT, System.currentTimeMillis())
            .apply()
    }

    private fun buildFullListMessage(context: Context, sessionId: Long, poll: String): String {
        val rows = Storage.currentSession(context, sessionId)
        return buildString {
            append(poll.trim())
            append('\n')
            rows.forEach { append("%02d. %s\n".format(it.rank, it.name)) }
        }.trimEnd()
    }
}
