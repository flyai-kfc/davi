"""#82 추론 전송 수명주기: deadline·호출 상한·in-flight dedupe·성공 replay."""

from __future__ import annotations

import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from threading import Event, Lock

import anyio
import app.main as main_module
import pytest
from app.errors import LlmError
from app.inference_lifecycle import (
    INFERENCE_MAX_WORKERS,
    ExecutionRole,
    InferenceCoordinator,
    LogicalRequestConflict,
    inference_budget,
    provider_call_timeout_seconds,
    request_fingerprint,
)
from app.main import app
from app.observability import ATTEMPT_HEADER, LOGICAL_REQUEST_ID_HEADER, REQUEST_ID_HEADER
from app.schemas import ErrorKind, NextStepRequest
from fastapi.testclient import TestClient
from pydantic import BaseModel


class _Request(BaseModel):
    value: str


def _execute(
    coordinator: InferenceCoordinator,
    logical_id: str,
    request: _Request,
    work,
):
    return coordinator.execute(
        scope="test-user",
        endpoint="/v1/test",
        logical_request_id=logical_id,
        request=request,
        work=work,
    )


def test_concurrent_same_request_runs_once_and_success_is_replayed():
    coordinator = InferenceCoordinator(deadline_seconds=1, max_workers=2)
    logical_id = str(uuid.uuid4())
    started = Event()
    release = Event()
    lock = Lock()
    calls = 0

    def work():
        nonlocal calls
        with lock:
            calls += 1
        started.set()
        assert release.wait(timeout=1)
        return "result"

    with ThreadPoolExecutor(max_workers=2) as callers:
        owner = callers.submit(_execute, coordinator, logical_id, _Request(value="same"), work)
        assert started.wait(timeout=1)
        joined = callers.submit(_execute, coordinator, logical_id, _Request(value="same"), work)
        time.sleep(0.02)
        release.set()
        first = owner.result(timeout=1)
        second = joined.result(timeout=1)

    replay = _execute(coordinator, logical_id, _Request(value="same"), work)

    assert calls == 1
    assert {first.role, second.role} == {ExecutionRole.OWNER, ExecutionRole.IN_FLIGHT_JOIN}
    assert first.unwrap() == second.unwrap() == replay.unwrap() == "result"
    assert replay.role is ExecutionRole.RESULT_REPLAY


def test_same_id_with_different_body_fails_closed():
    coordinator = InferenceCoordinator(deadline_seconds=1)
    logical_id = str(uuid.uuid4())
    assert _execute(coordinator, logical_id, _Request(value="first"), lambda: "ok").unwrap() == "ok"

    with pytest.raises(LogicalRequestConflict):
        _execute(coordinator, logical_id, _Request(value="different"), lambda: "leak")


def test_failure_is_not_cached_for_retryable_same_id():
    coordinator = InferenceCoordinator(deadline_seconds=1)
    logical_id = str(uuid.uuid4())
    calls = 0

    def work():
        nonlocal calls
        calls += 1
        if calls == 1:
            raise LlmError(ErrorKind.NETWORK, "temporary")
        return "recovered"

    first = _execute(coordinator, logical_id, _Request(value="same"), work)
    with pytest.raises(LlmError):
        first.unwrap()
    second = _execute(coordinator, logical_id, _Request(value="same"), work)

    assert calls == 2
    assert second.role is ExecutionRole.OWNER
    assert second.unwrap() == "recovered"


def test_work_past_hard_deadline_is_network_failure_and_late_result_is_discarded():
    coordinator = InferenceCoordinator(deadline_seconds=0.02, max_workers=2)
    logical_id = str(uuid.uuid4())

    started_at = time.monotonic()
    late = _execute(
        coordinator,
        logical_id,
        _Request(value="same"),
        lambda: time.sleep(0.06) or "too-late",
    )
    assert time.monotonic() - started_at < 0.055
    with pytest.raises(LlmError) as raised:
        late.unwrap()
    assert raised.value.kind is ErrorKind.NETWORK

    # 강제 종료할 수 없는 Python worker가 끝날 시간을 준다. deadline 뒤 성공은 cache되지 않는다.
    time.sleep(0.07)
    recovered = _execute(coordinator, logical_id, _Request(value="same"), lambda: "fresh")
    assert recovered.role is ExecutionRole.OWNER
    assert recovered.unwrap() == "fresh"


