#!/usr/bin/env python3
"""대화 화면(MA-1·3·4·8)의 **시작선**을 상태 조합마다 실기기에서 재서 표로 낸다 (#452).

**왜 스크립트인가.** 지금까지 이 화면의 결함은 사람이 손으로 조합을 골라 띄우다가
**우연히 뜬 화면**이 알려 줬다 — 2026-08-25에 「보기 0개」가 그렇게 나왔고, 같은 날 같은
화면을 여섯 번 고치는 동안 매번 다른 곳이 깨졌다. 조합을 다 돌지 않으면 일곱 번째가 난다.

재는 것은 **시작선 하나**다. 화면 맨 위에 서는 것의 윗변이 상태·배율·기기와 무관하게
같은 값이어야 한다는 것이 #452의 완료 조건이고, 그것이 값인지 아닌지는 표로만 갈린다.

    MA-3(구체화)   칩과 ✕가 카드 상단선에 걸치므로 **「칩·✕의 윗변」**이 시작선
    그 밖의 화면    **카드 상단**이 시작선 (배지가 있으면 그 위에 얹힌다)

**보기는 0~3개다 — 4개짜리 조합은 없다.** 이슈 본문의 격자는 「0·1·3·4개」라고 적었지만
계약이 두 겹으로 3을 상한으로 잡는다(`VoiceDialogueSession.MAX_OPTIONS`,
`SlotFillApiClient.MAX_CLARIFICATION_OPTIONS`). 질문도 같은 이유로 1~2개다
(`MAX_QUESTIONS`) — 그래서 칩 유무가 곧 질문 1개/2개다.

**force-stop을 쓰지 않는다.** 접근성 서비스가 죽으면 시스템이 권한을 내리고 다음 실행이
온보딩으로 튕겨, 재려던 화면 대신 ON-2가 찍힌다(app-android/CLAUDE.md 기기 함정 절).
글꼴 배율을 바꾸면 액티비티가 알아서 다시 만들어지므로 재시작이 필요 없다.

사용법:

    ./scripts/measure-conversation-anchors.py                    # 기본 격자, 표를 stdout에
    ./scripts/measure-conversation-anchors.py --out anchors.md   # 파일로도 남긴다
    ./scripts/measure-conversation-anchors.py --scales 1.0       # 배율 하나만
    ./scripts/measure-conversation-anchors.py --serial R3CX...   # 기기 지정

기기가 없으면 아무것도 못 잰다 — 이 스크립트는 실기기 전용이고, 파싱·판정 부분만
`scripts/tests/test_measure_conversation_anchors.py`가 기기 없이 검증한다.
"""

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

# ── 재는 대상 ────────────────────────────────────────────────────────────────
# 카드 래퍼는 배경만 있는 컨테이너라 접근성 트리에서 빠질 수 있다. 빠지면 값을 지어내지
# 않고 `-`로 남긴다 — 없는 것을 0으로 적으면 표가 조용히 거짓말을 한다.
TRACKED_IDS = (
    "card_column",
    "question_progress",
    "btn_close",
    "card_question_wrap",
    "card_question",
    "btn_question_toggle",
    "btn_option_1",
    "btn_option_2",
    "btn_option_3",
    "btn_direct_speak",
    "btn_mic",
    "logo",
    # 홈에는 질문 카드가 없다 — 맨 위에 서는 것이 제목이다.
    "home_title",
)

DEFAULT_PACKAGE = "com.davi.helper.debug"
ACTIVITY = f"{DEFAULT_PACKAGE}/com.davi.helper.ConversationActivity"
E = "com.davi.helper.extra"

OPTION_LABELS = ("카카오톡", "문자 메시지", "인스타그램")
PROMPT_WITH_OPTIONS = "어느 앱으로 사진을 보낼까요?"
# 보기 0개는 서버가 **선택지를 만들 수 없는 질문**을 줄 때 온다 — 자유 발화형이면 항상 그렇다.
PROMPT_FREEFORM = "어느 지역에서 가장 싼 카페를 찾을까요?"


