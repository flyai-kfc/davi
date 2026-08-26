"""#429 1단계 계측이 세 축을 프롬프트와 **같은 판정으로** 읽는지 고정한다.

계측이 판정을 다시 구현하면 프롬프트만 고쳐졌을 때 수치가 조용히 거짓이 된다. 그래서
회귀 표시는 `build_prompt`의 문장과 **함께** 단언한다 — 둘이 갈라지면 여기서 떨어진다.
"""

import pytest
from app.loop_axes import REPEAT_THRESHOLD, snapshot
from app.prompt import STEP_SCREEN_RETURNED, build_prompt
from app.schemas import ActionType, GuideStep, NextStepRequest, Screen, UiElement
from app.signature import screen_signature

_ELSEWHERE = "e" * 64


def _screen(content: str | None = None) -> Screen:
    return Screen(
        package_name="com.example.app",
        elements=[UiElement(node_id="n1", label="설정", clickable=True)],
        content_signature=content,
    )


def _step(**overrides) -> GuideStep:
    fields = {
        "instruction": "'설정'을 눌러 주세요.",
        "action_type": ActionType.TAP,
        "target_label": "설정",
        "target_node_id": "n1",
        "structure_signature": _ELSEWHERE,
        "screen_changed": True,
    }
    return GuideStep(**(fields | overrides))


def _request(*steps: GuideStep, content: str | None = None) -> NextStepRequest:
    return NextStepRequest(goal="와이파이 켜기", screen=_screen(content), steps=list(steps))


def _here(content: str | None = None) -> str:
    return screen_signature(_screen(content))


def test_empty_history_stands_no_axis():
    axes = snapshot(_request())

    assert axes.steps == 0
    assert not axes.loop_tried
    assert not axes.loop_returned
    assert not axes.target_identifiable
    assert not axes.causal_widened
    assert not axes.axes_all


def test_same_target_twice_on_this_screen_raises_the_structure_axis():
    here = _here()

    axes = snapshot(
        _request(_step(structure_signature=here), _step(structure_signature=here)),
    )

    assert axes.tried_max == REPEAT_THRESHOLD
    assert axes.tried_repeat_targets == 1
    assert axes.loop_tried
    assert axes.target_identifiable


def test_steps_from_another_screen_leave_no_countable_target():
    """`node_id`는 서명이 같을 때만 같은 요소를 가리킨다(#429 ① 대상 특정)."""

    axes = snapshot(_request(_step(), _step()))

    assert axes.tried_max == 0
    assert axes.structure_matches == 0
    assert not axes.target_identifiable


def test_returned_mark_moves_with_the_prompt_sentence():
    """계측이 프롬프트와 같은 판정을 읽는다는 것을 한 요청에서 함께 본다."""

    here = _here()
    request = _request(
        _step(structure_signature=here),
        _step(instruction="다른 화면의 안내"),
    )

    axes = snapshot(request)

    assert STEP_SCREEN_RETURNED in build_prompt(request)
    assert axes.loop_returned
    # 내용 서명이 없으면 **확인된 회귀가 아니다.** `None == None`을 동일성으로 읽으면
    # 낡은 앱의 회귀가 전부 「내용까지 같음」으로 계상돼 확신 문구 구간이 부풀어 보인다.
    assert axes.returned_confirmed == 0


def test_no_return_no_signal():
    request = _request(_step(structure_signature=_here()))

    assert STEP_SCREEN_RETURNED not in build_prompt(request)
    assert not snapshot(request).loop_returned


def test_content_axis_marks_the_confirmed_return():
    """내용 서명까지 같은 회귀는 따로 센다(#410) — 확신 문구가 나가는 그 구간이다."""

    content = "a" * 64
    here = _here(content)
    request = _request(
        _step(structure_signature=here, content_signature=content),
        _step(instruction="다른 화면의 안내"),
        content=content,
    )

    axes = snapshot(request)

    assert axes.returned_marks == 1
    assert axes.returned_confirmed == 1


def test_causality_reads_only_the_last_step():
    """배제가 발화할 자리는 지금 이 화면이라, 세션 초반의 조작이 인과를 세우면 안 된다."""

    here = _here()
    axes = snapshot(
        _request(
            _step(structure_signature=here, performed=True),
            _step(structure_signature=here, performed=None, screen_changed=False),
        ),
    )

    assert not axes.causal_performed
    assert not axes.causal_widened


def test_widened_axis_stands_where_performed_alone_does_not():
    """#429 계측 항목 넷째 — 좁은 축과 넓은 축을 같은 요청에서 함께 잰다."""

    here = _here()
    axes = snapshot(
        _request(
            _step(structure_signature=here),
            _step(structure_signature=here, performed=None, screen_changed=True),
        ),
    )

    assert not axes.causal_performed
    assert axes.causal_widened
    assert axes.axes_all
    assert not axes.axes_all_narrow


def _all_axes_request(**last_overrides) -> NextStepRequest:
    here = _here()
    return _request(
        _step(structure_signature=here),
        _step(instruction="다른 화면의 안내"),
        _step(structure_signature=here, **({"performed": True} | last_overrides)),
    )


