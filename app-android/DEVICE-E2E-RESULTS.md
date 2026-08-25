# 실제 Gemini 실기기 E2E 검증 결과

이 문서는 #124에서 삼성 시계 알람 시나리오용 UIAutomator driver를 구현하면서 실제 SM-S921N에서
확인한 결과를 기록한다. 실행 방법과 판정 기준은 [`DEVICE-E2E.md`](DEVICE-E2E.md)에 있다.

> 아래 실행은 #143 이전의 즉시 중단 기준으로 기록한 역사적 결과다. 현재는 `action_after_goal`,
> `unknown_clock_screen` 같은 회복 가능한 경로 이탈을 비종료 코드로 기록하고 최대 단계까지 속행한다.

## 검증 환경

| 항목 | 값 |
|---|---|
| 실행일 | 2026-08-13 (KST) |
| 기기 | Samsung SM-S921N / Android 15 |
| 대상 앱 | Samsung Clock 12.4.10.10 |
| 서버 | 로컬 개발 서버, `DAVI_USE_STUB_LLM=false` |
| 모델 | `gemini-3.5-flash-lite` |
| 시나리오 | Davi 최신 화면 → Android 홈 → 삼성 시계 → 테스트 알람 생성 후 자동 삭제 |

기기 serial, API 키, 화면 트리, 개인 알람 label, Gemini 응답 원문과 안내 문장은 기록하지 않았다.

## 구현 검증 결과

| 검증 항목 | 결과 | 확인 내용 |
|---|---|---|
| 앱·driver·test APK 빌드/설치 | 통과 | 제품 앱과 별도 instrumentation 프로세스로 실행 |
| 접근성 서비스 공존 | 통과 | 실행 중 Davi `Bound`, `Crashed services` 비어 있음 |
| 실제 Gemini 호출 | 통과 | stub이 아닌 모델 schema 요청과 HTTP 200 응답 확인 |
| 안내 기반 조작 | 통과 | 정답 selector가 아니라 Gemini action과 target bounds로 외부 앱 조작 |
| Davi → 홈 전환 | 통과 | 최신 Conversation 화면 확인 뒤 앱 프로세스가 안내 세션을 열고 홈 전환 |
| 앱 간 관통 | 통과 | 런처 탐색 뒤 Samsung Clock package로 이동하고 같은 안내 이력 유지 |
| 편집 화면 진입 | 통과 | 알람 목록에서 알람 편집 화면까지 실제 화면 전환 확인 |
| 시나리오 최종 완료 | **실패** | 저장 완료 뒤 모델이 추가 조작을 제시해 `action_after_goal`로 거부함 |
| 실패 시 데이터 정리 | 통과 | 실행 뒤 `DAVI_E2E_TEST` 알람이 남아 있지 않음을 별도 확인 |
| 로컬 상세 분석 보고서 | 통과 | 기본 생성, Git 제외 경로 누적, 명시적으로 끌 때만 생략 |
| API 키 없는 회귀 빌드 | 통과 | debug 앱, driver test APK와 JVM 테스트 컴파일 성공 |
| release 격리 | 통과 | release APK에 E2E receiver와 signature 권한이 없음을 확인 |

## 실제 모델 실행에서 발견한 결과

### 실행 1 — 문장과 구조화 action 불일치

초기 구현은 안내 문장에 구조화 action과 맞는 고정 한국어 동작 어휘가 없으면
`instruction_action_mismatch`로 중단했다. 이 판정은 자연어 표현의 다양성을 잘못 거부할 수 있어 리뷰
반영 과정에서 제거했다. 현재 driver는 구조화 action·target·시나리오 상태를 검사하고, 문장 의미 품질은
로컬 상세 보고서와 별도 eval judge에서 분석한다.

### 실행 2 — 단계 상한 도달

재실행에서는 알람 추가 대상을 선택해 편집 화면까지 진입했고 실제 Gemini 응답과 UIAutomator 조작이
이어졌다. 그러나 허용된 최대 18단계 안에 목표 시간·테스트 이름·저장·검증된 완료까지 도달하지 못해
`step_limit`으로 실패했다. 이 코드는 API 사용량 제한이 아니라 **시나리오 진행 단계 상한**이다.

### 실행 3 — 목표 미달 완료 응답 거부

리뷰 수정 후 재실행에서는 알람 추가, 시간 선택, 테스트 이름 입력, 저장까지 7개의 실제 Gemini 결정을
따라 진행했다. 저장 뒤 모델이 `done`을 반환했지만, 독립적인 Samsung Clock robot이 실제 저장 결과가
목표 시간과 일치하지 않음을 확인해 `done_before_goal`로 거부했다. 테스트 전용 알람 정리는 성공했다.

