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

## 실행 프로필

`DAVI_ENV`는 서버의 실행 경계를 정한다. 값이 없으면 로컬·테스트 호환을 위해
`development`이며, 배포 환경은 반드시 `production`을 명시한다.

| 설정 | development | production |
|---|---|---|
| `DAVI_USE_STUB_LLM=true` | 허용(기본값, 키 없이 실행) | 기동 실패 |
| `DAVI_USE_STUB_LLM=false` | non-empty `GEMINI_API_KEY` 필수 | non-empty `GEMINI_API_KEY` 필수 |
| `DAVI_DEV_ACCESS_TOKEN` | 선택. LAN 바인딩이면 32자 이상 필수 | 설정 시 기동 실패 |
| `DAVI_BIND_HOST` | 기본 `127.0.0.1`; LAN은 `0.0.0.0` | loopback만 허용 |
| 알 수 없는 `DAVI_ENV` | 기동 실패 | 기동 실패 |

검증은 설정을 import할 때 실행되므로 잘못된 조합은 첫 요청이 아니라 Uvicorn 기동 전에 실패한다.
또한 development의 실제 원격 client 요청은 토큰이 비어 있으면 401로 차단한다. 따라서
`DAVI_BIND_HOST`를 loopback으로 둔 채 `uvicorn --host 0.0.0.0`으로 스크립트를 우회해도
토큰 없는 LAN 요청이 통과하지 않는다.
모델 선택 정책 자체는 바꾸지 않으며, 키와 개발 토큰은 저장소 밖의 환경변수 또는 `.env`에만 둔다.

`/health` 응답도 프로필에 따라 다르다.

development:

```json
{"status":"ok","environment":"development","model":"gemini-3.5-flash-lite","stub_llm":true}
```

production:

```json
{"status":"ok"}
```

## 같은 Wi-Fi 공용 development 서버

