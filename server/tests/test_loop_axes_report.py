"""계측 로그를 접는 스크립트가 세션과 두 인과 축을 제대로 가르는지(#429 1단계, dev-only)."""

import importlib.util
import json
import sys
from pathlib import Path

_SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "loop_axes_report.py"
_spec = importlib.util.spec_from_file_location("loop_axes_report", _SCRIPT)
report_module = importlib.util.module_from_spec(_spec)
# **exec 전에 등록한다.** `dataclasses`가 클래스의 모듈을 `sys.modules`에서 되찾는데,
# 등록 전에 실행하면 그 조회가 `None`을 돌려줘 데코레이터가 죽는다.
sys.modules[_spec.name] = report_module
_spec.loader.exec_module(report_module)


def _event(**fields) -> str:
    payload = {
        "event": "loop_axes",
        "request_id": "r1",
        "steps": 3,
        "tried_max": 2,
        "returned_marks": 0,
        "loop_tried": True,
        "loop_returned": False,
        "target_identifiable": True,
        "causal_performed": True,
        "causal_widened": True,
        "target_repeat_identifiable": True,
        "causal_observed": True,
        "axes_all": True,
        "axes_all_observed": True,
        "axes_all_narrow": True,
    }
    return json.dumps(payload | fields, ensure_ascii=False)


def test_sessions_split_on_the_empty_history_request(tmp_path):
    log = tmp_path / "server.err"
    log.write_text(
        "\n".join([_event(steps=0), _event(), _event(steps=0), _event()]),
        encoding="utf-8",
    )

    events = report_module.read_events([log])
    sessions = report_module.split_sessions(events)

    assert len(events) == 4
    assert len(sessions) == 2


def test_other_events_and_garbage_lines_are_ignored(tmp_path):
    log = tmp_path / "server.err"
    log.write_text(
        "\n".join(
            [
                "INFO: 평범한 서버 로그",
                json.dumps({"event": "request_finished", "route": "/v1/next-step"}),
                "{깨진 json",
                _event(),
            ]
        ),
        encoding="utf-8",
    )

    assert len(report_module.read_events([log])) == 1


def test_widened_axis_gain_is_counted(tmp_path):
    """넓힌 축에서만 선 발화가 몇 건인지가 #429 계측 항목 넷째의 답이다."""

    log = tmp_path / "server.err"
    log.write_text(
        "\n".join(
            [
                _event(steps=0, axes_all=False, axes_all_observed=False, axes_all_narrow=False),
                _event(
                    logical_request_id="L1",
                    causal_performed=False,
                    causal_observed=False,
                    axes_all_observed=False,
                    axes_all_narrow=False,
                ),
                _event(logical_request_id="L2"),
            ]
        ),
        encoding="utf-8",
    )

    text = report_module.report(report_module.read_events([log]))

    assert "screen_changed까지 넓혀 1건" in text


def test_empty_log_says_so_instead_of_dividing_by_zero(tmp_path):
    log = tmp_path / "server.err"
    log.write_text("", encoding="utf-8")

    assert "loop_axes 이벤트가 없다" in report_module.report(report_module.read_events([log]))


def test_retries_of_one_logical_request_are_collapsed(tmp_path):
    """502 여섯 번이 발화 여섯 건이 되면 ①의 분자가 무너진다(#411 2번의 모양)."""

    log = tmp_path / "server.err"
    log.write_text(
        "\n".join(
            [
                _event(steps=0, logical_request_id="L1"),
                _event(steps=0, logical_request_id="L1"),
                _event(logical_request_id="L2"),
                _event(logical_request_id="L2"),
            ]
        ),
        encoding="utf-8",
    )

    events = report_module.collapse_retries(report_module.read_events([log]))

    assert len(events) == 2
    assert len(report_module.split_sessions(events)) == 1


def test_events_without_a_logical_id_are_kept(tmp_path):
    """낡은 앱은 추적 header를 안 보낸다 — 접을 근거가 없으면 접지 않는다."""

    log = tmp_path / "server.err"
    log.write_text("\n".join([_event(), _event()]), encoding="utf-8")

    assert len(report_module.collapse_retries(report_module.read_events([log]))) == 2


def test_returned_only_requests_are_counted_even_without_a_target(tmp_path):
    """#429 ③의 사각지대 — 구조 서명이 매번 달라 대상 특정이 안 되는 구간.

    표를 `target_identifiable`로 거르면 「회귀 표시만 켜짐」이 통째로 사라져,
    갈리는지 묻는 항목이 갈리지 않는다고 답한다.
    """

    log = tmp_path / "server.err"
    log.write_text(
        "\n".join(
            _event(
                logical_request_id=f"L{index}",
                loop_tried=False,
                loop_returned=True,
                target_identifiable=False,
                axes_all=False,
                axes_all_observed=False,
                axes_all_narrow=False,
            )
            for index in range(3)
        ),
        encoding="utf-8",
    )

    text = report_module.report(report_module.read_events([log]))

    assert "회귀 표시만 켜짐   3건" in text


def test_report_lists_the_join_key_the_device_log_carries(tmp_path):
    """기기 캡처와 대조할 때 쓰는 키는 `logical_request_id`다(#81)."""

    log = tmp_path / "server.err"
    log.write_text(_event(logical_request_id="L9"), encoding="utf-8")

    assert "logical=L9" in report_module.report(report_module.read_events([log]))
