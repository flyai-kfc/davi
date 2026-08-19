from collections.abc import Callable
from typing import Any

import pytest
from app.config import Settings, settings
from app.intent_resolution import get_intent_resolver
from app.llm import get_llm_client
from app.main import (
    auth_rate_limiter,
    dev_rate_limiter,
    external_daily_rate_limiter,
    external_rate_limiter,
)


def _validated_runtime_profile(**overrides: Any) -> Settings:
    values = {
        "davi_env": "development",
        "gemini_api_key": "",
        "davi_model": "gemini-3.5-flash-lite",
        "davi_dev_access_token": "",
        "davi_bind_host": "127.0.0.1",
        "davi_dev_rate_limit_requests": 10,
        "davi_dev_rate_limit_window_seconds": 60,
        "davi_dogfood_device_ids": "",
        "davi_use_stub_llm": True,
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


def _apply_runtime_profile(monkeypatch: pytest.MonkeyPatch, profile: Settings) -> None:
    """Settings 검증을 통과한 프로필만 전역 객체에 반영한다."""

    for field_name in Settings.model_fields:
        monkeypatch.setattr(settings, field_name, getattr(profile, field_name))


@pytest.fixture(autouse=True)
def isolate_runtime_state(monkeypatch):
    """전역 설정과 LLM 캐시가 테스트 사이에 섞이지 않게 한다."""

    _apply_runtime_profile(monkeypatch, _validated_runtime_profile())
    get_llm_client.cache_clear()
    get_intent_resolver.cache_clear()
    dev_rate_limiter.reset()
    auth_rate_limiter.reset()
    external_rate_limiter.reset()
    external_daily_rate_limiter.reset()
    yield
    get_llm_client.cache_clear()
    get_intent_resolver.cache_clear()
    dev_rate_limiter.reset()
    auth_rate_limiter.reset()
    external_rate_limiter.reset()
    external_daily_rate_limiter.reset()


@pytest.fixture
def set_runtime_profile(
    monkeypatch: pytest.MonkeyPatch,
) -> Callable[..., Settings]:
    """테스트가 실제 Settings 규칙을 통과한 실행 프로필로만 전환하게 한다."""

    def apply(**overrides: Any) -> Settings:
        profile = _validated_runtime_profile(**overrides)
        _apply_runtime_profile(monkeypatch, profile)
        get_llm_client.cache_clear()
        return profile

    return apply
