"""POST /v1/intent/app-resolution의 계약·비식별 관측."""

from __future__ import annotations

import json
import logging
import uuid

import app.main as main_module
from app.main import app
from app.schemas import AppCandidatePlan
from fastapi.testclient import TestClient

client = TestClient(app, client=("127.0.0.1", 50000))
PATH = "/v1/intent/app-resolution"


def _headers() -> dict[str, str]:
    return {
        "X-Davi-Logical-Request-ID": str(uuid.uuid4()),
        "X-Davi-Attempt": "1",
    }


def test_keyless_empty_candidates_need_app_name_without_feedback_request():
    initial = client.post(PATH, json={"goal": "카메라를 열고 싶어"}, headers=_headers())
    assert initial.status_code == 200
    assert initial.json()["status"] == "needs_app_name"
    assert initial.json().get("plan") is None


def test_invalid_feedback_returns_safe_bad_request():
    response = client.post(
        PATH,
        json={
            "goal": "private-goal-canary",
            "feedback": {
                "plan": {
                    "capability": "camera",
                    "candidates": [
                        {
                            "candidate_id": "candidate-1",
                            "canonical_name": "카메라",
                            "package_names": [],
                            "label_aliases": [],
                            "confidence": 1.0,
                        }
                    ],
                },
                "candidate_results": [],
            },
        },
        headers=_headers(),
    )
    assert response.status_code == 422
    assert response.json()["kind"] == "BAD_REQUEST"
    assert "private-goal-canary" not in response.text


def test_operational_logs_only_include_candidate_count_and_status(monkeypatch, caplog):
    private_values = ["private-goal-canary", "private-app-canary", "private.package.canary"]
    plan = AppCandidatePlan.model_validate(
        {
            "capability": "camera",
            "candidates": [
                {
                    "candidate_id": "candidate-1",
                    "canonical_name": private_values[1],
                    "package_names": [private_values[2]],
                    "label_aliases": [],
                    "confidence": 1.0,
                }
            ],
        }
    )
    monkeypatch.setattr(
        main_module,
        "resolve_app_request",
        lambda _: main_module.AppResolutionState(
            status="candidates",
            message="후보 확인",
            plan=plan,
        ),
    )

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.requests"):
        response = client.post(PATH, json={"goal": private_values[0]}, headers=_headers())

    assert response.status_code == 200
    events = [json.loads(record.getMessage()) for record in caplog.records]
    serialized = json.dumps(events, ensure_ascii=False)
    assert all(value not in serialized for value in private_values)
    assert events[-1]["candidate_count"] == 1
    assert events[-1]["resolution_status"] == "candidates"
