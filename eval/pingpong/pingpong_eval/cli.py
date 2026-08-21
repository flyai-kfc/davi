"""명령줄 진입점.

- ``check``  : 키 없이 시나리오 파일만 검증한다. CI에 걸 수 있는 유일한 층이다.
- ``run``    : 사용자 LLM ↔ 핑퐁 ↔ 평가자 LLM 한 바퀴.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import subprocess
import sys
import uuid
from datetime import datetime
from pathlib import Path

from .judge import judge as run_judge
from .llm import ClaudeJudge, GeminiClient, LlmError, estimate_credits
from .models import ScenarioFile, ScenarioResult, StopReason, Transcript
from .report import write_run
from .runner import MAX_REQUESTS, run_scenario
from .sut import PingpongError, SlotFillHttpAdapter
from .user_sim import UserSimulator

DEFAULT_SCENARIOS = Path("scenarios/handwritten-30.json")


def load_scenarios(path: Path) -> ScenarioFile:
    return ScenarioFile.model_validate_json(path.read_text(encoding="utf-8"))


def scenario_digest(path: Path) -> str:
    """시나리오 파일의 내용 해시.

    run과 judge를 나눠 두면 그 사이에 시나리오 파일이 바뀔 수 있다. 그러면 옛 대화를 새 정답으로
    조용히 채점하게 되고, 수치는 멀쩡해 보이지만 비교할 수 없는 값이 된다. 그래서 실행 때 찍고
    채점 때 대조한다.
    """

    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def cmd_check(args: argparse.Namespace) -> int:
    file = load_scenarios(args.scenarios)
    total_slots = sum(len(s.slots) for s in file.scenarios)
    ids = [s.id for s in file.scenarios]
    if len(set(ids)) != len(ids):
        print("id가 겹친다", file=sys.stderr)
        return 1
    for scenario in file.scenarios:
        if not scenario.first_slots:
            print(
                f"[{scenario.id}] 처음부터 말할 수 있는 슬롯이 하나도 없다",
                file=sys.stderr,
            )
            return 1
    print(f"시나리오 {len(file.scenarios)}개 · 슬롯 {total_slots}개 · 검증 통과")
    for level in ("쉬움", "중간", "어려움"):
        subset = [s for s in file.scenarios if s.difficulty.value == level]
        avg = sum(len(s.slots) for s in subset) / len(subset) if subset else 0
        print(f"  {level}: {len(subset)}개, 평균 슬롯 {avg:.1f}")
    return 0


def cmd_judge(args: argparse.Namespace) -> int:
    """이미 돌려둔 실행의 트랜스크립트를 나중에 채점한다.

    대화(Gemini)와 채점(게이트웨이 크레딧)의 비용 주체가 다르다. 대화를 먼저 싸게 다 돌려
    두고, 크레딧을 쓸 값어치가 있는 것만 골라 채점하려면 이 분리가 필요하다.
    """

    run_dir = Path(args.run)
    path = run_dir / "transcripts.jsonl"
    meta = json.loads((run_dir / "meta.json").read_text(encoding="utf-8"))
    recorded = meta.get("시나리오해시")
    current = scenario_digest(args.scenarios)
    if recorded and recorded != current and not args.allow_scenario_drift:
        print(
            f"시나리오 파일이 실행 당시와 다르다(기록 {recorded} · 지금 {current}). "
            "옛 대화를 새 정답으로 채점하면 비교할 수 없는 수치가 된다. "
            "실행 당시 파일로 되돌리거나, 알고도 진행하려면 --allow-scenario-drift 를 쓴다.",
            file=sys.stderr,
        )
        return 1
    scenarios = {s.id: s for s in load_scenarios(args.scenarios).scenarios}
    results = [
        ScenarioResult.model_validate_json(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]

    picked = results
    if args.difficulty:
        wanted = set(args.difficulty.split(","))
        picked = [r for r in picked if r.difficulty.value in wanted]
    if args.only:
        wanted = set(args.only.split(","))
        picked = [r for r in picked if r.scenario_id in wanted]
    if args.limit:
        picked = picked[: args.limit]

    print(f"{len(results)}건 중 {len(picked)}건을 채점한다")
    if args.dry_run:
        for result in picked:
            print(f"  {result.scenario_id} [{result.difficulty.value}]")
        return 0

    try:
        judge_client = ClaudeJudge()
    except LlmError as error:
        print(f"준비 실패: {error}", file=sys.stderr)
        return 1

    for result in picked:
        try:
            result.judge = run_judge(
                judge_client, scenarios[result.scenario_id], result.transcript
            )
        except LlmError as error:
            print(f"[{result.scenario_id}] 채점 실패: {error}", file=sys.stderr)
            continue
        print(
            f"{result.scenario_id} [{result.difficulty.value}] {result.judge.achievement.value}"
        )

    meta.update(
        {
            "평가자모델": judge_client.model,
            "평가자경유": judge_client.base_url,
            "평가자_입력토큰": judge_client.input_tokens,
            "평가자_출력토큰": judge_client.output_tokens,
            "평가자_추정크레딧": round(
                estimate_credits(judge_client.input_tokens, judge_client.output_tokens),
                1,
            ),
            "채점범위": args.difficulty or args.only or "전체",
            "채점시_시나리오해시": current,
        }
    )
    report = write_run(run_dir, results, meta)
    print(f"\n보고서 갱신: {report}")
    print(f"이번 채점 추정 크레딧: {meta['평가자_추정크레딧']}")
    return 0


def cmd_run(args: argparse.Namespace) -> int:
    file = load_scenarios(args.scenarios)
    scenarios = file.scenarios
    if args.only:
        wanted = set(args.only.split(","))
        scenarios = [s for s in scenarios if s.id in wanted]
    if args.limit:
        scenarios = scenarios[: args.limit]
    if not scenarios:
        print("돌릴 시나리오가 없다", file=sys.stderr)
        return 1

    try:
        user_client = GeminiClient()
        judge_client = None if args.no_judge else ClaudeJudge()
    except LlmError as error:
        print(f"준비 실패: {error}", file=sys.stderr)
        return 1

    user = UserSimulator(user_client, rng=random.Random(args.seed))
    results = []
    openings: list[str] = []

    for scenario in scenarios:
        adapter = SlotFillHttpAdapter(
            args.base_url, session_id=str(uuid.uuid4()), dev_token=args.dev_token
        )
        try:
            adapter.preflight()
            result = run_scenario(
                scenario,
                user,
                adapter,
                avoid_openings=openings,
                max_rounds=args.max_rounds,
            )
        except (PingpongError, LlmError) as error:
            # 한 건이 죽어도 나머지를 계속 돌린다. 실패도 결과다 — 보고서에 남긴다.
            print(f"[{scenario.id}] 건너뜀: {str(error)[:120]}", file=sys.stderr)
            results.append(
                ScenarioResult(
                    scenario_id=scenario.id,
                    difficulty=scenario.difficulty,
                    factors=scenario.factors,
                    transcript=Transcript(
                        scenario_id=scenario.id,
                        opening="",
                        stop_reason=StopReason.ERROR,
                        error=str(error)[:500],
                    ),
                    metrics={"라운드수": 0, "종료사유": "error"},
                )
            )
            continue
        finally:
            adapter.close()

        openings.append(result.transcript.opening)
        if judge_client is not None:
            try:
                result.judge = run_judge(judge_client, scenario, result.transcript)
            except LlmError as error:
                print(f"[{scenario.id}] 채점 실패: {error}", file=sys.stderr)
        results.append(result)
        achievement = result.judge.achievement.value if result.judge else "미채점"
        print(
            f"{scenario.id} [{scenario.difficulty.value}] "
            f"질문 {result.metrics['질문수']}개 · {result.metrics['종료사유']} · {achievement}"
        )

    meta = {
        "실행시각": datetime.now().astimezone().isoformat(timespec="seconds"),
        "시나리오파일": str(args.scenarios),
        "시나리오해시": scenario_digest(args.scenarios),
        "시나리오수": len(scenarios),
        "사용자모델": user_client.model,
        "평가자모델": judge_client.model if judge_client else None,
        "평가자경유": judge_client.base_url if judge_client else None,
        "평가자_입력토큰": judge_client.input_tokens if judge_client else 0,
        "평가자_출력토큰": judge_client.output_tokens if judge_client else 0,
        "평가자_추정크레딧": (
            round(
                estimate_credits(judge_client.input_tokens, judge_client.output_tokens),
                1,
            )
            if judge_client
            else 0
        ),
        "핑퐁": args.base_url,
        "davi_sha": _davi_sha(args.davi_repo),
        "seed": args.seed,
        "max_requests": args.max_requests,
    }
    out_dir = Path(args.out) / datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    path = write_run(out_dir, results, meta)
    print(f"\n보고서: {path}")
    print(json.dumps(_headline(results), ensure_ascii=False, indent=2))
    return 0


def _headline(results: list) -> dict:
    judged = [r for r in results if r.judge]
    return {
        "채점됨": len(judged),
        "충족": sum(1 for r in judged if r.judge.achievement.value == "충족"),
        "평균질문수": round(
            sum(r.metrics["질문수"] for r in results) / len(results), 2
        ),
    }


def _davi_sha(repo: str | None) -> str | None:
    """어느 핑퐁을 잰 실행인지 남긴다. 없으면 A/B 비교를 못 한다."""

    if not repo:
        return None
    try:
        return subprocess.run(
            ["git", "-C", repo, "rev-parse", "--short", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="pingpong_eval", description="핑퐁 eval")
    sub = parser.add_subparsers(dest="command", required=True)

    check = sub.add_parser("check", help="시나리오 파일만 검증(키 불필요)")
    check.add_argument("--scenarios", type=Path, default=DEFAULT_SCENARIOS)
    check.set_defaults(func=cmd_check)

    run = sub.add_parser("run", help="한 바퀴 돌리고 보고서를 쓴다")
    run.add_argument("--scenarios", type=Path, default=DEFAULT_SCENARIOS)
    run.add_argument("--base-url", default="http://127.0.0.1:8000")
    run.add_argument("--out", default="runs")
    run.add_argument("--only", help="쉼표로 구분한 시나리오 id")
    run.add_argument("--limit", type=int)
    run.add_argument("--max-requests", type=int, default=MAX_REQUESTS)
    run.add_argument(
        "--seed", type=int, default=None, help="숨길 슬롯 선택의 난수 시드"
    )
    run.add_argument("--no-judge", action="store_true", help="평가자 LLM 없이 대화만")
    run.add_argument("--davi-repo", default=None, help="davi 체크아웃 경로(sha 기록용)")
    run.add_argument(
        "--dev-token",
        default=None,
        help="X-Davi-Dev-Token 값. 없으면 PINGPONG_DEV_TOKEN 환경변수를 쓴다",
    )
    run.set_defaults(func=cmd_run)

    judge_cmd = sub.add_parser("judge", help="이미 돌린 실행을 나중에 채점한다")
    judge_cmd.add_argument("run", help="runs/<타임스탬프> 경로")
    judge_cmd.add_argument("--scenarios", type=Path, default=DEFAULT_SCENARIOS)
    judge_cmd.add_argument("--difficulty", help="쉬움/중간/어려움 (쉼표 구분)")
    judge_cmd.add_argument("--only", help="쉼표로 구분한 시나리오 id")
    judge_cmd.add_argument("--limit", type=int)
    judge_cmd.add_argument(
        "--dry-run", action="store_true", help="호출 없이 대상만 센다"
    )
    judge_cmd.set_defaults(func=cmd_judge)

    args = parser.parse_args(argv)
    return args.func(args)
