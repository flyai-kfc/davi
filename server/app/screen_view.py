"""화면 요소를 **어느 범위로 볼지**의 단일 결정 지점(PR #336).

`obscured`가 계약에 들어오면서 "전체 요소"와 "모델이 보는 요소"가 갈라졌다. 이 구분을
프롬프트와 가드레일이 **각자 판단하면 반드시 어긋난다** — 실제로 리뷰 세 라운드가 전부
"이 자리만 아직 전체를 본다"는 지적이었다. 그래서 판단을 여기 한 곳에 모으고 양쪽이 import한다.

프롬프트(`prompt.py`)에 두지 않는 이유는 이것이 표현 규칙이 아니라 **정책**이어서다.
가드레일도 같은 값을 봐야 하는데, 가드가 프롬프트 모듈을 들여다보는 모양이 되면 의존 방향이
뒤집힌 것처럼 읽힌다.
"""

from __future__ import annotations

from .schemas import UiElement


def obscured_observation_unreliable(elements: list[UiElement]) -> bool:
    """이 화면의 `obscured` 관측을 통째로 불신할지.

    근거: 무언가를 가리는 쪽(모달·시트)은 **자기 자신이 항상 보인다** — 덮는 요소가 가려질
    수는 없다. 그래서 "요소 전부가 가려졌다"는 화면은 실제로 존재하지 않고, 그렇게 관측됐다면
    앱의 기하 판정이 오작동한 것이다(PR #336 "남은 것"의 과잉 판정 시나리오).

    이 판정을 프롬프트(요소 목록 폴백)만 보고 가드가 안 보면, 폴백으로 실린 요소를 모델이
    골랐을 때 가드가 전부 거부해 **백오프 → 같은 프롬프트 → 같은 거부**의 뺑뺑이가 된다.
    """

    return bool(elements) and all(element.obscured is True for element in elements)


def visible_elements(elements: list[UiElement]) -> list[UiElement]:
    """사용자가 실제로 보고 누를 수 있는 요소만.

    **모델에게 보여 주는 목록이자, 서버가 판단에 쓰는 목록이다.** 둘이 달라지면 프롬프트가
    말한 것을 가드가 거부하는 모순이 생긴다.

    `None`(미관측)은 뺄 근거가 없으므로 남긴다(C10의 세 상태 규약). 전부 가려졌다고 관측된
    화면은 원본을 그대로 돌려준다([obscured_observation_unreliable]).
    """

    if obscured_observation_unreliable(elements):
        return elements
    return [element for element in elements if element.obscured is not True]


def has_scroll_metadata(element: UiElement) -> bool:
    """앱이 이 요소의 스크롤 상태를 관측해 보냈는가(C4).

    프롬프트와 가드가 같은 기준을 써야 해서 여기 둔다 — 두 벌로 두면 한쪽만 고쳐진다.
    """

    return (
        element.can_scroll_forward is not None
        or element.can_scroll_backward is not None
        or element.scroll_axis is not None
    )


def scrollable_is_only_obscured(screen_elements: list[UiElement]) -> bool:
    """스크롤 메타가 **가려진 요소에만** 있는가.

    `Screen.has_scrollable_element`는 화면 단위 관측이라 앱이 **가림 판정과 무관하게** 채운다
    (`UiTreeSerializer.observeScrollable`은 모든 노드에서 돈다). 그래서 스크롤되지 않는 모달이
    스크롤 목록을 덮으면 "스크롤 가능한 요소가 관측되었다"는 사실만 남고 목록에는 그 요소가
    없다 — 모델이 그 말을 믿고 scroll을 내면 후보가 비어 실패하고, 재질의마다 같은 미끼가
    다시 나간다(PR #336 리뷰 3R).

    **앱에서 고치지 않은 이유**는 그 필드의 뜻을 바꾸는 계약 변경이 되기 때문이다(C10은
    "보이는 트리 전체에서 하나라도 관측했는지"로 정의한다). 관측은 실제로 있었으므로
    "관측되지 않았다"로 뒤집는 것도 거짓이다. 사실을 그대로 두고 **단서를 붙이는 쪽**을 택한다.
    """

    visible = visible_elements(screen_elements)
    if any(has_scroll_metadata(element) for element in visible):
        return False
    return any(has_scroll_metadata(element) for element in screen_elements)
