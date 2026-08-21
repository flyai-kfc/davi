"""추론 파이프라인: 실행 -> 명시적 검증 (SPEC §4, HANDOFF §5-3).

이 모듈은 HTTP를 모른다. 순수 함수로 두어 eval 하니스가 직접 import해 회귀 테스트한다
(server/CLAUDE.md, eval/CLAUDE.md). FastAPI 라우터는 이걸 호출만 한다.

**계획(sub-task) 단계는 무기한 연기됐다**(SPEC §3 비목표). PoC의 단일 스텝 반응형을 넘어선 것은
완료 검증과 가드레일 단일 결정 지점이지 계획이 아니다 — 스텁과 TODO를 여기서 걷었다(#230).
가드레일은 `_apply_guardrails` 한 곳에 우선순위를 드러내 통합한다(HANDOFF §5-4).
"""

from __future__ import annotations

import hashlib
import json
import re

from .config import settings
from .errors import LlmError
from .llm import LlmClient
from .masking import MaskingPolicy, active_masking_policy
from .schemas import (
    ActionType,
    ErrorKind,
    GuideAction,
    NextStepRequest,
    NextStepResponse,
    Screen,
    SwipeDirection,
    UiElement,
)
from .screen_view import (
    has_scroll_metadata,
    obscured_observation_unreliable,
    screen_has_cover,
    visible_elements,
)

_APP_DRAWER_FALLBACK_PACKAGES = {"com.sec.android.app.launcher"}

# 근거 대조 전에 양쪽에서 지우는 것: 구두점·공백 등 의미를 바꾸지 않는 문자.
#
# **글자는 문자 종류를 가리지 않고 남긴다.** 한글·라틴만 남기면 label이 한자·가나·키릴인 화면
# (微信, 設定, Настройки)에서 정규화 결과가 통째로 비어 검사가 조용히 꺼진다(PR #62 리뷰 2번).
_EVIDENCE_NOISE = re.compile(r"[^\w]+", re.UNICODE)

# 한 글자 label(아이콘·숫자)은 우연 일치가 잦아 우선 제외한다. 다만 화면 전체가 한 글자 label뿐이면
# (숫자 키패드 등) 검사를 끄는 대신 그것들로라도 대조한다.
_MIN_EVIDENCE_LABEL_CHARS = 2


def _apply_guardrails(action: GuideAction, req: NextStepRequest) -> GuideAction:
    """모든 행동 교정의 단일 결정 지점. 위에서 아래로 우선순위를 적용한다.

    HANDOFF §5-4 경고: 가드가 서로 덮어써 불안정했던 전례가 있다.
    새 가드는 이 함수에 우선순위를 드러내 추가하고, 서로 연쇄 적용하지 않는다.
    """
    # 0. **가려진 대상은 어떤 행동으로도 내보낼 수 없다.** 이것만은 교정이 아니라 전제 검사라
    #    아래 교정들과 연쇄하지 않는다 — 통과하거나 실패로 닫거나 둘뿐이다.
    _reject_obscured_target(action, req.screen)

    # scroll만 교정 대상이다. 대상 없는 scroll은 안내를 그 자리에서 죽인다(#202).
    if action.action_type is not ActionType.SCROLL:
        return action
    return _resolve_scroll(action, req.screen)


