r"""`loop_axes` 로그를 #429 「잴 것」 네 가지 수치로 접는다. **개발 전용**(dev-only).

서버가 요청마다 남기는 `loop_axes` 이벤트만 읽는다. 판정은 서버가 이미 했고 여기서는 세기만
한다 — 세는 쪽이 다시 판정하면 두 곳이 갈린다(`app/loop_axes.py` 머리말과 같은 이유).

세션 경계는 **`steps=0`인 요청**이다. 이력이 빈 요청은 그 세션의 첫 안내라, 로그에 세션 id가
없어도(비식별 규약상 넣지 않는다) 회차를 가를 수 있다. 첫 줄이 `steps>0`이면 앞이 잘린
회차이므로 그것도 한 세션으로 센다.

**재전송은 하나로 접는다.** 앱은 network 실패와 retryable 429/502에서 같은
`logical_request_id`로 다시 보내고(`RequestTracing.kt`) 서버는 HTTP 전송마다 계측을 남긴다.
접지 않으면 502가 여섯 번 난 화면 하나가 발화 여섯 건이 되고(#411 2번이 실측한 모양),
첫 요청이 재전송되면 `steps=0`이 여러 번 나와 세션 수까지 부풀어 ①의 분모가 무너진다.

    python scripts/loop_axes_report.py %TEMP%\davi-server.err
"""

from __future__ import annotations

import json
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Session:
    events: list[dict] = field(default_factory=list)


def read_events(paths: list[Path]) -> list[dict]:
    events: list[dict] = []
    for path in paths:
        for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw.strip()
            if not line.startswith("{"):
                continue
            try:
                payload = json.loads(line)
            except ValueError:
                continue
            if payload.get("event") == "loop_axes":
                events.append(payload)
    return events


def collapse_retries(events: list[dict]) -> list[dict]:
    """같은 논리 요청의 재전송을 첫 건으로 접는다. id가 없는(낡은 앱) 줄은 그대로 둔다."""

    seen: set[str] = set()
    collapsed: list[dict] = []
    for event in events:
        logical = event.get("logical_request_id")
        if isinstance(logical, str):
            if logical in seen:
                continue
            seen.add(logical)
        collapsed.append(event)
    return collapsed


def split_sessions(events: list[dict]) -> list[Session]:
    sessions: list[Session] = []
    for event in events:
        if event.get("steps") == 0 or not sessions:
            sessions.append(Session())
        sessions[-1].events.append(event)
    return sessions


def _cross(events: list[dict]) -> Counter:
    return Counter(
        (bool(e.get("loop_tried")), bool(e.get("loop_returned")))
        for e in events
        if e.get("loop_tried") or e.get("loop_returned")
    )


def _cross_lines(title: str, cross: Counter) -> list[str]:
    return [
        f"   {title}",
        f"     tried=만 켜짐      {cross[(True, False)]}건",
        f"     회귀 표시만 켜짐   {cross[(False, True)]}건",
        f"     둘 다 켜짐         {cross[(True, True)]}건",
    ]


def report(events: list[dict]) -> str:
    if not events:
        return "loop_axes 이벤트가 없다. 서버가 이 브랜치 코드로 돌았는지 확인할 것."

    events = collapse_retries(events)
    sessions = split_sessions(events)
    fired = [e for e in events if e.get("axes_all")]
    fired_observed = [e for e in events if e.get("axes_all_observed")]
    fired_narrow = [e for e in events if e.get("axes_all_narrow")]
    # 반복 대상이 실제로 특정되는 것만 센 하한. `target_identifiable`은 「대상이 하나라도
    # 있다」라 회귀 단독 분기에서 한 번만 안내한 요소도 통과한다.
    fired_strict = [e for e in fired if e.get("target_repeat_identifiable")]
    per_session = [sum(1 for e in s.events if e.get("axes_all")) for s in sessions]
    identifiable = [e for e in events if e.get("target_identifiable")]

    lines = [
        f"논리 요청 {len(events)}건 · 세션 {len(sessions)}개 (재전송은 접었다)",
        "",
        "① 세 축이 모두 참인 자리",
        f"   {len(fired)}건 / {len(events)}요청"
        f" · 세션당 평균 {len(fired) / len(sessions):.1f}회"
        f" · 세션당 최대 {max(per_session, default=0)}회",
        f"   그중 반복 대상이 특정되는 것 {len(fired_strict)}건 (하한)",
        "",
        "② 정당한 반복 판정 대상 — 아래를 화면 캡처와 대조해 센다",
        "   (이 비율이 배제의 오발화 상한이다. 로그만으로는 못 가른다)",
    ]
    lines += [
        f"   - {e.get('timestamp', '?')}"
        f" logical={e.get('logical_request_id', '-')}"
        f" request={e.get('request_id', '?')}"
        f" tried_max={e.get('tried_max')} returned={e.get('returned_marks')}"
        for e in fired[:50]
    ] or ["   - (없음)"]
    if len(fired) > 50:
        lines.append(f"   … 외 {len(fired) - 50}건")

    lines += ["", "③ 두 루프 신호가 갈리는가"]
    # **거르지 않은 표가 먼저다.** 대상 특정이 안 되는 구간(구조 서명이 매번 다른 화면)이
    # 정확히 #429 ③이 지목한 사각지대라, 거기서 회귀 단독이 얼마나 서는지가 답의 핵심이다.
    lines += _cross_lines("전체", _cross(events))
    lines += _cross_lines(f"대상 특정되는 {len(identifiable)}건만", _cross(identifiable))
    lines += ["   → 「둘 다 켜짐」만 있으면 논리합이 필요 없다"]

    lines += [
        "",
        "④ 인과 축을 넓혔을 때",
        f"   좁은 축 (performed=true)       {len(fired_narrow)}건",
        f"   중간 축 (performed이 관측됨)   {len(fired_observed)}건",
        f"   넓힌 축 (∨ screen_changed)     {len(fired)}건",
        f"   → 관측까지 넓혀 살아난 발화 {len(fired_observed) - len(fired_narrow)}건"
        f" · screen_changed까지 넓혀 {len(fired) - len(fired_observed)}건",
        "   (넓힌 축은 구조 변화에 인과 게이트가 없어 과대 계상이다 — #456)",
    ]
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    paths = [Path(a) for a in argv]
    missing = [p for p in paths if not p.is_file()]
    if missing:
        print("없는 파일: " + ", ".join(str(p) for p in missing))
        return 2
    print(report(read_events(paths)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
