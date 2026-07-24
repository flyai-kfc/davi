"""환경설정. 값은 .env 또는 OS 환경변수에서 온다(.env.example 참고)."""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", env_file=".env", extra="ignore")

    # Gemini
    gemini_api_key: str = ""
    # 모델은 환경변수로 둔다. HANDOFF §6: 아키텍처가 여기 묶여 있지 않다(교체 한 줄).
    davi_model: str = "gemini-3.5-flash-lite"

    # 도그푸딩 기기에서만 raw 트리 로그 보존(SPEC §3). 콤마 구분 device id.
    davi_dogfood_device_ids: str = ""

    # 키 없이도 뜨는 스텁 LLM. 테스트/오프라인용.
    davi_use_stub_llm: bool = True

    @property
    def dogfood_device_ids(self) -> set[str]:
        return {d.strip() for d in self.davi_dogfood_device_ids.split(",") if d.strip()}

    def should_store_raw(self, device_id: str | None) -> bool:
        """이 기기의 raw 화면 트리를 저장해도 되는가(도그푸딩 기기만)."""
        return bool(device_id) and device_id in self.dogfood_device_ids


settings = Settings()
