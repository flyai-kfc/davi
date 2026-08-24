"""완료 판정이 근거를 화면과 대조하는지 고정한다(SPEC §4 결정).

`expected_result`·`evidence`는 필수 필드라 구조화 출력이 항상 채운다. 비었는지만 보는 검사는
`done`을 언제나 통과시켰다 — 조기 종료를 막으라고 만든 플래그가 항상 참이었다.
"""

from __future__ import annotations

import pytest
from app.llm import LlmClient
from app.reasoning import next_step
from app.schemas import (
    ActionType,
    GuideAction,
    NextStepRequest,
    Screen,
    UiElement,
)


class _DoneLlmClient(LlmClient):
    """`done`을 내는 fake. `StubLlmClient`는 `done`을 내지 않아 이 경로를 못 만든다."""

    def __init__(self, evidence: str, expected_result: str = "카카오톡이 열려 있다") -> None:
        self._evidence = evidence
        self._expected_result = expected_result

    def reason(self, req: NextStepRequest) -> GuideAction:
        return GuideAction(
            expected_result=self._expected_result,
            screen_summary="테스트 화면",
            evidence=self._evidence,
            action_type=ActionType.DONE,
            instruction="다 되었습니다.",
        )


def _screen(*labels: str) -> Screen:
    return Screen(
        package_name="com.kakao.talk",
        elements=[
            UiElement(node_id=f"n{i}", label=label, clickable=True)
            for i, label in enumerate(labels, start=1)
        ],
    )


def _verified(evidence: str, screen: Screen, **kwargs) -> bool:
    request = NextStepRequest(goal="카카오톡 열기", screen=screen)
    return next_step(request, _DoneLlmClient(evidence, **kwargs)).verified


def test_done_with_evidence_that_is_not_on_screen_is_not_verified():
    """지어낸 근거는 걸러진다 — 이 이슈의 핵심."""

    assert not _verified("채팅방 목록이 보입니다", _screen("Wi-Fi", "블루투스", "소리와 진동"))


def test_done_quoting_a_screen_label_is_verified():
    assert _verified("화면에 '채팅' 목록이 보임", _screen("채팅", "친구", "쇼핑"))


def test_korean_particles_do_not_break_the_match():
    """`카카오톡` label과 `카카오톡이`는 조사만 다르다. 토큰 일치로는 깨진다."""

    assert _verified("카카오톡이 열렸습니다", _screen("카카오톡", "전화"))


@pytest.mark.parametrize(
    ("label", "evidence"),
    [
        ("사진", "사진들이 보입니다"),
        ("카메라", "카메라라고 표시되어 있습니다"),
        ("바다", "바다라 판단했습니다"),
        ("지도", "지도고 다른 화면은 아닙니다"),
        ("시계", "시계며 일정 화면입니다"),
        ("시계", "시계였습니다"),
        ("설정", "설정에서는 확인했습니다"),
        ("설정", "설정에도 같은 값이 보입니다"),
    ],
)
def test_common_korean_particle_and_copula_pairs_are_verified(label, evidence):
    assert _verified(evidence, _screen(label))


@pytest.mark.parametrize(
    ("label", "evidence"),
    [
        ("카메라", "'카메라'라는 앱이 열렸습니다"),
        ("설정", "'설정'에만 값이 보입니다"),
        ("설정", "'설정'에서도 확인했습니다"),
        ("설정", "'설정'까지는 확인했습니다"),
        ("설정", "'설정'이지만 다른 화면은 아닙니다"),
    ],
)
def test_quoted_label_accepts_particles_outside_the_quote(label, evidence):
    """프롬프트가 요구하는 따옴표가 checker의 어절 경계를 명확하게 만든다."""
    assert _verified(evidence, _screen(label))


def test_verbal_connective_is_not_mistaken_for_a_particle():
    """`하고`는 조사일 수도 있지만 동사 활용도 되어 저장 전 버튼 오탐을 만든다."""
    assert not _verified("저장하고 목록으로 돌아왔습니다", _screen("저장"))
    assert _verified("'저장'하고 목록으로 돌아왔습니다", _screen("저장"))


def test_punctuation_and_spacing_differences_are_absorbed():
    """실측된 좋은 근거는 쉼표만 다르고 label과 같은 문자열이다(HANDOFF §9)."""

    assert _verified(
        "Wi-Fi, 사용 중, AITECHLAB 연결됨",
        _screen("Wi-Fi 사용 중 AITECHLAB 연결됨"),
    )
    assert _verified("WiFi가 연결됨", _screen("Wi-Fi"))


