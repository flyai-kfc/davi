"""요소의 행동 상태와 창 제목이 프롬프트·서명·마스킹을 제대로 지나는지 고정한다(C18 · #271).

**왜 이 테스트가 있나.** `clickable`은 뷰의 설정값이라 상태가 아니다. 상태를 안 실으면
비활성 버튼을 누르라고 안내하고, 스위치를 켜도 같은 화면으로 읽어 다음 안내가 나가지 않는다.
서명 쪽 검사가 특히 중요하다 — 필드만 늘리고 서명을 그대로 두면 증상이 하나도 안 고쳐진다.
"""

from app.masking import mask_screen, mask_screen_model
from app.prompt import (
    DISABLED_ELEMENT_INSTRUCTION,
    HINT_TEXT_INSTRUCTION,
    TOGGLE_STATE_INSTRUCTION,
    build_prompt,
)
from app.schemas import NextStepRequest, Screen, UiElement
from app.signature import screen_signature


def _request(**screen_fields) -> NextStepRequest:
    screen_fields.setdefault("package_name", "com.example.app")
    screen_fields.setdefault("elements", [])
    return NextStepRequest(goal="와이파이 켜기", screen=Screen(**screen_fields))


def test_disabled_element_is_named_and_explained():
    prompt = build_prompt(
        _request(
            elements=[
                UiElement(node_id="n1", label="다음", clickable=True, enabled=False),
                UiElement(node_id="n2", label="취소", clickable=True, enabled=True),
            ]
        )
    )
    assert "enabled=False" in prompt
    assert DISABLED_ELEMENT_INSTRUCTION in prompt
    # 활성 요소에는 값을 싣지 않는다 — 요소가 최대 200개라 기본값 줄이 토큰을 먹는다.
    assert "enabled=True" not in prompt


def test_says_nothing_about_enabled_when_the_app_did_not_fill_it():
    """낡은 앱의 침묵을 "비활성"으로 읽으면 tap 안내가 통째로 막힌다(C10과 같은 세 상태)."""

    prompt = build_prompt(
        _request(elements=[UiElement(node_id="n1", label="다음", clickable=True)])
    )
    assert "enabled" not in prompt
    assert DISABLED_ELEMENT_INSTRUCTION not in prompt


def test_toggle_state_is_carried_for_checkable_elements():
    prompt = build_prompt(
        _request(
            elements=[
                UiElement(
                    node_id="n1", label="Wi-Fi", role="Switch", clickable=True, checked=False
                ),
                UiElement(
                    node_id="n2", label="블루투스", role="Switch", clickable=True, checked=True
                ),
            ]
        )
    )
    assert "checked=False" in prompt
    assert "checked=True" in prompt
    assert TOGGLE_STATE_INSTRUCTION in prompt


def test_selected_is_carried_only_when_it_marks_the_current_item():
    prompt = build_prompt(
        _request(
            elements=[
                UiElement(node_id="n1", label="연결", clickable=True, selected=True),
                UiElement(node_id="n2", label="소리", clickable=True, selected=False),
            ]
        )
    )
    assert "selected=True" in prompt
    assert "selected=False" not in prompt
    assert TOGGLE_STATE_INSTRUCTION in prompt


def test_empty_input_is_named_without_sending_the_value():
    prompt = build_prompt(
        _request(
            elements=[
                UiElement(node_id="n1", label="검색어 입력", editable=True, showing_hint_text=True),
            ]
        )
    )
    assert "showing_hint_text=True" in prompt
    assert HINT_TEXT_INSTRUCTION in prompt


def test_prompt_never_claims_a_field_is_filled():
    """Chromium이 이 값을 안 세워 웹뷰의 빈 칸도 False로 온다(#288 리뷰 실측).

    False를 "값이 들어 있다"로 말하면 웹뷰에서 거짓이 되므로, 프롬프트는 False를 싣지도
    설명하지도 않는다. 세 상태 규약으로는 못 막는다 — 침묵이 아니라 틀린 값이 오기 때문이다.
    """

    prompt = build_prompt(
        _request(
            elements=[
                UiElement(node_id="n1", label="검색", editable=True, showing_hint_text=False),
            ]
        )
    )
    assert "showing_hint_text" not in prompt
    assert HINT_TEXT_INSTRUCTION not in prompt
    assert "값이 들어 있습니다" not in prompt