기본 활성화된 로컬 상세 보고서를 통해 요청마다 전달된 화면 요소·최근 안내·최근 action과 구조화 응답,
해결된 target, 실행 결과를 같은 가이드 번호에서 대조할 수 있었다. 이 과정에서 안내의 설명과 실제
`target_node_id`가 가리키는 시간 값이 어긋난 단계를 확인했다. 화면 label과 모델 문장 원문은 로컬
Git 제외 보고서에만 남기고 이 문서에는 옮기지 않았다.

### 실행 4 — Davi에서 시작하는 전체 여정

기본 시나리오를 Davi 최신 대화 화면에서 시작하도록 확장했다. 테스트 목표는 음성 인식 대신 debug
서명 채널로 주입했고, 앱 프로세스가 실제 제품 경로와 같은 Android 홈 intent를 실행했다. 이후
UIAutomator는 런처에서 검색을 거쳐 Samsung Clock으로 이동하고 알람 편집·테스트 이름 입력·저장까지
총 10개의 실제 Gemini 결정을 수행했다.

저장 뒤 테스트 알람이 이미 존재했지만 모델이 추가 tap을 제시해 `action_after_goal`로 실패했다.
정답 selector로 `done`을 대신 만들지 않았으며 테스트 전용 알람 정리는 성공했다. 이 실행으로 다음을
확인했다.

- 한 세션의 안내 이력이 런처 → Samsung Clock 전환 뒤에도 이어진다.
- 대상 없는 전역 `scroll`도 서버 계약에 맞게 전체 화면 제스처로 실행된다.
- 비식별 보고서가 단계별 package와 `경유한 package`를 남겨 앱 전환 지점을 보여 준다.
- 잘못된 런처 target은 개인 앱이 열리기 전에 구조화 실패로 차단할 수 있다.

따라서 현재 결론은 다음과 같다.

- 실제 Gemini를 포함한 닫힌 루프 E2E 인프라는 동작한다.
- 테스트가 현재 모델의 조기 완료, 목표 뒤 추가 동작과 단계 상한 도달을 실제 품질 실패로 검출한다.
- 삼성 시계 알람 생성 시나리오가 성공한다고 아직 주장할 수 없다.
- 테스트가 Gemini 결정을 정답 selector로 보정하지 않았기 때문에 이 실패는 그대로 보존해야 한다.

## 2026-08-24 · #316 목표 구체화 실기기 관통

SM-S921N(Android 15 / One UI 7.0)에 최신 main 기반 debug 앱과 app 계측 APK를 설치하고, 로컬
서버(`stub_llm=false`, OpenAI `gpt-5.6-luna`)로 `ClarificationFlowDeviceTest`의 두 live test를
실행했다. 둘 다 최종 확인 화면의 goal 조각까지 통과했다.

아래는 debug 앱과 app 계측 APK를 설치하고 로컬 8000 서버를 켠 뒤 실제로 쓴 실행 경계다.
`davi.liveGemini=true`를 빼면 두 live test가 assume skip되므로 명령에서 이 인자를 유지한다.
별도 worktree처럼 `local.properties`가 없는 곳은 에뮬레이터 기본값 `10.0.2.2`로 빌드되므로,
실기기 APK를 만들 때 `daviE2eServerUrl`도 명시한다.

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  -PdaviE2eServerUrl=http://127.0.0.1:8000
adb reverse tcp:8000 tcp:8000
adb shell am instrument -w -r \
  -e davi.liveGemini true \
  -e class com.davi.helper.ClarificationFlowDeviceTest \
  com.davi.helper.debug.test/androidx.test.runner.AndroidJUnitRunner
```

| eval 시나리오 | 실기기 입력 | 실제로 지난 경계 | 결과 |
|---|---|---|---|
| `e01` 사진 공유 | 첫 질문 계획 fixture + 보기 선택 | Android View click → `clarification_answers` 두 번째 서버 호출 → 앱 후보 판정 → Confirm | 통과 |
| `h07` 강수 확인 | 첫 질문 계획 fixture + 「직접 말하기」 + 성공 STT callback | Listening 화면 → freeform 답 → 두 번째 서버 호출 → 앱 후보 판정 → Confirm | 통과 |

첫 질문 계획은 모델의 비결정성을 섞지 않도록 eval 목표 조각으로 고정했다. 그 뒤의 보기 click,
직접 말하기 화면 전환, 앱의 로컬 답 누적, 실제 서버 최종화와 goal 렌더는 제품 경로다. 직접 말하기
테스트는 **성공한 STT callback부터** 관통하므로 마이크 음향 인식 품질을 통과했다고 주장하지 않는다.
음향 인식기는 #106의 별도 복구 범위다.

같은 날 `25c35c1`을 유선 SM-S921N에 다시 설치해 두 live test를 각각 단독 실행했고 둘 다
`OK (1 test)`였다. 첫 시도는 `local.properties` 없는 worktree의 `10.0.2.2` 기본값 때문에
서버에 도달하지 못해 실패했으며, 위 Gradle 속성으로 `BuildConfig.SERVER_URL`이
`http://127.0.0.1:8000`임을 확인한 뒤 재실행한 결과만 제품 관측으로 쓴다.

