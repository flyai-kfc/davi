# CLAUDE.md — app-android (기기측 실행기)

Kotlin/Android 앱. **안내만** 한다(대신 조작 금지). 추론은 서버가, 앱은 상태 수집·안내 실행만.
공통 규칙·도메인 용어는 루트 `../CLAUDE.md`. 방향은 `../SPEC.md`.

## 이 폴더의 책임
- **화면 트리 직렬화** — 접근성 트리 → 요소 목록. 클릭 노드에 자손 텍스트를 합쳐 `label`. 스크롤 메타(`can_scroll_forward/backward` 등)와 페이지 dot 정보를 함께 담는다.
- **1차 마스킹** — 전화·계좌·주민·카드·이메일 패턴을 서버로 보내기 전에 치환. **원본은 여기서 떠나지 않는다.**
- **정체 감지** — 시간(폴링) 기반. 조작 이벤트에 의존하지 않는다(목록 끝에서 이벤트가 안 옴).
- **오버레이 렌더링** — 강조·방향 안내·읽는 중 테두리. 서버가 준 "다음 안내 하나"를 그린다.
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
- **빌드는 Android Studio에서만.** 이 개발 환경은 Gradle이 loopback을 못 열어 CLI 빌드가 실패한다(`HANDOFF.md` §3-15). Kotlin·리소스만 고쳤으면 Sync 없이 **Run**으로 반영.
- **API 키를 앱에 넣지 않는다.** 서버 터널 URL만 주입(BuildConfig 또는 설정 화면). 이전 PoC의 `BuildConfig.GEMINI_API_KEY` 경로는 **되살리지 않는다**(§5-2).
- 재설치하면 접근성 권한이 꺼질 수 있다. 테스트 전 확인: `adb shell settings get secure enabled_accessibility_services`.
- adb 서버 버전 충돌 시: `taskkill /F /IM adb.exe` → `adb start-server` → `adb devices`.

## 디버깅
- 1순위: `adb logcat -s GuideSvc`. 재현 직전 `adb logcat -c`로 버퍼 비우기.
- 접근성에 안 보이는 속성(`selected`, 정확한 bounds)은 `adb shell uiautomator dump`. XML은 UTF-8로 읽기.
- 로그가 비어 있으면 "안 돌았다"는 뜻. 앱 프로세스 생존은 `FreecessHandler ... com.davi.helper`로 확인.

## 기기 함정 (검증 기기 SM-S918N / One UI 8.5 기준, `HANDOFF.md` §3)
- 설정 앱 위에는 오버레이가 안 그려진다 → 헤드업 알림 폴백.
- 삼성 런처는 홈/앱서랍 모두 scrollable 노드 0개 → 페이지 넘김은 페이지 dot을 정보원으로.
- 오버레이 좌표는 `FLAG_LAYOUT_IN_SCREEN` 없이는 상태표시줄만큼 어긋난다.
- 안내문이 키보드를 가리면 접근성 `windows`의 `TYPE_INPUT_METHOD` top 위에 배치.
- 한 instruction에 동작 두 개 금지("홈 누르고 위로 쓸어올리세요").
- 타 제조사·구버전은 미검증. 기기 분기는 베타 사이클.

## 재사용 가능한 PoC 자산
`HANDOFF.md` §4 표 참조(UiTreeSerializer, 스크롤 메타, PAGE_DOT_RE, OverlayController 등).
**참조만. reference/ 는 수정 금지, 새 코드는 여기에 새로 쓴다.**
