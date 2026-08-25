"""`measure-conversation-anchors.py`의 파싱·판정 부분을 기기 없이 검증한다 (#452).

**측정 스크립트 자체가 조용히 틀리면 표가 결함을 덮는다** — 인셋을 0으로 읽거나, 보기
0개를 재려던 칸이 3개로 뜨거나, 시작선이 갈리는데 「OK」로 나오는 것이 그렇다. 그 셋을
여기서 못 박는다.
"""

import importlib.util
import re
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "measure-conversation-anchors.py"
DIALOGUE_SESSION = (
    Path(__file__).parents[2]
    / "app/src/main/java/com/davi/helper/VoiceDialogueSession.kt"
)
SPEC = importlib.util.spec_from_file_location("measure_conversation_anchors", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)

DENSITY = 480  # 1dp = 3px (검증 기기 SM-S921N)
INSET_TOP_PX = 3 * 32
SCREEN_HEIGHT_PX = 2340
CLOSE_CIRCLE_INSET_PX = 3 * 6  # davi_close_circle_inset
FIXED_HEIGHTS_PX = {"btn_close": 3 * 56, "question_progress": 3 * 38}


def dump(nodes: dict) -> str:
    """`{id: (left, top, right, bottom)}`를 uiautomator 덤프 모양으로 만든다."""
    rows = "".join(
        f'<node resource-id="com.davi.helper.debug:id/{name}" '
        f'bounds="[{b[0]},{b[1]}][{b[2]},{b[3]}]" class="android.view.View" />'
        for name, b in nodes.items()
    )
    return f'<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0">{rows}</hierarchy>'


