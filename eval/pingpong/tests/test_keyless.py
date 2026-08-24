"""API 키 없이 도는 층. 시나리오 계약·유출 검사·루프 배선·어댑터 파싱을 지킨다."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from pingpong_eval.cli import DEFAULT_SCENARIOS, _load_run, load_scenarios
from pingpong_eval.metrics import summarize
from pingpong_eval.models import (
    Exposure,
    PingpongStatus,
    PingpongTurn,
    Question,
    Slot,
)
from pingpong_eval.report import render_completion_comparison
from pingpong_eval.runner import run_scenario
from pingpong_eval.sut import PingpongError, _parse
from pingpong_eval.user_sim import leaked_slots

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture(scope="module")
def scenarios():
    return load_scenarios(ROOT / DEFAULT_SCENARIOS).scenarios


def test_시나리오_파일이_계약을_지킨다(scenarios):
    assert len(scenarios) == 30
    assert len({s.id for s in scenarios}) == 30
    for scenario in scenarios:
        assert scenario.slots, f"{scenario.id}: 슬롯이 없다"
        assert scenario.first_slots, f"{scenario.id}: 처음 말할 수 있는 슬롯이 없다"
        assert scenario.reference_goal.strip()


def test_난이도가_세_티어로_고르게_있다(scenarios):
    counts = {}
    for scenario in scenarios:
        counts[scenario.difficulty.value] = counts.get(scenario.difficulty.value, 0) + 1
    assert counts == {"쉬움": 10, "중간": 10, "어려움": 10}


def test_숨은_슬롯에는_요인이_붙어_있다(scenarios):
    """되물어야 나오거나 사용자도 모르는 슬롯은 난이도 요인으로 드러나야 한다."""

    for scenario in scenarios:
        if any(s.exposure is not Exposure.FIRST for s in scenario.slots):
            assert scenario.factors, f"{scenario.id}: 숨은 슬롯이 있는데 요인이 비었다"


def test_유출_검사는_긴_값만_본다():
    slots = [
        Slot(키="지역", 값="인천 서구", 노출="되물음", 사용자_표현="딸 집"),
        Slot(키="수량", 값="1", 노출="처음"),
    ]
    assert leaked_slots("딸 집에 비 오나 봐줘", slots)[0].key == "지역"
    assert leaked_slots("인천 서구에 비 와?", slots)[0].key == "지역"
    assert leaked_slots("하나만 사줘", slots) == []


def test_모르는_status는_실패한다():
    with pytest.raises(PingpongError):
        _parse({"status": "누구세요", "message": "x"}, 0)


class _FakeUser:
    """질문마다 첫 보기를 고르고, 보기가 없으면 말로 답한다."""

    def opening(self, scenario, avoid=None):
        return "사진 좀 보내려고", scenario.first_slots[:1]

    def answer(self, scenario, question, said):
        from pingpong_eval.models import AnswerSource, UserAnswer

        if question.options:
            return UserAnswer(
                question_id=question.id,
                prompt=question.prompt,
                answer=question.options[0],
                source=AnswerSource.OPTION,
                chosen_option=0,
                revealed_slots=["대상"],
            )
        return UserAnswer(
            question_id=question.id,
            prompt=question.prompt,
            answer="딸이요",
            source=AnswerSource.FREEFORM,
            revealed_slots=["대상"],
        )


class _ClarificationAdapter:
    """#326 계약대로 첫 응답에 질문 2개를 주고, 두 번째 요청에서 확정한다."""

    def __init__(self, second_status="ready"):
        self.sent = []
        self._second_status = second_status

    def send(self, utterance, history, answers=None):
        self.sent.append(
            {"utterance": utterance, "history": list(history), "answers": answers or []}
        )
        if len(self.sent) == 1:
            return PingpongTurn(
                status=PingpongStatus.NEEDS_CLARIFICATION,
                message="누구에게 보낼까요?",
                questions=[
                    Question(
                        id="q1", prompt="누구에게 보낼까요?", options=["딸", "아들"]
                    ),
                    Question(
                        id="q2",
                        prompt="어떤 방법으로 보낼까요?",
                        options=["카카오톡", "문자"],
                    ),
                ],
            )
        if self._second_status == "ready":
            return PingpongTurn(
                status=PingpongStatus.READY, message="확인", goal="확정된 목표"
            )
        return PingpongTurn(
            status=PingpongStatus.UNABLE_TO_FINALIZE, message="다시 말씀해 주세요"
        )


