package com.example.zalopollrank

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object AutoSendQueue {
    private const val FILE_NAME = "auto_send_queue.json"
    private val lock = Any()

    data class PendingMessage(
        val id: Long,
        val sessionId: Long,
        val expectedGroup: String,
        val text: String,
        val createdAt: Long
    )

    fun enqueue(
        context: Context,
        sessionId: Long,
        expectedGroup: String,
        text: String
    ): PendingMessage = synchronized(lock) {
        val all = readAllLocked(context).toMutableList()
        val next = PendingMessage(
            id = System.nanoTime(),
            sessionId = sessionId,
            expectedGroup = expectedGroup.trim(),
            text = text,
            createdAt = System.currentTimeMillis()
        )
        all += next
        while (all.size > 100) all.removeAt(0)
        writeAllLocked(context, all)
        next
    }

    fun peek(context: Context): PendingMessage? = synchronized(lock) {
        readAllLocked(context).firstOrNull()
    }

    fun pop(context: Context, id: Long): Boolean = synchronized(lock) {
        val all = readAllLocked(context).toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized false
        all.removeAt(idx)
        writeAllLocked(context, all)
        true
    }

    fun size(context: Context): Int = synchronized(lock) { readAllLocked(context).size }

    fun clearSession(context: Context, sessionId: Long) = synchronized(lock) {
        writeAllLocked(context, readAllLocked(context).filterNot { it.sessionId == sessionId })
    }

    fun clearAll(context: Context) = synchronized(lock) {
        writeAllLocked(context, emptyList())
    }

    private fun readAllLocked(context: Context): List<PendingMessage> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        PendingMessage(
                            id = o.getLong("id"),
                            sessionId = o.getLong("sessionId"),
                            expectedGroup = o.getString("expectedGroup"),
                            text = o.getString("text"),
                            createdAt = o.getLong("createdAt")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAllLocked(context: Context, entries: List<PendingMessage>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("sessionId", e.sessionId)
                put("expectedGroup", e.expectedGroup)
                put("text", e.text)
                put("createdAt", e.createdAt)
            })
        }
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        val dst = File(context.filesDir, FILE_NAME)
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (dst.exists()) dst.delete()
        tmp.renameTo(dst)
    }
}