def test_all_three_axes_stand_together():
    axes = snapshot(_all_axes_request())

    assert axes.loop_tried and axes.loop_returned
    assert axes.target_identifiable
    assert axes.causal_performed
    assert axes.axes_all and axes.axes_all_narrow


@pytest.mark.parametrize(
    ("request_factory", "missing"),
    [
        (lambda: _all_axes_request(performed=None, screen_changed=False), "인과"),
        (
            lambda: _request(_step(), _step(instruction="다른 화면의 안내"), _step(performed=True)),
            "대상 특정",
        ),
        (
            lambda: _request(_step(structure_signature=_here(), performed=True)),
            "루프 판정",
        ),
    ],
)
def test_one_missing_axis_is_enough_to_stay_silent(request_factory, missing):
    axes = snapshot(request_factory())

    assert not axes.axes_all, f"{missing} 축이 없는데 세 축이 다 섰다"
    # 셋이 같은 축 구성을 써야 ④의 델타가 인과 축의 차이만 뜻한다.
    assert not axes.axes_all_observed, f"{missing} 축이 없는데 중간 축이 섰다"
    assert not axes.axes_all_narrow, f"{missing} 축이 없는데 좁은 축이 섰다"


def test_content_only_return_is_counted_where_structure_never_matches():
    """구조는 매번 다른데 내용으로 돌아오는 화면(#429 ③의 사각지대).

    계측이 회귀 판정을 **구조 축만 보는 사본**으로 다시 구현하면 여기서 떨어진다.
    """

    content = "b" * 64
    request = _request(
        _step(structure_signature="1" * 64, content_signature=content),
        _step(instruction="다른 화면의 안내", structure_signature="2" * 64, performed=True),
        content=content,
    )

    axes = snapshot(request)

    assert STEP_SCREEN_RETURNED in build_prompt(request)
    assert axes.loop_returned
    assert axes.returned_confirmed == 1
    # 대상 특정은 안 된다 — `node_id`가 다른 요소를 가리킨다.
    assert axes.structure_matches == 0
    assert not axes.target_identifiable
    # 인과가 서 있어도(마지막 스텝이 눌림 확인) 뺄 대상이 없으면 세 축은 안 선다.
    assert axes.causal_performed
    assert not axes.axes_all
    assert not axes.axes_all_narrow


@pytest.mark.parametrize(
    ("step_content", "current_content"),
    [
        ("b" * 64, None),
        # **둘 다 없는 경우가 진짜 함정이다.** `None == None`을 동일성으로 읽으면 내용이
        # 없는 화면끼리 같은 화면이 되어, 낡은 앱의 이력 전체에 거짓 회귀가 붙는다.
        (None, None),
    ],
)
def test_missing_content_signature_confirms_nothing(step_content, current_content):
    """한쪽이라도 값이 없으면 내용 동일성은 성립하지 않는다(C10 침묵 규약)."""

    request = _request(
        _step(structure_signature="1" * 64, content_signature=step_content),
        _step(
            instruction="다른 화면의 안내",
            structure_signature="2" * 64,
            content_signature="c" * 64,
        ),
        content=current_content,
    )

    axes = snapshot(request)

    assert not axes.loop_returned
    assert axes.returned_confirmed == 0


def test_blank_instruction_steps_are_not_counted_as_tries():
    """이력에 줄이 없는 스텝을 요소 줄이 세면 두 자리가 다른 말을 한다(`_tried_counts`)."""

    here = _here()
    axes = snapshot(
        _request(
            _step(structure_signature=here),
            _step(structure_signature=here, instruction="   "),
        ),
    )

    assert axes.tried_max == 1
    assert axes.tried_repeat_targets == 0
    assert not axes.loop_tried


def test_unobserved_screen_change_does_not_widen_causality():
    """`screen_changed=None`은 「그 안내 뒤 아직 요청이 없었다」이지 변화 관측이 아니다."""

    here = _here()
    axes = snapshot(
        _request(
            _step(structure_signature=here),
            _step(structure_signature=here, performed=None, screen_changed=None),
        ),
    )

    assert not axes.causal_widened
    assert not axes.causal_observed


def test_pressing_elsewhere_is_an_observation_but_not_a_press():
    """가운데 축이 여는 구간 — `performed=False`는 조작이 관측된 경우다(#429 인과 표)."""

    here = _here()
    axes = snapshot(
        _request(
            _step(structure_signature=here),
            _step(structure_signature=here, performed=False, screen_changed=False),
        ),
    )

    assert not axes.causal_performed
    assert axes.causal_observed
    assert not axes.causal_widened
    assert axes.axes_all_observed
    assert not axes.axes_all_narrow
    assert not axes.axes_all


def test_last_step_here_marks_whether_the_causal_step_is_this_screen():
    here = _here()

    stayed = snapshot(_request(_step(structure_signature=here)))
    left = snapshot(_request(_step(structure_signature=here), _step()))

    assert stayed.last_step_here
    assert not left.last_step_here
