import re
import unittest
from pathlib import Path


APP_DIR = Path(__file__).parents[2]
APP_CONTRACT = (
    APP_DIR / "app/src/debug/java/com/davi/helper/GuideE2eBridge.kt"
).read_text(encoding="utf-8")
DRIVER_CONTRACT = (
    APP_DIR / "e2e-driver/src/main/java/com/davi/helper/e2e/GuideDecision.kt"
).read_text(encoding="utf-8")


def string_constants(source: str) -> dict[str, str]:
    return dict(re.findall(r'const val ([A-Z0-9_]+) = "([^"]+)"', source))


class DeviceE2eContractTest(unittest.TestCase):
    def test_debug_app_and_driver_observation_contracts_match(self):
        app = string_constants(APP_CONTRACT)
        driver = string_constants(DRIVER_CONTRACT)
        shared = {
            "PERMISSION",
            "ACTION_DECISION",
            "ACTION_DIAGNOSTIC",
            "EXTRA_TARGET_RESOLVED",
            "EXTRA_DIAGNOSTIC",
            "EXTRA_REQUEST_GZIP",
            "EXTRA_RESPONSE_GZIP",
        }

        self.assertEqual({key: app[key] for key in shared}, {key: driver[key] for key in shared})

    def test_next_action_planner_has_no_uiautomator_or_selector_dependency(self):
        planner = (
            APP_DIR / "e2e-driver/src/main/java/com/davi/helper/e2e/GuidedActionPlan.kt"
        ).read_text(encoding="utf-8")

        self.assertNotIn("UiDevice", planner)
        self.assertNotIn("UiObject", planner)
        self.assertNotIn("By.", planner)
        self.assertIn("decision.targetBounds", planner)


if __name__ == "__main__":
    unittest.main()
