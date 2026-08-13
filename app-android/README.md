# app-android — davi 기기측 실행기

Kotlin/Android 앱. **안내만** 한다(대신 조작 금지). 전용 규칙은 `CLAUDE.md`, 방향은 `../SPEC.md`.

실제 Gemini 안내를 따라 다른 앱을 조작하는 opt-in UIAutomator 실기기 테스트는
[`DEVICE-E2E.md`](DEVICE-E2E.md)를 참고한다. 일반 CI는 API 키 없이 test APK까지만 컴파일한다.

## 빌드·설치 (CLI 한 줄)

```powershell
./scripts/dev-install.ps1
```

빌드 → 기기 설치 → **접근성 서비스 재활성화**까지 한 번에 한다. 재설치하면 접근성 권한이 꺼지는
문제(`HANDOFF.md` §12)를 스크립트가 흡수하므로 매번 설정에 들어갈 필요가 없다.

이후 로그: `adb logcat -s GuideSvc`

### Android Studio로 빌드했다면 권한만 되살린다

**재설치하면 접근성 권한이 꺼진다.** Studio로 빌드하면 스크립트가 관여하지 않으니 매번 꺼진 상태가 된다.
그때는 빌드 없이 권한만 복구하면 된다(2초).

```powershell
./scripts/dev-install.ps1 -SkipBuild
```

옵션 정리

| 옵션 | 뜻 |
|---|---|
| `-SkipBuild` | 빌드·설치 없이 접근성 권한만 복구 |
| `-ClearLog` | 끝나고 logcat 버퍼 비우기 |
| `-SkipAccessibility` | 권한을 건드리지 않음 |

### `HANDOFF.md` §3-15 "CLI 빌드 불가"는 해결됐다

원인은 문서에 적힌 **TCP 루프백이 아니라 AF_UNIX(유닉스 도메인 소켓)** 였다.

JDK 17+ 는 Windows에서 `Selector.open()`에 AF_UNIX 소켓 쌍을 쓴다. 그 소켓 파일을 `%TEMP%`에
만들면 이 PC에서 **bind는 되는데 connect가 `Invalid argument`로 실패**한다. Gradle은 셀렉터 없이
못 돌기 때문에 `Unable to establish loopback connection`으로 죽는다. TCP 루프백 자체는 멀쩡하다.

소켓 파일 위치만 옮기면 정상 동작한다. 스크립트가 이렇게 처리한다.

```
JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<저장소>/.gradle-afunix-tmp
```

수동으로 `gradlew`를 쓸 때도 같은 환경변수를 주면 된다. 다른 PC에서 이 문제가 없다면 그냥 두면 된다
(폴더만 만들어지고 동작은 같다).

### Gradle

- **Gradle Wrapper가 있다.** `./gradlew test assembleDebug`로 단위 테스트와 디버그 APK를 함께 검증한다(CI도 이 기준을 쓴다). Gradle 9.5.0.
- `gradle/gradle-daemon-jvm.properties`가 데몬 JVM을 **Java 21 / JetBrains**로 고정한다.
  다른 JDK로 열면 Gradle이 해당 JDK를 자동으로 내려받는다.
- `gradlew`는 실행 비트(100755)와 LF 줄바꿈으로 커밋돼 있다(`.gitattributes`). Windows에서 편집해도
  이 속성이 깨지지 않게 유지할 것 — 깨지면 리눅스 CI에서 실행되지 않는다.
- AGP/Gradle/Kotlin 버전은 `gradle/libs.versions.toml`의 **시작 버전**이다. Studio가 업그레이드를 제안하면 따라도 된다.

### Android Studio는 언제 쓰나

디버거·Layout Inspector·프로파일러가 필요할 때만. 일상적인 빌드·설치·로그 확인은 CLI가 더 빠르다.

## 도그푸딩 관통 절차 (실기기)

닫힌 루프를 처음부터 끝까지 한 번 돌리는 절차다. **USB로 붙은 기기는 터널이 필요 없다.**

```powershell
# 1) 서버 (스텁으로 먼저)
cd server; .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --no-access-log --log-config app/logging.json

# 2) 기기 -> 호스트 PC 포트 연결. 터널 대신 이걸 쓴다
adb reverse tcp:8000 tcp:8000

# 3) local.properties 에 DAVI_DEBUG_SERVER_URL=http://127.0.0.1:8000 을 넣고 설치
cd app-android; ./scripts/dev-install.ps1 -ClearLog
```

