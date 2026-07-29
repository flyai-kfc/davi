"""완료 판정을 실기기 fixture로 회귀한다(#54).

`StubLlmClient`는 `done`을 내지 않아 이 경로를 만들지 못한다. 결정론적 fake LLM으로
`done`을 내되, 근거를 **실기기 화면에 있는 것 / 없는 것**으로 갈라 넣는다.

실패 출력에 화면 label 원문을 넣지 않는다(`eval/CLAUDE.md` PII 규칙).
"""

from __future__ import annotations

import pytest
from app.llm import LlmClient
from app.reasoning import (
    _MIN_EVIDENCE_LABEL_CHARS,
    _normalize_for_evidence,
    next_step,
)
from app.schemas import ActionType, GuideAction, NextStepRequest

from harness import FIXTURES, load_fixture

_FIXTURE = "samsung_launcher_open_camera.json"


class DoneLlmClient(LlmClient):
    """언제나 `done`을 내는 fake. 근거 문장만 갈아 끼운다."""

    def __init__(
        self, evidence: str, expected_result: str = "카메라가 열려 있다"
    ) -> None:
        self._evidence = evidence
        self._expected_result = expected_result

    def reason(self, req: NextStepRequest) -> GuideAction:
        return GuideAction(
            expected_result=self._expected_result,
            evidence=self._evidence,
            action_type=ActionType.DONE,
            instruction="다 되었습니다.",
        )


@pytest.fixture
def request_from_device() -> NextStepRequest:
    return load_fixture(FIXTURES / _FIXTURE).request


def _first_label(req: NextStepRequest) -> str:
    """코드가 대조에 쓰는 것과 **같은 기준**으로 고른다.

    테스트가 원문 길이로 고르면, 정규화 후에야 짧아지는 label(`→ ←`, `1.`)이 첫 요소인 픽스처가
    들어왔을 때 코드는 설계대로인데 테스트만 깨진다(PR #62 리뷰 5번).
    """
    label = next(
        (
            e.label
            for e in req.screen.elements
            if len(_normalize_for_evidence(e.label)) >= _MIN_EVIDENCE_LABEL_CHARS
        ),
        None,
    )
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
    """실기기 화면의 label을 인용한 근거는 통과한다 — 정당한 완료를 막지 않는다.

    **목표가 실제로 달성된 화면으로 맞춘다.** 픽스처의 원래 목표(`open camera app`)를 그대로 두면
    런처 홈에서 `done`이 통과하는 조기 종료 시나리오를 기대 동작으로 굳히게 된다 — 나중에 그걸
    조이면 이 테스트가 회귀처럼 깨진다(PR #62 리뷰 4번). 그 한계는 `SPEC.md` §4에 적혀 있다.
    """
    reached_goal = request_from_device.model_copy(update={"goal": "홈 화면으로 가기"})
    quoted = _first_label(reached_goal)

    # `expected_result`도 바뀐 목표에 맞춘다. 지금 단언에는 영향이 없지만, 목표와 대조하는 검사가
    # 들어오면 이 픽스처가 틀린 데이터가 된다(PR #62 리뷰).
    response = next_step(
        reached_goal,
        DoneLlmClient(
            f"홈 화면에 {quoted} 이(가) 보임",
            expected_result="홈 화면이 보인다",
        ),
    )

    assert response.verified
