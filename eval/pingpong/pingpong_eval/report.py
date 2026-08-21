"""실행 결과를 사람이 읽는 표로. 수치가 안 남으면 프롬프트를 고칠 근거도 안 남는다."""

from __future__ import annotations

import json
from pathlib import Path

from .metrics import summarize
from .models import ScenarioResult

_SUMMARY_HEADERS = [
    "구간",
    "건수",
    "충족",
    "부분",
    "실패",
    "평균 질문수",
    "확정실패",
    "슬롯 정확률",
    "위반",
]


def write_run(out_dir: Path, results: list[ScenarioResult], meta: dict) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "meta.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    with (out_dir / "transcripts.jsonl").open("w", encoding="utf-8") as handle:
        for result in results:
            handle.write(result.model_dump_json() + "\n")
    summary = summarize(results)
    (out_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    report = render(summary, results, meta)
    path = out_dir / "report.md"
    path.write_text(report, encoding="utf-8")
    return path


def render(summary: dict, results: list[ScenarioResult], meta: dict) -> str:
    lines = [
        "# 핑퐁 eval 실행 보고",
        "",
        f"- 실행: {meta.get('실행시각')}",
        (
            f"- 시나리오: {meta.get('시나리오파일')} ({meta.get('시나리오수')}개, "
            f"해시 {meta.get('시나리오해시', '?')})"
        ),
        f"- 사용자 LLM: {meta.get('사용자모델')} / 평가자 LLM: {meta.get('평가자모델')}"
        + (f" (경유: {meta['평가자경유']})" if meta.get("평가자경유") else ""),
        f"- 핑퐁: {meta.get('핑퐁')} (davi {meta.get('davi_sha', '?')})",
        (
            f"- 채점 비용: 입력 {meta.get('평가자_입력토큰', 0)} · "
            f"출력 {meta.get('평가자_출력토큰', 0)} 토큰 "
            f"→ 약 {meta.get('평가자_추정크레딧', 0)} 크레딧 (추정)"
        ),
        "",
        "## 전체",
        "",
        _table(_SUMMARY_HEADERS),
        _row("전체", summary["전체"]),
    ]
    for name, block in summary["난이도별"].items():
        lines.append(_row(name, block))
    lines += [
        "",
        "## 난이도 요인별",
        "",
        _table(["요인"] + _SUMMARY_HEADERS[1:]),
    ]
    for name, block in summary["요인별"].items():
        lines.append(_row(name, block))

    lines += ["", "## 위반 유형", ""]
    if summary["위반_유형별"]:
        lines.append("| 유형 | 건수 |")
        lines.append("|---|---|")
        lines += [f"| {k} | {v} |" for k, v in summary["위반_유형별"].items()]
    else:
        lines.append("없음.")

    lines += [
        "",
        "## 시나리오별",
        "",
        _table(["id", "난이도", "도달", "질문", "자유입력", "종료", "최종 goal"]),
    ]
    for result in results:
        achievement = result.judge.achievement.value if result.judge else "-"
        # 모델이 만든 문장이라 표를 깨는 문자가 올 수 있다 — 구분자와 줄바꿈만 걷는다.
        goal = " ".join((result.transcript.final_goal or "-").replace("|", "/").split())
        lines.append(
            f"| {result.scenario_id} | {result.difficulty.value} | {achievement} "
            f"| {result.metrics.get('질문수')} | {result.metrics.get('자유입력')} "
            f"| {result.metrics.get('종료사유')} | {goal} |"
        )
    lines += [
        "",
        "> 깨끗한 손작성 시나리오 기준의 낙관적 상한이다. 실기기 발화의 인식 오류·중언부언은 여기 없다.",
        "",
    ]
    return "\n".join(lines)


def _table(headers: list[str]) -> str:
    return "| " + " | ".join(headers) + " |\n|" + "---|" * len(headers)


def _row(name: str, block: dict) -> str:
    정확률 = block["슬롯_정확률"]
    return (
        f"| {name} | {block['건수']} | {block['충족']} | {block['부분']} | {block['실패']} "
        f"| {block['평균_질문수']} | {block['확정실패']} "
        f"| {'-' if 정확률 is None else f'{정확률:.0%}'} | {block['위반수']} |"
    )
