"""API 키 없이 도는 층. 시나리오 계약·유출 검사·루프 배선·어댑터 파싱을 지킨다."""

from __future__ import annotations

from pathlib import Path

import pytest
from pingpong_eval.cli import DEFAULT_SCENARIOS, load_scenarios
from pingpong_eval.models import (
    Exposure,
    PingpongStatus,
    PingpongTurn,
    Question,
    Slot,
)
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


def test_judge는_시나리오_드리프트를_막고_옵션으로만_연다(monkeypatch, tmp_path):
    """안내 문구가 가리키는 --allow-scenario-drift 가 실제로 있어야 복구 경로가 된다."""

    import json

    from pingpong_eval import cli
    from pingpong_eval.models import ScenarioResult, StopReason, Transcript

    scenario = load_scenarios(ROOT / DEFAULT_SCENARIOS).scenarios[0]
    run_dir = tmp_path / "run"
    run_dir.mkdir()
    (run_dir / "meta.json").write_text(
        json.dumps({"시나리오해시": "실행당시와다름"}), encoding="utf-8"
    )
    result = ScenarioResult(
        scenario_id=scenario.id,
        difficulty=scenario.difficulty,
        factors=scenario.factors,
        transcript=Transcript(
            scenario_id=scenario.id, opening="사진 보내줘", stop_reason=StopReason.READY
        ),
    )
    (run_dir / "transcripts.jsonl").write_text(
        result.model_dump_json() + "\n", encoding="utf-8"
    )

    base = ["judge", str(run_dir), "--scenarios", str(ROOT / DEFAULT_SCENARIOS)]
    assert cli.main([*base, "--dry-run"]) == 1
    assert cli.main([*base, "--dry-run", "--allow-scenario-drift"]) == 0


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
