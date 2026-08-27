---
name: run-davi
description: davi를 빌드/실행/실기기 검증한다 — 서버 기동(스텁·실모델·프로브), 앱 빌드·설치·JVM 테스트, 서버 URL 교체·원복, adb reverse, 실기기 조작·스크린샷·GuideSvc 로그. run/start/build/install/test/screenshot/실행/검증/실기기 요청이면 이 스킬.
---

# run-davi

**드라이버는 저장소에 있다 — `scripts/dev-driver.ps1`**(#344로 커밋). 이 스킬은 그것을 부르는
에이전트 쪽 절차만 담는다. **사용법·명령 목록·측정값·Troubleshooting은 `scripts/README.md`가 원본이다**
— 여기 복제하지 않는다(사본은 먼저 낡는다).

`.Codex/skills/run-davi/driver.ps1`은 옛 경로를 위한 shim이다. 이 브랜치에 `scripts/`가 아직
없으면 **본 레포로 우회하지 않고 멈춘다** — 우회하면 워크트리 작업 중에 본 레포가 기기에 올라간다.

## 세션 시작 — 이것부터

```powershell
.\scripts\dev-driver.ps1 env
```

기기·설치 패키지·접근성(목록 대 실제 바인딩)·서버 URL과 종류·reverse·포트·venv·`.env`를 한 번에
**판정**해 준다. **이걸 돌렸으면 환경을 더 캐지 않는다.** 워크트리에서 처음이면 `worktree-init` 먼저.

**항상 리터럴 경로로 부른다** — 변수(`& $d …`)에 담으면 권한 허용 규칙의 접두 매칭이 안 걸려
매번 프롬프트가 뜬다.

## 실기기를 잡기 전에

- **사용자에게 기기 사용 가능 여부를 확인한다. 회차마다 새로 받는다** — 앞 회차 허락은 그 확인 건에만 유효하다.
- **세션이 여럿이면 실기기는 하나다.** 다른 세션이 쓰는 중이면 `install`이 그쪽 APK를 덮고 진행 중
  안내 세션을 끊는다. 남의 화면에 좌표 탭이 들어가 오버레이 권한이 꺼진 적이 있다.
  기기가 도는 중인지는 `log`(델타)와 `dumpsys window`의 최상단으로 먼저 본다.
- **탭 전에 직전 캡처로 화면을 확인한다.** 중지 버튼 좌표는 스크롤 위치에 따라 달라진다.

## 안내 세션을 시작하는 법

릴리스 진입은 음성 대화라 adb로 목표를 못 넣는다. **개발용 백업 화면**을 쓴다.

```powershell
adb shell am start -n com.davi.helper.debug/com.davi.helper.MainActivity
```

앞은 applicationId라 `.debug`가 붙고 뒤는 namespace라 안 붙는다. 축약형
`com.davi.helper.debug/.MainActivity`는 클래스를 `com.davi.helper.debug.MainActivity`로 풀어
틀린다(#58). 클래스가 debug 소스셋에만 있어 릴리스 패키지에는 아예 없다.

- **debug 브로드캐스트(`com.davi.helper.e2e.START`)로는 시작할 수 없다**(실측). `am`은
  `Broadcast completed: result=0`을 내지만 수신기가 실행되지 않는다 — signature 권한이라 shell uid에서
  조용히 드롭된다. `pm grant`는 런타임 권한 전용이라 우회가 없다.
- **한글 목표는 인텐트로 넣는다**(#323, PR #364로 들어왔다). base64로 실어 전송 바이트를 전부
  ASCII로 만든다:

  ```powershell
  $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('알람 맞춰줘'))
  adb shell am start -n com.davi.helper.debug/com.davi.helper.MainActivity `
    --es davi.goal.b64 $b64 --ez davi.autostart true
  ```

  `autostart`를 빼면 입력칸만 채운다. 결과는 `log`의 `event=debug_goal_intent`가 알려 준다
  (`reason=ok|no_goal|undecodable|session_active|autostart_off|accessibility_not_ready`).
  **세션이 도는 중에 던지면 목표가 무시된다** — 먼저 중지할 것.
- **자판 좌표 탭은 폴백이다.** 같은 폴더 `korean-keys.ps1`에 삼성 HoneyBoard 두벌식 자모 좌표표
  (1080×2340, 숫자열 있는 키보드가 열린 상태)가 있다. 목표 입력에는 이제 쓰지 않고, **다른 앱의
  입력칸**을 채워야 할 때 남는다. 치려면 기기 셸 한 번에 몰아친다 — 4배 빠르다
  (`app-android/AGENTS.md` §도구 함정). 좌표표는 자판 높이·모드·숫자열·자판 종류·회전에 종속이다.
- **`안내 시작`을 누른 뒤 HOME을 눌러야 첫 안내가 뜬다.** 앱 화면이 최상단이면 `root_state=unavailable`이다.
- 시작 버튼이 IME에 가려 있으면 `dumpsys input_method`의 `mInputShown`을 보고 IME가 떠 있을 때만
  BACK을 보낸다. IME가 없는데 BACK을 보내면 화면이 닫힌다.

## 닫힌 루프 한 바퀴 (검증 완료 절차)

1. `env` → 필요하면 `url set` → `adb-fix` → `server`(또는 `-Real`) → `install -Clear`
2. 위 절차로 목표를 넣고 안내를 시작한다
3. `step key 3 5000`으로 홈 이동 + 대기 + 로그 델타를 한 콜에
4. 로그의 `action=`과 `highlight <노드> at=[l,t,r,b]`를 읽어 중심을 계산하고
   `step <x> <y> 3000`을 반복한다. **스크린샷을 보지 않고 스텝이 돈다** — debug 빌드가 요소
   `label`·`bounds`를 `Log.d`로 남긴다. 좌표 없는 안내(`open_app_drawer`·전역 scroll)는 `swipe`
5. `session done goal=…`까지 간 뒤, 증거가 필요해진 시점에 `shots pull`로 한꺼번에 회수
6. `server-stop` → 바꿨으면 `url restore` → 다 됐으면 `shots clear`

**세션을 끝내지 않고 두면 모델 쿼터를 계속 쓴다.** 백업 화면의 `안내 중지`로 끊고, 홈에서
새 로그가 안 늘어나는지로 확인한다.

## 함정은 저장소 문서에 있다

- 셸·adb 함정(`logcat -t N`, `uiautomator dump` 후유증, IME 타이밍, PNG 인코딩 등)
  → `app-android/AGENTS.md` §도구 함정
- 드라이버 명령·측정값·Troubleshooting → `scripts/README.md`
- 기기 동작 함정 → `app-android/AGENTS.md` §기기 함정

여기 다시 적지 않는다. 어긋나면 저장소 쪽이 원본이다.

## 이 스킬이 안 감싸는 것

- **클린 머신 셋업** — venv·SDK·JDK 설치는 범위 밖
- **맥** — 드라이버는 Windows 전용이다(#72). 맥은 `app-android/README.md`의 수동 절차
- **오버레이·접근성 최초 허용** — 상태 판정과 복구는 하지만 처음 켜는 건 사람이 기기에서 한다
