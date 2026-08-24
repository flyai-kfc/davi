"""앱 1차와 서버 2차의 마스킹 정규식 **본문**이 글자까지 같은지 확인한다(#361).

두 파일은 같은 규칙을 두 언어로 들고 있다. 앱은 본문을 그대로 두고 컴파일할 때만 유니코드
플래그를 얹는데(자바의 `\\d`·`\\s`가 기본 ASCII라서다), **그 구조는 본문이 갈리지 않아야 성립한다.**
지금까지 그걸 지키는 건 사람 눈과 양쪽에 손으로 맞춘 테스트 목록뿐이었다.

이 검사는 앱·서버 어느 스택에도 속하지 않는다. 경로 필터가 걸리는 잡에 두면 **한쪽만 바뀐
PR에서 안 도는 관문**이 되므로, 워크플로에서 항상 도는 잡이 부른다.

의존성 없이 텍스트로 판정한다 — 이 검사가 도는 잡에는 서버 패키지가 설치돼 있지 않다.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / "server/app/masking.py"
APP = ROOT / "app-android/app/src/main/java/com/davi/helper/PrivacyMasker.kt"

_PY_LITERAL = re.compile(r'r"((?:[^"\\]|\\.)*)"')
_KT_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')


def fail(message: str) -> None:
    print(f"마스킹 정규식 대조 실패: {message}", file=sys.stderr)
    sys.exit(1)


def server_bodies() -> list[str]:
    """`_PATTERNS`의 `re.compile(r"...")` 본문을 순서대로 뽑는다."""
    text = SERVER.read_text(encoding="utf-8")
    block = text.split("_PATTERNS", 1)[-1].split("\n]", 1)[0]
    # `re.compile(` 로 끊어 각 조각의 raw 리터럴만 모은다. 계좌 패턴처럼 **본문 안에 괄호가
    # 있는 여러 줄 호출**이라 닫는 괄호를 찾는 방식은 잘린다.
    bodies = []
    for segment in block.split("re.compile(")[1:]:
        parts = _PY_LITERAL.findall(segment)
        if not parts:
            fail("서버에서 정규식 리터럴을 못 읽었다 — 파서를 함께 고쳐라")
        bodies.append("".join(parts))
    return bodies


def app_bodies() -> list[str]:
    """`patternBodies` 목록의 문자열을 순서대로 뽑아 이스케이프를 되돌린다."""
    text = APP.read_text(encoding="utf-8")
    if "patternBodies" not in text:
        fail("앱에서 patternBodies를 못 찾았다 — 파서를 함께 고쳐라")
    block = text.split("patternBodies = listOf(", 1)[-1].split("\n    )", 1)[0]
    bodies, current = [], ""
    for line in block.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("//"):
            continue
        current += stripped
        if stripped.endswith(","):
            parts = _KT_LITERAL.findall(current)
            if not parts:
                fail("앱에서 정규식 리터럴을 못 읽었다 — 파서를 함께 고쳐라")
            bodies.append("".join(parts).replace("\\\\", "\\"))
            current = ""
    return bodies


def main() -> None:
    server, app = server_bodies(), app_bodies()
    if not server or not app:
        fail("한쪽에서 패턴을 하나도 읽지 못했다")
    if len(server) != len(app):
        fail(f"패턴 개수가 다르다 — 서버 {len(server)}개, 앱 {len(app)}개")
    for index, (want, got) in enumerate(zip(server, app)):
        if want != got:
            fail(
                f"{index}번 본문이 다르다\n  서버: {want}\n  앱  : {got}\n"
                "  앱은 컴파일 플래그로만 유니코드를 맞춘다. 본문 자체는 같아야 한다(#361)."
            )
    print(f"마스킹 정규식 {len(server)}개 본문 일치")


if __name__ == "__main__":
    main()
