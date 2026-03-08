# notify-relay

카드사 문자 알림을 텔레그램으로 실시간 전달하는 Android 앱

삼성/Google 메시지 앱의 알림을 감지하여 지정한 발신자(카드사)의 메시지를 텔레그램 그룹으로 포워딩합니다.
SMS, RCS(채팅+), 앱 푸시 구분 없이 알림 시스템 레벨에서 동작합니다.

---

## 동작 방식

```
카드 결제 → 문자 수신 → notify-relay 감지 → 텔레그램 전송
```

```
📩 삼성카드
삼성카드 승인 45,000원 일시불 02/25 14:30 GS25강남역점
```

---

## 요구사항

- Android 8.0 (API 26) 이상
- 텔레그램 계정

---

## 설치

### 1. APK 빌드

```bash
# Android Studio로 열기 후
# Build → Build Bundle(s) / APK(s) → Build APK(s)
# 생성 위치: app/build/outputs/apk/debug/app-debug.apk
```

### 2. 기기에 설치

USB 연결 후 ADB로 설치:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 알림 접근 권한 부여

Android 13 이상에서 사이드로드 앱은 알림 접근 권한이 제한됩니다.
ADB로 직접 부여하는 것이 가장 간단합니다:

```bash
adb shell cmd notification allow_listener \
  com.eyelove.notifyrelay/com.eyelove.notifyrelay.RelayListener
```

---

## 텔레그램 봇 설정

### 1. 봇 생성

텔레그램에서 `@BotFather` 검색 후:

```
/newbot
→ 봇 이름 입력 (예: 카드알림봇)
→ username 입력 (예: mycard_notify_bot)
→ 발급된 토큰 복사
```

### 2. 프라이버시 모드 비활성화

그룹 메시지를 수신하려면 반드시 필요합니다:

```
@BotFather → /mybots → 봇 선택
→ Bot Settings → Group Privacy → Turn off
```

### 3. 그룹 생성 및 채팅 ID 확인

1. 텔레그램에서 새 그룹 생성 후 봇 초대
2. 그룹에 아무 메시지나 전송
3. 아래 명령으로 채팅 ID 확인:

```bash
curl "https://api.telegram.org/bot{봇토큰}/getUpdates"
```

응답에서 `"chat": {"id": -1001234567890}` 형태의 숫자가 채팅 ID입니다.

---

## 앱 설정

앱 실행 후:

1. **봇 토큰** 입력
2. **채팅방 찾기** 버튼으로 채팅방 자동 조회 → 선택 (또는 채팅 ID 직접 입력)
3. **저장** 탭
4. 상태가 **"✅ 동작 중"** 으로 바뀌면 완료

> **채팅방 찾기**: 봇 토큰 입력 후 "채팅방 찾기" 버튼을 누르면 봇이 참여 중인 채팅방 목록이 표시됩니다. 목록이 비어있다면 텔레그램에서 봇에게 아무 메시지나 보낸 후 다시 시도하세요.

---

## 배터리 최적화 설정

백그라운드에서 앱이 종료되지 않도록 아래 설정을 모두 적용하세요.
Samsung One UI 기준입니다.

| 경로 | 값 |
|------|----|
| 설정 → 앱 → notify-relay → 배터리 | 제한 없음 |
| 설정 → 앱 → notify-relay → 배터리 → 배터리 최적화 | 최적화하지 않음 |
| 설정 → 배터리 → 백그라운드 사용 제한 → 잠자지 않는 앱 | 목록에 추가 |
| 최근 앱 화면 → notify-relay | 잠금 |

> OS 업데이트 후 일부 설정이 초기화될 수 있습니다.

---

## 발신자 관리

앱 화면의 **3단계 — 발신자 관리** 섹션에서 알림을 전달할 발신자를 관리합니다.

- 기본값: 삼성카드, 신한카드, 현대카드, 하나카드
- **추가**: 발신자명(또는 전화번호 앞자리) 입력 후 "추가" 버튼
- **삭제**: 발신자 옆 "삭제" 버튼

변경 사항은 즉시 반영되며, 별도 저장이 필요하지 않습니다.

> 연락처에 미등록된 번호로 수신되는 경우 전화번호(예: `1588-8700`)를 발신자로 추가할 수 있습니다.

### 감지 대상 앱

삼성 메시지와 Google 메시지 앱의 알림을 감지합니다. 대상 앱은 `ConfigManager.kt`의 `targetPackages`에서 변경할 수 있습니다.

---

## 동작 확인

연락처에 임의 번호를 `삼성카드`로 저장 후 해당 번호로 문자를 보내면
텔레그램으로 메시지가 전달되는지 확인할 수 있습니다.

Logcat에서 실시간 로그 확인:

```
필터: NotifyRelay
```

---

## 기술 스택

- Kotlin
- Android NotificationListenerService
- Telegram Bot API
- HttpURLConnection
