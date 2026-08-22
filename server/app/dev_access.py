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
    # 여러 시간 창을 한 번에 판정할 때 거부한 창의 이름. 단일 창 리미터는 None이다.
    limited_window: str | None = None


@dataclass(frozen=True)
class RateLimitWindow:
    """원자적으로 함께 소비할 출처별+전역 슬라이딩 창 하나."""

    name: str
    source_limit: int
    source_window_seconds: int
    global_limit: int
    global_window_seconds: int


@dataclass
class _WindowState:
    global_window: deque[float]
    source_windows: dict[str, deque[float]]


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


class MultiWindowRateLimiter:
    """여러 시간 창의 출처별·전역 상한을 한 lock 안에서 원자적으로 판정한다.

    짧은 폭주 창과 하루 누적 창을 리미터 두 개로 순서대로 검사하면, 첫 창이 허용 기록을
    남긴 뒤 둘째 창이 거부할 수 있다. 그러면 실제로 처리하지 않은 요청이 첫 창의 슬롯을
    소모해 다른 사용자를 막는다. 이 클래스는 모든 창을 먼저 판정하고 **전부 허용될 때만**
    모든 창에 같은 요청을 기록한다.

    창별 출처 키 수 상한은 아래 [SourceAndGlobalRateLimiter]와 같은 조건, 즉 출처 창이 전역
    창보다 길지 않을 때 유지된다. 거부된 요청은 어느 창에도 출처 키를 만들지 않는다.
    """

    def __init__(self, clock: Callable[[], float] = time.monotonic):
        self._clock = clock
        self._states: dict[str, _WindowState] = {}
        self._lock = Lock()

    def check(self, key: str, windows: tuple[RateLimitWindow, ...]) -> RateLimitDecision:
        if not windows:
            raise ValueError("하나 이상의 rate limit window가 필요합니다")
        names = [window.name for window in windows]
        if len(set(names)) != len(names):
            raise ValueError("rate limit window 이름은 중복될 수 없습니다")
        for window in windows:
            if window.source_window_seconds > window.global_window_seconds:
                raise ValueError(
                    "source_window_seconds가 global_window_seconds보다 길면 "
                    "활성 출처 키 수의 전역 상한 보장이 깨진다"
                )

        now = self._clock()
        with self._lock:
            prepared: list[tuple[RateLimitWindow, _WindowState, deque[float] | None]] = []
            for window in windows:
                state = self._states.setdefault(
                    window.name,
                    _WindowState(global_window=deque(), source_windows={}),
                )
                source_cutoff = now - window.source_window_seconds
                global_cutoff = now - window.global_window_seconds
                expired_keys = [
                    source
                    for source, timestamps in state.source_windows.items()
                    if not timestamps or timestamps[-1] <= source_cutoff
                ]
                for expired in expired_keys:
                    del state.source_windows[expired]
                while state.global_window and state.global_window[0] <= global_cutoff:
                    state.global_window.popleft()

                source_timestamps = state.source_windows.get(key)
                if source_timestamps is not None:
                    while source_timestamps and source_timestamps[0] <= source_cutoff:
                        source_timestamps.popleft()
                    if len(source_timestamps) >= window.source_limit:
                        retry_after = max(
                            1,
                            math.ceil(window.source_window_seconds - (now - source_timestamps[0])),
                        )
                        return RateLimitDecision(
                            False,
                            retry_after,
                            limited_scope="source",
                            limited_window=window.name,
                        )

                if len(state.global_window) >= window.global_limit:
                    retry_after = max(
                        1,
                        math.ceil(window.global_window_seconds - (now - state.global_window[0])),
                    )
                    return RateLimitDecision(
                        False,
                        retry_after,
                        limited_scope="global",
                        limited_window=window.name,
                    )
                prepared.append((window, state, source_timestamps))

            # 모든 창의 허용이 확정된 뒤에만 한꺼번에 기록한다.
            for _window, state, source_timestamps in prepared:
                if source_timestamps is None:
                    source_timestamps = state.source_windows.setdefault(key, deque())
                source_timestamps.append(now)
                state.global_window.append(now)
            return RateLimitDecision(True)

    def tracked_key_count(self, window_name: str) -> int:
        """메모리 상한 검증용 — 지정한 창에 남아 있는 출처 수."""

        with self._lock:
            state = self._states.get(window_name)
            return len(state.source_windows) if state is not None else 0

    def reset(self) -> None:
        """테스트와 서버 재구성에서 모든 창을 명시적으로 비운다."""

        with self._lock:
            self._states.clear()


class SourceAndGlobalRateLimiter:
    """출처(key)별 제한과 전역 상한을 한 lock 안에서 원자적으로 판정한다.

    로그인처럼 인증 전 공개 endpoint에서는 전역 창 하나만 있으면 한 출처의 폭주가
    버킷을 소모해 모든 정상 사용자를 429로 막고(#167 재리뷰 P1), 반대로 판정을 두
    리미터로 나누면 전역 거부 전에 기록된 출처 키가 창이 끝날 때까지 남아 분산 출처
    폭주에서 키 수가 무한히 는다(#167 3차 리뷰 P1). 그래서 두 판정을 하나로 묶고
    **허용될 때만** 출처·전역 양쪽에 기록한다 — 거부된 요청은 어떤 흔적도 남기지
    않으므로, 활성 출처 키 수는 전역 상한을 소모한 요청 수로 묶인다.
    창이 끝난 키는 검사 때마다 제거한다.

    이 키 수 상한은 **출처 창 ≤ 전역 창**일 때만 성립한다(#167 4차 리뷰 P2). 출처 창이
    더 길면 전역 창이 갱신될 때마다 새 출처가 전역 상한만큼 허용되는데 기존 키는 아직
    안 죽어, 키 수가 전역 창 갱신 횟수에 비례해 는다. 그래서 위반 조합은 조용히 받지
    않고 거부한다 — 설정 계층(config.Settings)도 기동 시 같은 제약을 검증한다.
    """

    def __init__(self, clock: Callable[[], float] = time.monotonic):
        self._limiter = MultiWindowRateLimiter(clock)

    def check(
        self,
        key: str,
        *,
        source_limit: int,
        source_window_seconds: int,
        global_limit: int,
        global_window_seconds: int,
    ) -> RateLimitDecision:
        decision = self._limiter.check(
            key,
            (
                RateLimitWindow(
                    name="single",
                    source_limit=source_limit,
                    source_window_seconds=source_window_seconds,
                    global_limit=global_limit,
                    global_window_seconds=global_window_seconds,
                ),
            ),
        )
        return RateLimitDecision(
            allowed=decision.allowed,
            retry_after_seconds=decision.retry_after_seconds,
            limited_scope=decision.limited_scope,
        )

    def tracked_key_count(self) -> int:
        """메모리 상한 검증용 — 현재 창에 남아 있는 출처 수."""

        return self._limiter.tracked_key_count("single")

    def reset(self) -> None:
        """테스트와 개발 서버 재구성에서 이전 창을 명시적으로 비운다."""

        self._limiter.reset()