def _reject_obscured_target(action: GuideAction, screen: Screen) -> None:
    """모델이 **가려진 요소**를 대상으로 골랐으면 실패로 닫는다.

    실패 모양이 #202의 대상 없는 scroll과 같다: 사용자가 볼 수도 누를 수도 없는 자리를
    가리키면 화면이 그대로고 → 정체 판정 → **정체에서는 서버를 다시 부르지 않으므로**(#39)
    회복 경로가 없다. 실패로 닫으면 앱이 백오프 후 같은 화면을 다시 물어본다(`retryThisScreen`).

    프롬프트는 가려진 요소를 **목록에서 뺀다**(`prompt._visible_elements`). 그래도 여기서 한 번
    더 막는 이유는 미관측(`None`)과 경계 사례가 새기 때문이고, 무엇보다 **프롬프트를 거치지
    않고 대상이 정해지는 자리**가 있기 때문이다(`_resolve_scroll` 2단이 대상을 직접 채운다).
    실측에서 모델은 시트 뒤의 '치킨' 버튼을 골랐다.

    **다른 대상을 대신 고르지 않는다** — 가드는 교정이지 재선택이 아니다(`_resolve_scroll`과
    같은 경계). 무엇을 눌러야 하는지는 가려지지 않은 요소를 보고 모델이 다시 정할 일이다.

    `obscured`가 `None`(낡은 앱의 미관측)이면 아무 판정도 하지 않는다 — 침묵을 "가려짐"으로도
    "안 가려짐"으로도 읽지 않는다(C10의 세 상태 규약).
    """
    # 전부 가려졌다고 관측된 화면에서는 **판정하지 않는다.** 그런 화면은 실존하지 않아
    # (덮는 쪽은 항상 보인다) 관측 오작동의 신호이고, 프롬프트도 같은 판정으로 원본 목록을
    # 폴백으로 싣는다(`prompt._visible_elements`). 여기서만 계속 거부하면 폴백으로 실린
    # 요소를 모델이 고를 때마다 닫혀서, 백오프 → 같은 프롬프트 → 같은 거부의 뺑뺑이가 된다.
    if obscured_observation_unreliable(screen.elements):
        return
    target = _element_by_id(screen, action.target_node_id)
    if target is None or target.obscured is not True:
        return
    raise LlmError(
        ErrorKind.SERVER,
        "안내 대상이 다른 요소에 완전히 가려져 있어 사용자가 보거나 누를 수 없음",
    )


def _resolve_scroll(action: GuideAction, screen: Screen) -> GuideAction:
    """`scroll`의 대상을 요구한다. 교정하거나, 안전한 대체 행동으로 바꾸거나, 실패로 닫는다.

    실측된 실패는 이렇게 흘렀다(#202): `target=-`로 나간 scroll → 앱이 강조할 것이 없어
    `highlight miss` → 사용자가 화면 전체를 쓸어도 아무 일 없음 → 정체 판정 →
    **정체에서는 서버를 다시 부르지 않으므로**(#39, 폴링이 곧 호출량) 회복 경로가 없다.
    같은 모양이 서로 다른 앱에서 두 번 났고 한쪽은 `has_scrollable_element=true`였다 —
    "스크롤할 수 없는 화면"만의 문제가 아니라 **대상 해석이 비는 것 자체**가 원인이다.

    **앱 이름이 아니라 "대상 해석 실패"라는 일반 조건으로 쓴다**(루트 CLAUDE.md).
    """

    # 1. 관측하지 않은 것은 판정하지 않는다. 스크롤 메타를 하나도 안 보낸 낡은 앱의 침묵을
    #    "대상이 없다"로 읽으면, 실제로는 넘어가던 화면의 안내를 서버가 없애 버린다(C10).
    if not _observed_scroll_state(screen):
        return action

    # 2. 그 방향으로 스크롤할 수 있는 요소가 있으면 대상을 채워 내보낸다.
    candidates = _scroll_candidates(screen, action.direction)
    if candidates:
        named = _element_by_id(screen, action.target_node_id)
        if named is not None and any(e.node_id == named.node_id for e in candidates):
            return action
        return action.model_copy(update={"target_node_id": _widest(candidates).node_id})

    # 3. **덮인 화면에서는 "화면 자체"를 조작하라고 하지 않는다.** 아래 두 단은 대상이 요소가
    #    아니라 화면이라(페이지 넘김·앱서랍) 2단의 요소 필터가 닿지 않는다 — 페이지 위치는
    #    화면 단위 상태이고(C5) 앱은 가림 판정과 무관하게 채운다. 모달이 덮은 화면에서
    #    "옆으로 넘기세요"·"위로 쓸어올리세요"가 나가면 팝업이 제스처를 먹어 화면이 그대로고
    #    → 정체 → **정체에서는 서버를 다시 부르지 않으므로**(#39) 회복 경로가 없다. 이 PR이
    #    tap과 2단에서 닫은 실패와 같은 모양이라 같은 근거로 닫는다(PR #336 리뷰).
    #    **조건은 런처가 아니다** — page dot은 "N 페이지 중 M 페이지" 글자로 잡으므로
    #    (`UiTreeSerializer.parsePageDot`) 배너 캐러셀·온보딩처럼 페이지 안내가 있는 화면이면
    #    어디서든 4단이 열린다. 오히려 팝업이 뜬 화면일수록 2단 후보가 가림으로 비어 여기까지
    #    내려온다. 4단만 막고 5단을 두면 덮인 런처에서 앱서랍 안내가 대신 나가 같은 실패다.
    #    막힌 화면은 6단(실패)으로 간다 — 앱이 백오프 후 다시 물어보고, 그때 모델은 가려지지
    #    않은 요소(팝업 자신)만 보므로 "닫기"를 고를 수 있다.
    covered = screen_has_cover(screen.elements)

    # 4. 페이지로 넘기는 화면은 **대상 노드가 없는 것이 정상이다** — 대상이 요소가 아니라
    #    화면 자체다. 삼성 런처 홈·앱서랍은 페이지가 여러 장이어도 scrollable 노드가 0개다
    #    (HANDOFF §3-2). 아직 넘길 쪽이 남았으면 그대로 통과시킨다. 여기를 오류로 닫으면
    #    통과하던 정상 안내를 이 가드가 없애고(#251 리뷰), SPEC §3 시나리오 3의
    #    "앱서랍에서 다음 쪽"이 끊긴다.
    #    모델이 이 화면에서 `page_dot` 같은 요소를 target으로 골라도 비운다 — page dot은
    #    정보원이지 탭 대상이 아니고(HANDOFF §3-3), 대상이 화면 자체라는 근거와도 어긋난다
    #    (#251 리뷰).
    if not covered and _page_move_is_open(screen, action.direction):
        return action.model_copy(update={"target_node_id": None})

    # 5. 대상이 없다 — 이 scroll은 내보낼 수 없다. 안전한 대체 행동이 있는 화면은 그쪽으로 돌린다.
    if not covered and _can_open_app_drawer(screen):
        return GuideAction(
            expected_result=action.expected_result,
            # 행동만 바꾸는 교정이라 화면 요약은 모델이 쓴 것을 그대로 승계한다. 여기서 새로
            # 지어내면 앱이 이력에 담을 `screen_hint`가 실제 화면 관측과 어긋난다.
            screen_summary=action.screen_summary,
            evidence="런처 화면에 스크롤할 수 있는 요소가 없음",
            action_type=ActionType.OPEN_APP_DRAWER,
            instruction="화면 가운데를 위로 쓸어올려 전체 앱 목록을 열어 주세요.",
        )

    # 6. 그 밖에는 대체할 안전한 행동이 계약에 없다 — `done`은 성공 의미이고 "진행 불가"는
    #    보류 항목이다(server/CLAUDE.md). **틀린 안내를 내보내는 대신 실패로 닫는다.**
    #    앱은 이 화면을 백오프 후 다시 물어보므로(`retryThisScreen`), 정체와 달리 회복 경로가
    #    남는다.
    raise LlmError(ErrorKind.SERVER, _no_scroll_target_reason(screen, action.direction))


