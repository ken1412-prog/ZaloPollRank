package com.example.zalopollrank

import java.util.Locale

object PollParser {
    data class Parsed(val name: String, val poll: String)

    private const val MARKER = " tham gia cuộc bình chọn:"

    fun parse(raw: String, targetPoll: String = "", notificationMode: Boolean = false): Parsed? {
        val clean = cleanText(raw)
        val lower = clean.lowercase(Locale.ROOT)
        val idx = lower.indexOf(MARKER)
        if (idx <= 0) return null

        var name = clean.substring(0, idx).trim().trim('.', '-', '–', '—')
        var poll = clean.substring(idx + MARKER.length).trim()

        // Một số notification có thể tiền tố bằng tên nhóm, ví dụ:
        // "Tên nhóm: Hải Lê tham gia cuộc bình chọn: ..."
        // Khi đọc notification, chỉ giữ phần sau dấu ": " cuối cùng.
        if (notificationMode) {
            val colon = name.lastIndexOf(": ")
            if (colon >= 0 && colon + 2 < name.length) {
                name = name.substring(colon + 2).trim()
            }
        }

        poll = poll
            .replace(Regex("\\s+Xem$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+·\\s*.+$"), "")
            .trim()
            .trimEnd('.', ' ', '·')

        if (name.isBlank() || poll.isBlank()) return null
        if (targetPoll.isNotBlank() && !poll.contains(targetPoll, ignoreCase = true)) return null
        return Parsed(name, poll)
    }

    fun cleanText(s: String): String =
        s.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
}
