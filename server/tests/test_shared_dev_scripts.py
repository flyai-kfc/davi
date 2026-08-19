import os
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
    # 토큰 해결은 common.sh의 `shared_dev_resolve_tokens`로 옮겼다(아래에서 실행해 검증한다).
    assert "shared_dev_resolve_tokens" in content
    assert 'export DAVI_DEV_ACCESS_TOKEN="$dev_token"' in content
    assert 'export DAVI_BIND_HOST="0.0.0.0"' in content
    assert "--workers 1" in content
    assert "--no-access-log" in content
    assert "--log-config app/logging.json" in content


def _resolve_tokens(env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    """`shared_dev_resolve_tokens`를 실제로 실행한다.

    문자열 단언만으로는 조건 분기의 회귀를 못 잡는다 — `-z dev || -z test`로 넓힌 조건이
    평상시 실행을 죽이던 것을 CI가 못 잡았다(PR #257 리뷰). 그래서 여기서 돌려 본다.
    """

    script = (
        f'source "{SCRIPTS_DIR / "shared-dev-common.sh"}"\n'
        "shared_dev_resolve_tokens\n"
        'printf "%s|%s\\n" "$SHARED_DEV_DEV_TOKEN" "$SHARED_DEV_TEST_TOKEN"\n'
    )
    return subprocess.run(
        ["bash", "-c", script],
        capture_output=True,
        text=True,
        env={"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", ""), **env},
    )


def test_exported_dev_token_alone_starts_without_a_token_file():
    """평상시 상태다 — 외부 테스트 기간이 아니라 테스트 토큰이 없다.

    개발 토큰이 환경에 있으면 토큰 파일은 필요 없다. 파일 검사를 무조건 걸면
    "개발 토큰 파일이 없습니다"로 죽으면서 개발 토큰은 멀쩡히 들려 있는 상태가 된다.
    """

    dev = "d" * 32
    result = _resolve_tokens(
        {"DAVI_DEV_ACCESS_TOKEN": dev, "DAVI_SHARED_DEV_ENV_FILE": "/nonexistent/shared-dev.env"}
    )

    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == f"{dev}|"


def test_missing_token_file_still_fails_when_no_dev_token_is_exported():
    result = _resolve_tokens({"DAVI_SHARED_DEV_ENV_FILE": "/nonexistent/shared-dev.env"})

    assert result.returncode == 1
    assert "개발 토큰 파일이 없습니다" in result.stderr


def test_token_file_supplies_both_keys(tmp_path):
    dev, test = "d" * 32, "t" * 32
    env_file = tmp_path / "shared-dev.env"
    env_file.write_text(
        f"DAVI_DEV_ACCESS_TOKEN={dev}\nDAVI_TEST_ACCESS_TOKEN={test}\n", encoding="utf-8"
    )
    env_file.chmod(0o600)

    result = _resolve_tokens({"DAVI_SHARED_DEV_ENV_FILE": str(env_file)})

    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == f"{dev}|{test}"


def test_token_file_without_the_test_key_is_not_an_error(tmp_path):
    """테스트 토큰은 선택이다 — 테스트 기간이 아닌 때가 기본이다."""

    dev = "d" * 32
    env_file = tmp_path / "shared-dev.env"
    env_file.write_text(f"DAVI_DEV_ACCESS_TOKEN={dev}\n", encoding="utf-8")
    env_file.chmod(0o600)

    result = _resolve_tokens({"DAVI_SHARED_DEV_ENV_FILE": str(env_file)})

    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == f"{dev}|"


def test_short_test_token_is_rejected(tmp_path):
    env_file = tmp_path / "shared-dev.env"
    env_file.write_text(
        f"DAVI_DEV_ACCESS_TOKEN={'d' * 32}\nDAVI_TEST_ACCESS_TOKEN=short\n", encoding="utf-8"
    )
    env_file.chmod(0o600)

    result = _resolve_tokens({"DAVI_SHARED_DEV_ENV_FILE": str(env_file)})

    assert result.returncode == 1
    assert "DAVI_TEST_ACCESS_TOKEN은 32자 이상" in result.stderr


def test_loose_token_file_permissions_are_rejected(tmp_path):
    env_file = tmp_path / "shared-dev.env"
    env_file.write_text(f"DAVI_DEV_ACCESS_TOKEN={'d' * 32}\n", encoding="utf-8")
    env_file.chmod(0o644)

    result = _resolve_tokens({"DAVI_SHARED_DEV_ENV_FILE": str(env_file)})

    assert result.returncode == 1
    assert "권한은 600" in result.stderr


def test_file_mode_tries_gnu_stat_before_bsd():
    """순서가 뒤집히면 리눅스에서 권한 600인 파일이 거부된다.

    GNU에서 `-f`는 `--file-system`이라 `'%Lp'`를 파일 인자로 보고 실패하면서도 대상의
    파일시스템 정보를 출력한다. 그 출력이 `||` 뒤 결과와 합쳐져 mode에 섞인다. 위 실행
    테스트들이 리눅스 CI에서는 이걸 잡지만, macOS 로컬에서는 BSD 문법이 먼저 맞아 통과하므로
    순서 자체를 여기서 고정한다.
    """

    common = (SCRIPTS_DIR / "shared-dev-common.sh").read_text(encoding="utf-8")
    body = common[common.index("shared_dev_file_mode()") :]
    body = body[: body.index("\n}\n")]

    assert body.index("stat -c '%a'") < body.index("stat -f '%Lp'")
