package com.eyelove.notifyrelay

import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class RelayListener : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var config: ConfigManager

    override fun onCreate() {
        super.onCreate()
        config = ConfigManager.getInstance(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 1. 대상 앱(삼성/Google 메시지)인지 확인
        if (sbn.packageName !in config.targetPackages) return

        // 2. 발신자명과 본문 추출 (MessagingStyle 우선 → 일반 fallback)
        val (sender, text) = extractMessage(sbn) ?: return

        // 3. 디버그 로그 — 개발 4단계에서 실제 알림 구조 확인용
        Log.d(TAG, "pkg=${sbn.packageName} sender=$sender text=$text")

        // 4. 허용된 발신자인지 확인
        if (config.allowedSenders.none { sender.contains(it) }) return

        // 5. 설정 미완료 시 전송 생략
        if (!config.isConfigured) {
            Log.w(TAG, "봇 토큰 또는 채팅 ID 미설정 — 전송 생략")
            return
        }

        // 6. 텔레그램으로 전송 (단일 스레드 ExecutorService)
        executor.submit { sendToTelegram("📩 $sender\n$text") }
    }

    // 시스템이 리스너 연결을 끊었을 때 재바인딩 요청
    // executor는 rebind 후 동일 인스턴스가 재사용되므로 여기서 shutdown하지 않음
    override fun onListenerDisconnected() {
        requestRebind(ComponentName(this, RelayListener::class.java))
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    /**
     * 알림에서 발신자와 메시지 본문을 추출한다.
     * 최신 메시징 앱은 MessagingStyle을 사용하므로 이를 먼저 시도하고,
     * 없으면 일반 title/text로 fallback한다.
     */
    private fun extractMessage(sbn: StatusBarNotification): Pair<String, String>? {
        val extras = sbn.notification.extras

        // MessagingStyle 시도 (최신 삼성/Google 메시지 앱)
        val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray("android.messages", Bundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray("android.messages")
        }
        if (!messages.isNullOrEmpty()) {
            val last = messages.last() as? Bundle
            val msgText = last?.getCharSequence("text")?.toString()
            val sender = last?.getCharSequence("sender")?.toString()
                ?: extras.getString("android.title")
            if (msgText != null && sender != null) return Pair(sender, msgText)
        }

        // 일반 알림 fallback
        val title = extras.getString("android.title") ?: return null
        val text = extras.getCharSequence("android.text")?.toString() ?: return null
        return Pair(title, text)
    }

    private fun sendToTelegram(message: String) {
        try {
            val url = URL("https://api.telegram.org/bot${config.botToken}/sendMessage")
            val body = JSONObject().apply {
                put("chat_id", config.chatId)
                put("text", message)
            }
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray()) }
                val code = responseCode
                if (code in 200..299) inputStream.use { it.readBytes() }
                else errorStream?.use { it.readBytes() }
                Log.d(TAG, "텔레그램 응답: $code")
                disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "텔레그램 전송 실패", e)
        }
    }

    companion object {
        private const val TAG = "NotifyRelay"
    }
}
