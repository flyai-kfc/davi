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

**고정 대기로 재지 않는다.** 칸마다 「맨 위에 설 뷰」를 정해 두고 그것이 덤프에 뜰 때까지
다시 잰다([Case.expect]) — 느린 기기에서는 전이 중간이 찍혀 칸이 통째로 빈다(실측
S23 Ultra: MA-1·MA-4가 그랬고, 같은 격자가 S24에서는 멀쩡했다). 그래도 못 보면 표에
`-`를 남기고 **판정이 「못 잼」으로 부른다** — 재는 데 실패한 것과 이상이 없는 것은
표에서 구분돼야 한다.

사용법:

    ./scripts/measure-conversation-anchors.py                    # 기본 격자, 표를 stdout에
    ./scripts/measure-conversation-anchors.py --out anchors.md   # 파일로도 남긴다
    ./scripts/measure-conversation-anchors.py --scales 1.0       # 배율 하나만
    ./scripts/measure-conversation-anchors.py --serial R3CX...   # 기기 지정

**판정이 빨간불이어도 종료코드는 0이다** — 사람이 표를 읽는 도구다. 회귀 관문으로 쓸 거면
그때 종료코드를 갈라야 한다(그 전에는 아무도 이 값을 믿으면 안 된다).

**맥·리눅스 전용이다** — `adb`를 `~/Library/Android/sdk` 또는 `ANDROID_HOME`에서 찾고
임시 폴더 기본값이 `/tmp`다. Windows에서는 첫 호출에서 죽는다.

