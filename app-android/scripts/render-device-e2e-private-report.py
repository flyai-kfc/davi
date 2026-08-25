#!/usr/bin/env python3
"""로컬 전용 JSONL trace를 단계별 요청·응답 Markdown으로 렌더링한다."""

from __future__ import annotations

import argparse
import html
import json
from collections import Counter
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--device-model", required=True)
    parser.add_argument("--android-version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--started-at", required=True)
    parser.add_argument("--scenario", required=True)
    return parser.parse_args()


def cell(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, (dict, list)):
        value = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    escaped = html.escape(str(value).replace("\r", " ").replace("\n", " "))
    # 화면 label이나 모델 문장이 Markdown 링크·이미지·code span으로 해석되지 않게 한다.
    return (
        escaped.replace("\\", "&#92;")
        .replace("|", "&#124;")
        .replace("[", "&#91;")
        .replace("]", "&#93;")
        .replace("`", "&#96;")
    )


def load_events(path: Path) -> list[dict[str, Any]]:
    events = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"상세 trace {line_number}번째 줄이 JSON이 아닙니다.") from error
        if not isinstance(value, dict) or not isinstance(value.get("event"), str):
            raise ValueError(f"상세 trace {line_number}번째 줄의 event가 올바르지 않습니다.")
        events.append(value)
    return events


