#!/usr/bin/env bash
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=shared-dev-common.sh
. "$SCRIPT_DIR/shared-dev-common.sh"

shared_dev_prepare_dirs
cd "$SHARED_DEV_SERVER_DIR"

[[ -f ".env" ]] || shared_dev_die "server/.env가 없습니다. .env.example을 복사해 실제 LLM 공급자 설정을 채우세요."
[[ -x ".venv/bin/python" ]] || shared_dev_die "server/.venv/bin/python이 없습니다. Python 3.12 환경을 준비하세요."
command -v curl >/dev/null 2>&1 || shared_dev_die "curl이 필요합니다."
command -v lsof >/dev/null 2>&1 || shared_dev_die "lsof가 필요합니다."

if existing_pid="$(shared_dev_read_pid 2>/dev/null)"; then
    if shared_dev_is_managed_process "$existing_pid"; then
        shared_dev_die "공용 개발 서버가 이미 실행 중입니다(pid=$existing_pid)."
    fi
    rm -f "$SHARED_DEV_PID_FILE"
fi

shared_dev_port_in_use && shared_dev_die "TCP ${SHARED_DEV_PORT} 포트를 다른 프로세스가 사용 중입니다."

dev_token="${DAVI_DEV_ACCESS_TOKEN:-}"
if [[ -z "$dev_token" ]]; then
    env_file="$(shared_dev_env_file)"
    [[ -f "$env_file" ]] || shared_dev_die "개발 토큰 파일이 없습니다: $env_file"
    mode="$(shared_dev_file_mode "$env_file")"
    [[ "$mode" == "600" ]] || shared_dev_die "개발 토큰 파일 권한은 600이어야 합니다(현재 $mode)."
    dev_token="$(shared_dev_load_token "$env_file")" \
        || shared_dev_die "토큰 파일에 DAVI_DEV_ACCESS_TOKEN이 없습니다."
fi
[[ "${#dev_token}" -ge 32 ]] || shared_dev_die "DAVI_DEV_ACCESS_TOKEN은 32자 이상이어야 합니다."

private_ip="$(shared_dev_private_ipv4)" || shared_dev_die "활성 사설 IPv4를 찾지 못했습니다. Wi-Fi 연결을 확인하세요."

export DAVI_DEV_ACCESS_TOKEN="$dev_token"
export DAVI_BIND_HOST="0.0.0.0"

# Settings가 server/.env와 위 환경변수를 함께 읽어 키·profile·stub 경계를 한 번 더 검증한다.
".venv/bin/python" -c '
from app.config import settings
if settings.davi_env != "development":
    raise SystemExit("공용 개발 서버는 DAVI_ENV=development에서만 실행할 수 있습니다")
if settings.davi_use_stub_llm:
    raise SystemExit("공용 개발 서버는 DAVI_USE_STUB_LLM=false여야 합니다")
if settings.davi_bind_host != "0.0.0.0":
    raise SystemExit("공용 개발 서버 바인드 설정이 올바르지 않습니다")
'

# 7일보다 오래된 일 단위 로그만 정리한다. 상태 폴더 밖은 절대 지우지 않는다.
find "$SHARED_DEV_LOG_DIR" -type f -name '*.log' -mtime +6 -delete
log_file="$SHARED_DEV_LOG_DIR/$(date '+%Y-%m-%d').log"
touch "$log_file"
chmod 600 "$log_file"

nohup ".venv/bin/python" -m uvicorn app.main:app \
    --host "$DAVI_BIND_HOST" \
    --port "$SHARED_DEV_PORT" \
    --workers 1 \
    --no-access-log \
    --log-config app/logging.json \
    >>"$log_file" 2>&1 &
server_pid=$!
printf '%s\n' "$server_pid" > "$SHARED_DEV_PID_FILE"
chmod 600 "$SHARED_DEV_PID_FILE"

healthy=false
attempt=0
while [[ "$attempt" -lt 20 ]]; do
    if curl -fsS --max-time 1 "$SHARED_DEV_HEALTH_URL" >/dev/null 2>&1; then
        healthy=true
        break
    fi
    if ! kill -0 "$server_pid" 2>/dev/null; then
        break
    fi
    attempt=$((attempt + 1))
    sleep 0.5
done

if [[ "$healthy" != "true" ]]; then
    kill -TERM "$server_pid" 2>/dev/null || true
    rm -f "$SHARED_DEV_PID_FILE"
    shared_dev_die "health 확인에 실패했습니다. 로그를 확인하세요: $log_file"
fi

printf '공용 개발 서버 시작: pid=%s\n' "$server_pid"
printf 'local health: %s\n' "$SHARED_DEV_HEALTH_URL"
printf 'debug URL: DAVI_DEBUG_SERVER_URL=http://%s:%s\n' "$private_ip" "$SHARED_DEV_PORT"
printf '로그: %s (권한 600, 7일 보존)\n' "$log_file"
