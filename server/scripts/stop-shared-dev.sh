#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=shared-dev-common.sh
. "$SCRIPT_DIR/shared-dev-common.sh"

pid="$(shared_dev_read_pid 2>/dev/null || true)"
if [[ -z "$pid" ]]; then
    printf '공용 개발 서버가 실행 중이 아닙니다.\n'
    exit 0
fi

if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$SHARED_DEV_PID_FILE"
    printf '종료된 프로세스의 오래된 PID 파일을 정리했습니다(pid=%s).\n' "$pid"
    exit 0
fi

if ! shared_dev_is_managed_process "$pid"; then
    shared_dev_die "PID ${pid}가 이 스크립트가 시작한 davi uvicorn과 일치하지 않아 종료하지 않습니다."
fi

kill -TERM "$pid"
attempt=0
while kill -0 "$pid" 2>/dev/null && [[ "$attempt" -lt 20 ]]; do
    attempt=$((attempt + 1))
    sleep 0.5
done

if kill -0 "$pid" 2>/dev/null; then
    shared_dev_die "PID ${pid}가 SIGTERM 뒤에도 살아 있습니다. PID 파일을 보존했습니다."
fi

rm -f "$SHARED_DEV_PID_FILE"
printf '공용 개발 서버 종료: pid=%s\n' "$pid"
