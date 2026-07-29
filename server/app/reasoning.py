"""추론 파이프라인: 계획 -> 실행 -> 명시적 검증 (SPEC §4, HANDOFF §5-3).

이 모듈은 HTTP를 모른다. 순수 함수로 두어 eval 하니스가 직접 import해 회귀 테스트한다
(server/CLAUDE.md, eval/CLAUDE.md). FastAPI 라우터는 이걸 호출만 한다.

계획 단계와 완료 검증은 아직 스텁이며, 붙일 지점을 TODO로 표시했다.
가드레일은 `_apply_guardrails` 한 곳에 우선순위를 드러내 통합한다(HANDOFF §5-4).
"""

from __future__ import annotations

import hashlib
import json
import re

from .config import settings
from .llm import LlmClient
from .masking import mask_screen_model, mask_text
from .schemas import (
    ActionType,
    GuideAction,
    NextStepRequest,
    NextStepResponse,
    RecentAction,
    Screen,
)

# 같은 화면·같은 행동은 두 번까지 안내하고, 세 번째 후보부터 안전한 대체 행동을 검토한다.
# 실제 실패는 33초 동안 8회 반복됐다. 3회는 일시적인 화면 반영 지연을 허용하면서도 루프를
# 8회보다 일찍 끊는 보수적인 시작값이다.
MAX_IDENTICAL_ACTIONS = 3
_APP_DRAWER_FALLBACK_PACKAGES = {"com.sec.android.app.launcher"}

# 근거 대조 전에 양쪽에서 지우는 것: 구두점·공백 등 의미를 바꾸지 않는 문자.
#
# **글자는 문자 종류를 가리지 않고 남긴다.** 한글·라틴만 남기면 label이 한자·가나·키릴인 화면
# (微信, 設定, Настройки)에서 정규화 결과가 통째로 비어 검사가 조용히 꺼진다(PR #62 리뷰 2번).
_EVIDENCE_NOISE = re.compile(r"[^\w]+", re.UNICODE)

# 한 글자 label(아이콘·숫자)은 우연 일치가 잦아 우선 제외한다. 다만 화면 전체가 한 글자 label뿐이면
# (숫자 키패드 등) 검사를 끄는 대신 그것들로라도 대조한다.
_MIN_EVIDENCE_LABEL_CHARS = 2


def _plan(req: NextStepRequest) -> list[str]:
    """목표를 하위 태스크로 쪼갠다. TODO: 실제 계획 수립(선행조건 처리).

    지금은 단일 태스크로 통과시킨다(PoC의 단일 스텝 반응형과 동일한 동작).
    """
    return [req.goal]


def _apply_guardrails(action: GuideAction, req: NextStepRequest) -> GuideAction:
    """모든 행동 교정의 단일 결정 지점. 위에서 아래로 우선순위를 적용한다.

    HANDOFF §5-4 경고: 가드가 서로 덮어써 불안정했던 전례가 있다.
    새 가드는 이 함수에 우선순위를 드러내 추가하고, 서로 연쇄 적용하지 않는다.
    """
    # 1. 런처에 스크롤 노드가 없는데 같은 scroll을 반복하면 앱서랍 전환으로 경로를 바꾼다.
    #    `done`은 성공 의미라 막힘 fallback으로 사용하지 않는다.
    if _should_open_app_drawer(action, req):
        return GuideAction(
            expected_result=action.expected_result,
            evidence="런처의 같은 화면에서 같은 스크롤 행동이 반복됨",
            action_type=ActionType.OPEN_APP_DRAWER,
            instruction="화면 가운데를 위로 쓸어올려 전체 앱 목록을 열어 주세요.",
        )

    return action


def _should_open_app_drawer(action: GuideAction, req: NextStepRequest) -> bool:
    """실기기로 확인된 런처 scroll 루프에만 현재의 안전한 fallback을 적용한다."""

    if action.action_type is not ActionType.SCROLL:
        return False
    if req.screen.has_scrollable_element is not False:
        return False
    if req.screen.package_name not in _APP_DRAWER_FALLBACK_PACKAGES:
        return False
    # 삼성 앱서랍도 같은 package + has_scrollable_element=False다. 앱서랍에는 page 정보가
    # 관측되므로, 이미 페이지가 있는 런처 화면의 올바른 scroll을 앱서랍 열기로 덮지 않는다.
    # 일부 메타만 온 경우도 안전하게 가드를 적용하지 않는다.
    if req.screen.current_page is not None or req.screen.total_pages is not None:
        return False

    current_signature = _screen_signature(req.screen)
    candidate = _action_semantics(action)
    prior_identical = 0
    for previous in reversed(req.recent_actions):
        # 화면 또는 행동이 바뀐 순간 연속 반복 구간은 끝난다.
        if previous.screen_signature != current_signature:
            break
        if _action_semantics(previous) != candidate:
            break
        prior_identical += 1

    return prior_identical >= MAX_IDENTICAL_ACTIONS - 1


