"""완료 판정을 실기기 fixture로 회귀한다(#54).

`StubLlmClient`는 `done`을 내지 않아 이 경로를 만들지 못한다. 결정론적 fake LLM으로
`done`을 내되, 근거를 **실기기 화면에 있는 것 / 없는 것**으로 갈라 넣는다.

실패 출력에 화면 label 원문을 넣지 않는다(`eval/CLAUDE.md` PII 규칙).
"""

from __future__ import annotations

import pytest
from app.llm import LlmClient
from app.reasoning import next_step
from app.schemas import ActionType, GuideAction, NextStepRequest

from harness import FIXTURES, load_fixture

_FIXTURE = "samsung_launcher_open_camera.json"


class DoneLlmClient(LlmClient):
    """언제나 `done`을 내는 fake. 근거 문장만 갈아 끼운다."""

    def __init__(self, evidence: str) -> None:
        self._evidence = evidence

    def reason(self, req: NextStepRequest) -> GuideAction:
        return GuideAction(
            expected_result="카메라가 열려 있다",
            evidence=self._evidence,
            action_type=ActionType.DONE,
            instruction="다 되었습니다.",
        )


@pytest.fixture
def request_from_device() -> NextStepRequest:
    return load_fixture(FIXTURES / _FIXTURE).request


def _first_label(req: NextStepRequest) -> str:
    label = next((e.label for e in req.screen.elements if len(e.label) >= 2), None)
    assert label is not None, f"{_FIXTURE}에 대조할 label이 없다"
    return label


def test_fabricated_evidence_is_not_verified(request_from_device):
    """화면에 없는 문장을 근거로 낸 `done`은 검증을 통과하지 못한다."""

    response = next_step(
        request_from_device,
        DoneLlmClient("사진 촬영이 끝나 갤러리에 저장되었습니다"),
    )

    assert response.action.action_type is ActionType.DONE
    assert not response.verified


def test_evidence_quoted_from_the_device_screen_is_verified(request_from_device):
    """실기기 화면의 label을 인용한 근거는 통과한다 — 정당한 완료를 막지 않는다."""

    quoted = _first_label(request_from_device)
    response = next_step(
        request_from_device, DoneLlmClient(f"화면에 {quoted} 이(가) 보임")
    )

    assert response.verified
