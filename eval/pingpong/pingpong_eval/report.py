"""실행 결과를 사람이 읽는 표로. 수치가 안 남으면 프롬프트를 고칠 근거도 안 남는다."""

from __future__ import annotations

import json
from pathlib import Path

from .metrics import batch_of, in_batch, summarize
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
    summary = summarize(results, batch=batch_of(meta))
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
        f"- 실행 조건: seed {meta.get('seed', '?')} / max_requests {meta.get('max_requests', '?')}",
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
        "## #316 완료 지표",
        "",
        "| 구간 | ready | 2호출 이내 ready | goal 유효 | 누락 정보 환각 | 선택 강요 | 보기 선택 | ready 실행당 TTS 글자 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
        _completion_row("전체", summary["전체"]),
        "",
        "> #316 새 형식은 최대 2회 호출로 고정되므로 ready와 2호출 이내 ready가 같다. 새 형식 행에서 두 값이 같은 사실을 별도 개선 신호로 해석하지 않는다.",
        "> goal 유효·환각·선택 강요의 분모는 같은 배치로 채점된 건수다. 보기 선택은 답변 수, ready·2호출 ready는 전체 실행, TTS는 ready 실행 건수가 분모다.",
        "> TTS는 성공한 사용자 여정 1건의 총 청취 부담이다. 발화당 문구 길이가 아니며, 불필요한 질문이 줄어 값이 낮아지는 효과를 포함한다.",
        "",
        "## 시나리오별",
        "",
        _table(["id", "난이도", "도달", "질문", "자유입력", "종료", "최종 goal"]),
    ]
    batch = batch_of(meta)
    for result in results:
        # 다른 배치(다른 평가자 모델·시나리오 해시)의 채점은 위 집계에서 빠졌다 —
        # 표에서도 이번 수치처럼 보이면 안 된다.
        if in_batch(result, batch):
            achievement = result.judge.achievement.value
        elif result.judge is not None:
            achievement = "다른 배치"
        else:
            achievement = "-"
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


def render_completion_comparison(
    baseline_name: str,
    baseline: dict,
    candidate_name: str,
    candidate: dict,
    *,
    input_mismatches: list[str] | None = None,
) -> str:
    """#316의 다섯 축만 같은 표에 놓는다. 채점 없는 축은 ``-``로 숨기지 않고 드러낸다."""

    lines = [
        "# #316 목표 구체화 A/B",
        "",
        "| 대상 | ready | 2호출 이내 ready | goal 유효 | 누락 정보 환각 | 선택 강요 | 보기 선택 | ready 실행당 TTS 글자 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
        _completion_row(baseline_name, baseline),
        _completion_row(candidate_name, candidate),
        "",
    ]
    if input_mismatches:
        lines.append(
            f"> ⚠️ 입력 조건 불일치를 알고 비교했다: {', '.join(input_mismatches)}"
        )
    lines += [
        "> #316 새 형식은 최대 2회 호출로 고정되어 ready와 2호출 이내 ready가 같다. goal 유효·환각·선택 강요는 같은 평가자·시나리오 배치로 채점된 결과만 비교한다.",
        "> TTS는 ready 여정 1건의 총 청취 부담으로, 질문 수가 줄어 낮아지는 효과도 포함한다. 발화당 문구 길이로 읽지 않는다.",
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


def _completion_row(name: str, block: dict) -> str:
    def rate(key: str) -> str:
        value = block[key]
        return "-" if value is None else f"{value:.0%}"

    tts = block["ready_평균_TTS_문자수"]
    return (
        f"| {name} | {rate('ready율')} | {rate('2회이내_ready율')} | {rate('goal_유효율')} "
        f"| {rate('누락정보_환각률')} | {rate('선택_강요율')} "
        f"| {rate('보기_선택률')} | {'-' if tts is None else tts} |"
    )
