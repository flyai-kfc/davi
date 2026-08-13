import argparse
import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "render-device-e2e-report.py"
SPEC = importlib.util.spec_from_file_location("device_e2e_report", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class DeviceE2eReportTest(unittest.TestCase):
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
                test_class="SafeTest",
                shell_status=1,
            )

            report = MODULE.render(args)

            self.assertIn("`step_limit`", report)
            self.assertIn("| 1 | `tap` | true | false |", report)
            self.assertIn("| 시작된 서버 요청 | 1 |", report)
            self.assertIn("| 테스트 데이터 정리 | `success` |", report)
            self.assertNotIn("logical_request_id", report)


if __name__ == "__main__":
    unittest.main()