def test_short_but_two_character_evidence_still_passes():
    """두 글자 label은 짧아도 화면에서 정확한 어절로 인용했으면 통과한다."""

    assert _verified("지도", _screen("지도", "사진", "설정"))


def test_partial_word_with_opposite_state_is_not_verified():
    """`사용 중`은 `사용 중지`의 부분 문자열이지만 정반대 상태다(#71)."""

    assert not _verified("사용 중지했습니다", _screen("사용 중"))
    assert _verified("현재 사용 중입니다", _screen("사용 중"))


def test_lexical_continuation_is_not_a_quoted_label():
    assert not _verified("Mapping 작업을 마쳤습니다", _screen("Map"))


def test_screen_without_any_label_is_not_verified():
    """label이 하나도 없으면 대조할 것이 없다 — 검사 불가는 통과가 아니다(SPEC §4 2026-08-21 개정).

    예전에는 통과시켰다. 그 결정은 앱이 이런 화면을 보내지 않는다는 전제 위에 있었는데, 앱 전환
    직후 스플래시가 그대로 온다는 실측(#331)이 전제를 깼다.
    """

    assert not _verified("다 되었습니다", _screen("", ""))


def test_empty_screen_from_app_transition_is_not_verified():
    """실측 재현(#331) — 앱을 연 직후 요소가 하나도 없는 화면에서 온 done.

    에이닷 `element_count=0`, 카카오 T `element_count=1`(그 하나도 빈 label). 이 화면에서 done이
    통과하면 앱은 곧바로 종료 흐름으로 간다(`GuideSession.onDone`).
    """

    assert not _verified("일정이 저장되었습니다", _screen())


def test_hintless_editable_only_screen_is_not_verified():
    """#112가 늘린 빈 label 입력 화면도 같은 규칙을 받는다.

    label이 없어 대조할 수 없다는 점은 스플래시와 다르지 않다. 여기서 정말로 끝난 목표는
    `verified=false`로 몇 턴 늦게 끝날 뿐, 못 끝나지는 않는다 — 앱의 재시도 예산이 받는다.
    """

    screen = Screen(
        package_name="com.example.app",
        ime_visible=True,
        elements=[
            UiElement(
                node_id="n1",
                label="",
                role="EditText",
                editable=True,
                focused=True,
            )
        ],
    )

    assert not _verified("다 되었습니다", screen)


def test_non_latin_labels_are_still_compared():
    """한자·키릴 label이 정규화에서 통째로 사라지면 검사가 조용히 꺼진다(PR #62 리뷰 2번)."""

    assert not _verified("아무 말이나 지어냅니다", _screen("微信", "設定"))
    assert not _verified("아무 말이나 지어냅니다", _screen("Настройки", "Камера"))
    assert _verified("設定 화면이 보임", _screen("微信", "設定"))


def test_screen_of_single_character_labels_is_not_enough_to_verify_done():
    """숫자 키패드의 한 글자는 대부분의 문장에 우연히 들어가므로 근거로 쓰지 않는다(#71)."""

    keypad = _screen("1", "2", "3", "0")

    assert not _verified("결제가 완료되었습니다", keypad)
    assert not _verified("3을 눌렀습니다", keypad)


def test_empty_evidence_is_not_verified():
    assert not _verified("   ", _screen("채팅"))


def test_empty_expected_result_is_not_verified():
    assert not _verified("채팅 목록이 보임", _screen("채팅"), expected_result="  ")


def test_evidence_is_compared_against_masked_labels():
    """모델은 마스킹된 label을 본다. 대조도 마스킹 후 값으로 해야 한다(SPEC §2).

    2차 마스킹이 지운 값은 모델이 볼 수 없었으므로, 그 값을 인용한 근거는 화면 근거가 아니다.
    """

    screen = _screen("hong@example.com", "채팅")

    assert not _verified("hong@example.com 님을 찾았습니다", screen)
    assert _verified("채팅 목록이 보임", screen)


def test_non_done_action_is_never_verified():
    request = NextStepRequest(goal="카카오톡 열기", screen=_screen("채팅"))

    class _TapClient(LlmClient):
        def reason(self, req: NextStepRequest) -> GuideAction:
            return GuideAction(
                expected_result="카카오톡이 열려 있다",
                screen_summary="테스트 화면",
                evidence="채팅",
                action_type=ActionType.TAP,
                target_node_id="n1",
                instruction="'채팅'을 눌러 주세요.",
            )

    assert not next_step(request, _TapClient()).verified
