package com.eyelove.notifyrelay

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var config: ConfigManager
    private lateinit var tvStatus: TextView
    private lateinit var etBotToken: EditText
    private lateinit var etChatId: EditText
    private lateinit var llSendersContainer: LinearLayout
    private lateinit var etNewSender: EditText
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = ConfigManager.getInstance(this)
        tvStatus = findViewById(R.id.tv_status)
        etBotToken = findViewById(R.id.et_bot_token)
        etChatId = findViewById(R.id.et_chat_id)
        llSendersContainer = findViewById(R.id.ll_senders_container)
        etNewSender = findViewById(R.id.et_new_sender)

        // 저장된 설정 불러오기
        etBotToken.setText(config.botToken)
        etChatId.setText(config.chatId)

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            config.botToken = etBotToken.text.toString().trim()
            config.chatId = etChatId.text.toString().trim()
            Toast.makeText(this, "저장되었습니다", Toast.LENGTH_SHORT).show()
            updateStatus()
        }

        findViewById<Button>(R.id.btn_permission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btn_find_chat).setOnClickListener {
            findChatIds()
        }

        findViewById<Button>(R.id.btn_add_sender).setOnClickListener {
            val name = etNewSender.text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener
            config.allowedSenders = config.allowedSenders + name
            etNewSender.text.clear()
            renderSenders()
        }

        renderSenders()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val cn = ComponentName(this, RelayListener::class.java)
        return flat.contains(cn.flattenToString())
    }

    private fun updateStatus() {
        val permissionOk = isListenerEnabled()
        val configOk = config.isConfigured

        tvStatus.text = when {
            !permissionOk -> "⛔ 알림 접근 권한 없음"
            !configOk     -> "⚠️ 봇 토큰 또는 채팅 ID 미설정"
            else          -> "✅ 동작 중"
        }
    }

    private fun renderSenders() {
        llSendersContainer.removeAllViews()
        config.allowedSenders.sorted().forEach { sender ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (4 * resources.displayMetrics.density).toInt() }
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = sender
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val deleteBtn = Button(this).apply {
                text = "삭제"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    config.allowedSenders = config.allowedSenders - sender
                    renderSenders()
                }
            }
            row.addView(label)
            row.addView(deleteBtn)
            llSendersContainer.addView(row)
        }
    }

    private fun findChatIds() {
        val token = etBotToken.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, "봇 토큰을 먼저 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }

        val btn = findViewById<Button>(R.id.btn_find_chat)
        btn.isEnabled = false

        executor.execute {
            val chats = linkedMapOf<Long, String>()
            var errorMsg: String? = null

            try {
                val url = URL("https://api.telegram.org/bot$token/getUpdates")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val body = try {
                    conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Exception) {
                    conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: throw e
                } finally {
                    conn.disconnect()
                }

                val root = JSONObject(body)
                if (!root.optBoolean("ok", false)) {
                    errorMsg = root.optString("description", "API 오류")
                } else {
                    val results = root.getJSONArray("result")
                    for (i in 0 until results.length()) {
                        val update = results.getJSONObject(i)
                        val chatObj = update.optJSONObject("message")?.optJSONObject("chat")
                            ?: update.optJSONObject("channel_post")?.optJSONObject("chat")
                            ?: update.optJSONObject("my_chat_member")?.optJSONObject("chat")
                            ?: continue
                        val id = chatObj.optLong("id", 0L)
                        if (id == 0L || chats.containsKey(id)) continue
                        val label = chatObj.optString("title").ifEmpty {
                            listOf(chatObj.optString("first_name"), chatObj.optString("last_name"))
                                .filter { it.isNotEmpty() }
                                .joinToString(" ")
                        }.ifEmpty { "알 수 없음" }
                        chats[id] = "$label ($id)"
                    }
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: "알 수 없는 오류"
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btn.isEnabled = true
                if (errorMsg != null) {
                    Toast.makeText(this, "조회 실패: $errorMsg", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (chats.isEmpty()) {
                    Toast.makeText(
                        this,
                        "수신된 채팅방 없음 — 봇에 메시지를 보낸 후 다시 시도하세요",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                val entries = chats.entries.toList()
                val labels = entries.map { it.value }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("채팅방 선택")
                    .setItems(labels) { _, which ->
                        etChatId.setText(entries[which].key.toString())
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
    }
}
