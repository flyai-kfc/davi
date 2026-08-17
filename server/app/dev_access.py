"""공용 development endpoint의 토큰 확인과 인프로세스 요청 제한."""

from __future__ import annotations

import math
import secrets
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass
from threading import Lock
from typing import Literal


def token_matches(expected: str, supplied: str | None) -> bool:
    """길이 차이까지 포함해 토큰을 상수 시간 비교한다."""

    if not expected or not supplied:
        return False
    return secrets.compare_digest(expected.encode(), supplied.encode())


@dataclass(frozen=True)
class RateLimitDecision:
    allowed: bool
    retry_after_seconds: int | None = None
    # 거부한 계층. 단일 창 리미터는 None, 출처+전역 리미터는 "source"/"global"이다.
    limited_scope: Literal["source", "global"] | None = None


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


class SourceAndGlobalRateLimiter:
    """출처(key)별 제한과 전역 상한을 한 lock 안에서 원자적으로 판정한다.

    로그인처럼 인증 전 공개 endpoint에서는 전역 창 하나만 있으면 한 출처의 폭주가
    버킷을 소모해 모든 정상 사용자를 429로 막고(#167 재리뷰 P1), 반대로 판정을 두
    리미터로 나누면 전역 거부 전에 기록된 출처 키가 창이 끝날 때까지 남아 분산 출처
    폭주에서 키 수가 무한히 는다(#167 3차 리뷰 P1). 그래서 두 판정을 하나로 묶고
    **허용될 때만** 출처·전역 양쪽에 기록한다 — 거부된 요청은 어떤 흔적도 남기지
    않으므로, 활성 출처 키 수는 전역 상한을 소모한 요청 수로 묶인다.
    창이 끝난 키는 검사 때마다 제거한다.
    """

    def __init__(self, clock: Callable[[], float] = time.monotonic):
        self._clock = clock
        self._global_window: deque[float] = deque()
        self._windows: dict[str, deque[float]] = {}
        self._lock = Lock()

    def check(
        self,
        key: str,
        *,
        source_limit: int,
        source_window_seconds: int,
        global_limit: int,
        global_window_seconds: int,
    ) -> RateLimitDecision:
        now = self._clock()
        source_cutoff = now - source_window_seconds
        global_cutoff = now - global_window_seconds
        with self._lock:
            expired_keys = [
                k
                for k, window in self._windows.items()
                if not window or window[-1] <= source_cutoff
            ]
            for expired in expired_keys:
                del self._windows[expired]
            while self._global_window and self._global_window[0] <= global_cutoff:
                self._global_window.popleft()

            # 이 출처의 창부터 판정한다. 출처 초과 요청이 전역 슬롯을 소모하면
            # 한 출처의 폭주가 전역 상한을 잠그는 원래 문제로 되돌아간다.
            window = self._windows.get(key)
            if window is not None:
                while window and window[0] <= source_cutoff:
                    window.popleft()
                if len(window) >= source_limit:
                    retry_after = max(1, math.ceil(source_window_seconds - (now - window[0])))
                    return RateLimitDecision(False, retry_after, limited_scope="source")

            if len(self._global_window) >= global_limit:
                retry_after = max(
                    1, math.ceil(global_window_seconds - (now - self._global_window[0]))
                )
                return RateLimitDecision(False, retry_after, limited_scope="global")

            # 허용이 확정된 뒤에만 기록한다. 새 출처 키도 여기서만 생긴다.
            if window is None:
                window = self._windows.setdefault(key, deque())
            window.append(now)
            self._global_window.append(now)
            return RateLimitDecision(True)

    def tracked_key_count(self) -> int:
        """메모리 상한 검증용 — 현재 창에 남아 있는 출처 수."""

        with self._lock:
            return len(self._windows)

    def reset(self) -> None:
        """테스트와 개발 서버 재구성에서 이전 창을 명시적으로 비운다."""

        with self._lock:
            self._global_window.clear()
            self._windows.clear()