전용 맥북과 Android debug 기기가 같은 사설 Wi-Fi에 있을 때만 쓰는 개발 경로다(#57).
인터넷에 공개하지 않으며 production 실행·상시 무인 운영의 대체가 아니다.

### 최초 한 번 준비

1. `server/.env`에 실제 Gemini 설정을 둔다.

   ```dotenv
   DAVI_ENV=development
   DAVI_USE_STUB_LLM=false
   GEMINI_API_KEY=<서버에만 보관>
   DAVI_MODEL=gemini-3.5-flash-lite
   ```

2. 32자 이상 임의 토큰을 만들고 **저장소 밖**에 저장한다.

   ```bash
   mkdir -p ~/.config/davi
   python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
   # 출력값을 아래 파일의 <token> 자리에 직접 붙인다.
   printf 'DAVI_DEV_ACCESS_TOKEN=<token>\n' > ~/.config/davi/shared-dev.env
   chmod 600 ~/.config/davi/shared-dev.env
   ```

   명령 이력에 실제 토큰을 인자로 넣지 않는다. 다른 경로를 쓰려면
   `DAVI_SHARED_DEV_ENV_FILE`로 지정한다.
   일회성 자동 검증에서는 이미 설정된 `DAVI_DEV_ACCESS_TOKEN` 환경변수가 파일보다 우선한다.
   토큰을 명령행 인자로 직접 적지는 않는다.

3. macOS **시스템 설정 → 네트워크 → 방화벽 → 옵션**에서
   `server/.venv/bin/python`의 들어오는 연결을 허용한다. 사설 네트워크에서만 허용하고,
   공유기의 게스트 Wi-Fi/AP isolation이 켜져 있으면 기기끼리 통신할 수 없으므로 해제한다.
4. 시작 스크립트가 출력하는 사설 IPv4를 사용한다. 주소가 자주 바뀌면 공유기 관리 화면에서
   맥북의 Wi-Fi MAC 주소에 DHCP 예약을 설정한다. 공유기마다 메뉴 이름은
   `DHCP 예약`, `고정 할당`, `Address Reservation` 등으로 다르다.

### 매일 아침 → 저녁

```bash
cd server
./scripts/start-shared-dev.sh
./scripts/status-shared-dev.sh
```

시작 명령은 `.env`, Python 3.12 venv, 실제 LLM, 외부 토큰 파일 권한, 포트 충돌과 사설 IPv4를
검사한 뒤 worker 하나를 `0.0.0.0:8000`에 띄운다. 출력된 값을 각 개발자의
`app-android/local.properties`에 넣고 debug APK를 다시 빌드한다.

```properties
DAVI_DEBUG_SERVER_URL=http://192.168.x.y:8000
DAVI_DEBUG_ACCESS_TOKEN=<shared-dev.env와 같은 값>
```

저녁에는 반드시 해당 스크립트가 기록한 PID만 종료한다. `pkill`은 쓰지 않는다.

```bash
cd server
./scripts/stop-shared-dev.sh
```

PID와 로그는 `server/.shared-dev/` 아래에만 생기며 gitignore된다. 디렉터리는 700,
PID·로그 파일은 600이다. 로그는 일 단위로 이어 쓰고 시작 시 7일보다 오래된 파일을 정리한다.
요청 로그에는 request ID, 패키지명, 요소 개수, 클라이언트 버전, HTTP 상태와 처리 시간만 남긴다.
목표·화면 label·최근 안내·요청 본문·API 키·개발 토큰은 기록하지 않는다.

### 장애 확인 순서

1. `./scripts/status-shared-dev.sh` — PID와 local health
2. `curl http://127.0.0.1:8000/health` — `stub_llm:false` 확인
3. 기기와 맥북이 같은 Wi-Fi인지, 게스트 네트워크/AP isolation이 아닌지 확인
4. macOS 방화벽에서 `.venv/bin/python` 수신 허용 확인
5. 앱을 다시 빌드했는지와 URL의 현재 사설 IPv4 확인
6. HTTP 401이면 토큰 불일치, 429이면 `Retry-After`만큼 기다린다
7. `server/.shared-dev/logs/<오늘>.log`에서 비식별 request ID와 상태 코드만 확인

## 검사

```bash
ruff check . && ruff format --check .
pytest
```

## 구조

| 파일 | 역할 |
|---|---|
| `app/main.py` | FastAPI 라우터(얇게). 에러 분류만. |
| `app/errors.py` | 추론 오류 → HTTP 상태·응답·재시도 헤더 변환. |
| `app/schemas.py` | 앱↔서버 계약. 근거 필드가 action보다 앞(추론 순서). |
| `app/reasoning.py` | 계획→실행→검증 파이프라인. **HTTP 모름** → eval이 직접 import. |
| `app/masking.py` | 2차 마스킹 그물. 순수 변환, 저장·로깅 안 함. |
| `app/prompt.py` | 마스킹된 요청 → LLM 프롬프트. HTTP 없이 검증 가능한 순수 직렬화. |
| `app/llm.py` | 스텁/Gemini 클라이언트. HTTP 호출·스키마 400 폴백·응답 파싱. |
| `app/gemini_schema.py` | 모델 선언 → Gemini responseSchema(`propertyOrdering` 자동 생성). |
| `app/config.py` | 실행 프로필·환경변수와 fail-fast 검증. |

## 지금 무엇이 진짜인가

**동작하는 구현** (테스트로 덮인 것 포함)
- `app/masking.py` — 이메일·주민번호·카드·휴대폰·계좌 5종 정규식 치환. 비파괴. `tests/test_masking.py` 3건.
- `app/schemas.py` — 계약 모델 전체. C1~C8 반영 완료, `tests/test_contract.py`가 고정한다.
- `app/gemini_schema.py` — 모델 선언 → Gemini `responseSchema`. `propertyOrdering`(추론 순서)과
  `required`를 자동 생성하고, 빈 문자열 enum을 배포 전에 막는다.
- `app/main.py` + `app/errors.py` — `/health`, `/v1/next-step` 라우팅과 `LlmError` → HTTP 상태·응답·재시도 헤더 매핑.
- `/v1/next-step` 요청 로깅 — request ID·패키지명·요소 개수·클라이언트 버전·HTTP 상태·
  처리 시간만 한 줄로 기록한다. 사용자 입력인 목표·화면 `label`·최근 안내와 token은 기록하지 않는다.
- `app/reasoning.py:next_step` — 파이프라인 배선(2차 마스킹 → 추론 → 가드 → 검증)은 실제로 흐른다. 모델을 dict로 덤프·재검증하지 않고 비파괴 복사로 마스킹한다.
- `app/llm.py:StubLlmClient` — 규칙 기반 선택(tap → scroll → 막히면 LlmError). **LLM 품질을 대변하지 않는다.**
- `app/prompt.py` + `app/llm.py:GeminiLlmClient` — 프롬프트 구성과 Gemini REST `generateContent` 호출을 분리한다. 목표·화면 요소·최근 안내 이력으로
  프롬프트를 만들고, `GUIDE_ACTION_SCHEMA` 구조화 출력으로 `GuideAction`을 검증한다. 스키마 400이면
  JSON 계약을 프롬프트로 옮겨 무스키마 호출을 한 번 재시도한다.
  HTTP 목 테스트는 성공·400 폴백·429·인증·네트워크·서버·파싱 오류를 덮는다.
- Gemini 실호출 스모크(2026-07-27) — `.env`의 `gemini-3.5-flash-lite`로 마스킹된 샘플 화면을 호출해
  `tap`, `target_node_id=n1` 구조화 응답을 받았다. 이 모델 ID가 응답한다는 확인일 뿐, 모델 확정은 평가 체계가 선 뒤에 한다.
- `app/config.py` — development/production 실행 프로필과 기동 전 설정 검증.
  `should_store_raw()`는 동작하지만 **아직 호출하는 곳이 없다**(로그 저장 미구현).

**최소만 구현** (동작하지만 의도적으로 얕음)
- `reasoning._verify_done` — 근거 필드가 비었는지만 본다. evidence·label 매칭 검사는 미정(SPEC §11).

**껍데기** (호출하면 아무 일도 없거나 raise)
- `reasoning._plan` — 목표를 그대로 1개 태스크로 돌려준다. 하위 태스크 분해 없음.
- `reasoning._apply_guardrails` — action을 그대로 통과시킨다. 가드 0개(HANDOFF §5-4 통합 지점만 잡아둠).
