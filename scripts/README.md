# scripts/ — 실기기 검증 드라이버

서버·앱·기기를 한 진입점에서 다룬다. **Windows 전용**(Windows PowerShell 5.1)이다 —
맥에서는 안 돌고, 손으로 치는 절차가 `app-android/README.md`에 그대로 남아 있다. 이식은 #72.

**역할 경계** — 기기·권한 판정과 설치는 `app-android/scripts/dev-install.ps1`이 한다.
드라이버는 그걸 위임 호출하고 **세션 오케스트레이션**(서버 기동, 서버 URL 관리, adb 위생,
기기 조작, 관측)을 맡는다. 같은 판정을 두 벌 만들지 않는다.

**대상은 이 스크립트가 놓인 레포다.** 워크트리에 체크아웃돼 있으면 그 워크트리를 빌드·설치한다 —
어느 코드가 기기에 올라가는지 먼저 확인할 것.

## 세션 시작

```powershell
.\scripts\dev-driver.ps1 env
```

기기·설치 패키지·접근성(목록 대 실제 바인딩)·서버 URL과 종류·reverse·포트·venv·`.env`를
한 번에 **판정**해 준다. 워크트리에서 처음이면 `worktree-init`이 먼저다(gitignore된
`local.properties`가 없어 `SDK location not found`로 죽는다).

## 명령

```powershell
.\scripts\dev-driver.ps1 env                     # 전제조건 일람(판정 포함)
.\scripts\dev-driver.ps1 worktree-init           # 본 레포 local.properties 복사
.\scripts\dev-driver.ps1 adb-fix                 # adb 재시작 + reverse 재설정
.\scripts\dev-driver.ps1 server                  # 스텁 LLM 서버(키 불필요). -Real 실모델 / -Probe <py>
.\scripts\dev-driver.ps1 install                 # 빌드+설치+권한 복구 (-Clear: logcat 비움, -SkipBuild: 권한만)
.\scripts\dev-driver.ps1 test                    # 앱 JVM 단위 테스트 (기기 불필요)
.\scripts\dev-driver.ps1 step 538 1503 3000      # 탭+캡처예약+3초 대기+로그델타를 한 콜로
                                                 #   step key 3 3000 / step wait 3000(관찰만)
.\scripts\dev-driver.ps1 swipe 540 1600 540 900  # 스크롤 안내 따라 하기(300ms 지속 — 플링 방지)
                                                 #   tap·key와 같이 -Shot이면 캡처도 예약
.\scripts\dev-driver.ps1 tap 538 1503            # 좌표 탭 / key 3 (HOME) — -Shot이면 캡처도 예약
.\scripts\dev-driver.ps1 shots pull              # 회수 → app-android\captures\shots\**davi_shots\**
                                                 #   (폴더째 pull이라 한 겹 더 들어간다)
                                                 #   shots: 목록 / shots clear: 기기에서 삭제
.\scripts\dev-driver.ps1 shot [경로]              # 동기 캡처(1.3초). 기본 app-android/captures/run-shot.png
.\scripts\dev-driver.ps1 log                     # GuideSvc 로그 델타 (-Full: 버퍼 전체, -Clear: 비움)
.\scripts\dev-driver.ps1 url                     # 현재값 + 종류 / url set local|azure|wifi|<URL> / url restore
.\scripts\dev-driver.ps1 e2e list                # 실기기 E2E 시나리오 목록 / e2e <id>로 실행
.\scripts\dev-driver.ps1 server-stop
```

## 서버 URL — 앱이 어디에 붙는지

**URL은 빌드에 박힌다.** 파일만 고치고 재빌드하지 않으면 APK엔 옛 URL이 남는다.
`url set`·`url restore`가 백업·교체·재빌드·설치·권한 복구까지 한 번에 한다.

