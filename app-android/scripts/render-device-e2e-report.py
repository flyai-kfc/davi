#!/usr/bin/env python3
"""비식별 instrumentation 결과와 구조화 logcat을 Markdown 보고서로 만든다."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


FAILURE_DESCRIPTIONS = {
    "step_limit": "허용한 최대 안내 단계 안에 목표 상태와 검증된 완료에 도달하지 못했습니다.",
    "decision_timeout": "제한 시간 안에 표시된 Gemini 결정을 받지 못했습니다.",
    "done_before_goal": "실제 외부 앱 상태가 목표를 충족하기 전에 완료가 선언됐습니다.",
    "done_unverified": "완료 응답의 화면 근거가 검증되지 않았습니다.",
}
FAILURE_PATTERN = re.compile(r"guided scenario failed code=([a-z0-9_-]+)")
TIME_PATTERN = re.compile(r"^Time:\s*([0-9.]+)", re.MULTILINE)
STEP_PATTERN = re.compile(
    r"step=(\d+) action=([a-z_]+) target=(true|false) verified=(true|false)"
)
CLEANUP_PATTERN = re.compile(r"cleanup=(success|failed)")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instrumentation-result", type=Path, required=True)
    parser.add_argument("--safe-log", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--device-model", required=True)
    parser.add_argument("--android-version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--started-at", required=True)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--scenario-description", required=True)
    parser.add_argument("--test-class", required=True)
    parser.add_argument("--shell-status", type=int, required=True)
    return parser.parse_args()


def safe_value(value: str) -> str:
    """보고서 표 구조를 깨지 않는 비식별 메타데이터만 허용한다."""

    return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ").strip()


def render(args: argparse.Namespace) -> str:
    instrumentation = args.instrumentation_result.read_text(encoding="utf-8", errors="replace")
    safe_log = args.safe_log.read_text(encoding="utf-8", errors="replace")
    passed = args.shell_status == 0 and "INSTRUMENTATION_CODE: 0" in instrumentation
    failure_match = FAILURE_PATTERN.search(instrumentation)
    failure_code = failure_match.group(1) if failure_match else ("-" if passed else "instrumentation_failed")
    duration_match = TIME_PATTERN.search(instrumentation)
    duration = f"{duration_match.group(1)}초" if duration_match else "측정 불가"
    steps = STEP_PATTERN.findall(safe_log)
    cleanup_matches = CLEANUP_PATTERN.findall(safe_log)
    cleanup = cleanup_matches[-1] if cleanup_matches else "unobserved"
    request_count = safe_log.count("event=request_started")
    result = "통과" if passed else "실패"
    description = FAILURE_DESCRIPTIONS.get(
        failure_code,
        "구조화 실패 코드를 확인하고 비식별 단계 trace와 함께 원인을 분석해야 합니다.",
    ) if not passed else "외부 앱의 목표 상태와 검증된 완료까지 확인했습니다."

    lines = [
        "# Davi 실제 Gemini 실기기 E2E 결과",
        "",
        "> 이 파일은 실기기 E2E 실행기가 자동 생성한 로컬 보고서입니다. ",
        "> 화면 트리·화면 label·안내 문장·API 키·기기 serial은 포함하지 않습니다.",
        "",
        "## 실행 요약",
        "",
        "| 항목 | 결과 |",
        "|---|---|",
        f"| 판정 | **{result}** |",
        f"| 실패 코드 | `{failure_code}` |",
        f"| 설명 | {description} |",
        f"| 시작 시각 | {safe_value(args.started_at)} |",
        f"| 소요 시간 | {duration} |",
        f"| 모델 | `{safe_value(args.model)}` |",
        f"| 기기 | `{safe_value(args.device_model)}` / Android `{safe_value(args.android_version)}` |",
        f"| Git commit | `{safe_value(args.commit)}` |",
        f"| 시나리오 | `{safe_value(args.scenario)}` |",
        f"| 시나리오 설명 | {safe_value(args.scenario_description)} |",
        f"| 테스트 | `{safe_value(args.test_class)}` |",
        f"| 관측한 안내 단계 | {len(steps)} |",
        f"| 시작된 서버 요청 | {request_count} |",
        f"| 테스트 데이터 정리 | `{cleanup}` |",
        "",
        "## 구조화 단계 trace",
        "",
    ]
    if steps:
        lines += ["| 단계 | action | target bounds | 완료 검증 |", "|---:|---|---|---|"]
        lines += [
            f"| {step} | `{action}` | {target} | {verified} |"
            for step, action, target, verified in steps
        ]
    else:
        lines.append("표시된 결정을 실행하기 전 실패했거나 구조화 trace를 관측하지 못했습니다.")

    lines += [
        "",
        "## 이 결과가 확인하는 범위",
        "",
        "- Davi 접근성 화면 수집 → 실제 서버/Gemini → 구조화 action 응답 경로",
        "- 앱이 표시한 action과 target bounds를 별도 UIAutomator driver가 그대로 수행하는 경로",
        "- 안내 문장과 action의 최소 일관성, 안전한 대상, 조기 완료, 최종 외부 앱 상태",
        "- 성공·실패와 무관한 테스트 전용 데이터 정리 결과",
        "",
        "이 보고서만으로 모델의 전반적 품질이나 다른 기기·앱 호환성을 보장하지는 않습니다.",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    args = parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render(args), encoding="utf-8")


if __name__ == "__main__":
    main()