def _action_semantics(
    action: GuideAction | RecentAction,
) -> tuple[ActionType, object, str | None]:
    """안내 문장을 제외하고 action/direction/target 의미만 비교한다."""

    return (
        action.action_type,
        action.direction,
        action.target_node_id,
    )


def _screen_signature(screen: Screen) -> str:
    """PII·volatile text를 제외한 화면 구조의 결정론적 서명.

    label은 서버가 이미 받는 값이지만 해시만 장기간 남아도 흔한 문자열은 역추정할 수 있다.
    그래서 label의 원문·해시·길이 모두 제외한다. 시계·배터리 텍스트 변화도 같은 화면으로 본다.
    대신 package/page/요소 순서·역할·좌표·상호작용/스크롤 상태가 바뀌면 서명이 바뀐다.

    이 선택은 구조·좌표가 같고 내용만 바뀐 화면을 구분하지 못한다. 목록 항목이 label만 바뀌면
    실제 화면 변화가 있어도 같은 서명이다. 따라서 이 서명만으로 모든 화면 동일성을 주장하거나
    현재의 좁은 런처 fallback 밖에 재사용하면 안 된다. 알고리즘이 바뀌면 앱이 보관한 이전
    서명과 새 서명이 달라 진행 중 반복 카운트가 끊기지만, 가드가 발동하지 않는 fail-safe 방향이다.
    """

    structure = {
        "package_name": screen.package_name,
        "current_page": screen.current_page,
        "total_pages": screen.total_pages,
        "has_scrollable_element": screen.has_scrollable_element,
        "elements": [
            {
                "node_id": element.node_id,
                "role": element.role,
                "clickable": element.clickable,
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

    labels = [_normalize_for_evidence(element.label) for element in screen.elements]
    present = [label for label in labels if label]
    if not present:
        # 화면에 label이 하나도 없으면 검사할 수 없다.
        # 없는 것을 근거로 거짓 판정을 만들지 않는다(SPEC §4).
        return True

    # 한 글자 label은 아이콘·숫자라 아무 문장에나 우연히 걸린다. 다만 화면이 전부 한 글자면
    # (숫자 키패드 등) 검사를 끄는 대신 그것들로 대조한다 — 끄면 지어낸 근거가 그대로 통과한다.
    comparable = [label for label in present if len(label) >= _MIN_EVIDENCE_LABEL_CHARS] or present

    return any(label in normalized_evidence for label in comparable)


def _normalize_for_evidence(text: str) -> str:
    return _EVIDENCE_NOISE.sub("", text.casefold())


def next_step(req: NextStepRequest, llm: LlmClient) -> NextStepResponse:
    """앱 요청 하나 -> 다음 안내 하나. 파이프라인의 진입점."""
    # 2차 마스킹 그물: 모델에 넘기기 전에 한 번 더(1차는 앱에서 이미 수행).
    #
    # 이력도 함께 훑는다. 1차가 놓친 값이 label에 있었다면 모델이 그걸 instruction에 인용하고,
    # 앱은 그 문장을 이력에 담아 매 요청마다 다시 보낸다 — 화면은 넘어가도 이력은 남으므로
    # 놓친 값이 계속 재전송된다. 그물은 들어오는 모든 텍스트에 걸려야 의미가 있다(SPEC §2).
    masked = req.model_copy(
        update={
            "screen": mask_screen_model(req.screen),
            "recent_instructions": [mask_text(i) for i in req.recent_instructions],
        }
    )

    _plan(masked)  # TODO: 계획 결과를 실행에 반영
    action = llm.reason(masked)
    action = _apply_guardrails(action, masked)

    verified = _verify_done(action, masked)
    return NextStepResponse(
        action=action,
        model=settings.davi_model,
        verified=verified,
        screen_signature=_screen_signature(masked.screen),
    )
