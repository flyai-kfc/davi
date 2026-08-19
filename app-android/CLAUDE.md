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
  정책은 `LabelMasking.kt`의 `LabelMasker`가 들고 `UiTreeSerializer`가 주입받는다. 유일한 예외는
  `debug` 전용 비교 실험(#203)이며, `local.properties`의 `DAVI_DISABLE_PRIVACY_MASKING`이나
  일회성 `-PdaviDisablePrivacyMasking`(속성 우선)으로 켠다. release는 상수 `false`에
  `verifyReleaseConfiguration`으로 잠긴다. 절차·경계는 `DEVICE-E2E-RUN.md`와 `../SPEC.md` §3.
- **특수 권한은 접근성 하나뿐이다**(#212). `SYSTEM_ALERT_WINDOW`는 선언하지 않는다 — 창 타입이
  `TYPE_ACCESSIBILITY_OVERLAY`라 그리기에 필요 없고(#56), 남아 있던 유일한 쓰임인 **안내 중 자동
  복귀**(`startActivity`가 기대던 BAL 면제)는 **알림 탭**이 대신한다. 2026-08-18 실측에서 그 권한
  없이는 복귀가 **조용히 막히는 것**을 확인했다(로그는 나가는데 최상단이 설정 앱 그대로, BAL 차단
  로그도 없음). **복귀를 되살리려면 그 권한이 다시 필요하고, 시니어가 설정 앱을 파고드는 온보딩
  비용도 함께 돌아온다** — `ServiceOffAction` KDoc부터 읽을 것.
  통지 수단이 알림 하나인 이유도 같은 자리에 있다: 우리가 백그라운드일 때 토스트는 억제되고
  (#190 실측) 오버레이는 서비스와 함께 죽는다. `POST_NOTIFICATIONS`는 온보딩이 한 번 묻고,
  거부해도 진행을 막지 않는다.
- **세션 관리** — 사용자가 입력한 목표를 시작·중지한다. **세션이 없으면 화면을 아예 읽지 않는다**(직렬화 이전에 끊는다). 인메모리라 프로세스가 죽으면 함께 끝난다.
  **화면이 꺼져 있어도 읽지 않는다**(아래 오버레이 렌더링 참고).
- **음성 목표 입력** — `SpeechRecognizer`는 온디바이스를 우선하고, 서버의 LLM 질문은 Android
  TTS로 읽는다. 이전 user/assistant 쌍은 최대 5왕복만 메모리에 두며 완성 goal은 기존 입력칸에
  넣는다. 자동으로 안내를 시작하지 않는다. 발화·질문·goal·대화 UUID는 logcat에 남기지 않는다.
- **실행 가능 앱 index** — `QUERY_ALL_PACKAGES` 없이 `LauncherApps`의 현재 profile launcher
  activity만 메모리에 유지한다. 서버의 최대 5개 후보를 package exact→정규화 alias 순으로 기기
  안에서 대조한다. `launchable` 후보가 없거나 후보 요청이 실패하면 가능성 경고를 보여 주며, 앱
  하나를 고르거나 canonical name을 goal에 덧붙이지 않는다. 후보 요청 실패로 이미 완성된 goal을
  폐기하지 않는다. 경고에서 계속해도 바로 안내를 시작하지 않고 원래 goal의 최종 확인을 한 번 더
  거친다. 기기 고유 package·label·component·profile이나 전체 목록은 전송·저장하지 않는다(#185).
- **정체 감지** — 시간(폴링) 기반. 조작 이벤트에 의존하지 않는다(목록 끝에서 이벤트가 안 옴).
  접근성 이벤트는 "지금 볼 만하다"는 신호로만 쓰고 판단은 샘플러가 한다(#39). 같은 화면이
  `STUCK_AFTER_MS` 넘게 그대로면 정체로 보고 안내에 한 줄을 덧붙인다 — **서버를 다시 부르지 않는다**
  (폴링이 곧 호출량이다).
- **구조화 행동 이력** — 서버가 돌려준 구조 기반 화면 서명과 action/direction/target을 활성
  `GuideSession`에 최대 10개만 보관해 다음 요청으로 보낸다. 원문 label은 해시하지 않으며,
  세션 generation이 바뀌면 안내 문장 이력과 함께 폐기한다. **서버의 반복 카운트는 #202에서
  걷혔다** — 계약 필드(`recent_actions`)는 남아 있고 지금 서버 소비자는 없다.
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
  안내창의 컨트롤 행에는 **한 번에 하나**만 앉는다(#139) — 평소에는 중지 칩, 확인 중에는
  확인쌍. 확인 화면은 둘이고 **종류(kind) 한 축으로 갈린다**(#193) — 완료 확인(GD-3, 서버 done이
  띄운다)은 화면 가장자리 테두리를 성공 tone으로 켜고 하단에 앉으며, 종료 확인(GD-4, 중지 칩이
  띄운다)은 전면 스크림 위 화면 세로 중앙에 앉고 테두리가 없다. 크롬은 **배타적**이다. 확인 중에는
  어느 쪽이든 읽는 중 표시가 꺼진다.
  **앱이 스스로 세션을 끝내지 않는다** — 검증된 완료도, 중지 칩도 확인을 거치고 종료는 ○ 응답뿐이다.
  **확인 화면은 화면에 소속되지 않는다** — 조작 안내와 달리 가리킬 대상이 없어서, 사용자가
  알림창을 내리든 홈을 누르든 다른 앱을 열든 따라가고 답하기 전에는 사라지지 않는다. 화면에
  묶었더니 셰이드를 내린 것만으로 답할 자리가 사라지고 그 화면에 대고 재질의가 나갔다(실측).
  **스크림은 터치를 막지 않는다** — 그리는 창이 `FLAG_NOT_TOUCHABLE`이고, 뒤집으면 "대신 조작하지
  않는다"와 부딪친다. 막을 이유도 없다(답하기 전엔 안 사라지므로 가둘 위험만 남는다).
  방향 스와이프 애니메이션은 아직 없다(#152 — HANDOFF §4의 `SwipeGuideView`).
  **화면이 꺼지면 오버레이를 걷고 잠금이 풀린 뒤에만 되살린다. 세션은 유지한다**(#56).
  기준이 키가드가 아니라 **화면 꺼짐**인 이유는 안내 도중 보안 인증이 뜨는 것이 정상 흐름이라
  그걸로 세션을 끝내면 안 되기 때문이고, 되살리는 시점이 `ACTION_USER_PRESENT`인 이유는 화면만
  켜진 잠금화면에서 되살리면 같은 문제로 돌아가기 때문이다. 청소·재표시는 `root == null` 경로의
  기계를 그대로 쓴다. **꺼진 동안 도착한 응답은 버리고**(그리면 잠금화면에 창이 다시 붙는다)
  깨어날 때 그 화면을 다시 묻는다. 정체 타이머도 재개 시점으로 다시 찍는다 — 꺼져 있던 구간은
  정체가 아니다. `if (screenOff) return` 가드들은 방어적 잔재가 아니라 이 규칙의 일부다.
- **로그인 진입(ON-1, #166)** — 카카오 SDK로 받은 access token을 서버 `/v1/auth/kakao`와
  교환하고 세션 토큰을 `AuthStore`(SharedPreferences)에 보관한다. **스킵이 항상 열려 있고
  어떤 실패도 앱 사용을 막지 않는다.** `GuideSession`의 비영속 규칙은 안내 세션 이야기라
  별개 계층이다. 네이티브 앱 키는 **빌드 타입마다 출처가 다르다**(#215) — debug는 개발자별
  테스트 앱 키를 `local.properties`에서 읽고, release는 팀 앱 키를 `build.gradle.kts`의
  `releaseKakaoNativeAppKey` 상수에서 읽는다. 이 둘은 섞이지 않는다: local.properties 값이
  있으면 `verifyReleaseConfiguration`이 release 빌드를 막는다. 키 없는 빌드는 SDK init·카카오
  버튼·OAuth handler를 함께 끈다. 카카오 토큰·세션 토큰은 logcat 금지.
- **로그인 버튼은 `BuildConfig.LOGIN_ENABLED`에 건다** — 키 유무가 아니라 **로그인이 끝까지
  갈 수 있는가**다. 카카오 인증에 성공해도 세션을 교환할 `SERVER_URL`이 placeholder면
  `login_error_server`로 끝나므로, 그 빌드는 버튼을 띄우지 않는다. 진입점(scheme·handler
  activity)은 키와 함께 켜 두어 주소가 정해지는 순간(#214) 조건만 참이 되게 한다.
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
- **평소 검증 루프는 CLI로 돈다.** `./scripts/dev-install.ps1` 하나로 빌드·설치·접근성 재활성화까지 끝난다. Android Studio를 열 필요가 없다.
- **상태만 볼 땐 `./scripts/dev-install.ps1 -Status`.** 기기·설치 패키지·접근성·오버레이를 한 줄씩 판정해 준다. 아무것도 바꾸지 않는다.
- **Gradle CLI가 `Unable to establish loopback connection`으로 죽는 PC가 있다.** 원인은 TCP 루프백이 아니라 AF_UNIX 소켓 임시 폴더이고, 스크립트가 흡수한다. 자세한 건 `README.md`.
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
  **되살리지 않는다**(HANDOFF §5-2).
- **release 서명은 debug 설정이 아니다**(#221). 업로드 키스토어 경로·비밀번호는
  `local.properties`에서 읽되 `debugSetting`이 아니라 `localSetting`으로 읽는다 — release가
  정당하게 쓰는 값이라 `verifyReleaseConfiguration`이 막는 대상에 넣으면 안 된다.
  **값이 없으면 서명 없이 그대로 빌드한다**(`app-release-unsigned.apk`). CI와 키 없는 팀원
  PC에서 `assembleRelease`가 계속 돌아야 하기 때문이다. keystore·비밀번호는 커밋하지 않는다.
  다만 **반쯤 준 설정은 막는다** — 경로만·비밀번호만 준 쪽 모두 실패한다. 오타로 경로가
  비면 조용히 서명 없이 나가기 때문이다.
- **카카오 키 해시는 서명 인증서마다 다르다.** debug·업로드 키·Play 앱 서명 키 셋을 콘솔에
  등록해야 하고, Play 것은 첫 AAB 업로드 뒤에야 생긴다. 계산 절차와 표는 `README.md`
  "카카오 키 해시" 절 한 곳에만 둔다 — 코드 주석은 그리로 보낸다.
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
- **`animator_duration_scale`을 바꿔도 이미 떠 있는 프로세스에는 안 먹는다**(2026-08-19 실측).
  접근성 서비스는 세션 밖에서도 계속 살아 있어서, 배율을 0으로 내려도 그 프로세스가 캐시한
  "켜짐"을 계속 읽는다. **모션 끄기를 검증하려면 배율을 바꾼 뒤 재설치해 프로세스를 새로 띄운다.**
  안 그러면 "모션 끄기가 안 먹는다"로 잘못 읽는다(두 번 헤맸다).
- **중지 버튼 좌표는 스크롤 위치에 따라 달라진다.** 좌표를 외워 탭하지 말고 직전 캡처로 확인한다. 빗나가면 세션이 계속 돌며 모델 쿼터를 쓴다(실제로 두 번 겪었다).

## 기기 함정 (검증 기기 SM-S918N·SM-S921N / One UI 8.5 기준)
- 설정 앱 위에서 숨겨지는 것은 `SYSTEM_ALERT_WINDOW` 계열 오버레이뿐이다. **접근성 서비스가
  띄우는 `TYPE_ACCESSIBILITY_OVERLAY`는 그려진다**(2026-08-18 SM-S921N 실측, #56) — 강조·안내창·
  칩이 모두 뜨고 표시 오버레이 아래로 터치도 통과한다. `HANDOFF.md` §3-1의 "우회 불가"는 이
  범위로 좁혀 읽는다(HANDOFF는 읽기 전용이라 정정이 여기 있다).
  같은 실측에서 **오버레이가 덮은 자리를 탭해도 시스템이 터치를 버리지 않았다**(logcat에
  `Untrusted touch`·occlusion 계열 없음, 탭이 아래 앱에 그대로 닿았다). 다만 앱이 스스로
  거르는 `filterTouchesWhenObscured`(설정의 권한 토글 등)는 **재지 않았다** — 확인하려면
  실제 권한 토글을 눌러야 해서다. 그 자리만 남는다.
- **`ValueAnimator.areAnimatorsEnabled()`의 프로세스 캐시는 콜드 스타트에서 첫 창 부착보다
  늦게 동기화될 수 있다**(2026-08-19 SM-S921N 실측, #148 PR #243). 배율 0인데 첫 진입의
  `onAttachedToWindow` 시점 판정이 "켜짐"을 읽어 루프가 돌았고, 재진입 전이(재판정)에서야
  멈췄다. 그래서 `MotionDriver`는 시작 판정에 더해 **프레임 콜백에서 매 프레임 재확인**한다 —
  캐시 필드 읽기라 프레임당 비용이 없다. 시작 시점 한 번만 믿는 코드를 새로 쓰지 말 것.
- **절전 모드는 이 기기에서 애니메이터를 끄지 않는다**(2026-08-19 SM-S921N 실측, #148).
  `low_power=1` + 배율 1에서 모션이 계속 돌았다. `areAnimatorsEnabled()`가 절전을 반영한다는
  것은 API 문서상 기대일 뿐이니, "절전 = 정지 프레임"을 전제로 설계·검증하지 말 것.
- **`AccessibilityEvent.source`는 간헐적으로 null이다**(2026-08-19 SM-S921N 실측, #238).
  같은 런처 아이콘을 같은 방식으로 탭했는데 설치·진단이 연달아 돌던 구간에서만 `src=false`였고
  한산할 때는 정상이었다. 시스템이 노드를 짧게만 들고 있어 부하가 걸리면 회수되는 것으로 보인다 —
  **앱별 차이가 아니라 타이밍이다.** 그래서 클릭 이벤트를 좌표로만 맞추면 조용히 놓친다.
  `text`·`contentDescription`은 source와 무관하게 실려 오므로 폴백 축으로 쓴다(`PressMatch`).
- 삼성 런처의 page/scroll 노출은 구성에 따라 다르다(SM-S921N 실측): 1페이지 홈은 dot이
  없고, 다중 페이지 홈·격자 앱서랍은 page dot과 `scrollable=false`가 함께 오며, 가나다순
  앱서랍은 page 없이 `scrollable=true`인 세로 목록이다. 앱 규칙으로 일반화하지 않고
  `../eval/fixtures/`의 구성별 입력으로 보존한다(#47).
- **접근성 오버레이는 시스템 UI보다 위 층이다**(2026-08-18 SM-S921N `dumpsys window`, #0이 최상단):
  우리 두 창 **#2·#3** > `NavigationBar0` #5 > `Bouncer` #6 > `NotificationShade` #9 >
  `StatusBar` #10. 구 창 타입은 이 넷보다 아래였다. 설정 앱을 이긴 것과 같은 메커니즘의 다른
  얼굴이라, **잠금화면·상태표시줄·내비바를 덮을 수 있다**는 뜻이다(잠금화면은 #56이 화면 꺼짐
  가드로 닫았다). 읽는 중 테두리는 인셋을 빼지 않아 여전히 시계·내비 버튼 위에 겹칠 수 있다.
- 오버레이 좌표는 `FLAG_LAYOUT_IN_SCREEN` 없이는 상태표시줄만큼 어긋난다. 있어도 창마다 인셋이 다르게
  붙는다(런처에서만 밀린 실측). **flags를 맞춰도 두 창의 부모 프레임은 같아지지 않는다**(#139
  `dumpsys window` 실측 — MATCH_PARENT인 표시 창은 `[0,103]`, 크기가 지정된 조작 창은 `[0,0]`).
  그래서 창 사이에 자리를 넘길 때는 **화면 좌표**를 쓰고, 창 원점 보정은 그리는 쪽에서만 한다.
- **안내창은 시스템 바를 침범하면 안 된다**(#169). 안내창 사각형이 곧 조작 오버레이 창의 터치
  영역이라, 내비게이션 바에 걸친 만큼 뒤로·홈·최근 앱이 죽는다(A/B 실측: 창 하단 2266일 때
  `(242,2230)` 탭이 죽고, 2111로 물리면 살아난다). 그래서 안내창 자리 계산은 그릴 수 있는 범위가
  아니라 **시스템 바를 뺀 범위**를 쓴다. 강조 사각형은 그 범위를 안 쓴다 — 내비바 버튼도 강조
  대상이라 인셋을 빼면 아랫변이 잘린다.
- **네 변을 다 빼야 한다.** 위아래만 빼면 가로 화면에서 그대로 뚫린다 — 안내 대상 앱은 우리
  액티비티(`screenOrientation="portrait"`)와 달리 가로일 수 있고, 가로에서는 **내비게이션 바가
  옆 변으로 간다**(2026-08-18 SM-S921N `ROTATION_90` 실측: 오른쪽 변. 같은 좌표의 홈 버튼이
  오버레이가 있을 때만 죽었고, 오버레이 없는 대조군에서는 눌렸다).
- **인셋은 창이 아니라 디스플레이에서 읽는다.** 오버레이 창은 `FLAG_LAYOUT_NO_LIMITS`라
  `getRootWindowInsets()`가 `top=0 bottom=0`을 준다(실측) — 그걸 믿으면 아무것도 안 빼게 된다.
  `WindowManager.currentWindowMetrics`가 창 flags와 무관한 화면 기준 값을 준다. 하단 인셋이
  3버튼 162px / 제스처 51px로 3배 차이라 상수로 박지 않는다.
- 안내문이 키보드를 가리면 접근성 `windows`의 `TYPE_INPUT_METHOD` top 위에 배치.
- 한 instruction에 동작 두 개 금지("홈 누르고 위로 쓸어올리세요").
- 타 제조사·구버전은 미검증. 기기 분기는 베타 사이클.

## 재사용 가능한 PoC 자산
`HANDOFF.md` §4 표 참조(UiTreeSerializer, 스크롤 메타, PAGE_DOT_RE, OverlayController 등).
**참조만. reference/ 는 수정 금지, 새 코드는 여기에 새로 쓴다.**