@dataclass(frozen=True)
class Case:
    """한 번 띄워서 한 번 재는 화면 하나."""

    screen: str
    label: str
    extras: tuple = ()
    options: str = "-"
    chip: str = "-"
    #: 시작선을 카드 상단이 아니라 「칩·✕의 윗변」으로 읽는 화면(MA-3).
    perched: bool = False
    #: 띄운 뒤 스스로 사라지는 화면은 캡처를 서둘러야 한다(메인 8은 자동 복귀한다).
    settle_s: float = 1.2


def clarification_case(option_count: int, question_count: int) -> Case:
    """구체화(MA-3) 한 칸. 보기 0~3개 × 질문 1~2개."""
    labels = "|".join(OPTION_LABELS[:option_count])
    prompt = PROMPT_WITH_OPTIONS if option_count else PROMPT_FREEFORM
    return Case(
        screen="MA-3 구체화",
        label=f"보기 {option_count} · 질문 {question_count}",
        extras=(
            ("--es", f"{E}.DEBUG_CLARIFICATION", labels),
            ("--es", f"{E}.DEBUG_CLARIFICATION_PROMPT", prompt),
            ("--ei", f"{E}.DEBUG_CLARIFICATION_COUNT", str(question_count)),
        ),
        options=str(option_count),
        chip="있음" if question_count > 1 else "없음",
        perched=True,
    )


def default_cases() -> list:
    cases = [
        clarification_case(options, questions)
        for questions in (2, 1)
        for options in (3, 2, 1, 0)
    ]
    cases.append(Case(screen="MA-1 홈", label="기본"))
    cases.append(
        Case(
            screen="MA-4 최종 확인",
            label="짧은 goal",
            extras=(("--es", f"{E}.DEBUG_CONFIRM", "엄마에게 사진 보내기"),),
        )
    )
    cases.append(
        Case(
            screen="MA-4 최종 확인",
            label="긴 goal",
            extras=(
                (
                    "--es",
                    f"{E}.DEBUG_CONFIRM",
                    "서울에서 당진으로 이동하는 가장 빠른 시외버스 시간표를 찾아서 알려 주기",
                ),
            ),
        )
    )
    # 메인 8은 FAILED_RETURN_DELAY_MS 뒤 홈으로 스스로 돌아간다 — 그 안에 재야 한다.
    cases.append(
        Case(
            screen="MA-8 실패",
            label="기본",
            extras=(("--ez", f"{E}.DEBUG_FAILED", "true"),),
            settle_s=0.8,
        )
    )
    return cases


# ── 파싱·판정 (기기 없이 검증되는 부분) ──────────────────────────────────────
@dataclass(frozen=True)
class Bounds:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def height(self) -> int:
        return self.bottom - self.top


def parse_bounds(raw: str):
    """`[l,t][r,b]` 한 덩어리를 [Bounds]로. 형식이 아니면 None."""
    try:
        first, second = raw.replace("[", "").split("]")[:2]
        left, top = (int(v) for v in first.split(","))
        right, bottom = (int(v) for v in second.split(","))
    except (ValueError, IndexError):
        return None
    return Bounds(left, top, right, bottom)


def parse_dump(xml_text: str, package: str = DEFAULT_PACKAGE) -> dict:
    """uiautomator 덤프에서 [TRACKED_IDS]의 뷰 경계만 뽑는다.

    **픽셀이 아니라 뷰 경계로 재는 이유**는 그림자·테두리·안티에일리어싱이 섞여 몇 dp씩
    어긋나기 때문이다(2026-08-19 실측: 마이크 흰 원의 보이는 위쪽을 재서 간격을 62.7dp로
    잘못 읽었다 — 뷰 경계로는 정확히 48dp였다).
    """
    prefix = f"{package}:id/"
    found = {}
    for node in ET.fromstring(xml_text).iter("node"):
        resource_id = node.get("resource-id") or ""
        if not resource_id.startswith(prefix):
            continue
        name = resource_id[len(prefix) :]
        if name not in TRACKED_IDS:
            continue
        bounds = parse_bounds(node.get("bounds") or "")
        # 같은 id가 두 번 나오면 **먼저 나온 것**을 쓴다 — 트리 순서가 곧 z축 순서다.
        if bounds is not None and name not in found:
            found[name] = bounds
    return found