def _page_move_is_open(screen: Screen, direction: SwipeDirection | None) -> bool:
    """페이지로 넘기는 화면에서 요청 방향에 아직 넘길 쪽이 남았는가.

    **판정 근거는 프롬프트가 쓰는 것과 같은 사실이다**(`prompt._page_boundary` — #217).
    저쪽은 모델에게 "지금이 첫 쪽/마지막 쪽"이라고 말해 주고, 이쪽은 그 사실로 통과 여부를
    정한다. 새 지식이 아니라 이미 관측한 상태다.

    쪽 번호가 어긋나면(`current > total` 등) 아무 결론도 내지 않는다 — 저쪽과 같은 규칙이다.
    세로 방향은 페이지 이동이 아니므로 여기서 통과시키지 않는다.
    """

    current, total = screen.current_page, screen.total_pages
    if current is None or total is None:
        return False
    if total < 1 or not 1 <= current <= total:
        return False
    if direction is SwipeDirection.RIGHT:
        return current < total
    if direction is SwipeDirection.LEFT:
        return current > 1
    return False


def _can_open_app_drawer(screen: Screen) -> bool:
    """실기기로 확인된 런처 화면에만 현재의 안전한 대체 행동을 적용한다(C9).

    **반복 3회를 기다리지 않는다**(#202). 반복 카운트는 "이 scroll이 듣지 않는다"는 증거를
    모으는 장치였는데, 대상 해석 실패가 그 증거를 첫 요청에 결정론적으로 준다. 기다리는 동안
    나가던 헛안내 두 번은 그 자체로 #202가 없애려는 것이다.
    """

    if screen.package_name not in _APP_DRAWER_FALLBACK_PACKAGES:
        return False
    if screen.has_scrollable_element is not False:
        return False
    # 삼성 앱서랍도 같은 package + has_scrollable_element=False다. 앱서랍에는 page 정보가
    # 관측되므로, 페이지가 있는 런처 화면을 앱서랍 열기로 덮지 않는다. 일부 메타만 온 경우도
    # 안전하게 적용하지 않는다.
    return screen.current_page is None and screen.total_pages is None


