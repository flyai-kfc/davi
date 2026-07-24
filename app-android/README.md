# app-android — davi 기기측 실행기

Kotlin/Android 앱. **안내만** 한다(대신 조작 금지). 전용 규칙은 `CLAUDE.md`, 방향은 `../SPEC.md`.

## 열기·빌드
**빌드는 Android Studio에서만.** 이 개발 환경은 Gradle이 loopback을 못 열어 CLI 빌드가 실패한다
(`HANDOFF.md` §3-15). Android Studio로 `app-android/` 폴더를 연다.

- **Gradle Wrapper jar(`gradle/wrapper/gradle-wrapper.jar`)와 `gradlew` 스크립트는 현재 없다.** 나중에 추가한다.
  `gradle-wrapper.properties`만 있어 Android Studio에서 여는 데는 문제가 없지만, `./gradlew`로는 실행할 수 없다.
  추가하려면 Gradle이 설치된 환경에서 `gradle wrapper --gradle-version 8.13`.
  추가한 뒤에는 CI(`.github/workflows/ci.yml`)를 `gradle` → `./gradlew`로 바꾸고 wrapper 검증 스텝을 켠다.
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
| `.../GuideAccessibilityService.kt` | 닫힌 루프 본체 골격. 로그 태그 `GuideSvc` |
| `.../res/xml/accessibility_service_config.xml` | 접근성 서비스 설정 |
| `gradle/libs.versions.toml` | 버전 카탈로그 |

## 지금 무엇이 진짜인가

> **주의: 실기기에서 빌드·실행해 본 적이 없다.** 이 환경은 Gradle CLI가 막혀 있어 문법 검토만 했다.
> Android Studio 첫 Sync에서 버전 충돌이 날 수 있다.

**동작하는 구현**
- Gradle 구성(버전 카탈로그, viewBinding, `BuildConfig.SERVER_URL` 주입), 매니페스트, 접근성 서비스 등록.
- `MainActivity` — 온보딩 화면. 접근성 설정·오버레이 권한 화면으로 실제로 보낸다. XML + ViewBinding.
- `res/values/dimens.xml` — 시니어용 글자·터치 대상 기본값.

**껍데기**
- `GuideAccessibilityService` — `onServiceConnected`에서 로그 한 줄만 찍는다.
  **`onAccessibilityEvent`는 비어 있다.** 즉 화면 트리 직렬화·1차 마스킹·서버 호출·오버레이·정체 감지가
  전부 없다. 앱은 아직 아무것도 안내하지 못한다.

붙일 때 검증된 국소 구현은 `HANDOFF.md` §4 인덱스로 **해당 파일만** 참고한다(복사·이동 금지).

## 디버깅
- 1순위: `adb logcat -s GuideSvc` (재현 직전 `adb logcat -c`).
- 속성 확인: `adb shell uiautomator dump` (UTF-8로 읽기).
- adb 충돌 시: `taskkill /F /IM adb.exe` → `adb start-server`.
