"""API 키 없는 fake LLM 앱 후보→기기 상태 회귀(#128)."""

from app.app_resolution import AppCandidateResolver, resolve_app_request
from app.schemas import (
    AppCandidate,
    AppCandidateAvailability,
    AppCandidatePlan,
    AppCandidateResult,
    AppResolutionFeedback,
    AppResolutionRequest,
    AppResolutionStatus,
)


class FakeCandidateResolver(AppCandidateResolver):
    def __init__(self, candidates: list[AppCandidate]) -> None:
        self.plan = AppCandidatePlan(capability="food_delivery", candidates=candidates)

    def candidates(self, goal: str) -> AppCandidatePlan:
        return self.plan


def _candidate(index: int) -> AppCandidate:
    return AppCandidate(
        candidate_id=f"candidate-{index}",
        canonical_name=f"일반 배달 앱 {index}",
        package_names=[f"com.example.delivery{index}"],
        label_aliases=[f"배달 앱 {index}"],
        confidence=0.9,
    )


def _round_trip(statuses: list[AppCandidateAvailability]):
    candidates = [_candidate(index) for index in range(1, len(statuses) + 1)]
    initial = resolve_app_request(
        AppResolutionRequest(goal="치킨을 주문하고 싶어"),
        FakeCandidateResolver(candidates),
    )
    assert initial.status is AppResolutionStatus.CANDIDATES
    assert initial.plan is not None
    feedback = AppResolutionFeedback(
        plan=initial.plan,
        candidate_results=[
            AppCandidateResult(candidate_id=candidate.candidate_id, status=status)
            for candidate, status in zip(initial.plan.candidates, statuses, strict=True)
        ],
    )
    return resolve_app_request(
        AppResolutionRequest(goal="치킨을 주문하고 싶어", feedback=feedback)
    )


def test_fake_llm_missing_candidates_needs_app_name_without_install_claim():
    result = _round_trip([])
    assert result.status is AppResolutionStatus.NEEDS_APP_NAME
    assert "설치" not in result.message


def test_fake_llm_wrong_package_not_found_is_not_resolved():
    result = _round_trip([AppCandidateAvailability.NOT_FOUND])
    assert result.status is AppResolutionStatus.NEEDS_APP_NAME
    assert result.goal is None


def test_fake_llm_multiple_launchable_candidates_are_never_auto_selected():
    result = _round_trip(
        [AppCandidateAvailability.LAUNCHABLE, AppCandidateAvailability.LAUNCHABLE]
    )
    assert result.status is AppResolutionStatus.NEEDS_USER_CHOICE
    assert result.selected_candidate_id is None


def test_fake_llm_unknown_profile_state_is_distinct_from_not_found():
    result = _round_trip([AppCandidateAvailability.UNKNOWN])
    assert result.status is AppResolutionStatus.UNAVAILABLE_OR_UNKNOWN
