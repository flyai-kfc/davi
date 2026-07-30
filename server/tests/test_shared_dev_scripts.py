import subprocess
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = SERVER_DIR / "scripts"
SCRIPTS = [
    SCRIPTS_DIR / "shared-dev-common.sh",
    SCRIPTS_DIR / "start-shared-dev.sh",
    SCRIPTS_DIR / "stop-shared-dev.sh",
    SCRIPTS_DIR / "status-shared-dev.sh",
]


@pytest.mark.parametrize("script", SCRIPTS)
def test_shared_dev_script_has_valid_bash_syntax(script: Path):
    subprocess.run(["bash", "-n", str(script)], check=True)


@pytest.mark.parametrize(
    ("address", "expected"),
    [
        ("10.20.30.40", True),
        ("172.16.0.1", True),
        ("172.31.255.254", True),
        ("192.168.1.10", True),
        ("172.32.0.1", False),
        ("8.8.8.8", False),
        ("127.0.0.1", False),
        ("not-an-ip", False),
    ],
)
def test_private_ipv4_detection(address: str, expected: bool):
    command = f". {SCRIPTS_DIR / 'shared-dev-common.sh'}; shared_dev_is_private_ipv4 {address!s}"

    result = subprocess.run(["bash", "-c", command], check=False)

    assert (result.returncode == 0) is expected


def test_stop_script_never_uses_broad_process_kill():
    content = (SCRIPTS_DIR / "stop-shared-dev.sh").read_text()

    assert "shared_dev_is_managed_process" in content
    assert "kill -TERM" in content
    assert "pkill" not in content
    assert "killall" not in content


def test_start_script_enforces_development_real_llm_and_lan_bind():
    content = (SCRIPTS_DIR / "start-shared-dev.sh").read_text()

    assert 'settings.davi_env != "development"' in content
    assert "settings.davi_use_stub_llm" in content
    assert 'dev_token="${DAVI_DEV_ACCESS_TOKEN:-}"' in content
    assert 'export DAVI_BIND_HOST="0.0.0.0"' in content
    assert "--workers 1" in content
