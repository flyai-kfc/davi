"""API 계약을 고정하는 테스트. server/CLAUDE.md "API 계약 결정"(C1~C15)에 대응한다.

여기가 깨지면 앱·eval도 같이 깨진다는 뜻이므로, 실패를 무시하고 넘어가지 말 것.
"""

from app.gemini_schema import build_response_schema
from app.llm import GUIDE_ACTION_SCHEMA, LlmError, StubLlmClient
from app.schemas import (
    ActionType,
    ApiError,
    CheckState,
    ErrorKind,
    GuideAction,
    GuideStep,
    NextStepRequest,
    NextStepResponse,
    Screen,
    SwipeDirection,
    UiElement,
)


def test_partial_check_state_round_trips_without_boolean_loss():
    element = UiElement.model_validate(
        {"node_id": "n1", "label": "일부 선택", "check_state": "partial"}
    )

    assert element.check_state is CheckState.PARTIAL
    assert element.model_dump(mode="json")["check_state"] == "partial"

# --- C1: propertyOrdering이 선언 순서에서 실제로 생성되는가 -------------------


def test_property_ordering_puts_evidence_before_action():
    """근거 -> 동작 순서. 뒤집히면 모델이 근거를 나중에 대충 채운다(HANDOFF §3-7)."""
    ordering = GUIDE_ACTION_SCHEMA["propertyOrdering"]
    assert ordering.index("expected_result") < ordering.index("evidence")
    assert ordering.index("evidence") < ordering.index("completion_evidence")
    assert ordering.index("evidence") < ordering.index("action_type")
    # 사용자 문구는 동작을 정한 뒤에 만든다.
    assert ordering.index("action_type") < ordering.index("instruction")


def test_property_ordering_matches_declaration_order():
    """손으로 적은 배열이 아니라 모델 선언에서 뽑혔는지 확인한다."""
    assert GUIDE_ACTION_SCHEMA["propertyOrdering"] == list(GuideAction.model_fields)


def test_optional_fields_are_not_required():
    """'해당 없음'은 빈 값이 아니라 필드 생략으로 표현한다(HANDOFF §10)."""
    required = GUIDE_ACTION_SCHEMA["required"]
    assert "direction" not in required
    assert "target_node_id" not in required
    assert "completion_evidence" not in required
    assert "expected_target_state" not in required
    assert {"expected_result", "evidence", "action_type", "instruction"} <= set(required)


def test_enum_has_no_empty_string():
    """enum에 빈 문자열이 있으면 Gemini가 400으로 거부한다."""
    for prop in GUIDE_ACTION_SCHEMA["properties"].values():
        assert "" not in prop.get("enum", [])


def test_schema_builder_rejects_empty_enum_value():
    """빈 문자열 enum을 만들려 하면 배포 전에 터져야 한다."""
    import enum

    from pydantic import BaseModel

    class Bad(enum.StrEnum):
        NONE = ""

    class Model(BaseModel):
        x: Bad

    try:
        build_response_schema(Model)
    except ValueError as e:
        assert "빈 문자열" in str(e)
    else:
        raise AssertionError("빈 문자열 enum이 통과됐다")


# --- C6: 액션 집합 -----------------------------------------------------------


def test_action_type_values():
    """실기기에서 관찰된 액션만 둔다. 기다리기는 앱 폴링 담당이라 없다(C6).

    전역 제스처는 근거가 나온 것만 하나씩 올린다(C9) — 홈·뒤로는 아직 없다.
    여기에 값을 더하려면 실기기 로그를 근거로 들고 와야 한다.
    """
    assert {a.value for a in ActionType} == {
        "tap",
        "scroll",
        "type",
        "open_quick_settings",
        "open_app_drawer",
        "done",
    }


# --- C4 / C5: 상태를 어디에 두는가 -------------------------------------------


def test_scroll_metadata_lives_on_element():
    fields = UiElement.model_fields
    assert {"scroll_axis", "next_swipe_direction", "prev_swipe_direction"} <= set(fields)


def test_page_position_lives_on_screen_not_element():
    """페이지는 화면 단위 상태다. 요소마다 두면 '아무거나 집는' 실수를 재현한다(HANDOFF §2)."""
    assert {"current_page", "total_pages"} <= set(Screen.model_fields)
    assert "current_page" not in UiElement.model_fields
    assert "total_pages" not in UiElement.model_fields


def test_input_state_has_backward_compatible_defaults():
    """낡은 앱은 #112 필드를 보내지 않으므로 누락을 관측하지 않은 상태로 받아야 한다."""

    request = NextStepRequest.model_validate(
        {
            "goal": "검색하기",
            "screen": {
                "package_name": "com.example.app",
                "elements": [{"node_id": "n1", "label": "검색창"}],
            },
        }
    )

    assert request.screen.elements[0].editable is False
    assert request.screen.elements[0].focused is False
    assert request.screen.ime_visible is None


