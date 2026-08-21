"""목표 앱 후보 생성과 무상태 기기 대조 판정."""

from __future__ import annotations

import json
import logging

import httpx
import pytest
from app.app_resolution import (
    APP_CANDIDATE_SCHEMA,
    RemoteAppCandidateResolver,
    StubAppCandidateResolver,
    get_app_candidate_resolver,
    resolve_app_request,
)
from app.errors import LlmError
from app.schemas import (
    MAX_APP_CANDIDATES,
    MAX_APP_LABEL_ALIASES,
    MAX_APP_PACKAGE_NAMES,
    AppCandidate,
    AppCandidateAvailability,
    AppCandidatePlan,
    AppCandidateResult,
    AppResolutionFeedback,
    AppResolutionRequest,
    AppResolutionStatus,
    ErrorKind,
)

from tests.factories import gemini_app_candidate_resolver


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


def test_stub_is_keyless_and_asks_for_app_name_without_feedback_round_trip():
    state = resolve_app_request(
        AppResolutionRequest(goal="치킨을 주문하고 싶어"),
        StubAppCandidateResolver(),
    )

    assert state.status is AppResolutionStatus.NEEDS_APP_NAME
    assert state.plan is None
    assert "설치" not in state.message


def test_invalid_package_hint_is_dropped_without_rejecting_valid_candidate():
    response = {
        "decision_basis": "카메라 후보 판단",
        "capability": "camera",
        "candidates": [
            {
                "canonical_name": "카메라",
                "package_names": ["invalid-package", "com.example.camera"],
                "label_aliases": ["카메라"],
                "confidence": 0.9,
            }
        ],
    }
    resolver = gemini_app_candidate_resolver(
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
                                "content": {"parts": [{"text": json.dumps(response)}]},
                            }
                        ]
                    },
                )
            )
        ),
    )

    plan = resolver.candidates("사진 찍기")

    assert plan.candidates[0].package_names == ["com.example.camera"]


def test_provider_status_class_is_logged_without_model_output(caplog):
    private_output = "private-model-output-canary"
    payload = {
        "decision_basis": private_output,
        "capability": "camera",
        "candidates": [],
    }
    resolver = gemini_app_candidate_resolver(
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
                                "content": {"parts": [{"text": json.dumps(payload)}]},
                            }
                        ]
                    },
                )
            )
        ),
    )

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.llm"):
        resolver.candidates("사진 찍기")

    events = [json.loads(record.getMessage()) for record in caplog.records]
    finished = next(event for event in events if event["event"] == "llm_attempt_finished")
    assert finished["provider_status_class"] == "2xx"
    assert private_output not in json.dumps(events, ensure_ascii=False)


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
        # thinking 모델이 이 예산을 thinking 토큰으로 다 써 응답이 잘리는 걸 막는다
        # (실기기 재현, 2026-08-18 gemini-3.6-flash PARSE/max_output_tokens 실패).
        assert body["generationConfig"]["thinkingConfig"] == {"thinkingBudget": 800}
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

    resolver = gemini_app_candidate_resolver(
        "test-key",
        "gemini-test",
        httpx.Client(transport=httpx.MockTransport(handler)),
    )
    plan = resolver.candidates("치킨을 주문하고 싶어")

    assert plan.candidates[0].candidate_id == "candidate-1"
    assert len(seen) == 1
    candidate_schema = APP_CANDIDATE_SCHEMA["properties"]["candidates"]
    assert candidate_schema["type"] == "ARRAY"
    assert candidate_schema["maxItems"] == MAX_APP_CANDIDATES
    assert candidate_schema["items"]["type"] == "OBJECT"
    candidate_properties = candidate_schema["items"]["properties"]
    assert candidate_properties["package_names"]["maxItems"] == MAX_APP_PACKAGE_NAMES
    assert candidate_properties["label_aliases"]["maxItems"] == MAX_APP_LABEL_ALIASES


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

    resolver = gemini_app_candidate_resolver(
        "test-key",
        "gemini-test",
        httpx.Client(transport=httpx.MockTransport(handler)),
    )
    assert resolver.candidates("목표").candidates == []
    assert len(requests) == 2


def test_second_invalid_model_contract_fails_as_parse():
    resolver = gemini_app_candidate_resolver(
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


def test_get_app_candidate_resolver_wires_thinking_budget_from_settings(set_runtime_profile):
    assert isinstance(get_app_candidate_resolver(), StubAppCandidateResolver)

    set_runtime_profile(
        davi_use_stub_llm=False,
        gemini_api_key="server-side-key",
        davi_model="gemini-test",
        davi_gemini_thinking_budget=321,
    )
    get_app_candidate_resolver.cache_clear()

    resolver = get_app_candidate_resolver()

    assert isinstance(resolver, RemoteAppCandidateResolver)
    # DAVI_GEMINI_THINKING_BUDGET이 공급자까지 흘러가는지(config.py) 확인한다.
    # maxOutputTokens는 이 값 위에 원래 여유분(1024)을 얹은 것이어야 잘림을 안 만든다.
    assert resolver.provider.thinking_budget == 321
    assert resolver.provider.max_output_tokens == 321 + 1024
