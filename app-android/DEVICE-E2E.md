# 실제 Gemini 실기기 안내 E2E

`e2e-driver/src/androidTest/java/com/davi/helper/e2e/`는 Davi에서 안내를 시작해 Android 홈과
하나 이상의 외부 앱을 오가며 목표를 끝내는 닫힌 루프를 UIAutomator로 검증한다. 기본 시나리오는
SM-S921N의 Davi 최신 대화 화면에서 홈으로 나가 삼성 시계를 찾고 테스트 전용 알람을 만든 뒤,
성공·실패와 무관하게 자동 삭제한다(#124).

처음 사용하는 개발자는 다음 순서로 읽는다.

1. [`DEVICE-E2E-SETUP.md`](DEVICE-E2E-SETUP.md) — OS별 환경과 실기기 권한
2. [`DEVICE-E2E-RUN.md`](DEVICE-E2E-RUN.md) — CLI 시나리오 선택과 누적 보고서
3. [`DEVICE-E2E-SCENARIOS.md`](DEVICE-E2E-SCENARIOS.md) — 새 앱/흐름 추가
4. [`DEVICE-E2E-RESULTS.md`](DEVICE-E2E-RESULTS.md) — 구현 시 실제 Gemini 검증 결과

## 무엇을 검증하는가

테스트는 삼성 시계의 정답 버튼을 미리 찾아 대신 누르지 않는다.

1. driver가 Davi의 최신 대화 화면이 실제로 준비됐는지 확인한다.
2. debug 서명 채널로 테스트 목표를 넣고, 앱 프로세스가 안내 세션을 시작해 Android 홈으로 나간다.
3. 런처와 이후 외부 앱 화면을 Davi 접근성 서비스가 직렬화한다.
4. 개발 서버가 실제 Gemini로 `GuideAction`을 만든다.
5. 앱이 `target_node_id`를 현재 화면 요소와 연결해 오버레이를 그린다.
6. 별도 driver APK가 동일한 action과 target bounds를 받고 UIAutomator로 수행한다. 대상 없는 scroll은
   사용자의 전역 쓸기처럼 전체 화면에서 수행한다.
7. package가 바뀌어도 같은 세션과 이력을 이어가며, 최종 알람 시간·이름과 검증된 `done`을 확인한다.

따라서 Gemini가 잘못된 대상을 고르면 UIAutomator가 정답 selector로 보정하지 않는다. 이 실행은
전용 테스트 기기를 전제로 하므로 예상과 다른 앱·화면·target, 키보드로 일부 노드가 가려진 상태는
비종료 이탈로 기록하고 최대 단계 안에서 계속 수행한다. 설정 앱 진입과 메시지 전송·결제·게시·통화처럼
기기 밖에 영향을 미치는 조작만 실행 전 하드 차단한다. 성공은 최종 알람 시간·이름을 독립 확인한다.

이 full-journey는 최신 Davi 화면과 홈 전환부터 검증하지만 음성 인식의 비결정성을 섞지 않기 위해
목표 문장은 debug 서명 채널로 주입한다. 음성 인식·후속 질문·사용자의 최종 확인 UI 품질은 별도
대화 테스트 범위다. `samsung-clock-create-alarm-focused`는 알람 목록부터 시작하는 빠른 진단용이다.

안내 문장은 exact-match하거나 특정 한국어 동사 목록으로 제한하지 않는다. 대신 비어 있지 않은
근거·기대 결과, 현재 화면에 존재하는 target과 bounds, 실제 화면 진행, 조기 완료 여부와 최종 목표
달성으로 실행 품질을 검사한다. 문장의 의미 품질은 상세 분석 보고서와 별도 eval judge에서 본다.
화면·안내 원문은 기본 테스트 결과나 logcat에 출력하지 않는다.
실패 진단에는 단계, 구조화 action 종류, target bounds 존재 여부, 검증 여부와 비종료 이탈 코드만 남긴다.

## 실행 전 준비

- USB 또는 무선 adb로 삼성 테스트 기기를 연결한다.
- 기기에서 Davi 접근성 서비스와 다른 앱 위에 표시 권한을 허용한다.
- Mac의 `server/.env`에 실제 서버용 `GEMINI_API_KEY`와 모델을 설정한다.
- 서버는 `DAVI_USE_STUB_LLM=false`로 `127.0.0.1:8000`에서 실행한다.
- 서버에 `DAVI_DEV_ACCESS_TOKEN`이 있다면 같은 값을 `DAVI_DEBUG_ACCESS_TOKEN` 환경변수로
  내보낸 뒤 앱을 빌드한다. 값은 앱 소스나 이 문서에 기록하지 않는다.

예시:

```bash
cd server
DAVI_USE_STUB_LLM=false .venv/bin/python -m uvicorn app.main:app \
  --host 127.0.0.1 --port 8000
```

다른 터미널에서:

```bash
cd app-android
./scripts/run-device-e2e.sh --scenario samsung-clock-create-alarm-full-journey
```

스크립트는 `/health`의 `stub_llm=false`를 먼저 확인하고, `adb reverse tcp:8000 tcp:8000`,
서명 권한을 선언하는 debug 앱을 먼저 설치하고 driver/test APK를 설치한 뒤, 권한 사전 검사,
opt-in instrumentation 실행과 비식별 `GuideSvc`
로그 수집을 차례로 수행한다. 앱 APK 교체가 접근성 서비스를 crashed 상태로 남기지 않도록 기존
다른 접근성 항목은 그대로 둔 채 Davi 항목만 설치 전에 내렸다가 복구한다. 스크립트가 새로 만든
reverse만 종료 시 제거한다. 앱에는 Gemini API 키가 들어가지 않고 모든 모델 호출은 Mac의 서버가
수행한다.
처음부터 Davi 접근성이 꺼져 있으면 스크립트가 임의로 허가하지 않고 실행 전에 실패한다.

Android의 `dumpsys accessibility`에 보이는 Bound 상태는 앱의 실제 `onServiceConnected()`보다
먼저 바뀔 수 있다. 스크립트는 비식별 `connected` 로그를 최대 30초 기다린 뒤에만 instrumentation을
시작한다. 이 시간 안에 연결되지 않으면 모델 호출 없이 실패한다.

instrumentation을 제품 앱과 분리한 이유는 Android가 instrumentation 대상 프로세스를 시작하면서
같은 패키지의 접근성 서비스를 중단하기 때문이다. debug 앱만 세션 시작·결정 전달 receiver를
포함하며, debug 앱·driver·test APK는 같은 debug key의 `signature` 권한으로 통신한다. release 구현은
no-op이고 receiver와 권한 선언도 없다. 채널에는 구조화 action과 target 정보 외에 앱이 서버로 보낸
1차 마스킹 완료 요청 snapshot 및 파싱된 응답을 압축해 전달한다. 원본 Accessibility 트리는 전달하지
않지만 요청의 화면 label과 모델 문장(안내·기대 결과·근거)은 포함된다. 값은 logcat에 출력하지 않는다.

UIAutomator는 화면 조회·조작 때 기본 `UiAutomation`을 반복해서 요청한다. test APK의
`DaviE2eRunner`는 모든 요청에 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`를 합쳐 Davi 접근성
서비스를 유지한다. 테스트도 서비스가 Bound이고 Crashed가 아님을 최대 15초 확인한 뒤 시나리오를
준비한다.

실제 Gemini 호출은 비용·비결정성이 있으므로 `-e davi.liveGemini true` 없이는 테스트가 skip된다.
일반 CI는 `assembleDebugAndroidTest`로 라이브러리와 test APK만 키 없이 컴파일한다.

## 결과 문서

구현 시 실제 기기 검증 결과와 해석은 [`DEVICE-E2E-RESULTS.md`](DEVICE-E2E-RESULTS.md)에 있다.
실행기는 성공·실패와 무관하게 시나리오별 타임스탬프 보고서를 누적하고 `latest.md`도 갱신한다.
기본 실행은 별도의 로컬 상세 분석 보고서도 함께 만든다.

```text
build/reports/device-e2e/<scenario>/<timestamp>.md
build/reports/device-e2e/<scenario>/latest.md
build/reports/device-e2e-private/<scenario>/<timestamp>.md
build/reports/device-e2e-private/<scenario>/latest.md
```

기본 보고서는 구조화 action과 판정 메타데이터만 사용한다. 상세 보고서는 각 가이드의 요청 당시
`Screen.elements`, 스텝 이력(`steps`)과 응답의 `expected_result`, `screen_summary`, `evidence`,
`instruction`, `action_type`, `direction`, `target_node_id`를 함께 보여 준다. 상세 파일은 마스킹 뒤에도
개인 앱 이름 등이 남을 수 있는 **로컬 민감 산출물**이며 `.gitignore` 대상이다. PR·이슈에 첨부하지
않는다. 직접 생성하지 않으려면 `--no-detailed-report` 또는 `-NoDetailedReport`를 사용한다.
앱 1차 또는 서버 2차 마스킹 중 하나라도 우회 중이면 실행기가 상세 보고서를 자동으로
비활성화하며, 기기에도 원문 label을 포함하는 상세 화면 로그를 남기지 않는다.

두 렌더러는 다음 명령으로 API 키 없이 검증한다.

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
```

## 시나리오 추가

새 시나리오는 다음 네 부분을 분리한다.

- `GuidedScenario<State>`: 시작 모드, 목표, 최대 단계, 공통·시나리오 전용 하드 경계, package별 이탈, 입력값, 최종 검증, 정리 정책
- `GuidedScenarioRunner`: 결정 대기, 공통 품질 검사, 세션 수명, 항상 실행되는 정리
- `GuidedActionExecutor`: Gemini target bounds를 실제 손동작으로 변환
- 앱별 Robot: 안내 흐름이 아닌 준비·독립 검증·테스트 데이터 삭제용 selector

추가 절차:

1. 앱별 Robot으로 테스트 전용 데이터와 안전한 초기 상태를 만든다.
2. 전체 여정은 `start = DAVI_TO_ANDROID_HOME`, 앱 내부 진단은 `PREPARED_SCREEN`을 선택한다.
3. `GuidedScenario`에서 목표와 TYPE 입력값을 정의한다.
4. `hardBoundaries`에 외부 부작용을 추가하고 `assessBeforeExecute`를 package별로 나눠 회복 가능한 이탈을 정의한다.
5. `isGoalSatisfied`는 모델의 `done`과 독립된 외부 앱 UI로 판정한다.
6. `cleanup`은 테스트가 만든 고유 데이터만 선택하고, 준비 단계에서도 이전 실패 부산물을 지운다.
7. `@LargeTest` 클래스에서 `GuidedScenarioRunner`로 시나리오를 실행한다.

시나리오·실패 메시지·logcat에는 원본 화면 트리, 개인 label, 목표 원문, 모델 원문 응답, API 키를
저장하거나 출력하지 않는다. 상세 보고서도 원본 트리가 아니라 앱이 실제로 전송한 1차 마스킹
요청과 파싱된 구조화 응답만 쓴다. 앱별 resource ID와 문구 selector는 준비·검증·정리에만 사용할 수
있으며 본 안내 동작을 정답으로 덮어쓰는 데 사용하지 않는다.