앱에서 목표를 입력(또는 **말로 입력**) → **안내 시작** → 홈으로 이동 → 화면 위 강조·안내문 확인 → 따라 하면 다음 안내.
목표를 달성하면 서버가 `done`을 주고 오버레이가 사라진다.

### 음성 목표 입력

`말로 입력`은 pfront의 전체 실험 UI나 mock을 가져오지 않고 `SpeechRecognizerHelper`와 권한 처리만
선택 이식한 경로다. Android 12+에서 온디바이스 인식기가 있으면 우선 사용하고, 그 외 제공자에도
오프라인 선호 힌트를 보낸다. 최초 사용 때 `RECORD_AUDIO`를 요청하며 영구 거부 뒤에는 앱 설정으로
이동한다.

인식된 현재 발화와 이전에 끝난 user/assistant 쌍을 `/v1/intent/slot-fill`에 보낸다. 서버가
`needs_clarification`이면 `message`를 화면에 표시하고 Android TTS로 읽은 뒤 자동으로 다음 발화를
듣는다. `ready`이면 서버의 `goal`을 기존 입력칸에 넣고 끝낸다. **안내는 자동 시작하지 않으며**
사용자가 내용을 보고 기존 `안내 시작`을 눌러야 한다.

대화는 무작위 UUID별 최대 5왕복을 프로세스 메모리에만 두고 화면 이탈·재시작 때 폐기한다. 늦은
응답은 generation으로 버린다. 원시 음성을 Davi 서버에 보내거나 저장하지 않고, 발화·질문·goal·
대화 UUID는 `GuideSvc` 로그에 남기지 않는다. 인식된 텍스트와 이전 턴은 의미 보존을 위해 서버와
Gemini에 전달된다는 내부 도그푸딩 경계는 `../SPEC.md` §4에 명시돼 있다.

**옵트인 debug 빌드**(`local.properties`에 `DAVI_DOGFOOD=true`)는 접근성 서비스 연결 시
`GuideSvc`의 `connected ... dogfoodDeviceId=<16자 해시>`로 도그푸딩 화이트리스트 등록 값을
찍는다(#41 — 서버 `.env`의 `DAVI_DOGFOOD_DEVICE_IDS`, 절차는 `../server/README.md`
"도그푸딩 raw 보존"). 옵트인 없는 빌드와 release는 이 값을 계산하지도 보내지도 않는다.

배선이 확인되면 **실호출**로 한 번 더 돌린다. `.env`를 고치지 않고 환경변수로 덮어쓴다.

```powershell
cd server; $env:DAVI_USE_STUB_LLM="false"; .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --no-access-log --log-config app/logging.json
```

`GET /health`의 `stub_llm`이 `false`인지 먼저 확인한다. 무료 티어는 15 RPM / 1,000 RPD이고
RPD 리셋은 한국시간 17:00이다(`../HANDOFF.md` §10). 확인이 끝나면 서버를 내려 쿼터를 아낀다.

**평문 HTTP는 로컬·사설 주소를 쓰는 디버그 빌드에서만 열린다.** targetSdk 36은 평문 통신을
기본 차단하므로 Gradle이 debug 빌드마다 `network_security_config.xml`을 생성하고, 설정한
loopback 또는 RFC1918 사설 IPv4 host 하나에만 `domain-config` 평문 예외를 만든다. Azure
Container Apps HTTPS endpoint를 쓰면 이 예외 자체를 만들지 않고 일반 TLS로 연결한다.
**릴리스 빌드는 개발 endpoint 설정을 거부하며 평문도 계속 막혀 있다.**

### 관통 로그 (2026-07-27, SM-S921N / Android 16 / One UI 8.5)

```
GuideSvc: event=screen_stable package=com.sec.android.app.launcher element_count=8 clickable_count=8
GuideSvc: event=request_started logical_request_id=<uuid> attempt=1
GuideSvc: event=request_finished logical_request_id=<uuid> attempt=1 outcome=success status_code=200 server_request_id=<id>
GuideSvc: action=tap target=n8 direction=- history=0
GuideSvc: highlight n8 bounds=[787, 1902, 1035, 2170]
GuideSvc: event=screen_stable package=com.sec.android.app.camera element_count=14 clickable_count=13
GuideSvc: action=done target=- direction=- history=1
GuideSvc: session done
```

