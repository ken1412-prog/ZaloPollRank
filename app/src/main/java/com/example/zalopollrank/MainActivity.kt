package com.example.zalopollrank

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    companion object {
        const val PREFS = "zalo_poll_rank_prefs"
        const val KEY_ACTIVE = "active"
        const val KEY_POLL_TITLE = "poll_title"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_START_UPTIME = "start_uptime"

        const val KEY_AUTO_SEND = "auto_send"
        const val KEY_GROUP_NAME = "group_name"
        const val KEY_SEND_FULL_LIST = "send_full_list"
        const val KEY_LAST_SEND_STATUS = "last_send_status"
        const val KEY_LAST_SEND_AT = "last_send_at"
    }

    private lateinit var pollEdit: EditText
    private lateinit var groupEdit: EditText
    private lateinit var autoSendCheck: CheckBox
    private lateinit var fullListCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var listText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refresher = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Zalo Poll Rank"
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresher)
        handler.post(refresher)
    }

    override fun onPause() {
        saveSettingsFromUi()
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Zalo Poll Rank"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Bắt người tham gia poll → đánh STT → có thể tự dùng chính nick Zalo trên máy để nhắn danh sách cập nhật vào đúng nhóm."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(14))
        })

        pollEdit = EditText(this).apply {
            hint = "Tên poll, ví dụ: Đk chiều đá ae"
            setSingleLine(true)
            setText(prefs.getString(KEY_POLL_TITLE, ""))
        }
        root.addView(pollEdit, LinearLayout.LayoutParams(-1, -2))

        groupEdit = EditText(this).apply {
            hint = "Tên nhóm Zalo (khuyến nghị để fallback an toàn)"
            setSingleLine(true)
            setText(prefs.getString(KEY_GROUP_NAME, ""))
        }
        root.addView(groupEdit, marginParams(dp(6)))

        autoSendCheck = CheckBox(this).apply {
            text = "Tự nhắn cập nhật vào nhóm bằng nick Zalo của tôi"
            isChecked = prefs.getBoolean(KEY_AUTO_SEND, true)
        }
        root.addView(autoSendCheck, marginParams(dp(6)))

        fullListCheck = CheckBox(this).apply {
            text = "Mỗi lần gửi: gửi cả danh sách hiện tại (tắt = chỉ gửi người mới)"
            isChecked = prefs.getBoolean(KEY_SEND_FULL_LIST, true)
        }
        root.addView(fullListCheck)

        root.addView(Button(this).apply {
            text = "1. BẬT QUYỀN ĐỌC THÔNG BÁO (CHẠY NỀN)"
            setOnClickListener {
                saveSettingsFromUi()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }, marginParams(dp(8)))

        root.addView(Button(this).apply {
            text = "2. BẬT QUYỀN TRỢ NĂNG (FALLBACK TỰ GỬI)"
            setOnClickListener {
                saveSettingsFromUi()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, marginParams(dp(6)))

        root.addView(Button(this).apply {
            text = "3. BẮT ĐẦU PHIÊN MỚI"
            setOnClickListener { startNewSession() }
        }, marginParams(dp(6)))

        root.addView(Button(this).apply {
            text = "DỪNG GHI"
            setOnClickListener {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
                refreshUi()
            }
        }, marginParams(dp(6)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(Button(this).apply {
            text = "Xóa DS"
            setOnClickListener {
                val sid = currentSessionId()
                if (sid > 0) {
                    Storage.clearSession(this@MainActivity, sid)
                    AutoSendQueue.clearSession(this@MainActivity, sid)
                }
                refreshUi()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Chia sẻ CSV"
            setOnClickListener { shareCsv() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        root.addView(row, marginParams(dp(8)))

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(14), 0, dp(10))
        }
        root.addView(statusText)

        root.addView(TextView(this).apply {
            text = "Danh sách phiên hiện tại"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
        })

        listText = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(20))
            setTextIsSelectable(true)
        }
        root.addView(listText)

        root.addView(TextView(this).apply {
            text = "Cách tự gửi: ưu tiên dùng action Trả lời nhanh (RemoteInput) ngay trên notification Zalo nên có thể gửi nền mà không cần mở nhóm. Nếu notification không có Trả lời nhanh, app mới fallback: mở cuộc trò chuyện từ notification rồi dùng Trợ năng; trước khi bấm Gửi sẽ kiểm tra tên nhóm, ô chat và đúng nút Gửi. App không vượt khóa màn hình."
            textSize = 14f
        })
        return scroll
    }

    private fun marginParams(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = top }

    private fun saveSettingsFromUi() {
        if (!::pollEdit.isInitialized) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_POLL_TITLE, pollEdit.text.toString().trim())
            .putString(KEY_GROUP_NAME, groupEdit.text.toString().trim())
            .putBoolean(KEY_AUTO_SEND, autoSendCheck.isChecked)
            .putBoolean(KEY_SEND_FULL_LIST, fullListCheck.isChecked)
            .apply()
    }

    private fun startNewSession() {
        saveSettingsFromUi()
        val notif = isNotificationAccessEnabled()
        val accessibility = isAccessibilityEnabled()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val autoSend = prefs.getBoolean(KEY_AUTO_SEND, false)

        if (!notif && !accessibility) {
            Toast.makeText(
                this,
                "Hãy bật ít nhất quyền Đọc thông báo hoặc Trợ năng.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }

        val poll = pollEdit.text.toString().trim()
        if (poll.isBlank()) {
            Toast.makeText(this, "Nhập tên cuộc bình chọn để tránh bắt nhầm poll khác.", Toast.LENGTH_LONG).show()
            return
        }

        if (autoSend) {
            val group = groupEdit.text.toString().trim()
            if (!notif && accessibility && group.isBlank()) {
                Toast.makeText(
                    this,
                    "Nếu không dùng Đọc thông báo, hãy nhập đúng tên nhóm để Trợ năng không gửi nhầm chat.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            if (notif && !accessibility) {
                Toast.makeText(
                    this,
                    "Tự gửi nền sẽ dùng nút Trả lời của notification nếu Zalo cung cấp. Bật thêm Trợ năng để có fallback khi notification không hỗ trợ Trả lời.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val sid = System.currentTimeMillis()
        // Không để tin chờ của phiên cũ tự gửi nhầm sang phiên mới.
        AutoSendQueue.clearAll(this)
        prefs.edit()
            .putLong(KEY_SESSION_ID, sid)
            .putLong(KEY_START_UPTIME, SystemClock.uptimeMillis())
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_LAST_SEND_STATUS, "Chưa có nội dung cần gửi.")
            .apply()

        val mode = buildString {
            append(if (notif) "Thông báo nền" else "")
            if (notif && accessibility) append(" + ")
            if (accessibility) append("Trợ năng")
            if (autoSend) append(" + Tự gửi Zalo")
        }
        Toast.makeText(this, "Đã bắt đầu: $mode", Toast.LENGTH_LONG).show()
        refreshUi()
    }

    private fun shareCsv() {
        val sid = currentSessionId()
        if (sid <= 0) {
            Toast.makeText(this, "Chưa có phiên nào.", Toast.LENGTH_SHORT).show()
            return
        }
        val entries = Storage.currentSession(this, sid)
        if (entries.isEmpty()) {
            Toast.makeText(this, "Danh sách đang trống.", Toast.LENGTH_SHORT).show()
            return
        }
        val file = Storage.exportCsv(this, sid)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Chia sẻ danh sách"))
    }

    private fun refreshUi() {
        if (!::statusText.isInitialized) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        val accessibility = isAccessibilityEnabled()
        val notif = isNotificationAccessEnabled()
        val poll = prefs.getString(KEY_POLL_TITLE, "").orEmpty()
        val group = prefs.getString(KEY_GROUP_NAME, "").orEmpty()
        val autoSend = prefs.getBoolean(KEY_AUTO_SEND, false)
        val sid = currentSessionId()
        val entries = if (sid > 0) Storage.currentSession(this, sid) else emptyList()
        val pending = AutoSendQueue.size(this)
        val sendStatus = prefs.getString(KEY_LAST_SEND_STATUS, "").orEmpty()

        statusText.text = buildString {
            append("Đọc thông báo nền: ").append(if (notif) "ĐÃ BẬT" else "CHƯA BẬT")
            append("\nTrợ năng: ").append(if (accessibility) "ĐÃ BẬT" else "CHƯA BẬT")
            append("\nTheo dõi: ").append(if (active) "ĐANG CHẠY" else "ĐÃ DỪNG")
            if (poll.isNotBlank()) append("\nPoll: ").append(poll)
            if (group.isNotBlank()) append("\nNhóm: ").append(group)
            append("\nTự gửi: ").append(if (autoSend) "BẬT" else "TẮT")
            append(" | chờ gửi: ").append(pending)
            append("\nĐã ghi: ").append(entries.size).append(" người")
            if (sendStatus.isNotBlank()) append("\nGửi Zalo: ").append(sendStatus)
        }

        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale("vi", "VN"))
        listText.text = if (entries.isEmpty()) {
            "(chưa có ai)"
        } else entries.joinToString("\n") {
            val src = when (it.source) {
                "notification" -> "N"
                "event", "node" -> "A"
                else -> "?"
            }
            "%02d. %s  %s  [%s]".format(it.rank, it.name, fmt.format(Date(it.capturedAt)), src)
        }
    }

    private fun currentSessionId(): Long =
        getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_SESSION_ID, -1L)

    private fun isNotificationAccessEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, PollAccessibilityService::class.java)
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val si = info.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(si.packageName, si.name) == expected
            }
    }
}
