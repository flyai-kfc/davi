from app.dev_access import (
    KeyedSlidingWindowRateLimiter,
    SlidingWindowRateLimiter,
    token_matches,
)


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


def test_token_match_requires_exact_non_empty_value():
    expected = "t" * 32

    assert token_matches(expected, expected)
    assert not token_matches(expected, None)
    assert not token_matches(expected, "")
    assert not token_matches(expected, "w" * 32)


def test_sliding_window_limits_then_recovers():
    clock = FakeClock()
    limiter = SlidingWindowRateLimiter(clock)

    assert limiter.check(limit=2, window_seconds=60).allowed
    clock.now = 1
    assert limiter.check(limit=2, window_seconds=60).allowed

    clock.now = 2
    limited = limiter.check(limit=2, window_seconds=60)
    assert not limited.allowed
    assert limited.retry_after_seconds == 58

    clock.now = 60
    assert limiter.check(limit=2, window_seconds=60).allowed


def test_reset_clears_the_active_window():
    limiter = SlidingWindowRateLimiter()
    assert limiter.check(limit=1, window_seconds=60).allowed
    assert not limiter.check(limit=1, window_seconds=60).allowed

    limiter.reset()

    assert limiter.check(limit=1, window_seconds=60).allowed


def test_keyed_window_isolates_sources():
    """한 출처의 소진이 다른 출처의 창을 소모하지 않는다(#167 재리뷰 P1)."""

    limiter = KeyedSlidingWindowRateLimiter(FakeClock())

    assert limiter.check("attacker", limit=1, window_seconds=60).allowed
    limited = limiter.check("attacker", limit=1, window_seconds=60)
    assert not limited.allowed
    assert limited.retry_after_seconds == 60

    assert limiter.check("normal-user", limit=1, window_seconds=60).allowed


def test_keyed_window_recovers_after_window():
    clock = FakeClock()
    limiter = KeyedSlidingWindowRateLimiter(clock)

    assert limiter.check("a", limit=1, window_seconds=60).allowed
    assert not limiter.check("a", limit=1, window_seconds=60).allowed

    clock.now = 60
    assert limiter.check("a", limit=1, window_seconds=60).allowed


def test_keyed_window_evicts_expired_sources():
    """창이 끝난 출처는 제거된다 — 임의 출처 폭주로 키가 무한히 쌓이지 않는다."""

    clock = FakeClock()
    limiter = KeyedSlidingWindowRateLimiter(clock)

    for i in range(100):
        assert limiter.check(f"source-{i}", limit=1, window_seconds=60).allowed
    assert limiter.tracked_key_count() == 100

    clock.now = 61
    limiter.check("fresh", limit=1, window_seconds=60)
    assert limiter.tracked_key_count() == 1


def test_keyed_window_reset_clears_all_sources():
    limiter = KeyedSlidingWindowRateLimiter()
    assert limiter.check("a", limit=1, window_seconds=60).allowed
    assert not limiter.check("a", limit=1, window_seconds=60).allowed

    limiter.reset()

    assert limiter.check("a", limit=1, window_seconds=60).allowed
    assert limiter.tracked_key_count() == 1