def _observed_scroll_state(screen: Screen) -> bool:
    """앱이 스크롤 상태를 관측해 보냈는가. 침묵(`None`)은 사실이 아니다."""

    if screen.has_scrollable_element is not None:
        return True
    return any(has_scroll_metadata(element) for element in screen.elements)


def _scroll_candidates(screen: Screen, direction: SwipeDirection | None) -> list[UiElement]:
    """그 방향으로 더 갈 수 있다고 **앱이 관측한** 요소들.

    여유를 모르는 요소를 축만 보고 후보로 올리지 않는다. 앱은 스크롤 상태를 다섯 필드
    (`can_scroll_*`·`scroll_axis`·`next/prev_swipe_direction`) 통째로 채우거나 하나도 안 채우거나
    둘 중 하나라(`UiTreeSerializer.scrollStateOf`), "축만 아는 요소"는 계약상 표현될 뿐 관측된 적이
    없다. 없는 입력을 위한 두 번째 단은 검증할 수 없는 분기라 두지 않는다(#218이
    `SCROLL_ALL_ENDS_REACHED`를 걷어낸 것과 같은 근거).
    """

    if direction is None:  # pragma: no cover - 스키마가 scroll의 direction을 강제한다(C3)
        return []

    # **가려진 요소는 후보가 아니다.** 여기서 거르지 않으면 이 파일 맨 앞의 가림 검사를
    # 우회한다 — 그 검사는 모델이 고른 대상만 보는데, 아래 2단은 대상을 **직접 채워 넣는다**.
    # 모달이 뜬 화면에서 스크롤 가능한 것은 대개 뒤에 깔린 목록이라 흔한 경로다(PR #336 리뷰).
    # `None`(미관측)은 거르지 않는다 — 낡은 앱의 침묵을 "가려짐"으로 읽지 않는다(C10).
    # 전부 가려진 화면에서는 필터를 끈다 — `_reject_obscured_target`과 같은 근거·같은 판정이다.
    if obscured_observation_unreliable(screen.elements):
        return [e for e in screen.elements if _scrolls_toward(e, direction)]
    return [e for e in screen.elements if e.obscured is not True and _scrolls_toward(e, direction)]


def _scrolls_toward(element: UiElement, direction: SwipeDirection) -> bool:
    """이 요소가 그 방향으로 더 갈 수 있다고 앱이 관측했는가(C4).

    `can_scroll_forward`는 그 요소의 `next_swipe_direction` 쪽 여유다 — 요소마다 축이 다르므로
    값 하나로는 못 읽고 방향과 짝지어 봐야 한다(C17).
    """

    return bool(
        (element.can_scroll_forward and element.next_swipe_direction is direction)
        or (element.can_scroll_backward and element.prev_swipe_direction is direction)
    )


def _moves_along(element: UiElement, direction: SwipeDirection | None) -> bool:
    """이 요소가 그 방향의 축으로 움직이는가(여유는 보지 않는다)."""

    if direction is None:  # pragma: no cover - 스키마가 scroll의 direction을 강제한다(C3)
        return False
    return direction in {element.next_swipe_direction, element.prev_swipe_direction}


