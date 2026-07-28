from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

import harness
from harness import (
    FIXTURES,
    FixtureDocument,
    FixtureError,
    _validation_locations,
    find_sensitive_paths,
    load_fixture,
    load_fixtures,
    run_suite,
)


def _fixture_payload() -> dict:
    return {
        "id": "safe-device-screen",
        "description": "익명화된 실기기 화면",
        "source": {
            "kind": "anonymized_real_device",
            "captured_at": "2026-07-27",
            "issue": 30,
            "device_model": "test-device",
            "os_version": "test-os",
        },
        "input": {
            "goal": "카메라 열기",
            "screen": {
                "package_name": "com.example.launcher",
                "elements": [
                    {
                        "node_id": "n1",
                        "label": "일반 앱",
                        "clickable": True,
                    }
                ],
            },
        },
        "expected": {
            "stub_outcome": "success",
            "action_type": "tap",
        },
    }


def _write_fixture(directory: Path, name: str, payload: dict) -> Path:
    path = directory / name
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return path


def test_loader_reads_all_json_and_allows_omitted_optional_fields(tmp_path: Path):
    _write_fixture(tmp_path, "b.json", _fixture_payload())
    second = _fixture_payload()
    second["id"] = "second-screen"
    _write_fixture(tmp_path, "a.json", second)

    fixtures = load_fixtures(tmp_path)

    assert [item.path.name for item in fixtures] == ["a.json", "b.json"]
    assert fixtures[0].request.device_id is None
    assert fixtures[0].request.client_version is None
    assert fixtures[0].request.screen.current_page is None


def test_loader_rejects_unknown_or_coerced_input_fields(tmp_path: Path):
    payload = _fixture_payload()
    payload["input"]["screen"]["unknown"] = "lost by schema"
    path = _write_fixture(tmp_path, "unknown.json", payload)

    with pytest.raises(FixtureError, match="보존되지 않음"):
        load_fixture(path)


@pytest.mark.parametrize(
    "sensitive",
    [
        "contact@example.com",
        "010-1234-5678",
        "900101-1234567",
        "1234-5678-9012-3456",
        "123-456-789012",
        "•••",
    ],
)
def test_pii_failure_does_not_expose_sensitive_value(tmp_path: Path, sensitive: str):
    payload = _fixture_payload()
    payload["input"]["screen"]["elements"][0]["label"] = sensitive
    path = _write_fixture(tmp_path, "pii.json", payload)

    with pytest.raises(FixtureError) as raised:
        load_fixture(path)

    message = str(raised.value)
    assert sensitive not in message
    assert "$.input.screen.elements[0].label" in message
    assert "원문 생략" in message


def test_sensitive_fields_are_rejected_without_logging_values(tmp_path: Path):
    payload = _fixture_payload()
    payload["input"]["device_id"] = "private-device-value"
    path = _write_fixture(tmp_path, "device.json", payload)

    with pytest.raises(FixtureError) as raised:
        load_fixture(path)

    assert "private-device-value" not in str(raised.value)
    assert "$.input.device_id" in str(raised.value)


def test_sensitive_json_key_is_detected_without_exposing_key():
    sensitive_key = "contact@example.com"

    findings = find_sensitive_paths({sensitive_key: "safe"})

    assert findings == ["$.<redacted-key>"]
    assert sensitive_key not in " ".join(findings)


