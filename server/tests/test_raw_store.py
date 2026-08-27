"""픽스처 보존 채널(#41): 화이트리스트 기기의 왕복만, 운영 로그와 무관하게 저장된다."""

from __future__ import annotations

import json
import os
import stat

import pytest
from app import main as main_module
from app.config import settings
from app.errors import LlmError
from app.main import app
from app.raw_store import raw_store_dir
from app.schemas import ErrorKind
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


def test_llm_failure_exchange_is_stored(monkeypatch, tmp_path):
    """엔드포인트에 도달한 LLM 실패도 봉투로 남는다 — 실패 화면이 주 수집 대상이다."""
    monkeypatch.setattr(settings, "davi_dogfood_device_ids", WHITELISTED)
    monkeypatch.setattr(settings, "davi_dogfood_raw_dir", str(tmp_path))

    def _fail(req, llm_client):
        raise LlmError(ErrorKind.PARSE, "probe failure")

    monkeypatch.setattr(main_module, "next_step", _fail)

    response = client.post("/v1/next-step", json=_payload(WHITELISTED))

    assert response.status_code == 502
    files = list(tmp_path.glob("*.json"))
    assert len(files) == 1
    envelope = json.loads(files[0].read_text(encoding="utf-8"))
    assert envelope["status"] == 502
    assert envelope["response"]["kind"] == "PARSE"
    assert envelope["request"]["goal"] == "카메라 열기"


@pytest.mark.skipif(os.name == "nt", reason="Windows에는 POSIX 권한 비트가 없다")
def test_store_modes_are_owner_only(monkeypatch, tmp_path):
    """영구 보존물의 접근 범위는 각 머신의 소유자다(SPEC §3) — 디렉터리 0700, 파일 0600."""
    raw_dir = tmp_path / "raw"
    monkeypatch.setattr(settings, "davi_dogfood_device_ids", WHITELISTED)
    monkeypatch.setattr(settings, "davi_dogfood_raw_dir", str(raw_dir))

    assert client.post("/v1/next-step", json=_payload(WHITELISTED)).status_code == 200

    assert stat.S_IMODE(raw_dir.stat().st_mode) == 0o700
    stored = next(raw_dir.glob("*.json"))
    assert stat.S_IMODE(stored.stat().st_mode) == 0o600


@pytest.mark.skipif(os.name == "nt", reason="Windows에는 POSIX 권한 비트가 없다")
def test_existing_wide_directory_is_tightened(monkeypatch, tmp_path):
    raw_dir = tmp_path / "raw"
    raw_dir.mkdir()
    os.chmod(raw_dir, 0o755)
    monkeypatch.setattr(settings, "davi_dogfood_device_ids", WHITELISTED)
    monkeypatch.setattr(settings, "davi_dogfood_raw_dir", str(raw_dir))

    assert client.post("/v1/next-step", json=_payload(WHITELISTED)).status_code == 200

    assert stat.S_IMODE(raw_dir.stat().st_mode) == 0o700


def test_out_of_pattern_proper_nouns_stay_in_the_envelope(monkeypatch, tmp_path):
    """봉투는 label·창 제목을 지우지 않는다 — 알고 그대로 두기로 한 결정이다(#375).

    1차 마스킹이 패턴만 지우므로 사람 이름·기기명은 그대로 저장된다(#312 실측이 그 값이다).
    지우는 쪽으로 뒤집으려면 `SPEC.md` §2 「패턴 밖 고유명」과 §3이 먼저 바뀌어야 한다.
    """
    monkeypatch.setattr(settings, "davi_dogfood_device_ids", WHITELISTED)
    monkeypatch.setattr(settings, "davi_dogfood_raw_dir", str(tmp_path))
    body = _payload(WHITELISTED)
    body["screen"]["window_title"] = "김민준"
    body["screen"]["elements"][0]["label"] = "김민준의 Galaxy Buds"

    assert client.post("/v1/next-step", json=body).status_code == 200

    envelope = json.loads(next(tmp_path.glob("*.json")).read_text(encoding="utf-8"))
    stored_screen = envelope["request"]["screen"]
    assert stored_screen["elements"][0]["label"] == "김민준의 Galaxy Buds"
    assert stored_screen["window_title"] == "김민준"


def test_app_excluded_password_and_otp_values_are_absent_from_the_shared_envelope(
    monkeypatch, tmp_path
):
    """raw 채널은 앱 1차 경계 뒤 요청을 저장한다 — 민감 editable에는 이름만 와야 한다."""
    monkeypatch.setattr(settings, "davi_dogfood_device_ids", WHITELISTED)
    monkeypatch.setattr(settings, "davi_dogfood_raw_dir", str(tmp_path))
    body = _payload(WHITELISTED)
    body["screen"]["elements"] = [
        {
            "node_id": "n1",
            "label": "비밀번호 입력",
            "role": "EditText",
            "clickable": True,
            "editable": True,
        },
        {
            "node_id": "n2",
            "label": "인증 코드 입력",
            "role": "EditText",
            "editable": True,
        },
    ]

    assert client.post("/v1/next-step", json=body).status_code == 200

    stored = json.loads(next(tmp_path.glob("*.json")).read_text(encoding="utf-8"))
    assert [item["label"] for item in stored["request"]["screen"]["elements"]] == [
        "비밀번호 입력",
        "인증 코드 입력",
    ]


def test_relative_raw_dir_resolves_under_server_root():
    resolved = raw_store_dir(".dogfood-raw")
    assert resolved.name == ".dogfood-raw"
    assert (resolved.parent / "app" / "raw_store.py").exists()