고정 계획을 택하기 전에 실제 첫 호출도 관측했다. `e01` 계열 입력은 세 번 모두 질문을 만들었지만
보기는 0개였고, `h07` 계열 입력은 지역이 빠졌는데도 즉시 `ready`였다. 이 값은 표본이 작아 품질
결론으로 쓰지 않고, `eval/pingpong`의 보기 선택률·goal 유효성 지표로 다시 재게 남긴다.

두 계측 실행은 발화·선택·goal 원문을 logcat에 쓰지 않았다. 서버 로그에도 요청 id, 단계
(`plan`/`finalize`), 답 개수, status와 지연만 남는 것을 확인했다.

## 2026-08-25 · #424 완료 신뢰도 3상태 실기기 검증

SM-S921N(Android 15 / One UI 7.0), 기본 개인정보 마스킹, 로컬 실제 모델 서버
(`stub_llm=false`, OpenAI `gpt-5.6-luna`)에서
`completion-verdict-device-smoke`와 같은 테스트 클래스를 실행했다(실행 당시 registry id는
`completion-verdict-verified-smoke`). 삼성 시계 알람 목록을 이미 도달한 목표 화면으로
두어 기기 데이터를 바꾸지 않았고, 첫 응답 뒤 완료 확인의 「아직」 버튼까지 UIAutomator가 눌렀다.

| 단계 | action | completion verdict | 앱 동작 | 결과 |
|---:|---|---|---|---|
| 1 | `done` | `verified` | 확신 있는 완료 확인 표시 | 통과 |
| 2 | `done` | `contradicted` | 완료 확인 없이 고정 복구 문구 후 재탐색 | 통과 |

비식별 `GuideSvc` 로그에서 `done verified, asking user` → `confirm rejected` →
`done contradicted, retrying` 순서를 확인했다. E2E 기본 보고서에도 같은 두 verdict가 기록됐으며,
화면 label·안내문·목표 원문·API 키·기기 serial은 로그와 보고서에 넣지 않았다. 테스트 전용 데이터는
생성하지 않았고 cleanup은 성공했다.

같은 날 기존 `samsung-clock-create-alarm-focused`도 실행했다. 네 개의 실제 모델 응답이 모두
`inconclusive`인 일반 조작으로 정상 표시됐지만, 네 번째 scroll 뒤 화면이 변하지 않아 5단계
`decision_timeout`으로 시나리오 자체는 실패했다. 이는 3상태 wire 관측에는 포함하되 알람 생성 품질
통과로 주장하지 않는다.

## 2026-08-25 · #209 관측 실패 분류 경계

SM-S921N(Android 15 / One UI 7.0)에서 debug driver와 test APK를 설치하고
`GuideDecisionQueueDiagnosticTest`를 실행했다. 화면·목표·서버 원문 없이 서명 보호 broadcast로
root와 서버 상태를 넣은 뒤 결정 timeout의 구조화 실패 코드를 확인했다.

| 입력한 진단 순서 | timeout 결과 | 결과 |
|---|---|---|
| `root_unavailable` | `root_unavailable` | 통과 |
| `root_recovered` → `server_error` | `server_error` | 통과 |
| `request_started` | `decision_timeout` | 통과 |
| `screen_stuck` | `screen_stuck` | JVM 통과 · 실기기 재실행 보류 |
| `request_started` → `screen_unreadable` | `screen_unreadable` | JVM 통과 · 실기기 재실행 보류 |

