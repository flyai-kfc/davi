"""완료 판정을 실기기 fixture로 회귀한다(#54).

`StubLlmClient`는 `done`을 내지 않아 이 경로를 만들지 못한다. 결정론적 fake LLM으로
`done`을 내되, 근거를 **실기기 화면에 있는 것 / 없는 것**으로 갈라 넣는다.

실패 출력에 화면 label 원문을 넣지 않는다(`eval/CLAUDE.md` PII 규칙).
"""

from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Literal

import pytest
from app.llm import LlmClient
from app.reasoning import (
    _MIN_EVIDENCE_LABEL_CHARS,
    _evidence_words,
    _normalize_for_evidence,
    next_step,
)
from app.schemas import ActionType, GuideAction, NextStepRequest
from app.screen_view import visible_elements
from pydantic import BaseModel, ConfigDict, Field, model_validator

from harness import FIXTURES, assert_no_sensitive_data, load_fixture

_FIXTURE = "samsung_launcher_open_camera.json"
_COMPLETION_CASES = (
    Path(__file__).parents[1] / "completion-fixtures" / "evidence_cases.json"
)


class CompletionCase(BaseModel):
    """#71의 개인정보 없는 완료 판정 사례 한 건."""

    model_config = ConfigDict(extra="forbid")

    id: str = Field(pattern=r"^[a-z0-9][a-z0-9-]*$")
    axis: str
    source: Literal["eval_case", "anonymized_real_device_fixture"]
    request: dict | None = None
    screen_fixture: str | None = Field(default=None, pattern=r"^[a-z0-9_]+\.json$")
    goal: str | None = None
    expected_result: str
    evidence: str
    evidence_label: str
    expected_verified: bool

    @model_validator(mode="after")
    def validate_source(self) -> CompletionCase:
        if self.source == "eval_case" and (
            self.request is None
            or self.screen_fixture is not None
            or self.goal is not None
        ):
            raise ValueError("eval_case는 request만 가져야 한다")
        if self.source == "anonymized_real_device_fixture" and (
            self.screen_fixture is None or self.request is not None
        ):
            raise ValueError("실기기 참조 사례는 screen_fixture만 가져야 한다")
        return self


class CompletionDataset(BaseModel):
    model_config = ConfigDict(extra="forbid")

    dataset_version: Literal[1]
    description: str
    cases: list[CompletionCase] = Field(min_length=1)


class DoneLlmClient(LlmClient):
    """언제나 `done`을 내는 fake. 근거 문장만 갈아 끼운다."""

    def __init__(
        self, evidence: str, expected_result: str = "카메라가 열려 있다"
    ) -> None:
        self._evidence = evidence
        self._expected_result = expected_result

    def reason(self, req: NextStepRequest) -> GuideAction:
        return GuideAction(
            expected_result=self._expected_result,
            screen_summary="테스트 화면",
            evidence=self._evidence,
            action_type=ActionType.DONE,
            instruction="다 되었습니다.",
        )


@pytest.fixture
def request_from_device() -> NextStepRequest:
    return load_fixture(FIXTURES / _FIXTURE).request


def _first_label(req: NextStepRequest) -> str:
    """코드가 대조에 쓰는 것과 **같은 기준**으로 고른다.

    테스트가 원문 길이로 고르면, 정규화 후에야 짧아지는 label(`→ ←`, `1.`)이 첫 요소인 픽스처가
    들어왔을 때 코드는 설계대로인데 테스트만 깨진다(PR #62 리뷰 5번).
    """
    label = next(
        (
            e.label
            for e in req.screen.elements
            if len(_normalize_for_evidence(e.label)) >= _MIN_EVIDENCE_LABEL_CHARS
        ),
        None,
    )
    assert label is not None, f"{_FIXTURE}에 대조할 label이 없다"
    return label


def test_fabricated_evidence_is_not_verified(request_from_device):
    """화면에 없는 문장을 근거로 낸 `done`은 검증을 통과하지 못한다."""

    response = next_step(
        request_from_device,
        DoneLlmClient("사진 촬영이 끝나 갤러리에 저장되었습니다"),
    )

    assert response.action.action_type is ActionType.DONE
    assert not response.verified


def test_evidence_quoted_from_the_device_screen_is_verified(request_from_device):
    """실기기 화면의 label을 인용한 근거는 통과한다 — 정당한 완료를 막지 않는다.

    **목표가 실제로 달성된 화면으로 맞춘다.** 픽스처의 원래 목표(`open camera app`)를 그대로 두면
    런처 홈에서 `done`이 통과하는 조기 종료 시나리오를 기대 동작으로 굳히게 된다 — 나중에 그걸
    조이면 이 테스트가 회귀처럼 깨진다(PR #62 리뷰 4번). 그 한계는 `SPEC.md` §4에 적혀 있다.
    """
    reached_goal = request_from_device.model_copy(update={"goal": "홈 화면으로 가기"})
    quoted = _first_label(reached_goal)

    # `expected_result`도 바뀐 목표에 맞춘다. 지금 단언에는 영향이 없지만, 목표와 대조하는 검사가
    # 들어오면 이 픽스처가 틀린 데이터가 된다(PR #62 리뷰).
    response = next_step(
        reached_goal,
        DoneLlmClient(
            f"홈 화면에 {quoted} 이(가) 보임",
            expected_result="홈 화면이 보인다",
        ),
    )

    assert response.verified


