"""파이프라인이 모델에 넘기기 전에 무엇을 거르는지 고정한다.

2차 마스킹은 "앱이 놓쳤을 때를 위한 그물"이다(SPEC §2). 그물에 구멍이 나도 응답은 정상으로
보이기 때문에, 무엇이 모델에 실제로 전달됐는지를 붙잡아 두는 테스트가 필요하다.
"""

from __future__ import annotations

from app.llm import LlmClient
from app.reasoning import next_step
from app.schemas import (
    ActionType,
    GuideAction,
    GuideStep,
    NextStepRequest,
    Screen,
    UiElement,
)
from app.signature import screen_signature


class _CapturingLlmClient(LlmClient):
    """모델에 넘어간 요청을 그대로 붙잡아 두는 스파이."""

    def __init__(self) -> None:
        self.seen: NextStepRequest | None = None

    def reason(self, req: NextStepRequest) -> GuideAction:
        self.seen = req
        return GuideAction(
            expected_result="완료",
            screen_summary="테스트 화면",
            evidence="근거",
            action_type=ActionType.TAP,
            target_node_id="n1",
            instruction="누르세요.",
        )


def test_screen_signature_changes_with_focus_and_ime_state():
    before = Screen(
        package_name="com.example.app",
        ime_visible=False,
        elements=[UiElement(node_id="n1", role="EditText", editable=True)],
    )
    ready = Screen(
        package_name="com.example.app",
        ime_visible=True,
        elements=[UiElement(node_id="n1", role="EditText", editable=True, focused=True)],
    )

    assert screen_signature(before) != screen_signature(ready)


def _step(
    instruction: str,
    *,
    target_label: str | None = None,
    screen_hint: str | None = None,
) -> GuideStep:
    return GuideStep(
        instruction=instruction,
        action_type=ActionType.TAP,
        target_label=target_label,
        screen_hint=screen_hint,
        structure_signature="a" * 64,
    )


def test_second_pass_masks_every_text_field_of_the_steps():
    """스텝 이력도 그물에 걸려야 한다. 세 자리 모두다.

    1차가 놓친 값이 label에 있었다면 모델이 instruction과 화면 요약에 인용하고, `target_label`은
    그 label 자체다. 앱은 그 값을 스텝에 담아 매 요청마다 다시 보낸다 — 화면은 넘어가도 이력은
    남으므로 놓친 값이 계속 재전송된다.
    """
    spy = _CapturingLlmClient()
    req = NextStepRequest(
        goal="문의하기",
        screen=Screen(elements=[UiElement(node_id="n1", label="버튼", clickable=True)]),
        steps=[
            _step("hong@example.com 으로 보내세요."),
            _step(
                "010-1234-5678 을 누르세요.",
                target_label="010-1234-5678",
                screen_hint="hong@example.com 의 연락처 화면",
            ),
        ],
    )

    next_step(req, spy)

    assert spy.seen is not None
    sent = " ".join(
        value
        for step in spy.seen.steps
        for value in (step.instruction, step.target_label or "", step.screen_hint or "")
    )
    assert "hong@example.com" not in sent
    assert "010-1234-5678" not in sent
    # 문장 구조는 남는다 — 반복 안내 판정에 쓰이므로 통째로 지우면 안 된다.
    assert "으로 보내세요." in sent
    assert "의 연락처 화면" in sent


def test_second_pass_does_not_mutate_the_request():
    """비파괴여야 한다. 호출자의 객체가 바뀌면 로깅·재시도가 다른 값을 보게 된다."""
    spy = _CapturingLlmClient()
    original = "hong@example.com 으로 보내세요."
    req = NextStepRequest(
        goal="문의하기",
        screen=Screen(
            elements=[UiElement(node_id="n1", label="문의 hong@example.com", clickable=True)]
        ),
        steps=[_step(original)],
    )

    next_step(req, spy)

    assert req.steps[0].instruction == original
    assert "hong@example.com" in req.screen.elements[0].label
