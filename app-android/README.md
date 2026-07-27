# app-android — davi 기기측 실행기

Kotlin/Android 앱. **안내만** 한다(대신 조작 금지). 전용 규칙은 `CLAUDE.md`, 방향은 `../SPEC.md`.

## 열기·빌드
Android Studio로 `app-android/` 폴더를 열거나 Gradle Wrapper로 빌드한다.

- **Gradle Wrapper가 있다.** `./gradlew test assembleDebug`로 단위 테스트와 디버그 APK를 함께 검증한다(CI도 이 기준을 쓴다). Gradle 9.5.0.
- `gradle/gradle-daemon-jvm.properties`가 데몬 JVM을 **Java 21 / JetBrains**로 고정한다.
  다른 JDK로 열면 Gradle이 해당 JDK를 자동으로 내려받는다.
- `gradlew`는 실행 비트(100755)와 LF 줄바꿈으로 커밋돼 있다(`.gitattributes`). Windows에서 편집해도
  이 속성이 깨지지 않게 유지할 것 — 깨지면 리눅스 CI에서 실행되지 않는다.
- AGP/Gradle/Kotlin 버전은 `gradle/libs.versions.toml`의 **시작 버전**이다. Studio가 업그레이드를 제안하면 따라도 된다.

## 설정 (local.properties — gitignore됨)
```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
# 서버 터널 URL. 없으면 기본값 http://10.0.2.2:8000 (에뮬레이터→호스트 PC).
# 실기기는 cloudflared/ngrok 터널 URL을 넣는다(../server/README.md).
DAVI_SERVER_URL=https://<your-tunnel>.trycloudflare.com
```
`DAVI_SERVER_URL`은 `BuildConfig.SERVER_URL`로 주입된다. **API 키는 앱에 넣지 않는다**(`../SPEC.md` §7).

## 구성
| 경로 | 역할 |
|---|---|
| `app/src/main/AndroidManifest.xml` | 권한(INTERNET/오버레이/알림), MainActivity, 접근성 서비스 |
| `.../MainActivity.kt` | 온보딩 골격(접근성·오버레이 권한 안내, 두 모드 자리) |
| `.../GuideAccessibilityService.kt` | 닫힌 루프 본체. 로그 태그 `GuideSvc` |
| `.../GuideApiClient.kt` | `/v1/next-step` 호출 및 API/네트워크 오류 분류 |
| `.../res/xml/accessibility_service_config.xml` | 접근성 서비스 설정 |
| `gradle/libs.versions.toml` | 버전 카탈로그 |

## 지금 무엇이 진짜인가

> 실기기에서는 `DAVI_SERVER_URL`에 서버 터널 URL을 설정한 뒤 테스트한다. API 키는 앱에 넣지 않는다.

**동작하는 구현**
- Gradle 구성(버전 카탈로그, viewBinding, `BuildConfig.SERVER_URL` 주입), 매니페스트, 접근성 서비스 등록.
- `MainActivity` — 온보딩 화면. 접근성 설정·오버레이 권한 화면으로 실제로 보낸다. XML + ViewBinding.
- `res/values/dimens.xml` — 시니어용 글자·터치 대상 기본값.
- `GuideAccessibilityService` — 활성 화면 루트를 고르고 최소 화면 트리로 직렬화한 뒤, 백그라운드에서 `/v1/next-step`에 보낸다.
- `GuideApiClient` — 서버의 `NextStepRequest`/`NextStepResponse` 계약을 직렬화·파싱한다. 성공 시 `action_type`, `target_node_id`, `instruction`을 로그로 남기고 서버가 지정한 대상만 오버레이로 강조한다. 서버 오류는 `ApiError.kind`, 연결 오류는 `network_error`로 기록하며 앱을 종료하지 않는다.
- `UiTreeSerializer` + `PrivacyMasker` — 서버 전송용 `label`을 만들 때 이메일·주민등록번호·카드번호·휴대폰·계좌번호를 `•••`로 1차 마스킹한다.
  규칙은 `server/app/masking.py`와 같고 `app/src/test/`의 Kotlin 단위 테스트로 고정한다.

**남은 작업**
- 스크롤 메타·페이지 dot·정체 감지.

붙일 때 검증된 국소 구현은 `HANDOFF.md` §4 인덱스로 **해당 파일만** 참고한다(복사·이동 금지).

## 디버깅
- 1순위: `adb logcat -s GuideSvc` (재현 직전 `adb logcat -c`).
- 정상 왕복: `action=tap target=<node_id> instruction=<안내>`.
- 서버가 꺼짐: `network_error`만 남고 서비스는 계속 실행된다.
- 속성 확인: `adb shell uiautomator dump` (UTF-8로 읽기).
- adb 충돌 시: `taskkill /F /IM adb.exe` → `adb start-server`.
