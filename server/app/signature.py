"""화면 구조 서명. `prompt`와 `reasoning`이 함께 쓴다.

**`reasoning`에서 옮겨 왔다** — 이력 줄이 "지금 그 화면에 돌아와 있는가"를 말하려면
`prompt`가 현재 화면의 서명을 계산해야 하는데, `prompt -> reasoning` import는 순환이다.
계산 규칙은 옮기면서 바꾸지 않았다.
"""

from __future__ import annotations

import hashlib
import json

from .schemas import Screen


def screen_signature(screen: Screen) -> str:
    """PII·volatile text를 제외한 화면 구조의 결정론적 서명.

    label은 서버가 이미 받는 값이지만 해시만 장기간 남아도 흔한 문자열은 역추정할 수 있다.
    그래서 label의 원문·해시·길이 모두 제외한다. 시계·배터리 텍스트 변화도 같은 화면으로 본다.

    **`window_title`도 같은 이유로 뺀다**(#288 리뷰). 대화방 제목은 사람 이름이고 문서 뷰어는
    파일명을 싣는다 — 위험 등급이 label과 같다. 2차 마스킹을 거쳤다는 것은 근거가 못 된다.
    label도 마스킹 뒤에 제외되기 때문이다. 기기 안에서만 쓰는 `ScreenSignature`는 label과 제목을
    둘 다 포함하는데, 그 값은 기기를 떠나지 않으므로 여기의 기준과 어긋나지 않는다.
    대신 package/page/요소 순서·역할·좌표·상호작용/스크롤 상태가 바뀌면 서명이 바뀐다.

    이 선택은 구조·좌표가 같고 내용만 바뀐 화면을 구분하지 못한다. 목록 항목이 label만 바뀌면
    실제 화면 변화가 있어도 같은 서명이다. 따라서 이 서명만으로 모든 화면 동일성을 주장하면 안 된다.

    **지금 이 값을 읽는 서버 코드는 없다.** 반복 카운트를 걷으면서(#202) 소비자가 사라졌고,
    응답에 실어 앱이 이력에 붙이는 값으로만 남아 있다. 알고리즘이 바뀌면 앱이 보관한 이전
    서명과 새 서명이 달라지지만, 서버가 그 값으로 판정하지 않으므로 안내가 바뀌지는 않는다.
    """

    structure = {
        "package_name": screen.package_name,
        "current_page": screen.current_page,
        "total_pages": screen.total_pages,
        "has_scrollable_element": screen.has_scrollable_element,
        "ime_visible": screen.ime_visible,
        "elements": [
            {
                "node_id": element.node_id,
                "role": element.role,
                "clickable": element.clickable,
                "editable": element.editable,
                "focused": element.focused,
                # 행동 상태(C18). **이게 없으면 스위치를 켜도 같은 서명이 나온다** — label에
                # 입력 원문도 토글 상태도 들어가지 않으므로 이 자리가 유일한 변화 신호다(#271).
                "enabled": element.enabled,
                "checked": element.checked,
                "selected": element.selected,
                "showing_hint_text": element.showing_hint_text,
                # 모달이 뜨고 걷히면 같은 요소의 가림 여부가 뒤집힌다. 앱 시그니처
                # (`ScreenSignature`)에도 같은 이유로 들어 있어 두 서명이 대칭이다.
                "obscured": element.obscured,
                "bounds": element.bounds,
                "can_scroll_forward": element.can_scroll_forward,
                "can_scroll_backward": element.can_scroll_backward,
                "scroll_axis": element.scroll_axis,
                "next_swipe_direction": element.next_swipe_direction,
                "prev_swipe_direction": element.prev_swipe_direction,
            }
            for element in screen.elements
        ],
    }
    canonical = json.dumps(
        structure,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
