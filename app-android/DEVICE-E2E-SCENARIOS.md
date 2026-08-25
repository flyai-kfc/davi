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
| `hardBoundaries` | runner의 공통 설정 경계 뒤에 적용할 시나리오 전용 외부 부작용 경계를 선언 |
| `assessBeforeExecute` | 현재 package·화면·action·target의 비종료 이탈을 분류 |
| `textFor` | TYPE에 사용할 고정 테스트 값 제공. 모델 문장을 파싱하지 않음 |
| `isGoalSatisfied` | 외부 앱 실제 UI로 목표를 독립 확인 |
| `cleanup` | `state=null`인 준비 실패까지 고려해 테스트 데이터만 삭제 |

`maxSteps`는 성공에 필요한 합리적 상한으로 둔다. 상한을 크게 올려 모델 반복을 성공처럼 숨기지 않는다.

여러 앱을 오가는 시나리오는 `assessBeforeExecute`를 `decision.screenPackage`별로 분기한다. 예상과 다른
앱·화면·target은 `deviationCodes`로 반환해 최대 단계 안에서 회복 가능성을 계속 측정한다. 설정 package와
설정 target은 모든 시나리오의 기본 `hardBoundaries`에서 탭 전에 차단한다. 기기 밖 부작용이 있는
시나리오는 package 범위와 정확한 target label을 함께 고정한 경계를 추가한다.

```kotlin
override val hardBoundaries = listOf(
    GuidedHardBoundaries.blockedTapTargets(
        failureCode = "presend_send_blocked",
        packages = setOf(messagesPackage),
        labels = setOf("전송", "Send"),
    ),
)
```

### 문자 전송처럼 되돌릴 수 없는 동작 (#134)

**label 일치만으로는 부족하다.** 모델이 고른 대상의 label이 "전송"이 아니어도 그 대상의 **좌표가
전송 버튼 위**일 수 있고, 삼성 NumberPicker의 선택된 칸처럼 **label이 빈 문자열로 직렬화**되는
요소도 실측으로 확인됐다. 그래서 문자 앱에는 좌표까지 보는 경계를 쓴다.

```kotlin
override val hardBoundaries
    get() = listOf(
        MessagingSendGuard.sendControlBoundary(
            // composer에 들어온 뒤부터는 전송 버튼이 보여야 한다.
            sendControlRequired = { stage == Stage.COMPOSER },
        ),
    )
```

실행기가 실제로 누를 좌표(대상 bounds의 중심)가 전송 컨트롤 사각형 + 여백 안에 들어가면 막는다.
판정 규칙은 `MessagingSendSafety`에 순수 함수로 있고 CI의 JVM 테스트가 매번 확인한다.

**`TAP`과 `TYPE`을 모두 검사한다.** `GuidedActionExecutor.type()`이 텍스트를 넣기 전에 `tap()`을
불러 대상 중심을 실제로 누르기 때문이다. `SCROLL`은 `device.swipe`라 click을 주입하지 않아 제외한다.

| 실패 코드 | 언제 |
|---|---|
| `sms_send_target_blocked` | 모델이 전송 컨트롤을 직접 가리켰다 |
| `sms_send_proximity_blocked` | 누를 좌표가 전송 컨트롤 위(또는 여백 안)다 |
| `sms_send_target_unverifiable` | 전송 컨트롤이 있는데 대상 좌표·화면을 확인하지 못했다 |
| `sms_send_control_unidentified` | 전송 컨트롤이 **있어야 하는 단계**인데 하나도 못 찾았다 |

뒤의 둘이 중요하다 — **"확인하지 못했다"를 "안전하다"로 읽지 않는다.**

`sendControlRequired`는 시나리오가 자기 진행 단계로 판단한다. 대화 목록처럼 아직 전송 버튼이 없는
화면까지 무조건 막으면 시나리오가 진행되지 않으므로, **composer에 들어온 뒤부터** `true`로 올린다.
수신자를 전용 회선으로 제한하는 것은 원문 유출 방어이지 "문자를 발송하지 않는다"는 경계의 대체가
아니다 — 둘 다 필요하다.

전송 컨트롤은 text·contentDescription뿐 아니라 **resource-id**로도 찾는다(삼성 전송 버튼은
ImageButton이라 둘 다 빌 수 있다). 모델의 다음 행동을 정답 selector로 보정하는 것이 아니라 위험
컨트롤을 식별해 차단하는 용도이므로 안전 가드에서는 허용된다.

