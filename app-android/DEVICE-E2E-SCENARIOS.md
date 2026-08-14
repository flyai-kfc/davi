# 실기기 E2E 시나리오 추가 가이드

새 시나리오는 “미리 정한 정답 버튼을 누르는 자동화”가 아니라 **Davi가 실제 Gemini로 표시한 다음
행동을 UIAutomator가 그대로 수행하는 관통 테스트**여야 한다. 기존 구조와 실행 원리는
[`DEVICE-E2E.md`](DEVICE-E2E.md)에 설명돼 있다.

## 파일 구성

일반적으로 다음 세 파일을 `e2e-driver/src/androidTest/java/com/davi/helper/e2e/`에 추가한다.

| 파일 | 책임 |
|---|---|
| `<App>Robot.kt` | 초기화, 화면·대상 관측, 독립 최종 검증, 테스트 데이터 정리 |
| `<Scenario>.kt` | 목표·단계 상한·이탈/하드 경계·TYPE 입력값·완료 조건 |
| `<Scenario>GeminiTest.kt` | `GuidedScenarioRunner`에 시나리오 연결 |

공통 실행기는 수정하지 않는다. `GuidedActionExecutor`가 Gemini의 구조화 action과 target bounds를
사용해 탭·스크롤·입력을 수행한다.

## 1. Robot 작성

Robot의 selector는 다음에만 사용한다.

- 테스트 전 안전한 시작 화면 준비
- 기존 개인 데이터와 테스트 데이터 구분
- Gemini가 고른 bounds의 대상 종류와 예상 경로 이탈 판정
- 모델의 `done`과 독립된 최종 상태 확인
- 성공·실패와 무관한 테스트 데이터 삭제

Robot selector로 다음 정답 대상을 찾아 Gemini 선택을 바꾸거나 보정하면 안 된다. 전용 테스트 기기의
화면·대상 이탈은 비종료 코드로 남기고 실제 결정을 계속 수행한다. 설정 앱 진입이나 메시지 전송·결제·
게시·통화처럼 기기 밖에 영향을 미치는 조작만 시나리오의 하드 경계로 실행 전에 차단한다.

## 2. `GuidedScenario<State>` 구현

필수 메서드의 의미는 다음과 같다.

| 메서드 | 구현 기준 |
|---|---|
| `start` | 전체 여정은 `DAVI_TO_ANDROID_HOME`, 특정 화면 진단은 `PREPARED_SCREEN` |
| `prepare` | 이전 테스트 부산물을 정리하고 안전한 초기 상태 수집 |
| `goal` | 실제 사용자가 요청할 목표. 테스트 전용 데이터만 포함 |
| `assessBeforeExecute` | 현재 package·화면·action·target을 비종료 이탈과 하드 경계로 분류 |
| `textFor` | TYPE에 사용할 고정 테스트 값 제공. 모델 문장을 파싱하지 않음 |
| `isGoalSatisfied` | 외부 앱 실제 UI로 목표를 독립 확인 |
| `cleanup` | `state=null`인 준비 실패까지 고려해 테스트 데이터만 삭제 |

`maxSteps`는 성공에 필요한 합리적 상한으로 둔다. 상한을 크게 올려 모델 반복을 성공처럼 숨기지 않는다.

여러 앱을 오가는 시나리오는 `assessBeforeExecute`를 `decision.screenPackage`별로 분기한다. 예상과 다른
앱·화면·target은 `deviationCodes`로 반환해 최대 단계 안에서 회복 가능성을 계속 측정한다. 설정 앱
아이콘처럼 실행 뒤 package 검사로는 늦는 하드 경계는 현재 요청 snapshot의 `target_node_id`와 label로
탭 전에 차단한다. 기기 밖 부작용이 있는 시나리오는 전송·결제·게시 같은 최종 action을 같은 방식으로
`hardFailureCode`로 반환한다.

## 3. test class 연결

```kotlin
@RunWith(AndroidJUnit4::class)
@LargeTest
class ExampleGeminiTest {
    private lateinit var device: UiDevice

    @Before
    fun prepareEnvironment() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        LiveGeminiTestEnvironment.requireReady()
    }

    @Test
    fun followsGeminiGuidance() {
        GuidedScenarioRunner(device).run(ExampleScenario())
    }
}
```

