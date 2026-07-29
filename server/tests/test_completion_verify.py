"""완료 판정이 근거를 화면과 대조하는지 고정한다(SPEC §4 결정).

`expected_result`·`evidence`는 필수 필드라 구조화 출력이 항상 채운다. 비었는지만 보는 검사는
`done`을 언제나 통과시켰다 — 조기 종료를 막으라고 만든 플래그가 항상 참이었다.
"""

from __future__ import annotations

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


def test_punctuation_and_spacing_differences_are_absorbed():
    """실측된 좋은 근거는 쉼표만 다르고 label과 같은 문자열이다(HANDOFF §9)."""

    assert _verified(
        "Wi-Fi, 사용 중, AITECHLAB 연결됨",
        _screen("Wi-Fi 사용 중 AITECHLAB 연결됨"),
    )


def test_lazy_but_real_evidence_still_passes():
    """`evidence='지도'` 같은 성의 없는 근거는 **의도적으로** 통과시킨다(SPEC §4).

    임계값을 근거 없이 정하면 정당한 완료를 막는다. 실사용 로그를 보고 다시 연다.
    """

    assert _verified("지도", _screen("지도", "사진", "설정"))


def test_screen_without_any_label_is_not_failed():
    """label이 하나도 없으면 검사할 수 없다. 없는 것을 근거로 거짓 판정을 만들지 않는다."""

    assert _verified("다 되었습니다", _screen("", ""))


def test_non_latin_labels_are_still_compared():
    """한자·키릴 label이 정규화에서 통째로 사라지면 검사가 조용히 꺼진다(PR #62 리뷰 2번)."""

    assert not _verified("아무 말이나 지어냅니다", _screen("微信", "設定"))
    assert not _verified("아무 말이나 지어냅니다", _screen("Настройки", "Камера"))
    assert _verified("設定 화면이 보임", _screen("微信", "設定"))


def test_screen_of_single_character_labels_is_still_compared():
    """숫자 키패드처럼 전부 한 글자면, 검사를 끄는 대신 그것들로 대조한다."""

    keypad = _screen("1", "2", "3", "0")

    assert not _verified("결제가 완료되었습니다", keypad)
    assert _verified("3을 눌렀습니다", keypad)


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
                evidence="채팅",
                action_type=ActionType.TAP,
                target_node_id="n1",
                instruction="'채팅'을 눌러 주세요.",
            )

    assert not next_step(request, _TapClient()).verified
