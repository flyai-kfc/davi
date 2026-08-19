import argparse
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "render-device-e2e-private-report.py"
SPEC = importlib.util.spec_from_file_location("device_e2e_private_report", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class DeviceE2ePrivateReportTest(unittest.TestCase):
    def test_screen_text_cannot_create_markdown_link_or_code(self):
        self.assertEqual(
            MODULE.cell("![remote](https://example.invalid) `code` | value"),
            "!&#91;remote&#93;(https://example.invalid) &#96;code&#96; &#124; value",
        )

    def test_renders_request_response_and_outcome_per_guide(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trace = root / "trace.jsonl"
            events = [
                {
                    "event": "decision",
                    "step": 1,
                    "generation": 7,
                    "request_signature_sha256": "opaque-signature-digest",
                    "request": {
                        "goal": "테스트 목표",
                        "screen": {
                            "package_name": "com.example",
                            "elements": [
                                {
                                    "node_id": "n1",
                                    "label": "추가",
                                    "role": "Button",
                                    "clickable": True,
                                    "editable": False,
                                    "focused": False,
                                    "bounds": [1, 2, 3, 4],
                                }
                            ],
                            "has_scrollable_element": False,
                            "ime_visible": False,
                        },
                        "steps": [
                            {
                                "instruction": "이전 안내",
                                "action_type": "tap",
                                "target_label": "알람",
                                "screen_hint": "시계 앱의 알람 목록 화면",
                                "structure_signature": "screen-0",
                                "screen_changed": True,
                            }
                        ],
                        "client_version": "0.1",
                    },
                    "response": {
                        "model": "test-model",
                        "verified": False,
                        "screen_signature": "screen-1",
                        "action": {
                            "expected_result": "편집 화면",
                            "evidence": "추가 버튼",
                            "instruction": "추가를 여세요",
                            "action_type": "tap",
                            "target_node_id": "n1",
                        },
                    },
                    "resolved_target": {
                        "bounds": [1, 2, 3, 4],
                        "role": "Button",
                        "clickable": True,
                        "editable": False,
                    },
                },
                {"event": "step_deviation", "step": 1, "code": "unknown_clock_screen"},
                {"event": "step_outcome", "step": 1, "outcome": "executed", "failure_code": None},
                {"event": "cleanup", "outcome": "success"},
            ]
            trace.write_text("\n".join(json.dumps(event, ensure_ascii=False) for event in events), encoding="utf-8")
            args = argparse.Namespace(
                trace=trace,
                output=root / "private.md",
                model="test-model",
                device_model="test-device",
                android_version="15",
                commit="abcdef0",
                started_at="2026-08-13T12:00:00+09:00",
                scenario="safe-scenario",
            )

            report = MODULE.render(args)

            self.assertIn("## 가이드 1", report)
            self.assertIn("이전 안내", report)
            self.assertIn("시계 앱의 알람 목록 화면", report)
            self.assertIn("| `n1` | 추가 |", report)
            self.assertIn("| expected_result | 편집 화면 |", report)
            self.assertIn("| evidence | 추가 버튼 |", report)
            self.assertIn("| instruction | 추가를 여세요 |", report)
            self.assertIn("| target_node_id | `n1` |", report)
            self.assertIn("| outcome | `executed` |", report)
            self.assertIn(
                "| deviation_codes | `&#91;&quot;unknown_clock_screen&quot;&#93;` |",
                report,
            )
            self.assertIn("로컬 민감 산출물", report)


if __name__ == "__main__":
    unittest.main()
