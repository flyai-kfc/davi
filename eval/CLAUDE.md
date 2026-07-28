# CLAUDE.md — eval (평가 하니스)

Python 3.12. 기기와 API 키 없이 `[화면 fixture → 다음 안내]` 회귀를 검증한다. 공통 규칙은
루트 `../CLAUDE.md`, 전략은 `../SPEC.md` §8을 따른다.

## 이 폴더의 책임

- `fixtures/*.json`을 이름순으로 읽어 fixture별 결과와 전체 결과를 만든다.
- fixture 전체에 PII나 `•••` 마스킹 흔적이 없는지 검사한다. 실패 메시지에는 원문을 쓰지 않는다.
- `../server/`의 최신 `NextStepRequest` 스키마와 추론 코드를 직접 import한다. 선택 필드는
  fixture에서 생략 가능해야 하며, 알 수 없는 필드가 조용히 버려지면 실패해야 한다.
- 실제 후보 모델과 LLM judge는 `judge.py`의 별도 HTTP 경계로 호출한다. 키나 judge 모델이
  없으면 명시적으로 skip하고, 활성화된 실행의 HTTP·파싱 오류와 오답 판정은 실패한다.
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
- 반복 액션 fake LLM은 #36 범위다.

현재 수치는 깨끗한 fixture 기준의 낙관적 상한이다. 보고할 때 이 단서를 계속 붙인다.
