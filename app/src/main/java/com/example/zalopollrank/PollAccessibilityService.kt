package com.example.zalopollrank

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class PollAccessibilityService : AccessibilityService() {
    private val seenNodeTexts = LinkedHashSet<String>()
    private var baselineReady = false
    private var loadedSessionId = -1L
    private val handler = Handler(Looper.getMainLooper())
    private var sendAttemptScheduled = false
    private var sendingNow = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(MainActivity.KEY_ACTIVE, false)) return

        if (event.packageName?.toString() != "com.zing.zalo") return

        val sessionId = prefs.getLong(MainActivity.KEY_SESSION_ID, -1L)
        if (sessionId <= 0L) return
        if (sessionId != loadedSessionId) {
            loadedSessionId = sessionId
            baselineReady = false
            seenNodeTexts.clear()
        }

        val startUptime = prefs.getLong(MainActivity.KEY_START_UPTIME, 0L)
        if (event.eventTime + 100 >= startUptime) {
            val targetPoll = prefs.getString(MainActivity.KEY_POLL_TITLE, "")?.trim().orEmpty()

            val direct = mutableListOf<String>()
            event.text?.mapNotNullTo(direct) { it?.toString() }
            event.contentDescription?.toString()?.let(direct::add)
            if (direct.isNotEmpty()) direct += direct.joinToString(" ")
            direct.distinct().forEach { processCandidate(it, targetPoll, sessionId, "event") }

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            ) {
                val root = rootInActiveWindow ?: event.source
                if (root != null) {
                    val texts = mutableListOf<String>()
                    collectTexts(root, texts, 0)
                    val compact = texts.map(::cleanText).filter { it.isNotBlank() }.distinct()

                    if (!baselineReady) {
                        seenNodeTexts.addAll(compact)
                        baselineReady = true
                    } else {
                        for (t in compact) {
                            if (seenNodeTexts.add(t)) {
                                processCandidate(t, targetPoll, sessionId, "node")
                            }
                        }
                        while (seenNodeTexts.size > 600) {
                            val first = seenNodeTexts.iterator()
                            if (first.hasNext()) {
                                first.next(); first.remove()
                            } else break
                        }
                    }
                }
            }
        }

        schedulePendingSendAttempt()
    }

    override fun onInterrupt() = Unit

    private fun processCandidate(raw: String, targetPoll: String, sessionId: Long, source: String) {
        val parsed = PollParser.parse(raw, targetPoll, notificationMode = false) ?: return
        val entry = Storage.addIfNew(
            context = this,
            sessionId = sessionId,
            name = parsed.name,
            poll = parsed.poll,
            source = source,
            observedAt = System.currentTimeMillis()
        ) ?: return

        AutoSendCoordinator.onNewEntry(this, entry, conversationIntent = null)
    }

    private fun schedulePendingSendAttempt(delayMs: Long = 350L) {
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(MainActivity.KEY_AUTO_SEND, false)) return
        if (sendAttemptScheduled || sendingNow || AutoSendQueue.peek(this) == null) return
        sendAttemptScheduled = true
        handler.postDelayed({
            sendAttemptScheduled = false
            trySendPending()
        }, delayMs)
    }

    private fun trySendPending() {
        if (sendingNow) return
        val pending = AutoSendQueue.peek(this) ?: return
        val root = rootInActiveWindow ?: return

        if (!isExpectedGroupVisible(root, pending.expectedGroup)) {
            AutoSendCoordinator.setStatus(
                this,
                "Đã bắt người tham gia nhưng chưa thấy đúng nhóm '${pending.expectedGroup}' trên màn hình. Tin đang chờ."
            )
            return
        }

        val composer = findComposer(root) ?: run {
            AutoSendCoordinator.setStatus(this, "Đúng nhóm nhưng chưa tìm thấy ô nhập tin nhắn Zalo. Tin đang chờ.")
            return
        }

        sendingNow = true
        composer.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pending.text)
        }
        val setOk = composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!setOk) {
            sendingNow = false
            AutoSendCoordinator.setStatus(this, "Không điền được ô chat Zalo. Tin vẫn đang chờ.")
            return
        }

        handler.postDelayed({
            val freshRoot = rootInActiveWindow
            val sendNode = freshRoot?.let(::findSendButton)
            val clicked = sendNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (clicked) {
                AutoSendQueue.pop(this, pending.id)
                AutoSendCoordinator.setStatus(
                    this,
                    "Đã tự gửi vào '${pending.expectedGroup}'. Còn ${AutoSendQueue.size(this)} tin chờ."
                )
            } else {
                AutoSendCoordinator.setStatus(
                    this,
                    "Đã điền nội dung nhưng chưa bấm được nút Gửi. Tin vẫn đang chờ để tránh gửi nhầm."
                )
            }
            sendingNow = false
            if (clicked) schedulePendingSendAttempt(700L)
        }, 300L)
    }

    private fun isExpectedGroupVisible(root: AccessibilityNodeInfo, expected: String): Boolean {
        val target = normalize(expected)
        if (target.isBlank()) return false
        var found = false
        walk(root, 0) { node ->
            val text = node.text?.toString()?.let(::normalize).orEmpty()
            val desc = node.contentDescription?.toString()?.let(::normalize).orEmpty()
            if (text == target || desc == target || text.contains(target) || desc.contains(target)) {
                found = true
            }
        }
        return found
    }

    private fun findComposer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        walk(root, 0) { node ->
            if (!node.isVisibleToUser || !node.isEnabled) return@walk
            val clazz = node.className?.toString().orEmpty()
            val editable = node.isEditable || clazz.contains("EditText", ignoreCase = true)
            if (!editable) return@walk
            var score = 0
            if (node.isEditable) score += 5
            if (node.isFocusable) score += 2
            if (clazz.contains("EditText", ignoreCase = true)) score += 3
            val hintish = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .joinToString(" ")
                .lowercase(Locale.ROOT)
            if (hintish.contains("tin nhắn") || hintish.contains("nhập") || hintish.contains("message")) score += 4
            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }
        return best
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var exact: AccessibilityNodeInfo? = null
        walk(root, 0) { node ->
            if (exact != null || !node.isVisibleToUser || !node.isEnabled) return@walk
            val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .map { normalize(it) }
            if (labels.any { it == "gửi" || it == "send" }) {
                exact = clickableSelfOrAncestor(node)
            }
        }
        return exact
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        repeat(4) {
            if (cur?.isClickable == true && cur?.isEnabled == true) return cur
            cur = cur?.parent
        }
        return null
    }

    private fun normalize(s: String): String =
        s.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun cleanText(s: String): String = PollParser.cleanText(s)

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 40) return
        node.text?.toString()?.let(out::add)
        node.contentDescription?.toString()?.let(out::add)
        for (i in 0 until node.childCount) collectTexts(node.getChild(i), out, depth + 1)
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, block: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > 40) return
        block(node)
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, block)
    }
}