def test_success_replay_expires_after_ttl():
    now = [0.0]
    coordinator = InferenceCoordinator(
        deadline_seconds=1,
        replay_ttl_seconds=2,
        clock=lambda: now[0],
    )
    logical_id = str(uuid.uuid4())
    calls = 0

    def work():
        nonlocal calls
        calls += 1
        return calls

    assert _execute(coordinator, logical_id, _Request(value="same"), work).unwrap() == 1
    time.sleep(0.01)  # 완료 callback이 completed_at을 기록하게 한다.
    now[0] = 1.9
    assert (
        _execute(coordinator, logical_id, _Request(value="same"), work).role
        is ExecutionRole.RESULT_REPLAY
    )
    now[0] = 2.0
    expired = _execute(coordinator, logical_id, _Request(value="same"), work)
    assert expired.role is ExecutionRole.OWNER
    assert expired.unwrap() == 2


def test_oldest_success_replay_is_evicted_at_capacity():
    coordinator = InferenceCoordinator(
        deadline_seconds=1,
        max_success_replays=1,
    )
    first_id = str(uuid.uuid4())
    second_id = str(uuid.uuid4())

    assert _execute(coordinator, first_id, _Request(value="first"), lambda: "first").unwrap()
    time.sleep(0.01)
    assert _execute(coordinator, second_id, _Request(value="second"), lambda: "second").unwrap()
    time.sleep(0.01)

    rerun = _execute(coordinator, first_id, _Request(value="first"), lambda: "fresh")
    assert rerun.role is ExecutionRole.OWNER
    assert rerun.unwrap() == "fresh"


def test_executor_queue_time_counts_toward_hard_deadline():
    coordinator = InferenceCoordinator(deadline_seconds=0.03, max_workers=1)
    queued_calls = 0

    first = _execute(
        coordinator,
        str(uuid.uuid4()),
        _Request(value="blocking"),
        lambda: time.sleep(0.08) or "late",
    )
    with pytest.raises(LlmError):
        first.unwrap()

    def queued_work():
        nonlocal queued_calls
        queued_calls += 1
        return "must-not-run"

    second = _execute(
        coordinator,
        str(uuid.uuid4()),
        _Request(value="queued"),
        queued_work,
    )
    with pytest.raises(LlmError):
        second.unwrap()
    time.sleep(0.06)

    assert queued_calls == 0


def test_default_worker_capacity_matches_fastapi_sync_capacity():
    coordinator = InferenceCoordinator()

    async def default_sync_capacity() -> float:
        return anyio.to_thread.current_default_thread_limiter().total_tokens

    assert anyio.run(default_sync_capacity) == INFERENCE_MAX_WORKERS
    assert coordinator._executor._max_workers == INFERENCE_MAX_WORKERS


def test_provider_call_count_and_deadline_are_request_scoped():
    with inference_budget(deadline_seconds=1):
        for attempt in range(1, 4):
            assert 0 < provider_call_timeout_seconds("next_step", attempt) <= 1
        with pytest.raises(LlmError) as raised:
            provider_call_timeout_seconds("next_step", 3)
        assert raised.value.kind is ErrorKind.SERVER

    with inference_budget(deadline_seconds=0):
        with pytest.raises(LlmError) as raised:
            provider_call_timeout_seconds("intent_dialogue", 1)
        assert raised.value.kind is ErrorKind.NETWORK


def _payload(
    goal: str = "open app",
    *,
    label: str = "masked",
    node_id: str = "n1",
    bounds: list[int] | None = None,
    window_title: str | None = None,
    steps: list[dict] | None = None,
) -> dict:
    return {
        "goal": goal,
        "client_version": "0.1.0",
        "steps": steps or [],
        "screen": {
            "package_name": "com.example.launcher",
            "window_title": window_title,
            "elements": [
                {
                    "node_id": node_id,
                    "label": label,
                    "clickable": True,
                    "bounds": bounds,
                }
            ],
        },
    }


def test_next_step_fingerprint_matches_android_structure_axis():
    first = NextStepRequest.model_validate(
        _payload(label="3:45", node_id="n1", bounds=[0, 0, 100, 100], window_title="받은편지함 3")
    )
    volatile_retry = NextStepRequest.model_validate(
        _payload(label="3:46", node_id="n9", bounds=[1, 2, 101, 102], window_title="받은편지함 4")
    )
    structural_change_payload = _payload()
    structural_change_payload["screen"]["elements"][0]["checked"] = True
    structural_change = NextStepRequest.model_validate(structural_change_payload)

    assert request_fingerprint(first) == request_fingerprint(volatile_retry)
    assert request_fingerprint(first) != request_fingerprint(structural_change)


