# CLAUDE.md — eval (평가 하니스)

Python 3.12. 기기 없이 `[화면 fixture → 다음 안내]` 회귀를 검증한다.
**`pingpong/`은 대상이 다른 별도 하니스다** — 목표 구체화(핑퐁) 단계를 텍스트로 재며,
자체 README·CLAUDE 규칙 대신 이 문서 맨 아래 절을 따른다(#329). **입력 회귀는 API 키 없이
항상 돌고**, 실제 모델과 LLM judge는 별도 주간·수동 관측에서만 돈다. 층 구분은 `../SPEC.md`
§8, 공통 규칙은 루트 `../CLAUDE.md`.

## 이 폴더의 책임

- `fixtures/*.json`을 이름순으로 읽어 fixture별 결과와 전체 결과를 만든다.
- fixture 전체에 PII나 `•••` 마스킹 흔적이 없는지 검사한다. 실패 메시지에는 원문을 쓰지 않는다.
  **단 이 검사는 `mask_text` 기반이라 고유명(사람 이름·기기명·대화방 제목)을 통과시킨다** —
  그쪽 방어선은 사람이 먼저 익명화하는 것이다(`SPEC.md` §2 「패턴 밖 고유명」, #360).
- `../server/`의 최신 `NextStepRequest` 스키마와 추론 코드를 직접 import한다. 선택 필드는
  fixture에서 생략 가능해야 하며, 알 수 없는 필드가 조용히 버려지면 실패해야 한다.
- `completion-fixtures/evidence_cases.json`은 완료 근거 판정의 TP/TN/FP/FN을 고정한다. 실기기
  화면은 기존 익명화 fixture를 파일명으로 참조하고, 합성 사례는 `eval_case`로 명시한다. 후보
  정책 수치는 `completion_ab.py`가 같은 corpus에서 키 없이 결정론적으로 센다(#71·#425).
  기존 `expected_verified`는 근거 문장 인용의 #71 이진 정답으로 유지하고, 별도 사람 기대값
  `expected_verdict=verified|inconclusive|contradicted`는 현재 화면의 목표 상태만 판정한다.
  보고서에는 3×3 혼동 행렬·false success·verified recall·contradiction recall·inconclusive
  rate와 corpus 해시·두 정책 식별자·git SHA를 남긴다. 화면·목표·근거 원문은 출력하지 않는다.
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
py -3.12 completion_ab.py
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
- **「입력칸은 보이는데 아무것도 선택되지 않은」 화면 픽스처가 없다**(#373). 그래서
  `prompt.INPUT_NOT_FOCUSED`가 실모델 A/B의 무회귀 근거 밖이다 — 입력 요소가 있는 픽스처는
  알람 편집기 하나뿐이고 거기엔 focused 입력칸이 있어 그 분기가 죽은 경로다. 그 화면을
  익명화해 넣으면 닫힌다.
- 완료 fixture의 두 사람 기대값은 축이 다르다. `expected_verified`는 evidence가 화면 label을
  올바르게 인용했는지, `expected_verdict`는 evidence 문장과 무관하게 현재 화면이 목표 상태를
  직접 뒷받침/반박하는지다. 알려진 오판도 지우지 않고 case id와 혼동행렬로 남긴다(#71·#425).
  baseline adapter는 #425 착수 시점의 화면 근거 정책과 보이는 요소 판정을
  `completion_baseline.py`에 복사해 독립적으로 보존하고, 파일 해시를 policy id에 넣는다.
  candidate id도 서버 추론 파일 해시를 포함한다. #424 이후 candidate는 서버의 구조화 완료
  근거·현재 상태·직전 수행 결과·동일 화면 사용자 거절을 사용해 세 상태를 직접 분류한다.

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


## `pingpong/` — 핑퐁 성능 하니스 (#329)

목표 구체화 단계만 떼어 **발화 대신 텍스트로** 재는 별도 하니스다. 이 폴더의 나머지(화면
fixture → 다음 안내)와 대상도 실행 경로도 겹치지 않으므로 **같은 하니스로 합치지 않는다.**

- 구성은 셋이다 — 사용자 LLM(Gemini, 일부러 조각을 빼고 발화) · 어댑터 · 평가자 LLM(Claude).
- **davi 코드를 부르지 않는다.** 서버를 띄워 `POST /v1/intent/slot-fill`을 HTTP로 부른다.
  그래서 계약이 바뀌어도 import가 깨지지 않고, 대신 **어댑터가 형식을 흡수한다**(`sut.py`).
  핑퐁이 선택지 형식으로 바뀌면(#316) 고칠 파일은 그 하나다.
- **무엇을 숨길지는 코드가 정한다.** 사용자 LLM에게 "말하지 마세요"로만 맡기면 새고, 새면
  핑퐁이 1라운드에 끝나 아무것도 측정되지 않는다. LLM은 말투만 만든다.
- **세는 일은 코드가 한다**(라운드 수·기타 사용률·지연). 평가자는 판정만 한다 — 모델은 세기를 틀린다.
- **키 없이 도는 층**은 `tests/test_keyless.py`다(시나리오 계약·유출 검사·루프 배선·어댑터
  파싱·재시도). 실제 모델을 부르는 `run`·`judge`는 CI 게이트로 올리지 않는다 — 이 폴더의
  judge 운영 정책과 같은 이유다.
- 대화는 Gemini 쿼터를, 채점은 게이트웨이 크레딧을 쓴다. **비용 주체가 달라 명령을 나눴다** —
  `run --no-judge`로 대화를 먼저 확보하고, 값어치 있는 것만 골라 나중에 `judge`한다.
  부분 채점이 겹칠 수 있어 **결과마다 채점 배치(평가자 모델·시나리오 해시·채점 계약)를 저장하고,
  보고서는 같은 배치만 집계한다.** 다른 배치의 점수는 저장은 유지하되 '다른 배치'로
  표시하고 섞지 않는다 — 섞이면 비교 가능한 수치라는 계약이 깨진다.
- 수치의 원본은 `../../docs/voice-goal.md` §8이고, 상세는 `pingpong/BASELINE.md`다.