- `local` — `http://127.0.0.1:8000`. **`adb reverse` 필수**(`adb-fix`), 토큰 불필요
- `azure` — 공유 서버(HTTPS). 토큰 필요
- `wifi` — `192.168.x.y:8000` 공용 개발 서버(#57). 토큰 필요

**안내가 스텁·프로브 응답과 다르면 이 URL부터 의심한다.** `env`가 "로컬 서버인데 reverse 없음"
같은 상태를 짚어 준다.

## 워크트리에서 서버를 띄울 때 — 인터프리터와 코드는 출처가 다르다

`worktree-init`은 `local.properties`만 복사한다. `server\.venv`는 워크트리에 없다(레포마다
가상환경을 뜨는 건 디스크·시간 낭비다). 그래서 `server`는 이렇게 돈다:

| | 어디서 |
|---|---|
| 파이썬 인터프리터·패키지 | **본 레포** `server\.venv` (빌려 씀) |
| 도는 코드 (`app` 패키지) | **이 레포** — 작업 디렉터리가 이 레포의 `server`다 |
| `.env` 값 | 이 레포에 없으면 본 레포 것을 **환경변수로만** 넘긴다(비밀값 파일을 복사하지 않는다) |

**"python 경로 = 코드 경로"가 성립하지 않는다.** 그래서 `server`가 뜰 때 `code=`·`python=`을
매번 찍는다 — 어느 레포 코드가 도는지는 그 줄이 답이다. `config.py`의 `env_file=".env"`가
**cwd 기준**이라 `.env`도 같은 함정이었다.

## 관측은 텍스트 먼저, 픽셀은 마지막

SM-S921N에서 잰 값이다(USB, 5회 중앙값).

| 채널 | 비용 | 무엇에 답하나 |
|---|---|---|
| `log`(델타) | 0.4~0.6초 | 앱이 무엇을 판정했나. debug 빌드는 요소 `label`·`bounds`까지 남긴다 |
| 백그라운드 캡처 | 0.02~0.1초 | 나중에 볼 증거를 남긴다(기기에 적재) |
| `shot`(동기) | 1.1~1.5초 | 지금 당장 픽셀을 봐야 할 때만 |

**캡처 1.3초는 전송이 아니라 기기의 PNG 인코딩이다**(raw 263ms, `pull`은 2.4MB에 106ms).
그래서 왕복을 줄이는 방향은 헛수고다 — 왕복 셋(`screencap`+`pull`+`rm`)을 하나로 합친 `exec-out`이
1485ms로, 합치기 전 1518ms와 사실상 같았다. 답은 **비동기로 미루는 것**이다.
`step`·`tap -Shot`이 그 일을 한다 — 캡처는 기기의 `/sdcard/davi_shots`에 쌓이고 `shots pull`로 회수한다.

**`step`의 순서는 조작 → 대기 → 캡처 → 로그다.** 캡처를 조작 직후에 걸면 인코딩이 1.3초라
저장 프레임이 **전이 전 화면**이 된다. 조용히 한 스텝씩 밀린 증거가 쌓이므로 순서를 바꾸지 말 것.

**한 콜로 묶은 효과**(같은 세션에서 A/B 교대 8스텝):

| | 조작·캡처·로그를 따로(3콜) | `step`(1콜) |
|---|---|---|
| 도구 시간(중앙값, **대기 ms 제외**) | 2753ms | **896ms** |
| 읽은 로그 줄 | 매번 버퍼 전체(43→68→68→68) | 델타(50→2→1→1) |

앱이 쓰는 시간(스텝당 4474ms, 그중 서버 왕복 2608ms)은 도구로 줄지 않는다.

**`log` 델타는 기본 200줄에서 잘린다**(`-Lines`). 잘리면 `(앞 N줄 생략)`을 함께 찍는데,
커서는 이미 마지막 줄로 가 있어 **잘린 앞부분은 다시 안 나온다** — 그 줄이 보이면 `-Full`로 본다.

## 이 스크립트가 안 감싸는 것

- **클린 머신 셋업** — venv·SDK·JDK 설치는 범위 밖
- **맥** — #72
- **접근성 최초 허용** — 상태 판정과 복구는 하지만 처음 켜는 건 사람이 기기에서 한다
  (특수 권한은 접근성 하나다 — `SYSTEM_ALERT_WINDOW`는 #212로 걷혔다)

## Troubleshooting

| 증상 | 처치 |
|---|---|
| `adb server version (40) doesn't match this client (41)` | `adb-fix` |
| 앱 로그 `network_error` (서버는 떠 있음) | reverse 소실 — `adb-fix`. URL이 local인지도 `env`로 확인 |
| 안내가 스텁·프로브와 다름 | 서버 URL 오인 — `url` |
| gradle `SDK location not found` | 워크트리면 `worktree-init`. **처음 clone한 레포면** `app-android/local.properties`에 `sdk.dir=<SDK 경로>`를 직접 쓰거나 Android Studio로 한 번 연다 |
| `venv 없음` (워크트리) | 자동 폴백된다 — 본 레포 venv를 빌려 쓰고 코드는 이 레포다. `server`의 `code=`·`python=` 줄로 확인 |
| 워크트리에서 `-Real`인데 키가 안 먹는 듯 | `.env`는 cwd 기준이라 워크트리엔 없다. 드라이버가 본 레포 `.env`를 환경변수로 넘긴다 |
| 안내가 아예 시작되지 않음 / 화면에 아무것도 안 그려짐 | 접근성 바인딩 죽음 — `install -SkipBuild`. 판정은 `env` |
| `.ps1` 파스 에러(Unexpected token 등) | BOM 없는 UTF-8로 저장됨 — BOM 붙여 재저장 |

adb·기기 쪽 함정은 `app-android/CLAUDE.md`의 「도구 함정」에 모여 있다.
