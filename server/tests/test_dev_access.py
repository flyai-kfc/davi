from app.dev_access import SlidingWindowRateLimiter, token_matches


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
