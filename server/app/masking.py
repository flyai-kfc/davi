"""2차 마스킹 그물. 앱의 1차 마스킹이 놓친 개인정보 패턴을 서버에서 한 번 더 막는다.

경계(SPEC §2, §4):
- 원본은 기기를 떠나지 않는다. 여기 들어오는 값은 이미 1차 마스킹된 것이다.
- 여기서 저장/로깅하지 않는다. 이 함수는 순수 변환일 뿐이다.
- 원격 업데이트 가능한 지점이 여기다(앱 재배포 없이 패턴 추가).

한국어·삼성 전용 가정이 아직 곳곳에 있다(SPEC §11 열린 결정). 패턴은 보수적으로 추가한다.
"""

from __future__ import annotations

import re
from typing import Any

from .schemas import Screen

_TOKEN = "•••"

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


def mask_screen(screen: dict[str, Any]) -> dict[str, Any]:
    """Screen dict의 모든 element label을 마스킹한 새 dict를 돌려준다(비파괴)."""
    elements = [
        {**el, "label": mask_text(el.get("label", ""))} for el in screen.get("elements", [])
    ]
    return {**screen, "elements": elements}


def mask_screen_model(screen: Screen) -> Screen:
    """파이프라인용 Screen 모델을 직렬화 왕복 없이 비파괴로 2차 마스킹한다."""

    return screen.model_copy(
        update={
            "elements": [
                element.model_copy(update={"label": mask_text(element.label)})
                for element in screen.elements
            ]
        }
    )
