package com.example.zalopollrank

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Nếu notification Zalo có action RemoteInput ("Trả lời"), gửi thẳng nội dung
 * qua action đó. Đây là đường tốt nhất vì không cần mở giao diện Zalo.
 */
object DirectReplySender {
    fun trySend(context: Context, notification: Notification, text: String): Boolean {
        val actions = notification.actions ?: return false
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            val usable = inputs.filter { it.allowFreeFormInput }
            if (usable.isEmpty()) continue

            val fillIn = Intent()
            val results = Bundle()
            usable.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, fillIn, results)
            if (Build.VERSION.SDK_INT >= 28) {
                RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
            }

            val ok = runCatching {
                action.actionIntent.send(context, 0, fillIn)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}