def test_326_흐름은_요청_두_번으로_끝난다(scenarios):
    """첫 요청 → 질문 1~2개를 로컬에서 답 → 두 번째 요청. 이것이 앱이 실제로 하는 핑퐁이다."""

    adapter = _ClarificationAdapter()
    result = run_scenario(scenarios[0], _FakeUser(), adapter)

    assert len(adapter.sent) == 2
    assert result.transcript.stop_reason.value == "ready"
    assert result.transcript.final_goal == "확정된 목표"
    assert result.metrics["질문수"] == 2
    assert result.metrics["요청수"] == 2
    assert result.metrics["2회이내_ready"] is True
    # 새 화면은 첫 질문만 읽고, ready 확인 문구를 한 번 더 읽는다.
    assert result.metrics["TTS_발화수"] == 2
    assert result.metrics["TTS_문자수"] == len("누구에게 보낼까요?") + len("확인")
    assert result.metrics["보기_적중률"] == 1.0


def test_두_번째_요청은_원래_발화와_답을_함께_보낸다(scenarios):
    """앱의 originalUtterance와 같다 — 발화는 그대로 두고 답만 싣는다."""

    adapter = _ClarificationAdapter()
    run_scenario(scenarios[0], _FakeUser(), adapter)

    first, second = adapter.sent
    assert second["utterance"] == first["utterance"]
    assert second["history"] == []  # 이력에 쌓지 않는다
    wire = [answer.to_wire() for answer in second["answers"]]
    assert [w["question_id"] for w in wire] == ["q1", "q2"]
    assert [w["source"] for w in wire] == ["option", "option"]
    assert wire[0]["prompt"] == "누구에게 보낼까요?"


def test_안전_종료를_결과로_남긴다(scenarios):
    result = run_scenario(scenarios[0], _FakeUser(), _ClarificationAdapter("unable"))
    assert result.transcript.stop_reason.value == "unable_to_finalize"
    assert result.transcript.final_goal is None


def test_보기가_답을_못_담으면_자유입력으로_센다(scenarios):
    class _NoFitAdapter(_ClarificationAdapter):
        def send(self, utterance, history, answers=None):
            self.sent.append(
                {
                    "utterance": utterance,
                    "history": list(history),
                    "answers": answers or [],
                }
            )
            if len(self.sent) == 1:
                return PingpongTurn(
                    status=PingpongStatus.NEEDS_CLARIFICATION,
                    message="어디로 보낼까요?",
                    questions=[
                        Question(id="q1", prompt="어디로 보낼까요?", options=[])
                    ],
                )
            return PingpongTurn(
                status=PingpongStatus.READY, message="확인", goal="목표"
            )

    result = run_scenario(scenarios[0], _FakeUser(), _NoFitAdapter())
    assert result.metrics["자유입력"] == 1
    assert result.metrics["보기_적중률"] == 0.0


def test_어댑터가_clarification을_읽는다():
    turn = _parse(
        {
            "status": "needs_clarification",
            "message": "누구에게 보낼까요?",
            "clarification": {
                "questions": [
                    {
                        "id": "q1",
                        "prompt": "누구에게 보낼까요?",
                        "options": ["딸", "아들"],
                        "allow_freeform": True,
                    }
                ]
            },
        },
        5,
    )
    assert turn.legacy is False
    assert turn.questions[0].id == "q1"
    assert turn.questions[0].options == ["딸", "아들"]


def test_어댑터는_옛_형식을_legacy_질문_하나로_읽는다():
    turn = _parse({"status": "needs_clarification", "message": "어디로 보낼까요?"}, 12)
    assert turn.legacy is True
    assert len(turn.questions) == 1
    assert turn.questions[0].options == []


def test_안전_종료_상태를_읽는다():
    turn = _parse({"status": "unable_to_finalize", "message": "죄송해요"}, 1)
    assert turn.status.value == "unable_to_finalize"
    assert turn.questions == []


