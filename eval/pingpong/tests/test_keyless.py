"""API 키 없이 도는 층. 시나리오 계약·유출 검사·루프 배선·어댑터 파싱을 지킨다."""

from __future__ import annotations

from pathlib import Path

import pytest

from pingpong_eval.cli import DEFAULT_SCENARIOS, load_scenarios
from pingpong_eval.models import (
    Exposure,
    PingpongStatus,
    PingpongTurn,
    Slot,
    UserReply,
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


def test_어댑터는_옛_형식을_선택지_없는_경우로_읽는다():
    turn = _parse({"status": "needs_clarification", "message": "어디로 보낼까요?"}, 12)
    assert turn.options == []
    assert turn.has_other is True
    assert turn.latency_ms == 12


def test_어댑터는_선택지_형식도_읽는다():
    turn = _parse(
        {
            "status": "needs_clarification",
            "message": "어디로 보낼까요?",
            "options": ["카카오톡", "문자"],
            "has_other": True,
        },
        3,
    )
    assert turn.options == ["카카오톡", "문자"]
    assert turn.has_other is True


def test_모르는_status는_실패한다():
    with pytest.raises(PingpongError):
        _parse({"status": "누구세요", "message": "x"}, 0)


class _FakeAdapter:
    """두 번 되묻고 세 번째에 목표를 확정하는 핑퐁."""

    def __init__(self):
        self.sent = []

    def send(self, utterance, history):
        self.sent.append((utterance, list(history)))
        if len(self.sent) < 3:
            return PingpongTurn(
                status=PingpongStatus.NEEDS_CLARIFICATION,
                question="누구한테 보낼까요?",
                options=["딸", "아들"],
                has_other=True,
            )
        return PingpongTurn(
            status=PingpongStatus.READY, question="이거 맞으세요?", goal="확정된 목표"
        )


class _FakeUser:
    def opening(self, scenario, avoid=None):
        return "사진 좀 보내려고", scenario.first_slots[:1]

    def reply(self, scenario, turn, said):
        if not turn.options:
            return UserReply(chosen_option=None, text="딸이요", revealed_slots=["대상"])
        return UserReply(chosen_option=0, text=turn.options[0], revealed_slots=["대상"])


def test_루프는_ready에서_멈추고_이력을_쌓는다(scenarios):
    result = run_scenario(scenarios[0], _FakeUser(), _FakeAdapter())
    assert result.transcript.stop_reason.value == "ready"
    assert result.transcript.final_goal == "확정된 목표"
    assert result.metrics["라운드수"] == 2  # 답한 라운드만 센다
    assert result.metrics["선택지_적중률"] == 1.0


def test_상한에_걸리면_limit로_끝난다(scenarios):
    class _NeverReady(_FakeAdapter):
        def send(self, utterance, history):
            self.sent.append((utterance, list(history)))
            return PingpongTurn(
                status=PingpongStatus.NEEDS_CLARIFICATION, question="뭘 하실래요?"
            )

    result = run_scenario(scenarios[0], _FakeUser(), _NeverReady(), max_rounds=3)
    assert result.transcript.stop_reason.value == "limit"
    assert result.metrics["라운드수"] == 3
    assert result.metrics["기타_사용"] == 0  # 선택지가 없으면 '기타'로 세지 않는다


def test_429는_기다렸다_다시_보낸다(monkeypatch):
    """공유 서버 요청 상한은 실패가 아니라 정상 경로다."""

    import httpx

    from pingpong_eval.sut import SlotFillHttpAdapter

    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        assert (
            request.headers["X-Davi-Dev-Token"]
            == "dev-token-abcdefghijklmnopqrstuvwxyz123456"
        )
        if calls["n"] == 1:
            return httpx.Response(
                429,
                json={"kind": "QUOTA", "retry_after_seconds": 1},
                headers={"Retry-After": "1"},
            )
        return httpx.Response(
            200, json={"status": "ready", "message": "확인", "goal": "목표"}
        )

    monkeypatch.setattr("time.sleep", lambda _seconds: None)
    adapter = SlotFillHttpAdapter(
        "http://azure.example",
        session_id="s",
        dev_token="dev-token-abcdefghijklmnopqrstuvwxyz123456",
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )
    turn = adapter.send("사진 보내줘", [])
    assert calls["n"] == 2
    assert turn.goal == "목표"


def test_401은_토큰_안내로_끝난다():
    import httpx

    from pingpong_eval.sut import SlotFillHttpAdapter

    adapter = SlotFillHttpAdapter(
        "http://azure.example",
        session_id="s",
        client=httpx.Client(
            transport=httpx.MockTransport(
                lambda _r: httpx.Response(401, json={"kind": "AUTH"})
            )
        ),
    )
    with pytest.raises(PingpongError, match="PINGPONG_DEV_TOKEN"):
        adapter.send("사진 보내줘", [])


def test_행위는_첫_발화에서_숨기지_않는다(scenarios):
    """조각이 둘뿐인 시나리오가 한 단어 발화로 무너지는 것을 막는다(h07 실측)."""

    from pingpong_eval.user_sim import UserSimulator

    user = UserSimulator.__new__(UserSimulator)
    user._rng = __import__("random").Random(0)
    for scenario in scenarios:
        revealed = user._pick_revealed(scenario)
        assert revealed, f"{scenario.id}: 말할 것이 하나도 없다"
        keys = {slot.key for slot in revealed}
        if any(s.key == "행위" for s in scenario.first_slots):
            assert "행위" in keys, f"{scenario.id}: 행위가 숨겨졌다"


def test_사용자_LLM은_429를_재시도한다(monkeypatch):
    """분당 한도는 정상 경로다 — 한 번의 429로 실행 전체가 죽으면 안 된다(실측)."""

    import httpx

    from pingpong_eval.llm import GeminiClient

    calls = {"n": 0}
    quota = {
        "error": {
            "code": 429,
            "message": "quota",
            "details": [
                {
                    "@type": "type.googleapis.com/google.rpc.RetryInfo",
                    "retryDelay": "3s",
                }
            ],
        }
    }

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] < 3:
            return httpx.Response(429, json=quota)
        return httpx.Response(
            200,
            json={
                "candidates": [
                    {"content": {"parts": [{"text": '{"utterance":"안녕"}'}]}}
                ]
            },
        )

    slept = []
    monkeypatch.setattr("time.sleep", slept.append)
    client = GeminiClient(
        api_key="k", client=httpx.Client(transport=httpx.MockTransport(handler))
    )
    body = client.generate_json("프롬프트", {"type": "object"})
    assert body["utterance"] == "안녕"
    assert calls["n"] == 3
    assert slept == [4.0, 4.0]  # RetryInfo의 3s + 1


def test_불필요_질문_축이_있다():
    """과잉 명세가 30건 기준선의 실패 원인이었다 — 증상(반복)과 따로 세야 한다."""

    from pingpong_eval.models import ViolationAxis

    assert ViolationAxis("불필요_질문") is ViolationAxis.UNNECESSARY_QUESTION


def test_채점_프롬프트가_불필요_질문을_정의한다(scenarios):
    from pingpong_eval.judge import build_prompt
    from pingpong_eval.models import StopReason, Transcript

    prompt = build_prompt(
        scenarios[0],
        Transcript(
            scenario_id="e01", opening="사진 보내줘", stop_reason=StopReason.LIMIT
        ),
    )
    assert "불필요_질문" in prompt
    assert "정답 목표에 없는 조각" in prompt
