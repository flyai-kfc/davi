#!/usr/bin/env bash

# start/stop/status가 공유하는 경로와 PID 검증. macOS 기본 Bash 3.2에서 동작해야 한다.

SHARED_DEV_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHARED_DEV_SERVER_DIR="$(cd "$SHARED_DEV_SCRIPT_DIR/.." && pwd)"
SHARED_DEV_STATE_DIR="$SHARED_DEV_SERVER_DIR/.shared-dev"
SHARED_DEV_LOG_DIR="$SHARED_DEV_STATE_DIR/logs"
SHARED_DEV_PID_FILE="$SHARED_DEV_STATE_DIR/server.pid"
SHARED_DEV_PORT=8000
SHARED_DEV_HEALTH_URL="http://127.0.0.1:${SHARED_DEV_PORT}/health"

shared_dev_die() {
    printf '오류: %s\n' "$*" >&2
    exit 1
}

shared_dev_prepare_dirs() {
    mkdir -p "$SHARED_DEV_LOG_DIR"
    chmod 700 "$SHARED_DEV_STATE_DIR" "$SHARED_DEV_LOG_DIR"
}

shared_dev_read_pid() {
    if [[ ! -f "$SHARED_DEV_PID_FILE" ]]; then
        return 1
    fi
    local pid
    pid="$(tr -d '[:space:]' < "$SHARED_DEV_PID_FILE")"
    if [[ ! "$pid" =~ ^[0-9]+$ ]]; then
        return 1
    fi
    printf '%s\n' "$pid"
}

shared_dev_process_cwd() {
    local pid="$1"
    if command -v lsof >/dev/null 2>&1; then
        lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1
        return
    fi
    if [[ -e "/proc/$pid/cwd" ]]; then
        readlink "/proc/$pid/cwd"
    fi
}

shared_dev_is_managed_process() {
    local pid="$1"
    kill -0 "$pid" 2>/dev/null || return 1
    local command_line process_cwd
    command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    process_cwd="$(shared_dev_process_cwd "$pid")"
    [[ "$command_line" == *"uvicorn app.main:app"* ]] || return 1
    [[ "$command_line" == *"--port ${SHARED_DEV_PORT}"* ]] || return 1
    [[ "$process_cwd" == "$SHARED_DEV_SERVER_DIR" ]]
}

shared_dev_private_ipv4() {
    local interface_name address candidate
    interface_name="$(route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}')"
    if [[ -n "$interface_name" ]]; then
        address="$(ipconfig getifaddr "$interface_name" 2>/dev/null || true)"
        if shared_dev_is_private_ipv4 "$address"; then
            printf '%s\n' "$address"
            return 0
        fi
    fi

    for candidate in en0 en1; do
        address="$(ipconfig getifaddr "$candidate" 2>/dev/null || true)"
        if shared_dev_is_private_ipv4 "$address"; then
            printf '%s\n' "$address"
            return 0
        fi
    done
    return 1
}

