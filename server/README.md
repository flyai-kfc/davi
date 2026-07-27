# server — davi 추론 오케스트레이터

FastAPI. [마스킹된 화면 + 목표] → 다음 안내 하나. 전용 규칙은 `CLAUDE.md`, 방향은 `../SPEC.md`.

## 빠른 시작 (Python 3.12 고정 — `py -3.12`)

```bash
py -3.12 -m venv .venv
.venv\Scripts\activate            # PowerShell:  .venv\Scripts\Activate.ps1
py -3.12 -m pip install -r requirements-dev.txt
copy .env.example .env            # 키는 나중에. 지금은 DAVI_USE_STUB_LLM=true 로 뜬다.
uvicorn app.main:app --reload
```

- 헬스체크: `GET http://127.0.0.1:8000/health`
- 다음 안내: `POST /v1/next-step` (요청/응답 스키마는 `app/schemas.py`)

실기기 접속은 터널로(`../SPEC.md` §7):

```bash
# 예: cloudflared tunnel --url http://localhost:8000   또는   ngrok http 8000
```

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
| `app/llm.py` | 스텁/Gemini 클라이언트. 스키마 400 폴백·에러 분류 자리. |
| `app/gemini_schema.py` | 모델 선언 → Gemini responseSchema(`propertyOrdering` 자동 생성). |
| `app/config.py` | 환경변수. 모델명·도그푸딩 기기 화이트리스트. |

## 지금 무엇이 진짜인가

**동작하는 구현** (테스트로 덮인 것 포함)
- `app/masking.py` — 이메일·주민번호·카드·휴대폰·계좌 5종 정규식 치환. 비파괴. `tests/test_masking.py` 3건.
- `app/schemas.py` — 계약 모델 전체. C1~C8 반영 완료, `tests/test_contract.py`가 고정한다.
- `app/gemini_schema.py` — 모델 선언 → Gemini `responseSchema`. `propertyOrdering`(추론 순서)과
  `required`를 자동 생성하고, 빈 문자열 enum을 배포 전에 막는다.
- `app/main.py` — `/health`, `/v1/next-step` 라우팅과 `LlmError` → HTTP 상태 매핑.
- `app/reasoning.py:next_step` — 파이프라인 배선(2차 마스킹 → 추론 → 가드 → 검증)은 실제로 흐른다.
- `app/llm.py:StubLlmClient` — 규칙 기반 선택(tap → scroll → 막히면 LlmError). **LLM 품질을 대변하지 않는다.**
- `app/config.py` — 환경변수 로딩. `should_store_raw()`는 동작하지만 **아직 호출하는 곳이 없다**(로그 저장 미구현).

**최소만 구현** (동작하지만 의도적으로 얕음)
- `reasoning._verify_done` — 근거 필드가 비었는지만 본다. evidence·label 매칭 검사는 미정(SPEC §11).

**껍데기** (호출하면 아무 일도 없거나 raise)
- `llm.GeminiLlmClient.reason` — `raise`만 한다. 실제 Gemini 호출·스키마 강제·400 폴백 전부 미구현.
  → 이것 때문에 `propertyOrdering` 약속이 아직 코드와 연결돼 있지 않다(`CLAUDE.md` C1).
- `reasoning._plan` — 목표를 그대로 1개 태스크로 돌려준다. 하위 태스크 분해 없음.
- `reasoning._apply_guardrails` — action을 그대로 통과시킨다. 가드 0개(HANDOFF §5-4 통합 지점만 잡아둠).