`UiDevice` 초기화 뒤 `requireReady()`를 호출하는 순서를 유지한다. custom runner가 UIAutomator의 모든
`UiAutomation` 요청에 접근성 서비스 억제 금지 플래그를 적용한다.

## 4. registry 등록

`device-e2e-scenarios.json`의 `scenarios`에 한 항목만 추가한다. macOS/Linux와 Windows 실행기가 이
파일을 함께 읽는다.

```json
{
  "id": "example-flow",
  "test_class": "com.davi.helper.e2e.ExampleGeminiTest",
  "description": "테스트가 검증하는 사용자 흐름을 한 문장으로 설명한다.",
  "requirements": "검증한 기기·앱 버전·언어 조건"
}
```

`id`는 소문자, 숫자, 하이픈만 사용하고 중복시키지 않는다. 한 test class에는 한 시나리오만 둔다.
그래야 실행별 보고서의 단계와 정리 결과가 섞이지 않는다.

## 5. 개인정보와 실패 출력

- 고정 테스트 이름만 사용하고 개인 label을 assertion에 넣지 않는다.
- 원본 화면 트리, 안내 문장, 목표 원문, 모델 원문을 logcat이나 실패 메시지에 출력하지 않는다.
- 비종료 이탈은 `GuidedDecisionAssessment(deviationCodes=...)`, 하드 실패는
  `hardFailureCode` 또는 `GuidedScenarioFailure("safe_code", step)`처럼 구조화 코드로 남긴다.
- API 키는 서버에만 두며 Android fixture·manifest·BuildConfig에 넣지 않는다.
- `cleanup` 실패도 원문 대신 구조화 코드로 표현한다.

기본으로 생성되는 `build/reports/device-e2e-private/` 상세 보고서는 예외적으로 앱이 서버에 보낸
1차 마스킹 요청과 파싱된 구조화 응답을 단계별로 보존한다. 시나리오 구현은 이 파일을 assertion이나
로그 입력으로 사용하지 않으며, 개발자는 저장소·PR·이슈에 공유하지 않는다.

## 6. 로컬 검증 순서

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
./gradlew :e2e-driver:assembleDebugAndroidTest :app:assembleDebug test
./scripts/run-device-e2e.sh --list-scenarios
./scripts/run-device-e2e.sh --scenario example-flow
```

Windows에서는 다음과 같다.

```powershell
python -m unittest discover -s scripts/tests -p 'test_*.py'
.\gradlew.bat :e2e-driver:assembleDebugAndroidTest :app:assembleDebug test
.\scripts\run-device-e2e.ps1 -ListScenarios
.\scripts\run-device-e2e.ps1 -Scenario example-flow
```

기본 결과는 `build/reports/device-e2e/example-flow/`, 로컬 상세 결과는
`build/reports/device-e2e-private/example-flow/`에 타임스탬프별로 누적된다.

## 리뷰 체크리스트

- [ ] Gemini action/target을 정답 selector로 덮어쓰지 않는다.
- [ ] 전용 기기에서 회복 가능한 화면·대상 이탈은 비종료 코드로 기록하고 속행한다.
- [ ] 설정 앱 및 기기 밖 부작용만 하드 경계로 실행 전에 차단한다.
- [ ] full-journey가 Davi → 홈 → 모든 경유 package를 같은 세션에서 검증한다.
- [ ] 완료를 외부 앱 상태로 독립 검증한다.
- [ ] 성공·실패·준비 실패 모두 테스트 데이터를 정리한다.
- [ ] logcat·실패 메시지·기본 보고서에 화면·안내·개인 원문이 없다.
- [ ] 상세 보고서는 Git 제외 경로에만 생성되고 원본 Accessibility 트리·키·serial이 없다.
- [ ] registry ID가 고유하고 test class가 실제로 존재한다.
- [ ] 키 없는 빌드와 보고서 렌더러 테스트가 통과한다.
