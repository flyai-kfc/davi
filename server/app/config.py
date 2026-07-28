"""환경설정. 값은 .env 또는 OS 환경변수에서 온다(.env.example 참고)."""

from __future__ import annotations

from typing import Literal, Self

from pydantic import SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", env_file=".env", extra="ignore")

    # 실행 프로필. 미설정 로컬/테스트는 development로 유지한다.
    davi_env: Literal["development", "production"] = "development"

    # Gemini
    gemini_api_key: SecretStr = SecretStr("")
    # 모델은 환경변수로 둔다. HANDOFF §6: 아키텍처가 여기 묶여 있지 않다(교체 한 줄).
    davi_model: str = "gemini-3.5-flash-lite"

    # 공용 개발 endpoint 보호용 토큰(#57). production에서는 설정 자체를 금지한다.
    davi_dev_access_token: SecretStr = SecretStr("")

    # 도그푸딩 기기에서만 raw 트리 로그 보존(SPEC §3). 콤마 구분 device id.
    davi_dogfood_device_ids: str = ""

    # 키 없이도 뜨는 스텁 LLM. 테스트/오프라인용.
    davi_use_stub_llm: bool = True

    @model_validator(mode="after")
    def validate_runtime_profile(self) -> Self:
        api_key = self.gemini_api_key.get_secret_value().strip()
        dev_access_token = self.davi_dev_access_token.get_secret_value().strip()

        if self.davi_env == "production" and self.davi_use_stub_llm:
            raise ValueError("production에서는 DAVI_USE_STUB_LLM=false여야 합니다")
        if not self.davi_use_stub_llm and not api_key:
            raise ValueError("실제 LLM을 사용하려면 GEMINI_API_KEY가 필요합니다")
        if self.davi_env == "production" and dev_access_token:
            raise ValueError("production에서는 DAVI_DEV_ACCESS_TOKEN을 설정할 수 없습니다")
        return self

    @property
    def dogfood_device_ids(self) -> set[str]:
        return {d.strip() for d in self.davi_dogfood_device_ids.split(",") if d.strip()}

    def should_store_raw(self, device_id: str | None) -> bool:
        """이 기기의 raw 화면 트리를 저장해도 되는가(도그푸딩 기기만)."""
        return bool(device_id) and device_id in self.dogfood_device_ids


settings = Settings()