@dataclass
class Row:
    screen: str
    label: str
    options: str
    chip: str
    scale: str
    start_line_dp: float = None
    start_line_source: str = "-"
    card_top_dp: float = None
    close_top_dp: float = None
    chip_top_dp: float = None
    inset_top_dp: float = None
    button_h_dp: str = "-"
    clipped: str = "-"
    missing: tuple = ()
    notes: list = field(default_factory=list)


DIMENS_XML = Path(__file__).resolve().parents[1] / "app/src/main/res/values/dimens.xml"


def read_dimen_dp(name: str, dimens_path=DIMENS_XML) -> float:
    """`dimens.xml`의 dp 토큰 하나를 읽는다.

    **값을 스크립트에 베껴 두지 않는다** — 베끼면 토큰이 바뀌어도 표는 옛 값으로 계속
    「OK」를 낸다. 여기서 읽는 것은 ✕의 **보이는 원**이 뷰보다 얼마나 안쪽인지 하나뿐이다.
    """
    text = dimens_path.read_text(encoding="utf-8")
    found = re.search(rf'<dimen name="{name}">([0-9.]+)dp</dimen>', text)
    if not found:
        raise RuntimeError(f"{dimens_path.name}에서 {name}을 못 찾았다")
    return float(found.group(1))


def dp(px, density: int):
    if px is None:
        return None
    return round(px * 160.0 / density, 2)


#: 높이가 dp로 못 박혀 있는 뷰. **덤프가 돌려주는 경계는 화면 안으로 잘린 값이라**
#: 상태바를 파고든 ✕는 「top = 인셋」으로 보고돼 잘림이 안 보인다 — 실제 윗변은
#: 바닥에서 이 높이만큼 올라간 자리다. (실측: ✕가 56dp인데 40dp로 보고됐다.)
FIXED_HEIGHT_TOKENS = {
    "btn_close": "davi_size_touch_target",
    "question_progress": "davi_step_chip_height",
}


def true_top(bounds, expected_height_px):
    """덤프의 잘린 경계에서 **잘리기 전 윗변**을 되살린다.

    돌려주는 것은 `(윗변, 잘린 높이)`다. 안 잘렸으면 잘린 높이가 0이다.
    """
    if bounds is None:
        return None, 0
    if expected_height_px and bounds.height < expected_height_px - 1:
        return bounds.bottom - expected_height_px, expected_height_px - bounds.height
    return bounds.top, 0


