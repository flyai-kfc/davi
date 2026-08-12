# server — davi 추론 오케스트레이터

FastAPI. [마스킹된 화면 + 목표] → 다음 안내 하나. 전용 규칙은 `CLAUDE.md`, 방향은 `../SPEC.md`.

## 빠른 시작 (Python 3.12 고정)

macOS/Linux:

```bash
python3.12 -m venv .venv
source .venv/bin/activate
python3.12 -m pip install -r requirements-dev.txt
cp .env.example .env
uvicorn app.main:app --reload --no-access-log --log-config app/logging.json
```

Windows:

```bash
py -3.12 -m venv .venv
.venv\Scripts\activate            # PowerShell:  .venv\Scripts\Activate.ps1
py -3.12 -m pip install -r requirements-dev.txt
copy .env.example .env            # 키는 나중에. 지금은 DAVI_USE_STUB_LLM=true 로 뜬다.
uvicorn app.main:app --reload --no-access-log --log-config app/logging.json
```

- 헬스체크: `GET http://127.0.0.1:8000/health`
- 다음 안내: `POST /v1/next-step` (요청/응답 스키마는 `app/schemas.py`)
- 음성 목표 정리: `POST /v1/intent/slot-fill` (고정 슬롯 없이 질문 또는 최종 goal 반환)

ASGI에 도달한 응답에는 서버가 HTTP 전송마다 만든 32자리 비식별 `X-Request-ID`가 붙는다.
Android는 `/v1/next-step`에 무작위 UUID `X-Davi-Logical-Request-ID`와 1부터 증가하는
`X-Davi-Attempt`를 함께 보낸다. 서버는 둘 다 없으면 이전 APK로 보고 허용하고, 정확히 한 쌍의
유효한 값만 echo한다. 한쪽 누락·중복·형식 오류는 `400 BAD_REQUEST`이며 검증 전 값은 반사하지 않는다.
JSON 요청·응답 본문 계약에는 이 전송 메타데이터를 추가하지 않는다.

같은 화면·세션의 network/timeout 및 retryable 429/502 재전송만 Logical Request ID를 유지한다.
화면·목표·세션 변경과 정상 응답 뒤의 새 추론은 새 ID다. #81은 검증·echo·로그 연결만 하며,
같은 ID의 dedupe·결과 cache·timeout budget은 #82 범위다.
Android 같은 네이티브·직접 HTTP 클라이언트는 헤더를 읽을 수 있다. 현재 서버는 브라우저
origin을 허용하는 CORS 계약이 없으므로 웹 클라이언트에서 읽을 수 있다고 가정하지 않는다.
향후 웹 지원을 추가할 때 허용 origin과 함께 `X-Request-ID`를 명시적으로 expose해야 한다.

## 음성 목표 대화 계약

`/v1/intent/slot-fill`은 PR #88의 엔드포인트 모양만 참고하고 규칙 기반 슬롯 분류는 이식하지
않았다. `conversation_history`에는 **이전에 끝난** user/assistant 쌍만 오래된 순으로 넣고,
`utterance`가 이번 요청의 현재 사용자 발화다. 이력은 최대 10개 메시지(5왕복)이며
`session_id`는 무작위 UUID다.

```json
{
  "utterance": "사진을 보내고 싶어",
  "session_id": "2c8a6a21-0d5a-4868-a394-694684f494df",
  "conversation_history": []
}
```

모델이 정보가 더 필요하다고 판단하면 한 질문만 돌려준다.

```json
{
  "status": "needs_clarification",
  "message": "사진을 어디로 보내고 싶으세요?",
  "goal": null
}
```

충분하면 기존 `/v1/next-step`의 `goal`로 바로 쓸 정규화된 목표를 돌려준다.

```json
{
  "status": "ready",
  "message": "보호자에게 메신저로 사진 보내기로 정리했어요.",
  "goal": "메신저에서 보호자에게 사진 보내기"
}
```

발화와 이전 턴은 의미 보존을 위해 Gemini에 전달되지만 서버 운영 로그와 도그푸딩 raw 채널에는
저장하지 않는다. `session_id`는 모델 입력에도 넣지 않는다. 키 없는 개발·CI에서는
`StubIntentResolver`가 현재 발화를 그대로 ready goal로 돌려 계약만 검증하며 의미 품질을 주장하지 않는다.
두 trace header와 development auth/rate limit, `ApiError`는 `/v1/next-step`과 같은 경계를 쓴다.

