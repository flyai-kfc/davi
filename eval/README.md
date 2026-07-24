# eval — davi 평가 하니스

기기 없이 [화면 픽스처 → 다음 안내]를 회귀 검증한다. 서버 추론 코드를 **직접 import**한다.
전용 규칙은 `CLAUDE.md`, 전략은 `../SPEC.md` §8.

## 실행 (Python 3.12)

```bash
py -3.12 -m pip install -r requirements.txt
py -3.12 harness.py          # 스텁 LLM으로 파이프라인 확인
```

## 지금 무엇이 진짜인가

**동작하는 구현**
- `harness.py:run_fixture` — `../server/app`의 `next_step`을 실제로 import해 돌린다. 모노레포 형제 폴더
  import 경로 처리 포함. `py -3.12 harness.py`로 확인 가능.

**껍데기 / 아직 없음**
- 픽스처 로더 — `fixtures/`는 비어 있고, 이를 읽는 코드가 없다. `FIXTURES` 상수만 선언돼 있다.
- 골든 기대값·통과/실패 판정 — 없다. 지금은 결과를 출력만 한다.
- **LLM-judge 채점** — 없다(HANDOFF §1-3의 exact-match 착시 대응이 여기 붙는다).
- 멀티모델 A/B, `--min-interval`/`--retries`, `runs/` 저장, 리포트 — 전부 없다. `requirements.txt`에
  litellm이 들어 있지만 **아직 아무 코드도 쓰지 않는다.**

다음 단계는 `../SPEC.md` §8: 픽스처 로더 → LLM-judge 채점 → 실기기 실패 픽스처 확대.

## 원칙
- 새 픽스처는 **반드시 익명화**. 실제 개인정보를 저장소에 올리지 않는다.
- 보고 시 "현재 수치는 깨끗한 픽스처 기준 낙관적 상한"이라는 단서를 붙인다(HANDOFF §5-5).
