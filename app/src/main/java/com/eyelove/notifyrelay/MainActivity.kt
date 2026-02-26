package com.eyelove.notifyrelay

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var config: ConfigManager
    private lateinit var tvStatus: TextView
    private lateinit var etBotToken: EditText
    private lateinit var etChatId: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = ConfigManager.getInstance(this)
        tvStatus = findViewById(R.id.tv_status)
        etBotToken = findViewById(R.id.et_bot_token)
        etChatId = findViewById(R.id.et_chat_id)

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
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
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
}
