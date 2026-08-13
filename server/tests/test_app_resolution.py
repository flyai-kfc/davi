"""목표 앱 후보 생성과 무상태 기기 대조 판정."""

from __future__ import annotations

import json

import httpx
import pytest
from app.app_resolution import (
    APP_CANDIDATE_SCHEMA,
    GeminiAppCandidateResolver,
    StubAppCandidateResolver,
    resolve_app_request,
)
from app.errors import LlmError
from app.schemas import (
    AppCandidate,
    AppCandidateAvailability,
    AppCandidatePlan,
    AppCandidateResult,
    AppResolutionFeedback,
    AppResolutionRequest,
    AppResolutionStatus,
    ErrorKind,
)


def _plan() -> AppCandidatePlan:
    return AppCandidatePlan(
        capability="food_delivery",
        candidates=[
            AppCandidate(
                candidate_id="candidate-1",
                canonical_name="배달 앱 A",
                package_names=["com.example.delivery.a"],
                label_aliases=["배달A"],
                confidence=0.9,
            ),
            AppCandidate(
                candidate_id="candidate-2",
                canonical_name="배달 앱 B",
                package_names=["com.example.delivery.b"],
                label_aliases=["배달B"],
                confidence=0.8,
            ),
        ],
    )


def _feedback(*statuses: AppCandidateAvailability) -> AppResolutionFeedback:
    plan = _plan()
    return AppResolutionFeedback(
        plan=plan,
        candidate_results=[
            AppCandidateResult(candidate_id=candidate.candidate_id, status=status)
            for candidate, status in zip(plan.candidates, statuses, strict=True)
        ],
    )


def _request_with(*statuses: AppCandidateAvailability) -> AppResolutionRequest:
    return AppResolutionRequest(goal="치킨을 주문하고 싶어", feedback=_feedback(*statuses))


def test_stub_is_keyless_and_does_not_claim_installation():
    state = resolve_app_request(
        AppResolutionRequest(goal="치킨을 주문하고 싶어"),
        StubAppCandidateResolver(),
    )

    assert state.status is AppResolutionStatus.CANDIDATES
    assert state.plan is not None
    assert state.plan.candidates == []


def test_single_launchable_candidate_is_resolved_without_device_package_echo():
    state = resolve_app_request(
        _request_with(AppCandidateAvailability.LAUNCHABLE, AppCandidateAvailability.NOT_FOUND)
    )

    assert state.status is AppResolutionStatus.RESOLVED
    assert state.selected_candidate_id == "candidate-1"
    assert state.goal == "치킨을 주문하고 싶어 (사용할 앱: 배달 앱 A)"
    assert state.plan is None


@pytest.mark.parametrize(
    "statuses",
    [
        (AppCandidateAvailability.LAUNCHABLE, AppCandidateAvailability.LAUNCHABLE),
        (AppCandidateAvailability.AMBIGUOUS, AppCandidateAvailability.NOT_FOUND),
    ],
)
def test_multiple_or_ambiguous_matches_need_user_choice(statuses):
    state = resolve_app_request(_request_with(*statuses))
    assert state.status is AppResolutionStatus.NEEDS_USER_CHOICE
    assert state.goal is None


def test_zero_matches_does_not_claim_not_installed():
    state = resolve_app_request(
        _request_with(AppCandidateAvailability.NOT_FOUND, AppCandidateAvailability.NOT_FOUND)
    )
    assert state.status is AppResolutionStatus.NEEDS_APP_NAME
    assert "설치" not in state.message


def test_unknown_takes_precedence_over_a_single_visible_match():
    state = resolve_app_request(
        _request_with(AppCandidateAvailability.LAUNCHABLE, AppCandidateAvailability.UNKNOWN)
    )
    assert state.status is AppResolutionStatus.UNAVAILABLE_OR_UNKNOWN
    assert state.selected_candidate_id is None


def test_nested_gemini_schema_caps_candidate_array_and_assigns_ids_server_side():
    seen: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        seen.append(body)
        assert body["generationConfig"]["responseSchema"] == APP_CANDIDATE_SCHEMA
        prompt = body["contents"][0]["parts"][0]["text"]
        assert "설치 앱 목록은 알 수 없으며" in prompt
        return httpx.Response(
            200,
            json={
                "candidates": [
                    {
                        "finishReason": "STOP",
                        "content": {
                            "parts": [
                                {
                                    "text": json.dumps(
                                        {
                                            "decision_basis": "배달 기능에 직접 필요한 후보만 선택",
                                            "capability": "food_delivery",
                                            "candidates": [
                                                {
                                                    "canonical_name": "배달 앱 A",
                                                    "package_names": ["com.example.delivery"],
                                                    "label_aliases": ["배달A"],
                                                    "confidence": 0.9,
                                                }
                                            ],
                                        },
                                        ensure_ascii=False,
                                    )
                                }
                            ]
                        },
                    }
                ]
            },
        )

    resolver = GeminiAppCandidateResolver(
        "test-key",
        "gemini-test",
        httpx.Client(transport=httpx.MockTransport(handler)),
    )
    plan = resolver.candidates("치킨을 주문하고 싶어")

    assert plan.candidates[0].candidate_id == "candidate-1"
    assert len(seen) == 1
    candidate_schema = APP_CANDIDATE_SCHEMA["properties"]["candidates"]
    assert candidate_schema["type"] == "ARRAY"
    assert candidate_schema["items"]["type"] == "OBJECT"


def test_invalid_model_contract_is_retried_once_without_raw_output():
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        requests.append(body)
        payload = (
            {
                "decision_basis": "후보 판단",
                "capability": "food_delivery",
                "candidates": [{"bad": "shape"}],
            }
            if len(requests) == 1
            else {
                "decision_basis": "후보 없음",
                "capability": "food_delivery",
                "candidates": [],
            }
        )
        return httpx.Response(
            200,
            json={
                "candidates": [
                    {
                        "finishReason": "STOP",
                        "content": {"parts": [{"text": json.dumps(payload)}]},
                    }
                ]
            },
        )

    resolver = GeminiAppCandidateResolver(
        "test-key",
        "gemini-test",
        httpx.Client(transport=httpx.MockTransport(handler)),
    )
    assert resolver.candidates("목표").candidates == []
    assert len(requests) == 2


def test_second_invalid_model_contract_fails_as_parse():
    resolver = GeminiAppCandidateResolver(
        "test-key",
        "gemini-test",
        httpx.Client(
            transport=httpx.MockTransport(
                lambda _: httpx.Response(
                    200,
                    json={
                        "candidates": [
                            {
                                "finishReason": "STOP",
                                "content": {"parts": [{"text": "{}"}]},
                            }
                        ]
                    },
                )
            )
        ),
    )
    with pytest.raises(LlmError) as raised:
        resolver.candidates("목표")
    assert raised.value.kind is ErrorKind.PARSE


def test_resolved_goal_keeps_candidate_within_wire_length_limit():
    request = AppResolutionRequest(
        goal="가" * 500,
        feedback=_feedback(
            AppCandidateAvailability.LAUNCHABLE,
            AppCandidateAvailability.NOT_FOUND,
        ),
    )

    state = resolve_app_request(request)

    assert state.status is AppResolutionStatus.RESOLVED
    assert state.goal is not None
    assert len(state.goal) <= 500
    assert "사용할 앱: 배달 앱 A" in state.goal
