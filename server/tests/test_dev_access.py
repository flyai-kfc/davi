import pytest
from app.dev_access import (
    SlidingWindowRateLimiter,
    SourceAndGlobalRateLimiter,
    token_matches,
)

_LIMITS = {
    "source_limit": 1,
    "source_window_seconds": 60,
    "global_limit": 100,
    "global_window_seconds": 60,
}


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


def test_source_limit_isolates_sources():
    """한 출처의 소진이 다른 출처의 창을 소모하지 않는다(#167 재리뷰 P1)."""

    limiter = SourceAndGlobalRateLimiter(FakeClock())

    assert limiter.check("attacker", **_LIMITS).allowed
    limited = limiter.check("attacker", **_LIMITS)
    assert not limited.allowed
    assert limited.limited_scope == "source"
    assert limited.retry_after_seconds == 60

    assert limiter.check("normal-user", **_LIMITS).allowed


def test_source_rejection_does_not_consume_global_budget():
    """출처 초과 요청이 전역 슬롯을 소모하면 한 출처가 전역 상한을 잠글 수 있다."""

    limiter = SourceAndGlobalRateLimiter(FakeClock())
    limits = dict(_LIMITS, global_limit=3)

    assert limiter.check("attacker", **limits).allowed
    for _ in range(10):
        assert limiter.check("attacker", **limits).limited_scope == "source"

    # 공격자의 거부 10회가 전역 슬롯을 쓰지 않았으므로 남은 2슬롯은 그대로다.
    assert limiter.check("user-1", **limits).allowed
    assert limiter.check("user-2", **limits).allowed
    assert limiter.check("user-3", **limits).limited_scope == "global"


def test_source_window_recovers_after_window():
    clock = FakeClock()
    limiter = SourceAndGlobalRateLimiter(clock)

    assert limiter.check("a", **_LIMITS).allowed
    assert not limiter.check("a", **_LIMITS).allowed

    clock.now = 60
    assert limiter.check("a", **_LIMITS).allowed


def test_rejected_sources_never_grow_tracked_keys():
    """전역 상한 소진 뒤 수천 개의 새 출처 요청에도 키 수는 전역 상한을 넘지 않는다(#167 3차 P1).

    거부된 요청은 출처 키를 만들지 않는다 — 키는 전역 슬롯을 소모한 요청만 만든다.
    """

    limiter = SourceAndGlobalRateLimiter(FakeClock())

    for i in range(_LIMITS["global_limit"]):
        assert limiter.check(f"filler-{i}", **_LIMITS).allowed
    assert limiter.tracked_key_count() == _LIMITS["global_limit"]

    for i in range(3000):
        decision = limiter.check(f"flood-{i}", **_LIMITS)
        assert decision.limited_scope == "global"
    assert limiter.tracked_key_count() == _LIMITS["global_limit"]


def test_check_rejects_source_window_longer_than_global():
    """출처 창 > 전역 창은 키 수 상한이 깨지는 조합이라 조용히 받지 않는다(#167 4차 P2)."""

    limiter = SourceAndGlobalRateLimiter(FakeClock())

    with pytest.raises(ValueError, match="전역 상한 보장"):
        limiter.check(
            "a",
            source_limit=10,
            source_window_seconds=3600,
            global_limit=100,
            global_window_seconds=60,
        )


def test_key_bound_holds_across_multiple_global_windows():
    """전역 창이 여러 번 갱신돼도 활성 키 수는 전역 상한을 넘지 않는다(#167 4차 P2).

    출처 창 ≤ 전역 창이 강제되므로, 새 전역 창의 새 출처가 허용되는 시점에는
    이전 창의 출처 키도 이미 만료돼 있다.
    """

    clock = FakeClock()
    limiter = SourceAndGlobalRateLimiter(clock)
    limits = {
        "source_limit": 1,
        "source_window_seconds": 30,
        "global_limit": 5,
        "global_window_seconds": 60,
    }

    for window_index in range(3):
        clock.now = window_index * 61.0
        for i in range(limits["global_limit"]):
            assert limiter.check(f"w{window_index}-s{i}", **limits).allowed
        # 전역 창이 찬 뒤의 새 출처는 거부되고 키도 남기지 않는다.
        for i in range(20):
            assert limiter.check(f"w{window_index}-x{i}", **limits).limited_scope == "global"
        assert limiter.tracked_key_count() <= limits["global_limit"]


def test_expired_sources_are_evicted():
    """창이 끝난 출처 키는 다음 검사에서 회수된다."""

    clock = FakeClock()
    limiter = SourceAndGlobalRateLimiter(clock)

    for i in range(50):
        assert limiter.check(f"source-{i}", **_LIMITS).allowed
    assert limiter.tracked_key_count() == 50

    clock.now = 61
    limiter.check("fresh", **_LIMITS)
    assert limiter.tracked_key_count() == 1


def test_source_and_global_reset_clears_all_state():
    limiter = SourceAndGlobalRateLimiter()
    assert limiter.check("a", **_LIMITS).allowed
    assert not limiter.check("a", **_LIMITS).allowed

    limiter.reset()

    assert limiter.check("a", **_LIMITS).allowed
    assert limiter.tracked_key_count() == 1