def measure(
    nodes: dict,
    density: int,
    inset_top_px: int,
    screen_height_px: int,
    perched: bool,
    close_circle_inset_px: int = 0,
    fixed_heights_px: dict = None,
) -> dict:
    """한 조합의 덤프에서 시작선과 잘림 여부를 뽑는다.

    시작선은 **화면 맨 위에 서는 것의 윗변**이다. MA-3은 칩·✕가 카드 상단선에 걸쳐
    카드보다 위에 서므로 그 둘 중 위쪽을, 그 밖의 화면은 카드 상단을 본다.

    **✕는 뷰가 아니라 보이는 원의 윗변으로 잰다** — 배경이 `davi_close_circle_inset`만큼
    안으로 들어와 있어서 뷰 경계로 재면 어르신 눈에 안 보이는 6dp가 시작선에 섞인다.
    앱 코드도 칩을 그 원의 윗변에 맞춘다([perchTopControlsOnCard]).

    **잘린 뷰는 잘리기 전 자리로 되돌려 잰다**([true_top]). 그러지 않으면 상태바를 파고든
    ✕가 「인셋에 딱 붙었다」로 보고돼, 시작선이 흔들리는 것도 잘린 것도 표에서 사라진다.
    """
    fixed_heights_px = fixed_heights_px or {}
    card = nodes.get("card_question_wrap")
    close_top, close_cut = true_top(nodes.get("btn_close"), fixed_heights_px.get("btn_close"))
    chip_top, chip_cut = true_top(
        nodes.get("question_progress"), fixed_heights_px.get("question_progress")
    )

    candidates = []
    if perched:
        if chip_top is not None:
            candidates.append(("칩", chip_top))
        if close_top is not None:
            candidates.append(("✕", close_top + close_circle_inset_px))
    if not candidates:
        if card is not None:
            candidates.append(("카드", card.top))
        elif "home_title" in nodes:
            candidates.append(("제목", nodes["home_title"].top))

    start_source, start_px = min(candidates, key=lambda c: c[1]) if candidates else ("-", None)

    # 잘림은 **인셋 위로 나갔는가**로 본다 — 상태바에 덮인 ✕는 화면 안에 있지만 안 보인다.
    reasons = []
    if close_cut:
        reasons.append(f"✕가 {dp(close_cut, density)}dp 잘렸다")
    if chip_cut:
        reasons.append(f"칩이 {dp(chip_cut, density)}dp 잘렸다")
    if start_px is not None and start_px < inset_top_px and not reasons:
        reasons.append(f"{start_source}가 인셋 위로 {dp(inset_top_px - start_px, density)}dp")
    bottom_most = max(
        (b.bottom for name, b in nodes.items() if name.startswith("btn_")),
        default=None,
    )
    if bottom_most is not None and bottom_most > screen_height_px:
        reasons.append(f"버튼이 화면 아래로 {dp(bottom_most - screen_height_px, density)}dp")

    heights = [
        nodes[name].height
        for name in ("btn_option_1", "btn_option_2", "btn_option_3", "btn_direct_speak")
        if name in nodes
    ]
    return {
        "start_line_dp": dp(start_px, density),
        "start_line_source": start_source,
        "card_top_dp": dp(card.top if card else None, density),
        "close_top_dp": dp(
            close_top + close_circle_inset_px if close_top is not None else None, density
        ),
        "chip_top_dp": dp(chip_top, density),
        "button_h_dp": "/".join(str(dp(h, density)) for h in heights) if heights else "-",
        "clipped": " · ".join(reasons) if reasons else "아니오",
    }


def start_line_groups(rows: list) -> dict:
    """화면별로 시작선이 몇 가지 값으로 갈리는지. 값이 하나여야 완료 조건을 만족한다.

    배율마다 따로 묶는다 — 「배율이 달라도 같아야 한다」는 조건이 아니다. 글자가 커지면
    칩도 커지므로 배율 사이의 차이는 정상이고, **같은 배율 안에서 상태가 흔드는 것**이 결함이다.
    """
    groups = {}
    for row in rows:
        if row.start_line_dp is None:
            continue
        groups.setdefault((row.screen, row.scale), set()).add(row.start_line_dp)
    return groups


def verdict_lines(rows: list) -> list:
    """표 아래에 붙는 판정. 사람이 표를 눈으로 훑지 않아도 되게 한다."""
    lines = []
    for (screen, scale), values in sorted(start_line_groups(rows).items()):
        spread = round(max(values) - min(values), 2)
        mark = "OK" if spread == 0 else "흔들림"
        joined = ", ".join(f"{v}dp" for v in sorted(values))
        lines.append(f"- [{mark}] {screen} · 배율 {scale} — 시작선 {joined} (폭 {spread}dp)")
    clipped = [r for r in rows if r.clipped not in ("아니오", "-")]
    for row in clipped:
        lines.append(f"- [잘림] {row.screen} · {row.label} · 배율 {row.scale} — {row.clipped}")
    missing = {name for row in rows for name in row.missing}
    if missing:
        lines.append(f"- [주의] 덤프에서 못 찾은 뷰: {', '.join(sorted(missing))}")
    return lines


