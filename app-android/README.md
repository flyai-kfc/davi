# app-android — davi 기기측 실행기

Kotlin/Android 앱. **안내만** 한다(대신 조작 금지). 전용 규칙은 `CLAUDE.md`, 방향은 `../SPEC.md`.

실제 Gemini 안내를 따라 다른 앱을 조작하는 opt-in UIAutomator 실기기 테스트는 다음 문서에서
확인한다. 일반 CI는 API 키 없이 test APK까지만 컴파일한다.

- [구조와 판정 원칙](DEVICE-E2E.md)
- [환경 설정](DEVICE-E2E-SETUP.md)
- [실행과 결과 읽기](DEVICE-E2E-RUN.md)
- [새 시나리오 추가](DEVICE-E2E-SCENARIOS.md)
- [구현 시 실제 검증 결과](DEVICE-E2E-RESULTS.md)

## 빌드·설치 (CLI 한 줄)

```powershell
./scripts/dev-install.ps1
```

빌드 → 기기 설치 → **접근성 서비스 재활성화** → **오버레이 권한 확인**까지 한 번에 한다.
재설치하면 접근성 권한이 꺼지는 문제(`HANDOFF.md` §12)를 스크립트가 흡수하므로 매번 설정에
들어갈 필요가 없다.

이후 로그: `adb logcat -s GuideSvc`

### 지금 상태만 보고 싶다면

아무것도 바꾸지 않고 기기·설치 패키지·접근성·오버레이 상태를 한 줄씩 출력한다.

```powershell
./scripts/dev-install.ps1 -Status
```

```
기기      : SM-S921N [R3CX...] 
패키지    : com.davi.helper 0.1.0 설치됨
접근성    : 목록엔 있으나 바인딩 없음 — -A11yOnly로 복구
오버레이  : 설정된 적 없음(미허용) — 설정에서 "다른 앱 위에 표시"를 켤 것
```

**목록 등록과 실제 바인딩은 다르다.** `settings`에 값이 들어가 있어도 서비스가 붙지 않은 상태가
있고(`dumpsys`의 `Bound services:{}`), 그때 앱은 조용히 아무 안내도 내지 않는다. `-Status`는 그
둘을 구분해서 보여준다.

### Android Studio로 빌드했다면 권한만 되살린다

**재설치하면 접근성 권한이 꺼진다.** Studio로 빌드하면 스크립트가 관여하지 않으니 매번 꺼진 상태가 된다.
그때는 빌드 없이 권한만 복구하면 된다(2초).

```powershell
./scripts/dev-install.ps1 -A11yOnly
```

옵션 정리

| 옵션 | 뜻 |
|---|---|
| `-Status` | 아무것도 바꾸지 않고 상태만 출력. 다른 옵션은 무시 |
| `-A11yOnly` (= `-SkipBuild`) | 빌드·설치 없이 권한만 복구 |
| `-ClearLog` | 끝나고 logcat 버퍼 비우기 |
| `-SkipAccessibility` | 접근성 권한을 건드리지 않음 |
| `-SkipOverlayCheck` | 오버레이 권한을 확인하지 않음 |
| `-Serial <serial>` | 기기가 여러 대일 때 대상 지정 (`ANDROID_SERIAL`도 됨) |
| `-PackageName <pkg>` | debug 패키지명을 직접 지정 |

기기가 **없거나 여러 대**면 설치하지 않고 멈춘다. 여러 대일 때는 `-Serial`로 대상을 지정한다.

패키지명은 `app/build/outputs/apk/debug/output-metadata.json`의 `applicationId`에서 읽는다.
debug 앱 ID에 접미어가 붙어도 올바른 접근성 서비스를 켜기 위해서다. 산출물이 없으면
`com.davi.helper`로 떨어지고, `-PackageName`으로 덮어쓸 수 있다.

### 오버레이 권한