Gemini는 내부 전용 `decision_basis`를 먼저 생성한 뒤 status를 결정한다. 이 근거는 wire 응답과
운영 로그에 포함하지 않는다. `needs_clarification`에 goal이 함께 온 경우 유효한 질문만 남기고 goal을
제거한다. ready goal 누락·공백 또는 텍스트 상한 위반은 이전 모델 원문 없이 고정 진단만 덧붙여 한 번
교정한다. schema 400 폴백을 포함해 공급자 호출은 요청당 최대 2회이며, 그 뒤에는 `PARSE`로 실패한다.

## 실행 프로필

`DAVI_ENV`는 서버의 실행 경계를 정한다. 값이 없으면 로컬·테스트 호환을 위해
`development`다. 제품 배포는 반드시 `production`을 명시하고, #94 Azure 공유 서버는 제품 배포가
아니므로 개발 토큰과 rate limit을 쓰는 `development`를 유지한다.

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

## 다음 안내의 입력 준비 상태

`/v1/next-step` 화면 계약은 입력 원문 없이 다음 세 상태를 받을 수 있다(#112).

```json
{
  "goal": "검색창에 날씨 입력하기",
  "screen": {
    "package_name": "com.example.app",
    "ime_visible": true,
    "elements": [
      {
        "node_id": "n1",
        "label": "검색어 입력",
        "role": "EditText",
        "clickable": true,
        "editable": true,
        "focused": true
      }
    ]
  }
}
```

`editable=true`와 `focused=true`는 사용자가 해당 입력 대상을 선택했다는 관측값이지, 추가 입력이
필요하다는 뜻은 아니다. 프롬프트는 label 있는 비편집 `clickable=true` 요소가 함께 있으면 먼저
목표에 맞는 검색 결과나 후속 UI인지 판단한다. 바로 조작할 목표 요소라면 키보드와 포커스가
유지돼도 그 요소의 `tap`을 `type`보다 우선한다. 그런 요소가 없고 입력이 더 필요한 경우에만
`ime_visible=true`에서 같은 칸의 재탭보다 그 요소를 대상으로 한 `type`을 우선한다. `false`이면
키보드가 닫힌 관측이므로 필요한 경우 입력칸을 탭해 키보드를 여는 한 단계를 먼저 안내한다.
`null`은 키보드 상태 미관측이며 같은 우선순위를 적용한다. 포커스·IME 어느 쪽도 입력 완료·목표
완료로 해석하지 않는다. 낡은 앱이 `editable`·`focused`를 생략하면 `false`, `ime_visible`을
생략하거나 interactive window를 관측하지 못하면 `null`로 받는다. 앱은 편집 요소의 입력된
`text`와 IME 키 트리를 요청에 넣지 않는다.

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
PID·로그 파일은 600이다. 로그는 일 단위 JSON Lines로 이어 쓰고 시작 시 7일보다 오래된 파일을
정리한다. 정상 추론은 `request_received` → `gemini_attempt_started` →
`gemini_attempt_finished` → `request_finished`로 이어지고 request ID·Logical Request ID·attempt로
대조한다. 정상 `/health`와 기본 Uvicorn access log는 생략해 반복 I/O와 IP/port 보존을 막는다.
목표·화면 label·최근 안내·요청 본문·프롬프트·Gemini 응답·API 키·개발 토큰은 기록하지 않는다.

### 장애 확인 순서

1. `./scripts/status-shared-dev.sh` — PID와 local health
2. `curl http://127.0.0.1:8000/health` — `stub_llm:false` 확인
3. 기기와 맥북이 같은 Wi-Fi인지, 게스트 네트워크/AP isolation이 아닌지 확인
4. macOS 방화벽에서 `.venv/bin/python` 수신 허용 확인
5. 앱을 다시 빌드했는지와 URL의 현재 사설 IPv4 확인
6. HTTP 401이면 토큰 불일치, 429이면 `Retry-After`만큼 기다린다
7. `server/.shared-dev/logs/<오늘>.log`에서 Logical Request ID로 앱의 attempt와 Gemini 단계를 대조

## Azure Container Apps 공유 development 서버

같은 Wi-Fi·전용 맥북 없이 팀이 같은 `main` 서버를 검증할 때만 쓰는 **개발 전용** 경로다(#94).
GitHub Actions가 `main`에 병합된 서버·인프라 변경을 ACR image와 단일 Container Apps revision으로
배포한다. Docker만 Linux/Python 3.12에서 고정한 `requirements.lock.txt`와 digest 고정 base image를
사용하며, 로컬 개발·테스트는 계속 `requirements-dev.txt`/`requirements.txt`를 쓴다. 배포 뒤에는
이번 run의 정확한 revision과 image가 active·healthy·traffic 100%인지 대조한 다음 `/health`를
검사하므로 이전 revision의 정상 응답으로 성공하지 않는다. PR은 공유 서버를 바꾸지 않는다.

외부 ingress는 HTTPS만 허용하고 API는 32자 이상 개발 토큰을 요구한다. Gemini 키와 개발 토큰은
Key Vault의 versionless secret으로만 보관하며 Container App의 user-assigned identity가 읽는다.
앱에는 Azure URL과 개발 토큰만 debug 설정으로 전달한다. Azure의 도그푸딩 allowlist는 비워 raw
화면 데이터를 저장하지 않는다. 최초 권한·GitHub OIDC 연결과 장애 확인은
[`../docs/development/azure-dev-server.md`](../docs/development/azure-dev-server.md)를 따른다.

## 도그푸딩 raw 보존 (#41)

`DAVI_DOGFOOD_DEVICE_IDS`(콤마 구분)에 등록된 기기의 `/v1/next-step` 왕복만
`DAVI_DOGFOOD_RAW_DIR`(기본 `server/.dogfood-raw/`, gitignore)에 요청+응답 봉투 JSON으로
저장한다. **영구 보존**이며 자동 삭제가 없다 — 정리는 수동. 디렉터리는 0700, 파일은 0600으로
생성한다(접근 범위 = 각 머신 소유자, SPEC §3). **LLM 단계의 실패 응답**(`LlmError` →
4xx/5xx `ApiError`)도 저장한다 — 실패 화면이 픽스처의 주 수집 대상이다. 미들웨어에서 끊기는
401/429는 엔드포인트에 도달하지 않아 저장되지 않는다. 운영 로그와 **별개 채널**이라 요청
로그의 비식별 원칙은 그대로다(CLAUDE.md 실행 절).

- 등록 값은 **옵트인 debug 빌드**(`local.properties`에 `DAVI_DOGFOOD=true`)가 계산하는
  `ANDROID_ID`의 SHA-256 앞 16자다. 접근성 서비스 연결 시 `adb logcat -s GuideSvc`의
  `dogfoodDeviceId=`로 찍힌다. `adb shell settings get secure android_id`는 셸 스코프
  값이라(Android 8+ 서명키별) 이 값을 얻을 수 없다.
- 다른 debug 키스토어로 빌드하면 해시가 바뀐다. 화이트리스트가 리스트이니 새 값을 추가하면 된다.
- 개인 기기는 옵트인하지도 등록하지도 않는다 — 공기계(SM-S921N)만(SPEC §3). 옵트인 없는
  빌드와 release는 값을 계산하지도 보내지도 않는다.

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
| `app/intent_prompt.py` | 이전 턴+현재 발화 → 고정 슬롯 없는 음성 목표 프롬프트. |
| `app/intent_resolution.py` | 음성 목표 스텁/Gemini 경계, 구조화 질문·goal 검증. |
| `app/observability.py` | 비식별 요청 문맥·고정 이벤트·안전한 JSON Lines formatter. |
| `app/logging.json` | Uvicorn access log를 끄고 서버 진단을 JSON Lines로 통합하는 설정. |
| `app/gemini_schema.py` | 모델 선언 → Gemini responseSchema(`propertyOrdering` 자동 생성). |
| `app/config.py` | 실행 프로필·환경변수와 fail-fast 검증. |

## 지금 무엇이 진짜인가

**동작하는 구현** (테스트로 덮인 것 포함)
- `app/masking.py` — 이메일·주민번호·카드·휴대폰·계좌 5종 정규식 치환. 비파괴.
  dict/model 입력은 같은 텍스트 필드 순회 규칙을 사용한다.
- `app/schemas.py` — 다음 안내와 음성 목표 대화 계약. C1~C14를 계약 테스트가 고정한다.
- `app/gemini_schema.py` — 모델 선언 → Gemini `responseSchema`. `propertyOrdering`(추론 순서)과
  `required`를 자동 생성하고, 빈 문자열 enum을 배포 전에 막는다.
- `app/main.py` + `app/errors.py` — `/health`, `/v1/next-step`, `/v1/intent/slot-fill` 라우팅과 `LlmError` → HTTP 상태·응답·재시도 헤더 매핑.
- 요청 관측(#81) — 모든 ASGI 응답의 request ID, Android Logical Request ID/attempt와 Gemini 호출
  단계를 비식별 JSON Lines로 연결한다. 401·422·429·502·500·404는 고정 오류 코드만 남기고,
  정상 `/health`, IP/port, 사용자 입력과 token은 기록하지 않는다.
- `app/reasoning.py:next_step` — 파이프라인 배선(2차 마스킹 → 추론 → 가드 → 검증)은 실제로 흐른다. 모델을 dict로 덤프·재검증하지 않고 비파괴 복사로 마스킹한다.
- `app/llm.py:StubLlmClient` — 규칙 기반 선택(tap → scroll → 막히면 LlmError). **LLM 품질을 대변하지 않는다.**
- `app/prompt.py` + `app/llm.py:GeminiLlmClient` — 프롬프트 구성과 Gemini REST `generateContent` 호출을 분리한다. 목표·화면 요소·최근 안내 이력으로
  프롬프트를 만들고, `GUIDE_ACTION_SCHEMA` 구조화 출력으로 `GuideAction`을 검증한다. 스키마 400이면
  JSON 계약을 프롬프트로 옮겨 무스키마 호출을 한 번 재시도한다.
  HTTP 목 테스트는 성공·400 폴백·429·인증·네트워크·서버·파싱 오류를 덮는다.
- `app/intent_prompt.py` + `app/intent_resolution.py:GeminiIntentResolver` — 완결된 이전 턴과 현재
  발화를 분리해 직렬화하고 `needs_clarification`/`ready` 구조화 출력으로 질문 또는 goal을 만든다.
  내부 판단 근거를 status보다 먼저 생성하되 wire·로그에는 버리고, 안전한 goal 제거와 총 2회 안의
  계약 교정만 허용한다. `StubIntentResolver`는 keyless 스모크 전용이며 자연어 품질을 대변하지 않는다.
- Gemini 실호출 스모크(2026-07-27) — `.env`의 `gemini-3.5-flash-lite`로 마스킹된 샘플 화면을 호출해
  `tap`, `target_node_id=n1` 구조화 응답을 받았다. 이 모델 ID가 응답한다는 확인일 뿐, 모델 확정은 평가 체계가 선 뒤에 한다.
- `app/config.py` — development/production 실행 프로필과 기동 전 설정 검증.
- `app/raw_store.py` — 도그푸딩 화이트리스트 왕복의 봉투 저장(#41). `/v1/next-step`이
  `should_store_raw()`로 판정해 호출한다. `tests/test_raw_store.py` 5건. 위 "도그푸딩 raw 보존" 절.

**최소만 구현** (동작하지만 의도적으로 얕음)
- `reasoning._verify_done` — evidence가 현재 화면 label과 실제로 맞물리는지 대조한다(#62,
  SPEC §4 — §11에서 이동). 부분 문자열·한 글자 label 오탐의 측정·보강은 #71.

**껍데기** (호출하면 아무 일도 없거나 raise)
- `reasoning._plan` — 목표를 그대로 1개 태스크로 돌려준다. 하위 태스크 분해 없음.
- `reasoning._apply_guardrails` — 같은 삼성 런처 화면의 동일 scroll 세 번째 후보를
  `open_app_drawer`로 전환하는 좁은 반복 가드가 단일 지점에 있다(#36).