def launch_args(case: Case) -> list:
    """`am start` 인자. **문자열 값을 작은따옴표로 한 번 더 감싼다.**

    adb가 argv를 공백으로 이어 붙여 기기 셸에 넘기므로, 감싸지 않으면 `|`가 파이프로
    읽히고(「문자 메시지」가 갈라진다) 빈 문자열은 **인자 자체가 사라진다** — 보기 0개를
    재려던 칸이 조용히 보기 3개로 뜬다.
    """
    args = ["am", "start", "-n", ACTIVITY]
    for flag, key, value in case.extras:
        args.extend([flag, key, f"'{value}'"] if flag == "--es" else [flag, key, value])
    return args


COLUMNS = (
    ("화면", "screen"),
    ("조합", "label"),
    ("보기", "options"),
    ("칩", "chip"),
    ("배율", "scale"),
    ("시작선(dp)", "start_line_dp"),
    ("기준", "start_line_source"),
    ("카드 상단", "card_top_dp"),
    ("✕ 상단", "close_top_dp"),
    ("칩 상단", "chip_top_dp"),
    ("인셋 위", "inset_top_dp"),
    ("버튼 높이", "button_h_dp"),
    ("잘림", "clipped"),
)


def format_table(rows: list) -> str:
    head = "| " + " | ".join(name for name, _ in COLUMNS) + " |"
    rule = "| " + " | ".join("---" for _ in COLUMNS) + " |"
    body = []
    for row in rows:
        cells = []
        for _, attr in COLUMNS:
            value = getattr(row, attr)
            cells.append("-" if value is None else str(value))
        body.append("| " + " | ".join(cells) + " |")
    return "\n".join([head, rule, *body])


def format_report(rows: list, header: str) -> str:
    parts = [header, "", format_table(rows), "", "## 판정", ""]
    parts.extend(verdict_lines(rows) or ["- 잰 것이 없다."])
    return "\n".join(parts) + "\n"


# ── 기기 조작 ────────────────────────────────────────────────────────────────
class Device:
    def __init__(self, adb: str, serial: str = None):
        self._base = [adb] + (["-s", serial] if serial else [])

    def shell(self, *args: str) -> str:
        done = subprocess.run(
            self._base + ["shell", *args],
            check=True,
            capture_output=True,
            text=True,
        )
        return done.stdout

    def pull(self, remote: str, local: str) -> None:
        subprocess.run(self._base + ["pull", remote, local], check=True, capture_output=True)

    def density(self) -> int:
        raw = self.shell("wm", "density")
        # Override가 있으면 그쪽이 실제 값이다.
        lines = [line for line in raw.splitlines() if "density" in line.lower()]
        return int(lines[-1].split(":")[1].strip())

    def screen_height_px(self) -> int:
        raw = self.shell("wm", "size")
        lines = [line for line in raw.splitlines() if "size" in line.lower()]
        return int(lines[-1].split(":")[1].strip().split("x")[1])

    def status_bar_height_px(self) -> int:
        """상태바 높이 = 화면이 물러나야 하는 위쪽 인셋.

        `dumpsys window`의 InsetsSource 중 statusBars 프레임을 읽는다. 못 읽으면 0을
        돌려주지 않고 예외로 세운다 — 인셋을 0으로 두면 「잘림 아니오」가 거짓으로 나온다.
        """
        raw = self.shell("dumpsys", "window")
        # `type=statusBars frame=[0,0][1080,103]` — 안드로이드 판본마다 `mFrame=`으로도 쓴다.
        pattern = re.compile(r"[Tt]ype=statusBars\b.*?[Ff]rame=(\[[^\]]*\]\[[^\]]*\])")
        for line in raw.splitlines():
            found = pattern.search(line)
            if not found:
                continue
            bounds = parse_bounds(found.group(1))
            if bounds is not None and bounds.height > 0:
                return bounds.bottom
        raise RuntimeError("dumpsys window에서 statusBars 인셋을 못 찾았다")

    def font_scale(self) -> str:
        return (self.shell("settings", "get", "system", "font_scale") or "").strip()

    def set_font_scale(self, scale: str) -> None:
        self.shell("settings", "put", "system", "font_scale", scale)

    def launch(self, case: Case) -> None:
        self.shell(*launch_args(case))

    def dump(self, local_path: str) -> str:
        remote = "/sdcard/davi-anchors.xml"
        self.shell("uiautomator", "dump", remote)
        self.pull(remote, local_path)
        with open(local_path, encoding="utf-8") as handle:
            return handle.read()


