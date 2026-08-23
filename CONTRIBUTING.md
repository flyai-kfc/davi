# 기여 가이드

이 저장소는 **모노레포**다. 한 저장소에 스택 셋(앱·서버·평가)이 들어 있고, 워크스페이스 도구 없이
폴더 규약과 CI로 묶는다. 방향·범위는 [SPEC.md](SPEC.md), 작업 규칙은 [CLAUDE.md](CLAUDE.md)와
각 폴더의 `CLAUDE.md`에 있다. 이전 PoC의 교훈은 [HANDOFF.md](HANDOFF.md).

**이 저장소는 이번 사이클의 구현을 담는다.** 아직 사이클에 없는 장기 기획(북극성)은 별도 레포
`davi-docs`의 `PLAN.md`에 있고, 거기서 미결이 닫힌 뒤 `SPEC.md`로 승격돼야 여기 이슈가
된다. PLAN은 PR·리뷰를 사람만 쓰는 등 규칙이 달라 저장소를 나눴다.
**화면의 형태는 다른 트랙이다** — Figma Wireframe → `docs/SCREENS.md` → 이슈로 가며 PLAN
카드를 거치지 않는다(`SPEC.md` 머리). 다만 그 화면이 **새로운 제품 결정**을 요구하면 그
결정은 PLAN 카드다.

## 시작하기

**클론 직후 한 번** — main 직접 푸시를 막는 훅을 켠다:

```bash
git config core.hooksPath .githooks
```

브랜치 보호를 걸 수 없는 플랜이라(아래 "저장소 설정") 이게 유일한 실질 방어선이다.

| 폴더 | 준비 |
|---|---|
| `server/` | `py -3.12 -m venv .venv` → `pip install -r requirements-dev.txt` → `copy .env.example .env` ([server/README.md](server/README.md)) |
| `app-android/` | Android Studio로 `app-android/` 폴더를 연다. `local.properties`에 `DAVI_DEBUG_SERVER_URL` — 에뮬레이터 기본(`10.0.2.2`)은 토큰 불필요, 사설 Wi-Fi 또는 `*.azurecontainerapps.io` HTTPS 공유 서버면 `DAVI_DEBUG_ACCESS_TOKEN`도 함께 넣어야 빌드된다 ([app-android/README.md](app-android/README.md)) |
| `eval/` | `pip install -r requirements.txt` → `py -3.12 harness.py` ([eval/README.md](eval/README.md)) |

`reference/` 는 이전 PoC 코드다. **읽기 전용이고 gitignore된다.** 수정·복사·이동하지 않는다.
필요한 구현이 있으면 `HANDOFF.md` §4 인덱스를 보고 **해당 파일만** 참조한 뒤, 새 코드는 해당 스택 폴더에 새로 쓴다.

## 브랜치

- **main에 직접 푸시하지 않는다.** feature 브랜치 → PR → 리뷰 → merge.
- 브랜치명: `<타입>/<범위>-<요약>`
  - 타입: `feat` / `fix` / `docs` / `chore` / `refactor` / `test`
  - 예: `feat/server-completion-verify`, `fix/app-overlay-offset`, `docs/spec-guardrails`

## 커밋

- **영어 범위 접두어 + 한국어 명령형 한 줄** 제목.
  접두어는 폴더·브랜치 범위명과 같게 쓴다 — `app:` / `server:` / `eval:` / `docs:` / `repo:`
  - `server: 완료 검증 스키마 추가`
  - `app: 오버레이 좌표가 상태표시줄만큼 밀리던 문제 수정`
  - `repo: CI에 wrapper 검증 스텝 추가`

  접두어만 영어인 것은 grep·정렬·자동화가 걸리는 자리이기 때문이다. 본문·설명은 한국어 그대로 쓴다.
- 리뷰어가 읽을 것을 전제로 커밋을 작게 나눈다. 왜 그렇게 했는지는 본문에 적는다.
- **비밀값을 커밋하지 않는다.** `.env`, `local.properties`는 gitignore되지만 습관으로 한 번 더 확인한다.
  API 키는 서버 환경변수에만 둔다 — 앱 APK에는 넣지 않는다(SPEC §2·§7).

## PR

- PR 하나는 **스택 하나**를 원칙으로 한다. 앱·서버 동시 변경이 불가피하면 PR 본문에 계약 변경을 명시한다.
- **승인 1명**이 필요하다. 단 **리뷰어는 직접 지정해야 한다** — 무료 private 플랜에서는 CODEOWNERS
  자동 지정이 동작하지 않는다(아래 "저장소 설정" 참고).
- CI가 초록이어야 머지한다. **시스템이 막아주지 않으니 눈으로 확인한다.**
  `skipped`는 **「변경 감지 + 저장소 전역 검사」가 초록일 때만** 정상이다(경로 필터로 안 도는 잡).
  그 잡이 빨간불이면 아래 잡들은 **안 돈 것이지 통과한 게 아니다.** 집계 잡을 지운 뒤로
  이 구분은 사람이 한다.