def test_input_state_deserializes_without_input_text():
    request = NextStepRequest.model_validate(
        {
            "goal": "검색하기",
            "screen": {
                "package_name": "com.example.app",
                "ime_visible": True,
                "elements": [
                    {
                        "node_id": "n1",
                        "label": "검색어 입력",
                        "role": "EditText",
                        "editable": True,
                        "focused": True,
                    }
                ],
            },
        }
    )

    assert request.screen.ime_visible is True
    assert request.screen.elements[0].editable is True
    assert request.screen.elements[0].focused is True
    assert "text" not in UiElement.model_fields


# --- C7 / C8: 요청·에러 ------------------------------------------------------


def test_request_carries_client_version():
    req = NextStepRequest(goal="x", screen=Screen(), client_version="0.1.0")
    assert req.client_version == "0.1.0"
    # 낡은 앱이 안 보낼 수도 있으므로 선택 필드여야 한다.
    assert NextStepRequest(goal="x", screen=Screen()).client_version is None


def test_step_carries_the_whole_guidance_record():
    """스텝 하나 = 안내 하나. 문장과 구조를 한 레코드로 합친 것이 C11 개정의 요점이다."""

    step = GuideStep(
        instruction="아래쪽으로 스크롤해 주세요.",
        action_type=ActionType.SCROLL,
        direction=SwipeDirection.DOWN,
        target_label="연결",
        screen_hint="설정 앱의 메뉴 목록 화면",
        structure_signature="a" * 64,
        screen_changed=True,
    )
    req = NextStepRequest(goal="x", screen=Screen(), steps=[step])

    assert req.steps == [step]
    # 관측이 없으면 없는 값으로 온다 — performed는 귀속 미확인(#319, None을 "안 누름"으로 읽지
    # 않는다), content_signature는 label 없는 화면이거나 이 값을 안 채우는 낡은 앱(#410)이다.
    assert step.performed is None
    assert step.content_signature is None


def test_step_rejects_non_digest_structure_signature():
    try:
        GuideStep(
            instruction="누르세요.",
            action_type=ActionType.TAP,
            structure_signature="raw screen label",
        )
    except ValueError as error:
        assert "structure_signature" in str(error)
    else:
        raise AssertionError("화면 원문으로 쓸 수 있는 서명이 통과됐다")


def test_content_signature_rejects_a_non_digest_value():
    """형식은 `structure_signature`와 같다(#410). 어긋나면 앱이 다른 규칙으로 만들고 있는 것이다.

    같은 레코드에 나란히 서는 두 값이라 한쪽만 원문을 받아들이면 그 자리로 label이 샌다.
    """

    for bad in ("a" * 63, "A" * 64, "화면 원문"):
        for build in (
            lambda value: Screen(content_signature=value),
            lambda value: GuideStep(
                instruction="누르세요.",
                action_type=ActionType.TAP,
                structure_signature="a" * 64,
                content_signature=value,
            ),
        ):
            try:
                build(bad)
            except ValueError as error:
                assert "content_signature" in str(error)
            else:
                raise AssertionError(f"{bad!r}가 통과됐다")


def test_scroll_step_requires_a_direction():
    """방향 없는 scroll 이력은 "무엇을 안내했는지"를 말하지 못한다(C3와 같은 이유)."""

    try:
        GuideStep(
            instruction="넘겨 주세요.",
            action_type=ActionType.SCROLL,
            structure_signature="a" * 64,
        )
    except ValueError as error:
        assert "direction" in str(error)
    else:
        raise AssertionError("방향 없는 scroll 스텝이 통과됐다")


def test_step_history_has_a_request_size_bound():
    step = GuideStep(
        instruction="누르세요.",
        action_type=ActionType.TAP,
        structure_signature="a" * 64,
    )

    try:
        NextStepRequest(goal="x", screen=Screen(), steps=[step] * 11)
    except ValueError as error:
        assert "steps" in str(error)
    else:
        raise AssertionError("스텝 이력 상한을 넘긴 요청이 통과됐다")


def test_response_carries_server_generated_screen_signature():
    response = NextStepResponse(
        action=GuideAction(
            expected_result="완료",
            screen_summary="테스트 화면",
            evidence="근거",
            action_type=ActionType.TAP,
            target_node_id="n1",
            instruction="누르세요.",
        ),
        model="stub",
        screen_signature="b" * 64,
    )

    assert response.screen_signature == "b" * 64


def test_api_error_can_carry_retry_and_request_id():
    err = ApiError(
        kind=ErrorKind.QUOTA,
        message="rate limited",
        retry_after_seconds=30,
        request_id="abc",
    )
    assert err.retry_after_seconds == 30
    assert err.request_id == "abc"


# --- C3: scroll에는 방향이 있어야 실행 가능하다 -------------------------------