**시나리오가 직접 좌표를 누를 때**(준비·정리)도 같은 규칙을 쓴다. `device.click(x, y)`를 그대로
부르지 말고 살아 있는 화면을 스캔하는 가드를 통한다.

```kotlin
MessagingSendGuard.tapOrFail(device, x = 774, y = 2112)          // 기본 fail-closed
MessagingSendGuard.tapOrFail(device, x, y, sendControlRequired = false)  // 전송 버튼 없는 화면
```

### 개인정보를 띄우는 시나리오

수신자 번호는 **입력한 순간부터 이후 모든 화면 snapshot의 label에 남는다.** 시나리오 코드가 번호를
로그에 안 찍는 것만으로는 상세 보고서 유출을 막을 수 없다.

```kotlin
override val handlesPersonalData = true
```

`true`면 실행기가 `davi.detailedReport` 인자와 무관하게 상세 trace를 끈다. 켜는 방향으로는
작동하지 않는다.

수신자는 **코드에 넣지 않고** 전용 인자로 받는다. 기본값이 없어 설정하지 않으면 시나리오가 서고,
값은 `SmsTestRecipient` 타입 밖으로 문자열로 새지 않는다(`toString()`이 원문을 돌려주지 않는다).

```kotlin
private val recipient = SmsTestRecipients.require()   // -e davi.smsTestRecipient <시험자 회선>

override fun textFor(state: State, step: Int, decision: DisplayedDecision): String? =
    recipient.valueForInput()   // 원문이 나오는 유일한 자리
```

수신자는 **앱 `PrivacyMasker`가 실제로 가리는 국내 휴대폰 형식**(`01X-XXXX-XXXX`)만 받는다.
자릿수 검증이 아니라 유출 경로를 막는 조건이다 — 번호는 입력한 순간부터 화면 label에 남고,
1차 마스킹이 그 형식을 못 알아보면 **원문 그대로 서버를 거쳐 모델까지 나간다.** 상세 보고서를
꺼도 이 경로는 막히지 않는다. `+82`·유선·인터넷 전화는 거부하며, 넓히려면 앱 마스킹 계약부터
바꾼다(`PrivacyMasker.kt`와 `SmsTestRecipient`를 함께 본다).

시험자가 통제하는 회선인지는 코드가 알 수 없다. 기계적으로 확인 가능한 것(형식·긴급번호)만
막으므로, **전용 테스트 회선을 쓰는 것은 운영 규칙으로 지킨다.**

runner는 공통 설정 경계를 항상 먼저 평가한 뒤 시나리오 경계를 등록 순서대로 평가하며, 첫 failure
code로 테스트를 종료한다. `done`은 손동작이 아니라 세션 종료 후보이므로, `verified`일 때만 독립
목표 상태를 확인해 성공하거나 `done_before_goal`로 종료한다. `inconclusive`·`contradicted`는 각각
deviation으로 기록하고 앱의 복구를 기다린다. 이후 `verified`에 도달하지 못하면 시나리오의 전체
step 예산에서 `step_limit`로 실패한다.

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
  `GuidedHardBoundary`의 안전한 구조화 failure code로 남긴다.
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
- [ ] 설정 앱 및 기기 밖 부작용만 `hardBoundaries`로 실행 전에 차단한다.
- [ ] 문자 전송 시나리오는 label이 아니라 **좌표까지** 보는 경계를 쓴다(`MessagingSendGuard`).
- [ ] 시나리오가 직접 누르는 좌표도 `MessagingSendGuard.tapOrFail`을 통한다 — 생 `device.click` 금지.
- [ ] 개인정보를 띄우면 `handlesPersonalData = true`로 상세 trace를 끈다.
- [ ] 수신자·계정 같은 값은 코드에 넣지 않고 인자로 받으며, 원문이 `toString()`으로 새지 않는다.
- [ ] full-journey가 Davi → 홈 → 모든 경유 package를 같은 세션에서 검증한다.
- [ ] 완료를 외부 앱 상태로 독립 검증한다.
- [ ] 성공·실패·준비 실패 모두 테스트 데이터를 정리한다.
- [ ] logcat·실패 메시지·기본 보고서에 화면·안내·개인 원문이 없다.
- [ ] 상세 보고서는 Git 제외 경로에만 생성되고 원본 Accessibility 트리·키·serial이 없다.
- [ ] registry ID가 고유하고 test class가 실제로 존재한다.
- [ ] 키 없는 빌드와 보고서 렌더러 테스트가 통과한다.
