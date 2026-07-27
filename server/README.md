# server — davi 추론 오케스트레이터

FastAPI. [마스킹된 화면 + 목표] → 다음 안내 하나. 전용 규칙은 `CLAUDE.md`, 방향은 `../SPEC.md`.

## 빠른 시작 (Python 3.12 고정)

macOS/Linux:

```bash
python3.12 -m venv .venv
source .venv/bin/activate
python3.12 -m pip install -r requirements-dev.txt
cp .env.example .env
uvicorn app.main:app --reload
```

Windows:

```bash
py -3.12 -m venv .venv
.venv\Scripts\activate            # PowerShell:  .venv\Scripts\Activate.ps1
py -3.12 -m pip install -r requirements-dev.txt
copy .env.example .env            # 키는 나중에. 지금은 DAVI_USE_STUB_LLM=true 로 뜬다.
uvicorn app.main:app --reload
```

- 헬스체크: `GET http://127.0.0.1:8000/health`
- 다음 안내: `POST /v1/next-step` (요청/응답 스키마는 `app/schemas.py`)

## 실기기 접속용 터널

이번 사이클은 로컬 서버를 Cloudflare Quick Tunnel로 외부에 노출한다(`../SPEC.md` §7).
먼저 위 절차로 서버를 `127.0.0.1:8000`에 실행하고, 다른 터미널에서 다음을 실행한다.

macOS(Homebrew):

```bash
brew install cloudflared
cloudflared tunnel --url http://127.0.0.1:8000
```

출력된 `https://<랜덤>.trycloudflare.com` 주소로 다음을 확인한다.

1. 실기기 브라우저에서 `<터널 URL>/health`를 열어 `{"status":"ok", ...}` 응답을 확인한다.
2. `app-android/local.properties`의 `DAVI_SERVER_URL`에 터널 URL을 넣고 앱을 다시 빌드한다.
3. 앱에서 요청을 보내 서버 콘솔의 `next_step` 로그 한 줄을 확인한다.

```properties
DAVI_SERVER_URL=https://<랜덤>.trycloudflare.com
```

요청 로그에는 `package_name`, 요소 개수, `goal`, `client_version`, HTTP 상태,
처리 시간만 기록한다. 화면 요소의 `label` 원문은 기록하지 않는다.
Quick Tunnel URL은 실행할 때마다 바뀔 수 있으므로 재실행 후 앱 설정도 갱신한다.

## 검사

```bash
ruff check . && ruff format --check .
pytest
```

## 구조

| 파일 | 역할 |
|---|---|
| `app/main.py` | FastAPI 라우터(얇게). 에러 분류만. |
| `app/schemas.py` | 앱↔서버 계약. 근거 필드가 action보다 앞(추론 순서). |
| `app/reasoning.py` | 계획→실행→검증 파이프라인. **HTTP 모름** → eval이 직접 import. |
| `app/masking.py` | 2차 마스킹 그물. 순수 변환, 저장·로깅 안 함. |
| `app/llm.py` | 스텁/Gemini 클라이언트. 프롬프트·스키마 400 폴백·에러 분류. |
| `app/gemini_schema.py` | 모델 선언 → Gemini responseSchema(`propertyOrdering` 자동 생성). |
| `app/config.py` | 환경변수. 모델명·도그푸딩 기기 화이트리스트. |

## 지금 무엇이 진짜인가

**동작하는 구현** (테스트로 덮인 것 포함)
- `app/masking.py` — 이메일·주민번호·카드·휴대폰·계좌 5종 정규식 치환. 비파괴. `tests/test_masking.py` 3건.
- `app/schemas.py` — 계약 모델 전체. C1~C8 반영 완료, `tests/test_contract.py`가 고정한다.
- `app/gemini_schema.py` — 모델 선언 → Gemini `responseSchema`. `propertyOrdering`(추론 순서)과
  `required`를 자동 생성하고, 빈 문자열 enum을 배포 전에 막는다.
- `app/main.py` — `/health`, `/v1/next-step` 라우팅과 `LlmError` → HTTP 상태 매핑.
- `/v1/next-step` 요청 로깅 — 패키지명·요소 개수·목표·클라이언트 버전·처리 시간만
  한 줄로 기록한다. 화면 `label` 원문은 기록하지 않는다.
- `app/reasoning.py:next_step` — 파이프라인 배선(2차 마스킹 → 추론 → 가드 → 검증)은 실제로 흐른다.
- `app/llm.py:StubLlmClient` — 규칙 기반 선택(tap → scroll → 막히면 LlmError). **LLM 품질을 대변하지 않는다.**
- `app/llm.py:GeminiLlmClient` — Gemini REST `generateContent`를 호출한다. 목표·화면 요소·최근 안내 이력으로
  프롬프트를 만들고, `GUIDE_ACTION_SCHEMA` 구조화 출력으로 `GuideAction`을 검증한다. 스키마 400이면
  JSON 계약을 프롬프트로 옮겨 무스키마 호출을 한 번 재시도한다.
  HTTP 목 테스트는 성공·400 폴백·429·인증·네트워크·서버·파싱 오류를 덮는다.
- Gemini 실호출 스모크(2026-07-27) — 마스킹된 샘플 화면에서 `tap`, `target_node_id=n1` 구조화 응답을 받았다.
- `app/config.py` — 환경변수 로딩. `should_store_raw()`는 동작하지만 **아직 호출하는 곳이 없다**(로그 저장 미구현).

**최소만 구현** (동작하지만 의도적으로 얕음)
- `reasoning._verify_done` — 근거 필드가 비었는지만 본다. evidence·label 매칭 검사는 미정(SPEC §11).

**껍데기** (호출하면 아무 일도 없거나 raise)
- `reasoning._plan` — 목표를 그대로 1개 태스크로 돌려준다. 하위 태스크 분해 없음.
- `reasoning._apply_guardrails` — action을 그대로 통과시킨다. 가드 0개(HANDOFF §5-4 통합 지점만 잡아둠).