def resolve_adb() -> str:
    root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    candidates = []
    if root:
        candidates.append(os.path.join(root, "platform-tools", "adb"))
    candidates.append(os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
    for candidate in candidates:
        if os.access(candidate, os.X_OK):
            return candidate
    return "adb"


def run(args) -> int:
    device = Device(resolve_adb(), args.serial)
    density = device.density()
    screen_height = device.screen_height_px()
    inset_top = device.status_bar_height_px()
    close_circle_inset_px = round(read_dimen_dp("davi_close_circle_inset") * density / 160.0)
    fixed_heights_px = {
        view: round(read_dimen_dp(token) * density / 160.0)
        for view, token in FIXED_HEIGHT_TOKENS.items()
    }
    original_scale = device.font_scale()
    cases = default_cases()
    rows = []
    tmp = os.path.join(args.work_dir, "davi-anchors.xml")
    os.makedirs(args.work_dir, exist_ok=True)
    try:
        for scale in args.scales:
            device.set_font_scale(scale)
            # 배율 변경은 액티비티를 다시 만든다 — 다음 주입이 옛 배율에서 재지 않게 기다린다.
            time.sleep(1.0)
            for case in cases:
                device.launch(case)
                time.sleep(case.settle_s)
                nodes = parse_dump(device.dump(tmp), args.package)
                metrics = measure(
                    nodes,
                    density,
                    inset_top,
                    screen_height,
                    case.perched,
                    close_circle_inset_px,
                    fixed_heights_px,
                )
                rows.append(
                    Row(
                        screen=case.screen,
                        label=case.label,
                        options=case.options,
                        chip=case.chip,
                        scale=scale,
                        inset_top_dp=dp(inset_top, density),
                        # **그 화면에 원래 있는 뷰만 「못 찾음」으로 센다** — MA-4에는 ✕가
                        # 없고 홈에는 질문 카드가 없다. 없는 것을 못 찾았다고 적으면
                        # 경고가 매번 떠서 **진짜 못 찾은 날에 아무도 안 본다.**
                        missing=tuple(
                            name
                            for name in (("card_question_wrap", "btn_close") if case.perched else ())
                            if name not in nodes
                        ),
                        **metrics,
                    )
                )
    finally:
        if original_scale and original_scale != "null":
            device.set_font_scale(original_scale)

    header = (
        f"# 대화 화면 시작선 실측 (#452)\n\n"
        f"기기 `{args.serial or 'default'}` · 밀도 {density}dpi · "
        f"화면 높이 {dp(screen_height, density)}dp · 위 인셋 {dp(inset_top, density)}dp"
    )
    report = format_report(rows, header)
    print(report)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(report)
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial", default=None, help="adb 기기 시리얼")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help="덤프에서 찾을 패키지")
    parser.add_argument(
        "--scales",
        default="1.0,2.0",
        type=lambda raw: [v.strip() for v in raw.split(",") if v.strip()],
        help="글꼴 배율 목록 (기본 1.0,2.0)",
    )
    parser.add_argument("--out", default=None, help="표를 남길 마크다운 경로")
    parser.add_argument(
        "--work-dir",
        default="/tmp/davi-anchors",
        help="덤프 XML을 내려받을 임시 폴더",
    )
    args = parser.parse_args(argv)
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
