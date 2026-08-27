from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

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


def test_schema_date_field_allows_exact_iso_date(tmp_path: Path):
    path = _write_fixture(tmp_path, "dated.json", _fixture_payload())

    fixture = load_fixture(path)

    assert fixture.document.source.captured_at.isoformat() == "2026-07-27"


def test_iso_date_in_plain_string_field_is_not_flagged(tmp_path: Path):
    """날짜는 더 이상 민감으로 잡히지 않는다(#361).

    예전에는 계좌번호 정규식이 `YYYY-MM-DD`를 잡아서, 스키마가 `date`로 검증한 필드만 골라
    면제하는 장치가 하니스에 있었다. 이제 패턴 쪽에서 날짜를 거부하므로 일반 문자열 필드의
    날짜도 그냥 통과한다 — **면제 장치가 없어도 같은 결과**라는 것을 여기서 고정한다.

    패턴이 다시 날짜를 잡게 되면 이 테스트가 빨개진다. 그때 되짚을 곳은 하니스가 아니라
    `server/app/masking.py`의 계좌 패턴이다.
    """
    payload = _fixture_payload()
    payload["description"] = "2026-07-27"
    path = _write_fixture(tmp_path, "date-shaped-text.json", payload)

    fixture = load_fixture(path)

    assert fixture.document.description == "2026-07-27"

    # 이 테스트가 **`input` 밖의 평범한 문자열 필드도 검사 대상**이라는 유일한 증거였다.
    # 날짜 쪽으로 뒤집으면서 그 증거까지 잃지 않도록 계좌 케이스를 같은 필드에 남긴다.
    payload["description"] = "123456-01-123456"
    path = _write_fixture(tmp_path, "account-in-description.json", payload)

    with pytest.raises(FixtureError) as raised:
        load_fixture(path)

    assert "$.description" in str(raised.value)
    assert "123456-01-123456" not in str(raised.value)


def test_observed_action_requires_explicit_review_state(tmp_path: Path):
    payload = _fixture_payload()
    payload["source"]["observed_model_action"] = {
        "action_type": "tap",
        "target_node_id": "n1",
    }
    path = _write_fixture(tmp_path, "missing-review.json", payload)

    with pytest.raises(FixtureError, match="fixture 형식 오류"):
        load_fixture(path)


def test_review_state_without_observed_action_is_rejected(tmp_path: Path):
    payload = _fixture_payload()
    payload["source"]["observed_model_action_review"] = {"status": "unreviewed"}
    path = _write_fixture(tmp_path, "review-without-action.json", payload)

    with pytest.raises(FixtureError, match="fixture 형식 오류"):
        load_fixture(path)


@pytest.mark.parametrize("status", ["accepted", "rejected"])
def test_reviewed_observation_requires_date_and_pull_request(
    tmp_path: Path,
    status: str,
):
    payload = _fixture_payload()
    payload["source"]["observed_model_action"] = {
        "action_type": "tap",
        "target_node_id": "n1",
    }
    payload["source"]["observed_model_action_review"] = {"status": status}
    path = _write_fixture(tmp_path, f"{status}.json", payload)

    with pytest.raises(FixtureError, match="fixture 형식 오류"):
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


def test_envelope_validation_location_redacts_sensitive_key(tmp_path: Path):
    sensitive_key = "contact@example.com"
    payload = _fixture_payload()
    payload[sensitive_key] = "safe"
    path = _write_fixture(tmp_path, "invalid-envelope.json", payload)

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
    assert fixture.request.steps == []

    # 실호출 n8 선택은 source 관측값일 뿐, Stub exact-match 기대값이 아니다.
    observed = fixture.document.source.observed_model_action
    review = fixture.document.source.observed_model_action_review
    assert observed is not None
    assert review is not None
    assert observed.target_node_id == "n8"
    assert review.status == "accepted"
    assert review.reviewed_in_pull_request == 80
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


def test_repository_alarm_picker_fixture_preserves_observed_value_projection():
    fixture = load_fixture(FIXTURES / "samsung_clock_alarm_picker_values.json")
    screen = fixture.request.screen
    elements = {element.node_id: element for element in screen.elements}

    assert fixture.document.source.issue == 462
    assert fixture.document.source.device_model == "SM-S921N"
    assert len(elements) == 29
    assert list(elements) == [f"n{index}" for index in range(1, 30)]
    assert screen.has_scrollable_element is True
    assert screen.ime_visible is False

    assert elements["n2"].label == "오전"
    assert elements["n2"].role == "TextView"
    assert elements["n2"].selected is True

    assert elements["n6"].label == "6, 시간"
    assert elements["n10"].label == "00, 분"
    for node_id in ("n6", "n10"):
        element = elements[node_id]
        assert element.role == "EditText"
        assert element.clickable is True
        assert element.editable is True
        assert element.focused is False
        assert element.showing_hint_text is False

    alarm_name = elements["n24"]
    assert alarm_name.label == "알람 이름"
    assert alarm_name.editable is True
    assert alarm_name.showing_hint_text is True

    assert {element.node_id for element in screen.elements if element.scroll_axis} == {
        "n1",
        "n4",
        "n8",
        "n12",
    }
    assert fixture.request.steps == []
    assert fixture.document.source.observed_model_action is None