def _no_scroll_target_reason(screen: Screen, direction: SwipeDirection | None) -> str:
    """대상이 없는 이유를 상황별로 갈라 적는다.

    한 문장으로 덮으면 실기기 로그에서 서로 다른 화면이 한 칸에 섞인다 — 스크롤 요소가 아예
    없는 화면, 요소는 있는데 그 방향만 끝난 화면(가장 흔하다), 앱은 스크롤 요소가 있다고 했는데
    요소 목록에는 안 실린 화면(`UiTreeSerializer.MAX_ELEMENTS` 절단이 유력하다), 세로 목록만
    있는데 모델이 좌우를 고른 화면, 그리고 **가림 때문에 막힌 화면 둘**(그 방향의 스크롤 요소가
    가려진 경우 · 3단이 화면 단위 조작을 막은 경우)은 다음 수가 서로 다르다.

    **"이미 끝"은 그 방향의 축으로 움직이는 요소가 있을 때만 쓴다.** 축을 안 보면 세로 목록
    화면의 `scroll right`가 "right 방향은 이미 끝"으로 나가 네 번째 상황이 첫 칸에 섞인다.
    """

    # **"가려짐"을 "이미 끝"보다 먼저 본다.** 후보가 가려져서 빈 경우 아래 첫 분기가 참이 되어
    # (가려진 목록도 `can_scroll_forward=True`다) 끝이 아닌데 "이미 끝"으로 적힌다. 이 함수의
    # 존재 이유가 상황을 갈라 적는 것이라, 실기기 로그가 이 문구를 들고 오면 엉뚱한 데를 판다.
    if direction is not None and any(
        _scrolls_toward(element, direction) and element.obscured is True
        for element in screen.elements
    ):
        return (
            f"scroll 대상을 해석할 수 없다: {direction} 방향의 스크롤 요소가 "
            "다른 요소에 가려져 있음"
        )
    # **가림이 화면 단위 조작을 막은 경우도 따로 적는다.** 3단이 4·5단을 막고 여기로 보내면
    # 아래 분기들은 "스크롤 요소가 없다"고만 적는데(런처는 실제로 0개다), 없어서가 아니라
    # 덮여서 막은 것이라 실기기 로그가 엉뚱한 데를 판다.
    if screen_has_cover(screen.elements) and (
        _page_move_is_open(screen, direction) or _can_open_app_drawer(screen)
    ):
        return (
            "scroll 대상을 해석할 수 없다: 화면이 다른 요소에 가려져 "
            "화면 단위 조작(페이지 넘김·앱서랍)을 내보내지 않음"
        )
    if any(_moves_along(element, direction) for element in screen.elements):
        return f"scroll 대상을 해석할 수 없다: 스크롤 요소는 있으나 {direction} 방향은 이미 끝"
    if any(has_scroll_metadata(element) for element in screen.elements):
        return f"scroll 대상을 해석할 수 없다: 스크롤 요소가 {direction} 축으로 움직이지 않음"
    if screen.has_scrollable_element:
        return (
            "scroll 대상을 해석할 수 없다: 화면에 스크롤 요소가 있다고 왔는데 "
            "요소 목록에 실리지 않음(요소 수 절단 의심)"
        )
    return "scroll 대상을 해석할 수 없다: 그 방향으로 스크롤할 수 있는 요소가 화면에 없음"


def _element_by_id(screen: Screen, node_id: str | None) -> UiElement | None:
    if node_id is None:
        return None
    return next((e for e in screen.elements if e.node_id == node_id), None)


def _widest(candidates: list[UiElement]) -> UiElement:
    """후보가 여럿이면 화면에서 가장 넓은 스크롤 영역을 고른다.

    사용자는 요소가 아니라 **화면을 쓴다.** 목록 위에 작은 캐러셀이 겹친 화면에서 넓은 쪽이
    화면 전체 제스처가 실제로 움직이는 영역이고, 강조 사각형도 그쪽이 알아보기 쉽다.
    좌표가 없는 요소는 넓이 0으로 두고, 같은 넓이면 화면에 실린 순서를 지킨다(결정론).
    """

    _, widest = max(enumerate(candidates), key=lambda pair: (_area(pair[1]), -pair[0]))
    return widest


def _area(element: UiElement) -> int:
    if element.bounds is None or len(element.bounds) != 4:
        return 0
    left, top, right, bottom = element.bounds
    return max(right - left, 0) * max(bottom - top, 0)


def _screen_signature(screen: Screen) -> str:
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


def _verify_done(action: GuideAction, req: NextStepRequest) -> bool:
    """완료 판정 2차 검증. 근거가 **화면에 있는 것인지**까지 본다(SPEC §4 결정).

    두 근거 필드는 `GuideAction`의 필수 필드라 구조화 출력이 항상 채운다. 비었는지만 보면
    `done`은 언제나 통과했다(빈 evidence로 done을 거부한 실측 사례는 그 앞 단계에서 걸린 것이다).

    **충분한 근거인지는 판정하지 않는다.** `evidence='지도'`처럼 흔한 단어 하나로 형식만 채우는
    성의 없는 근거도(HANDOFF §9) 그 단어가 화면에 있으면 통과한다 — 임계값을 근거 없이 정하면
    정당한 완료를 막는다. 이 함수는 `verified` 플래그만 정확하게 만들고 안내를 막지 않는다.
    """
    if action.action_type is not ActionType.DONE:
        return False
    if not action.evidence.strip() or not action.expected_result.strip():
        return False
    return _evidence_quotes_screen(action.evidence, req.screen)


