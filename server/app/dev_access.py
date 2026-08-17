"""공용 development endpoint의 토큰 확인과 인프로세스 요청 제한."""

from __future__ import annotations

import math
import secrets
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass
from threading import Lock


def token_matches(expected: str, supplied: str | None) -> bool:
    """길이 차이까지 포함해 토큰을 상수 시간 비교한다."""

    if not expected or not supplied:
        return False
    return secrets.compare_digest(expected.encode(), supplied.encode())


@dataclass(frozen=True)
class RateLimitDecision:
    allowed: bool
    retry_after_seconds: int | None = None


class SlidingWindowRateLimiter:
    """단일 프로세스의 팀 전체 요청량을 제한한다.

    공용 개발 서버는 uvicorn worker 하나로만 실행한다. 사용자/IP별 제한보다 전역 제한이
    Gemini 쿼터와 비용을 직접 보호하며, 서버 재시작 시 상태가 사라지는 것은 개발 경로에서
    의도된 fail-open이다.
    """

    def __init__(self, clock: Callable[[], float] = time.monotonic):
        self._clock = clock
        self._timestamps: deque[float] = deque()
        self._lock = Lock()

    def check(self, limit: int, window_seconds: int) -> RateLimitDecision:
        now = self._clock()
        cutoff = now - window_seconds
        with self._lock:
            while self._timestamps and self._timestamps[0] <= cutoff:
                self._timestamps.popleft()

            if len(self._timestamps) >= limit:
                retry_after = max(
                    1,
                    math.ceil(window_seconds - (now - self._timestamps[0])),
                )
                return RateLimitDecision(False, retry_after)

            self._timestamps.append(now)
            return RateLimitDecision(True)

    def reset(self) -> None:
        """테스트와 개발 서버 재구성에서 이전 창을 명시적으로 비운다."""

        with self._lock:
            self._timestamps.clear()


class KeyedSlidingWindowRateLimiter:
    """출처(key)별 슬라이딩 창 제한.

    로그인처럼 인증 전 공개 endpoint에서는 전역 창 하나만 있으면 한 출처의 폭주가
    버킷을 소모해 모든 정상 사용자를 429로 막는다(#167 재리뷰 P1). 그래서 출처별로
    창을 분리한다. 창이 끝난 키는 검사 때마다 제거해 임의 출처 폭주로 키 수가
    무한히 늘지 않게 한다 — 유지되는 키는 현재 창 안에 요청이 있는 출처뿐이다.
    """

    def __init__(self, clock: Callable[[], float] = time.monotonic):
        self._clock = clock
        self._windows: dict[str, deque[float]] = {}
        self._lock = Lock()

    def check(self, key: str, limit: int, window_seconds: int) -> RateLimitDecision:
        now = self._clock()
        cutoff = now - window_seconds
        with self._lock:
            expired_keys = [
                k for k, window in self._windows.items() if not window or window[-1] <= cutoff
            ]
            for expired in expired_keys:
                del self._windows[expired]

            window = self._windows.setdefault(key, deque())
            while window and window[0] <= cutoff:
                window.popleft()

            if len(window) >= limit:
                retry_after = max(1, math.ceil(window_seconds - (now - window[0])))
                return RateLimitDecision(False, retry_after)

            window.append(now)
            return RateLimitDecision(True)

    def tracked_key_count(self) -> int:
        """메모리 상한 검증용 — 현재 창에 남아 있는 출처 수."""

        with self._lock:
            return len(self._windows)

    def reset(self) -> None:
        """테스트와 개발 서버 재구성에서 이전 창을 명시적으로 비운다."""

        with self._lock:
            self._windows.clear()
