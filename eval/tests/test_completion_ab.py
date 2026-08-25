from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

import pytest

import completion_ab
from completion_ab import (
    COMPLETION_CASES,
    BaselineCompletionPolicy,
    CompletionCase,
    CompletionVerdict,
    CurrentServerCandidatePolicy,
    IncompatibleComparisonError,
    _corpus_hash,
    current_git_sha,
    load_comparison_report,
    load_completion_dataset,
    run_comparison,
)
from harness import FIXTURES, FixtureError

_GIT_SHA = "a" * 40


class _FixedPolicy:
    def __init__(self, policy_id: str, verdict: CompletionVerdict) -> None:
        self.policy_id = policy_id
        self._verdict = verdict

    def evaluate(self, case: CompletionCase, request) -> CompletionVerdict:
        return self._verdict


def test_completion_fixture_has_human_tristate_verdict_and_is_pii_safe():
    loaded = load_completion_dataset()

    assert loaded.dataset.dataset_version == 2
    assert len(loaded.cases) == 18
    assert {item.case.expected_verdict for item in loaded.cases} == {
        CompletionVerdict.VERIFIED,
        CompletionVerdict.INCONCLUSIVE,
        CompletionVerdict.CONTRADICTED,
    }
    by_id = {item.case.id: item.case for item in loaded.cases}
    assert not by_id["invalid-opposite-state-prefix"].expected_verified
    assert (
        by_id["invalid-opposite-state-prefix"].expected_verdict
        is CompletionVerdict.VERIFIED
    )
    assert len(loaded.corpus_sha256) == 64


def test_completion_fixture_pii_error_does_not_expose_original(tmp_path: Path):
    sensitive = "010-1234-5678"
    raw = json.loads(COMPLETION_CASES.read_text(encoding="utf-8"))
    raw["cases"][0]["request"]["screen"]["elements"][0]["label"] = sensitive
    path = tmp_path / "completion.json"
    path.write_text(json.dumps(raw, ensure_ascii=False), encoding="utf-8")

    with pytest.raises(FixtureError) as raised:
        load_completion_dataset(path, FIXTURES)

    message = str(raised.value)
    assert sensitive not in message
    assert "원문 생략" in message


def test_corpus_hash_includes_referenced_fixture_content_and_ignores_key_order():
    dataset_a = {"dataset_version": 2, "description": "safe", "cases": []}
    dataset_b = {"cases": [], "description": "safe", "dataset_version": 2}
    fixture_a = {"input": {"screen": {"package_name": "com.example.a"}}}
    fixture_b = {"input": {"screen": {"package_name": "com.example.b"}}}

    first = _corpus_hash(dataset_a, {"screen.json": fixture_a})
    same = _corpus_hash(dataset_b, {"screen.json": fixture_a})
    changed = _corpus_hash(dataset_a, {"screen.json": fixture_b})

    assert first == same
    assert first != changed


def test_default_ab_report_has_confusion_matrices_metrics_and_identity():
    loaded = load_completion_dataset()
    report = run_comparison(loaded, git_sha=_GIT_SHA)

    expected_matrix = {
        "verified": {"verified": 8, "inconclusive": 2, "contradicted": 0},
        "inconclusive": {"verified": 2, "inconclusive": 1, "contradicted": 0},
        "contradicted": {"verified": 2, "inconclusive": 3, "contradicted": 0},
    }
    assert report.baseline.policy_id == BaselineCompletionPolicy.policy_id
    assert report.candidate.policy_id == CurrentServerCandidatePolicy.policy_id
    assert report.baseline.confusion_matrix == expected_matrix
    assert report.candidate.confusion_matrix == expected_matrix
    assert report.baseline.verdicts == report.candidate.verdicts

    for policy in (report.baseline, report.candidate):
        assert policy.false_success.numerator == 4
        assert policy.false_success.denominator == 8
        assert policy.false_success.rate == pytest.approx(1 / 2)
        assert policy.verified_recall.numerator == 8
        assert policy.verified_recall.denominator == 10
        assert policy.verified_recall.rate == pytest.approx(4 / 5)
        assert policy.contradiction_recall.numerator == 0
        assert policy.contradiction_recall.denominator == 5
        assert policy.contradiction_recall.rate == 0
        assert policy.inconclusive_rate.numerator == 6
        assert policy.inconclusive_rate.denominator == 18
        assert policy.inconclusive_rate.rate == pytest.approx(1 / 3)

    assert report.corpus_sha256 == loaded.corpus_sha256
    assert report.git_sha == _GIT_SHA
    assert len(report.experiment_id) == 64
    assert len(report.run_id) == 64
    assert not report.false_success_regression