- **Backend 잡은 PR에서만 돈다.** main 푸시에서는 저장소 전역 검사와 해당되는 Android 잡만
  실행한다(#363). 서버·인프라 변경은 같은 main 푸시의 Azure development deploy가 image 빌드와
  `/health`를 확인하지만 `pytest`·`ruff`는 다시 돌리지 않는다. 무료 private 플랜이라 PR을 최신
  main에 맞추도록 강제할 수 없으므로, 머지 직전 PR의 CI가 최신 커밋에서 초록인지 사람이 확인한다.
- 팀은 5명이지만 스택별로 담당이 나뉘어 있어 실제 리뷰어는 한둘로 좁혀진다.
  리뷰가 밀리면 그대로 정지하므로 **24시간 안에** 본다.
- 템플릿의 체크리스트를 실제로 확인하고 채운다. 안 돌린 검사는 안 돌렸다고 쓴다.

### `server/app/schemas.py`를 건드릴 때
이 파일은 **앱·서버·eval 셋이 동시에 무는 계약**이라 한쪽만 고치면 조용히 깨진다.
바꾸기 전에 `server/CLAUDE.md`의 "API 계약 결정"(확정/보류 목록)을 먼저 읽고,
PR 본문에 바뀐 필드와 세 곳에 미친 영향을 적는다.

HTTP 전송 추적 계약은 `server/app/observability.py`와 Android `RequestTracing.kt`가 함께 문다.
header 이름·UUID·attempt 규칙을 바꾸면 두 파일과 서버 계약 드리프트 테스트, CI 경로 필터를
같이 고치고 JSON 본문 계약 변경 여부를 별도로 적는다.

## 로컬 검사 (푸시 전)

CI와 같은 것을 돌린다.

```bash
cd server && ruff check . && ruff format --check . && pytest
```

앱은 CLI로 빌드·설치한다. `app-android/scripts/dev-install.ps1` 하나로 빌드·설치·접근성 재활성화가
끝난다(`-Status`는 아무것도 바꾸지 않고 상태만 본다). 특수 권한은 접근성 하나다(#212).
**Windows에서 실기기 루프를 자주 돈다면** 그 위에 `scripts/dev-driver.ps1`이 있다 — 서버 기동·
서버 URL 교체·기기 조작·관측을 같은 진입점에서 하고, 판정·설치는 위 스크립트에 위임한다
(`scripts/README.md`). 맥에서는 안 돈다.
Android Studio는 디버거·Layout Inspector가 필요할 때만 쓴다. Gradle Wrapper(`./gradlew`)가 저장소에 있고 CI는 그걸 쓰지만,
개발 PC에 따라 Gradle CLI가 `Unable to establish loopback connection`으로 실패할 수 있다.
원인은 AF_UNIX 소켓 임시 폴더이고 `app-android/scripts/dev-install.ps1`이 이를 흡수한다 —
자세한 건 `app-android/README.md`.

**macOS: 보호 폴더가 통째로 막힐 수 있다.** `ls: Operation not permitted`가 나고
`Documents`·`Desktop`·`Downloads` 아래 전부에 걸린다 — 앱 빌드만이 아니라 `server/`의 pytest도,
eval 픽스처 읽기도 같이 죽는다.

**실측된 것은 여기까지다: 증상, 그리고 그 도구를 재시작하면 풀린다는 것**(2026-08-21).
겪은 자리는 **에이전트 CLI**(`~/Library/Application Support/Claude/claude-code/<버전>/claude.app`)를
통해 돌린 명령들이었다. 셸을 새로 띄우는 것만으로는 안 풀렸다. 개인정보 보호 설정에서 접근을 다시 허용해야 할 수도 있다.

원인은 **확인하지 못했다.** 관측된 정황은 그 실행 파일 경로에 버전이 박혀 있고
업데이트가 그 폴더를 갈아치웠다는 것인데, macOS는 앱을 번들 ID와 코드 서명으로도 식별하므로
"경로가 바뀌어서"가 원인이라고 단정할 수 없다. **1회 관측이다.** 다시 겪으면 그때 좁힌다.

Gradle은 여기에 더해 데몬이 옛 맥락을 물고 사는 문제가 겹친다 — `app-android/README.md` §Gradle.

`gradlew`는 실행 비트와 LF 줄바꿈으로 커밋돼 있다(`.gitattributes`가 강제). Windows에서 이 속성이
깨지면 리눅스 CI에서 실행되지 않으니, wrapper 파일을 건드린 PR은 `git ls-files --stage app-android/gradlew`가
`100755`인지 확인한다.

## 개인정보

시니어 대상 앱이고 화면 내용을 다루므로 이 항목은 형식적인 게 아니다.

- 이슈·PR·커밋에 **실제 개인정보**(연락처 이름, 메시지 본문, 전화번호 등)를 붙여넣지 않는다.
  로그를 공유할 땐 가린다.
- 평가 픽스처는 **반드시 익명화**한 뒤 `eval/fixtures/`에 넣는다.
- 패턴 범위 안의 원본 화면 데이터는 기기를 떠나지 않는다. 패턴 밖 고유명은 가리지 못한다(SPEC §2, #360). 마스킹은 앱에서 1차, 서버에서 2차(SPEC §2·§4).

## 제품 원칙 (코드로 어기지 말 것)

- **가르치기, 대신 해주지 않는다.** `performAction`/`performGlobalAction`으로 사용자 대신 조작하지 않는다.
- **앱별/버전별 지식을 코드에 박지 않는다.** 서버 프롬프트·가드레일에만 둔다.
- **모델에게 지식이 아니라 상태를 준다**(스크롤 가능 여부, 페이지 위치 등).

## 저장소 설정 (관리자용)

### 지금 상태 — 브랜치 보호는 **걸려 있지 않다**

조직이 **GitHub Free**이고 저장소가 **private**이라 브랜치 보호와 룰셋을 쓸 수 없다.
추측이 아니라 API가 그렇게 답한다:

```
GET /repos/flyai-kfc/davi/branches/main/protection
→ 403 "Upgrade to GitHub Pro or make this repository public to enable this feature."
```

**따라서 아래는 전부 사람이 지키는 규약이지, 시스템이 막아주는 게 아니다.**

- `main` 직접 푸시가 **기술적으로는 가능하다.** 하지 않기로 한 것뿐이다.
- **CODEOWNERS도 동작하지 않는다.** 무료 private에서는 코드 소유자 자동 리뷰어 지정이
  제공되지 않는다. `.github/CODEOWNERS`는 플랜을 올렸을 때를 위해 남겨둔 것이다.
- CI는 PR에서 정상 실행되지만 **빨간불이어도 머지를 막지 못한다.** 반드시 눈으로 확인할 것.

로컬 방어선으로 pre-push 훅을 제공한다(아래 "훅 설치"). 각자 한 번 켜면 된다.

### 적용돼 있는 설정 (무료에서도 되는 것)

| 설정 | 값 | 이유 |
|---|---|---|
| Squash merge | 허용 | 히스토리를 PR 단위로 유지 |
| Merge commit / Rebase merge | 금지 | 머지 방식을 하나로 고정 |
| Merge 후 브랜치 삭제 | 켬 | 브랜치 목록이 쌓이지 않게 |
| Update branch 버튼 | 켬 | main 최신화를 UI에서 |

(`allow_auto_merge`는 무료 private에서 켜지지 않는다.)

### 플랜을 올린 뒤 할 것

GitHub Team 이상으로 올리면 `Settings → Rules → Rulesets → New branch ruleset`:

- Target: `Include default branch`
- `Require a pull request before merging` — Required approvals **1**,
  `Dismiss stale approvals when new commits are pushed`, `Require review from Code Owners`
- `Require status checks to pass` + `Require branches to be up to date`
  → **먼저 집계 잡을 되살려야 한다.**
  `Android` / `Backend`는 경로 필터 때문에 변경 없는 쪽이 `skipped`가 되는데,
  **skipped는 필수 체크를 통과시키지 못해 머지가 영구히 막힌다.** 그래서 모든 실행에서 도는
  집계 잡 하나(`ci` / 표시 이름 `CI 통과`)만 필수 체크로 거는 방식을 쓴다.
  그 잡은 **러너 비용 때문에 지워 둔 상태다.** 브랜치 보호를 걸 수 없는 플랜에서는
  아무것도 지키지 않으면서, GitHub이 잡마다 실행 시간을 1분 단위로 올려 청구하는 탓에
  3초짜리 잡이 실행마다 1분씩(주 400분 넘게) 나갔다. 플랜을 올리면 **`ci` 잡 정의만**
  되살리고(#362의 마지막 hunk), 그 다음에 아래 설정을 건드린다.
  **그 커밋을 통째로 revert하지 말 것** — 잡 병합·전역 검사 이동·`timeout-minutes` 같은
  비용 절감분이 함께 날아간다.
- `Block force pushes`, `Restrict deletions`

CLI로도 된다(플랜이 올라간 뒤에만 성공한다):

```bash
gh api -X PUT repos/flyai-kfc/davi/branches/main/protection --input - <<'JSON'
{"required_status_checks":{"strict":true,"contexts":["CI 통과"]},"enforce_admins":true,"required_pull_request_reviews":{"required_approving_review_count":1,"dismiss_stale_reviews":true,"require_code_owner_reviews":true},"restrictions":null,"allow_force_pushes":false,"allow_deletions":false}
JSON
```
