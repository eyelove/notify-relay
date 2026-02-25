# notify-relay — 작업계획서

**프로젝트명:** notify-relay
**레포지토리:** `github.com/eyelove/notify-relay`
**패키지명:** `com.eyelove.notifyrelay`
**버전:** 2.1.0
**작성일:** 2026-02-25
**상태:** Draft

---

## 1. 개요

### 한줄 요약
갤럭시 문자 알림 중 **특정 발신자(카드사)**로부터 온 메시지를 감지하여 텔레그램으로 **원본 그대로** 전달하는 안드로이드 앱

### 배경
- 부부가 각자 본인 명의 카드(삼성, 신한, 현대, 하나)를 사용 중
- 카드 결제 시 상대방에게도 실시간으로 알림을 공유하고 싶음
- 기존 SMS 포워딩 앱이 안드로이드 보안 정책 강화 + RCS(채팅+) 전환으로 동작하지 않음
- 카드사 공식 서비스로는 본인 명의 카드의 결제 알림을 타인에게 동시 발송 불가

### 핵심 아이디어
- SMS 권한 대신 **Notification 접근 권한**을 사용하면 SMS/RCS/앱푸시 구분 없이 모든 알림 캡처 가능
- 알림의 title 필드에 **발신자명**이 포함되므로, 카드사 이름으로 필터링하면 정확도 높고 단순함
- 파싱 없이 **원본 메시지 그대로** 텔레그램에 전달하여 복잡도 최소화

---

## 2. 범위

### In Scope
- 내 폰(Galaxy S24, One UI 8.0)에만 설치
- 사전 정의한 발신자(카드사)의 알림만 텔레그램으로 전달
- 알림 원본 텍스트를 가공 없이 그대로 전송

### Out of Scope
- 메시지 파싱 (금액, 가맹점 추출 등)
- 구글 시트 기록 / 가계부 기능
- 아내 폰 설치
- Play 스토어 배포
- 자체 서버

---

## 3. 동작 흐름

```
카드 결제 발생
    ↓
갤럭시에 문자 알림 수신 (SMS 또는 RCS)
    ↓
Android Notification System
    ↓
notify-relay 앱 — NotificationListenerService
    ↓
필터링 (2단계)
    ├─ 1차: 대상 앱(삼성 메시지/Google 메시지)에서 온 알림인가?
    └─ 2차: 알림 title에 허용된 발신자명이 포함되어 있는가?
         ├─ YES → 텔레그램 Bot API로 원본 메시지 전송
         └─ NO  → 무시
```

### 텔레그램 수신 예시
```
📩 삼성카드
삼성카드 승인 45,000원 일시불 02/25 14:30 GS25강남역점
```

---

## 4. 기능 요구사항

### Must Have (P0)

| ID | 기능 | 설명 |
|----|------|------|
| F-01 | 알림 감지 | NotificationListenerService로 갤럭시 알림 실시간 수신 |
| F-02 | 발신자 필터링 | 알림 title(발신자명)이 Config의 허용 목록에 포함되는지 판별 |
| F-03 | 텔레그램 전송 | 필터 통과한 알림의 원본 텍스트를 텔레그램 Bot API로 전송 |
| F-04 | 백그라운드 유지 | 배터리 최적화 예외 설정 안내 + NotificationListenerService 시스템 바인딩으로 상시 동작 보장 |

### Should Have (P1)

| ID | 기능 | 설명 |
|----|------|------|
| F-11 | 서비스 on/off UI | 메인 화면에서 서비스 상태 확인 및 토글 |
| F-12 | 전송 실패 재시도 | 네트워크 오류 시 최대 3회 재시도 |
| F-13 | 알림 디버그 로그 | 감지된 알림의 packageName, title, text를 Logcat에 출력 (개발/디버깅용) |

### Won't Have (이번 릴리스)
- 메시지 파싱 / 가공
- 구글 시트 기록
- 금액 필터링
- 중복 알림 방지
- 과소비 분석

---

## 5. 기술 설계

### 5.1 기술 스택

| 구분 | 기술 | 이유 |
|------|------|------|
| 언어 | Kotlin | NotificationListenerService가 Android 네이티브 API |
| Min SDK | API 26 (Android 8.0) | NotificationListenerService 안정 지원 |
| HTTP | OkHttp 또는 kotlin 기본 HttpURLConnection | 텔레그램 API 호출용, 의존성 최소화 |
| 배포 | APK 사이드로드 | 개인용, Play 스토어 불필요 |

### 5.2 앱 구조

```
app/src/main/
├── AndroidManifest.xml
├── java/com/eyelove/notifyrelay/
│   ├── Config.kt                  # 설정값 (10줄)
│   ├── RelayListener.kt           # 알림 감지 + 필터 + 전송 (50줄)
│   └── MainActivity.kt            # 권한 안내 + on/off UI (30줄)
└── res/
    ├── layout/activity_main.xml
    └── values/strings.xml
```

