import pytest
from app.config import Settings
from pydantic import ValidationError


def _settings(**overrides) -> Settings:
    values = {
        "davi_env": "development",
        "gemini_api_key": "",
        "davi_dev_access_token": "",
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
    configured = _settings(davi_dev_access_token="development-only-token")

    assert configured.davi_env == "development"
    assert configured.davi_use_stub_llm is True
    assert configured.gemini_api_key.get_secret_value() == ""
    assert configured.davi_dev_access_token.get_secret_value() == "development-only-token"


def test_development_real_llm_requires_api_key():
    with pytest.raises(ValidationError, match="GEMINI_API_KEY"):
        _settings(davi_use_stub_llm=False)


def test_valid_production_profile():
    configured = _settings(
        davi_env="production",
        davi_use_stub_llm=False,
        gemini_api_key="server-side-key",
    )

    assert configured.davi_env == "production"
    assert configured.davi_use_stub_llm is False
    assert configured.gemini_api_key.get_secret_value() == "server-side-key"


def test_validation_error_hides_secret_values():
    api_key = "private-api-key-value"
    dev_token = "private-development-token"

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