shared_dev_is_private_ipv4() {
    local address="$1"
    local first second third fourth
    [[ "$address" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || return 1
    first="${BASH_REMATCH[1]}"
    second="${BASH_REMATCH[2]}"
    third="${BASH_REMATCH[3]}"
    fourth="${BASH_REMATCH[4]}"
    [[ "$first" -le 255 && "$second" -le 255 && "$third" -le 255 && "$fourth" -le 255 ]] || return 1
    [[ "$first" -eq 10 ]] && return 0
    [[ "$first" -eq 172 && "$second" -ge 16 && "$second" -le 31 ]] && return 0
    [[ "$first" -eq 192 && "$second" -eq 168 ]] && return 0
    return 1
}

shared_dev_port_in_use() {
    lsof -nP -iTCP:"$SHARED_DEV_PORT" -sTCP:LISTEN >/dev/null 2>&1
}

shared_dev_env_file() {
    if [[ -n "${DAVI_SHARED_DEV_ENV_FILE:-}" ]]; then
        printf '%s\n' "$DAVI_SHARED_DEV_ENV_FILE"
        return
    fi
    printf '%s/davi/shared-dev.env\n' "${XDG_CONFIG_HOME:-"$HOME/.config"}"
}

# 파일 권한 비트를 8진수 문자열로. GNU(coreutils)와 BSD(macOS)의 문법이 달라 둘 다 시도한다.
#
# **GNU를 먼저 시도해야 한다.** 반대로 두면 GNU에서 `-f`가 `--file-system`으로 해석돼
# `'%Lp'`를 파일 인자로 보고 실패하면서도 **대상 파일의 파일시스템 정보를 출력**한다. 그 출력이
# `||` 뒤의 정상 결과와 합쳐져 mode에 섞이고, 권한이 정확히 600인 파일이 거부된다.
# macOS에서는 `stat -c`가 "illegal option"으로 종료코드 1이라 안전하게 BSD 쪽으로 넘어간다.
# (PR #257에서 스크립트를 실제로 실행하는 테스트를 넣고서야 리눅스 CI에서 드러났다.)
shared_dev_file_mode() {
    local path="$1"
    stat -c '%a' "$path" 2>/dev/null || stat -f '%Lp' "$path" 2>/dev/null
}

# 토큰 파일에서 키 하나를 뽑는다. 키 이름을 인자로 받는 이유는 외부 테스트 토큰
# (DAVI_TEST_ACCESS_TOKEN)도 같은 파일에 두기 때문이다 — `.env.example`이 그렇게 안내한다.
# 이 파일은 pydantic이 읽지 않으므로(Settings의 env_file은 server/.env), 여기서 export하지
# 않으면 프로세스 환경에도 없고 서버는 경고 없이 그 토큰 없이 기동한다.
shared_dev_load_token() {
    local env_file="$1"
    local key="${2:-DAVI_DEV_ACCESS_TOKEN}"
    local token
    token="$(
        sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*//p" "$env_file" \
            | tail -n 1
    )"
    case "$token" in
        \"*\") token="${token#\"}"; token="${token%\"}" ;;
        \'*\') token="${token#\'}"; token="${token%\'}" ;;
    esac
    [[ -n "$token" ]] || return 1
    printf '%s\n' "$token"
}

# 두 토큰을 환경변수 -> 토큰 파일 순으로 해결해 전역에 담는다.
#
# 결과를 stdout이 아니라 전역으로 돌려주는 이유는 `shared_dev_die`가 `exit`이기 때문이다 —
# `$(...)` 안에서 부르면 서브셸만 죽고 호출자는 빈 값으로 계속 간다.
#
# **토큰 파일은 개발 토큰을 환경에서 못 구했을 때만 필수다.** 테스트 토큰이 없는 것이
# 평상시의 정상 상태라, 파일 존재·권한 검사를 무조건 걸면 개발 토큰만 export한 실행이
# "개발 토큰 파일이 없습니다"로 죽는다(PR #257 리뷰에서 실측된 회귀).
shared_dev_resolve_tokens() {
    SHARED_DEV_DEV_TOKEN="${DAVI_DEV_ACCESS_TOKEN:-}"
    SHARED_DEV_TEST_TOKEN="${DAVI_TEST_ACCESS_TOKEN:-}"
    local env_file mode

    if [[ -z "$SHARED_DEV_DEV_TOKEN" || -z "$SHARED_DEV_TEST_TOKEN" ]]; then
        env_file="$(shared_dev_env_file)"
        if [[ -f "$env_file" ]]; then
            mode="$(shared_dev_file_mode "$env_file")"
            [[ "$mode" == "600" ]] \
                || shared_dev_die "개발 토큰 파일 권한은 600이어야 합니다(현재 $mode)."
            if [[ -z "$SHARED_DEV_DEV_TOKEN" ]]; then
                SHARED_DEV_DEV_TOKEN="$(shared_dev_load_token "$env_file")" \
                    || shared_dev_die "토큰 파일에 DAVI_DEV_ACCESS_TOKEN이 없습니다."
            fi
            if [[ -z "$SHARED_DEV_TEST_TOKEN" ]]; then
                SHARED_DEV_TEST_TOKEN="$(
                    shared_dev_load_token "$env_file" DAVI_TEST_ACCESS_TOKEN
                )" || SHARED_DEV_TEST_TOKEN=""
            fi
        elif [[ -z "$SHARED_DEV_DEV_TOKEN" ]]; then
            shared_dev_die "개발 토큰 파일이 없습니다: $env_file"
        fi
    fi

    [[ "${#SHARED_DEV_DEV_TOKEN}" -ge 32 ]] \
        || shared_dev_die "DAVI_DEV_ACCESS_TOKEN은 32자 이상이어야 합니다."
    if [[ -n "$SHARED_DEV_TEST_TOKEN" && "${#SHARED_DEV_TEST_TOKEN}" -lt 32 ]]; then
        shared_dev_die "DAVI_TEST_ACCESS_TOKEN은 32자 이상이어야 합니다."
    fi
}
