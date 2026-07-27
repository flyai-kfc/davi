import logging

from app.main import app
from fastapi.testclient import TestClient

client = TestClient(app)


def test_health_ok():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_next_step_stub_returns_action():
    payload = {
        "goal": "카카오톡 열기",
        "client_version": "0.1.0",
        "screen": {
            "package_name": "com.sec.android.app.launcher",
            "current_page": 2,
            "total_pages": 4,
            "elements": [
                {"node_id": "n1", "label": "카카오톡", "clickable": True},
                {"node_id": "n2", "label": "시계", "clickable": False},
            ],
        },
    }
    r = client.post("/v1/next-step", json=payload)
    assert r.status_code == 200
    body = r.json()
    assert body["action"]["action_type"] == "tap"
    assert body["action"]["target_node_id"] == "n1"
    # 근거 필드가 비어 있지 않아야 한다(HANDOFF: 성의 없는/빈 근거 방지의 출발점).
    assert body["action"]["evidence"]
    assert body["action"]["expected_result"]
    # scroll이 아니면 방향은 생략된다.
    assert body["action"]["direction"] is None


def test_next_step_stuck_returns_classified_error():
    """막히면 kind로 분류된 에러가 오고, 앱이 그걸 보고 안내를 분기한다(C8)."""
    r = client.post("/v1/next-step", json={"goal": "x", "screen": {"elements": []}})
    assert r.status_code == 502
    body = r.json()
    assert body["kind"] == "SERVER"
    assert body["request_id"]


def test_next_step_logs_metadata_without_screen_labels(caplog):
    payload = {
        "goal": "카카오톡\n열기",
        "client_version": "0.1.0",
        "screen": {
            "package_name": "com.sec.android.app.launcher",
            "elements": [
                {
                    "node_id": "n1",
                    "label": "개인정보 hong@example.com",
                    "clickable": True,
                },
                {"node_id": "n2", "label": "시계", "clickable": False},
            ],
        },
    }

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.requests"):
        r = client.post("/v1/next-step", json=payload)

    assert r.status_code == 200
    messages = [
        record.getMessage()
        for record in caplog.records
        if record.name == "uvicorn.error.davi.requests"
    ]
    assert len(messages) == 1
    message = messages[0]
    assert "package_name=com.sec.android.app.launcher" in message
    assert "element_count=2" in message
    assert "goal=카카오톡 열기" in message
    assert "client_version=0.1.0" in message
    assert "status_code=200" in message
    assert "duration_ms=" in message
    assert "개인정보" not in message
    assert "hong@example.com" not in message
    assert "시계" not in message
