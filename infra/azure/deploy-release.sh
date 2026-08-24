#!/usr/bin/env bash

# main의 release 배포 workflow를 시작하고 같은 실행이 끝날 때까지 기다린다.
# 실제 Azure 변경과 검증은 workflow 한 곳에서만 수행한다.

set -euo pipefail

readonly repository="flyai-kfc/davi"
readonly workflow="azure-release-deploy.yml"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
request_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
cd "$repo_root"

command -v gh >/dev/null 2>&1 || {
  echo "gh CLI가 필요합니다: https://cli.github.com/" >&2
  exit 1
}
gh auth status --hostname github.com >/dev/null

# 아직 main에 workflow가 없으면 feature branch에서 실수로 배포를 시도한 경우다.
gh api \
  --silent \
  -H "Accept: application/vnd.github+json" \
  "repos/$repository/contents/.github/workflows/$workflow?ref=main"

printf 'release 배포 요청: %s (main)\n' "$request_id"
gh workflow run "$workflow" \
  --repo "$repository" \
  --ref main \
  -f "request_id=$request_id"

run_id=""
for _ in $(seq 1 30); do
  run_id="$(
    gh run list \
      --repo "$repository" \
      --workflow "$workflow" \
      --event workflow_dispatch \
      --limit 30 \
      --json databaseId,displayTitle \
      --jq ".[] | select(.displayTitle == \"Azure release deploy $request_id\") | .databaseId"
  )"
  if [[ -n "$run_id" ]]; then
    break
  fi
  sleep 2
done

if [[ -z "$run_id" ]]; then
  echo "GitHub Actions 실행 ID를 찾지 못했습니다. Actions 화면에서 요청 ID를 확인하세요: $request_id" >&2
  exit 1
fi

printf 'GitHub Actions run: %s\n' "$run_id"
gh run watch "$run_id" --repo "$repository" --exit-status
run_url="$(gh run view "$run_id" --repo "$repository" --json url --jq .url)"
printf '검증 완료: %s\n' "$run_url"
