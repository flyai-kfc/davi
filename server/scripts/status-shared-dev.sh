#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=shared-dev-common.sh
. "$SCRIPT_DIR/shared-dev-common.sh"

pid="$(shared_dev_read_pid 2>/dev/null || true)"
if [[ -z "$pid" ]]; then
    printf '상태: stopped (PID 파일 없음)\n'
    exit 1
fi

if ! shared_dev_is_managed_process "$pid"; then
    printf '상태: stale-or-foreign (pid=%s, 종료하지 않음)\n' "$pid" >&2
    exit 1
fi

if curl -fsS --max-time 2 "$SHARED_DEV_HEALTH_URL" >/dev/null 2>&1; then
    printf '상태: running+healthy (pid=%s)\n' "$pid"
    printf 'local health: %s\n' "$SHARED_DEV_HEALTH_URL"
    if private_ip="$(shared_dev_private_ipv4 2>/dev/null)"; then
        printf 'debug URL: DAVI_DEBUG_SERVER_URL=http://%s:%s\n' "$private_ip" "$SHARED_DEV_PORT"
    fi
    exit 0
fi

printf '상태: running+unhealthy (pid=%s)\n' "$pid" >&2
exit 2
