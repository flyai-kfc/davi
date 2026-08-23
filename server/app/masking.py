"""2차 마스킹 그물. 앱의 1차 마스킹이 놓친 개인정보 패턴을 서버에서 한 번 더 막는다.

경계(SPEC §2, §4):
- 원본은 기기를 떠나지 않는다. 여기 들어오는 값은 이미 1차 마스킹된 것이다.
- 여기서 저장/로깅하지 않는다. 이 함수는 순수 변환일 뿐이다.
- 원격 업데이트 가능한 지점이 여기다(앱 재배포 없이 패턴 추가).

한국어·삼성 전용 가정이 아직 곳곳에 있다(SPEC §11 열린 결정). 패턴은 보수적으로 추가한다.

앱(`PrivacyMasker.kt`)이 **같은 문자열**을 쓰되 컴파일할 때 `(?U)`(UNICODE_CHARACTER_CLASS)를
얹는다(#361). 자바의 `\d`·`\s`는 기본이 ASCII라 전각 숫자·NBSP를 놓치는데 파이썬 `re`는
유니코드가 기본이어서, 그대로 두면 1차만 새고 2차만 잡는 상태가 된다. 여기서 `re.ASCII`를 켜면
그 균형이 반대로 깨진다 — 켜지 말 것.
"""

from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from .config import settings
from .schemas import GuideStep, Screen

_TOKEN = "•••"

# 새 텍스트 필드를 2차 마스킹 대상으로 추가할 때 이 목록만 확장한다.
# dict/model 어댑터가 아래의 같은 순회 규칙을 공유하므로 한쪽만 빠지지 않는다.
_MASKED_ELEMENT_TEXT_FIELDS = ("label",)

# 스텝 이력에서 원문이 실릴 수 있는 자리(C11 개정, #270). 셋 다 화면 label에서 왔다 —
# 안내 문장은 label을 인용하고, `target_label`은 label 그 자체이며, `screen_hint`는 모델이
# 화면을 요약하며 고유명(장소명 등)을 인용하는 것이 실측됐다.
_MASKED_STEP_TEXT_FIELDS = ("instruction", "target_label", "screen_hint")

# 화면 단위 텍스트 필드. 요소 목록과 순회 축이 달라 따로 두되 규칙은 같다.
_MASKED_SCREEN_TEXT_FIELDS = ("window_title",)