def test_state_sentences_are_absent_when_no_element_carries_the_value():
    prompt = build_prompt(
        _request(elements=[UiElement(node_id="n1", label="다음", clickable=True)])
    )
    assert TOGGLE_STATE_INSTRUCTION not in prompt
    assert HINT_TEXT_INSTRUCTION not in prompt


def test_window_title_names_the_screen_alongside_the_package():
    """package만으로는 화면을 알 수 없다 — 설정>배터리가 com.samsung.android.lool로 온다(#269)."""

    prompt = build_prompt(_request(package_name="com.samsung.android.lool", window_title="배터리"))
    assert "현재 화면: '배터리' (com.samsung.android.lool)" in prompt
    assert "현재 앱 패키지" not in prompt


def test_screen_line_falls_back_to_the_package_when_the_window_has_no_title():
    prompt = build_prompt(_request(package_name="com.example.app"))
    assert "현재 앱 패키지: com.example.app" in prompt


def test_window_title_goes_through_second_stage_masking():
    """대화방 제목은 사람 이름이고 문서 뷰어는 파일명을 싣는다 — label과 같은 취급이다."""

    screen = {"package_name": "p", "window_title": "010-1234-5678 님", "elements": []}
    assert mask_screen(screen)["window_title"] == "••• 님"
    # 원본은 그대로여야 한다(비파괴).
    assert screen["window_title"] == "010-1234-5678 님"

    model = Screen(package_name="p", window_title="hong@example.com")
    assert mask_screen_model(model).window_title == "•••"
    assert model.window_title == "hong@example.com"


def test_missing_window_title_survives_masking():
    assert mask_screen({"package_name": "p", "elements": []}).get("window_title") is None
    assert mask_screen_model(Screen(package_name="p")).window_title is None


def _switch_screen(checked: bool) -> Screen:
    return Screen(
        package_name="com.android.settings",
        window_title="Wi-Fi",
        elements=[
            UiElement(node_id="n1", label="Wi-Fi", role="Switch", clickable=True, checked=checked)
        ],
    )


def test_signature_changes_when_only_the_switch_flips():
    """이 화면에서 바뀌는 것은 checked 하나뿐이다 — label도 role도 그대로다."""

    assert screen_signature(_switch_screen(False)) != screen_signature(_switch_screen(True))


def test_signature_changes_when_only_the_button_becomes_enabled():
    def screen(enabled: bool) -> Screen:
        return Screen(
            package_name="com.example.app",
            elements=[UiElement(node_id="n1", label="다음", clickable=True, enabled=enabled)],
        )

    assert screen_signature(screen(False)) != screen_signature(screen(True))


def test_signature_changes_when_the_input_gets_filled():
    """입력 원문을 안 보내므로(#112) label은 그대로다. 이 값이 유일한 변화 신호다."""

    def screen(showing_hint: bool) -> Screen:
        return Screen(
            package_name="com.example.app",
            elements=[
                UiElement(
                    node_id="n1", label="검색어 입력", editable=True, showing_hint_text=showing_hint
                )
            ],
        )

    assert screen_signature(screen(True)) != screen_signature(screen(False))


def test_signature_leaves_the_window_title_out():
    """제목은 label과 위험 등급이 같다 — 서버 해시에 남기지 않는다(#288 리뷰).

    기기 안에서만 쓰는 `ScreenSignature`는 label과 제목을 둘 다 포함한다. 그 값은 기기를
    떠나지 않으므로 여기의 기준과 어긋나지 않는다.
    """

    def screen(title: str) -> Screen:
        return Screen(package_name="com.android.settings", window_title=title)

    assert screen_signature(screen("연결")) == screen_signature(screen("배터리"))


def test_signature_is_stable_for_a_client_that_fills_nothing():
    """낡은 앱은 필드를 안 보낸다. 그 침묵이 서명을 흔들면 안 된다."""

    screen = Screen(
        package_name="com.example.app",
        elements=[UiElement(node_id="n1", label="다음", clickable=True)],
    )
    assert screen_signature(screen) == screen_signature(screen.model_copy(deep=True))