def test_옛_형식은_여러_왕복을_돈다(scenarios):
    """#326 이전 기준선을 다시 재거나 비교할 수 있어야 한다."""

    class _LegacyAdapter:
        def __init__(self):
            self.sent = 0

        def send(self, utterance, history, answers=None):
            self.sent += 1
            assert not answers  # 옛 형식에는 clarification_answers를 싣지 않는다
            return PingpongTurn(
                status=PingpongStatus.NEEDS_CLARIFICATION,
                message="뭘 하실래요?",
                questions=[
                    Question(id="legacy-message", prompt="뭘 하실래요?", options=[])
                ],
                legacy=True,
            )

    adapter = _LegacyAdapter()
    result = run_scenario(scenarios[0], _FakeUser(), adapter, max_legacy_requests=3)
    assert adapter.sent == 3
    assert result.transcript.stop_reason.value == "limit"
    assert result.metrics["TTS_발화수"] == 3
    assert result.metrics["TTS_문자수"] == len("뭘 하실래요?") * 3


def test_316_완료지표는_채점분모와_코드분모를_섞지_않는다(scenarios):
    from pingpong_eval.models import (
        Achievement,
        JudgeBatch,
        JudgeResult,
        SlotScore,
        SlotVerdict,
        Violation,
        ViolationAxis,
    )

    good = run_scenario(scenarios[0], _FakeUser(), _ClarificationAdapter())
    good.judge = JudgeResult(
        achievement=Achievement.FULL,
        reason="충족",
        slot_scores=[],
        violations=[],
    )
    good.judge_batch = JudgeBatch(model="judge", scenario_digest="same")

    hallucinated = run_scenario(scenarios[0], _FakeUser(), _ClarificationAdapter())
    hallucinated.missing_slot_keys = ["대상"]
    hallucinated.judge = JudgeResult(
        achievement=Achievement.FAIL,
        reason="누락 정보를 지어냄",
        slot_scores=[
            SlotScore(key="대상", verdict=SlotVerdict.WRONG, evidence="다른 사람")
        ],
        violations=[
            Violation(
                axis=ViolationAxis.FORCED_CHOICE,
                round_index=1,
                quote="둘 중 고르세요",
                why="선택 강요",
            )
        ],
    )
    hallucinated.judge_batch = JudgeBatch(model="judge", scenario_digest="same")

    summary = summarize([good, hallucinated], batch=("judge", "same"))["전체"]
    assert summary["ready율"] == 1.0
    assert summary["2회이내_ready율"] == 1.0
    assert summary["goal_유효율"] == 0.5
    assert summary["누락정보_환각률"] == 0.5
    assert summary["선택_강요율"] == 0.5
    assert summary["보기_선택률"] == 1.0

    report = render_completion_comparison("옛 흐름", summary, "새 흐름", summary)
    assert "| 옛 흐름 | 100% | 100% | 50% | 50% (n=2) | 50% | 100% |" in report
    assert "| 새 흐름 | 100% | 100% | 50% | 50% (n=2) | 50% | 100% |" in report


def test_TTS_평균은_ready로_완료된_실행만_세다(scenarios):
    from pingpong_eval.models import ScenarioResult, StopReason, Transcript

    spoken = run_scenario(scenarios[0], _FakeUser(), _ClarificationAdapter())
    failed_transcript = Transcript(
        scenario_id=scenarios[1].id,
        opening="",
        stop_reason=StopReason.ERROR,
        error="network unavailable",
    )
    failed = ScenarioResult(
        scenario_id=scenarios[1].id,
        difficulty=scenarios[1].difficulty,
        factors=scenarios[1].factors,
        transcript=failed_transcript,
        metrics={
            "TTS_발화수": 0,
            "TTS_문자수": 0,
            "2회이내_ready": False,
            "종료사유": "error",
        },
    )
    unable = run_scenario(scenarios[2], _FakeUser(), _ClarificationAdapter("unable"))
    assert unable.metrics["TTS_발화수"] == 1  # 첫 질문은 읽었지만 완료하지 못했다.

    summary = summarize([spoken, unable, failed])["전체"]
    assert summary["ready_평균_TTS_문자수"] == spoken.metrics["TTS_문자수"]


