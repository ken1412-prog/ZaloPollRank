package com.example.zalopollrank

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Storage {
    private const val FILE_NAME = "poll_rank_entries.json"
    private val lock = Any()

    data class Entry(
        val sessionId: Long,
        val rank: Int,
        val name: String,
        val poll: String,
        val capturedAt: Long,
        val source: String
    )

    fun addIfNew(
        context: Context,
        sessionId: Long,
        name: String,
        poll: String,
        source: String,
        observedAt: Long = System.currentTimeMillis()
    ): Entry? = synchronized(lock) {
        val all = readAllLocked(context).toMutableList()
        val normalizedName = normalize(name)
        val normalizedPoll = normalize(poll)

        val duplicate = all.any {
            it.sessionId == sessionId &&
                normalize(it.name) == normalizedName &&
                normalize(it.poll) == normalizedPoll
        }
        if (duplicate) return@synchronized null

        val rank = all.count { it.sessionId == sessionId } + 1
        val entry = Entry(
            sessionId = sessionId,
            rank = rank,
            name = name.trim(),
            poll = poll.trim(),
            capturedAt = observedAt,
            source = source
        )
        all += entry
        writeAllLocked(context, all)
        entry
    }

    fun currentSession(context: Context, sessionId: Long): List<Entry> = synchronized(lock) {
        readAllLocked(context).filter { it.sessionId == sessionId }.sortedBy { it.rank }
    }

    fun clearSession(context: Context, sessionId: Long) = synchronized(lock) {
        val kept = readAllLocked(context).filterNot { it.sessionId == sessionId }
        writeAllLocked(context, kept)
    }

    fun exportCsv(context: Context, sessionId: Long): File = synchronized(lock) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "zalo_poll_rank_$sessionId.csv")
        val rows = currentSession(context, sessionId)
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { w ->
            // BOM để Excel trên Windows nhận UTF-8 tiếng Việt tốt hơn.
            w.write('\uFEFF'.code)
            w.appendLine("STT,Tên,Cuộc bình chọn,Thời gian ghi nhận,Nguồn")
            val fmt = SimpleDateFormat("HH:mm:ss.SSS dd/MM/yyyy", Locale("vi", "VN"))
            for (e in rows) {
                w.appendLine(listOf(
                    e.rank.toString(), e.name, e.poll,
                    fmt.format(Date(e.capturedAt)), e.source
                ).joinToString(",") { csv(it) })
            }
        }
        file
    }

    private fun normalize(s: String): String =
        s.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun csv(s: String): String = "\"${s.replace("\"", "\"\"")}\""

    private fun readAllLocked(context: Context): List<Entry> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Entry(
                            sessionId = o.getLong("sessionId"),
                            rank = o.getInt("rank"),
                            name = o.getString("name"),
                            poll = o.getString("poll"),
                            capturedAt = o.getLong("capturedAt"),
                            source = o.optString("source", "unknown")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAllLocked(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("sessionId", e.sessionId)
                put("rank", e.rank)
                put("name", e.name)
                put("poll", e.poll)
                put("capturedAt", e.capturedAt)
                put("source", e.source)
            })
        }
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        val dst = File(context.filesDir, FILE_NAME)
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (dst.exists()) dst.delete()
        tmp.renameTo(dst)
    }
}
