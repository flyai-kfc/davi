"""명령줄 진입점.

- ``check``  : 키 없이 시나리오 파일만 검증한다. CI에 걸 수 있는 유일한 층이다.
- ``run``    : 사용자 LLM ↔ 핑퐁 ↔ 평가자 LLM 한 바퀴.

키는 환경변수로 읽는다. [load_env]가 진입점에서 `.env`를 한 번 올려 주므로 파일에 적어 두면
된다 — 자세한 것은 그 함수 주석.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import subprocess
import sys
import uuid
from datetime import datetime
from pathlib import Path

from dotenv import dotenv_values

from .judge import judge as run_judge
from .judge import judge_contract
from .llm import ClaudeJudge, GeminiClient, LlmError, estimate_credits
from .metrics import batch_of, in_batch, summarize, transcript_metrics
from .models import JudgeBatch, ScenarioFile, ScenarioResult, StopReason, Transcript
from .report import render_completion_comparison, write_run
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


def _load_results(path: Path) -> list[ScenarioResult]:
    """저장된 transcript를 정본으로 코드 지표를 다시 계산한다.

    #316 지표가 생기기 전 run의 ``metrics``는 새 키가 없다. 예전 dict를
    그대로 쓰면 compare가 관측하지 않은 값을 0으로 보여 준다.
    """

    results = [
        ScenarioResult.model_validate_json(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    for result in results:
        result.metrics = transcript_metrics(result.transcript)
    return results


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
    if not path.is_file() or not (run_dir / "meta.json").is_file():
        print(
            f"{run_dir} 는 실행 디렉터리가 아니다 — "
            "transcripts.jsonl 과 meta.json 이 있는 runs/<타임스탬프> 를 준다.",
            file=sys.stderr,
        )
        return 1
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
    results = _load_results(path)

    picked = results
    if args.difficulty:
        wanted = set(args.difficulty.split(","))
        picked = [r for r in picked if r.difficulty.value in wanted]
    if args.only:
        wanted = set(args.only.split(","))
        picked = [r for r in picked if r.scenario_id in wanted]
    if args.limit:
        picked = picked[: args.limit]
    if not picked:
        # 선택 오타가 자동화에서 성공으로 보이면 안 되고, 빈 채점이 meta·보고서를
        # 다시 쓰게 둬서도 안 된다 — 평가자 생성 전에 막는다.
        print(
            "채점할 대상이 없다 — --difficulty/--only/--limit 을 확인한다",
            file=sys.stderr,
        )
        return 1

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

    batch = JudgeBatch(
        model=judge_client.model,
        scenario_digest=current,
        judge_contract=judge_contract(),
    )
    judge_failures = 0
    for result in picked:
        # 재채점 대상의 이전 채점은 재시도 전에 비운다 — 실패했는데 남아 있으면
        # 옛 점수가 이번 채점(meta의 현재 평가자 모델·해시)으로 집계된다.
        result.judge = None
        result.judge_batch = None
        scenario = scenarios.get(result.scenario_id)
        if scenario is None:
            # 드리프트를 알고 진행했거나 해시가 없는 옛 실행이면 여기 올 수 있다.
            print(
                f"[{result.scenario_id}] 시나리오 파일에 없는 id라 채점할 수 없다",
                file=sys.stderr,
            )
            judge_failures += 1
            continue
        try:
            result.judge = run_judge(judge_client, scenario, result.transcript)
        except LlmError as error:
            print(f"[{result.scenario_id}] 채점 실패: {error}", file=sys.stderr)
            judge_failures += 1
            continue
        result.judge_batch = batch
        print(
            f"{result.scenario_id} [{result.difficulty.value}] {result.judge.achievement.value}"
        )

    # 선택 밖 결과에 다른 배치(다른 평가자 모델·시나리오 해시·채점 계약)의 채점이 남아 있으면
    # 저장은 유지하되 집계에서 빠진다 — 조용히 섞이는 것도, 조용히 사라지는 것도 안 된다.
    #
    # **배치 튜플을 손으로 조립하지 않는다**(PR #448 리뷰). 축이 하나 늘었을 때 이 자리만 옛
    # 길이로 남아 길이 불일치로 **전부 stale**이 됐다 — 집계는 멀쩡한데 경고만 "30건 제외"를
    # 찍는 모양이라 로그로도 안 드러난다.
    batch_key = (batch.model, batch.scenario_digest, batch.judge_contract)
    stale = sum(
        1 for r in results if r.judge is not None and not in_batch(r, batch_key)
    )
    if stale:
        print(
            f"다른 배치에서 채점된 {stale}건은 이번 집계에서 제외했다"
            "(보고서 시나리오별 표에 '다른 배치'로 표시). 같은 수치로 합치려면 "
            "같은 평가자·시나리오로 다시 채점한다.",
            file=sys.stderr,
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
            "채점계약": judge_contract(),
        }
    )
    report = write_run(run_dir, results, meta)
    print(f"\n보고서 갱신: {report}")
    print(f"이번 채점 추정 크레딧: {meta['평가자_추정크레딧']}")
    if judge_failures:
        print(f"채점 실패 {judge_failures}건 (보고서에는 남겼다)", file=sys.stderr)
        return 1
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

    # Random(None)은 고정된 None 시드가 아니라 매번 새 시스템 엔트로피를 쓴다.
    # None을 meta에 남기면 다른 난수열 두 실행이 같은 입력으로 비교되므로,
    # 실제로 쓴 정수를 한 번 확정해 RNG와 기록에 같이 쓴다.
    seed = (
        args.seed
        if args.seed is not None
        else random.SystemRandom().randrange(0, 2**63)
    )
    user = UserSimulator(user_client, rng=random.Random(seed))
    results = []
    openings: list[str] = []
    digest = scenario_digest(args.scenarios)
    judge_failures = 0

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
                max_requests=args.max_requests,
            )
        except (PingpongError, LlmError) as error:
            # 한 건이 죽어도 나머지를 계속 돌린다. 실패도 결과다 — 보고서에 남긴다.
            # 지표는 성공 경로와 같은 계약으로 만든다 — 키가 다르면 집계·표가 깨진다.
            print(f"[{scenario.id}] 건너뜀: {str(error)[:120]}", file=sys.stderr)
            transcript = Transcript(
                scenario_id=scenario.id,
                opening="",
                stop_reason=StopReason.ERROR,
                error=str(error)[:500],
            )
            results.append(
                ScenarioResult(
                    scenario_id=scenario.id,
                    difficulty=scenario.difficulty,
                    factors=scenario.factors,
                    transcript=transcript,
                    metrics=transcript_metrics(transcript),
                )
            )
            continue
        finally:
            adapter.close()

        openings.append(result.transcript.opening)
        if judge_client is not None:
            try:
                result.judge = run_judge(judge_client, scenario, result.transcript)
                result.judge_batch = JudgeBatch(
                    model=judge_client.model,
                    scenario_digest=digest,
                    judge_contract=judge_contract(),
                )
            except LlmError as error:
                print(f"[{scenario.id}] 채점 실패: {error}", file=sys.stderr)
                judge_failures += 1
        results.append(result)
        achievement = result.judge.achievement.value if result.judge else "미채점"
        print(
            f"{scenario.id} [{scenario.difficulty.value}] "
            f"질문 {result.metrics['질문수']}개 · {result.metrics['종료사유']} · {achievement}"
        )

    meta = {
        "실행시각": datetime.now().astimezone().isoformat(timespec="seconds"),
        "시나리오파일": str(args.scenarios),
        "시나리오해시": digest,
        "시나리오수": len(scenarios),
        "사용자모델": user_client.model,
        "평가자모델": judge_client.model if judge_client else None,
        "채점계약": judge_contract() if judge_client else None,
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
        "seed": seed,
        "max_requests": args.max_requests,
    }
    out_dir = Path(args.out) / datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    path = write_run(out_dir, results, meta)
    print(f"\n보고서: {path}")
    print(json.dumps(_headline(results), ensure_ascii=False, indent=2))

    # 한 건씩 계속 돌리되, 실패가 있었다는 사실은 exit code로 드러낸다 —
    # 0으로 끝나면 스모크·자동화가 깨진 실행을 정상 기준선으로 오해한다.
    run_failures = sum(
        1 for r in results if r.transcript.stop_reason is StopReason.ERROR
    )
    if run_failures or judge_failures:
        print(
            f"실패 포함: 실행 {run_failures}건 · 채점 {judge_failures}건 "
            "(보고서에는 남겼다)",
            file=sys.stderr,
        )
        return 1
    return 0


def _load_run(run_dir: Path) -> tuple[list[ScenarioResult], dict]:
    transcript_path = run_dir / "transcripts.jsonl"
    meta_path = run_dir / "meta.json"
    if not transcript_path.is_file() or not meta_path.is_file():
        raise ValueError(f"{run_dir} 는 실행 디렉터리가 아니다")
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    results = _load_results(transcript_path)
    return results, meta


def cmd_compare(args: argparse.Namespace) -> int:
    """두 실행에서 #316 완료 지표만 비교한다."""

    try:
        baseline_results, baseline_meta = _load_run(Path(args.baseline))
        candidate_results, candidate_meta = _load_run(Path(args.candidate))
    except (ValueError, OSError, json.JSONDecodeError) as error:
        print(str(error), file=sys.stderr)
        return 1

    input_mismatches = []
    baseline_digest = baseline_meta.get("시나리오해시")
    candidate_digest = candidate_meta.get("시나리오해시")
    if (
        not baseline_digest
        or not candidate_digest
        or baseline_digest != candidate_digest
    ):
        input_mismatches.append("시나리오 파일 해시")
    if [r.scenario_id for r in baseline_results] != [
        r.scenario_id for r in candidate_results
    ]:
        input_mismatches.append("실행한 시나리오 id·순서")
    for key in ("seed", "max_requests"):
        baseline_value = baseline_meta.get(key)
        candidate_value = candidate_meta.get(key)
        if (
            baseline_value is None
            or candidate_value is None
            or baseline_value != candidate_value
        ):
            input_mismatches.append(key)
    if input_mismatches and not args.allow_scenario_drift:
        print(
            f"A/B 입력 조건이 다르거나 기록되지 않았다: {', '.join(input_mismatches)}. "
            "알고 비교하려면 --allow-scenario-drift 를 쓴다.",
            file=sys.stderr,
        )
        return 1
    if input_mismatches:
        print(
            f"⚠️ A/B 입력 조건 불일치를 알고 비교했다: {', '.join(input_mismatches)}",
            file=sys.stderr,
        )

    baseline_batch = batch_of(baseline_meta)
    candidate_batch = batch_of(candidate_meta)
    if (
        baseline_batch is not None
        and candidate_batch is not None
        and baseline_batch != candidate_batch
    ):
        print(
            "평가자 모델·채점 시나리오·채점 계약 배치가 다르다 — goal·환각·선택 강요를 "
            f"A/B로 묶을 수 없다(기준 {baseline_batch} / 후보 {candidate_batch}). "
            "같은 배치로 다시 채점한다. 모델과 해시가 같은데 걸린다면 채점 계약이 다른 "
            "것이고, 그 필드가 생기기 전의 실행은 값이 비어 있어 재채점 없이는 못 맞댄다.",
            file=sys.stderr,
        )
        return 1

    baseline = summarize(
        baseline_results,
        batch=baseline_batch,
    )["전체"]
    candidate = summarize(
        candidate_results,
        batch=candidate_batch,
    )["전체"]
    report = render_completion_comparison(
        args.baseline_name,
        baseline,
        args.candidate_name,
        candidate,
        input_mismatches=input_mismatches,
    )
    if args.out:
        Path(args.out).write_text(report, encoding="utf-8")
        print(f"비교 보고서: {args.out}")
    else:
        print(report)
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