def test_예전_run은_transcript로_새_지표를_재계산한다(scenarios, tmp_path):
    from pingpong_eval.models import Achievement, JudgeBatch, JudgeResult

    result = run_scenario(scenarios[0], _FakeUser(), _ClarificationAdapter())
    result.judge = JudgeResult(
        achievement=Achievement.FULL,
        reason="충족",
        slot_scores=[],
        violations=[],
    )
    result.judge_batch = JudgeBatch(model="judge", scenario_digest="same")
    old_record = result.model_dump(mode="json", exclude={"missing_slot_keys"})
    old_record["metrics"] = {"질문수": 99}  # #316 지표가 없던 예전 저장값
    (tmp_path / "transcripts.jsonl").write_text(
        json.dumps(old_record, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (tmp_path / "meta.json").write_text("{}\n", encoding="utf-8")

    loaded, _ = _load_run(tmp_path)

    assert loaded[0].metrics["질문수"] == 2
    assert loaded[0].metrics["2회이내_ready"] is True
    assert loaded[0].missing_slot_keys is None
    summary = summarize(loaded, batch=("judge", "same"))["전체"]
    assert summary["누락정보_환각"] is None
    assert summary["누락정보_환각_분모"] == 0
    assert summary["누락정보_환각률"] is None


def test_누락정보_환각률은_측정불가_결과만_개별로_뺀다(scenarios):
    from pingpong_eval.models import Achievement, JudgeResult

    comparable = run_scenario(scenarios[0], _FakeUser(), _ClarificationAdapter())
    comparable.judge = JudgeResult(
        achievement=Achievement.FULL,
        reason="충족",
        slot_scores=[],
        violations=[],
    )
    unknown = comparable.model_copy(deep=True)
    unknown.scenario_id = scenarios[1].id
    unknown.missing_slot_keys = None

    summary = summarize([comparable, unknown])["전체"]

    assert summary["누락정보_환각"] == 0
    assert summary["누락정보_환각_분모"] == 1
    assert summary["누락정보_환각률"] == 0.0


def test_preflight는_스텁_서버를_거부한다():
    """스텁은 무조건 ready에 발화를 그대로 돌려준다 — 그 수치가 기준선처럼 남으면 안 된다."""

    import httpx
    from pingpong_eval.sut import SlotFillHttpAdapter

    adapter = SlotFillHttpAdapter(
        "http://localhost",
        session_id="s",
        client=httpx.Client(
            transport=httpx.MockTransport(
                lambda _r: httpx.Response(200, json={"status": "ok", "stub_llm": True})
            )
        ),
    )
    with pytest.raises(PingpongError, match="스텁"):
        adapter.preflight()


def test_preflight는_실제_모델_서버를_통과시킨다():
    import httpx
    from pingpong_eval.sut import SlotFillHttpAdapter

    for body in ({"status": "ok", "stub_llm": False}, {"status": "ok"}):
        adapter = SlotFillHttpAdapter(
            "http://localhost",
            session_id="s",
            client=httpx.Client(
                transport=httpx.MockTransport(
                    lambda _r, b=body: httpx.Response(200, json=b)
                )
            ),
        )
        adapter.preflight()  # production은 status만 알린다(#279) — 설정이 스텁을 금지한다


def _transcript(scenario):
    from pingpong_eval.models import Exchange, StopReason, Transcript

    return Transcript(
        scenario_id=scenario.id,
        opening="사진 보내줘",
        exchanges=[
            Exchange(
                index=1,
                turn=PingpongTurn(
                    status=PingpongStatus.NEEDS_CLARIFICATION,
                    message="누구에게요?",
                    questions=[Question(id="q1", prompt="누구에게요?", options=["딸"])],
                ),
            )
        ],
        final_goal="카카오톡으로 순자에게 사진 보내기",
        stop_reason=StopReason.READY,
    )


class _StubJudgeClient:
    """평가자 대역. 실제 호출 없이 계약 위반 출력만 흉내 낸다."""

    def __init__(self, scores):
        self._scores = scores
        self.model = "stub"
        self.base_url = None

    def parse(self, prompt, response_model, system=None):
        from pingpong_eval.models import Achievement, SlotScore, SlotVerdict

        return response_model(
            achievement=Achievement.PARTIAL,
            reason="테스트",
            slot_scores=[
                SlotScore(key=key, verdict=SlotVerdict(verdict), evidence="")
                for key, verdict in self._scores
            ],
            violations=[],
        )


def test_평가자가_없는_슬롯_키를_내면_실패한다(scenarios):
    """분모에 없는 행이 끼면 슬롯 정확률이 조용히 왜곡된다."""

    from pingpong_eval.judge import JudgeContractError, judge

    scenario = scenarios[0]
    with pytest.raises(JudgeContractError, match="없는 슬롯 키"):
        judge(
            _StubJudgeClient([("앱", "누락"), ("없는키", "틀림")]),
            scenario,
            _transcript(scenario),
        )


def test_평가자가_같은_키를_두_번_내면_실패한다(scenarios):
    from pingpong_eval.judge import JudgeContractError, judge

    scenario = scenarios[0]
    with pytest.raises(JudgeContractError, match="두 번"):
        judge(
            _StubJudgeClient([("앱", "누락"), ("앱", "부분")]),
            scenario,
            _transcript(scenario),
        )


def test_빠진_슬롯만_정확으로_보충한다(scenarios):
    from pingpong_eval.judge import judge

    scenario = scenarios[0]  # e01: 앱·행위·대상
    result = judge(_StubJudgeClient([("앱", "누락")]), scenario, _transcript(scenario))

    assert len(result.slot_scores) == len(scenario.slots)
    verdicts = {s.key: s.verdict.value for s in result.slot_scores}
    assert verdicts == {"앱": "누락", "행위": "정확", "대상": "정확"}


class _StubGemini:
    model = "stub-user"


class _WiredAdapter(_ClarificationAdapter):
    """cli가 만드는 그대로의 시그니처로 #326 두-요청 흐름을 흉내 낸다."""

    def __init__(self, base_url, *, session_id, dev_token=None):
        super().__init__()

    def preflight(self):
        pass

    def close(self):
        pass


class _DeadAdapter(_WiredAdapter):
    def preflight(self):
        raise PingpongError("서버에 닿지 못했다")


def _patch_run(monkeypatch, adapter_cls):
    from pingpong_eval import cli

    monkeypatch.setattr(cli, "GeminiClient", _StubGemini)
    monkeypatch.setattr(cli, "UserSimulator", lambda client, rng=None: _FakeUser())
    monkeypatch.setattr(cli, "SlotFillHttpAdapter", adapter_cls)
    return cli


def test_run_명령이_끝까지_배선돼_돈다(monkeypatch, tmp_path):
    """파서가 만든 인자 이름과 run_scenario의 시그니처가 어긋나면 여기서 죽는다."""

    cli = _patch_run(monkeypatch, _WiredAdapter)
    code = cli.main(
        [
            "run",
            "--scenarios",
            str(ROOT / DEFAULT_SCENARIOS),
            "--limit",
            "1",
            "--out",
            str(tmp_path),
            "--no-judge",
        ]
    )
    assert code == 0
    (run_dir,) = tmp_path.iterdir()
    assert (run_dir / "report.md").exists()
    assert (run_dir / "transcripts.jsonl").exists()
    meta = json.loads((run_dir / "meta.json").read_text(encoding="utf-8"))
    first_id = load_scenarios(ROOT / DEFAULT_SCENARIOS).scenarios[0].id
    assert first_id in (run_dir / "transcripts.jsonl").read_text(encoding="utf-8")
    # 생략해도 실제로 쓴 시드는 복원 가능해야 한다.
    assert isinstance(meta["seed"], int)
    report = (run_dir / "report.md").read_text(encoding="utf-8")
    assert f"seed {meta['seed']}" in report


def test_실행_실패는_현_지표_계약으로_남고_exit_code로_드러난다(monkeypatch, tmp_path):
    """실패 결과의 지표 키가 성공 경로와 다르면 집계·요약이 깨지고,
    exit 0이면 깨진 실행이 정상 기준선처럼 남는다."""

    import json

    from pingpong_eval.models import ScenarioResult

    cli = _patch_run(monkeypatch, _DeadAdapter)
    code = cli.main(
        [
            "run",
            "--scenarios",
            str(ROOT / DEFAULT_SCENARIOS),
            "--limit",
            "2",
            "--out",
            str(tmp_path),
            "--no-judge",
        ]
    )
    assert code == 1
    (run_dir,) = tmp_path.iterdir()
    lines = (run_dir / "transcripts.jsonl").read_text(encoding="utf-8").splitlines()
    results = [ScenarioResult.model_validate_json(line) for line in lines]
    assert len(results) == 2
    for result in results:
        assert result.metrics["종료사유"] == "error"
        assert result.metrics["질문수"] == 0  # 성공 경로와 같은 키가 있어야 한다
    summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
    assert summary["전체"]["건수"] == 2


def _make_run_dir(
    tmp_path, scenario, *, digest, judge=None, name="run", meta_overrides=None
):
    import json

    from pingpong_eval.models import ScenarioResult, StopReason, Transcript

    run_dir = tmp_path / name
    run_dir.mkdir()
    meta = {"시나리오해시": digest}
    meta.update(meta_overrides or {})
    (run_dir / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
    result = ScenarioResult(
        scenario_id=scenario.id,
        difficulty=scenario.difficulty,
        factors=scenario.factors,
        transcript=Transcript(
            scenario_id=scenario.id, opening="사진 보내줘", stop_reason=StopReason.READY
        ),
        judge=judge,
    )
    (run_dir / "transcripts.jsonl").write_text(
        result.model_dump_json() + "\n", encoding="utf-8"
    )
    return run_dir


@pytest.mark.parametrize(
    ("field", "baseline_value", "candidate_value"),
    [("seed", 1, 2), ("max_requests", 1, 2)],
)
def test_compare는_실행_조건이_다르면_막는다(
    scenarios, tmp_path, capsys, field, baseline_value, candidate_value
):
    from pingpong_eval import cli

    common = {"seed": 1, "max_requests": 2}
    baseline_meta = {**common, field: baseline_value}
    candidate_meta = {**common, field: candidate_value}
    baseline = _make_run_dir(
        tmp_path,
        scenarios[0],
        digest="same",
        name="baseline",
        meta_overrides=baseline_meta,
    )
    candidate = _make_run_dir(
        tmp_path,
        scenarios[0],
        digest="same",
        name="candidate",
        meta_overrides=candidate_meta,
    )

    assert cli.main(["compare", str(baseline), str(candidate)]) == 1
    drift_report = tmp_path / "drift.md"
    assert (
        cli.main(
            [
                "compare",
                str(baseline),
                str(candidate),
                "--allow-scenario-drift",
                "--out",
                str(drift_report),
            ]
        )
        == 0
    )
    warning = f"입력 조건 불일치를 알고 비교했다: {field}"
    assert warning in capsys.readouterr().err
    assert f"> ⚠️ {warning}" in drift_report.read_text(encoding="utf-8")


def test_compare는_파일이_같아도_실행_id가_다르면_막는다(scenarios, tmp_path):
    from pingpong_eval import cli

    meta = {"seed": 1, "max_requests": 2}
    baseline = _make_run_dir(
        tmp_path,
        scenarios[0],
        digest="same",
        name="baseline",
        meta_overrides=meta,
    )
    candidate = _make_run_dir(
        tmp_path,
        scenarios[1],
        digest="same",
        name="candidate",
        meta_overrides=meta,
    )

    assert cli.main(["compare", str(baseline), str(candidate)]) == 1


def test_judge는_시나리오_드리프트를_막고_옵션으로만_연다(tmp_path):
    """안내 문구가 가리키는 --allow-scenario-drift 가 실제로 있어야 복구 경로가 된다."""

    from pingpong_eval import cli

    scenario = load_scenarios(ROOT / DEFAULT_SCENARIOS).scenarios[0]
    run_dir = _make_run_dir(tmp_path, scenario, digest="실행당시와다름")

    base = ["judge", str(run_dir), "--scenarios", str(ROOT / DEFAULT_SCENARIOS)]
    assert cli.main([*base, "--dry-run"]) == 1
    assert cli.main([*base, "--dry-run", "--allow-scenario-drift"]) == 0


def test_재채점_실패는_이전_채점값을_남기지_않는다(monkeypatch, tmp_path):
    """옛 judge가 남은 채로 meta만 현재 평가자로 갱신되면,
    이전 점수가 이번 채점 결과처럼 집계된다(리뷰 실측)."""

    import json

    from pingpong_eval import cli
    from pingpong_eval.llm import LlmError
    from pingpong_eval.models import Achievement, JudgeResult, ScenarioResult

    class _FailingJudge:
        model = "stub-judge"
        base_url = None
        input_tokens = 0
        output_tokens = 0

        def parse(self, prompt, response_model, system=None):
            raise LlmError("채점 호출 실패")

    monkeypatch.setattr(cli, "ClaudeJudge", _FailingJudge)

    scenario = load_scenarios(ROOT / DEFAULT_SCENARIOS).scenarios[0]
    old_judge = JudgeResult(
        achievement=Achievement.FULL, reason="옛 채점", slot_scores=[], violations=[]
    )
    run_dir = _make_run_dir(
        tmp_path,
        scenario,
        digest=cli.scenario_digest(ROOT / DEFAULT_SCENARIOS),
        judge=old_judge,
    )

    code = cli.main(
        ["judge", str(run_dir), "--scenarios", str(ROOT / DEFAULT_SCENARIOS)]
    )
    assert code == 1

    (line,) = (run_dir / "transcripts.jsonl").read_text(encoding="utf-8").splitlines()
    assert ScenarioResult.model_validate_json(line).judge is None
    summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
    assert summary["전체"]["충족"] == 0


def test_부분_재채점은_다른_배치의_점수를_섞어_집계하지_않는다(monkeypatch, tmp_path):
    """옛 모델 점수 2건 중 1건만 재채점하면, 나머지 1건은 저장은 유지하되
    집계에서 빠지고 표에 '다른 배치'로 드러나야 한다(리뷰 실측 재현)."""

    import json

    from pingpong_eval import cli
    from pingpong_eval.models import (
        Achievement,
        JudgeBatch,
        JudgeResult,
        ScenarioResult,
        StopReason,
        Transcript,
    )

    class _OkJudge:
        model = "새모델"
        base_url = None
        input_tokens = 0
        output_tokens = 0

        def parse(self, prompt, response_model, system=None):
            return response_model(
                achievement=Achievement.PARTIAL,
                reason="새 채점",
                slot_scores=[],
                violations=[],
            )

    monkeypatch.setattr(cli, "ClaudeJudge", _OkJudge)

    digest = cli.scenario_digest(ROOT / DEFAULT_SCENARIOS)
    first, second = load_scenarios(ROOT / DEFAULT_SCENARIOS).scenarios[:2]
    run_dir = tmp_path / "run"
    run_dir.mkdir()
    (run_dir / "meta.json").write_text(
        json.dumps({"시나리오해시": digest}), encoding="utf-8"
    )
    with (run_dir / "transcripts.jsonl").open("w", encoding="utf-8") as handle:
        for scenario in (first, second):
            handle.write(
                ScenarioResult(
                    scenario_id=scenario.id,
                    difficulty=scenario.difficulty,
                    factors=scenario.factors,
                    transcript=Transcript(
                        scenario_id=scenario.id,
                        opening="사진 보내줘",
                        stop_reason=StopReason.READY,
                    ),
                    judge=JudgeResult(
                        achievement=Achievement.FULL,
                        reason="옛 채점",
                        slot_scores=[],
                        violations=[],
                    ),
                    judge_batch=JudgeBatch(model="옛모델", scenario_digest=digest),
                ).model_dump_json()
                + "\n"
            )

    code = cli.main(
        [
            "judge",
            str(run_dir),
            "--scenarios",
            str(ROOT / DEFAULT_SCENARIOS),
            "--only",
            first.id,
        ]
    )
    assert code == 0

    lines = (run_dir / "transcripts.jsonl").read_text(encoding="utf-8").splitlines()
    saved = {r.scenario_id: r for r in map(ScenarioResult.model_validate_json, lines)}
    assert saved[first.id].judge.reason == "새 채점"
    assert saved[first.id].judge_batch.model == "새모델"
    assert saved[second.id].judge.reason == "옛 채점"  # 저장은 지워지지 않는다
    assert saved[second.id].judge_batch.model == "옛모델"

    summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
    assert summary["전체"]["부분"] == 1  # 이번 배치의 새 점수만
    assert summary["전체"]["충족"] == 0  # 옛 배치의 충족이 섞이지 않는다
    assert "다른 배치" in (run_dir / "report.md").read_text(encoding="utf-8")


def test_judge는_시나리오에_없는_id를_건너뛰고_실패로_센다(monkeypatch, tmp_path):
    """드리프트를 알고 진행한 복구 경로가 KeyError로 죽으면 안 된다."""

    from pingpong_eval import cli
    from pingpong_eval.models import ScenarioResult

    class _NeverCalledJudge:
        model = "stub-judge"
        base_url = None
        input_tokens = 0
        output_tokens = 0

        def parse(self, prompt, response_model, system=None):
            raise AssertionError("없는 id는 채점 호출 전에 걸러져야 한다")

    monkeypatch.setattr(cli, "ClaudeJudge", _NeverCalledJudge)

    scenario = load_scenarios(ROOT / DEFAULT_SCENARIOS).scenarios[0]
    run_dir = _make_run_dir(
        tmp_path, scenario, digest=cli.scenario_digest(ROOT / DEFAULT_SCENARIOS)
    )
    ghost = ScenarioResult.model_validate_json(
        (run_dir / "transcripts.jsonl").read_text(encoding="utf-8")
    ).model_copy(update={"scenario_id": "유령"})
    (run_dir / "transcripts.jsonl").write_text(
        ghost.model_dump_json() + "\n", encoding="utf-8"
    )

    code = cli.main(
        ["judge", str(run_dir), "--scenarios", str(ROOT / DEFAULT_SCENARIOS)]
    )
    assert code == 1


def test_judge는_실행_디렉터리가_아니면_안내하고_끝낸다(tmp_path):
    from pingpong_eval import cli

    code = cli.main(
        [
            "judge",
            str(tmp_path / "없는경로"),
            "--scenarios",
            str(ROOT / DEFAULT_SCENARIOS),
        ]
    )
    assert code == 1


def test_judge_필터가_비면_실패로_끝난다(tmp_path):
    """선택 오타가 자동화에서 성공으로 보이거나 기존 보고서를 다시 쓰면 안 된다."""

    from pingpong_eval import cli

    scenario = load_scenarios(ROOT / DEFAULT_SCENARIOS).scenarios[0]
    run_dir = _make_run_dir(
        tmp_path, scenario, digest=cli.scenario_digest(ROOT / DEFAULT_SCENARIOS)
    )

    code = cli.main(
        [
            "judge",
            str(run_dir),
            "--scenarios",
            str(ROOT / DEFAULT_SCENARIOS),
            "--only",
            "없는id",
            "--dry-run",
        ]
    )
    assert code == 1
    assert not (run_dir / "report.md").exists()


def test_보고서_표는_헤더와_값의_열이_맞는다(scenarios):
    """헤더가 6열인데 값이 7개면 표가 밀려 엉뚱한 열 아래 값이 보인다(실측)."""

    from pingpong_eval.metrics import summarize, transcript_metrics
    from pingpong_eval.models import ScenarioResult
    from pingpong_eval.report import render

    scenario = scenarios[0]
    transcript = _transcript(scenario)
    results = [
        ScenarioResult(
            scenario_id=scenario.id,
            difficulty=scenario.difficulty,
            factors=scenario.factors,
            transcript=transcript,
            metrics=transcript_metrics(transcript),
        )
    ]
    text = render(summarize(results), results, meta={})

    assert "평균 질문수" in text and "확정실패" in text
    assert "평균 라운드" not in text and "상한도달" not in text

    width = None
    for line in text.splitlines():
        if line.startswith("|"):
            if width is None:
                width = line.count("|")
            assert line.count("|") == width, f"열 수가 어긋난 행: {line}"
        else:
            width = None  # 표가 끝났다 — 다음 표는 새로 잰다


def test_시나리오_해시는_내용이_바뀌면_달라진다(tmp_path):
    """run과 judge 사이에 정답이 바뀌면 비교할 수 없는 수치가 된다."""

    from pingpong_eval.cli import scenario_digest

    path = tmp_path / "s.json"
    path.write_text('{"version": 1, "시나리오": []}', encoding="utf-8")
    before = scenario_digest(path)
    path.write_text('{"version": 1, "시나리오": [] }', encoding="utf-8")
    assert scenario_digest(path) != before
