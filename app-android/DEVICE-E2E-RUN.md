# 실기기 E2E 실행과 결과 읽기

환경을 처음 구성한다면 먼저 [`DEVICE-E2E-SETUP.md`](DEVICE-E2E-SETUP.md)를 완료한다. 이 테스트는
실제 Gemini 호출 비용과 비결정성을 가지며 CI에서는 자동 실행하지 않는다.

## 시나리오 선택

macOS/Linux:

```bash
cd app-android
./scripts/run-device-e2e.sh --list-scenarios
./scripts/run-device-e2e.sh --scenario samsung-clock-create-alarm-full-journey
```

Windows PowerShell:

```powershell
cd app-android
.\scripts\run-device-e2e.ps1 -ListScenarios
.\scripts\run-device-e2e.ps1 -Scenario samsung-clock-create-alarm-full-journey
```

시나리오를 생략하면 `device-e2e-scenarios.json`의 `default_scenario`가 실행된다. 두 실행기는 같은
registry를 읽으므로 OS마다 시나리오 목록을 따로 관리하지 않는다.

상세 분석 보고서는 기본으로 함께 생성된다. 화면·모델 문장이 포함된 로컬 상세 파일이 필요 없는
실행에서는 다음처럼 끈다.

```bash
./scripts/run-device-e2e.sh --no-detailed-report
```

```powershell
.\scripts\run-device-e2e.ps1 -NoDetailedReport
```

## 실행 순서

실행기는 다음을 자동 수행한다.

1. `/health`가 실제 모델 모드인지 확인한다.
2. 대상 기기와 접근성 권한을 확인한다.
3. 필요한 경우 `adb reverse tcp:8000 tcp:8000`을 만든다.
4. Davi debug 앱, 별도 driver APK와 test APK를 설치한다.
5. Davi 접근성 서비스의 실제 연결 callback을 기다린다.
6. 선택한 test class를 실제 Gemini opt-in으로 실행한다.
7. full-journey이면 Davi 최신 화면 → Android 홈 → 외부 앱 package 전환을 같은 세션에서 수행한다.
8. 성공·실패와 무관하게 시나리오 정리를 수행한다.
9. 비식별 Markdown 보고서를 생성한다.
10. Davi 앱은 유지하고 launcher가 없는 driver/test helper 패키지를 제거한다.

실행기가 새로 만든 `adb reverse`만 종료 시 제거한다. 이미 있던 reverse는 그대로 둔다.
따라서 테스트 중에는 별도 helper APK가 필요하지만 사용자가 실행하거나 계속 설치해 둘 두 번째 앱은
아니다. 다음 실행 때 실행기가 다시 설치한다.

## masked / unmasked 비교 실행 (#203)

마스킹이 가이드 품질에 주는 영향을 보려면 같은 시나리오를 두 번 돌려 비교한다. **기본 실행은
언제나 마스킹이 켜져 있고**, 우회는 아래처럼 매번 명시적으로 켠다.

> ⚠️ **우회 실행은 원문 화면 label을 실제 Gemini까지 보낸다.** 합성 데이터만 넣은 전용 테스트
> 기기에서만 한다. 연락처·메시지·계정에 실제 개인정보가 있는 기기에서는 하지 않는다.
> 정책 원본은 `../SPEC.md` §3 "마스킹 우회 비교 실험".

기준선(마스킹 적용)을 먼저 남긴다.

```bash
./scripts/run-device-e2e.sh --scenario samsung-clock-create-alarm-full-journey
```

도그푸딩 화이트리스트에 기기 해시를 등록할 때도 이 기준선 APK에서 연결 로그의
`dogfoodDeviceId=` 값을 읽는다. 우회 APK는 raw 저장 경로를 닫기 위해 opt-in 빌드에서도
식별자를 보내지 않고 `dogfoodDeviceId=-`로 표시한다.

비교군은 **서버와 앱 두 겹을 모두** 우회해야 한다. 서버를 우회 설정으로 다시 띄우고,

```bash
DAVI_DISABLE_PRIVACY_MASKING=true .venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

실행기에 앱 우회 플래그를 준다.

```bash
./scripts/run-device-e2e.sh --scenario samsung-clock-create-alarm-full-journey --disable-privacy-masking
```

Windows PowerShell:

```powershell
.\scripts\run-device-e2e.ps1 -Scenario samsung-clock-create-alarm-full-journey -DisablePrivacyMasking
```

`--disable-privacy-masking`은 `-PdaviDisablePrivacyMasking=true`로 debug APK를 다시 설치한다.
이 플래그 없이 실행하면 debug 기본값이 마스킹 적용이므로 **직전 우회 APK가 다시 덮인다** —
비교군을 연속으로 돌릴 때도 매번 붙여야 한다.

여러 번 반복한다면 `local.properties`에 적어 두는 편이 편하다.

```properties
DAVI_DISABLE_PRIVACY_MASKING=true
```

이 경우 `--disable-privacy-masking` 없이 돌려도 우회 APK가 설치된다. **다만 그 머신은 그때부터
계속 우회 APK를 만든다** — 기준선을 다시 뜨려면 `-PdaviDisablePrivacyMasking=false`로 한 번만
덮거나(속성이 파일보다 우선한다) 실험이 끝난 뒤 파일에서 지운다.

### 두 겹의 상태를 각각 확인한다

한쪽만 우회한 실행도 유효한 비교지만, 어느 겹이 켜져 있었는지 모른 채 결과를 읽으면 안 된다.
실행기가 시작할 때 두 값을 각각 출력한다.

```text
개인정보 마스킹 — 앱 1차: 우회 / 서버 2차: 우회
```

직접 확인할 때는 이렇게 본다.

| 겹 | 확인 방법 | 우회 중일 때 |
|---|---|---|
| 앱 1차 | `adb logcat -s GuideSvc` | `privacyMasking=DISABLED` + 경고 한 줄 |
| 서버 2차 | `GET /health` | `"privacy_masking_disabled": true` |

### 우회 실행이 남기지 않는 것

- **상세 보고서를 만들지 않는다.** 어느 한 겹이라도 우회면 실행기가 `--no-detailed-report`와
  같은 상태로 내려간다. 상세 보고서는 화면 label과 모델 문장을 그대로 담기 때문이다.
- **도그푸딩 raw 봉투를 저장하지 않는다.** 앱 1차 우회는 `dogfoodDeviceId`를 요청에서 빼고,
  서버 2차 우회는 서버 저장 정책을 끈다. 따라서 어느 한 겹만 우회해도 원문 요청이 raw 저장소에
  들어가지 않는다.
- **원문 label 상세 logcat을 남기지 않는다.** 앱 1차 우회 중에는 E2E 세션 밖의 일반 debug
  안내에서도 상세 화면 로그를 끈다. 상태 확인용 `connected ... privacyMasking=DISABLED`는 남는다.
- 비교에 남기는 것은 기본 비식별 보고서의 **판정 · 실패 코드 · 관측한 안내 단계 · 비종료 이탈**
  같은 지표뿐이다. 원문 logcat이나 서버 로그를 PR·이슈·채팅에 붙이지 않는다.

## 보고서 위치

보고서는 시나리오별로 누적된다.

```text
build/reports/device-e2e/
└── samsung-clock-create-alarm-full-journey/
    ├── 20260813T120000123+0900.md
    ├── 20260813T123000456+0900.md
    └── latest.md