def ma3(card_top_px: int, with_chip: bool) -> dict:
    """구체화 한 칸. ✕는 카드 상단선에 **중심**을, 칩은 ✕ 원의 윗변에 맞춘다."""
    close_h = 3 * 56
    nodes = {
        "card_column": (0, INSET_TOP_PX, 1080, SCREEN_HEIGHT_PX),
        "card_question_wrap": (72, card_top_px, 1008, card_top_px + 3 * 200),
        "btn_close": (900, card_top_px - close_h // 2, 900 + close_h, card_top_px + close_h // 2),
    }
    if with_chip:
        chip_top = card_top_px - close_h // 2 + 3 * 6
        nodes["question_progress"] = (96, chip_top, 96 + 3 * 110, chip_top + 3 * 38)
    return nodes


class ParseDumpTest(unittest.TestCase):
    def test_tracked_ids_only_and_bounds_are_parsed(self):
        nodes = MODULE.parse_dump(
            dump(
                {
                    "btn_close": (900, 100, 1068, 268),
                    "home_sparkle": (0, 0, 10, 10),  # 재는 대상이 아니다
                }
            )
        )

        self.assertEqual(set(nodes), {"btn_close"})
        self.assertEqual(nodes["btn_close"], MODULE.Bounds(900, 100, 1068, 268))

    def test_missing_view_is_absent_rather_than_zero(self):
        # **없는 것을 0으로 적으면 표가 조용히 거짓말을 한다** — 시작선 0dp는 잘림으로 읽힌다.
        nodes = MODULE.parse_dump(dump({"btn_close": (900, 100, 1068, 268)}))

        self.assertNotIn("card_question_wrap", nodes)

    def test_malformed_bounds_are_dropped(self):
        broken = (
            '<hierarchy><node resource-id="com.davi.helper.debug:id/btn_close" '
            'bounds="[a,b][c,d]" /></hierarchy>'
        )

        self.assertEqual(MODULE.parse_dump(broken), {})


class MeasureTest(unittest.TestCase):
    def measure(self, nodes: dict, perched: bool = True) -> dict:
        return MODULE.measure(
            MODULE.parse_dump(dump(nodes)),
            DENSITY,
            INSET_TOP_PX,
            SCREEN_HEIGHT_PX,
            perched,
            CLOSE_CIRCLE_INSET_PX,
            FIXED_HEIGHTS_PX,
        )

    def test_ma3_start_line_is_the_chip_top_when_the_chip_stands(self):
        # 칩과 ✕의 **보이는 원**은 윗변이 같다 — 앱이 그렇게 맞춘다. 같으면 min이 먼저
        # 만난 칩을 고르고, 어긋나면 위에 선 쪽이 잡혀 표에 드러난다.
        result = self.measure(ma3(card_top_px=3 * 225, with_chip=True))

        self.assertEqual(result["start_line_source"], "칩")
        self.assertEqual(result["start_line_dp"], 225 - 28 + 6)

    def test_ma3_start_line_falls_back_to_the_close_button_without_a_chip(self):
        result = self.measure(ma3(card_top_px=3 * 225, with_chip=False))

        self.assertEqual(result["start_line_source"], "✕")
        self.assertEqual(result["start_line_dp"], 225 - 28 + 6)

    def test_other_screens_measure_the_card_top(self):
        nodes = {"card_question_wrap": (72, 3 * 114, 1008, 3 * 300)}

        result = self.measure(nodes, perched=False)

        self.assertEqual(result["start_line_source"], "카드")
        self.assertEqual(result["start_line_dp"], 114)

    def test_close_button_pushed_above_the_inset_is_reported_as_clipped(self):
        # #452의 증상 그대로다 — 칩이 사라져 카드가 올라가면 ✕가 상태바로 밀린다.
        result = self.measure(ma3(card_top_px=3 * 36, with_chip=False))

        self.assertNotEqual(result["clipped"], "아니오")
        self.assertIn("인셋 위로", result["clipped"])

    def test_a_clipped_close_button_is_restored_before_it_is_measured(self):
        """덤프는 **화면 안으로 잘린** 경계를 준다 — 그대로 재면 잘림이 사라진다.

        2026-08-25 실측: ✕가 상태바를 16dp 파고들었는데 덤프의 높이는 56dp가 아니라
        40dp였고 top은 인셋과 같았다. 되살리지 않으면 시작선이 「인셋에 딱 붙었다」로 나와
        표가 결함을 덮는다.
        """
        # 카드 상단 44dp -> ✕ 윗변 16dp -> 인셋(32dp) 위로 16dp 나간다.
        nodes = ma3(card_top_px=3 * 44, with_chip=False)
        left, _, right, bottom = nodes["btn_close"]
        nodes["btn_close"] = (left, INSET_TOP_PX, right, bottom)  # 잘린 경계

        result = self.measure(nodes)

        self.assertIn("✕가 16.0dp 잘렸다", result["clipped"])
        # 되살린 윗변 = 바닥 - 56dp, 거기에 원 인셋 6dp.
        self.assertEqual(result["close_top_dp"], MODULE.dp(bottom - 3 * 56 + 3 * 6, DENSITY))

    def test_a_button_below_the_screen_is_reported_as_clipped(self):
        nodes = ma3(card_top_px=3 * 225, with_chip=True)
        nodes["btn_direct_speak"] = (72, SCREEN_HEIGHT_PX - 30, 1008, SCREEN_HEIGHT_PX + 120)

        result = self.measure(nodes)

        self.assertIn("화면 아래로", result["clipped"])

    def test_button_heights_are_listed_in_dp(self):
        nodes = ma3(card_top_px=3 * 225, with_chip=True)
        nodes["btn_option_1"] = (72, 3 * 300, 1008, 3 * 372)
        nodes["btn_direct_speak"] = (72, 3 * 380, 1008, 3 * 452)

        result = self.measure(nodes)

        self.assertEqual(result["button_h_dp"], "72.0/72.0")


class VerdictTest(unittest.TestCase):
    def rows(self, *start_lines: float) -> list:
        return [
            MODULE.Row(
                screen="MA-3 구체화",
                label=f"칸 {index}",
                options="3",
                chip="있음",
                scale="2.0",
                start_line_dp=value,
                clipped="아니오",
            )
            for index, value in enumerate(start_lines)
        ]

    def test_one_start_line_per_screen_and_scale_is_ok(self):
        lines = MODULE.verdict_lines(self.rows(197.0, 197.0))

        self.assertEqual(len(lines), 1)
        self.assertIn("[OK]", lines[0])

    def test_the_63dp_jump_of_issue_452_is_reported_as_흔들림(self):
        # 보기 3개·질문 2개(225dp)와 보기 0개·질문 1개(36dp)의 카드 상단 차이가 63dp였다.
        lines = MODULE.verdict_lines(self.rows(197.0, 197.0 - 63.0))

        self.assertIn("흔들림", lines[0])
        self.assertIn("폭 63.0dp", lines[0])

    def test_scales_are_grouped_apart(self):
        # 글자가 커지면 칩도 커진다 — 배율 사이의 차이는 결함이 아니다.
        rows = self.rows(197.0)
        rows.append(
            MODULE.Row(
                screen="MA-3 구체화",
                label="배율 1.0",
                options="3",
                chip="있음",
                scale="1.0",
                start_line_dp=150.0,
                clipped="아니오",
            )
        )

        lines = MODULE.verdict_lines(rows)

        self.assertEqual(len(lines), 2)
        self.assertTrue(all("[OK]" in line for line in lines))

    def test_missing_views_are_surfaced_instead_of_being_swallowed(self):
        rows = self.rows(197.0)
        rows[0].missing = ("card_question_wrap",)

        lines = MODULE.verdict_lines(rows)

        self.assertTrue(any("card_question_wrap" in line for line in lines))


class GridTest(unittest.TestCase):
    def test_grid_covers_every_option_and_question_count_the_contract_allows(self):
        clarification = [c for c in MODULE.default_cases() if c.perched]
        combinations = {(case.options, case.chip) for case in clarification}

        self.assertEqual(
            combinations,
            {(str(n), chip) for n in range(0, 4) for chip in ("있음", "없음")},
        )

    def test_grid_option_ceiling_follows_the_contract(self):
        # 이슈 본문의 격자는 「보기 4개」를 적었지만 계약이 3을 상한으로 잡는다. 계약이
        # 바뀌면 이 테스트가 먼저 깨져 격자를 함께 고치게 한다.
        source = DIALOGUE_SESSION.read_text(encoding="utf-8")
        max_options = int(re.search(r"const val MAX_OPTIONS = (\d+)", source).group(1))
        max_questions = int(re.search(r"const val MAX_QUESTIONS = (\d+)", source).group(1))

        clarification = [c for c in MODULE.default_cases() if c.perched]

        self.assertEqual(max(int(case.options) for case in clarification), max_options)
        self.assertEqual(max_questions, 2)  # 칩 유무가 곧 질문 1개/2개다


class LaunchArgsTest(unittest.TestCase):
    def args_for(self, option_count: int, question_count: int) -> list:
        return MODULE.launch_args(MODULE.clarification_case(option_count, question_count))

    def test_option_labels_stay_one_argument_so_the_pipe_is_not_a_pipe(self):
        args = self.args_for(3, 2)

        self.assertIn("'카카오톡|문자 메시지|인스타그램'", args)

    def test_zero_options_sends_an_empty_string_rather_than_no_argument(self):
        # 감싸지 않으면 인자가 통째로 사라져 **보기 0개를 재려던 칸이 3개로 뜬다.**
        args = self.args_for(0, 2)

        self.assertIn("''", args)
        self.assertEqual(args[args.index(f"{MODULE.E}.DEBUG_CLARIFICATION") + 1], "''")

    def test_integer_extras_are_not_quoted(self):
        args = self.args_for(3, 1)

        self.assertEqual(args[args.index(f"{MODULE.E}.DEBUG_CLARIFICATION_COUNT") + 1], "1")


class DimenTest(unittest.TestCase):
    def test_close_circle_inset_is_read_from_dimens_not_copied(self):
        # 베껴 두면 토큰이 바뀌어도 표는 옛 값으로 계속 「OK」를 낸다.
        self.assertEqual(MODULE.read_dimen_dp("davi_close_circle_inset"), 6.0)

    def test_missing_token_raises_instead_of_defaulting(self):
        with self.assertRaises(RuntimeError):
            MODULE.read_dimen_dp("davi_token_that_does_not_exist")


class StatusBarInsetTest(unittest.TestCase):
    def test_inset_is_read_from_dumpsys_and_never_defaults_to_zero(self):
        class FakeDevice(MODULE.Device):
            def __init__(self, raw: str):
                self._raw = raw

            def shell(self, *args: str) -> str:
                return self._raw

        # SM-S921N / Android 16 실측 형식.
        raw = (
            "  InsetsSource id=77600000 type=statusBars frame=[0,0][1080,96] visible=true\n"
            "  InsetsSource id=77600001 type=navigationBars frame=[0,2277][1080,2340]\n"
        )

        self.assertEqual(FakeDevice(raw).status_bar_height_px(), 96)

        with self.assertRaises(RuntimeError):
            FakeDevice("아무것도 없다\n").status_bar_height_px()


if __name__ == "__main__":
    unittest.main()