def test_false_success_increase_is_an_explicit_gate_failure():
    loaded = load_completion_dataset()
    report = run_comparison(
        loaded,
        baseline=_FixedPolicy("safe-baseline", CompletionVerdict.INCONCLUSIVE),
        candidate=_FixedPolicy("unsafe-candidate", CompletionVerdict.VERIFIED),
        git_sha=_GIT_SHA,
    )

    assert report.baseline.false_success.numerator == 0
    assert report.candidate.false_success.numerator == 8
    assert report.false_success_regression
    assert report.to_dict()["gate"]["false_success_not_increased"] is False


def test_cli_fails_when_server_candidate_regresses_without_changing_baseline(
    monkeypatch,
    capsys,
):
    monkeypatch.setattr(completion_ab, "_verify_done", lambda action, request: True)
    monkeypatch.setattr(completion_ab, "current_git_sha", lambda: _GIT_SHA)

    exit_code = completion_ab.main(["--compact"])
    captured = capsys.readouterr()
    payload = json.loads(captured.out)

    assert exit_code == 1
    assert payload["baseline"]["metrics"]["false_success"]["count"] == 4
    assert payload["candidate"]["metrics"]["false_success"]["count"] == 8
    assert payload["gate"]["false_success_not_increased"] is False
    assert "candidate false success" in captured.err


@pytest.mark.parametrize(
    "field",
    ["schema", "corpus", "baseline", "candidate"],
)
def test_different_corpus_or_policy_id_is_not_treated_as_same_experiment(field: str):
    report = run_comparison(load_completion_dataset(), git_sha=_GIT_SHA)
    other = report
    if field == "schema":
        other = replace(report, schema_version=2)
    elif field == "corpus":
        other = replace(report, corpus_sha256="0" * 64)
    elif field == "baseline":
        other = replace(
            report,
            baseline=replace(report.baseline, policy_id="different-baseline"),
        )
    else:
        other = replace(
            report,
            candidate=replace(report.candidate, policy_id="different-candidate"),
        )

    with pytest.raises(IncompatibleComparisonError, match="서로 다른 A/B 실험 결과"):
        report.assert_same_experiment(other)


def test_saved_report_round_trips_and_can_be_compared(tmp_path: Path):
    report = run_comparison(load_completion_dataset(), git_sha=_GIT_SHA)
    path = tmp_path / "report.json"
    path.write_text(
        json.dumps(report.to_dict(), ensure_ascii=False),
        encoding="utf-8",
    )

    restored = load_comparison_report(path)

    assert restored == report
    restored.assert_same_experiment(report)


def test_saved_report_rejects_policy_id_that_does_not_match_experiment_id(
    tmp_path: Path,
):
    report = run_comparison(load_completion_dataset(), git_sha=_GIT_SHA)
    payload = report.to_dict()
    payload["candidate"]["policy_id"] = "tampered-candidate"
    path = tmp_path / "tampered.json"
    path.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(FixtureError, match="보고서 식별자"):
        load_comparison_report(path)


def test_cli_compare_accepts_a_saved_report_from_the_same_experiment(
    tmp_path: Path,
    monkeypatch,
    capsys,
):
    report = run_comparison(load_completion_dataset(), git_sha=_GIT_SHA)
    path = tmp_path / "same.json"
    path.write_text(json.dumps(report.to_dict()), encoding="utf-8")
    monkeypatch.setattr(completion_ab, "current_git_sha", lambda: "b" * 40)

    exit_code = completion_ab.main(["--compare", str(path), "--compact"])
    captured = capsys.readouterr()

    assert exit_code == 0
    assert json.loads(captured.out)["git_sha"] == "b" * 40
    assert captured.err == ""


def test_cli_compare_rejects_a_valid_report_from_another_policy(
    tmp_path: Path,
    monkeypatch,
    capsys,
):
    other = run_comparison(
        load_completion_dataset(),
        candidate=_FixedPolicy("another-candidate", CompletionVerdict.INCONCLUSIVE),
        git_sha=_GIT_SHA,
    )
    path = tmp_path / "other.json"
    path.write_text(json.dumps(other.to_dict()), encoding="utf-8")
    monkeypatch.setattr(completion_ab, "current_git_sha", lambda: "b" * 40)

    exit_code = completion_ab.main(["--compare", str(path), "--compact"])
    captured = capsys.readouterr()

    assert exit_code == 1
    assert captured.out == ""
    assert "candidate_policy_id" in captured.err


def test_git_sha_is_recorded_from_current_checkout():
    sha = current_git_sha()

    assert len(sha) == 40
    assert all(char in "0123456789abcdef" for char in sha)
