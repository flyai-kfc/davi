"""테스트용 공급자 조립 헬퍼.

운영 코드는 `llm_provider.build_provider()`가 설정을 읽어 공급자를 만든다. 테스트는 그 대신
MockTransport를 물린 httpx 클라이언트를 직접 주입해야 하므로, 세 호출부가 쓰던
`(api_key, model, http_client)` 서명을 여기서 유지한다 — 테스트가 공급자 조립 세부를 알 필요가
없고, 출력 토큰 계산이 운영 코드와 어긋나지도 않는다(같은 상수를 쓴다).

기본은 Gemini다. OpenAI·Anthropic 경로는 각 공급자 테스트가 직접 조립한다.
"""

from __future__ import annotations

import httpx
from app.app_resolution import _RESPONSE_HEADROOM_TOKENS as APP_CANDIDATE_HEADROOM
from app.app_resolution import (
    APP_CANDIDATE_CONTRACT,
    RemoteAppCandidateResolver,
)
from app.intent_resolution import _RESPONSE_HEADROOM_TOKENS as INTENT_DIALOGUE_HEADROOM
from app.intent_resolution import (
    INTENT_DIALOGUE_CONTRACT,
    RemoteIntentResolver,
)
from app.llm import _RESPONSE_HEADROOM_TOKENS as NEXT_STEP_HEADROOM
from app.llm import GUIDE_ACTION_CONTRACT, RemoteLlmClient
from app.llm_provider import GeminiProvider, ResponseContract, StructuredJsonProvider

DEFAULT_THINKING_BUDGET = 800


def gemini_provider(
    api_key: str,
    model: str,
    http_client: httpx.Client | None,
    *,
    contract: ResponseContract,
    operation: str,
    response_headroom: int,
    thinking_budget: int = DEFAULT_THINKING_BUDGET,
) -> StructuredJsonProvider:
    return GeminiProvider(
        api_key=api_key,
        model=model,
        operation=operation,
        contract=contract,
        max_output_tokens=thinking_budget + response_headroom,
        thinking_budget=thinking_budget,
        http_client=http_client,
    )


def gemini_llm_client(
    api_key: str,
    model: str,
    http_client: httpx.Client | None = None,
    thinking_budget: int = DEFAULT_THINKING_BUDGET,
) -> RemoteLlmClient:
    return RemoteLlmClient(
        gemini_provider(
            api_key,
            model,
            http_client,
            contract=GUIDE_ACTION_CONTRACT,
            operation="next_step",
            response_headroom=NEXT_STEP_HEADROOM,
            thinking_budget=thinking_budget,
        )
    )


def gemini_intent_resolver(
    api_key: str,
    model: str,
    http_client: httpx.Client | None = None,
    thinking_budget: int = DEFAULT_THINKING_BUDGET,
) -> RemoteIntentResolver:
    return RemoteIntentResolver(
        gemini_provider(
            api_key,
            model,
            http_client,
            contract=INTENT_DIALOGUE_CONTRACT,
            operation="intent_dialogue",
            response_headroom=INTENT_DIALOGUE_HEADROOM,
            thinking_budget=thinking_budget,
        )
    )


def gemini_app_candidate_resolver(
    api_key: str,
    model: str,
    http_client: httpx.Client | None = None,
    thinking_budget: int = DEFAULT_THINKING_BUDGET,
) -> RemoteAppCandidateResolver:
    return RemoteAppCandidateResolver(
        gemini_provider(
            api_key,
            model,
            http_client,
            contract=APP_CANDIDATE_CONTRACT,
            operation="app_candidates",
            response_headroom=APP_CANDIDATE_HEADROOM,
            thinking_budget=thinking_budget,
        )
    )