# 숫자 패턴의 경계를 `\b` 대신 **숫자 lookaround**로 본다(#312).
#
# 파이썬 `re`의 `\b`는 유니코드 인식이라 **한글 음절이 word character**다. 그래서 숫자 바로
# 뒤에 조사가 오면 경계가 성립하지 않아 `'010-1234-5678에게 전화'`가 통째로 새어 나갔다 —
# 연락처·메시지·송금 화면의 label이 정확히 그 형태다. `(?<!\d)`/`(?!\d)`는 "긴 숫자열의
# 일부만 잘라 맞추지 않는다"는 원래 의도만 남기고 한글 인접 여부는 보지 않는다.
#
# **같은 소스가 두 언어에서 같게 동작하게 되는 효과도 있다.** JVM(`PrivacyMasker.kt`)의 `\b`는
# 기본이 ASCII 기준이라 한글을 word character로 보지 않아, 지금까지 1차와 2차의 동작이
# 갈려 있었다(#312에서 JVM으로 실측). lookaround에는 그 차이가 없다.
#
# (이름, 정규식) — 오탐을 줄이려 형태가 뚜렷한 것부터. 필요 시 추가.
_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("email", re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")),
    # MAC 주소(콜론 구분 6바이트 이상). 블루투스 기기 목록에서 그대로 새어 나갔다(#312 실측).
    #
    # **경계에서 콜론을 금지하지 않는다.** 콜론은 주소의 구분자이기도 하지만 `MAC:`·`주소:`처럼
    # **앞 라벨의 구분자**이기도 해서, 금지하면 공백 없는 표기를 통째로 놓친다(PR #357 리뷰에서
    # 실측). 대신 영숫자를 금지해 긴 hex 열의 일부만 잘라 맞추는 것을 막는다.
    #
    # 7바이트 이상(EUI-64 등)은 `{5,}`가 **통째로** 삼킨다. 앞 6바이트만 지우고 꼬리를 남기면
    # 가린 척만 하는 셈이고, 콜론 구분 hex가 6쌍 이상 이어지는 화면 텍스트는 하드웨어 주소
    # 말고는 사실상 없다(시각 `12:34:56`은 3쌍, IPv6는 4자리 그룹이라 걸리지 않는다).
    #
    # 하이픈 구분(`3C-86-…`) 형태는 넣지 않는다 — 기기 화면에서 실측된 형태가 콜론이고,
    # 하이픈 형태는 account 패턴과 앞부분이 겹쳐 순서 의존을 만든다.
    ("mac", re.compile(r"(?<![0-9A-Za-z])(?:[0-9A-Fa-f]{2}:){5,}[0-9A-Fa-f]{2}(?![0-9A-Za-z])")),
    # 주민등록번호 6-7
    ("rrn", re.compile(r"(?<!\d)\d{6}[-\s]?[1-4]\d{6}(?!\d)")),
    # 카드번호 4-4-4-4
    ("card", re.compile(r"(?<!\d)(?:\d{4}[-\s]?){3}\d{4}(?!\d)")),
    # 전화번호(휴대폰 위주). 하이픈/공백 허용.
    ("phone", re.compile(r"(?<!\d)01[016789][-\s]?\d{3,4}[-\s]?\d{4}(?!\d)")),
    # 계좌번호처럼 보이는 긴 숫자열(하이픈·공백 구분 세 덩어리).
    #
    # **날짜 모양을 먼저 거부한다**(#361). `2026-08-18`이 그대로 이 패턴에 들어맞아 가려졌다 —
    # 개인정보가 새는 게 아니라 **안내 품질이 조용히 깎이는** 쪽이라 아무도 빨개지지 않았다.
    # 달·일의 범위(1~12 / 1~31)까지 보므로 `12-34-56`처럼 날짜가 아닌 것은 계속 걸린다.
    #
    # **첫 덩어리를 6자리까지 넓혔다.** 국민은행 `123456-01-123456`·우체국 `012345-01-123456`
    # 형태가 `\d{2,4}`에 안 맞아 **통째로 새고 있었다**(#361에서 실제 형태를 재다 발견).
    #
    # 아는 한계 ①: **날짜 모양이면 가려지지 않는다** — `1234-05-06`(4-2-2, 8자리)뿐 아니라
    # `12-05-06`(2-2-2, 6자리)도 그렇다. 국내 계좌는 둘 다보다 길어 겹치지 않는다고 보고 택한
    # 맞바꿈이다. 되짚을 때 이 줄부터 본다.
    #
    # 아는 한계 ②: **앱 1차와 결과가 갈리는 입력이 있다.** 정규식 문자열은 `PrivacyMasker.kt`와
    # 글자까지 같지만 `\d`·`\s`가 파이썬에서는 유니코드, 자바에서는 ASCII다 — NBSP로 구분한
    # 계좌나 전각 숫자를 여기서는 잡고 앱에서는 못 잡는다. 여섯 패턴 전부에 걸린 옛 문제이며
    # (#312가 `\b`에서 같은 종류를 겪었다) 이 PR이 만든 것은 아니다.
    (
        "account",
        re.compile(
            r"(?<!\d)"
            r"(?!(?:\d{4}|\d{2})[-\s](?:0?[1-9]|1[0-2])[-\s](?:0?[1-9]|[12]\d|3[01])(?!\d))"
            r"\d{2,6}[-\s]\d{2,6}[-\s]\d{2,7}(?!\d)"
        ),
    ),
]


def mask_text(text: str) -> str:
    """단일 문자열의 개인정보 패턴을 토큰으로 치환한다."""
    if not text:
        return text
    for _name, pattern in _PATTERNS:
        text = pattern.sub(_TOKEN, text)
    return text


