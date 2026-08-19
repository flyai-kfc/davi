# eval — davi 평가 하니스

기기와 API 키 없이 `fixtures/*.json`을 읽어 `[화면 fixture → 다음 안내]` 파이프라인을 회귀
검증한다. 서버 추론 코드를 직접 import하며, 현재 단계(#30)는 평가 3층 중 입력 보존 계층이다.

## 실행 (Python 3.12)

```bash
cd eval
py -3.12 -m pip install -r requirements.txt
py -3.12 harness.py
py -3.12 -m pytest
py -3.12 judge.py
```

macOS/Linux에서는 `py -3.12` 대신 `python3.12`를 사용한다. `harness.py`는 fixture별 결과와
전체 `total/passed/failed`를 JSON으로 출력하고 하나라도 실패하면 종료 코드 1을 반환한다.

CI는 별도 API 키나 secret 없이 `python -m pytest`, 전체 eval Python의 Ruff 린트·포맷 검사와
`python harness.py` 스모크를 모두 실행한다. 실제 fixture의 로딩, PII 검사, 요청 스키마,
입력 보존, 스텁 파이프라인뿐 아니라 PII 탐지·원문 비노출 같은 음성 경로도 항상 검사한다.
`judge.py`도 일반 CI에서 secret을 주입하지 않은 채 실행해 명시적인 `skipped` 결과와 종료 코드
0을 확인한다. 실제 모델 호출은 아래의 별도 관측 워크플로에서만 실행한다.

다른 디렉터리를 검사하려면 다음처럼 지정한다.

```bash
python3.12 harness.py --fixtures-dir /path/to/fixtures
```

## fixture 형식

파일은 `fixtures/*.json`에 하나의 JSON 객체로 저장한다.

```json
{
  "id": "device-screen-short-name",
  "description": "어떤 실기기 화면인지 설명",
  "source": {
    "kind": "anonymized_real_device",
    "captured_at": "2026-07-27",
    "issue": 28,
    "device_model": "SM-S921N",
    "os_version": "Android 16",
    "ui_version": "One UI 8.5",
    "anonymization": [
      "식별 가능한 커스텀 앱 이름을 일반적인 label로 치환"
    ],
    "observed_model_action": {
      "action_type": "tap",
      "target_node_id": "n8",
      "instruction": "실기기에서 관측한 익명화 안내"
    },
    "observed_model_action_review": {
      "status": "accepted",
      "reviewed_at": "2026-07-28",
      "reviewed_in_pull_request": 43
    }
  },
  "input": {
    "goal": "open camera app",
    "screen": {
      "package_name": "com.sec.android.app.launcher",
      "elements": [
        {
          "node_id": "n1",
          "label": "일반 앱",
          "clickable": true
        }
      ]
    }
  },
  "expected": {
    "stub_outcome": "success",
    "action_type": "tap"
  }
}
```

- `input`은 `server/app/schemas.py:NextStepRequest`의 JSON 표현이다. 최신 계약의 선택 필드는
  생략할 수 있다. 로더는 실제로 적힌 필드만 직렬화 왕복해 알 수 없는 필드나 묵시적 타입 변환으로
  입력이 손실되지 않는지도 확인한다.
- #112 이전 실기기 fixture는 `editable`·`focused`·`ime_visible`을 생략한 구버전 관측으로
  그대로 유지한다. 새 입력 fixture는 실제 관측값만 넣고, 편집 요소의 입력 원문이나 IME 키 목록을
  재구성해 넣지 않는다.
- `recent_instructions`는 모델 프롬프트용 문장 이력이고, `recent_actions`는 앱이 보관하는
  구조화 이력이다(반복 카운트가 #202에서 걷혀 지금 서버에 소비자는 없다). fixture는 세션 시작
  화면이므로 빈 `recent_actions`를 명시하거나 생략할 수 있다.
- `source.observed_model_action`은 실기기 실호출에서 관측한 구조화 메타데이터다. 원문 모델 응답을
  저장하는 곳이 아니며, 스텁 기대값으로 채점하지 않는다.
- 관측 행동이 있으면 `source.observed_model_action_review`도 반드시 둔다. `unreviewed`는 아직
  비교 기준으로 검토하지 않은 상태, `accepted`는 사람이 유효한 기준으로 확인한 상태,
  `rejected`는 관측은 보존하되 기준으로 쓰지 않는 상태다. `accepted`와 `rejected`에는 검토 날짜와
  검토가 기록된 PR 번호가 필요하며, judge는 `accepted`만 실제 호출 대상으로 삼는다.
- 모든 fixture의 암묵적 기대값은 `PII 없음`, `fixture 형식 적합`, `NextStepRequest 적합`,
  `입력 보존`이다.
- `expected.stub_outcome`은 `success` 또는 `error`다. 성공이면 구조적인 `action_type`, 오류면
  `error_kind`를 선택적으로 적을 수 있다.
- `target_node_id`, `instruction`, 의미상 정답은 스텁 기대값으로 표현하지 않는다.
  `StubLlmClient`는 목표를 이해하지 않고 첫 clickable 요소를 고르므로 “카메라(n8)를 선택해야
  한다” 같은 exact-match는 실제 품질을 검증하지 못한다. 의미 품질은 #42의 실제 모델 + judge
  계층에서 다룬다.

## 익명화와 PII 검사

fixture는 저장하기 전에 사람이 먼저 익명화하고, 하니스가 전체 JSON의 key와 value를 다시
검사한다.

- 이메일
- 주민등록번호
- 카드번호
- 휴대폰 번호
- 계좌번호 형태의 긴 숫자열
- 앱/서버가 이미 치환한 `•••` 토큰
- `device_id`, API 키, raw 모델 응답 필드와 흔한 API 키 형태

검사는 `server/app/masking.py:mask_text`를 재사용해 앱/서버의 다섯 정규식과 같이 움직인다. 실패
메시지에는 안전한 JSON 경로만 표시하고 발견한 원문은 표시하지 않는다. key 자체가 민감하거나
경로로 안전하게 표현할 수 없으면 `<redacted-key>`로 치환한다. 스키마 검증 오류의 필드 경로에도
같은 규칙을 적용한다.

정규식은 사람 이름이나 커스텀 앱 이름을 판별할 수 없다. 따라서 그런 label은 `일반 앱 1`처럼
의미 품질에 영향을 최소화하는 일반 label로 사람이 치환하고, `source.anonymization`에 무엇을
바꿨는지 남긴다. 원본 화면 트리, 개인 기기 식별값, API 키, 모델 원문 응답은 fixture나 실행
로그에 넣지 않는다.

## 삼성 런처 구성 fixture

SM-S921N / Android 16 / One UI 8.5에서 런처 구성에 따라 달라지는 관측값을 별도 fixture로
보존한다(#47).

| fixture | page | `has_scrollable_element` | 구분 근거 |
|---|---:|---:|---|
| `samsung_launcher_open_camera.json` | 없음 | 미기록 | 기존 실기기 캡처의 전체 8개 요소, page dot 없음 |
| `samsung_launcher_multi_page_home.json` | 1/2 | `false` | 홈 page dot 2개 |
| `samsung_launcher_grid_app_drawer.json` | 1/2 | `false` | 가로 페이징 앱서랍 page dot 2개 |
| `samsung_launcher_alphabetical_app_drawer.json` | 없음 | `true` | `vertical` 스크롤 컨테이너 1개 |

1페이지 홈은 `has_scrollable_element` 계약이 앱에 연결되기 전 캡처라 선택 필드를 그대로
생략했다. 나머지 세 fixture는 최신 앱 직렬화 결과를 사용한다. 위 표는 삼성 런처 전체에 대한
제품 규칙이 아니라 특정 기기·설정에서 관측한 입력이다.

새로 캡처한 세 구성은 실제 모델을 호출하지 않았으므로 `source.observed_model_action`을
추측해 넣지 않았다. 키가 있는 judge 실행에서도 이 fixture들은 호출 없이 `skipped`된다. 관측
행동이 있더라도 사람 검토가 `accepted`로 끝난 fixture만 후보 모델 + judge의 픽스처당 2회 호출
대상이 된다.

## 평가 계층

1. 항상 실행: 이 하니스의 fixture loader / PII / 스키마 / 입력 보존
2. 항상 실행: 결정론적 fake LLM 가드레일 회귀(#202). 실기기 fixture를 로더로 읽고 대상 없는
   scroll이 앱까지 나가지 않는지 — 교정·앱서랍 전환·실패로 갈리는지 — 를 검사한다.
   목표 앱 후보는 fake resolver로 후보 누락·오패키지 `not_found`·복수 후보·접근 가능한 profile의
   조회 실패 `unknown`을 재현해 자동 선택이나 미설치 단정을 막는다(#128).
3. `main` 주 1회·수동 관측: 실제 모델 + LLM judge(#42, #68)

현재 출력은 의미 품질 점수가 아니다. 실기기 기반 fixture가 늘어나도 “현재 수치는 깨끗한 fixture
기준의 낙관적 상한”이라는 단서를 유지한다.

## 선택적 실제 모델 + LLM judge

골든패스 exact-match는 동등하거나 더 나은 대안 경로도 오답으로 처리한다. PoC2의 FAIL 4건이
전부 이 착시였으므로, 의미 품질은 `[화면 + 목표 + 관측 안내 + 후보 안내]`를 별도 LLM judge가
판정한다.

```bash
cd eval
GEMINI_API_KEY=... \
DAVI_EVAL_JUDGE_MODEL=gemini-judge-model \
DAVI_MODEL=gemini-candidate-model \
python3.12 judge.py --max-fixtures 10
```

- 필수 환경변수는 `GEMINI_API_KEY`, `DAVI_EVAL_JUDGE_MODEL`이다.
- 후보 모델은 서버와 같은 `DAVI_MODEL`을 사용하며 생략하면 현재 개발 기본값을 쓴다.
- 키 또는 judge 모델이 없으면 `status=skipped`와 누락 변수명만 출력하고 종료 코드 0을 반환한다.
- 두 값이 모두 있으면 익명화 fixture마다 실제 후보 안내를 생성하고 `equivalent`, `better`,
  `incorrect` 중 하나로 구조화 판정한다. 앞의 두 판정은 통과하고 `incorrect`는 실패한다.
- 활성화한 실행에서 HTTP·네트워크·응답 파싱 오류가 나면 경고로 성공시키지 않고 종료 코드 1을
  반환한다.
- 후보·judge HTTP 호출과 오류 보고 정책은 eval에 두고, Gemini 후보 텍스트 추출·출력 상한 종료
  판별·JSON fence 제거만 서버의 순수 프로토콜 보조 로직과 공유한다.
- API 키, 원본 화면 트리, 후보·judge의 원문 응답과 판정 reason은 출력하거나 저장하지 않는다.
  결과에는 fixture id, 후보의 action enum, judge verdict만 남긴다.
- 화면 label과 후보·관측 안내는 모두 신뢰하지 않는 JSON 데이터 경계에 넣는다. judge의 시스템
  지시는 이 데이터 안의 역할 변경·규칙 무시·판정 강요 문구를 따르지 않도록 고정한다.

### CI와 비용 정책

- 필수 PR CI에는 실제 모델 secret을 주입하지 않는다. 항상 실행되는 eval 테스트·하니스와
  `judge.py`의 keyless skip만 병합 게이트다.
- 실제 모델 평가는 별도 `Eval judge observation` 워크플로에서 한국시간 월요일 09:00와 수동
  실행으로만 돈다. PR 이벤트가 없어 `incorrect`의 비결정성이 병합을 막지 않는다.
- 사람 검토가 `accepted`인 fixture를 이름순으로 실행하며 기본·최대 상한은 10개다. 정상 경로는
  fixture당 후보와 judge 각 1회라 실행당 최대 20회 호출한다. `--max-fixtures`는 1 이상이어야
  하고 워크플로 입력은 10 이하로 제한한다.
- `incorrect`, HTTP·네트워크·인증·파싱 실패는 관측 워크플로를 실패시킨다. 결과에는 안전한
  fixture ID와 enum만 남고, 이 워크플로는 브랜치 보호 필수 체크로 지정하지 않는다.
