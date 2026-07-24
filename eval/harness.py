"""회귀 평가 하니스 (골격).

서버의 추론 코드를 직접 import해 [화면 픽스처 -> 다음 안내]를 검증한다(eval/CLAUDE.md).
기기 없이 CI에서 돈다. 지금은 스텁 LLM으로 파이프라인이 통하는지만 확인한다.

TODO:
- fixtures/*.json 로더 + 골든 기대값.
- LLM-judge 채점(HANDOFF §1-3): exact-match 착시 대신 동등/우수 경로를 인정.
- 실기기 로그에서 실패 화면을 익명화해 픽스처로 추가(§12).
"""

from __future__ import annotations

import sys
from pathlib import Path

# 서버 패키지를 import 경로에 추가(모노레포 형제 폴더).
_SERVER = Path(__file__).resolve().parent.parent / "server"
sys.path.insert(0, str(_SERVER))

from app.llm import StubLlmClient  # noqa: E402
from app.reasoning import next_step  # noqa: E402
from app.schemas import NextStepRequest  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures"


def run_fixture(payload: dict) -> dict:
    req = NextStepRequest.model_validate(payload)
    resp = next_step(req, StubLlmClient())
    return resp.model_dump()


if __name__ == "__main__":
    demo = {
        "goal": "카카오톡 열기",
        "screen": {"elements": [{"node_id": "n1", "label": "카카오톡", "clickable": True}]},
    }
    print(run_fixture(demo))