def test_stub_scrolls_with_direction_when_nothing_clickable():
    req = NextStepRequest(
        goal="설정 찾기",
        screen=Screen(
            elements=[
                UiElement(
                    node_id="n1",
                    label="목록",
                    clickable=False,
                    can_scroll_forward=True,
                    next_swipe_direction=SwipeDirection.UP,
                )
            ]
        ),
    )
    action = StubLlmClient().reason(req)
    assert action.action_type is ActionType.SCROLL
    assert action.direction is SwipeDirection.UP
    assert action.target_node_id == "n1"


def test_stub_scroll_instruction_points_the_same_way_as_the_direction():
    """스텁 문장도 `direction`과 **같은 말**이어야 한다(C17 · #109).

    문장을 만드는 자리는 셋이다 — 프롬프트로 유도하는 모델, 가드레일, 그리고 이 스텁.
    스텁만 고정 문장("더 내려 보세요")이던 동안 `direction`과 어긋난 조합을 만들고 있었다.
    `eval/harness.py`가 이 경로를 직접 돌고, 앱이 방향을 그리기 시작하면 화면에서 어긋난다.
    기준이 보러 가는 방향으로 바뀌었으니 문장도 화면이 가는 쪽으로 쓴다 — 손가락 동작은
    앱이 그림으로 따로 보여준다.
    """

    expected_words = {
        SwipeDirection.UP: "위쪽으로",
        SwipeDirection.DOWN: "아래쪽으로",
        SwipeDirection.LEFT: "왼쪽 화면",
        SwipeDirection.RIGHT: "오른쪽 화면",
    }

    for direction, word in expected_words.items():
        req = NextStepRequest(
            goal="설정 찾기",
            screen=Screen(
                elements=[
                    UiElement(
                        node_id="n1",
                        label="목록",
                        clickable=False,
                        can_scroll_forward=True,
                        next_swipe_direction=direction,
                    )
                ]
            ),
        )

        action = StubLlmClient().reason(req)

        assert action.direction is direction
        assert word in action.instruction, f"{direction}: {action.instruction}"
        # 반대말이 섞이면 방향이 둘인 문장이 된다.
        opposite = expected_words[
            {
                SwipeDirection.UP: SwipeDirection.DOWN,
                SwipeDirection.DOWN: SwipeDirection.UP,
                SwipeDirection.LEFT: SwipeDirection.RIGHT,
                SwipeDirection.RIGHT: SwipeDirection.LEFT,
            }[direction]
        ]
        assert opposite not in action.instruction


def test_scroll_action_without_direction_is_rejected():
    """방향 없는 scroll은 앱이 안내할 수 없는 계약 위반이다(C3)."""
    try:
        GuideAction(
            expected_result="다음 항목이 보임",
            screen_summary="테스트 화면",
            evidence="목록이 보임",
            action_type=ActionType.SCROLL,
            instruction="화면을 쓸어 주세요.",
        )
    except ValueError as error:
        assert "direction" in str(error)
    else:
        raise AssertionError("방향 없는 scroll이 통과됐다")


# --- C9·C10: 실기기 근거로 들어온 계약 ----------------------------------------


def test_app_drawer_action_is_offered_to_the_model():
    """앱서랍이 선택지에 없으면 홈에 아이콘 없는 앱은 영원히 못 찾는다(C9).

    Gemini 구조화 출력의 enum은 이 선언에서 나온다. 값이 빠지면 모델이 고를 수가 없어,
    실기기에서 관찰된 스크롤 무한 반복이 그대로 재현된다.
    """
    assert ActionType.OPEN_APP_DRAWER.value == "open_app_drawer"
    action_type = GUIDE_ACTION_SCHEMA["properties"]["action_type"]
    assert "open_app_drawer" in action_type["enum"]


def test_app_drawer_action_needs_no_target():
    """화면 요소가 아니라 제스처다. target_node_id 없이 만들어져야 한다(C9)."""
    action = GuideAction(
        expected_result="앱 목록이 열린다",
        screen_summary="테스트 화면",
        evidence="홈에 찾는 앱 아이콘이 없음",
        action_type=ActionType.OPEN_APP_DRAWER,
        instruction="화면 가운데를 위로 쓸어올려 주세요.",
    )
    assert action.target_node_id is None


def test_screen_distinguishes_no_scrollable_from_unknown():
    """False(없다)와 None(앱이 안 채웠다)이 섞이면 안 된다(C10)."""
    assert Screen().has_scrollable_element is None
    assert Screen(has_scrollable_element=False).has_scrollable_element is False


def test_stub_raises_when_stuck():
    """막힘 상태를 조용히 '기다리기'로 덮지 않는다(C6)."""
    req = NextStepRequest(goal="x", screen=Screen(elements=[]))
    try:
        StubLlmClient().reason(req)
    except LlmError as e:
        assert e.kind is ErrorKind.SERVER
    else:
        raise AssertionError("막힌 상태인데 액션을 돌려줬다")
