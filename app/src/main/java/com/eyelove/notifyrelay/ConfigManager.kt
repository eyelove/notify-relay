package com.eyelove.notifyrelay

import android.content.Context
import android.content.SharedPreferences

class ConfigManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var botToken: String
        get() = prefs.getString(KEY_BOT_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_BOT_TOKEN, value).apply() }

    var chatId: String
        get() = prefs.getString(KEY_CHAT_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_CHAT_ID, value).apply() }

    val isConfigured: Boolean
        get() = botToken.isNotBlank() && chatId.isNotBlank()

    // 알림을 수신하는 앱 패키지명
    val targetPackages: Set<String> = setOf(
        "com.samsung.android.messaging",     // 삼성 기본 메시지
        "com.google.android.apps.messaging", // Google 메시지
    )

    // 허용할 발신자명 — 개발 4단계에서 실제 알림 구조 확인 후 조정
    // 연락처 미등록 시 전화번호로 표시될 수 있으므로 번호 추가 가능
    val allowedSenders: Set<String> = setOf(
        "삼성카드",
        "신한카드",
        "현대카드",
        "하나카드",
    )

    companion object {
        private const val PREFS_NAME = "notify_relay_config"
        private const val KEY_BOT_TOKEN = "bot_token"
        private const val KEY_CHAT_ID = "chat_id"

        @Volatile
        private var instance: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager =
            instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
    }
}