## 설정 (local.properties — gitignore됨)
```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
# 없으면 기본값 http://10.0.2.2:8000 (에뮬레이터→호스트 PC).
DAVI_DEBUG_SERVER_URL=http://192.168.x.y:8000
# 같은 Wi-Fi 공용 development 서버가 요구하는 32자 이상 토큰.
DAVI_DEBUG_ACCESS_TOKEN=<shared development token>
# 공기계(도그푸딩) 빌드 머신에서만: 기기 해시 계산·전송 옵트인(#41). 개인 기기 빌드엔 넣지 않는다.
DAVI_DOGFOOD=true
```

두 값은 debug `BuildConfig`에만 주입된다. 평문 HTTP는 `127.0.0.1`, `localhost`,
`10.0.2.2`와 RFC1918 사설 IPv4 literal의 8000 포트만 허용하며, URL에 별도 경로를 둘 수
없다. 사설 Wi-Fi 주소에는 토큰이 필수다. Azure 공유 개발 서버는 기본 443 포트의
`*.azurecontainerapps.io` **HTTPS** URL만 허용하며 원격 host이므로 토큰이 필수다. HTTPS에는
평문 통신 예외를 만들지 않는다. 그 밖의 공개 endpoint와 터널은 허용하지 않으며, release에는
어느 개발 값도 들어가지 않는다.

일회성 자동 검증은 파일에 토큰을 기록하지 않도록 같은 이름의 환경변수를 사용할 수 있다.
`local.properties`가 있으면 그 값이 우선하며, 환경변수도 debug 전용이고 release 검증에서는
개발 설정으로 취급한다.

release는 `https://example.invalid` 안전 placeholder와 빈 토큰으로만 빌드되고 평문 HTTP를
거부한다. `local.properties`에 `DAVI_DEBUG_SERVER_URL` 또는 `DAVI_DEBUG_ACCESS_TOKEN`이
남아 있으면 release 빌드 자체가 실패한다. 이전 `DAVI_SERVER_URL`은 사용하지 않는다.
**Gemini API 키는 앱에 넣지 않는다**(`../SPEC.md` §7).

같은 Wi-Fi 공용 서버의 기동·방화벽·DHCP 예약·종료 절차는 `../server/README.md`를 따른다.
Azure 공유 개발 서버의 최초 설정과 팀 전달 절차는
[`../docs/development/azure-dev-server.md`](../docs/development/azure-dev-server.md)를 따른다.

## 구성
| 경로 | 역할 |
|---|---|
| `app/src/main/AndroidManifest.xml` | 권한(INTERNET/오버레이/알림/마이크), MainActivity, 접근성 서비스 |
| `.../MainActivity.kt` | 온보딩 + 텍스트/음성 목표 입력, 안내 세션 시작·중지 |
| `.../GuideSession.kt` | 안내 세션 하나(목표·최근 안내·구조화 행동). 인메모리 — 영속화하지 않는다 |
| `.../GuideAccessibilityService.kt` | 닫힌 루프 본체. 로그 태그 `GuideSvc` |
| `.../GuideApiClient.kt` | `/v1/next-step` 호출 및 API/네트워크 오류 분류 |
| `.../SpeechRecognizerHelper.kt` | 온디바이스 우선 음성인식 결과 하나의 lifecycle 경계 |
| `.../VoiceDialogueSession.kt` | 최대 5왕복의 이전 턴과 늦은 응답 generation 격리 |
| `.../SlotFillApiClient.kt` | `/v1/intent/slot-fill` 계약·추적 header·오류 분류 |
| `.../TextToSpeechHelper.kt` | 서버 질문/완료 문장을 읽고 다음 청취 callback 연결 |
| `.../RequestTracing.kt` | 화면·세션 수명주기의 Logical Request ID와 HTTP attempt 계약 |
| `.../GuideTelemetry.kt` | 원문을 받지 않는 비식별 `screen/request/response` 이벤트 |
| `.../res/xml/accessibility_service_config.xml` | 접근성 서비스 설정 |
| `gradle/libs.versions.toml` | 버전 카탈로그 |

## 지금 무엇이 진짜인가

