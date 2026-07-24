"""추론 파이프라인: 계획 -> 실행 -> 명시적 검증 (SPEC §4, HANDOFF §5-3).

이 모듈은 HTTP를 모른다. 순수 함수로 두어 eval 하니스가 직접 import해 회귀 테스트한다
(server/CLAUDE.md, eval/CLAUDE.md). FastAPI 라우터는 이걸 호출만 한다.

지금은 골격이다. 계획 단계와 완료 검증은 스텁이며, 붙일 지점을 TODO로 표시했다.
가드레일은 이후 '단일 결정 지점'으로 이 파일 안에 통합한다(HANDOFF §5-4). 앱에 흩지 않는다.
"""

from __future__ import annotations

from .config import settings
from .llm import LlmClient
from .masking import mask_screen
from .schemas import ActionType, GuideAction, NextStepRequest, NextStepResponse, Screen


def _plan(req: NextStepRequest) -> list[str]:
    """목표를 하위 태스크로 쪼갠다. TODO: 실제 계획 수립(선행조건 처리).

    지금은 단일 태스크로 통과시킨다(PoC의 단일 스텝 반응형과 동일한 동작).
    """
    return [req.goal]


def _apply_guardrails(action: GuideAction, req: NextStepRequest) -> GuideAction:
    """일반 정책(앱 무관)만 코드로 교정한다. TODO: scroll-before-search 등 통합.

    HANDOFF §5-4 경고: 가드가 서로 덮어써 불안정했던 전례가 있다.
    여기 '한 곳'에서 우선순위를 명시해 결정한다. 지금은 통과.
    """
    return action


def _verify_done(action: GuideAction, req: NextStepRequest) -> bool:
    """완료 판정 2차 검증. expected_result vs evidence, 목표-상대적으로.

    TODO: evidence가 실제 label과 매칭되는지 검사(SPEC §11 열린 결정).
    지금은 근거 필드가 비지 않았는지만 본다(HANDOFF: 빈 evidence로 done 거부한 실측 사례).
    """
    if action.action is not ActionType.DONE:
        return False
    return bool(action.evidence.strip()) and bool(action.expected_result.strip())


def next_step(req: NextStepRequest, llm: LlmClient) -> NextStepResponse:
    """앱 요청 하나 -> 다음 안내 하나. 파이프라인의 진입점."""
    # 2차 마스킹 그물: 모델에 넘기기 전에 한 번 더(1차는 앱에서 이미 수행).
    masked_screen = Screen.model_validate(mask_screen(req.screen.model_dump()))
    masked = req.model_copy(update={"screen": masked_screen})

    _plan(masked)  # TODO: 계획 결과를 실행에 반영
    action = llm.reason(masked)
    action = _apply_guardrails(action, masked)

    verified = _verify_done(action, masked)
    return NextStepResponse(action=action, model=settings.davi_model, verified=verified)
