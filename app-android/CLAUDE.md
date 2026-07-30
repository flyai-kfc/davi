# CLAUDE.md — app-android (기기측 실행기)

Kotlin/Android 앱. **안내만** 한다(대신 조작 금지). 추론은 서버가, 앱은 상태 수집·안내 실행만.
공통 규칙·도메인 용어는 루트 `../CLAUDE.md`. 방향은 `../SPEC.md`.

## 이 폴더의 책임

**책임이지 현황이 아니다.** 아직 없는 것은 아래에 이슈 번호로 표시한다 — 문서를 읽고
"이미 있다"고 착각하지 않도록.

- **화면 트리 직렬화** — 접근성 트리 → 요소 목록. 클릭 노드에 자손 텍스트를 합쳐 `label`. 스크롤 메타(`can_scroll_forward/backward` 등)와 페이지 dot 정보를 함께 담는다.
- **1차 마스킹** — 전화·계좌·주민·카드·이메일 패턴을 서버로 보내기 전에 치환. **원본은 여기서 떠나지 않는다.**
- **세션 관리** — 사용자가 입력한 목표를 시작·중지한다. **세션이 없으면 화면을 아예 읽지 않는다**(직렬화 이전에 끊는다). 인메모리라 프로세스가 죽으면 함께 끝난다.
- **정체 감지** — 시간(폴링) 기반. 조작 이벤트에 의존하지 않는다(목록 끝에서 이벤트가 안 옴).
  **아직 없다(#39).** 지금은 접근성 이벤트에만 반응해서, 화면이 안 바뀌면 다음 안내가 오지 않는다.
- **반복 가드 이력** — 서버가 돌려준 구조 기반 화면 서명과 action/direction/target을 활성
  `GuideSession`에 최대 10개만 보관해 다음 요청으로 보낸다. 원문 label은 해시하지 않으며,
  세션 generation이 바뀌면 안내 문장 이력과 함께 폐기한다.
- **오버레이 렌더링** — 서버가 준 "다음 안내 하나"를 그린다. 지금은 **강조 사각형 + 안내문 상자**까지.
  방향 스와이프 애니메이션·읽는 중 테두리는 아직 없다(HANDOFF §4의 `SwipeGuideView` / `ReadingBorderView`).
  오버레이가 안 그려지는 화면(설정 앱)의 헤드업 폴백도 아직 없다.
- 서버가 준 action을 그대로 실행하되, **사용자 대신 탭하지 않는다.**

## UI 컨벤션 (확정 2026-07-24)
**화면(Activity/Fragment)은 XML 레이아웃 + ViewBinding. 오버레이는 커스텀 View + Canvas. Compose는 쓰지 않는다.**
- 이유: 시니어 요구(큰 글씨·고대비·TalkBack)가 XML 리소스에서 거의 공짜다 — `sp`가 시스템 글꼴을 따라가고 `values-night` 등으로 분기된다. 오버레이는 `WindowManager.addView` 런타임 생성 + `onDraw` 애니메이션이라 애초에 커스텀 View만 가능하고, HANDOFF §4의 검증 자산도 전부 커스텀 View다. Compose는 오버레이 윈도우에 ViewTree owner를 손으로 붙여야 하는 함정이 있어 이 사이클엔 이득 < 비용.
- **코드에서 px/밀도를 계산하지 않는다.** 글자 크기·여백·터치 대상은 `res/values/dimens.xml`에서 가져온다.
- 글자가 커지면 잘리므로 화면 루트는 스크롤 가능하게 둔다.

## 고정값
- `applicationId` / `namespace` = **`com.davi.helper`** (PoC의 `com.davipoc.mvp` 재사용 안 함 — poc/mvp가 영구히 박히므로).
- `minSdk` 26 (Android 8.0) / `targetSdk` · `compileSdk` 36 (Android 16, 검증 기기 기준).
- 소스 경로: `app/src/main/java/com/davi/helper/`. 로그 태그: `GuideSvc`.

## 빌드·실행 (코드만 봐선 모르는 것)
- **평소 검증 루프는 CLI로 돈다.** `./scripts/dev-install.ps1` 하나로 빌드·설치·접근성 재활성화까지 끝난다. Android Studio를 열 필요가 없다.
- `HANDOFF.md` §3-15의 "CLI 빌드 불가"는 **해결됐다.** 원인은 TCP 루프백이 아니라 AF_UNIX 소켓이었고, 소켓 임시 폴더를 옮기면 정상 동작한다. 자세한 건 `README.md`.
- **Android Studio는 디버거·Layout Inspector·프로파일러가 필요할 때만** 쓴다. 그건 CLI로 대체되지 않는다.
- **Studio로 빌드하면 접근성 권한이 꺼진 채로 남는다.** Studio는 그걸 되살려주지 않으므로 `./scripts/dev-install.ps1 -SkipBuild`로 권한만 복구한다(2초).
- **wrapper 파일을 건드렸다면** `git ls-files --stage app-android/gradlew`가 `100755`인지 확인한다. 실행 비트나 LF가 깨지면 리눅스 CI에서 안 돈다(`.gitattributes`가 강제하지만 Windows 편집 시 주의).
- **API 키를 앱에 넣지 않는다.** debug에만 서버 URL·개발 접근 토큰을 주입한다. release는
  개발 설정을 읽거나 포함하지 않는다. 이전 PoC의 `BuildConfig.GEMINI_API_KEY` 경로는
  **되살리지 않는다**(§5-2).
- 재설치하면 접근성 권한이 꺼질 수 있다. 테스트 전 확인: `adb shell settings get secure enabled_accessibility_services`.
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
- 오버레이 좌표는 `FLAG_LAYOUT_IN_SCREEN` 없이는 상태표시줄만큼 어긋난다.
- 안내문이 키보드를 가리면 접근성 `windows`의 `TYPE_INPUT_METHOD` top 위에 배치.
- 한 instruction에 동작 두 개 금지("홈 누르고 위로 쓸어올리세요").
- 타 제조사·구버전은 미검증. 기기 분기는 베타 사이클.

## 재사용 가능한 PoC 자산
`HANDOFF.md` §4 표 참조(UiTreeSerializer, 스크롤 메타, PAGE_DOT_RE, OverlayController 등).
**참조만. reference/ 는 수정 금지, 새 코드는 여기에 새로 쓴다.**
