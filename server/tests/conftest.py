import pytest
from app.config import settings
from app.llm import get_llm_client
from app.main import dev_rate_limiter
from pydantic import SecretStr


@pytest.fixture(autouse=True)
def isolate_runtime_state(monkeypatch):
    """전역 설정과 LLM 캐시가 테스트 사이에 섞이지 않게 한다."""

    monkeypatch.setattr(settings, "davi_env", "development")
    monkeypatch.setattr(settings, "gemini_api_key", SecretStr(""))
    monkeypatch.setattr(settings, "davi_model", "gemini-3.5-flash-lite")
    monkeypatch.setattr(settings, "davi_dev_access_token", SecretStr(""))
    monkeypatch.setattr(settings, "davi_bind_host", "127.0.0.1")
    monkeypatch.setattr(settings, "davi_dev_rate_limit_requests", 10)
    monkeypatch.setattr(settings, "davi_dev_rate_limit_window_seconds", 60)
    monkeypatch.setattr(settings, "davi_dogfood_device_ids", "")
    monkeypatch.setattr(settings, "davi_use_stub_llm", True)
    get_llm_client.cache_clear()
    dev_rate_limiter.reset()
    yield
    get_llm_client.cache_clear()
    dev_rate_limiter.reset()