def _mask_element_text_fields[ElementT](
    elements: list[ElementT],
    *,
    read_field: Callable[[ElementT, str], str],
    copy_with_updates: Callable[[ElementT, dict[str, str]], ElementT],
) -> list[ElementT]:
    """입력 표현과 무관하게 요소의 민감 텍스트 필드를 한 규칙으로 순회한다."""

    return [
        copy_with_updates(
            element,
            {
                field_name: mask_text(read_field(element, field_name))
                for field_name in _MASKED_ELEMENT_TEXT_FIELDS
            },
        )
        for element in elements
    ]


def mask_screen(screen: dict[str, Any]) -> dict[str, Any]:
    """Screen dict의 민감 텍스트를 마스킹한 새 dict를 돌려준다(비파괴)."""
    elements = _mask_element_text_fields(
        screen.get("elements", []),
        read_field=lambda element, field_name: element.get(field_name, ""),
        copy_with_updates=lambda element, updates: {**element, **updates},
    )
    screen_updates = {
        field_name: mask_text(screen[field_name])
        for field_name in _MASKED_SCREEN_TEXT_FIELDS
        if isinstance(screen.get(field_name), str)
    }
    return {**screen, **screen_updates, "elements": elements}


def mask_screen_model(screen: Screen) -> Screen:
    """파이프라인용 Screen 모델을 직렬화 왕복 없이 비파괴로 2차 마스킹한다."""

    updates: dict[str, Any] = {
        "elements": _mask_element_text_fields(
            screen.elements,
            read_field=lambda element, field_name: getattr(element, field_name),
            copy_with_updates=lambda element, updates: element.model_copy(update=updates),
        )
    }
    updates.update(
        {
            field_name: mask_text(value)
            for field_name in _MASKED_SCREEN_TEXT_FIELDS
            if isinstance(value := getattr(screen, field_name, None), str)
        }
    )
    return screen.model_copy(update=updates)


def mask_step_model(step: GuideStep) -> GuideStep:
    """스텝 하나의 텍스트 필드를 비파괴로 2차 마스킹한다.

    요소 label과 달리 **값이 없을 수 있다**(`target_label`·`screen_hint`). None은 그대로 둔다 —
    빈 문자열로 바꾸면 "대상이 없는 동작"과 "대상 label을 못 실은 안내"가 섞인다.
    """

    updates: dict[str, str] = {}
    for field_name in _MASKED_STEP_TEXT_FIELDS:
        value = getattr(step, field_name)
        if isinstance(value, str):
            updates[field_name] = mask_text(value)
    return step.model_copy(update=updates)


@dataclass(frozen=True)
class MaskingPolicy:
    """2차 마스킹을 적용할지 담은 값(#203).

    파이프라인이 설정을 직접 읽는 대신 이 값을 주입받는다. 그래야 마스킹 경로와 우회 경로를
    **둘 다** 테스트에서 LLM에 실제로 전달된 요청으로 검증할 수 있다.
    """

    enabled: bool

    def apply_screen(self, screen: Screen) -> Screen:
        return mask_screen_model(screen) if self.enabled else screen

    def apply_steps(self, steps: list[GuideStep]) -> list[GuideStep]:
        return [mask_step_model(step) for step in steps] if self.enabled else steps


#: 제품 기본값. 모든 프로필에서 이 정책이 기본이다.
ENABLED_MASKING = MaskingPolicy(enabled=True)

#: local development 비교 실험 전용 우회(#203).
#: **원문 label이 실제 Gemini까지 그대로 전달된다.** `Settings`가 development·loopback·
#: 개발 토큰 없음을 모두 만족할 때만 이 정책이 선택될 수 있다.
DISABLED_MASKING = MaskingPolicy(enabled=False)


def active_masking_policy() -> MaskingPolicy:
    """설정이 정한 현재 정책. 설정 해석을 이 함수 하나로 모은다."""

    return DISABLED_MASKING if settings.davi_disable_privacy_masking else ENABLED_MASKING