기기가 없으면 아무것도 못 잰다 — 이 스크립트는 실기기 전용이고, 파싱·판정 부분만
`scripts/tests/test_measure_conversation_anchors.py`가 기기 없이 검증한다.
"""

import argparse
import os
import re
import shlex
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
    #: 청취 화면에만 있다 — 「다 섰다」의 기준으로 쓴다.
    "card_utterance_wrap",
    "btn_mic",
    "logo",
    # 홈에는 질문 카드가 없다 — 맨 위에 서는 것이 제목이다.
    "home_title",
)

DEFAULT_PACKAGE = "com.davi.helper.debug"
E = "com.davi.helper.extra"


def activity_for(package: str) -> str:
    """띄울 대상. **덤프를 뒤지는 접두어와 같은 값에서 나와야 한다** — 예전에는 이것이
    상수라, `--package`를 주면 debug 앱을 띄워 놓고 **다른 접두어로 덤프를 뒤져** 표가
    통째로 비었다."""
    return f"{package}/com.davi.helper.ConversationActivity"

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
    #: **이 뷰가 덤프에 뜰 때까지 기다린다.** 고정 대기로는 느린 기기에서 전이 중간이
    #: 찍힌다(실측 S23 Ultra: MA-1·MA-4 칸이 통째로 비었다). 화면마다 맨 위에 서는
    #: 것이 다르므로 칸이 스스로 무엇을 기다릴지 안다.
    expect: str = "card_question_wrap"
    #: 시작선을 토큰에서 재생하는 사슬. 있으면 표가 **절대값까지** 못 박는다.
    #: MA-3만 사슬을 유도해 뒀다 — 다른 화면은 아직 「칸 사이가 같은가」만 본다.
    start_line_chain: tuple = None
    #: 화면이 선 뒤 누를 뷰. **주입만으로 못 닿는 화면이 있다** — 청취는 「직접 말하기」를
    #: 거쳐야 오고, 그 전이는 세션이 아니라 화면 상태만 보므로 주입한 화면에서도 탄다.
    tap: str = None
    #: 누른 뒤 기다릴 뷰. [tap]이 있으면 이쪽이 「다 섰다」의 기준이 된다.
    expect_after: str = None
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
        expect="btn_close",
        start_line_chain=MA3_START_LINE_CHAIN,
    )


def listening_case(question_count: int) -> Case:
    """「직접 말하기」 청취(MA-3에서 이어지는 화면).

    **여기가 상쇄 토큰이 살아 있는 화면이다** — 구체화에서 배지를 뺀 뒤에도 이쪽은 배지가
    서고 배지 위 여백이 카드를 잡는다. 칩이 서고 안 서고에 따라 카드가 뛰는지를 **여기서도**
    봐야 #452의 「자리를 남긴다」가 이 화면을 안 깼다고 말할 수 있다.

    **음성 인식은 끈다.** 켜면 마이크가 열리고, 아무도 말하지 않으면 곧 오류로 구체화로
    되돌아가 재려던 화면이 사라진다. 상태 전이는 그대로 타므로 배치는 진짜다.
    """
    labels = "|".join(OPTION_LABELS)
    return Case(
        screen="MA-3 청취(직접 말하기)",
        label=f"질문 {question_count}",
        extras=(
            ("--es", f"{E}.DEBUG_CLARIFICATION", labels),
            ("--es", f"{E}.DEBUG_CLARIFICATION_PROMPT", PROMPT_WITH_OPTIONS),
            ("--ei", f"{E}.DEBUG_CLARIFICATION_COUNT", str(question_count)),
            ("--ez", f"{E}.INSTRUMENTATION_SKIP_SPEECH_RECOGNIZER", "true"),
        ),
        options="3",
        chip="있음" if question_count > 1 else "없음",
        # **✕가 걸치지 않는 화면이다** — 걸치기는 구체화에서만 한다. 시작선은 카드 상단이다.
        perched=False,
        expect="btn_direct_speak",
        tap="btn_direct_speak",
        expect_after="card_utterance_wrap",
    )


def default_cases() -> list:
    cases = [
        clarification_case(options, questions)
        for questions in (2, 1)
        for options in (3, 2, 1, 0)
    ]
    cases.extend(listening_case(questions) for questions in (2, 1))
    cases.append(Case(screen="MA-1 홈", label="기본", expect="home_title"))
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
        # 같은 id가 두 번 나오면 **먼저 나온 것**을 쓴다. 이 화면에서 그럴 일이 없어서
        # 고른 규칙일 뿐이고 **z축과는 무관하다** — 트리 순서로 z를 읽으면 틀린다(같은 부모
        # 아래서는 뒤에 오는 노드가 위에 그려지고, 칩은 elevation까지 갖고 있어 아예 따로 논다).
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
    #: 이 칸이 뜨기를 기다린 뷰. 「못 잼」 줄이 무엇을 못 봤는지 말해 준다.
    expected: str = None
    #: 토큰 사슬이 말하는 시작선. 잰 값과 어긋나면 구조가 아니라 **넘침**이 붙잡고 있던 것이다.
    expected_start_line_dp: float = None
    #: 마지막 덤프에서 실제로 본 뷰들. **못 잰 칸에서 이게 없으면 다음 사람도 똑같이
    #: 추측만 한다** — 다른 화면이 찍혔는지, 트리가 덜 만들어졌는지가 여기서 갈린다.
    seen: tuple = ()
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
    inset_bottom_px: int = 0,
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
    # **걸치는 화면이 칩·✕를 못 찾으면 카드로 갈아타지 않는다**(#458 리뷰). 갈아타면 행이
    # 비지 않아 「못 잼」 그물을 통과하고, 대신 토큰 사슬이 **재는 데 실패한 것을 레이아웃
    # 결함으로** 신고한다 — 조용하지는 않은데 진단이 틀린다. 카드 상단은 자기 열에 그대로
    # 남으므로 잃는 정보도 없다.
    if not candidates and not perched:
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
    # **아래도 인셋으로 잰다** — 내비게이션 바에 깔린 버튼은 화면 안에 있지만 안 보인다.
    floor_px = screen_height_px - inset_bottom_px
    if bottom_most is not None and bottom_most > floor_px:
        reasons.append(f"버튼이 인셋 아래로 {dp(bottom_most - floor_px, density)}dp")

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


#: MA-3 시작선을 **토큰에서 재생하는 사슬**. 합이 「인셋 위로 몇 dp인가」다.
#:
#: **폭만 재면 못 잡는 것이 있다.** 표의 `폭 0.0dp`는 칸 **사이의** 차이만 보므로, 모든 칸이
#: 똑같이 밀리면 여전히 초록이다. 그런데 MA-3에서 시작선을 붙잡고 있는 것은 구조가 아니라
#: **칸이 넘친다는 사실**이다 — 카드는 weight 0이고, 여유가 생기면 칩 자리와 카드 사이의
#: `space_flex_top`(weight 2)이 그 1/3을 먹어 시작선을 통째로 아래로 민다. 절대값을 함께
#: 못 박아야 그 순간이 잡힌다(화면이 커지거나 조작 영역이 줄면 온다).
#:
#: **값을 베끼지 않는다** — 다섯 토큰을 `dimens.xml`에서 읽어 더한다.
MA3_START_LINE_CHAIN = (
    ("davi_step_row_margin_top", 1),      # 칩 줄이 인셋에서 떨어진 만큼
    ("davi_step_chip_height", 1),         # 칩이 먹는 높이 — 배율을 안 탄다(고정 토큰)
    ("davi_spacing_md", 1),               # 구체화의 카드 위 마진
    ("davi_size_touch_target", -0.5),     # ✕는 카드 상단선에 **중심**을 맞춘다
    ("davi_close_circle_inset", 1),       # 재는 것은 뷰가 아니라 **보이는 원**의 윗변
)


def chain_dp(chain, dimens_path=DIMENS_XML) -> float:
    """사슬을 dp 하나로 접는다."""
    return round(sum(read_dimen_dp(name, dimens_path) * mult for name, mult in chain), 2)


#: 글꼴 배율을 바꾼 뒤 기다리는 시간. 위 [launch_until_ready]의 재시도보다 앞선다.
SCALE_SETTLE_S = 3.0

#: 3x 기기에서 1px = 0.33dp다. **레이아웃 반올림까지 흔들림으로 부르지 않는다** — 칸마다
#: 1px씩 갈리는 것은 결함이 아니고, 그것을 흔들림으로 세면 다 고치고도 초록이 안 뜬다.
SPREAD_TOLERANCE_DP = 0.5


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
        mark = "OK" if spread <= SPREAD_TOLERANCE_DP else "흔들림"
        joined = ", ".join(f"{v}dp" for v in sorted(values))
        lines.append(f"- [{mark}] {screen} · 배율 {scale} — 시작선 {joined} (폭 {spread}dp)")
    # **절대값도 못 박는다** — 폭만 보면 모든 칸이 함께 밀린 날에도 초록이 뜬다.
    for row in rows:
        if row.expected_start_line_dp is None or row.start_line_dp is None:
            continue
        gap = round(row.start_line_dp - row.expected_start_line_dp, 2)
        if abs(gap) > SPREAD_TOLERANCE_DP:
            lines.append(
                f"- [어긋남] {row.screen} · {row.label} · 배율 {row.scale} — "
                f"시작선 {row.start_line_dp}dp인데 토큰 사슬은 "
                f"{row.expected_start_line_dp}dp다 ({gap:+}dp)"
            )
    # **못 잰 칸을 판정에서 빼지 않는다.** 빼면 표에 `-`만 한 줄 남고 판정은 조용해서,
    # 「재는 데 실패했다」가 「이상 없다」와 구분되지 않는다. `am start`는 화면을 못 띄우고도
    # 종료코드 0을 준다.
    for row in rows:
        if row.start_line_dp is None:
            seen = ", ".join(row.seen) if row.seen else "아무것도"
            lines.append(
                f"- [못 잼] {row.screen} · {row.label} · 배율 {row.scale} — "
                f"시작선을 못 찾았다 (기다린 뷰: {row.expected or '-'} · 본 것: {seen})"
            )
    clipped = [r for r in rows if r.clipped not in ("아니오", "-")]
    for row in clipped:
        lines.append(f"- [잘림] {row.screen} · {row.label} · 배율 {row.scale} — {row.clipped}")
    missing = {name for row in rows for name in row.missing}
    if missing:
        lines.append(f"- [주의] 덤프에서 못 찾은 뷰: {', '.join(sorted(missing))}")
    return lines


def launch_args(case: Case, package: str = DEFAULT_PACKAGE) -> list:
    """`am start` 인자. **문자열 값을 작은따옴표로 한 번 더 감싼다.**

    adb가 argv를 공백으로 이어 붙여 기기 셸에 넘기므로, 감싸지 않으면 `|`가 파이프로
    읽히고(「문자 메시지」가 갈라진다) 빈 문자열은 **인자 자체가 사라진다** — 보기 0개를
    재려던 칸이 조용히 보기 3개로 뜬다.
    """
    args = ["am", "start", "-n", activity_for(package)]
    for flag, key, value in case.extras:
        args.extend([flag, key, shlex.quote(value)] if flag == "--es" else [flag, key, value])
    return args


COLUMNS = (
    ("화면", "screen"),
    ("조합", "label"),
    ("보기", "options"),
    ("칩", "chip"),
    ("배율", "scale"),
    ("시작선(dp)", "start_line_dp"),
    ("토큰 사슬", "expected_start_line_dp"),
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

    def system_bar_height_px(self, bar: str) -> int:
        """시스템 바 하나의 높이 = 화면이 그만큼 물러나야 하는 인셋.

        **위아래를 같은 자로 잰다.** 예전에는 위만 인셋으로 보고 아래는 화면 물리 높이로
        봐서, **내비게이션 바에 깔린 버튼이 「잘림 아니오」로 나왔다** — 상태바에 덮인 ✕와
        똑같이 안 보이는데도. 「바로 말하기」가 맨 아래 버튼이라 실제로 걸릴 자리다.

        못 읽으면 0을 돌려주지 않고 예외로 세운다 — 인셋을 0으로 두면 잘림이 사라진다.
        """
        raw = self.shell("dumpsys", "window")
        # `type=statusBars frame=[0,0][1080,103]` — 안드로이드 판본마다 `mFrame=`으로도 쓴다.
        pattern = re.compile(rf"[Tt]ype={bar}\b.*?[Ff]rame=(\[[^\]]*\]\[[^\]]*\])")
        for line in raw.splitlines():
            found = pattern.search(line)
            if not found:
                continue
            bounds = parse_bounds(found.group(1))
            if bounds is not None and bounds.height > 0:
                return bounds
        raise RuntimeError(f"dumpsys window에서 {bar} 인셋을 못 찾았다")

    def status_bar_height_px(self) -> int:
        """위쪽 인셋 — 상태바 **아래**가 내용이 시작할 수 있는 첫 줄이다."""
        return self.system_bar_height_px("statusBars").bottom

    def navigation_bar_height_px(self) -> int:
        """아래쪽 인셋 — 내비게이션 바 **높이**만큼 화면 바닥에서 물러나야 한다."""
        return self.system_bar_height_px("navigationBars").height

    def screen_state(self) -> tuple:
        """`(깨어 있는 것을 봤는가, 잠금이 풀린 것을 봤는가)`.

        **둘 다 「봤을 때만 참」이다 — `=true`를 찾지 않는다.** 그 필드가 없는 기기에서는
        매칭이 실패해 **가드가 조용히 열리고**, 그것이 바로 이 가드가 막으려던 실패 모양이다
        (`app-android/CLAUDE.md` 잠금 함정 절). 필드 자체는 SM-S921N에서만 확인됐고 다른
        기기는 안 봤다 — 없을 수 있다는 뜻이다.

        `svc power stayon usb`를 그 문단에서 가져왔으면서 **이쪽 절반을 놓쳤었다**(#458 리뷰).
        """
        raw = self.shell("dumpsys", "window")
        awake = "mAwake=true" in raw
        unlocked = "mDreamingLockscreen=false" in raw
        return awake, unlocked

    def stay_on_setting(self) -> str:
        """지금의 `stay_on_while_plugged_in` 값(비트마스크). 되돌릴 때 쓴다."""
        return (self.shell("settings", "get", "global", "stay_on_while_plugged_in") or "").strip()

    def keep_screen_on(self) -> None:
        """USB로 붙어 있는 동안 화면을 안 끄게 한다.

        **격자 한 판이 기기의 화면 꺼짐 시간보다 길다.** S23 Ultra는 30초로 잡혀 있었고,
        앞선 기기를 재는 10분 사이에 잠들어 **배율 1.0 패스 14칸이 통째로 빈 트리**를 읽었다
        (`본 것: 아무것도`). 잠든 화면에서도 `am start`는 종료코드 0을 준다.
        """
        self.shell("svc", "power", "stayon", "usb")

    def restore_stay_on(self, original: str) -> None:
        """**끄는 것이 아니라 원래 값으로 되돌린다**(#458 리뷰). 이 설정을 켜 두고 쓰는
        기기가 있고, `false`로 밀면 그 사람의 설정을 말없이 바꾼다 — 배율 복원과 같은 이유다.
        값은 비트마스크라 `svc`가 아니라 `settings put`으로 되돌린다."""
        if original.isdigit():
            self.shell("settings", "put", "global", "stay_on_while_plugged_in", original)
        else:
            self.shell("svc", "power", "stayon", "false")

    def wake(self) -> None:
        self.shell("input", "keyevent", "KEYCODE_WAKEUP")

    def font_scale(self) -> str:
        return (self.shell("settings", "get", "system", "font_scale") or "").strip()

    def set_font_scale(self, scale: str) -> None:
        self.shell("settings", "put", "system", "font_scale", scale)

    def restore_font_scale(self, original: str) -> None:
        """스크립트가 끝나면 기기를 원래대로 돌려놓는다.

        **한 번도 배율을 바꾼 적 없는 기기는 `null`을 돌려준다** — 그리고 그 기기가 정확히
        「기본 1.0」인 기기다. 그때 복원을 건너뛰면 기기가 `--scales`의 마지막 값(2.0)에
        남아, 다음 사람이 원인 모를 배율에서 딴 걸 잰다. **1.0을 박지 말고 지운다.**
        """
        if original and original != "null":
            self.set_font_scale(original)
        else:
            self.shell("settings", "delete", "system", "font_scale")

    def launch(self, case: Case, package: str = DEFAULT_PACKAGE) -> None:
        self.shell(*launch_args(case, package))

    def tap(self, bounds: Bounds) -> None:
        """뷰 한가운데를 누른다. **좌표를 적어 두지 않고 덤프에서 읽는다** — 배율이 바뀌면
        버튼이 옮겨 가고, 적어 둔 좌표는 그때 빈 곳을 누른다."""
        self.shell(
            "input",
            "tap",
            str((bounds.left + bounds.right) // 2),
            str((bounds.top + bounds.bottom) // 2),
        )

    def dump(self, local_path: str) -> str:
        """화면 하나를 덤프해서 XML 문자열로.

        **먼저 지운다.** `uiautomator dump`는 idle을 못 잡으면
        `ERROR: could not get idle state.`를 찍고 **종료코드 0으로 끝난다** — 지우지 않으면
        옛 파일이 그대로 남아 `pull`이 성공하고, **이번 칸에 직전 칸의 숫자가 적힌다.**
        흔들림을 재는 표에서 그것은 「못 쟀다」보다 나쁘다: 두 칸이 같은 값으로 나와 **OK가 뜬다.**
        애니메이션이 도는 화면(메인 8은 자동 복귀 중이다)이 가장 걸리기 쉽다.
        """
        remote = "/sdcard/davi-anchors.xml"
        self.shell("rm", "-f", remote)
        out = self.shell("uiautomator", "dump", remote)
        if "dumped to" not in out:
            raise RuntimeError(f"uiautomator dump가 실패했다: {out.strip()}")
        self.pull(remote, local_path)
        with open(local_path, encoding="utf-8") as handle:
            return handle.read()


#: 한 칸을 다시 띄워 볼 횟수와, 한 번 띄운 뒤 다시 덤프할 횟수·간격.
LAUNCH_ATTEMPTS = 3
READY_DUMPS = 4
READY_INTERVAL_S = 0.4
#: 다시 띄우기 전에 쉬는 시간. **곧바로 다시 넣으면 세 번이 같은 혼란 안에서 소모된다** —
#: 배율이 바뀐 직후에는 시스템이 아직 화면을 다시 만들고 있다.
RELAUNCH_BACKOFF_S = 1.5


def launch_until_ready(device, case: Case, tmp_path: str, package: str) -> dict:
    """칸을 띄우고 [Case.expect]가 덤프에 뜰 때까지 기다린다. 안 뜨면 **다시 띄운다.**

    **고정 대기로는 느린 기기에서 전이 중간이 찍힌다** — S23 Ultra에서 MA-1·MA-4 칸이
    통째로 비었다(같은 격자가 S24에서는 멀쩡했다). 「몇 초면 되겠지」를 기기마다 다시
    고르는 대신, 화면이 실제로 섰는지를 본다.

    **기다리기만 해서는 모자란다.** 글꼴 배율을 바꾸면 액티비티가 다시 만들어지는데, 그
    사이에 넣은 `am start`는 삼켜질 수 있다 — 그러면 아무리 기다려도 옛 화면이다(실측:
    배율을 바꾼 직후 첫 칸이 그랬다). 그래서 기다림이 헛돌면 주입을 다시 넣는다.

    끝내 못 보면 **마지막 덤프를 그대로 돌려준다** — 여기서 예외를 던지면 나머지 칸을
    못 재고, 표에서 빈 칸은 [verdict_lines]가 「못 잼」으로 세운다.
    """
    nodes = {}
    for attempt in range(LAUNCH_ATTEMPTS):
        if attempt:
            time.sleep(RELAUNCH_BACKOFF_S)
        device.launch(case, package)
        nodes = wait_for(device, case, case.expect, tmp_path, package)
        if case.expect not in nodes:
            continue
        if case.tap is None:
            return nodes
        target = nodes.get(case.tap)
        if target is None:
            continue
        device.tap(target)
        nodes = wait_for(device, case, case.expect_after, tmp_path, package)
        if case.expect_after in nodes:
            return nodes
    return nodes


def wait_for(device, case: Case, view: str, tmp_path: str, package: str) -> dict:
    """`view`가 덤프에 뜰 때까지 다시 잰다. 못 보면 마지막 덤프를 그대로 돌려준다."""
    nodes = {}
    for attempt in range(READY_DUMPS):
        time.sleep(case.settle_s if attempt == 0 else READY_INTERVAL_S)
        nodes = parse_dump(device.dump(tmp_path), package)
        if view in nodes:
            return nodes
    return nodes


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
    inset_bottom = device.navigation_bar_height_px()
    close_circle_inset_px = round(read_dimen_dp("davi_close_circle_inset") * density / 160.0)
    fixed_heights_px = {
        view: round(read_dimen_dp(token) * density / 160.0)
        for view, token in FIXED_HEIGHT_TOKENS.items()
    }
    original_scale = device.font_scale()
    # **재기 전에 화면부터 확인한다.** 잠긴 기기에서 돌리면 28칸이 전부 「못 잼」으로 나오고,
    # 그 표는 결함을 못 찾은 것이 아니라 **아무것도 안 본 것**이다. 비밀번호는 스크립트가
    # 못 넣으므로 여기서 세운다 — 사람이 풀어야 한다.
    device.wake()
    time.sleep(1.0)
    awake, unlocked = device.screen_state()
    if not (awake and unlocked):
        raise RuntimeError(
            "화면이 꺼졌거나 잠겼거나 **확인이 안 된다** — 잠금을 풀고 다시 돌려라 "
            f"(깨어 있음 확인={awake} 잠금 풀림 확인={unlocked})"
        )
    original_stay_on = device.stay_on_setting()
    device.keep_screen_on()
    cases = default_cases()
    rows = []
    tmp = os.path.join(args.work_dir, "davi-anchors.xml")
    os.makedirs(args.work_dir, exist_ok=True)
    try:
        for scale in args.scales:
            device.set_font_scale(scale)
            # **배율 변경은 액티비티 하나가 아니라 시스템 전체를 다시 만든다.** 그 사이에
            # 넣은 주입은 삼켜지고, 덤프에는 **아무 뷰도 없는 트리**가 온다(실측 S23 Ultra:
            # 배율 1.0으로 넘어가는 첫 두 칸이 「본 것: 아무것도」로 두 번 연속 비었다).
            # 칸마다 다시 띄우는 그물이 있어도 그 그물이 같은 혼란 안에서 소모되므로,
            # 여기서 먼저 가라앉기를 기다린다.
            time.sleep(SCALE_SETTLE_S)
            for case in cases:
                nodes = launch_until_ready(device, case, tmp, args.package)
                metrics = measure(
                    nodes,
                    density,
                    inset_top,
                    screen_height,
                    case.perched,
                    close_circle_inset_px,
                    fixed_heights_px,
                    inset_bottom,
                )
                rows.append(
                    Row(
                        screen=case.screen,
                        label=case.label,
                        options=case.options,
                        chip=case.chip,
                        scale=scale,
                        inset_top_dp=dp(inset_top, density),
                        expected=case.expect_after or case.expect,
                        expected_start_line_dp=(
                            round(dp(inset_top, density) + chain_dp(case.start_line_chain), 2)
                            if case.start_line_chain
                            else None
                        ),
                        seen=tuple(sorted(nodes)),
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
        device.restore_font_scale(original_scale)
        device.restore_stay_on(original_stay_on)

    header = (
        f"# 대화 화면 시작선 실측 (#452)\n\n"
        f"기기 `{args.serial or 'default'}` · 밀도 {density}dpi · "
        f"화면 높이 {dp(screen_height, density)}dp · "
        f"인셋 위 {dp(inset_top, density)}dp · 아래 {dp(inset_bottom, density)}dp"
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
