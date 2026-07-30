"""픽스처 보존 채널(#41): 화이트리스트 기기의 왕복만, 운영 로그와 무관하게 저장된다."""

from __future__ import annotations

import json

from app.config import settings
from app.main import app
from app.raw_store import raw_store_dir
from fastapi.testclient import TestClient

client = TestClient(app, client=("127.0.0.1", 50000))

WHITELISTED = "ba7816bf8f01cfea"


def _payload(device_id: str | None = None) -> dict:
    body = {
        "goal": "카메라 열기",
        "screen": {
            "package_name": "com.sec.android.app.launcher",
            "elements": [
                {"node_id": "n1", "label": "카메라", "role": "Button", "clickable": True},
            ],
        },
        "client_version": "0.1.0",
    }
    if device_id is not None:
        body["device_id"] = device_id
    return body


def test_whitelisted_device_exchange_is_stored(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "davi_dogfood_device_ids", WHITELISTED)
    monkeypatch.setattr(settings, "davi_dogfood_raw_dir", str(tmp_path))

    response = client.post("/v1/next-step", json=_payload(WHITELISTED))

    assert response.status_code == 200
    files = list(tmp_path.glob("*.json"))
    assert len(files) == 1
    envelope = json.loads(files[0].read_text(encoding="utf-8"))
    assert envelope["status"] == 200
    assert envelope["client_version"] == "0.1.0"
    assert envelope["request"]["goal"] == "카메라 열기"
    assert envelope["request"]["device_id"] == WHITELISTED
    assert envelope["response"]["action"]["action_type"]
    assert envelope["request_id"]
    assert envelope["request_id"] in files[0].name


def test_non_whitelisted_or_missing_device_is_not_stored(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "davi_dogfood_device_ids", WHITELISTED)
    monkeypatch.setattr(settings, "davi_dogfood_raw_dir", str(tmp_path))

    assert client.post("/v1/next-step", json=_payload("0000000000000000")).status_code == 200
    assert client.post("/v1/next-step", json=_payload()).status_code == 200

    assert list(tmp_path.iterdir()) == []


def test_empty_whitelist_stores_nothing(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "davi_dogfood_raw_dir", str(tmp_path))

    assert client.post("/v1/next-step", json=_payload(WHITELISTED)).status_code == 200

    assert list(tmp_path.iterdir()) == []


def test_store_failure_does_not_break_the_request(monkeypatch, tmp_path):
    """저장은 best-effort다 — 디렉터리를 만들 수 없어도 안내 응답은 그대로 나간다."""
    blocker = tmp_path / "not-a-dir"
    blocker.write_text("x", encoding="utf-8")
    monkeypatch.setattr(settings, "davi_dogfood_device_ids", WHITELISTED)
    monkeypatch.setattr(settings, "davi_dogfood_raw_dir", str(blocker))

    response = client.post("/v1/next-step", json=_payload(WHITELISTED))

    assert response.status_code == 200


def test_relative_raw_dir_resolves_under_server_root():
    resolved = raw_store_dir(".dogfood-raw")
    assert resolved.name == ".dogfood-raw"
    assert (resolved.parent / "app" / "raw_store.py").exists()
