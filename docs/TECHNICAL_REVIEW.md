# notify-relay 기술 검토 결과

**검토일:** 2026-02-25
**검토 대상:** docs/PRD.md (v2.1.0)
**검토 결과:** 총 11건 (Critical 3, High 3, Medium 3, Low 2)

---

## 요약

PRD의 핵심 아이디어(NotificationListenerService로 카드사 알림을 텔레그램에 전달)는 기술적으로 타당합니다. 그러나 Android 13+ 보안 정책, Samsung 배터리 최적화, 알림 파싱 방식에서 PRD의 주장과 실제가 다른 부분이 있어 수정이 필요합니다.

---

## Critical (3건) — 구현 전 반드시 수정

### 1. Android 13+ "제한된 설정" — PRD 주장 오류

**PRD 주장 (6.3절):**
> "Notification 접근 권한은 '제한된 설정' 대상이 아닙니다. 사이드로드 APK라도 위 경로에서 정상적으로 허용할 수 있습니다."

**실제:**
Android 13(API 33)부터 Google이 도입한 "Restricted Settings"는 **NotificationListenerService**와 **AccessibilityService** 두 가지를 명시적으로 차단합니다. 사이드로드된 APK(브라우저 다운로드, 파일 관리자, `adb install` 등 non-session-based 설치)로 설치하면 알림 접근 권한 활성화 시 **"보안을 위해 이 설정은 현재 사용할 수 없습니다"** 다이얼로그가 표시됩니다.

Galaxy S24는 One UI 8.0 (Android 16, API 36)을 실행하므로 이 제한이 적용됩니다.

**해결 방안 (우선순위순):**

1. **Split APK Installer(SAI)로 설치** — Play 스토어에서 SAI 설치 → SAI로 APK 설치 (session-based 설치로 처리됨) → 앱 정보에서 "제한된 설정 허용" 토글 접근 가능
2. **수동 허용** — APK 설치 후 → 앱 아이콘 길게 누름 → 앱 정보 → 점3개 메뉴 → "제한된 설정 허용" (일부 Samsung 기기에서는 이 메뉴가 숨겨져 있을 수 있음)
3. **ADB 명령** — `adb shell appops set com.eyelove.notifyrelay ACCESS_RESTRICTED_SETTINGS allow` (기기/버전에 따라 동작하지 않을 수 있음)

**수정 필요:**
- PRD 6.3절 "Galaxy S24 설정"에 제한된 설정 우회 과정 추가
- MainActivity에 알림 접근 권한 미부여 시 단계별 설정 가이드 표시 구현