def _load_completion_cases() -> list[tuple[CompletionCase, NextStepRequest]]:
    raw = json.loads(_COMPLETION_CASES.read_text(encoding="utf-8"))
    assert_no_sensitive_data(raw, filename=_COMPLETION_CASES.name)
    dataset = CompletionDataset.model_validate(raw)
    assert len({case.id for case in dataset.cases}) == len(dataset.cases)

    loaded: list[tuple[CompletionCase, NextStepRequest]] = []
    for case in dataset.cases:
        if case.request is not None:
            request = NextStepRequest.model_validate(case.request)
        else:
            assert case.screen_fixture is not None
            request = load_fixture(FIXTURES / case.screen_fixture).request
            if case.goal is not None:
                request = request.model_copy(update={"goal": case.goal})
        loaded.append((case, request))
    return loaded


def _new_policy(case: CompletionCase, request: NextStepRequest) -> bool:
    return next_step(
        request,
        DoneLlmClient(case.evidence, expected_result=case.expected_result),
    ).verified


def _legacy_policy(case: CompletionCase, request: NextStepRequest) -> bool:
    """#71 직전의 `정규화한 label in evidence` 정책을 평가 안에서만 재현한다."""
    normalized_evidence = _normalize_for_evidence(case.evidence)
    labels = [
        _normalize_for_evidence(element.label)
        for element in visible_elements(request.screen.elements)
    ]
    present = [label for label in labels if label]
    comparable = [
        label for label in present if len(label) >= _MIN_EVIDENCE_LABEL_CHARS
    ] or present
    return bool(
        normalized_evidence
        and any(label in normalized_evidence for label in comparable)
    )


def _non_clickable_policy(case: CompletionCase, request: NextStepRequest) -> bool:
    """후보: 최종 어절 정책에 더해 clickable label을 완료 근거에서 제외한다."""
    if not _new_policy(case, request):
        return False
    return any(
        element.label == case.evidence_label and not element.clickable
        for element in visible_elements(request.screen.elements)
    )


def _goal_overlap_policy(case: CompletionCase, request: NextStepRequest) -> bool:
    """후보: 인용 label의 어절 하나가 goal/expected_result에도 있어야 한다."""
    if not _new_policy(case, request):
        return False
    label_words = {
        word
        for word in _evidence_words(case.evidence_label)
        if len(_normalize_for_evidence(word)) >= _MIN_EVIDENCE_LABEL_CHARS
    }
    goal_words = set(_evidence_words(f"{request.goal} {case.expected_result}"))
    return bool(label_words & goal_words)


def _confusion_matrix(policy) -> Counter[str]:
    matrix: Counter[str] = Counter()
    for case, request in _load_completion_cases():
        actual = policy(case, request)
        if case.expected_verified:
            matrix["tp" if actual else "fn"] += 1
        else:
            matrix["fp" if actual else "tn"] += 1
    return matrix


def test_completion_policy_fixture_is_valid_and_measures_candidates():
    """현재 오탐·미탐과 후보별 비용을 결정론적으로 센다(#71).

    어절 경계+최소 두 글자는 정상 사례를 더 잃지 않고 FP 세 건을 줄인다. clickable 필터와 목표
    문자열 겹침은 부정문 FP도 없애지 못하면서 정상 완료를 각각 셋·넷 막는다. 따라서 둘은 적용하지
    않는다.
    """
    assert _confusion_matrix(_legacy_policy) == Counter(tp=9, tn=2, fp=6, fn=1)
    assert _confusion_matrix(_new_policy) == Counter(tp=9, tn=5, fp=3, fn=1)
    assert _confusion_matrix(_non_clickable_policy) == Counter(tp=7, tn=7, fp=1, fn=3)
    assert _confusion_matrix(_goal_overlap_policy) == Counter(tp=6, tn=7, fp=1, fn=4)


def test_remaining_false_results_are_named_instead_of_hidden():
    results = {
        case.id: (case.expected_verified, _new_policy(case, request))
        for case, request in _load_completion_cases()
    }

    assert {
        case_id for case_id, result in results.items() if result == (False, True)
    } == {
        "invalid-app-name-on-launcher",
        "invalid-negated-visible-label",
        "invalid-unrelated-visible-label",
    }
    assert {
        case_id for case_id, result in results.items() if result == (True, False)
    } == {"valid-paraphrase-without-exact-label"}