### 5.3 컴포넌트 상세

#### Config.kt
```kotlin
package com.eyelove.notifyrelay

object Config {
    // 텔레그램
    const val TELEGRAM_BOT_TOKEN = "여기에_봇토큰"
    const val TELEGRAM_CHAT_ID = "여기에_채팅ID"

    // 감지 대상 앱 (알림을 수신하는 앱)
    val TARGET_PACKAGES = setOf(
        "com.samsung.android.messaging",      // 삼성 기본 메시지
        "com.google.android.apps.messaging",  // Google 메시지
    )

    // 허용할 발신자명 (알림 title과 매칭)
    val ALLOWED_SENDERS = setOf(
        "삼성카드",
        "신한카드",
        "현대카드",
        "하나카드",
        // 발신번호로도 가능 (연락처 미등록 시)
        // "1588-8700",
    )
}
```

#### RelayListener.kt — 핵심 로직

```kotlin
package com.eyelove.notifyrelay

class RelayListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 1. 대상 앱인지 확인
        if (sbn.packageName !in Config.TARGET_PACKAGES) return

        // 2. 발신자명과 본문 추출
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: return
        val text = extras.getCharSequence("android.text")?.toString() ?: return

        // 3. 디버그 로그 (개발 시 알림 구조 확인용)
        Log.d("NotifyRelay", "pkg=${sbn.packageName} title=$title text=$text")

        // 4. 허용된 발신자인지 확인 (title = 발신자명)
        if (Config.ALLOWED_SENDERS.none { title.contains(it) }) return

        // 5. 텔레그램으로 원본 전송
        sendToTelegram("📩 $title\n$text")
    }

    private fun sendToTelegram(message: String) {
        // Telegram Bot API 호출 (별도 스레드)
        Thread {
            try {
                val url = URL("https://api.telegram.org/bot${Config.TELEGRAM_BOT_TOKEN}/sendMessage")
                val body = JSONObject().apply {
                    put("chat_id", Config.TELEGRAM_CHAT_ID)
                    put("text", message)
                }
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    outputStream.write(body.toString().toByteArray())
                    responseCode // 전송 실행
                    disconnect()
                }
            } catch (e: Exception) {
                Log.e("NotifyRelay", "텔레그램 전송 실패", e)
                // P1: 재시도 로직 추가
            }
        }.start()
    }
}
```

#### MainActivity.kt
- 알림 접근 권한 허용 여부 확인
- 미허용 시 설정 화면으로 이동 안내
- 서비스 동작 상태 표시 (on/off)

### 5.4 AndroidManifest.xml

```xml
<!-- package: com.eyelove.notifyrelay -->

<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".RelayListener"
    android:exported="true"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

> **참고:** NotificationListenerService는 시스템이 바인딩하는 서비스이므로 별도의 FOREGROUND_SERVICE 권한이나 foregroundServiceType 선언이 필요하지 않습니다. 시스템이 직접 서비스 수명을 관리합니다.

### 5.5 필요 권한

| 권한 | 용도 | 비고 |
|------|------|------|
| Notification 접근 | 알림 읽기 (핵심) | 설정 → 알림 → 기기 및 앱 알림에서 수동 허용 |
| INTERNET | 텔레그램 API 호출 | 자동 허용 |
| POST_NOTIFICATIONS | 서비스 상태 알림 표시 (선택) | Android 13+ |

**SMS/SEND_SMS 권한 불필요**
**FOREGROUND_SERVICE 불필요** — NotificationListenerService는 시스템 바인딩 서비스

### 5.6 백그라운드 동작 보장

NotificationListenerService는 시스템이 관리하는 서비스로, 일반적인 백그라운드 서비스와 달리 시스템에 의해 강제 종료되지 않습니다. 다만 배터리 최적화 정책에 의해 간헐적으로 중단될 수 있으므로 아래 설정이 필요합니다:

1. **배터리 최적화 예외**: 설정 → 앱 → notify-relay → 배터리 → "제한 없음"
2. **앱 절전 제외**: 설정 → 배터리 → 백그라운드 사용 제한 → notify-relay 제외

별도의 Foreground Service 구현은 불필요합니다. 위 설정만으로 안정적인 상시 동작이 보장됩니다.

---

## 6. 사전 준비

### 6.1 텔레그램 봇 생성
1. 텔레그램에서 `@BotFather` → `/newbot` → 봇 토큰 복사
2. 부부 공유 그룹 생성 → 봇 초대
3. 그룹에 아무 메시지 전송 후 `https://api.telegram.org/bot<TOKEN>/getUpdates` 에서 Chat ID 확인
4. 봇 토큰 → `Config.TELEGRAM_BOT_TOKEN`, Chat ID → `Config.TELEGRAM_CHAT_ID`

### 6.2 발신자명 확인 (⚠️ 개발 전 필수 검증)

