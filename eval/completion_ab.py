"""완료 판정 정책을 같은 fixture corpus에서 비교하는 keyless A/B runner(#425).

#424가 제품의 신뢰도 상태와 종료 UX를 확정하기 전에도 측정 경계는 독립적으로 유지한다.
baseline은 현재 화면 근거 정책을 별도 snapshot 모듈에 명시적으로 보존하고, candidate는 현재 서버의 순수
완료 검증 경계를 호출한다. 지금 두 정책의 의미는 같지만 adapter는 분리되어 있으므로 #424가
candidate만 교체해도 같은 corpus와 보고서 계약으로 전후 비용을 비교할 수 있다.

출력에는 case id와 집계만 싣는다. 화면 label, goal, evidence 같은 fixture 원문은 출력하지 않는다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import subprocess
import sys
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Any, Literal, Protocol

ROOT = Path(__file__).resolve().parent.parent
_SERVER = ROOT / "server"
sys.path.insert(0, str(_SERVER))

from app.reasoning import _verify_done
from app.schemas import ActionType, GuideAction, NextStepRequest
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from completion_baseline import BASELINE_POLICY_ID, baseline_verified
from harness import (
    FIXTURES,
    FixtureError,
    _validation_locations,
    assert_no_sensitive_data,
    load_fixture,
)

COMPLETION_CASES = (
    Path(__file__).resolve().parent / "completion-fixtures" / "evidence_cases.json"
)
_CANDIDATE_SOURCE_SHA256 = hashlib.sha256(
    (ROOT / "server" / "app" / "reasoning.py").read_bytes()
).hexdigest()
_SAFE_CASE_ID = re.compile(r"^[a-z0-9][a-z0-9-]*$")


class CompletionVerdict(StrEnum):
    """사람의 기대값과 정책 출력이 공유하는 완료 신뢰도 상태."""

    VERIFIED = "verified"
    INCONCLUSIVE = "inconclusive"
    CONTRADICTED = "contradicted"


class CompletionCase(BaseModel):
    """개인정보 없는 완료 판정 사례 한 건."""

    model_config = ConfigDict(extra="forbid")

    id: str = Field(pattern=_SAFE_CASE_ID.pattern)
    axis: str = Field(min_length=1)
    source: Literal["eval_case", "anonymized_real_device_fixture"]
    request: dict[str, Any] | None = None
    screen_fixture: str | None = Field(default=None, pattern=r"^[a-z0-9_]+\.json$")
    goal: str | None = None
    expected_result: str = Field(min_length=1)
    evidence: str = Field(min_length=1)
    evidence_label: str = Field(min_length=1)
    expected_verified: bool
    expected_verdict: CompletionVerdict

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
    """완료 판정 corpus envelope."""

    model_config = ConfigDict(extra="forbid")

    dataset_version: Literal[2]
    description: str = Field(min_length=1)
    cases: list[CompletionCase] = Field(min_length=1)


@dataclass(frozen=True)
class LoadedCompletionCase:
    case: CompletionCase
    request: NextStepRequest


@dataclass(frozen=True)
class LoadedCompletionDataset:
    dataset: CompletionDataset
    cases: tuple[LoadedCompletionCase, ...]
    corpus_sha256: str


class CompletionPolicy(Protocol):
    """HTTP·모델 호출 없이 완료 판정을 내는 정책 adapter."""

    policy_id: str

    def evaluate(
        self,
        case: CompletionCase,
        request: NextStepRequest,
    ) -> CompletionVerdict: ...


class BaselineCompletionPolicy:
    """#425 착수 시점의 화면 근거 정책을 명시적으로 보존한 baseline.

    candidate adapter를 호출하지 않는다. #424에서 서버의 완료 판정이 바뀌어도 이 구현은 그대로
    남아야 하며, baseline 자체를 바꾸면 policy_id도 함께 올려야 한다.
    """

    policy_id = BASELINE_POLICY_ID

    def evaluate(
        self,
        case: CompletionCase,
        request: NextStepRequest,
    ) -> CompletionVerdict:
        if baseline_verified(case.evidence, case.expected_result, request.screen):
            return CompletionVerdict.VERIFIED
        return CompletionVerdict.INCONCLUSIVE


class CurrentServerCandidatePolicy:
    """현재 서버의 순수 완료 검증 경계를 호출하는 candidate adapter.

    #424가 `contradicted`를 판정하는 순수 경계를 추가하면 이 adapter만 그 함수를 호출하도록
    바꾼다. 그 전에는 기존 bool의 false를 안전하게 `inconclusive`로 옮기며 제품 계약을 바꾸지
    않는다.
    """

    policy_id = f"current-server-candidate-v1-{_CANDIDATE_SOURCE_SHA256[:12]}"

    def evaluate(
        self,
        case: CompletionCase,
        request: NextStepRequest,
    ) -> CompletionVerdict:
        verified = _verify_done(_done_action(case), request)
        return (
            CompletionVerdict.VERIFIED if verified else CompletionVerdict.INCONCLUSIVE
        )


@dataclass(frozen=True)
class Metric:
    numerator: int
    denominator: int

    @property
    def rate(self) -> float | None:
        return self.numerator / self.denominator if self.denominator else None

    def to_dict(self) -> dict[str, int | float | None]:
        return {
            "count": self.numerator,
            "denominator": self.denominator,
            "rate": self.rate,
        }


@dataclass(frozen=True)
class PolicyReport:
    policy_id: str
    confusion_matrix: dict[str, dict[str, int]]
    false_success: Metric
    verified_recall: Metric
    contradiction_recall: Metric
    inconclusive_rate: Metric
    verdicts: dict[str, str]

    def to_dict(self) -> dict[str, Any]:
        return {
            "policy_id": self.policy_id,
            "confusion_matrix": self.confusion_matrix,
            "metrics": {
                "false_success": self.false_success.to_dict(),
                "verified_recall": self.verified_recall.to_dict(),
                "contradiction_recall": self.contradiction_recall.to_dict(),
                "inconclusive_rate": self.inconclusive_rate.to_dict(),
            },
            "verdicts": self.verdicts,
        }


class _MetricDocument(BaseModel):
    model_config = ConfigDict(extra="forbid")

    count: int = Field(ge=0)
    denominator: int = Field(ge=0)
    rate: float | None

    @model_validator(mode="after")
    def validate_rate(self) -> _MetricDocument:
        expected = self.count / self.denominator if self.denominator else None
        if expected is None:
            if self.rate is not None:
                raise ValueError("분모가 0인 지표의 rate는 null이어야 한다")
        elif self.rate is None or not math.isclose(
            self.rate,
            expected,
            rel_tol=0,
            abs_tol=1e-12,
        ):
            raise ValueError("지표 count/denominator/rate가 일치하지 않는다")
        return self


class _PolicyMetricsDocument(BaseModel):
    model_config = ConfigDict(extra="forbid")

    false_success: _MetricDocument
    verified_recall: _MetricDocument
    contradiction_recall: _MetricDocument
    inconclusive_rate: _MetricDocument


class _PolicyReportDocument(BaseModel):
    model_config = ConfigDict(extra="forbid")

    policy_id: str = Field(pattern=r"^[a-z0-9][a-z0-9._:-]+$")
    confusion_matrix: dict[str, dict[str, int]]
    metrics: _PolicyMetricsDocument
    verdicts: dict[str, CompletionVerdict]

    @model_validator(mode="after")
    def validate_matrix_and_metrics(self) -> _PolicyReportDocument:
        verdict_names = {verdict.value for verdict in CompletionVerdict}
        if set(self.confusion_matrix) != verdict_names or any(
            set(row) != verdict_names for row in self.confusion_matrix.values()
        ):
            raise ValueError("혼동 행렬의 verdict 축이 완전하지 않다")
        if any(
            count < 0
            for row in self.confusion_matrix.values()
            for count in row.values()
        ):
            raise ValueError("혼동 행렬 count는 음수일 수 없다")
        if any(not _SAFE_CASE_ID.fullmatch(case_id) for case_id in self.verdicts):
            raise ValueError("보고서 case id 형식이 잘못됐다")

        matrix = self.confusion_matrix
        expected_non_verified = sum(
            sum(matrix[expected].values())
            for expected in verdict_names
            if expected != CompletionVerdict.VERIFIED.value
        )
        expected_verified = sum(matrix[CompletionVerdict.VERIFIED.value].values())
        expected_contradicted = sum(
            matrix[CompletionVerdict.CONTRADICTED.value].values()
        )
        total = sum(sum(row.values()) for row in matrix.values())
        expected_metrics = {
            "false_success": (
                sum(
                    matrix[expected][CompletionVerdict.VERIFIED.value]
                    for expected in verdict_names
                    if expected != CompletionVerdict.VERIFIED.value
                ),
                expected_non_verified,
            ),
            "verified_recall": (
                matrix[CompletionVerdict.VERIFIED.value][
                    CompletionVerdict.VERIFIED.value
                ],
                expected_verified,
            ),
            "contradiction_recall": (
                matrix[CompletionVerdict.CONTRADICTED.value][
                    CompletionVerdict.CONTRADICTED.value
                ],
                expected_contradicted,
            ),
            "inconclusive_rate": (
                sum(
                    row[CompletionVerdict.INCONCLUSIVE.value] for row in matrix.values()
                ),
                total,
            ),
        }
        for name, expected in expected_metrics.items():
            metric = getattr(self.metrics, name)
            if (metric.count, metric.denominator) != expected:
                raise ValueError(f"{name} 지표가 혼동 행렬과 일치하지 않는다")
        return self


class _GateDocument(BaseModel):
    model_config = ConfigDict(extra="forbid")

    false_success_not_increased: bool


class _ComparisonReportDocument(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal[1]
    experiment_id: str = Field(pattern=r"^[0-9a-f]{64}$")
    run_id: str = Field(pattern=r"^[0-9a-f]{64}$")
    corpus_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    git_sha: str = Field(pattern=r"^[0-9a-f]{40}$")
    case_count: int = Field(gt=0)
    baseline: _PolicyReportDocument
    candidate: _PolicyReportDocument
    gate: _GateDocument

    @model_validator(mode="after")
    def validate_cross_report_invariants(self) -> _ComparisonReportDocument:
        baseline_total = sum(
            sum(row.values()) for row in self.baseline.confusion_matrix.values()
        )
        candidate_total = sum(
            sum(row.values()) for row in self.candidate.confusion_matrix.values()
        )
        if baseline_total != self.case_count or candidate_total != self.case_count:
            raise ValueError("case_count와 혼동 행렬 합계가 일치하지 않는다")
        if (
            set(self.baseline.verdicts) != set(self.candidate.verdicts)
            or len(self.baseline.verdicts) != self.case_count
        ):
            raise ValueError("두 정책의 case id 집합이 일치하지 않는다")
        expected_gate = (
            self.candidate.metrics.false_success.count
            <= self.baseline.metrics.false_success.count
        )
        if self.gate.false_success_not_increased is not expected_gate:
            raise ValueError("false success gate가 지표와 일치하지 않는다")
        return self


class IncompatibleComparisonError(ValueError):
    """서로 다른 corpus·정책의 수치를 같은 실험으로 비교하려 한 오류."""


@dataclass(frozen=True)
class ComparisonReport:
    schema_version: int
    experiment_id: str
    run_id: str
    corpus_sha256: str
    git_sha: str
    case_count: int
    baseline: PolicyReport
    candidate: PolicyReport
    false_success_regression: bool

    @classmethod
    def from_dict(cls, value: Any) -> ComparisonReport:
        """CLI JSON을 검증해 다시 비교 가능한 보고서로 복원한다."""
        document = _ComparisonReportDocument.model_validate(value)

        def metric(item: _MetricDocument) -> Metric:
            return Metric(numerator=item.count, denominator=item.denominator)

        def policy(item: _PolicyReportDocument) -> PolicyReport:
            return PolicyReport(
                policy_id=item.policy_id,
                confusion_matrix=item.confusion_matrix,
                false_success=metric(item.metrics.false_success),
                verified_recall=metric(item.metrics.verified_recall),
                contradiction_recall=metric(item.metrics.contradiction_recall),
                inconclusive_rate=metric(item.metrics.inconclusive_rate),
                verdicts={
                    case_id: verdict.value for case_id, verdict in item.verdicts.items()
                },
            )

        report = cls(
            schema_version=document.schema_version,
            experiment_id=document.experiment_id,
            run_id=document.run_id,
            corpus_sha256=document.corpus_sha256,
            git_sha=document.git_sha,
            case_count=document.case_count,
            baseline=policy(document.baseline),
            candidate=policy(document.candidate),
            false_success_regression=(not document.gate.false_success_not_increased),
        )
        experiment_id, run_id = _report_ids(
            corpus_sha256=report.corpus_sha256,
            baseline_policy_id=report.baseline.policy_id,
            candidate_policy_id=report.candidate.policy_id,
            git_sha=report.git_sha,
        )
        if report.experiment_id != experiment_id or report.run_id != run_id:
            raise IncompatibleComparisonError(
                "보고서 식별자가 corpus·정책·git 메타데이터와 일치하지 않는다"
            )
        return report

    def assert_same_experiment(self, other: ComparisonReport) -> None:
        mismatches: list[str] = []
        if self.schema_version != other.schema_version:
            mismatches.append("schema_version")
        if self.corpus_sha256 != other.corpus_sha256:
            mismatches.append("corpus_sha256")
        if self.baseline.policy_id != other.baseline.policy_id:
            mismatches.append("baseline_policy_id")
        if self.candidate.policy_id != other.candidate.policy_id:
            mismatches.append("candidate_policy_id")
        if mismatches:
            raise IncompatibleComparisonError(
                "서로 다른 A/B 실험 결과: " + ", ".join(mismatches)
            )

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "experiment_id": self.experiment_id,
            "run_id": self.run_id,
            "corpus_sha256": self.corpus_sha256,
            "git_sha": self.git_sha,
            "case_count": self.case_count,
            "baseline": self.baseline.to_dict(),
            "candidate": self.candidate.to_dict(),
            "gate": {
                "false_success_not_increased": not self.false_success_regression,
            },
        }


def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise FixtureError(f"{path.name}: JSON을 읽을 수 없음 (원문 생략)") from error


def load_comparison_report(path: Path) -> ComparisonReport:
    """저장한 CLI 보고서를 원문 노출 없이 검증하고 복원한다."""
    raw = _read_json(path)
    try:
        return ComparisonReport.from_dict(raw)
    except ValidationError as error:
        locations = _validation_locations(error)
        raise FixtureError(
            f"{path.name}: completion A/B 보고서 형식 오류: {locations} (입력값 생략)"
        ) from error
    except IncompatibleComparisonError as error:
        raise FixtureError(f"{path.name}: {error}") from error


def _corpus_hash(
    dataset_raw: Any,
    referenced_fixtures: dict[str, Any],
) -> str:
    """dataset과 실제 참조 화면을 함께 묶은 의미상 corpus 해시."""
    digest = hashlib.sha256(b"davi-completion-corpus-v1\0")
    digest.update(_canonical_json(dataset_raw))
    for name, payload in sorted(referenced_fixtures.items()):
        digest.update(b"\0fixture\0")
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(_canonical_json(payload))
    return digest.hexdigest()


def load_completion_dataset(
    path: Path = COMPLETION_CASES,
    fixtures_dir: Path = FIXTURES,
) -> LoadedCompletionDataset:
    """PII·스키마·참조 fixture를 검증하고 A/B 입력을 한 번만 만든다."""
    raw = _read_json(path)
    assert_no_sensitive_data(raw, filename=path.name)
    try:
        dataset = CompletionDataset.model_validate(raw)
    except ValidationError as error:
        locations = _validation_locations(error)
        raise FixtureError(
            f"{path.name}: completion fixture 형식 오류: {locations} (입력값 생략)"
        ) from error

    ids = [case.id for case in dataset.cases]
    duplicates = sorted({case_id for case_id in ids if ids.count(case_id) > 1})
    if duplicates:
        raise FixtureError(f"중복 completion case id: {', '.join(duplicates)}")

    loaded: list[LoadedCompletionCase] = []
    referenced: dict[str, Any] = {}
    for case in dataset.cases:
        if case.request is not None:
            try:
                request = NextStepRequest.model_validate(case.request)
            except ValidationError as error:
                locations = _validation_locations(error)
                raise FixtureError(
                    f"{path.name}: {case.id} 요청 스키마 오류: {locations} (입력값 생략)"
                ) from error
            normalized = request.model_dump(mode="json", exclude_unset=True)
            if _canonical_json(case.request) != _canonical_json(normalized):
                raise FixtureError(
                    f"{path.name}: {case.id} 요청이 스키마 왕복에서 보존되지 않음 "
                    "(입력값 생략)"
                )
        else:
            assert case.screen_fixture is not None
            fixture_path = fixtures_dir / case.screen_fixture
            fixture = load_fixture(fixture_path)
            request = fixture.request
            if case.goal is not None:
                request = request.model_copy(update={"goal": case.goal})
            referenced[case.screen_fixture] = _read_json(fixture_path)
        loaded.append(LoadedCompletionCase(case=case, request=request))

    return LoadedCompletionDataset(
        dataset=dataset,
        cases=tuple(loaded),
        corpus_sha256=_corpus_hash(raw, referenced),
    )


def _done_action(case: CompletionCase) -> GuideAction:
    return GuideAction(
        expected_result=case.expected_result,
        screen_summary="평가 화면",
        evidence=case.evidence,
        action_type=ActionType.DONE,
        instruction="완료했습니다.",
    )


def _empty_matrix() -> dict[str, dict[str, int]]:
    return {
        expected.value: {actual.value: 0 for actual in CompletionVerdict}
        for expected in CompletionVerdict
    }


def _evaluate_policy(
    loaded: LoadedCompletionDataset,
    policy: CompletionPolicy,
) -> PolicyReport:
    matrix = _empty_matrix()
    verdicts: dict[str, str] = {}
    for item in loaded.cases:
        actual = CompletionVerdict(policy.evaluate(item.case, item.request))
        expected = item.case.expected_verdict
        matrix[expected.value][actual.value] += 1
        verdicts[item.case.id] = actual.value

    expected_non_verified = sum(
        sum(matrix[expected.value].values())
        for expected in CompletionVerdict
        if expected is not CompletionVerdict.VERIFIED
    )
    expected_verified = sum(matrix[CompletionVerdict.VERIFIED.value].values())
    expected_contradicted = sum(matrix[CompletionVerdict.CONTRADICTED.value].values())
    total = len(loaded.cases)

    return PolicyReport(
        policy_id=policy.policy_id,
        confusion_matrix=matrix,
        false_success=Metric(
            numerator=sum(
                matrix[expected.value][CompletionVerdict.VERIFIED.value]
                for expected in CompletionVerdict
                if expected is not CompletionVerdict.VERIFIED
            ),
            denominator=expected_non_verified,
        ),
        verified_recall=Metric(
            numerator=matrix[CompletionVerdict.VERIFIED.value][
                CompletionVerdict.VERIFIED.value
            ],
            denominator=expected_verified,
        ),
        contradiction_recall=Metric(
            numerator=matrix[CompletionVerdict.CONTRADICTED.value][
                CompletionVerdict.CONTRADICTED.value
            ],
            denominator=expected_contradicted,
        ),
        inconclusive_rate=Metric(
            numerator=sum(
                row[CompletionVerdict.INCONCLUSIVE.value] for row in matrix.values()
            ),
            denominator=total,
        ),
        verdicts=verdicts,
    )


def _report_ids(
    *,
    corpus_sha256: str,
    baseline_policy_id: str,
    candidate_policy_id: str,
    git_sha: str,
) -> tuple[str, str]:
    identity = {
        "schema_version": 1,
        "corpus_sha256": corpus_sha256,
        "baseline_policy_id": baseline_policy_id,
        "candidate_policy_id": candidate_policy_id,
    }
    experiment_id = hashlib.sha256(_canonical_json(identity)).hexdigest()
    run_id = hashlib.sha256(
        _canonical_json({**identity, "git_sha": git_sha})
    ).hexdigest()
    return experiment_id, run_id


def run_comparison(
    loaded: LoadedCompletionDataset,
    *,
    baseline: CompletionPolicy | None = None,
    candidate: CompletionPolicy | None = None,
    git_sha: str,
) -> ComparisonReport:
    """같은 입력으로 두 adapter를 실행하고 false-success 회귀 여부를 계산한다."""
    baseline_policy = baseline or BaselineCompletionPolicy()
    candidate_policy = candidate or CurrentServerCandidatePolicy()
    baseline_report = _evaluate_policy(loaded, baseline_policy)
    candidate_report = _evaluate_policy(loaded, candidate_policy)

    experiment_id, run_id = _report_ids(
        corpus_sha256=loaded.corpus_sha256,
        baseline_policy_id=baseline_report.policy_id,
        candidate_policy_id=candidate_report.policy_id,
        git_sha=git_sha,
    )

    return ComparisonReport(
        schema_version=1,
        experiment_id=experiment_id,
        run_id=run_id,
        corpus_sha256=loaded.corpus_sha256,
        git_sha=git_sha,
        case_count=len(loaded.cases),
        baseline=baseline_report,
        candidate=candidate_report,
        false_success_regression=(
            candidate_report.false_success.numerator
            > baseline_report.false_success.numerator
        ),
    )


def current_git_sha(root: Path = ROOT) -> str:
    """보고서가 어느 checkout에서 나왔는지 숨김없이 기록한다."""
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    sha = completed.stdout.strip().lower()
    if len(sha) != 40 or any(char not in "0123456789abcdef" for char in sha):
        raise RuntimeError("현재 git SHA를 40자리 hex로 읽지 못함")
    return sha


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="완료 판정 baseline/candidate keyless A/B 보고서를 출력한다."
    )
    parser.add_argument(
        "--compact",
        action="store_true",
        help="들여쓰기 없는 JSON으로 출력한다.",
    )
    parser.add_argument(
        "--compare",
        type=Path,
        metavar="REPORT.json",
        help="저장한 보고서를 읽어 현재 실행과 같은 corpus·정책·스키마인지 검사한다.",
    )
    args = parser.parse_args(argv)

    try:
        loaded = load_completion_dataset()
        report = run_comparison(loaded, git_sha=current_git_sha())
        if args.compare is not None:
            previous = load_comparison_report(args.compare)
            previous.assert_same_experiment(report)
    except (
        FixtureError,
        IncompatibleComparisonError,
        RuntimeError,
        subprocess.SubprocessError,
    ) as error:
        print(f"completion A/B 실행 실패: {error}", file=sys.stderr)
        return 1

    print(
        json.dumps(
            report.to_dict(),
            ensure_ascii=False,
            sort_keys=True,
            indent=None if args.compact else 2,
        )
    )
    if report.false_success_regression:
        print(
            "completion A/B 실패: candidate false success가 baseline보다 증가함",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