ENV_FILE = Path(__file__).resolve().parents[1] / ".env"


def load_env(path: Path | None = None) -> None:
    """`eval/pingpong/.env`를 환경변수로 올린다.

    키를 읽는 곳이 셋인데(`GeminiClient`·`ClaudeJudge`가 쓰는 anthropic SDK·개발 토큰) 셋 다
    ``os.getenv``만 본다. **이 호출이 없으면 `.env.example`을 복사해도 아무 효과가 없고**,
    README가 안내하는 준비 절차가 성립하지 않는다.

    **이미 있는 값은 덮지 않는다** — 한 번만 다른 모델·게이트웨이로 돌려 볼 때 파일을 고쳤다
    되돌리는 절차가 생기지 않게 한다. 파일이 없으면 조용히 넘어간다.

    **빈 값은 올리지 않는다.** `.env.example`은 키 자리가 비어 있어, 복사만 하고 한쪽을 안 채우면
    「키 없음」이 아니라 「빈 키」가 된다. **지금 소비자 중 빈 값에 의미를 두는 것은 없고**
    (키는 명시적으로 막고, 모델·base_url·토큰은 `.strip() or <기본값>`이다), 그래서 올릴 이유가
    없다 — 복사만 한 상태와 파일이 없는 상태가 같은 환경이 되는 쪽이 단순하다.

    **`${VAR}` 보간에서는 파일 값이 이긴다** — `dotenv_values()`가 내부적으로 ``override=True``로
    보간해서다(PR #477 리뷰 실측). 위 「이미 있는 값은 덮지 않는다」는 보간 결과에는 적용되지
    않는다. 지금 `.env.example`에 보간이 없어 걸릴 자리가 없다.

    기본 경로를 인자 기본값으로 굳히지 않는 것은 테스트가 [ENV_FILE]을 갈아끼울 수 있게
    하려는 것이다.
    """
    for key, value in dotenv_values(path or ENV_FILE).items():
        if value:
            os.environ.setdefault(key, value)


def main(argv: list[str] | None = None) -> int:
    load_env()
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
    judge_cmd.add_argument(
        "--allow-scenario-drift",
        action="store_true",
        help="시나리오 파일이 실행 당시와 달라도 알고 채점한다(수치 비교 불가)",
    )
    judge_cmd.set_defaults(func=cmd_judge)

    compare = sub.add_parser("compare", help="#316 완료 지표로 두 실행을 A/B 비교한다")
    compare.add_argument("baseline", help="기준 runs/<타임스탬프>")
    compare.add_argument("candidate", help="후보 runs/<타임스탬프>")
    compare.add_argument("--baseline-name", default="기존 자유 발화")
    compare.add_argument("--candidate-name", default="#316 질문 계획")
    compare.add_argument("--out", help="Markdown 출력 경로")
    compare.add_argument(
        "--allow-scenario-drift",
        action="store_true",
        help="해시·실행 id·seed·요청 상한이 달라도 알고 비교한다",
    )
    compare.set_defaults(func=cmd_compare)

    args = parser.parse_args(argv)
    return args.func(args)
