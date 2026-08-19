import pytest
from app.config import Settings
from pydantic import ValidationError


def _settings(**overrides) -> Settings:
    values = {
        "davi_env": "development",
        "gemini_api_key": "",
        "davi_dev_access_token": "",
        "davi_bind_host": "127.0.0.1",
        "davi_use_stub_llm": True,
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


def test_unknown_runtime_profile_is_rejected():
    with pytest.raises(ValidationError):
        _settings(davi_env="staging")


def test_missing_runtime_profile_defaults_to_development(monkeypatch):
    monkeypatch.delenv("DAVI_ENV", raising=False)

    configured = Settings(
        _env_file=None,
        gemini_api_key="",
        davi_dev_access_token="",
        davi_use_stub_llm=True,
    )

    assert configured.davi_env == "development"


def test_production_rejects_stub_llm():
    with pytest.raises(ValidationError, match="DAVI_USE_STUB_LLM=false"):
        _settings(
            davi_env="production",
            davi_use_stub_llm=True,
            gemini_api_key="server-side-key",
        )


def test_production_requires_non_empty_api_key():
    with pytest.raises(ValidationError, match="GEMINI_API_KEY"):
        _settings(davi_env="production", davi_use_stub_llm=False, gemini_api_key="  ")


def test_production_rejects_development_access_token():
    with pytest.raises(ValidationError, match="DAVI_DEV_ACCESS_TOKEN"):
        _settings(
            davi_env="production",
            davi_use_stub_llm=False,
            gemini_api_key="server-side-key",
            davi_dev_access_token="development-only-token",
        )


def test_development_stub_starts_without_api_key():
    configured = _settings(davi_dev_access_token="d" * 32)

    assert configured.davi_env == "development"
    assert configured.davi_use_stub_llm is True
    assert configured.gemini_api_key.get_secret_value() == ""
    assert configured.davi_dev_access_token.get_secret_value() == "d" * 32


def test_development_real_llm_requires_api_key():
    with pytest.raises(ValidationError, match="GEMINI_API_KEY"):
        _settings(davi_use_stub_llm=False)


def test_llm_api_key_is_normalized_before_use():
    configured = _settings(
        davi_use_stub_llm=False,
        gemini_api_key="  server-side-key\n",
    )

    assert configured.gemini_api_key.get_secret_value() == "server-side-key"


def test_blank_model_falls_back_to_the_provider_default():
    """공급자만 바꾸고 DAVI_MODEL을 안 고쳐서 엉뚱한 모델 id가 나가는 사고를 막는다."""

    assert _settings(davi_model=" ").resolved_model == "gemini-3.5-flash-lite"
    assert (
        _settings(
            davi_llm_provider="anthropic",
            davi_model="",
            gemini_api_key="",
            anthropic_api_key="server-side-key",
            davi_use_stub_llm=False,
        ).resolved_model
        == "claude-haiku-4-5"
    )


def test_configured_model_wins_over_the_provider_default():
    assert _settings(davi_model="gemini-3.6-flash").resolved_model == "gemini-3.6-flash"


def test_valid_production_profile():
    configured = _settings(
        davi_env="production",
        davi_use_stub_llm=False,
        gemini_api_key="server-side-key",
    )

    assert configured.davi_env == "production"
    assert configured.davi_use_stub_llm is False
    assert configured.gemini_api_key.get_secret_value() == "server-side-key"


def test_runtime_profile_fixture_rejects_impossible_production_stub(set_runtime_profile):
    with pytest.raises(ValidationError, match="DAVI_USE_STUB_LLM=false"):
        set_runtime_profile(
            davi_env="production",
            davi_use_stub_llm=True,
            gemini_api_key="server-side-key",
        )


def test_development_lan_bind_requires_access_token():
    with pytest.raises(ValidationError, match="DAVI_DEV_ACCESS_TOKEN"):
        _settings(davi_bind_host="0.0.0.0")


def test_development_lan_bind_accepts_strong_access_token():
    configured = _settings(
        davi_bind_host="0.0.0.0",
        davi_dev_access_token="d" * 32,
    )

    assert configured.davi_bind_host == "0.0.0.0"


def test_short_development_access_token_is_rejected():
    with pytest.raises(ValidationError, match="32자"):
        _settings(davi_dev_access_token="too-short")


def test_production_rejects_lan_bind():
    with pytest.raises(ValidationError, match="loopback"):
        _settings(
            davi_env="production",
            davi_use_stub_llm=False,
            gemini_api_key="server-side-key",
            davi_bind_host="0.0.0.0",
        )


def test_rate_limit_settings_must_be_positive():
    with pytest.raises(ValidationError, match="DAVI_DEV_RATE_LIMIT_REQUESTS"):
        _settings(davi_dev_rate_limit_requests=0)
    with pytest.raises(ValidationError, match="DAVI_DEV_RATE_LIMIT_WINDOW_SECONDS"):
        _settings(davi_dev_rate_limit_window_seconds=0)
    with pytest.raises(ValidationError, match="DAVI_AUTH_RATE_LIMIT_REQUESTS"):
        _settings(davi_auth_rate_limit_requests=0)
    with pytest.raises(ValidationError, match="DAVI_AUTH_RATE_LIMIT_WINDOW_SECONDS"):
        _settings(davi_auth_rate_limit_window_seconds=0)
    with pytest.raises(ValidationError, match="DAVI_AUTH_RATE_LIMIT_PER_SOURCE_REQUESTS"):
        _settings(davi_auth_rate_limit_per_source_requests=0)
    with pytest.raises(ValidationError, match="DAVI_AUTH_RATE_LIMIT_PER_SOURCE_WINDOW_SECONDS"):
        _settings(davi_auth_rate_limit_per_source_window_seconds=0)


def test_auth_source_window_must_not_exceed_global_window():
    """출처 창 > 전역 창이면 활성 키 수가 전역 상한을 넘는다(#167 4차 리뷰 P2)."""

    with pytest.raises(ValidationError, match="이하여야 합니다"):
        _settings(
            davi_auth_rate_limit_window_seconds=60,
            davi_auth_rate_limit_per_source_window_seconds=3600,
        )

    # 같은 값(기본)과 더 짧은 출처 창은 유효하다.
    _settings(
        davi_auth_rate_limit_window_seconds=60,
        davi_auth_rate_limit_per_source_window_seconds=60,
    )
    _settings(
        davi_auth_rate_limit_window_seconds=60,
        davi_auth_rate_limit_per_source_window_seconds=30,
    )


def test_validation_error_hides_secret_values():
    api_key = "private-api-key-value"
    dev_token = "private-development-token-value-123"

    with pytest.raises(ValidationError) as raised:
        _settings(
            davi_env="production",
            davi_use_stub_llm=False,
            gemini_api_key=api_key,
            davi_dev_access_token=dev_token,
        )

    rendered = str(raised.value)
    assert api_key not in rendered
    assert dev_token not in rendered


def test_llm_thinking_budget_defaults_to_800():
    assert _settings().davi_gemini_thinking_budget == 800


def test_llm_thinking_budget_is_configurable():
    configured = _settings(davi_gemini_thinking_budget=1500)

    assert configured.davi_gemini_thinking_budget == 1500


def test_llm_thinking_budget_rejects_negative_values():
    with pytest.raises(ValidationError, match="DAVI_GEMINI_THINKING_BUDGET"):
        _settings(davi_gemini_thinking_budget=-1)


def test_llm_thinking_budget_allows_zero():
    configured = _settings(davi_gemini_thinking_budget=0)

    assert configured.davi_gemini_thinking_budget == 0