build/reports/device-e2e-private/
└── samsung-clock-create-alarm-full-journey/
    ├── 20260813T120000123+0900.md
    └── latest.md
```

타임스탬프 파일은 과거 실행 비교용으로 계속 남고, `latest.md`는 해당 시나리오의 가장 최근 결과를
가리키는 복사본이다. `build/`와 상세 보고서 경로는 Git에서 제외된다. 기본 비식별 보고서만 필요한
부분을 PR 설명이나 승인된 결과 저장소에 옮길 수 있다. `device-e2e-private` 파일은 마스킹된 label이라도
식별 가능한 앱 이름과 모델 문장이 남을 수 있으므로 공유하지 않는다.

## 보고서에서 확인할 내용

| 항목 | 의미 |
|---|---|
| 판정 | 외부 앱 목표 상태와 검증된 완료까지 도달했는지 |
| 실패 코드 | 원문 대신 저장하는 구조화 원인 |
| 관측한 안내 단계 | driver가 실제로 받은 Gemini 결정 수 |
| 비종료 이탈 | 예상 경로를 벗어났지만 남은 단계에서 계속 수행한 코드와 횟수 |
| 시작된 서버 요청 | Davi가 서버에 시작한 논리 요청 수 |
| 단계 trace | action, target bounds 존재 여부, 완료 검증 여부 |
| 경유한 package | 안내가 런처와 외부 앱 사이에서 이동한 순서 |
| 테스트 데이터 정리 | 성공·실패 뒤 테스트 전용 데이터 삭제 결과 |

상세 분석 보고서는 각 가이드 번호 아래에서 다음을 함께 보여 준다.

- 요청 시점의 package/page/scroll/IME 및 전달된 화면 요소 전체
- 그 요청에 실제 포함된 스텝 이력(`steps`) — 안내 문장·조작·대상 label·화면 요약·화면 변화 여부
- 응답의 model/verified/screen signature와 전체 `GuideAction` 필드
- 해석된 target bounds·role, 비종료 이탈과 실행/실패 결과

`step_limit`은 Gemini 할당량 제한이 아니다. 시나리오가 정한 최대 안내 단계 안에 목표를 완료하지
못했다는 품질 실패다. API rate/quota 문제는 서버 호출 실패나 HTTP 429 계열로 별도 나타난다.
키보드·화면 분류 불확실이나 예상 target 이탈은 즉시 실패시키지 않고 이 상한까지 회복 여부를 본다.

## 기록하지 않는 정보

기본 보고서와 콘솔의 비식별 trace에는 다음을 넣지 않는다.

- 화면 트리와 화면 label
- 안내 문장과 모델 원문 응답
- API 키와 접근 토큰
- 기기 serial과 개인 데이터

도그푸딩 빌드의 연결 로그에 가명 식별자가 있더라도 호스트 실행기가 `dogfoodDeviceId`와
`device_id` 값을 `[redacted]`로 바꾼 뒤 콘솔·임시 safe log에 기록한다.

기기 모델·Android 버전·모델명·Git commit은 재현에 필요한 비식별 실행 메타데이터로 기록한다.

상세 보고서는 원본 Accessibility 트리나 API 키·기기 serial을 넣지 않지만, 위 항목 중 앱이 전송한
1차 마스킹 화면 label과 파싱된 모델 문장을 의도적으로 포함한다. 실행 중간 JSONL은 보고서 생성 뒤
삭제되고 test APK 제거로 기기 파일도 함께 제거된다.

## 키 없이 확인할 회귀

실제 모델을 호출하지 않고 코드·보고서 생성기를 확인하려면 다음을 실행한다.

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
./gradlew :e2e-driver:assembleDebugAndroidTest :app:assembleDebug test
```

Windows에서는 `python`과 `gradlew.bat`을 사용한다.

```powershell
python -m unittest discover -s scripts/tests -p 'test_*.py'
.\gradlew.bat :e2e-driver:assembleDebugAndroidTest :app:assembleDebug test
```
