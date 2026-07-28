# eval — davi 평가 하니스

기기와 API 키 없이 `fixtures/*.json`을 읽어 `[화면 fixture → 다음 안내]` 파이프라인을 회귀
검증한다. 서버 추론 코드를 직접 import하며, 현재 단계(#30)는 평가 3층 중 입력 보존 계층이다.

## 실행 (Python 3.12)

```bash
cd eval
py -3.12 -m pip install -r requirements.txt
py -3.12 harness.py
py -3.12 -m pytest
```

macOS/Linux에서는 `py -3.12` 대신 `python3.12`를 사용한다. `harness.py`는 fixture별 결과와
전체 `total/passed/failed`를 JSON으로 출력하고 하나라도 실패하면 종료 코드 1을 반환한다.

CI는 별도 API 키나 secret 없이 `python -m pytest`, Ruff 린트·포맷 검사와
`python harness.py` 스모크를 모두 실행한다. 실제 fixture의 로딩, PII 검사, 요청 스키마,
입력 보존, 스텁 파이프라인뿐 아니라 PII 탐지·원문 비노출 같은 음성 경로도 항상 검사한다.

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
- `source.observed_model_action`은 실기기 실호출에서 관측한 구조화 메타데이터다. 원문 모델 응답을
  저장하는 곳이 아니며, 스텁 기대값으로 채점하지 않는다.
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

## 평가 계층

1. 항상 실행: 이 하니스의 fixture loader / PII / 스키마 / 입력 보존
2. 항상 실행: 결정론적 fake LLM 반복 가드 회귀(#36)
3. API 키가 있을 때만: 실제 모델 + LLM judge(#42)

현재 출력은 의미 품질 점수가 아니다. 실기기 기반 fixture가 늘어나도 “현재 수치는 깨끗한 fixture
기준의 낙관적 상한”이라는 단서를 유지한다.
