# CLAUDE.md — app-android (기기측 실행기)

Kotlin/Android 앱. **안내만** 한다(대신 조작 금지). 추론은 서버가, 앱은 상태 수집·안내 실행만.
공통 규칙·도메인 용어는 루트 `../CLAUDE.md`. 방향은 `../SPEC.md`.

## 이 폴더의 책임

**책임이지 현황이 아니다.** 아직 없는 것은 아래에 이슈 번호로 표시한다 — 문서를 읽고
"이미 있다"고 착각하지 않도록.

- **화면 트리 직렬화** — 접근성 트리 → 요소 목록. 클릭 노드에 자손 텍스트를 합쳐 `label`.
  스크롤 메타·페이지 dot과 편집 가능/입력 포커스/IME 표시 상태를 함께 담는다. 일반 편집 요소의 입력
  원문과 IME 키 트리는 직렬화하지 않고 hint만 안전한 label 후보로 쓴다(#112). 단 보임·활성·조작
  action·가까운 조정 가능 조상을 확인한 `NumberPicker`의 짧은 숫자 label은 현재 선택값으로 보존하고,
  그 숫자는 화면 시그니처에도 포함해 picker 스크롤을 새 상태로 감지한다.
- **1차 마스킹** — 전화·계좌·주민·카드·이메일 패턴을 서버로 보내기 전에 치환. **원본은 여기서 떠나지 않는다.**
- **세션 관리** — 사용자가 입력한 목표를 시작·중지한다. **세션이 없으면 화면을 아예 읽지 않는다**(직렬화 이전에 끊는다). 인메모리라 프로세스가 죽으면 함께 끝난다.
- **음성 목표 입력** — `SpeechRecognizer`는 온디바이스를 우선하고, 서버의 LLM 질문은 Android
  TTS로 읽는다. 이전 user/assistant 쌍은 최대 5왕복만 메모리에 두며 완성 goal은 기존 입력칸에
  넣는다. 자동으로 안내를 시작하지 않는다. 발화·질문·goal·대화 UUID는 logcat에 남기지 않는다.
- **실행 가능 앱 index** — `QUERY_ALL_PACKAGES` 없이 `LauncherApps`의 현재 profile launcher
  activity만 메모리에 유지한다. 서버의 최대 5개 후보를 package exact→정규화 alias 순으로 기기
  안에서 대조하고, 기기 고유 package·label·component·profile이나 전체 목록은 전송·저장하지 않는다.
- **정체 감지** — 시간(폴링) 기반. 조작 이벤트에 의존하지 않는다(목록 끝에서 이벤트가 안 옴).
  접근성 이벤트는 "지금 볼 만하다"는 신호로만 쓰고 판단은 샘플러가 한다(#39). 같은 화면이
  `STUCK_AFTER_MS` 넘게 그대로면 정체로 보고 안내에 한 줄을 덧붙인다 — **서버를 다시 부르지 않는다**
  (폴링이 곧 호출량이다).
- **반복 가드 이력** — 서버가 돌려준 구조 기반 화면 서명과 action/direction/target을 활성
  `GuideSession`에 최대 10개만 보관해 다음 요청으로 보낸다. 원문 label은 해시하지 않으며,
  세션 generation이 바뀌면 안내 문장 이력과 함께 폐기한다.
- **논리 요청 추적** — 안정된 화면의 첫 요청에 무작위 Logical Request ID를 만들고 network/timeout
  재전송은 같은 ID의 attempt만 올린다. 화면·목표·세션 변경과 정상 응답 뒤 새 추론은 새 ID다.
  화면 내용·`device_id`에서 ID를 만들지 않고 앱 프로세스 밖에 저장하지 않는다(#81).
- **오버레이 렌더링** — 서버가 준 "다음 안내 하나"를 그린다. 지금은 **강조 사각형 + 안내문 상자**,
  그리고 **읽는 중 테두리**(#127 — 세션 시작·화면 흔들림·요청·응답 대기 구간을 덮고 안내가
  도착하면 꺼진다. 안내와 독립 레이어라 `clear()`가 지우지 않는다)까지.
  오버레이는 둘이다(#126) — **표시 오버레이**(`FLAG_NOT_TOUCHABLE`, 하이라이트와 읽는 중 테두리를
  그리고 터치는 아래 앱으로 통과)와 **조작 오버레이**(안내창과 칩이 살고 그 영역의 터치를 받는다).
  안내창을 조작 쪽에 둔 이유는 칩이 안내창에서 떨어지지 않게 하려는 것이고, 창 전체가 터치를
  흡수하는 이유는 칩을 빗맞혔을 때 가려진 아래 앱이 눌리는 오조작을 막으려는 것이다.
  안내창의 컨트롤 행에는 **한 번에 하나**만 앉는다(#139) — 평소에는 중지 칩, 완료 확인(GD-3)
  중에는 확인쌍(×아직요/○됐어요). 확인 중에는 화면 가장자리 테두리가 성공 tone으로 함께 켜지고
  읽는 중 표시는 꺼진다. **검증된 완료도 앱이 스스로 끝내지 않는다** — 종료는 ○ 응답뿐이다.
  **확인 화면은 화면에 소속되지 않는다** — 조작 안내와 달리 가리킬 대상이 없어서, 사용자가
  알림창을 내리든 홈을 누르든 다른 앱을 열든 따라가고 답하기 전에는 사라지지 않는다. 화면에
  묶었더니 셰이드를 내린 것만으로 답할 자리가 사라지고 그 화면에 대고 재질의가 나갔다(실측).
  방향 스와이프 애니메이션은 아직 없다(HANDOFF §4의 `SwipeGuideView`).
  오버레이가 안 그려지는 화면(설정 앱)의 헤드업 폴백도 아직 없다.
- 서버가 준 action을 그대로 실행하되, **사용자 대신 탭하지 않는다.**

## UI 컨벤션 (확정 2026-07-24)
**화면(Activity/Fragment)은 XML 레이아웃 + ViewBinding. 오버레이는 커스텀 View + Canvas. Compose는 쓰지 않는다.**
- 이유: 시니어 요구(큰 글씨·고대비)가 XML 리소스에서 거의 공짜다 — `sp`가 시스템 글꼴을 따라가고 `values-night` 등으로 분기된다. 오버레이는 `WindowManager.addView` 런타임 생성 + `onDraw` 애니메이션이라 애초에 커스텀 View만 가능하고, HANDOFF §4의 검증 자산도 전부 커스텀 View다. Compose는 오버레이 윈도우에 ViewTree owner를 손으로 붙여야 하는 함정이 있어 이 사이클엔 이득 < 비용.
- **코드에서 px/밀도를 계산하지 않는다.** 글자 크기·여백·터치 대상은 `res/values/dimens.xml`에서 가져온다.
- 글자가 커지면 잘리므로 **폼형 화면**(입력·버튼이 세로로 쌓이는 화면)은 루트를 스크롤 가능하게
  둔다. 대화 화면처럼 고정 배치인 화면은 예외다(#123) — 요소 수를 적게 유지해 여백으로 흡수한다.

## 고정값
- `applicationId` / `namespace` = **`com.davi.helper`** (PoC의 `com.davipoc.mvp` 재사용 안 함 — poc/mvp가 영구히 박히므로).
- `minSdk` 26 (Android 8.0) / `targetSdk` · `compileSdk` 36 (Android 16, 검증 기기 기준).
- 소스 경로: `app/src/main/java/com/davi/helper/`. 로그 태그: `GuideSvc`.

## 빌드·실행 (코드만 봐선 모르는 것)
- **평소 검증 루프는 CLI로 돈다.** `./scripts/dev-install.ps1` 하나로 빌드·설치·접근성 재활성화·오버레이 권한 확인까지 끝난다. Android Studio를 열 필요가 없다.
- **상태만 볼 땐 `./scripts/dev-install.ps1 -Status`.** 기기·설치 패키지·접근성·오버레이를 한 줄씩 판정해 준다. 아무것도 바꾸지 않는다.
- `HANDOFF.md` §3-15의 "CLI 빌드 불가"는 **해결됐다.** 원인은 TCP 루프백이 아니라 AF_UNIX 소켓이었고, 소켓 임시 폴더를 옮기면 정상 동작한다. 자세한 건 `README.md`.
- **Android Studio는 디버거·Layout Inspector·프로파일러가 필요할 때만** 쓴다. 그건 CLI로 대체되지 않는다.
- 다른 앱까지 포함한 실제 Gemini 안내 E2E는 `DEVICE-E2E.md`에서 문서 경로를 확인하고
  `scripts/run-device-e2e.sh` 또는 `.ps1`을 쓴다. API 키는 서버에만 두고, 일반 CI는
  `assembleDebugAndroidTest`로 test APK 컴파일까지만 수행한다. 실행별 상세 요청·응답 보고서는
  `build/reports/device-e2e-private/`에 기본 생성되며 로컬 분석 전용이라 공유·커밋하지 않는다.
  기본 시나리오는 Davi 최신 화면 → Android 홈 → 여러 package를 같은 세션에서 잇는
  `full-journey`이며, 특정 외부 앱 화면부터 시작하는 시나리오는 빠른 진단용으로 명시한다.
- **Studio로 빌드하면 접근성 권한이 꺼진 채로 남는다.** Studio는 그걸 되살려주지 않으므로 `./scripts/dev-install.ps1 -A11yOnly`로 권한만 복구한다(2초).
- **wrapper 파일을 건드렸다면** `git ls-files --stage app-android/gradlew`가 `100755`인지 확인한다. 실행 비트나 LF가 깨지면 리눅스 CI에서 안 돈다(`.gitattributes`가 강제하지만 Windows 편집 시 주의).
- **API 키를 앱에 넣지 않는다.** debug에만 서버 URL·개발 접근 토큰을 주입한다. release는
  개발 설정을 읽거나 포함하지 않는다. 이전 PoC의 `BuildConfig.GEMINI_API_KEY` 경로는
  **되살리지 않는다**(§5-2).
- `RECORD_AUDIO`는 사용자가 `말로 입력`을 누를 때 런타임 요청한다. 원시 음성을 Davi 서버로
  직접 보내거나 저장하지 않는다. 인식된 텍스트와 이전 턴은 의미 정리를 위해 서버/Gemini로
  전송된다(SPEC §4 음성 목표 계약).
- 재설치하면 접근성 권한이 꺼질 수 있다. 테스트 전 확인: `./scripts/dev-install.ps1 -Status`.
  목록(`settings get secure enabled_accessibility_services`)에 값이 있어도 바인딩이 죽어
  있을 수 있어(`dumpsys accessibility`의 `Bound services:{}`) 목록만 봐선 모른다.
- adb 서버 버전 충돌 시: `taskkill /F /IM adb.exe` → `adb start-server` → `adb devices`.

## 디버깅
- 1순위: `adb logcat -s GuideSvc`. 재현 직전 `adb logcat -c`로 버퍼 비우기.
- 접근성에 안 보이는 속성(`selected`, 정확한 bounds)은 `adb shell uiautomator dump`. XML은 UTF-8로 읽기.
- 로그가 비어 있으면 "안 돌았다"는 뜻. 앱 프로세스 생존은 `FreecessHandler ... com.davi.helper`로 확인.

### 도구 함정 (2026-07-28 실측)
셸·adb 쪽 함정이라 기기와 무관하다. 기기 동작 지식은 여기 적지 말고 픽스처로 쌓는다(#47).
- **Git Bash에서 adb에 `/sdcard/...`를 넘길 땐 `MSYS_NO_PATHCONV=1`.** 없으면 MSYS가 경로를 Windows 경로로 바꿔 파일을 못 찾는다.
- **PowerShell `>`가 `adb exec-out screencap` 바이너리를 깨뜨린다.** 인코딩이 끼어든다 → `adb shell screencap -p /sdcard/x.png` + `adb pull`.
- **`adb shell input text`는 한글을 못 넣는다.** 목표 입력은 기기에서 직접 치거나 다른 경로로.
- **중지 버튼 좌표는 스크롤 위치에 따라 달라진다.** 좌표를 외워 탭하지 말고 직전 캡처로 확인한다. 빗나가면 세션이 계속 돌며 모델 쿼터를 쓴다(실제로 두 번 겪었다).

## 기기 함정 (검증 기기 SM-S918N·SM-S921N / One UI 8.5 기준, `HANDOFF.md` §3)
- 설정 앱 위에는 오버레이가 안 그려진다 → 헤드업 알림 폴백.
- 삼성 런처의 page/scroll 노출은 구성에 따라 다르다(SM-S921N 실측): 1페이지 홈은 dot이
  없고, 다중 페이지 홈·격자 앱서랍은 page dot과 `scrollable=false`가 함께 오며, 가나다순
  앱서랍은 page 없이 `scrollable=true`인 세로 목록이다. 앱 규칙으로 일반화하지 않고
  `../eval/fixtures/`의 구성별 입력으로 보존한다(#47).
- 오버레이 좌표는 `FLAG_LAYOUT_IN_SCREEN` 없이는 상태표시줄만큼 어긋난다. 있어도 창마다 인셋이 다르게
  붙는다(런처에서만 밀린 실측). **flags를 맞춰도 두 창의 부모 프레임은 같아지지 않는다**(#139
  `dumpsys window` 실측 — MATCH_PARENT인 표시 창은 `[0,103]`, 크기가 지정된 조작 창은 `[0,0]`).
  그래서 창 사이에 자리를 넘길 때는 **화면 좌표**를 쓰고, 창 원점 보정은 그리는 쪽에서만 한다.
- 안내문이 키보드를 가리면 접근성 `windows`의 `TYPE_INPUT_METHOD` top 위에 배치.
- 한 instruction에 동작 두 개 금지("홈 누르고 위로 쓸어올리세요").
- 타 제조사·구버전은 미검증. 기기 분기는 베타 사이클.

## 재사용 가능한 PoC 자산
`HANDOFF.md` §4 표 참조(UiTreeSerializer, 스크롤 메타, PAGE_DOT_RE, OverlayController 등).
**참조만. reference/ 는 수정 금지, 새 코드는 여기에 새로 쓴다.**