def test_envelope_validation_location_redacts_sensitive_key(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    sensitive_key = "contact@example.com"
    payload = _fixture_payload()
    payload[sensitive_key] = "safe"
    path = _write_fixture(tmp_path, "invalid-envelope.json", payload)
    monkeypatch.setattr(
        harness, "assert_no_sensitive_data", lambda *_args, **_kwargs: None
    )

    with pytest.raises(FixtureError) as raised:
        load_fixture(path)

    message = str(raised.value)
    assert sensitive_key not in message
    assert "$.<redacted-key>" in message
    assert "fixture 형식 오류" in message


def test_validation_location_helper_redacts_sensitive_key():
    sensitive_key = "contact@example.com"
    payload = _fixture_payload()
    payload[sensitive_key] = "safe"

    with pytest.raises(ValidationError) as raised:
        FixtureDocument.model_validate(payload)

    locations = _validation_locations(raised.value)
    assert sensitive_key not in locations
    assert locations == "$.<redacted-key>"


def test_suite_returns_per_fixture_and_overall_results(tmp_path: Path):
    _write_fixture(tmp_path, "safe.json", _fixture_payload())

    report = run_suite(tmp_path)

    assert report["summary"] == {"total": 1, "passed": 1, "failed": 0}
    assert report["fixtures"][0]["id"] == "safe-device-screen"
    assert report["fixtures"][0]["status"] == "passed"
    assert report["fixtures"][0]["stub"] == {
        "outcome": "success",
        "action_type": "tap",
    }


def test_repository_one_page_home_preserves_complete_anonymized_tree():
    fixture = load_fixture(FIXTURES / "samsung_launcher_open_camera.json")
    elements = fixture.request.screen.elements

    assert [element.node_id for element in elements] == [
        f"n{index}" for index in range(1, 9)
    ]
    assert all(element.clickable for element in elements)
    assert all(element.role == "FrameLayout" for element in elements)
    assert all(element.bounds is not None for element in elements)
    assert elements[2].label == "일반 앱 1"
    assert elements[3].label == "일반 앱 2"
    assert elements[7].label == "카메라"
    assert elements[7].bounds == [787, 1902, 1035, 2170]
    assert fixture.request.recent_actions == []

    # 실호출 n8 선택은 source 관측값일 뿐, Stub exact-match 기대값이 아니다.
    observed = fixture.document.source.observed_model_action
    assert observed is not None
    assert observed.target_node_id == "n8"
    assert "target_node_id" not in fixture.document.expected.model_fields_set


def test_repository_launcher_fixtures_preserve_observed_layout_variants():
    fixtures = {
        item.document.id: item
        for item in load_fixtures()
        if item.request.screen.package_name == "com.sec.android.app.launcher"
    }

    assert {
        "samsung-launcher-one-page-home",
        "samsung-launcher-multi-page-home",
        "samsung-launcher-grid-app-drawer",
        "samsung-launcher-alphabetical-app-drawer",
    } <= fixtures.keys()

    one_page = fixtures["samsung-launcher-one-page-home"].request.screen
    assert one_page.current_page is None
    assert one_page.total_pages is None
    assert one_page.has_scrollable_element is None
    assert all(element.role != "page_dot" for element in one_page.elements)

    multi_page = fixtures["samsung-launcher-multi-page-home"].request.screen
    assert len(multi_page.elements) == 16
    assert (multi_page.current_page, multi_page.total_pages) == (1, 2)
    assert multi_page.has_scrollable_element is False
    assert sum(element.role == "page_dot" for element in multi_page.elements) == 2

    grid = fixtures["samsung-launcher-grid-app-drawer"].request.screen
    assert len(grid.elements) == 28
    assert (grid.current_page, grid.total_pages) == (1, 2)
    assert grid.has_scrollable_element is False
    assert sum(element.role == "page_dot" for element in grid.elements) == 2
    assert all(element.scroll_axis is None for element in grid.elements)

    alphabetical = fixtures["samsung-launcher-alphabetical-app-drawer"].request.screen
    assert len(alphabetical.elements) == 31
    assert alphabetical.current_page is None
    assert alphabetical.total_pages is None
    assert alphabetical.has_scrollable_element is True
    scrollable = [element for element in alphabetical.elements if element.scroll_axis]
    assert len(scrollable) == 1
    assert scrollable[0].scroll_axis.value == "vertical"
    assert scrollable[0].can_scroll_forward is True
    assert scrollable[0].can_scroll_backward is False