카드사 알림의 title/text 구조는 앱마다 다를 수 있으므로, **개발 착수 전에 반드시 실제 알림 구조를 확인**해야 합니다.

**확인 방법:**
1. Android Studio에서 기기 연결 후 Logcat 열기
2. 필터: `NotifyRelay`
3. 앱 설치 후 디버그 로그 활성화 상태에서 실제 카드 결제 수행
4. 각 카드사별로 아래 항목 확인:

| 확인 항목 | 설명 |
|-----------|------|
| packageName | 어떤 앱에서 알림이 오는가 |
| title | 발신자명이 여기에 있는가, 어떤 형태인가 |
| text | 메시지 본문이 여기에 있는가 |

**특히 확인 필요한 케이스:**
- RCS(채팅+): title 형태가 SMS와 다를 수 있음
- 삼성카드: 앱 푸시가 없으므로 SMS/RCS로만 수신됨

이 검증 결과에 따라 `Config.ALLOWED_SENDERS`와 `RelayListener`의 필터링 로직을 조정합니다.

### 6.3 Galaxy S24 설정
- **알림 접근 권한 허용**: 설정 → 알림 → 기기 및 앱 알림 → notify-relay 활성화
- **배터리 최적화 예외**: 설정 → 앱 → notify-relay → 배터리 → "제한 없음"

> **참고:** Notification 접근 권한은 "제한된 설정" 대상이 아닙니다. 사이드로드 APK라도 위 경로에서 정상적으로 허용할 수 있습니다.

---

## 7. 개발 순서

| 순서 | 작업 | 완료 기준 | 예상 시간 |
|------|------|-----------|-----------|
| 1 | Android Studio 프로젝트 생성 + Manifest 설정 | 빌드 성공 | 10분 |
| 2 | Config.kt 작성 (봇 토큰, 발신자 목록) | - | 5분 |
| 3 | RelayListener 구현 (디버그 로그만) | Logcat에 감지된 알림의 pkg/title/text 출력 | 20분 |
| 4 | **⚠️ 알림 구조 검증** | 실제 카드 결제하여 각 카드사별 title/text 형태 확인, Config 조정 | 30분 |
| 5 | 텔레그램 전송 기능 추가 | 테스트 알림 → 텔레그램 수신 확인 | 20분 |
| 6 | MainActivity 권한 안내 화면 | 권한 미허용 시 설정으로 이동 | 20분 |
| 7 | 배터리 최적화 예외 설정 안내 | 설정 가이드 or 인앱 안내 | 10분 |
| 8 | 실제 카드 결제로 E2E 테스트 | 결제 → 텔레그램 수신 확인 | 10분 |

**총 예상 소요: 약 2시간**

> **주의:** 4번 "알림 구조 검증"이 가장 중요한 단계입니다. 여기서 확인된 결과에 따라 필터링 로직이 달라질 수 있으므로, 텔레그램 전송 기능(5번)보다 반드시 먼저 수행해야 합니다.

---

## 8. 테스트

### 테스트 방법

| 방법 | 설명 | 용도 |
|------|------|------|
| Logcat 디버그 | 앱 설치 후 아무 알림이나 수신, Logcat에서 pkg/title/text 확인 | 알림 구조 파악 |
| 연락처 테스트 | 다른 폰 번호를 "삼성카드"로 연락처에 저장 → 해당 번호로 문자 발송 | 발신자 필터링 검증 |
| 소액 결제 | 실제 카드로 소액 결제 | E2E 검증 |

### 확인 사항
- [ ] SMS로 온 카드 알림이 텔레그램에 도착하는가
- [ ] RCS(채팅+)로 온 카드 알림이 텔레그램에 도착하는가
- [ ] 일반 문자는 무시되는가
- [ ] 앱 종료 후에도 동작하는가
- [ ] 비행기모드 해제 후 밀린 알림이 전송되는가

---

## 9. 리스크

| 리스크 | 영향 | 대응 |
|--------|------|------|
| 알림 title에 발신자명이 예상과 다르게 표시 | 필터링 실패 | 개발 순서 4번에서 실제 알림 구조 사전 검증, Config 조정 |
| One UI 업데이트로 NotificationListener 동작 변경 | 서비스 중단 | 배터리 최적화 예외 설정으로 방어 |
| 동일 결제에 SMS + 앱푸시 동시 수신 | 중복 전송 | Phase 1에서는 허용, 필요 시 향후 중복 제거 로직 추가 |

---

## 10. 향후 확장 (필요 시)

- 카카오톡 알림톡 지원 (TARGET_PACKAGES에 com.kakao.talk 추가 + text 매칭)
- 구글 시트 기록 추가 (Google Apps Script 웹앱 연동)
- 메시지 파싱 (금액, 가맹점 추출)
- 아내 폰에도 설치하여 양쪽 카드 통합
- 월간 지출 요약 자동 전송
- 중복 알림 방지 (금액+시간 기반 dedup)
