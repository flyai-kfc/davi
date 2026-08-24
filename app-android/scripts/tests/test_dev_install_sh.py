import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "dev-install.sh"
SERVICE = "com.davi.helper.debug/com.davi.helper.GuideAccessibilityService"
TALKBACK = "com.google.android.marvin.talkback/.TalkBackService"


FAKE_ADB = r"""#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_ADB_LOG"

if [[ -n "${FAKE_FAIL_ONCE:-}" && "$*" == *"$FAKE_FAIL_ONCE"* && ! -e "$FAKE_STATE_DIR/failed-once" ]]; then
    : > "$FAKE_STATE_DIR/failed-once"
    exit 9
fi

if [[ "${1:-}" == "devices" ]]; then
    printf 'List of devices attached\n'
    printf '%b' "${FAKE_DEVICES:-}"
    exit 0
fi

if [[ "${1:-}" == "-s" ]]; then shift 2; fi
if [[ "${1:-}" == "logcat" && "${2:-}" == "-c" ]]; then exit 0; fi
if [[ "${1:-}" != "shell" ]]; then exit 2; fi
shift

if [[ "$*" == "getprop ro.product.model" ]]; then
    printf '%s\n' "${FAKE_MODEL:-SM-TEST}"
elif [[ "$*" == "settings get secure enabled_accessibility_services" ]]; then
    value="$(cat "$FAKE_STATE_DIR/enabled")"
    printf '%s\n' "${value:-null}"
elif [[ "$*" == "settings get secure accessibility_enabled" ]]; then
    cat "$FAKE_STATE_DIR/global"
elif [[ "${1:-}" == "settings" && "${2:-}" == "put" && "${3:-}" == "secure" ]]; then
    if [[ "$4" == "enabled_accessibility_services" ]]; then
        printf '%s' "$5" > "$FAKE_STATE_DIR/enabled"
    elif [[ "$4" == "accessibility_enabled" ]]; then
        printf '%s' "$5" > "$FAKE_STATE_DIR/global"
    fi
elif [[ "${1:-}" == "settings" && "${2:-}" == "delete" && "${3:-}" == "secure" ]]; then
    : > "$FAKE_STATE_DIR/enabled"
elif [[ "$*" == "dumpsys accessibility" ]]; then
    enabled="$(cat "$FAKE_STATE_DIR/enabled")"
    global="$(cat "$FAKE_STATE_DIR/global")"
    bound="${FAKE_BOUND_SERVICES:-}"
    if [[ -z "$bound" && "$global" == "1" ]]; then bound="$enabled"; fi
    printf 'User state[attributes]:{\n'
    printf '  Bound services:{%s}\n' "$bound"
    printf '  Crashed services:{%s}\n' "${FAKE_CRASHED_SERVICES:-}"
    printf '  Enabled services:{%s}\n' "$enabled"
    printf '}\n'
elif [[ "${1:-}" == "pm" && "${2:-}" == "list" && "${3:-}" == "packages" ]]; then
    if [[ "${FAKE_INSTALLED_PACKAGE:-}" == "$4" ]]; then
        printf 'package:%s\n' "$4"
    fi
elif [[ "${1:-}" == "dumpsys" && "${2:-}" == "package" ]]; then
    printf '  versionName=%s\n' "${FAKE_VERSION:-0.2.0-debug}"
elif [[ "$*" == "am start -a android.settings.ACCESSIBILITY_SETTINGS" ]]; then
    printf 'Starting: Intent\n'
else
    printf '지원하지 않는 fake adb 호출: %s\n' "$*" >&2
    exit 2
fi
"""


FAKE_GRADLEW = r"""#!/usr/bin/env bash
set -euo pipefail
printf 'args=%s\n' "$*" >> "$FAKE_GRADLE_LOG"
printf 'JAVA_HOME=%s\n' "${JAVA_HOME:-}" >> "$FAKE_GRADLE_LOG"
printf 'ANDROID_HOME=%s\n' "${ANDROID_HOME:-}" >> "$FAKE_GRADLE_LOG"
printf 'ANDROID_SERIAL=%s\n' "${ANDROID_SERIAL:-}" >> "$FAKE_GRADLE_LOG"
"""


class DevInstallShTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.app = self.root / "app-android"
        self.scripts = self.app / "scripts"
        self.sdk = self.root / "sdk"
        self.state = self.root / "state"
        self.scripts.mkdir(parents=True)
        (self.sdk / "platform-tools").mkdir(parents=True)
        self.state.mkdir()

        self.script = self.scripts / "dev-install.sh"
        shutil.copy2(SCRIPT, self.script)
        self.adb = self.sdk / "platform-tools" / "adb"
        self.adb.write_text(FAKE_ADB, encoding="utf-8")
        self.adb.chmod(0o755)
        self.gradlew = self.app / "gradlew"
        self.gradlew.write_text(FAKE_GRADLEW, encoding="utf-8")
        self.gradlew.chmod(0o755)

        (self.state / "enabled").write_text(TALKBACK, encoding="utf-8")
        (self.state / "global").write_text("1", encoding="utf-8")
        self.adb_log = self.root / "adb.log"
        self.gradle_log = self.root / "gradle.log"
        self.env = {
            **os.environ,
            "ANDROID_HOME": str(self.sdk),
            "ANDROID_SDK_ROOT": "",
            "ANDROID_SERIAL": "",
            "JAVA_HOME": "",
            "HOME": str(self.root / "home"),
            "FAKE_ADB_LOG": str(self.adb_log),
            "FAKE_GRADLE_LOG": str(self.gradle_log),
            "FAKE_STATE_DIR": str(self.state),
            "FAKE_DEVICES": "SERIAL-1\\tdevice\\n",
            "FAKE_INSTALLED_PACKAGE": "com.davi.helper.debug",
        }

    def tearDown(self):
        self.temp.cleanup()

    def run_script(self, *args, env=None):
        return subprocess.run(
            ["/bin/bash", str(self.script), *args],
            check=False,
            capture_output=True,
            text=True,
            env=env or self.env,
            timeout=10,
        )

    def write_metadata(self, application_id="com.davi.helper.debug"):
        metadata = self.app / "app/build/outputs/apk/debug/output-metadata.json"
        metadata.parent.mkdir(parents=True)
        metadata.write_text(
            '{\n  "version": 3,\n  "applicationId": "' + application_id + '"\n}\n',
            encoding="utf-8",
        )

    def test_status_is_read_only_and_distinguishes_global_switch_from_binding(self):
        (self.state / "enabled").write_text(f"{TALKBACK}:{SERVICE}", encoding="utf-8")
        (self.state / "global").write_text("0", encoding="utf-8")
        env = {**self.env, "FAKE_BOUND_SERVICES": SERVICE}

        result = self.run_script("--status", env=env)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("기기      : SM-TEST [SERIAL-1]", result.stdout)
        self.assertIn("com.davi.helper.debug 0.2.0-debug 설치됨", result.stdout)
        self.assertIn("바인딩은 있으나 전역 스위치가 꺼짐", result.stdout)
        calls = self.adb_log.read_text(encoding="utf-8")
        self.assertNotIn("settings put", calls)
        self.assertNotIn("settings delete", calls)
        self.assertFalse(self.gradle_log.exists())

    def test_status_distinguishes_listed_unbound_crashed_and_unlisted_bound(self):
        cases = (
            (
                f"{TALKBACK}:{SERVICE}",
                TALKBACK,
                "",
                "목록엔 있으나 바인딩 없음",
            ),
            (
                f"{TALKBACK}:{SERVICE}",
                TALKBACK,
                SERVICE,
                "서비스가 크래시 상태",
            ),
            (
                TALKBACK,
                SERVICE,
                "",
                "바인딩은 있으나 목록에 없음",
            ),
        )
        for enabled, bound, crashed, expected in cases:
            with self.subTest(expected=expected):
                (self.state / "enabled").write_text(enabled, encoding="utf-8")
                env = {
                    **self.env,
                    "FAKE_BOUND_SERVICES": bound,
                    "FAKE_CRASHED_SERVICES": crashed,
                }
                result = self.run_script("--status", env=env)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(expected, result.stdout)

    def test_a11y_only_preserves_other_services_and_enables_debug_component(self):
        self.write_metadata()

        result = self.run_script("--a11y-only")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            (self.state / "enabled").read_text(encoding="utf-8"),
            f"{TALKBACK}:{SERVICE}",
        )
        self.assertEqual((self.state / "global").read_text(encoding="utf-8"), "1")
        self.assertIn(f"켬: {SERVICE}", result.stdout)
        calls = self.adb_log.read_text(encoding="utf-8")
        self.assertIn("settings put secure accessibility_enabled 0", calls)
        self.assertIn(f"settings put secure enabled_accessibility_services {TALKBACK}:{SERVICE}", calls)

    def test_a11y_failure_restores_original_services_and_global_switch(self):
        self.write_metadata()
        env = {
            **self.env,
            "FAKE_FAIL_ONCE": f"settings put secure enabled_accessibility_services {TALKBACK}:{SERVICE}",
        }

        result = self.run_script("--a11y-only", env=env)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.state / "enabled").read_text(encoding="utf-8"), TALKBACK)
        self.assertEqual((self.state / "global").read_text(encoding="utf-8"), "1")
        self.assertIn("진입 시점으로 되돌리고 전역 스위치를 켰습니다", result.stderr)

    def test_build_uses_local_sdk_metadata_package_jbr_and_selected_serial(self):
        self.write_metadata()
        jdk = self.root / "jdk"
        (jdk / "bin").mkdir(parents=True)
        (jdk / "bin/java").write_text("#!/bin/sh\n", encoding="utf-8")
        (jdk / "bin/java").chmod(0o755)
        (self.app / "local.properties").write_text(f"sdk.dir={self.sdk}\n", encoding="utf-8")
        env = {
            **self.env,
            "ANDROID_HOME": "",
            "ANDROID_SDK_ROOT": "",
            "JAVA_HOME": str(jdk),
        }

        result = self.run_script("--skip-accessibility", env=env)

        self.assertEqual(result.returncode, 0, result.stderr)
        gradle = self.gradle_log.read_text(encoding="utf-8")
        self.assertIn("args=installDebug", gradle)
        self.assertIn(f"JAVA_HOME={jdk}", gradle)
        self.assertIn(f"ANDROID_HOME={self.sdk}", gradle)
        self.assertIn("ANDROID_SERIAL=SERIAL-1", gradle)

    def test_status_reports_multiple_devices_without_mutation(self):
        env = {**self.env, "FAKE_DEVICES": "SERIAL-1\\tdevice\\nSERIAL-2\\tdevice\\n"}

        result = self.run_script("--status", env=env)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("기기가 여러 대 연결", result.stdout)
        self.assertIn("패키지    : 확인 불가", result.stdout)
        calls = self.adb_log.read_text(encoding="utf-8")
        self.assertNotIn("settings put", calls)

    def test_status_reports_no_device_without_failing(self):
        result = self.run_script("--status", env={**self.env, "FAKE_DEVICES": ""})

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("기기      : 없음", result.stdout)
        self.assertIn("접근성    : 확인 불가", result.stdout)

    def test_install_refuses_no_device_and_multiple_devices(self):
        for devices, message in (
            ("", "연결되고 adb 승인된 기기가 없습니다"),
            ("SERIAL-1\\tdevice\\nSERIAL-2\\tdevice\\n", "기기가 여러 대 연결"),
        ):
            with self.subTest(devices=devices):
                self.adb_log.write_text("", encoding="utf-8")
                result = self.run_script("--a11y-only", env={**self.env, "FAKE_DEVICES": devices})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)
                self.assertNotIn("settings put", self.adb_log.read_text(encoding="utf-8"))

    def test_adb_devices_failure_is_not_reported_as_no_device(self):
        result = self.run_script("--status", env={**self.env, "FAKE_FAIL_ONCE": "devices"})

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("adb devices 실행에 실패", result.stderr)
        self.assertNotIn("기기      : 없음", result.stdout)

    def test_missing_adb_and_invalid_java_home_explain_the_required_setting(self):
        missing_sdk = self.root / "missing-sdk"
        env = {
            **self.env,
            "ANDROID_HOME": str(missing_sdk),
            "ANDROID_SDK_ROOT": "",
            "PATH": "/usr/bin:/bin",
        }
        result = self.run_script("--status", env=env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ANDROID_HOME/ANDROID_SDK_ROOT", result.stderr)

        env = {**self.env, "JAVA_HOME": str(self.root / "missing-jdk")}
        result = self.run_script("--skip-accessibility", env=env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("JAVA_HOME의 java를 실행할 수 없습니다", result.stderr)

    def test_explicit_package_and_serial_override_discovery(self):
        custom = "com.example.davi.debug"
        env = {
            **self.env,
            "FAKE_DEVICES": "SERIAL-1\\tdevice\\nSERIAL-2\\tdevice\\n",
            "FAKE_INSTALLED_PACKAGE": custom,
            "FAKE_BOUND_SERVICES": f"{custom}/com.davi.helper.GuideAccessibilityService",
        }

        result = self.run_script(
            "--status",
            "--serial",
            "SERIAL-2",
            "--package-name",
            custom,
            env=env,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("SM-TEST [SERIAL-2] (연결 2대 중 대상)", result.stdout)
        self.assertIn(f"패키지    : {custom}", result.stdout)


if __name__ == "__main__":
    unittest.main()