def test_next_step_fingerprint_ignores_mutable_last_step_observations():
    immutable_step = {
        "instruction": "카카오톡을 누르세요",
        "action_type": "tap",
        "target_label": "카카오톡",
        "target_node_id": "n1",
        "screen_hint": "홈 화면",
        "structure_signature": "a" * 64,
    }
    first = NextStepRequest.model_validate(
        _payload(
            steps=[
                {
                    **immutable_step,
                    "screen_changed": None,
                    "performed": None,
                    "content_signature": None,
                }
            ]
        )
    )
    observed_retry = NextStepRequest.model_validate(
        _payload(
            steps=[
                {
                    **immutable_step,
                    "screen_changed": False,
                    "performed": True,
                    "content_signature": "b" * 64,
                }
            ]
        )
    )
    changed_instruction = NextStepRequest.model_validate(
        _payload(steps=[{**immutable_step, "instruction": "다른 앱을 누르세요"}])
    )

    assert request_fingerprint(first) == request_fingerprint(observed_retry)
    assert request_fingerprint(first) != request_fingerprint(changed_instruction)


def _headers(logical_id: str, attempt: int) -> dict[str, str]:
    return {
        LOGICAL_REQUEST_ID_HEADER: logical_id,
        ATTEMPT_HEADER: str(attempt),
    }


def test_http_retransmission_joins_one_logical_work_and_echoes_each_attempt(monkeypatch):
    original = main_module.next_step
    started = Event()
    release = Event()
    lock = Lock()
    calls = 0

    def tracked(request, llm_client):
        nonlocal calls
        with lock:
            calls += 1
        started.set()
        assert release.wait(timeout=1)
        return original(request, llm_client)

    monkeypatch.setattr(main_module, "next_step", tracked)
    logical_id = str(uuid.uuid4())

    def send(attempt: int):
        payload = (
            _payload()
            if attempt != 2
            else _payload(
                label="updated label",
                node_id="n9",
                bounds=[1, 2, 101, 102],
                window_title="updated title",
            )
        )
        with TestClient(app, client=("127.0.0.1", 50000 + attempt)) as client:
            return client.post(
                "/v1/next-step",
                json=payload,
                headers=_headers(logical_id, attempt),
            )

    with ThreadPoolExecutor(max_workers=2) as callers:
        first_future = callers.submit(send, 1)
        assert started.wait(timeout=1)
        second_future = callers.submit(send, 2)
        time.sleep(0.02)
        release.set()
        first = first_future.result(timeout=2)
        second = second_future.result(timeout=2)

    replay = send(3)

    assert calls == 1
    assert [first.status_code, second.status_code, replay.status_code] == [200, 200, 200]
    assert first.headers[ATTEMPT_HEADER] == "1"
    assert second.headers[ATTEMPT_HEADER] == "2"
    assert replay.headers[ATTEMPT_HEADER] == "3"
    assert (
        len(
            {
                first.headers[REQUEST_ID_HEADER],
                second.headers[REQUEST_ID_HEADER],
                replay.headers[REQUEST_ID_HEADER],
            }
        )
        == 3
    )


def test_http_same_id_different_body_is_409_and_retryable_failure_runs_again(monkeypatch):
    original = main_module.next_step
    calls = 0

    def flaky(request, llm_client):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise LlmError(ErrorKind.NETWORK, "temporary")
        return original(request, llm_client)

    monkeypatch.setattr(main_module, "next_step", flaky)
    logical_id = str(uuid.uuid4())
    with TestClient(app, client=("127.0.0.1", 50000)) as client:
        first = client.post(
            "/v1/next-step",
            json=_payload(),
            headers=_headers(logical_id, 1),
        )
        recovered = client.post(
            "/v1/next-step",
            json=_payload(),
            headers=_headers(logical_id, 2),
        )
        conflict = client.post(
            "/v1/next-step",
            json=_payload("different goal"),
            headers=_headers(logical_id, 3),
        )

    assert first.status_code == 502
    assert recovered.status_code == 200
    assert calls == 2
    assert conflict.status_code == 409
    assert conflict.json()["kind"] == "BAD_REQUEST"
    assert "different goal" not in conflict.text