**출처:** [XDA Developers](https://www.xda-developers.com/android-13-restricted-setting-notification-listener/), [Esper](https://www.esper.io/blog/android-13-sideloading-restriction-harder-malware-abuse-accessibility-apis), [DroidWin](https://droidwin.com/android-13-restricted-settings-for-sideloaded-apps-how-to-bypass/)

---

### 2. MessagingStyle 알림 파싱 — 필터링 로직 수정 필요

**PRD 설계 (5.3절 RelayListener.kt):**
```kotlin
val title = extras.getString("android.title") ?: return
val text = extras.getCharSequence("android.text")?.toString() ?: return
```

**실제:**
삼성 메시지/Google 메시지 등 최신 메시징 앱은 `NotificationCompat.MessagingStyle`을 사용합니다. 이 경우:
- `EXTRA_TITLE` / `EXTRA_TEXT`가 null이거나 대화 제목(그룹명 등)을 반환할 수 있음
- 실제 메시지 데이터는 `extras.getParcelableArray("android.messages")` 배열 안에 있음
- 각 메시지는 Bundle로 `"text"`, `"time"`, `"sender_person"` (API 28+) 키를 포함

또한 한국 카드사 SMS의 경우, 알림 title에 발신자명("삼성카드")이 아닌 **전화번호**("1588-8700")가 표시될 수 있습니다 (연락처 미등록 시).

**권장 파싱 로직:**
```kotlin
fun extractMessage(sbn: StatusBarNotification): Pair<String, String>? {
    val extras = sbn.notification.extras

    // 1. MessagingStyle 시도
    val messages = extras.getParcelableArray("android.messages")
    if (messages != null && messages.isNotEmpty()) {
        val lastMessage = messages.last() as? Bundle
        val text = lastMessage?.getCharSequence("text")?.toString()
        val sender = (lastMessage?.getParcelable<Person>("sender_person"))?.name?.toString()
            ?: lastMessage?.getCharSequence("sender")?.toString()
        if (text != null) return Pair(sender ?: "Unknown", text)
    }

    // 2. 일반 알림 fallback
    val title = extras.getString("android.title")
    val text = extras.getCharSequence("android.text")?.toString()
    if (title != null && text != null) return Pair(title, text)

    return null
}
```

**수정 필요:**
- PRD 5.3절 RelayListener.kt에 이중 파싱 로직 반영
- Config.ALLOWED_SENDERS에 전화번호도 포함 가능하도록 주석에 명시 (이미 부분적으로 있음)
- 개발 순서 4번 "알림 구조 검증"이 이 문제를 해결하는 핵심 단계임을 재확인

**출처:** [Android MessagingStyle API](https://developer.android.com/reference/android/app/Notification.MessagingStyle)

---

### 3. 봇 토큰 하드코딩 — 보안 리스크

**PRD 설계 (Config.kt):**
```kotlin
const val TELEGRAM_BOT_TOKEN = "여기에_봇토큰"
```

**리스크:**
- APK 디컴파일 도구(jadx, apktool)로 토큰이 평문 추출됨
- 토큰으로 봇의 전체 제어 가능 (메시지 읽기/쓰기)
- 토큰 교체 시 APK 재빌드 필요

**평가:**
개인 기기 1대에만 설치하는 용도이므로 **허용 가능한 수준**입니다. 다만 개선 방안:

| 방법 | 복잡도 | 장점 |
|------|--------|------|
| Config.kt 하드코딩 (현재) | 최소 | 단순함 |
| `local.properties` + `BuildConfig` | 낮음 | 소스코드에서 토큰 분리, git 추적 제외 가능 |
| SharedPreferences + 설정 UI | 중간 | 런타임에 토큰 입력/변경 가능, 재빌드 불필요 |

**최소 권장:** `.gitignore`에 `Config.kt`를 추가하여 토큰이 git에 커밋되지 않도록 하거나, `local.properties` → `BuildConfig` 방식 사용

---

## High (3건) — 기능/안정성에 큰 영향

### 4. onListenerDisconnected 미처리 — 서비스 조용히 중단 가능

**PRD 현황:** RelayListener.kt에 `onNotificationPosted()`만 구현, `onListenerDisconnected()` 미언급

**문제:**
시스템이 리스너를 끊으면(메모리 부족, 배터리 최적화 등) 자동 재바인딩이 보장되지 않습니다. 특히 Samsung 기기에서 이 현상이 잘 보고됩니다.

**필수 추가 코드:**
```kotlin
override fun onListenerDisconnected() {
    requestRebind(ComponentName(this, RelayListener::class.java))
}
```

이 없으면 앱이 **사용자 모르게 동작을 멈추고**, 카드 결제 알림이 텔레그램에 전달되지 않는 심각한 상황이 발생합니다.

**출처:** [Android API - requestRebind](https://developer.android.com/reference/android/service/notification/NotificationListenerService#requestRebind), [XDA Forums](https://xdaforums.com/t/notificationlistenerservice-gets-killed-but-not-restarted.2722627/)

---

### 5. Samsung 배터리 최적화 — 가이드 불충분

**PRD 현황 (5.6절):** 2가지 설정만 언급
1. 배터리 최적화 예외: 배터리 → "제한 없음"
2. 앱 절전 제외: 백그라운드 사용 제한 → notify-relay 제외

**실제 필요한 전체 설정:**
Samsung One UI는 Android 기본 배터리 최적화 위에 자체 최적화 레이어를 추가합니다. 다음 **모든** 설정이 필요합니다:

| # | 설정 경로 | 값 |
|---|-----------|-----|
| 1 | 설정 → 앱 → notify-relay → 배터리 | "제한 없음" |
| 2 | 설정 → 앱 → notify-relay → 배터리 → 배터리 최적화 | "최적화하지 않음" |
| 3 | 설정 → 배터리 → 백그라운드 사용 제한 → 잠자지 않는 앱 | 목록에 추가 |
| 4 | 설정 → 배터리 → 백그라운드 사용 제한 → 사용하지 않는 앱 일시중지 | 비활성화 |
| 5 | 최근 앱 → notify-relay → 잠금(Lock) | 활성화 |

> 비활성화한 제한이 OS 업데이트 후 초기화될 수 있습니다. 기본적으로 3일간 실행하지 않은 앱은 자동으로 잠자기 상태로 전환됩니다.

**출처:** [dontkillmyapp.com/samsung](https://dontkillmyapp.com/samsung)

---

### 6. AndroidManifest.xml 불완전

**PRD 현황 (5.4절):** `<service>` 선언만 있고 `<application>`, `<activity>` 선언 누락

**완전한 Manifest 구조:**
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.eyelove.notifyrelay">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.NotifyRelay">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".RelayListener"
            android:exported="true"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>

    </application>
</manifest>
```

**누락 항목:**
- `<application>` 태그 (icon, label, theme)
- `<activity>` 선언 (MAIN/LAUNCHER intent-filter)
- `android:exported="true"` 명시 (Android 12+ 필수)

---

## Medium (3건) — 품질/신뢰성 영향

### 7. Thread{}.start() → ExecutorService 또는 Coroutines 권장

**PRD 설계:** `Thread { sendToTelegram(...) }.start()`

**문제점:**
- 서비스 종료 시 스레드가 남아 메모리 누수 발생 가능
- 알림 급증 시 무제한 스레드 생성 (backpressure 없음)
- 에러 발생 시 조용히 실패 (UncaughtExceptionHandler 미설정)
- 재시도 로직(P1) 구현이 어려움

**권장 대안 (복잡도 순):**

| 방법 | 의존성 | 장점 |
|------|--------|------|
| `Executors.newSingleThreadExecutor()` | 없음 (JDK 내장) | 스레드 1개 재사용, shutdown 가능, 순차 처리 |
| `kotlinx.coroutines` | ~1.6MB | 구조적 동시성, 취소, SupervisorJob, 재시도 용이 |

**최소 변경 (ExecutorService):**
```kotlin
private val executor = Executors.newSingleThreadExecutor()

override fun onNotificationPosted(sbn: StatusBarNotification) {
    // ... 필터링 ...
    executor.submit { sendToTelegram(message) }
}

override fun onListenerDisconnected() {
    executor.shutdown()
    requestRebind(ComponentName(this, RelayListener::class.java))
}
```

**결론:** PRD의 "복잡도 최소화" 원칙에 따라 `Executors.newSingleThreadExecutor()`가 가장 적합합니다. 외부 의존성 없이 스레드 관리/재사용/종료가 가능합니다.

---

### 8. 빌드 설정 누락

**PRD에 누락된 항목:** Gradle, AGP, Kotlin 버전, compileSdk, targetSdk

**권장 빌드 설정:**

| 항목 | 값 | 이유 |
|------|-----|------|
| AGP | 8.7.x | 안정 버전, compileSdk 35 지원 |
| Kotlin | 2.0.x ~ 2.1.x | AGP 8.7.x 호환 |
| Gradle | 8.9+ | AGP 8.7.x 요구 |
| compileSdk | 35 | Android 15 API |
| targetSdk | 34~35 | 안정 타겟 |
| minSdk | 26 | PRD와 동일 |
| 빌드 스크립트 | Kotlin DSL (.kts) | 업계 표준, 타입 안전 |
| Java | 17 | AGP 8.x 요구 |

> 참고: 최신 AGP 9.0.x도 출시되었으나, 안정성을 위해 8.7.x를 권장합니다. AGP 9.0은 Kotlin 내장 지원 등 큰 변경이 있어 신규 프로젝트에서는 선택 가능합니다.

---

### 9. POST_NOTIFICATIONS 용도 불명확

**PRD 현황 (5.4절, 5.5절):** POST_NOTIFICATIONS 권한을 선언하고 "서비스 상태 알림 표시 (선택)"으로 설명

**명확화 필요:**
- `POST_NOTIFICATIONS`는 알림을 **표시**하기 위한 권한 (Android 13+)
- 알림을 **읽기** 위한 것이 아님 (읽기는 Notification 접근 권한)
- 앱이 자체 상태 알림(예: "서비스 동작 중")을 표시할 경우에만 필요
- 순수한 백그라운드 릴레이만 할 경우 불필요

**권장:** 앱이 자체 알림을 표시할 계획이 없다면 이 권한 선언을 제거. 표시할 계획이라면 런타임 권한 요청 로직 추가 필요.

---

## Low (2건) — 개선사항

### 10. HTTP 클라이언트: HttpURLConnection 확정

**PRD (5.1절):** "OkHttp 또는 kotlin 기본 HttpURLConnection"

**권장:** `HttpURLConnection` 확정
- Telegram Bot API는 단순 HTTPS POST 1개
- OkHttp 추가 시 APK 크기 ~1MB 증가 + 의존성 관리 부담
- HttpURLConnection은 Android SDK 내장, 추가 설정 불필요
- 재시도 로직도 단순 루프로 구현 가능

---

### 11. ProGuard/R8

**개인용 사이드로드 앱이므로 코드 난독화가 불필요합니다.**
- release 빌드에서 `isMinifyEnabled = false`로 설정하면 충분
- 만약 활성화할 경우: Manifest에 선언된 컴포넌트(RelayListener, MainActivity)는 AAPT2가 자동 보존하므로 별도 keep 규칙 불필요
- HttpURLConnection 사용 시 외부 라이브러리 관련 규칙도 불필요

---

## PRD에 추가 권장하는 항목

| 항목 | 설명 |
|------|------|
| 제한된 설정 우회 가이드 | 6절에 SAI 설치 또는 수동 허용 절차 추가 |
| Samsung 배터리 전체 설정 | 5.6절에 5가지 설정 모두 기재 |
| 완전한 Manifest | 5.4절에 application/activity 포함한 전체 구조 |
| onListenerDisconnected | 5.3절에 requestRebind 로직 추가 |
| 빌드 설정 | 5.1절에 AGP/Kotlin/Gradle 버전 명시 |
| MessagingStyle 파싱 | 5.3절에 이중 파싱 로직 반영 |
| 인앱 서비스 상태 확인 | MainActivity에서 `Settings.Secure.getString(contentResolver, "enabled_notification_listeners")` 확인 |

---

## PRD 주장 검증 결과 요약

| PRD 주장 | 검증 결과 |
|----------|-----------|
| NotificationListenerService에 FOREGROUND_SERVICE 불필요 | ✅ 맞음 |
| 사이드로드 APK도 알림 접근 권한 정상 허용 가능 | ❌ **틀림** — Android 13+에서 차단됨 |
| 배터리 최적화 예외 2가지면 충분 | ⚠️ 부분적 — Samsung에서는 5가지 설정 필요 |
| 알림 title에 발신자명 포함 | ⚠️ 부분적 — MessagingStyle 사용 시 다를 수 있음 |
| Thread{}.start()로 충분 | ⚠️ 동작은 하나 개선 권장 (ExecutorService) |
| POST_NOTIFICATIONS 필요 (Android 13+) | ✅ 맞음 (앱 자체 알림 표시 시에만) |
| SMS/SEND_SMS 권한 불필요 | ✅ 맞음 |
| BOOT_COMPLETED BroadcastReceiver 불필요 | ✅ 맞음 (시스템이 자동 리바인딩) |

---

## 출처

- [XDA Developers - Android 13 Restricted Settings](https://www.xda-developers.com/android-13-restricted-setting-notification-listener/)
- [Esper - Android 13 Sideloading Restriction](https://www.esper.io/blog/android-13-sideloading-restriction-harder-malware-abuse-accessibility-apis)
- [DroidWin - Bypass Restricted Settings](https://droidwin.com/android-13-restricted-settings-for-sideloaded-apps-how-to-bypass/)
- [Don't Kill My App - Samsung](https://dontkillmyapp.com/samsung)
- [Android API - NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [Android API - requestRebind](https://developer.android.com/reference/android/service/notification/NotificationListenerService#requestRebind)
- [Android API - MessagingStyle](https://developer.android.com/reference/android/app/Notification.MessagingStyle)
- [XDA Forums - NotificationListenerService killed](https://xdaforums.com/t/notificationlistenerservice-gets-killed-but-not-restarted.2722627/)