세 경로와 `target_node_id_mismatch`의 signed decision 전달을 두 계측 테스트에서 실행해
`OK (2 tests)`를 확인했다. transient root 복구와 다음 서버 요청은
오래된 진단을 지우므로 이전 오류가 다음 단계의 원인으로 남지 않는다. `target_node_id` 불일치와
UIAutomator 비보정 경계는 기기 없이 도는 `GuidedObservationBoundaryTest` 8건으로 고정했다.
`screen_stuck`은 안내를 실행했지만 화면이 바뀌지 않은 모델 품질 실패로, `screen_unreadable`은
접근성 관측 인프라 실패로 공유 보고서에서 갈라진다. 문자 전송 하드 경계 4코드는 모두
`safety_boundary_failure`로 묶여 전송 차단 실행이 미분류로 떨어지지 않는다.
두 화면 진단을 추가한 뒤 driver/test APK 컴파일과 JVM 상태 전이 테스트는 통과했지만, 연결 기기가
없어 새 두 행의 서명 broadcast 재실행은 보류했다. 기존 root·server·target 경계의 `OK (2 tests)`
실측을 새 화면 진단까지 통과한 것으로 넓혀 쓰지 않는다.

리뷰 수정 커밋 `bfea9fb`으로 같은 기기에서 `samsung-clock-create-alarm-full-journey`도 다시 실행했다.
기본 개인정보 마스킹과 실제 `gemini-3.5-flash-lite`를 사용했고, 런처에서 삼성 시계로 이동해
14개의 HTTP 200 결정을 수행했다. 14단계 `done`은 서버가 `verified`로 판정했으며 앱이 bounds를
publish하지 않아 `target=false`였지만, bounds를 쓰지 않는 action이므로
`target_node_id_mismatch` 오탐 없이 완료 분기까지 도달했다. 독립 robot이 최종 알람 상태 불일치를
확인해 실행 결과는 의도대로 `done_before_goal` / `model_quality_failure`였고 테스트 데이터 정리는
성공했다.

아래는 화면·목표·안내 원문과 request id를 제외한 같은 실행의 `GuideSvc`·`DaviE2e` 발췌다.

```text
GuideSvc: event=request_started attempt=1
GuideSvc: event=request_finished attempt=1 outcome=success status_code=200
GuideSvc: action=done target=- direction=- history=10
GuideSvc: done verified, asking user
DaviE2e: step=14 action=done package=com.sec.android.app.clockpackage target=false verified=true verdict=verified
DaviE2e: cleanup=success
```

첫 준비 실행은 로컬 `.env`의 `DAVI_GEMINI_THINKING_BUDGET=0` 때문에 모델 요청이 HTTP 400으로
거부돼 `server_error` / `infra_failure`로 종료됐다. 민감정보 없는 최소 실호출에서 같은 모델은
thinking budget 0일 때 400, 800일 때 200임을 다시 확인했고, 위 최종 실행은 프로세스 환경에서만
800을 명시했다. 이 준비 실패와 실제 모델 품질 실패가 공유 보고서에서 서로 다른 범주로 보이는 것도
함께 확인했다.

## 이후 실행 결과 확인

OS에 맞는 `run-device-e2e.sh` 또는 `run-device-e2e.ps1`을 실행하면 매번 타임스탬프 문서가
누적되고 시나리오별 최신 복사본도 갱신된다.

```text
app-android/build/reports/device-e2e/samsung-clock-create-alarm-full-journey/<timestamp>.md
app-android/build/reports/device-e2e/samsung-clock-create-alarm-full-journey/latest.md
app-android/build/reports/device-e2e-private/samsung-clock-create-alarm-full-journey/<timestamp>.md
app-android/build/reports/device-e2e-private/samsung-clock-create-alarm-full-journey/latest.md
```

기본 비식별 보고서에는 다음 내용만 포함된다.

- 통과/실패와 구조화 실패 코드
- 실행 시각, 모델명, 기기 모델·Android 버전, Git commit
- 관측한 단계 수와 서버 요청 시작 수
- 단계별 package, `action`, target bounds 존재 여부, 완료 검증 여부, 비종료 이탈과 경유 package 순서
- 테스트 전용 데이터 정리 성공 여부

별도의 상세 분석 보고서는 기본으로 함께 생성되며, 각 가이드의 앱 전송 요청 스냅샷과 구조화 응답을
같은 순서로 보여 준다. 원본 Accessibility 트리, API 키, 기기 serial, `device_id`는 포함하지 않지만,
1차 마스킹 뒤 화면 label과 모델 문장이 있으므로 로컬 민감 산출물로 취급한다. `--no-detailed-report`
또는 `-NoDetailedReport`로 명시적으로 끌 수 있다.

두 경로 모두 `build/` 아래이고 상세 경로는 루트 `.gitignore`에도 명시되어 자동 커밋되지 않는다.
특히 `device-e2e-private` 보고서는 PR·이슈·채팅에 첨부하지 않는다.
