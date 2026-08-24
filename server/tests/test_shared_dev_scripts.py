import os
import shutil
import signal
import subprocess
import sys
import time
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


def _isolated_stop_script(tmp_path: Path) -> tuple[Path, Path]:
    """실제 저장소 PID 파일과 격리된 공용 서버 디렉터리를 만든다."""

    server_dir = tmp_path / "server"
    scripts_dir = server_dir / "scripts"
    scripts_dir.mkdir(parents=True)
    (server_dir / ".shared-dev").mkdir()
    for name in ("shared-dev-common.sh", "stop-shared-dev.sh"):
        shutil.copy2(SCRIPTS_DIR / name, scripts_dir / name)
    return server_dir, scripts_dir / "stop-shared-dev.sh"


def _start_probe_process(cwd: Path, *command_markers: str) -> int:
    """start 스크립트처럼 부모 셸이 끝난 임시 프로세스의 PID를 돌려준다.

    pytest의 직접 자식으로 두면 SIGTERM 뒤 부모가 wait하기 전까지 zombie로 남아 `kill -0`가
    생존으로 읽는다. 실제 start 스크립트는 백그라운드 uvicorn을 띄운 뒤 셸이 끝나므로, 같은
    프로세스 수명을 만들고 OS가 종료된 자식을 회수하게 한다.
    """

    launcher = 'cd "$1"; shift; "$@" >/dev/null 2>&1 & printf "%s\\n" "$!"'
    result = subprocess.run(
        [
            "bash",
            "-c",
            launcher,
            "probe-launcher",
            str(cwd),
            sys.executable,
            "-c",
            "import time; time.sleep(60)",
            *command_markers,
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    pid = int(result.stdout.strip())
    expected_marker = " ".join(command_markers)
    for _attempt in range(50):
        command = subprocess.run(
            ["ps", "-p", str(pid), "-o", "command="],
            check=False,
            capture_output=True,
            text=True,
        ).stdout
        if expected_marker in command:
            return pid
        time.sleep(0.02)
    raise AssertionError("임시 프로세스가 기대한 command로 시작되지 않았다")


def _record_probe_pid(server_dir: Path, pid: int) -> Path:
    pid_file = server_dir / ".shared-dev" / "server.pid"
    pid_file.write_text(f"{pid}\n", encoding="utf-8")
    return pid_file


def _run_isolated_stop(stop_script: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["bash", str(stop_script)],
        check=False,
        capture_output=True,
        text=True,
        timeout=15,
    )


def _probe_is_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True


def _terminate_probe(pid: int) -> None:
    if not _probe_is_alive(pid):
        return
    os.kill(pid, signal.SIGTERM)
    for _attempt in range(30):
        if not _probe_is_alive(pid):
            return
        time.sleep(0.1)
    os.kill(pid, signal.SIGKILL)


def test_stop_script_terminates_the_recorded_managed_process(tmp_path: Path):
    server_dir, stop_script = _isolated_stop_script(tmp_path)
    pid = _start_probe_process(
        server_dir,
        "uvicorn",
        "app.main:app",
        "--port",
        "8000",
    )
    pid_file = _record_probe_pid(server_dir, pid)

    try:
        result = _run_isolated_stop(stop_script)

        assert result.returncode == 0, result.stderr
        assert not _probe_is_alive(pid)
        assert not pid_file.exists()
    finally:
        _terminate_probe(pid)


@pytest.mark.parametrize("mismatch", ["command", "cwd"])
def test_stop_script_preserves_a_process_that_does_not_match(tmp_path: Path, mismatch: str):
    server_dir, stop_script = _isolated_stop_script(tmp_path)
    process_cwd = tmp_path if mismatch == "cwd" else server_dir
    markers = (
        ("unrelated-process", "--port", "8000")
        if mismatch == "command"
        else ("uvicorn", "app.main:app", "--port", "8000")
    )
    pid = _start_probe_process(process_cwd, *markers)
    pid_file = _record_probe_pid(server_dir, pid)

    try:
        result = _run_isolated_stop(stop_script)

        assert result.returncode == 1
        assert "일치하지 않아 종료하지 않습니다" in result.stderr
        assert _probe_is_alive(pid)
        assert pid_file.exists()
    finally:
        _terminate_probe(pid)


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
