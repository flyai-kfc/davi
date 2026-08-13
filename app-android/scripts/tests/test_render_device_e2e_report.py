import argparse
import importlib.util
import json
import re
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "render-device-e2e-report.py"
SPEC = importlib.util.spec_from_file_location("device_e2e_report", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class DeviceE2eReportTest(unittest.TestCase):
    def test_android_junit_success_code_is_reported_as_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            instrumentation = root / "instrumentation.txt"
            safe_log = root / "safe.log"
            instrumentation.write_text(
                "Time: 12.5\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n",
                encoding="utf-8",
            )
            safe_log.write_text("I DaviE2e: cleanup=success\n", encoding="utf-8")
            args = argparse.Namespace(
                instrumentation_result=instrumentation,
                safe_log=safe_log,
                output=root / "report.md",
                model="test-model",
                device_model="test-device",
                android_version="15",
                commit="abcdef0",
                started_at="2026-08-13T12:00:00+09:00",
                scenario="safe-scenario",
                scenario_description="비식별 테스트 설명",
                test_class="SafeTest",
                shell_status=0,
            )

            report = MODULE.render(args)

            self.assertIn("| 판정 | **통과** |", report)
            self.assertIn("| 실패 코드 | `-` |", report)

    def test_renders_only_structured_trace_and_failure_code(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            instrumentation = root / "instrumentation.txt"
            safe_log = root / "safe.log"
            instrumentation.write_text(
                "guided scenario failed code=step_limit (화면/안내 원문 생략)\nTime: 12.5\n"
                "INSTRUMENTATION_CODE: -1\n",
                encoding="utf-8",
            )
            safe_log.write_text(
                "I DaviE2e: step=1 action=tap target=true verified=false\n"
                "I GuideSvc: event=request_started logical_request_id=opaque attempt=1\n"
                "I DaviE2e: cleanup=success\n",
                encoding="utf-8",
            )
            args = argparse.Namespace(
                instrumentation_result=instrumentation,
                safe_log=safe_log,
                output=root / "report.md",
                model="test-model",
                device_model="test-device",
                android_version="15",
                commit="abcdef0",
                started_at="2026-08-13T12:00:00+09:00",
                scenario="safe-scenario",
                scenario_description="비식별 테스트 설명",
                test_class="SafeTest",
                shell_status=1,
            )

            report = MODULE.render(args)

            self.assertIn("`step_limit`", report)
            self.assertIn("| 1 | `tap` | true | false |", report)
            self.assertIn("| 시작된 서버 요청 | 1 |", report)
            self.assertIn("| 테스트 데이터 정리 | `success` |", report)
            self.assertIn("| 시나리오 | `safe-scenario` |", report)
            self.assertNotIn("logical_request_id", report)

    def test_scenario_registry_is_safe_and_points_to_test_classes(self):
        app_dir = Path(__file__).parents[2]
        registry = json.loads((app_dir / "device-e2e-scenarios.json").read_text(encoding="utf-8"))
        scenarios = registry["scenarios"]
        ids = [scenario["id"] for scenario in scenarios]

        self.assertEqual(registry["schema_version"], 1)
        self.assertIn(registry["default_scenario"], ids)
        self.assertEqual(len(ids), len(set(ids)))
        for scenario in scenarios:
            self.assertRegex(scenario["id"], re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$"))
            self.assertTrue(scenario["description"].strip())
            self.assertTrue(scenario["requirements"].strip())
            self.assertNotRegex(scenario["description"], re.compile(r"[\r\n]"))
            self.assertNotRegex(scenario["requirements"], re.compile(r"[\r\n]"))
            self.assertRegex(
                scenario["test_class"],
                re.compile(r"^com\.davi\.helper\.e2e\.[A-Za-z][A-Za-z0-9]*Test$"),
            )
            class_name = scenario["test_class"].rsplit(".", 1)[-1]
            source = app_dir / "e2e-driver/src/androidTest/java/com/davi/helper/e2e" / f"{class_name}.kt"
            self.assertTrue(source.is_file(), source)


if __name__ == "__main__":
    unittest.main()
