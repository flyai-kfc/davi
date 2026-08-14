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
2. 대상 기기와 접근성·오버레이 권한을 확인한다.
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
- 그 요청에 실제 포함된 `recent_instructions`와 구조화 `recent_actions`
- 응답의 model/verified/screen signature와 전체 `GuideAction` 필드
- 해석된 target bounds·role, 비종료 이탈과 실행/실패 결과

`step_limit`은 Gemini 할당량 제한이 아니다. 시나리오가 정한 최대 안내 단계 안에 목표를 완료하지
못했다는 품질 실패다. API rate/quota 문제는 서버 호출 실패나 HTTP 429 계열로 별도 나타난다.
키보드·화면 분류 불확실이나 예상 target 이탈은 즉시 실패시키지 않고 이 상한까지 회복 여부를 본다.
목표 미달 또는 미검증 `done`도 비종료 이탈로 세고 같은 목표를 현재 화면에서 재개한다.

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
