# CLAUDE.md — eval (평가 하니스)

Python 3.12. 기기 없이 `[화면 fixture → 다음 안내]` 회귀를 검증한다. **입력 회귀는 API 키 없이
항상 돌고**, 실제 모델과 LLM judge는 별도 주간·수동 관측에서만 돈다. 층 구분은 `../SPEC.md`
§8, 공통 규칙은 루트 `../CLAUDE.md`.

## 이 폴더의 책임

- `fixtures/*.json`을 이름순으로 읽어 fixture별 결과와 전체 결과를 만든다.
- fixture 전체에 PII나 `•••` 마스킹 흔적이 없는지 검사한다. 실패 메시지에는 원문을 쓰지 않는다.
- `../server/`의 최신 `NextStepRequest` 스키마와 추론 코드를 직접 import한다. 선택 필드는
  fixture에서 생략 가능해야 하며, 알 수 없는 필드가 조용히 버려지면 실패해야 한다.
- 실제 후보 모델과 LLM judge는 `judge.py`의 별도 HTTP 경계로 호출한다. 키나 judge 모델이
  없으면 명시적으로 skip하고, 활성화된 실행의 HTTP·파싱 오류와 오답 판정은 실패한다.
- HTTP·실패·출력 정책은 서버와 분리하되, Gemini 후보 텍스트 추출·`MAX_TOKENS` 판별·JSON
  fence 제거는 `server/app/gemini_response.py`의 순수 프로토콜 보조 로직을 공유한다.
- 실기기 캡처는 사람이 먼저 익명화한다. 실제 개인정보, 원본 트리, `device_id`, API 키, 모델
  원문 응답을 저장소나 실행 로그에 남기지 않는다.

## 실행

```bash
cd eval
py -3.12 -m pip install -r requirements.txt
py -3.12 harness.py
py -3.12 -m pytest
py -3.12 judge.py
```

macOS/Linux에서는 `py -3.12` 대신 `python3.12`를 쓴다. `harness.py`의 기본 실행 경로가 CI의
`eval 하니스 스모크`와 동일하다. 없는 `main.py`, 멀티모델 CLI, `runs/` 출력을 전제하지 않는다.

## 기대값 경계

- 스텁으로 로더, PII, 요청/응답 계약, 파이프라인 실행만 검증한다.
- `StubLlmClient`는 첫 clickable 요소를 고르므로 실기기 관측 target/instruction을 exact-match
  하지 않는다. 관측값은 `source.observed_model_action` 메타데이터로만 남긴다.
- 골든패스 exact-match는 동등하거나 더 나은 경로도 오답 처리한다(PoC2 FAIL 4건이 전부 이
  착시였다). `judge.py`는 화면·목표·관측 안내·후보 안내를 보고 equivalent/better/incorrect로
  판정한다.
- **judge의 기준(`observed_model_action`)은 정답이 아니라 관측이다.** 실기기에서 모델이 실제로
  고른 것일 뿐 옳았다는 보장이 없다. #47의 구성별 런처 fixture는 실제 모델을 호출하지 않았으므로
  이 필드를 추측해 넣지 않는다. 기준이 없는 fixture는 `judge.py`가 호출 없이 skip한다.
  관측값이 있으면 `observed_model_action_review`를 함께 두고 `unreviewed`, `accepted`, `rejected`
  중 하나로 사람 검토 상태를 명시한다. judge는 `accepted`만 비교 기준으로 사용하며 나머지는
  호출 없이 skip한다. 검토 상태가 없는 관측값은 fixture 스키마 오류다.
- `tests/test_scroll_target_guard.py`는 실기기 fixture를 #30 로더로 읽고, 대상 없는 scroll을
  내놓는 fake LLM으로 서버 가드레일을 키 없이 검증한다 — 런처의 앱서랍 즉시 전환, 목록 화면의
  대상 교정, 대체 행동도 대상도 없는 화면의 실패, 스크롤 미관측 앱의 통과(#202).
  #36 반복 가드 회귀(`test_repeat_guard.py`)를 대신한다 — 반복 카운트가 #202에서 걷혔다.

현재 수치는 깨끗한 fixture 기준의 낙관적 상한이다. 보고할 때 이 단서를 계속 붙인다.

## 실제 judge 운영 정책

- PR과 일반 `main` CI의 필수 게이트는 키 없는 fixture·PII·fake LLM 회귀뿐이다. `judge.py`는
  keyless skip 계약만 확인하며 secret을 주입하지 않는다.
- 실제 후보 모델 + judge는 별도 `Eval judge observation` 워크플로에서 한국시간 월요일 09:00
  주 1회와 `workflow_dispatch` 수동 실행으로만 돈다. PR에는 트리거되지 않아 병합을 막지 않는다.
- `accepted` fixture를 이름순으로 최대 10개 실행한다. fixture당 후보 + judge 2회이므로 정상
  완료 기준 실행당 최대 20회 호출이다. 상한 밖 fixture는 HTTP 호출 없이 skip한다.
- HTTP·인증·파싱 실패와 `incorrect`는 관측 워크플로를 실패시켜 눈에 띄게 하되, 이 워크플로를
  브랜치 보호의 필수 체크로 지정하지 않는다. 반복성과 표본이 쌓이기 전에는 PR 게이트로 올리지 않는다.