def _evidence_quotes_screen(evidence: str, screen: Screen) -> bool:
    """근거가 화면 label 중 하나를 실제로 물고 있는가.

    label은 2차 마스킹을 거친 값이다 — 모델이 본 것과 같은 값으로 비교해야 한다.
    한국어는 조사가 붙어 토큰 일치가 깨지므로(`카카오톡` vs `카카오톡이`) **label이 근거 안에
    들어 있는지**를 본다. 구두점·띄어쓰기는 양쪽에서 지운다: 실측된 좋은 근거
    `'Wi-Fi, 사용 중, AITECHLAB 연결됨'`은 쉼표만 다르고 label과 같은 문자열이다(HANDOFF §9).
    """
    normalized_evidence = _normalize_for_evidence(evidence)
    if not normalized_evidence:
        return False

    # **모델이 본 목록으로만 대조한다.** 가려진 요소는 프롬프트에 실리지 않으므로
    # (`prompt._visible_elements`) 그 label에 걸리는 근거는 모델이 볼 수 없었던 것이고,
    # 대조에 넣으면 지어낸 근거를 통과시키는 쪽으로만 작용한다 — 검증이 느슨해진다.
    labels = [
        _normalize_for_evidence(element.label) for element in visible_elements(screen.elements)
    ]
    present = [label for label in labels if label]
    if not present:
        # 화면에 label이 하나도 없으면 대조할 것이 없다. 검사할 수 없는 것을 통과로 돌리면
        # 앱 전환 직후 스플래시에서 온 `done`이 그대로 세션을 끝낸다(#331 실측 2건).
        # `verified=false`는 안내를 막지 않고 앱의 재시도 예산이 받는다(SPEC §4 2026-08-21 개정).
        return False

    # 한 글자 label은 아이콘·숫자라 아무 문장에나 우연히 걸린다. 다만 화면이 전부 한 글자면
    # (숫자 키패드 등) 검사를 끄는 대신 그것들로 대조한다 — 끄면 지어낸 근거가 그대로 통과한다.
    comparable = [label for label in present if len(label) >= _MIN_EVIDENCE_LABEL_CHARS] or present

    return any(label in normalized_evidence for label in comparable)


def _normalize_for_evidence(text: str) -> str:
    return _EVIDENCE_NOISE.sub("", text.casefold())


def next_step(
    req: NextStepRequest,
    llm: LlmClient,
    masking: MaskingPolicy | None = None,
) -> NextStepResponse:
    """앱 요청 하나 -> 다음 안내 하나. 파이프라인의 진입점.

    `masking`을 주지 않으면 설정이 정한 정책을 쓴다. 기본은 항상 2차 마스킹 적용이며,
    우회는 local development 비교 실험에서만 켜진다(#203).
    """
    # 2차 마스킹 그물: 모델에 넘기기 전에 한 번 더(1차는 앱에서 이미 수행).
    #
    # 스텝 이력도 함께 훑는다. 1차가 놓친 값이 label에 있었다면 모델이 그걸 instruction이나
    # 화면 요약에 인용하고, 앱은 그 값을 스텝에 담아 매 요청마다 다시 보낸다 — 화면은 넘어가도
    # 이력은 남으므로 놓친 값이 계속 재전송된다. 그물은 들어오는 모든 텍스트에 걸려야 의미가
    # 있다(SPEC §2). `target_label`은 화면 label 그 자체라 더 말할 것도 없다.
    policy = masking if masking is not None else active_masking_policy()
    masked = req.model_copy(
        update={
            "screen": policy.apply_screen(req.screen),
            "steps": policy.apply_steps(req.steps),
        }
    )

    action = llm.reason(masked)
    action = _apply_guardrails(action, masked)

    verified = _verify_done(action, masked)
    return NextStepResponse(
        action=action,
        # DAVI_MODEL을 비우면 공급자 기본 모델이 쓰인다. 앱·eval이 받는 값은
        # 설정 문자열이 아니라 **실제로 호출된 모델 id**여야 한다.
        model=settings.resolved_model,
        verified=verified,
        screen_signature=_screen_signature(masked.screen),
    )
