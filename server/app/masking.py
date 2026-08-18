"""2차 마스킹 그물. 앱의 1차 마스킹이 놓친 개인정보 패턴을 서버에서 한 번 더 막는다.

경계(SPEC §2, §4):
- 원본은 기기를 떠나지 않는다. 여기 들어오는 값은 이미 1차 마스킹된 것이다.
- 여기서 저장/로깅하지 않는다. 이 함수는 순수 변환일 뿐이다.
- 원격 업데이트 가능한 지점이 여기다(앱 재배포 없이 패턴 추가).

한국어·삼성 전용 가정이 아직 곳곳에 있다(SPEC §11 열린 결정). 패턴은 보수적으로 추가한다.
"""

from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from .config import settings
from .schemas import Screen

_TOKEN = "•••"

# 새 텍스트 필드를 2차 마스킹 대상으로 추가할 때 이 목록만 확장한다.
# dict/model 어댑터가 아래의 같은 순회 규칙을 공유하므로 한쪽만 빠지지 않는다.
_MASKED_ELEMENT_TEXT_FIELDS = ("label",)

# (이름, 정규식) — 오탐을 줄이려 형태가 뚜렷한 것부터. 필요 시 추가.
_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("email", re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")),
    # 주민등록번호 6-7
    ("rrn", re.compile(r"\b\d{6}[-\s]?[1-4]\d{6}\b")),
    # 카드번호 4-4-4-4
    ("card", re.compile(r"\b(?:\d{4}[-\s]?){3}\d{4}\b")),
    # 전화번호(휴대폰 위주). 하이픈/공백 허용.
    ("phone", re.compile(r"\b01[016789][-\s]?\d{3,4}[-\s]?\d{4}\b")),
    # 계좌번호처럼 보이는 긴 숫자열(10~14자리, 하이픈 포함 가능)
    ("account", re.compile(r"\b\d{2,4}[-\s]\d{2,6}[-\s]\d{2,7}\b")),
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
    """Screen dict의 모든 element label을 마스킹한 새 dict를 돌려준다(비파괴)."""
    elements = _mask_element_text_fields(
        screen.get("elements", []),
        read_field=lambda element, field_name: element.get(field_name, ""),
        copy_with_updates=lambda element, updates: {**element, **updates},
    )
    return {**screen, "elements": elements}


def mask_screen_model(screen: Screen) -> Screen:
    """파이프라인용 Screen 모델을 직렬화 왕복 없이 비파괴로 2차 마스킹한다."""

    return screen.model_copy(
        update={
            "elements": _mask_element_text_fields(
                screen.elements,
                read_field=lambda element, field_name: getattr(element, field_name),
                copy_with_updates=lambda element, updates: element.model_copy(update=updates),
            )
        }
    )


@dataclass(frozen=True)
class MaskingPolicy:
    """2차 마스킹을 적용할지 담은 값(#203).

    파이프라인이 설정을 직접 읽는 대신 이 값을 주입받는다. 그래야 마스킹 경로와 우회 경로를
    **둘 다** 테스트에서 LLM에 실제로 전달된 요청으로 검증할 수 있다.
    """

    enabled: bool

    def apply_screen(self, screen: Screen) -> Screen:
        return mask_screen_model(screen) if self.enabled else screen

    def apply_text(self, text: str) -> str:
        return mask_text(text) if self.enabled else text


#: 제품 기본값. 모든 프로필에서 이 정책이 기본이다.
ENABLED_MASKING = MaskingPolicy(enabled=True)

#: local development 비교 실험 전용 우회(#203).
#: **원문 label이 실제 Gemini까지 그대로 전달된다.** `Settings`가 development·loopback·
#: 개발 토큰 없음을 모두 만족할 때만 이 정책이 선택될 수 있다.
DISABLED_MASKING = MaskingPolicy(enabled=False)


def active_masking_policy() -> MaskingPolicy:
    """설정이 정한 현재 정책. 설정 해석을 이 함수 하나로 모은다."""

    return DISABLED_MASKING if settings.davi_disable_privacy_masking else ENABLED_MASKING