def render(args: argparse.Namespace) -> str:
    events = load_events(args.trace)
    decisions = {event["step"]: event for event in events if event["event"] == "decision"}
    outcomes = {event["step"]: event for event in events if event["event"] == "step_outcome"}
    deviations: dict[int, list[str]] = {}
    for event in events:
        if event["event"] == "step_deviation":
            deviations.setdefault(event["step"], []).append(event.get("code", "unknown"))
    discarded = Counter(
        event.get("reason", "unknown") for event in events if event["event"] == "decision_discarded"
    )
    cleanup = next(
        (event.get("outcome", "unknown") for event in reversed(events) if event["event"] == "cleanup"),
        "unobserved",
    )

    lines = [
        "# Davi 실기기 E2E 상세 분석 보고서",
        "",
        "> **로컬 민감 산출물입니다. 저장소에 커밋하거나 PR·이슈·채팅에 첨부하지 마세요.**",
        "> 원본 Accessibility 트리는 아니지만, 앱이 서버로 보낸 1차 마스킹 화면 label과 모델 문장이 포함됩니다.",
        "",
        "## 실행 정보",
        "",
        "| 항목 | 값 |",
        "|---|---|",
        f"| 시작 시각 | {cell(args.started_at)} |",
        f"| 시나리오 | `{cell(args.scenario)}` |",
        f"| 모델 | `{cell(args.model)}` |",
        f"| 기기 | `{cell(args.device_model)}` / Android `{cell(args.android_version)}` |",
        f"| Git commit | `{cell(args.commit)}` |",
        f"| 기록된 가이드 | {len(decisions)} |",
        f"| cleanup | `{cell(cleanup)}` |",
        "",
    ]
    if discarded:
        lines += ["## 폐기한 결정", "", "| 사유 | 개수 |", "|---|---:|"]
        lines += [f"| `{cell(reason)}` | {count} |" for reason, count in sorted(discarded.items())]
        lines.append("")

    for step, event in sorted(decisions.items()):
        request = event.get("request") or {}
        response = event.get("response") or {}
        screen = request.get("screen") or {}
        action = response.get("action") or {}
        target = event.get("resolved_target") or {}
        outcome = outcomes.get(step) or {}

        lines += [
            f"## 가이드 {step}",
            "",
            "### 요청 snapshot",
            "",
            "| 필드 | 값 |",
            "|---|---|",
            f"| generation | `{cell(event.get('generation'))}` |",
            f"| request_signature_sha256 | `{cell(event.get('request_signature_sha256'))}` |",
            f"| goal | {cell(request.get('goal'))} |",
            f"| package_name | `{cell(screen.get('package_name'))}` |",
            f"| current_page / total_pages | `{cell(screen.get('current_page'))}` / `{cell(screen.get('total_pages'))}` |",
            f"| has_scrollable_element | `{cell(screen.get('has_scrollable_element'))}` |",
            f"| ime_visible | `{cell(screen.get('ime_visible'))}` |",
            f"| client_version | `{cell(request.get('client_version'))}` |",
            "",
            "#### steps",
            "",
        ]
        steps = request.get("steps") or []
        if steps:
            lines += [
                "| 순서 | instruction | action_type | direction | target_label | "
                "screen_hint | changed | performed | structure_signature |",
                "|---:|---|---|---|---|---|---|---|---|",
            ]
            for index, value in enumerate(steps, 1):
                lines.append(
                    f"| {index} | {cell(value.get('instruction'))} | "
                    f"`{cell(value.get('action_type'))}` | `{cell(value.get('direction'))}` | "
                    f"{cell(value.get('target_label'))} | {cell(value.get('screen_hint'))} | "
                    f"`{cell(value.get('screen_changed'))}` | `{cell(value.get('performed'))}` | "
                    f"`{cell(value.get('structure_signature'))}` |"
                )
        else:
            lines.append("- 없음")

        lines += ["", "#### 전달된 화면 요소", ""]
        elements = screen.get("elements") or []
        if elements:
            lines += [
                "| node_id | label | role | click | edit | focus | bounds | scroll |",
                "|---|---|---|---|---|---|---|---|",
            ]
            for element in elements:
                scroll = {
                    key: element.get(key)
                    for key in (
                        "can_scroll_forward",
                        "can_scroll_backward",
                        "scroll_axis",
                        "next_swipe_direction",
                        "prev_swipe_direction",
                    )
                    if element.get(key) is not None
                }
                lines.append(
                    f"| `{cell(element.get('node_id'))}` | {cell(element.get('label'))} | "
                    f"`{cell(element.get('role'))}` | {cell(element.get('clickable'))} | "
                    f"{cell(element.get('editable'))} | {cell(element.get('focused'))} | "
                    f"`{cell(element.get('bounds'))}` | `{cell(scroll)}` |"
                )
        else:
            lines.append("- 전달된 요소 없음")

        lines += [
            "",
            "### 구조화 응답",
            "",
            "| 필드 | 값 |",
            "|---|---|",
            f"| model | `{cell(response.get('model'))}` |",
            f"| verified | `{cell(response.get('verified'))}` |",
            f"| completion_verdict | `{cell(response.get('completion_verdict'))}` |",
            f"| screen_signature | `{cell(response.get('screen_signature'))}` |",
            f"| expected_result | {cell(action.get('expected_result'))} |",
            f"| screen_summary | {cell(action.get('screen_summary'))} |",
            f"| evidence | {cell(action.get('evidence'))} |",
            f"| completion_evidence | `{cell(action.get('completion_evidence'))}` |",
            f"| instruction | {cell(action.get('instruction'))} |",
            f"| action_type | `{cell(action.get('action_type'))}` |",
            f"| direction | `{cell(action.get('direction'))}` |",
            f"| target_node_id | `{cell(action.get('target_node_id'))}` |",
            f"| expected_target_state | `{cell(action.get('expected_target_state'))}` |",
            f"| resolved target role | `{cell(target.get('role'))}` |",
            f"| resolved target bounds | `{cell(target.get('bounds'))}` |",
            f"| resolved target clickable/editable | `{cell(target.get('clickable'))}` / `{cell(target.get('editable'))}` |",
            "",
            "### 실행 결과",
            "",
            "| 항목 | 값 |",
            "|---|---|",
            f"| outcome | `{cell(outcome.get('outcome', 'unobserved'))}` |",
            f"| failure_code | `{cell(outcome.get('failure_code'))}` |",
            f"| deviation_codes | `{cell(deviations.get(step) or None)}` |",
            "",
        ]

    if not decisions:
        lines += ["## 가이드", "", "구조화 결정을 받기 전에 실행이 끝났습니다.", ""]
    return "\n".join(lines)


def main() -> None:
    args = parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render(args), encoding="utf-8")


if __name__ == "__main__":
    main()