> **닫힌 루프가 실기기에서 한 번 관통됐다** (2026-07-27, SM-S921N / Android 16 / One UI 8.5).
> 목표 입력 → 안내 → 사용자가 따라 함 → 화면 변화 감지 → `done`까지. 스텁 1회, Gemini 실호출 1회.
> API 키는 앱에 넣지 않는다 — 서버만 안다.

**동작하는 구현**
- Gradle 구성(버전 카탈로그, viewBinding, debug 전용 서버 URL·개발 토큰 주입), 매니페스트, 접근성 서비스 등록.
- `MainActivity` + `GuideSession` — 두 권한이 있을 때만 목표를 입력해 안내를 시작·중지한다. 세션은 인메모리라 앱이 죽으면 함께 끝난다.
- 음성 목표 경로 — 마이크 권한, 온디바이스 우선 STT, LLM 후속 질문/TTS, 완성 goal의 기존 입력칸
  전달까지 연결된다. 앱은 고정 슬롯을 조합하거나 음성만으로 안내를 자동 시작하지 않는다.
- `res/values/dimens.xml` — 시니어용 글자·터치 대상 기본값.
- `GuideAccessibilityService` — **세션이 있을 때만** 활성 화면 루트를 고르고 최소 화면 트리로 직렬화해 `/v1/next-step`에 보낸다. 세션이 없으면 화면을 아예 읽지 않는다.
- `GuideApiClient` — 서버의 JSON 계약을 직렬화·파싱하고 Logical Request ID/attempt header를
  전송한다. 서버 오류는 `ApiError.kind`, 연결 오류는 `network_error`로 분류하며 앱을 종료하지 않는다.
- `OverlayController` + `HighlightOverlayView` — 서버가 고른 대상에 테두리를 그리고 안내문을 상자에 담아 함께 보여준다. `done`이면 지운다.
- `UiTreeSerializer` + `PrivacyMasker` — 서버 전송용 `label`을 만들 때 이메일·주민등록번호·카드번호·휴대폰·계좌번호를 `•••`로 1차 마스킹한다. 스크롤 메타·페이지 dot과
  `editable`·`focused`·`ime_visible`도 채운다. 일반 편집 요소의 입력된 `text`와 IME 키 트리는 보내지
  않고 hint만 label 후보로 쓴다(#112). `NumberPicker`가 현재 시·분을 `EditText`로 노출하면
  보임·활성·조작 action·가까운 조정 가능 조상을 확인한 짧은 숫자 label만 예외로 보존한다.
  이 선택값의 숫자는 화면 시그니처에도 포함해 picker 스크롤 뒤 후속 안내를 다시 요청한다.
  규칙은 `server/app/masking.py`와 같고 `app/src/test/`의 Kotlin 단위 테스트로 고정한다.

**관통에서 드러난 것 (다음 작업의 근거)**
- ~~**같은 안내를 반복한다.**~~ 문장 이력은 모델 프롬프트에 유지하고, 별도의
  `recent_actions`를 서버 `_apply_guardrails`가 비교한다(#36). 앱은 서버가 label 없이 만든
  화면 서명과 구조화 행동을 활성 세션에 최대 10개만 보관하며 세션 재시작 때 비운다.
- ~~**앱 진입 직후 트리가 덜 그려진 상태로 요청이 나간다.**~~ 같은 화면 연속 샘플을 확인한 뒤
  요청하고, 응답 대기 중 화면 변화와 정체를 폴링으로 판정한다(#39).
- 권한 상태·오류 문구가 화면에 없어 서버가 꺼지면 사용자는 이유를 알 수 없다.
- 정체 감지, 오버레이 불가 화면(설정 앱)의 헤드업 폴백은 아직 없다.

붙일 때 검증된 국소 구현은 `HANDOFF.md` §4 인덱스로 **해당 파일만** 참고한다(복사·이동 금지).

## 디버깅
- 1순위: `adb logcat -s GuideSvc` (재현 직전 `adb logcat -c`).
- 정상 왕복: 같은 `logical_request_id`의 `request_started` → `request_finished outcome=success`.
- 서버가 꺼짐: `request_finished outcome=network_error`만 남고 서비스는 계속 실행된다.
- 속성 확인: `adb shell uiautomator dump` (UTF-8로 읽기).
- adb 충돌 시: `taskkill /F /IM adb.exe` → `adb start-server`.
