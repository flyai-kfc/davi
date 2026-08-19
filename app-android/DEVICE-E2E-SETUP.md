# 실기기 E2E 환경 설정

이 문서는 실제 Android 기기와 실제 Gemini를 사용하는 Davi UIAutomator E2E를 처음 실행할 개발자를
위한 준비 절차다. 테스트 실행법은 [`DEVICE-E2E-RUN.md`](DEVICE-E2E-RUN.md), 새 시나리오 작성법은
[`DEVICE-E2E-SCENARIOS.md`](DEVICE-E2E-SCENARIOS.md)를 참고한다.

## 1. 필요한 도구

| 도구 | 용도 | 확인 명령 |
|---|---|---|
| JDK 17 이상 | Android/Gradle 빌드 | `java -version` |
| Android SDK Platform Tools | 실기기 연결과 instrumentation | `adb version` |
| Python 3.12 | 서버와 Markdown 결과 생성 | `python3 --version` 또는 `python --version` |
| USB/무선 디버깅 기기 | 외부 앱 관통 테스트 | `adb devices` |

macOS/Linux 실행기는 Bash와 `curl`, Windows 실행기는 PowerShell 5.1 이상을 사용한다. Windows에서는
Android Studio 기본 JBR을 쓸 수 있다.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

macOS에서 기본 경로가 아닌 SDK/JDK를 사용한다면 직접 지정한다.

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

## 2. 서버 비밀값

Gemini API 키는 서버에만 둔다. 앱 소스, `local.properties`, 테스트 시나리오, 결과 보고서에는 넣지
않는다. `server/.env`는 Git에서 제외된다.

기본 실행은 `build/reports/device-e2e-private/`에 앱이 전송한 1차 마스킹 화면과 모델 문장을 담은
로컬 상세 보고서를 만든다. 이 경로는 Git에서 제외되지만 개발자가 직접 첨부하면 유출될 수 있으므로
PR·이슈·메신저로 공유하지 않는다.

```dotenv
GEMINI_API_KEY=<개발자 개인 키>
DAVI_USE_STUB_LLM=false
```

저장소의 현재 서버 설정에 `DAVI_DEV_ACCESS_TOKEN`이 있다면 Android debug 빌드에도 같은 값을
`DAVI_DEBUG_ACCESS_TOKEN`으로 제공해야 한다. 값을 문서나 shell history에 남기지 않는 조직의 비밀값
주입 방식을 사용한다.

## 3. 실제 Gemini 서버 실행

macOS/Linux:

```bash
cd server
DAVI_USE_STUB_LLM=false .venv/bin/python -m uvicorn app.main:app \
  --host 127.0.0.1 --port 8000
```

Windows PowerShell:

```powershell
cd server
$env:DAVI_USE_STUB_LLM = 'false'
.\.venv\Scripts\python.exe -m uvicorn app.main:app `
  --host 127.0.0.1 --port 8000
```

별도 터미널에서 다음 응답의 `stub_llm`이 `false`인지 확인한다.

```text
GET http://127.0.0.1:8000/health
```

## 4. 실기기 준비

1. 개발자 옵션과 USB 또는 무선 디버깅을 켠다.
2. `adb devices`에서 대상이 `device` 상태인지 확인한다.
3. 기기 설정의 접근성 목록에서 **「다비 도우미 (개발)」**을 직접 허용한다.
   **필요한 특수 권한은 이것 하나다**(#212).
   debug와 release가 함께 깔리면 항목이 둘 뜨고 **E2E가 도는 것은 (개발) 쪽뿐이다**(#58).
   제품 쪽(「다비 도우미」)을 켜면 실행기가 "bound 상태가 아닙니다"로 멈추는데, 그 문구가
   껐다 켜라고 안내해서 **틀린 항목을 껐다 켜는 루프**에 빠지기 쉽다.
4. 여러 기기가 연결됐다면 `ANDROID_SERIAL`을 지정한다.

```powershell
$env:ANDROID_SERIAL = '<adb serial>'
```

```bash
export ANDROID_SERIAL='<adb serial>'
```

실행기는 처음부터 꺼져 있던 접근성 권한을 임의로 허가하지 않는다. 권한이 켜진 상태에서만 APK
교체 중 Davi 항목을 잠시 내렸다가 원래 목록에 복구하며, 다른 접근성 서비스는 유지한다.
실행 중에는 Davi와 분리된 UIAutomator helper/test APK를 임시 설치한다. 두 패키지에는 launcher
화면이 없으며 실행 종료 시 자동 제거되고 Davi 앱만 남는다.

## 5. 현재 시나리오의 기기 조건

`samsung-clock-create-alarm-full-journey`는 SM-S921N, Android 15, 한국어 Samsung launcher와
Samsung Clock 12.4.10.10에서 검증됐다. `samsung-clock-create-alarm-focused`는 같은 시계의 알람
목록부터 시작하는 진단용이다. 다른 런처·시계 버전에서는 label, resource ID나 화면 구조 차이로 안전
검사가 실패할 수 있다. 이것은 테스트가 임의로 우회할 조건이 아니라 호환성 결과로 기록할 조건이다.

## 6. 준비 확인

다음 명령이 시나리오 목록을 출력하면 호스트 실행기와 registry를 읽을 수 있는 상태다.

```bash
cd app-android
./scripts/run-device-e2e.sh --list-scenarios
```

```powershell
cd app-android
.\scripts\run-device-e2e.ps1 -ListScenarios
```
