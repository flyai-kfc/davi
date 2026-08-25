"""#425 착수 시점 완료 근거 정책의 고정 snapshot.

이 파일은 server/app/reasoning.py@6910aad의 완료 근거 대조와 보이는 요소 판정을 복사해 둔다.
candidate 구현을 import하지 않으며, 서버 정책이 바뀌어도 baseline 수치가 함께 움직이지 않게 한다.
중복은 의도적이다. 이 파일을 수정하면 파일 해시가 policy id에 자동 반영된다.
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path
from typing import NamedTuple

from app.schemas import Screen, UiElement

_EVIDENCE_NOISE = re.compile(r"[^\w]+", re.UNICODE)
_EVIDENCE_WORD = re.compile(r"\w+", re.UNICODE)
_VALUE_CONNECTORS = frozenset("-./:")
_MIN_EVIDENCE_LABEL_CHARS = 2
_KOREAN_EVIDENCE_SUFFIXES = frozenset(
    {
        "가",
        "고",
        "과",
        "까지",
        "께",
        "께서",
        "나",
        "는",
        "도",
        "라",
        "라고",
        "랑",
        "로",
        "를",
        "마다",
        "만",
        "며",
        "보다",
        "부터",
        "에",
        "에게",
        "에서",
        "예요",
        "였습니다",
        "와",
        "으로",
        "은",
        "을",
        "의",
        "이",
        "이고",
        "이며",
        "이나",
        "이랑",
        "이라",
        "이라고",
        "이었습니다",
        "이에요",
        "입니다",
        "인",
        "처럼",
    }
)
_KOREAN_EVIDENCE_COMPOUND_BASES = frozenset(
    {
        "과",
        "까지",
        "께",
        "께서",
        "로",
        "보다",
        "부터",
        "에",
        "에게",
        "에서",
        "와",
        "으로",
        "처럼",
    }
)
_KOREAN_EVIDENCE_AUXILIARY_SUFFIXES = frozenset({"는", "도", "만", "까지"})


class _Phrases(NamedTuple):
    compact: str
    starts: list[int]
    ends: list[int]
    glued: list[bool]


def baseline_verified(evidence: str, expected_result: str, screen: Screen) -> bool:
    """고정한 v1 정책으로 DONE 근거를 검증한다."""
    if not evidence.strip() or not expected_result.strip():
        return False
    return _evidence_quotes_screen(evidence, screen)


def _evidence_quotes_screen(evidence: str, screen: Screen) -> bool:
    phrases = _phrase_offsets(evidence)
    if not phrases.compact:
        return False
    comparable = [
        normalized
        for element in _visible_elements(screen.elements)
        if len(normalized := _normalize_for_evidence(element.label))
        >= _MIN_EVIDENCE_LABEL_CHARS
    ]
    return bool(comparable) and any(
        _contains_quoted_phrase(phrases, label) for label in comparable
    )


def _visible_elements(elements: list[UiElement]) -> list[UiElement]:
    """snapshot 당시 server/app/screen_view.py의 가림 판정을 보존한다."""
    if elements and all(element.obscured is True for element in elements):
        return elements
    return [element for element in elements if element.obscured is not True]


def _phrase_offsets(text: str) -> _Phrases:
    folded = text.casefold()
    matches = list(_EVIDENCE_WORD.finditer(folded))
    starts: list[int] = []
    ends: list[int] = []
    glued: list[bool] = []
    cursor = 0
    for index, match in enumerate(matches):
        starts.append(cursor)
        cursor += match.end() - match.start()
        ends.append(cursor)
        following = matches[index + 1] if index + 1 < len(matches) else None
        separator = folded[match.end() : following.start()] if following else ""
        glued.append(
            bool(separator) and all(char in _VALUE_CONNECTORS for char in separator)
        )
    return _Phrases("".join(match.group() for match in matches), starts, ends, glued)


def _contains_quoted_phrase(phrases: _Phrases, normalized_phrase: str) -> bool:
    for index, start in enumerate(phrases.starts):
        if index > 0 and phrases.glued[index - 1]:
            continue
        if not phrases.compact.startswith(normalized_phrase, start):
            continue
        match_end = start + len(normalized_phrase)
        last = next(
            (i for i, end in enumerate(phrases.ends) if end >= match_end),
            None,
        )
        if last is None:
            continue
        word_end = phrases.ends[last]
        if word_end != match_end and not _is_allowed_korean_evidence_suffix(
            phrases.compact[match_end:word_end]
        ):
            continue
        if not phrases.glued[last]:
            return True
    return False


def _is_allowed_korean_evidence_suffix(suffix: str) -> bool:
    tail = suffix.removeprefix("들")
    if not tail or tail in _KOREAN_EVIDENCE_SUFFIXES:
        return True
    return any(
        base != auxiliary and tail == base + auxiliary
        for base in _KOREAN_EVIDENCE_COMPOUND_BASES
        for auxiliary in _KOREAN_EVIDENCE_AUXILIARY_SUFFIXES
    )


def _normalize_for_evidence(text: str) -> str:
    return _EVIDENCE_NOISE.sub("", text.casefold())


BASELINE_SOURCE_SHA256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
BASELINE_POLICY_ID = f"screen-evidence-baseline-v1-{BASELINE_SOURCE_SHA256[:12]}"
