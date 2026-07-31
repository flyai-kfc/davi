"""환경설정. 값은 .env 또는 OS 환경변수에서 온다(.env.example 참고)."""

from __future__ import annotations

from typing import Literal, Self

from pydantic import SecretStr, field_validator, model_validator
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
    # 공식 실행 스크립트가 uvicorn --host와 같은 값으로 설정한다.
    davi_bind_host: str = "127.0.0.1"
    # 같은 Wi-Fi 팀 전체가 공유하는 인프로세스 제한. Gemini 무료 티어(약 15 RPM)보다 낮게 둔다.
    davi_dev_rate_limit_requests: int = 10
    davi_dev_rate_limit_window_seconds: int = 60

    # 도그푸딩 기기에서만 raw 트리 로그 보존(SPEC §3). 콤마 구분 device id(앱이 찍는 16자 해시).
    davi_dogfood_device_ids: str = ""
    # raw 봉투 저장 위치(#41). 상대 경로는 server/ 루트 기준. 영구 보존, 정리는 수동.
    davi_dogfood_raw_dir: str = ".dogfood-raw"

    # 키 없이도 뜨는 스텁 LLM. 테스트/오프라인용.
    davi_use_stub_llm: bool = True

    @field_validator("gemini_api_key", mode="after")
    @classmethod
    def normalize_gemini_api_key(cls, value: SecretStr) -> SecretStr:
        """검증한 키와 실제 HTTP 헤더에 쓰는 키가 같은 값이게 한다."""

        return SecretStr(value.get_secret_value().strip())

    @model_validator(mode="after")
    def validate_runtime_profile(self) -> Self:
        api_key = self.gemini_api_key.get_secret_value()
        dev_access_token = self.davi_dev_access_token.get_secret_value().strip()
        bind_host = self.davi_bind_host.strip().lower()
        model = self.davi_model.strip()

        if self.davi_env == "production" and self.davi_use_stub_llm:
            raise ValueError("production에서는 DAVI_USE_STUB_LLM=false여야 합니다")
        if not self.davi_use_stub_llm and not api_key:
            raise ValueError("실제 LLM을 사용하려면 GEMINI_API_KEY가 필요합니다")
        if not model:
            raise ValueError("DAVI_MODEL은 비어 있을 수 없습니다")
        if self.davi_env == "production" and dev_access_token:
            raise ValueError("production에서는 DAVI_DEV_ACCESS_TOKEN을 설정할 수 없습니다")
        if dev_access_token and len(dev_access_token) < 32:
            raise ValueError("DAVI_DEV_ACCESS_TOKEN은 32자 이상이어야 합니다")
        if self.davi_env == "production" and bind_host not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("production은 loopback 주소에만 바인딩할 수 있습니다")
        if (
            self.davi_env == "development"
            and bind_host not in {"127.0.0.1", "localhost", "::1"}
            and not dev_access_token
        ):
            raise ValueError("development LAN 바인딩에는 DAVI_DEV_ACCESS_TOKEN이 필요합니다")
        if self.davi_dev_rate_limit_requests <= 0:
            raise ValueError("DAVI_DEV_RATE_LIMIT_REQUESTS는 양수여야 합니다")
        if self.davi_dev_rate_limit_window_seconds <= 0:
            raise ValueError("DAVI_DEV_RATE_LIMIT_WINDOW_SECONDS는 양수여야 합니다")
        if not self.davi_dogfood_raw_dir.strip():
            raise ValueError("DAVI_DOGFOOD_RAW_DIR은 비어 있을 수 없습니다")
        return self

    @property
    def dogfood_device_ids(self) -> set[str]:
        return {d.strip() for d in self.davi_dogfood_device_ids.split(",") if d.strip()}

    def should_store_raw(self, device_id: str | None) -> bool:
        """이 기기의 raw 화면 트리를 저장해도 되는가(도그푸딩 기기만)."""
        return bool(device_id) and device_id in self.dogfood_device_ids


settings = Settings()