접근성과 별개로 **"다른 앱 위에 표시"** 가 없으면 안내가 그려지지 않는다. 스크립트가 설치 후
상태를 확인하고, 꺼져 있으면 해당 설정 화면을 띄운다. 자동으로 허용하지는 않는다 —
`appops set`은 제조사 정책에 막히는 경우가 있어 사람이 화면에서 켜는 편이 확실하다.

One UI 8.5(SM-S921N 실측)는 `package:` 딥링크를 무시하고 **앱별 화면이 아니라 전체 앱 목록**을
연다. 목록에서 "다비 도우미"를 찾아 토글을 켜면 된다.

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
듣는다. `ready`이면 `/v1/intent/app-resolution`에서 서버의 일반 앱 후보(최대 5개)를 받고,
기기 메모리의 `LaunchableAppIndex`와 package exact→정규화 label alias 순으로 대조한다. 앱 하나를
확정하거나 goal을 다시 쓰지 않는다. `launchable` 후보가 하나 이상이면 원래 goal의 최종 확인으로,
하나도 없거나 index가 불안정하거나 후보 요청이 실패하면 “이 과정에 필요한 앱이 설치되어 있지 않을
수 있어요. 그래도 계속할까요?” 경고와 같은 확인쌍으로 간다. 후보 요청 실패도 이미 완성된 goal을
폐기하지 않는다. 경고의 ○는 원래 goal 최종 확인 화면으로 이동하고, 그 화면에서 다시 ○를 눌러야
안내를 시작한다. 어느 화면에서든 ×는 홈으로 돌아간다. **안내는 자동 시작하지 않는다.** 안내 중 앱
탐색 반복은 기존 서버 next-step 경로가 맡는다(#185).

index는 `QUERY_ALL_PACKAGES`를 쓰지 않고 현재 접근 가능한 profile의 `MAIN`/`LAUNCHER` activity만
조회한다. 잠긴 profile은 지금 안내할 수 없는 범위라 제외한다. 설치·삭제·변경 callback은 revision을
올리고 기존 snapshot을 무효화만 하며, 다음 음성 대조 때 작업 스레드에서 전체를 다시 읽는다. 같은
profile·package의 launcher activity가 여러 개여도 한 앱으로 판정한다. 전체 목록과 기기에서만 발견한
package·label·component·profile은 서버·파일·상세 로그에 나가지 않는다.

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
# 1차 마스킹 우회(#203). masked/unmasked 비교 실험 전용이며 기본은 꺼짐이다.
# 켜면 원문 화면 label이 서버와 실제 Gemini까지 나간다. 합성 데이터만 넣은 전용 기기에서만.
# 실행 하나만 덮으려면 -PdaviDisablePrivacyMasking=true/false (속성이 이 파일보다 우선).
# 실험이 끝나면 지운다 — 남겨 두면 그 머신이 계속 우회 APK를 만든다.
DAVI_DISABLE_PRIVACY_MASKING=false
```

위 개발 설정은 필요한 항목만 debug `BuildConfig`에 주입된다. 평문 HTTP는 `127.0.0.1`, `localhost`,
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

`DAVI_DISABLE_PRIVACY_MASKING`도 마찬가지로 release 관문에 걸린다 — **값이 `false`여도
설정이 남아 있다는 것 자체를 실패로 본다.** `-PdaviDisablePrivacyMasking`을 준 release 빌드도
같다. release `BuildConfig`의 `PRIVACY_MASKING_DISABLED`는 어느 경우에도 상수 `false`다.
비교 실험 절차는 `DEVICE-E2E-RUN.md`, 경계는 `../SPEC.md` §3에 있다.
**Gemini API 키는 앱에 넣지 않는다**(`../SPEC.md` §7).

### release 서명 (#221)

위 값들과 달리 **서명 키는 debug 전용 설정이 아니다.** release가 정당하게 쓰는 값이라
release 관문에 걸리지 않는다.

```properties
# 업로드 키스토어. \ 는 .properties의 이스케이프 문자이므로 경로는 / 로 쓴다.
DAVI_RELEASE_KEYSTORE=C:/path/to/davi-upload.jks
DAVI_RELEASE_KEYSTORE_PASSWORD=<store 비밀번호>
# 없으면 davi-upload. 키 비밀번호가 따로 없으면 store 비밀번호를 쓴다.
DAVI_RELEASE_KEY_ALIAS=davi-upload
DAVI_RELEASE_KEY_PASSWORD=<key 비밀번호>
```

**값이 없으면 서명하지 않고 그대로 빌드한다.** CI와 키를 갖지 않은 팀원 PC에서
`assembleRelease`가 계속 돌아야 하기 때문이다 — 그 경우 산출물이
`app-release-unsigned.apk`가 되고 기기 설치·Play 업로드만 안 된다. 키가 있으면
`app-release.apk`와 `bundleRelease`의 `.aab`가 서명된 채로 나온다.

**어느 한쪽만 준 설정은 조용히 넘어가지 않고 빌드가 실패한다.** 경로를 주고 비밀번호를
빠뜨린 경우도, 반대로 비밀번호·alias는 넣고 경로를 빠뜨린 경우도 같다. 뒤쪽이 특히
`DAVI_RELEASE_KEYSTORE` 이름을 오타 냈을 때의 모습인데, 막지 않으면 나머지를 제대로 넣고도
서명 없는 APK가 나가고 그 사실을 Play 업로드 단계에서야 알게 된다.

**keystore 파일과 비밀번호는 저장소에 넣지 않는다.** `*.jks`·`*.keystore`는 gitignore돼
있지만 워킹트리 밖에 두는 편이 안전하다. 업로드 키를 잃어도 Play App Signing을 쓰면 Google에
재설정을 요청할 수 있다 — 앱 서명 키는 Google이 보관한다.

서명된 release를 로컬에서 만들려면 **debug 설정을 지운 `local.properties`가 필요하다**
(`verifyReleaseConfiguration`이 debug 값이 남아 있으면 막는다).

#### 카카오 키 해시 — 서명 인증서마다 다르다

카카오는 로그인 요청에 실린 **서명 인증서의 지문**을 콘솔 등록 목록과 대조한다. 서명한 키가
다르면 지문도 다르므로, **우리 앱은 서명 주체가 셋이고 등록할 키 해시도 셋이다.**

| 빌드 | 서명하는 키 | 그 빌드를 만나는 곳 |
|---|---|---|
| debug | 각자 PC의 `~/.android/debug.keystore` | 개발 중 실기기 |
| release, 직접 설치 | 업로드 키(`davi-upload.jks`) | `adb install`로 넣는 서명 검증 |
| release, Play 배포 | **Google의 앱 서명 키** | 내부 테스트·프로덕션 |

**셋 다 등록한다.** Play가 AAB를 받아 재서명하므로 마지막 것은 업로드 키 해시와 다르고,
**첫 업로드 전에는 존재하지도 않는다.** 그래서 로그인이 실패하면 **어느 경로로 설치한
앱인지부터** 확인한다 — 빠진 해시만 그 경로에서 실패한다.

앞의 둘은 keystore에서 직접 계산한다. `-rfc`는 인증서를 **텍스트(PEM)로** 내보내므로
Windows PowerShell이 파이프에서 바이너리를 깨뜨리는 함정을 피하고 `openssl`도 필요 없다.

```powershell
$kt = "$env:JAVA_HOME\bin\keytool.exe"
$pem = & $kt -exportcert -rfc -alias davi-upload -keystore C:/path/to/davi-upload.jks -storepass '<store 비밀번호>'
$der = [Convert]::FromBase64String((($pem | Where-Object { $_ -notmatch '-----' }) -join ''))
[Convert]::ToBase64String([Security.Cryptography.SHA1]::Create().ComputeHash($der))
```

debug 키는 `-alias androiddebugkey -keystore $env:USERPROFILE/.android/debug.keystore
-storepass android`로 같은 명령을 돌린다. **`keytool -list -v`로 SHA-1을 읽는 방법은 쓰지
않는다** — 한국어 로케일 JDK에서 `IllegalFormatConversionException`으로 죽는다(실측).

Play 앱 서명 키는 keystore가 우리에게 없다. 첫 AAB 업로드 뒤 **Play Console → 설정 → 앱
무결성**에 뜨는 SHA-1 지문을 복사해 같은 형식으로 바꾼다.

```powershell
$sha1 = 'A1:B2:...'   # Play Console에서 복사한 SHA-1
[Convert]::ToBase64String(($sha1 -split ':' | ForEach-Object { [Convert]::ToByte($_,16) }))
```

Git Bash·macOS·Linux에서는 `keytool -exportcert -alias <alias> -keystore <keystore> | openssl
sha1 -binary | base64` 한 줄로도 같은 값이 나온다.

같은 Wi-Fi 공용 서버의 기동·방화벽·DHCP 예약·종료 절차는 `../server/README.md`를 따른다.
Azure 공유 개발 서버의 최초 설정과 팀 전달 절차는
[`../docs/development/azure-dev-server.md`](../docs/development/azure-dev-server.md)를 따른다.

## 구성

**경계만 적는다.** 소스 파일은 30개가 넘고 계속 는다 — 전부 나열하면 곧 낡으므로, 여기에는
**진입점·계약 경계·독립 판정 단위**만 둔다. 나머지는 `app/src/main/java/com/davi/helper/`를 보면 된다.

| 경로 | 역할 |
|---|---|
| `app/src/main/AndroidManifest.xml` | 권한(INTERNET/오버레이/알림/마이크), 두 Activity, 접근성 서비스 |
| `.../OnboardingActivity.kt` | 권한 두 개를 켜게 안내하고 갖춰지면 대화 화면으로(ON-2). 접근성은 **고지·동의 면을 거쳐야** 설정으로 보낸다(#227) |
| `.../ConversationActivity.kt` | 릴리스 진입점. 음성 목표 입력과 안내 시작(MA-*) |
| `.../GuideAccessibilityService.kt` | 닫힌 루프 본체. 로그 태그 `GuideSvc` |
| **계약 경계** | |
| `.../GuideApiClient.kt` | `/v1/next-step` 호출 및 API/네트워크 오류 분류 |
| `.../SlotFillApiClient.kt` | `/v1/intent/slot-fill` 계약·추적 header·오류 분류 |
| `.../AppResolutionApiClient.kt` | `/v1/intent/app-resolution` 계약·추적 header·오류 분류 |
| `.../RequestTracing.kt` | 화면·세션 수명주기의 Logical Request ID와 HTTP attempt 계약 |
| `.../GuideTelemetry.kt` | 원문을 받지 않는 비식별 `screen/request/response` 이벤트 |
| **경계가 되는 판정 단위** (여기 없는 것도 JVM 테스트가 있다 — `app/src/test/` 참고) | |
| `.../GuideSession.kt` | 안내 세션 하나(목표·최근 안내·구조화 행동·완료 확인 대기). 인메모리 — 영속화하지 않는다 |
| `.../GuideDisplayState.kt` | 오버레이에 지금 무엇이 보여야 하는가(재표시·정체 접미사) |
| `.../ScreenStability.kt` | 화면 서명과 안정·정체 판정 |
| `.../UiTreeSerializer.kt` + `.../PrivacyMasker.kt` | 접근성 트리 → 요소 목록, 1차 마스킹 |
| `.../LaunchableAppIndex.kt` | launcher 앱의 메모리 index·정규화 대조·callback revision |
| `.../AppResolutionCoordinator.kt` | 후보 생성→로컬 대조→실행 가능성 사전 점검과 generation 격리 |
| **그 밖** | |
| `.../*View.kt` · `.../*Motion.kt` · `res/layout/` | 화면·오버레이 렌더링과 모션 |
| `.../res/xml/accessibility_service_config.xml` | 접근성 서비스 설정 |
| `gradle/libs.versions.toml` | 버전 카탈로그 |
| `app/src/debug/` | 개발 전용. 백업 화면 `MainActivity`(런처에 없다 — `adb shell am start`로 연다)와 E2E 브리지 |
| `e2e-driver/` | 실기기 E2E 드라이버(별도 앱). `DEVICE-E2E.md` |

## 지금 무엇이 진짜인가

> **닫힌 루프가 실기기에서 한 번 관통됐다** (2026-07-27, SM-S921N / Android 16 / One UI 8.5).
> 목표 입력 → 안내 → 사용자가 따라 함 → 화면 변화 감지 → `done`까지. 스텁 1회, Gemini 실호출 1회.
> API 키는 앱에 넣지 않는다 — 서버만 안다.

**동작하는 구현**
- Gradle 구성(버전 카탈로그, viewBinding, debug 전용 서버 URL·개발 토큰 주입), 매니페스트, 접근성 서비스 등록.
- `OnboardingActivity` → `ConversationActivity` + `GuideSession` — 두 권한이 갖춰지면 대화 화면으로
  넘어가고, 거기서 목표를 정해 안내를 시작·중지한다(#123). 밖에서 권한이 꺼지면 세션을 끊고
  온보딩으로 되돌린다. 세션은 인메모리라 앱이 죽으면 함께 끝난다.
- 음성 목표 경로 — 마이크 권한, 온디바이스 우선 STT, LLM 후속 질문/TTS, 완성 goal 전달까지
  연결된다. 앱은 고정 슬롯을 조합하거나 음성만으로 안내를 자동 시작하지 않는다.
- `res/values/dimens.xml` — 시니어용 글자·터치 대상 기본값.
- `GuideAccessibilityService` — **세션이 있을 때만** 활성 화면 루트를 고르고 최소 화면 트리로 직렬화해 `/v1/next-step`에 보낸다. 세션이 없으면 화면을 아예 읽지 않는다.
- `GuideApiClient` — 서버의 JSON 계약을 직렬화·파싱하고 Logical Request ID/attempt header를
  전송한다. 서버 오류는 `ApiError.kind`, 연결 오류는 `network_error`로 분류하며 앱을 종료하지 않는다.
- `OverlayController` — 오버레이 **둘**을 소유한다(#126). **표시 오버레이**(`HighlightOverlayView`)는
  강조 테두리와 화면 가장자리 테두리를 그리고 터치를 아래 앱으로 통과시키며, **조작 오버레이**
  (`ControlOverlay`)는 안내창과 칩이 살고 그 영역의 터치를 받는다. 화면 가장자리 테두리는 두 tone을
  나눠 쓴다 — 읽는 중(#127)과 완료 확인(#139). 완료는 사용자가 ○/×로 답해야 끝난다.
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
- 권한 상태·오류 문구가 화면에 없어 서버가 꺼지면 사용자는 이유를 알 수 없다(#29).

붙일 때 검증된 국소 구현은 `HANDOFF.md` §4 인덱스로 **해당 파일만** 참고한다(복사·이동 금지).

## 디버깅
- 1순위: `adb logcat -s GuideSvc` (재현 직전 `adb logcat -c`).
- 정상 왕복: 같은 `logical_request_id`의 `request_started` → `request_finished outcome=success`.
- 서버가 꺼짐: `request_finished outcome=network_error`만 남고 서비스는 계속 실행된다.
- 속성 확인: `adb shell uiautomator dump` (UTF-8로 읽기).
- adb 충돌 시: `taskkill /F /IM adb.exe` → `adb start-server`.
