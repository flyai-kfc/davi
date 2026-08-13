# 실제 Gemini 실기기 안내 E2E

`e2e-driver/src/androidTest/java/com/davi/helper/e2e/`는 Davi가 다른 앱 위에서 안내하는 닫힌 루프를
UIAutomator로 검증한다. 첫 시나리오는 SM-S921N의 한국어 삼성 시계에서 테스트 전용 알람을
만들고, 결과를 확인한 뒤 성공·실패와 무관하게 자동 삭제한다(#124).

## 무엇을 검증하는가

테스트는 삼성 시계의 정답 버튼을 미리 찾아 대신 누르지 않는다.

1. 실제 삼성 시계 화면을 Davi 접근성 서비스가 직렬화한다.
2. 개발 서버가 실제 Gemini로 `GuideAction`을 만든다.
3. 앱이 `target_node_id`를 현재 화면 요소와 연결해 오버레이를 그린다.
4. 별도 driver APK가 서명 권한으로 보호된 debug 전용 채널에서 동일한 action과 target bounds를 받는다.
5. UIAutomator가 그 bounds를 탭·스크롤·입력한다.
6. 바뀐 실제 화면을 다시 수집하고, 최종 알람 시간·이름과 검증된 `done`을 확인한다.

따라서 Gemini가 잘못된 대상을 고르면 UIAutomator가 정답 selector로 보정하지 않는다. 삼성 시계
시나리오는 기존 개인 알람을 보호하기 위해 알람 추가, 시간 picker, 테스트 이름, 저장만 허용하며
그 밖의 target은 조작 전에 안전한 오류 코드로 실패시킨다.

안내 문장은 exact-match하지 않는다. 대신 비어 있지 않은 근거·기대 결과, action과 맞는 한국어
동작 표현, 현재 화면에 존재하는 target과 bounds, 실제 화면 진행, 조기 완료 여부와 최종 목표
달성으로 품질을 검사한다. 화면·안내 원문은 테스트 결과나 logcat에 출력하지 않는다.
실패 진단에는 단계, 구조화 action 종류, target bounds 존재 여부, 검증 여부만 남긴다.

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
./scripts/run-samsung-clock-e2e.sh
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
포함하며, debug 앱·driver·test APK는 같은 debug key의 `signature` 권한으로 통신한다. release 구현은 no-op이고 receiver와
권한 선언도 없다. 채널에는 구조화 action, target bounds와 품질 판정 필드만 전달하며 화면 트리나
화면 label은 전달하지 않는다.

UIAutomator는 화면 조회·조작 때 기본 `UiAutomation`을 반복해서 요청한다. test APK의
`DaviE2eRunner`는 모든 요청에 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`를 합쳐 Davi 접근성
서비스를 유지한다. 테스트도 서비스가 Bound이고 Crashed가 아님을 최대 15초 확인한 뒤 시나리오를
준비한다.

실제 Gemini 호출은 비용·비결정성이 있으므로 `-e davi.liveGemini true` 없이는 테스트가 skip된다.
일반 CI는 `assembleDebugAndroidTest`로 라이브러리와 test APK만 키 없이 컴파일한다.

## 시나리오 추가

새 시나리오는 다음 네 부분을 분리한다.

- `GuidedScenario<State>`: 목표, 최대 단계, 입력값, 최종 검증, 정리 정책
- `GuidedScenarioRunner`: 결정 대기, 공통 품질 검사, 세션 수명, 항상 실행되는 정리
- `GuidedActionExecutor`: Gemini target bounds를 실제 손동작으로 변환
- 앱별 Robot: 안내 흐름이 아닌 준비·독립 검증·테스트 데이터 삭제용 selector

추가 절차:

1. 앱별 Robot으로 테스트 전용 데이터와 안전한 초기 상태를 만든다.
2. `GuidedScenario`에서 목표와 TYPE 입력값을 정의한다.
3. `validateBeforeExecute`에 개인 데이터 보호를 위한 최소 허용 target을 정의한다.
4. `isGoalSatisfied`는 모델의 `done`과 독립된 외부 앱 UI로 판정한다.
5. `cleanup`은 테스트가 만든 고유 데이터만 선택하고, 준비 단계에서도 이전 실패 부산물을 지운다.
6. `@LargeTest` 클래스에서 `GuidedScenarioRunner`로 시나리오를 실행한다.

시나리오나 실패 메시지에 원본 화면 트리, 개인 label, 목표 원문, 모델 원문 응답, API 키를 저장하거나
출력하지 않는다. 앱별 resource ID와 문구 selector는 준비·검증·정리에만 사용할 수 있으며 본 안내
동작을 정답으로 덮어쓰는 데 사용하지 않는다.
