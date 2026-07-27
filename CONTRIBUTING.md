# 기여 가이드

이 저장소는 **모노레포**다. 한 저장소에 스택 셋(앱·서버·평가)이 들어 있고, 워크스페이스 도구 없이
폴더 규약과 CI로 묶는다. 방향·범위는 [SPEC.md](SPEC.md), 작업 규칙은 [CLAUDE.md](CLAUDE.md)와
각 폴더의 `CLAUDE.md`에 있다. 이전 PoC의 교훈은 [HANDOFF.md](HANDOFF.md).

## 시작하기

| 폴더 | 준비 |
|---|---|
| `server/` | `py -3.12 -m venv .venv` → `pip install -r requirements-dev.txt` → `copy .env.example .env` ([server/README.md](server/README.md)) |
| `app-android/` | Android Studio로 `app-android/` 폴더를 연다. `local.properties`에 `DAVI_SERVER_URL` ([app-android/README.md](app-android/README.md)) |
| `eval/` | `pip install -r requirements.txt` → `py -3.12 harness.py` ([eval/README.md](eval/README.md)) |

`reference/` 는 이전 PoC 코드다. **읽기 전용이고 gitignore된다.** 수정·복사·이동하지 않는다.
필요한 구현이 있으면 `HANDOFF.md` §4 인덱스를 보고 **해당 파일만** 참조한 뒤, 새 코드는 해당 스택 폴더에 새로 쓴다.

## 브랜치

- **main에 직접 푸시하지 않는다.** feature 브랜치 → PR → 리뷰 → merge.
- 브랜치명: `<타입>/<범위>-<요약>`
  - 타입: `feat` / `fix` / `docs` / `chore` / `refactor` / `test`
  - 예: `feat/server-completion-verify`, `fix/app-overlay-offset`, `docs/spec-guardrails`

## 커밋

- 한국어 명령형 한 줄 제목. **범위 접두어**를 붙인다 — `서버:` / `앱:` / `평가:` / `문서:` / `저장소:`
  - `서버: 완료 검증 스키마 추가`
  - `앱: 오버레이 좌표가 상태표시줄만큼 밀리던 문제 수정`
- 리뷰어가 읽을 것을 전제로 커밋을 작게 나눈다. 왜 그렇게 했는지는 본문에 적는다.
- **비밀값을 커밋하지 않는다.** `.env`, `local.properties`는 gitignore되지만 습관으로 한 번 더 확인한다.
  API 키는 서버 환경변수에만 둔다 — 앱 APK에는 넣지 않는다(SPEC §2·§7).

## PR

- PR 하나는 **스택 하나**를 원칙으로 한다. 앱·서버 동시 변경이 불가피하면 PR 본문에 계약 변경을 명시한다.
- **승인 1명**이 필요하다. CODEOWNERS(`@flyai-kfc/dev`)가 리뷰어로 자동 지정된다.
- CI(`CI 통과` 체크)가 초록이어야 머지한다.
- 템플릿의 체크리스트를 실제로 확인하고 채운다. 안 돌린 검사는 안 돌렸다고 쓴다.

### `server/app/schemas.py`를 건드릴 때
이 파일은 **앱·서버·eval 셋이 동시에 무는 계약**이라 한쪽만 고치면 조용히 깨진다.
바꾸기 전에 `server/CLAUDE.md`의 "API 계약 결정"(확정/보류 목록)을 먼저 읽고,
PR 본문에 바뀐 필드와 세 곳에 미친 영향을 적는다.

## 로컬 검사 (푸시 전)

CI와 같은 것을 돌린다.

```bash
cd server && ruff check . && ruff format --check . && pytest
```

앱은 **Android Studio에서 빌드·실행한다.** Gradle Wrapper(`./gradlew`)가 저장소에 있고 CI는 그걸 쓰지만,
개발 PC에 따라 Gradle CLI가 loopback 연결을 못 열어 실패할 수 있다(HANDOFF §3-15). 그 경우 Studio를 쓴다.

`gradlew`는 실행 비트와 LF 줄바꿈으로 커밋돼 있다(`.gitattributes`가 강제). Windows에서 이 속성이
깨지면 리눅스 CI에서 실행되지 않으니, wrapper 파일을 건드린 PR은 `git ls-files --stage app-android/gradlew`가
`100755`인지 확인한다.

## 개인정보

시니어 대상 앱이고 화면 내용을 다루므로 이 항목은 형식적인 게 아니다.

- 이슈·PR·커밋에 **실제 개인정보**(연락처 이름, 메시지 본문, 전화번호 등)를 붙여넣지 않는다.
  로그를 공유할 땐 가린다.
- 평가 픽스처는 **반드시 익명화**한 뒤 `eval/fixtures/`에 넣는다.
- 원본 화면 데이터는 기기를 떠나지 않는다. 마스킹은 앱에서 1차, 서버에서 2차(SPEC §2·§4).

## 제품 원칙 (코드로 어기지 말 것)

- **가르치기, 대신 해주지 않는다.** `performAction`/`performGlobalAction`으로 사용자 대신 조작하지 않는다.
- **앱별/버전별 지식을 코드에 박지 않는다.** 서버 프롬프트·가드레일에만 둔다.
- **모델에게 지식이 아니라 상태를 준다**(스크롤 가능 여부, 페이지 위치 등).

## 저장소 설정 (관리자용)

GitHub에 올린 뒤 `Settings → Branches`에서 main 보호 규칙:

- Require a pull request before merging — **Require approvals: 1**
- Require review from Code Owners
- Require status checks to pass — **`CI 통과`** 를 필수 체크로 지정
  (`Android` / `Backend` 잡은 변경이 없으면 skipped가 되므로 필수로 걸지 않는다. `CI 통과`가 이를 집계한다.)
- Do not allow bypassing the above settings
