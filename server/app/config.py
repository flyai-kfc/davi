"""환경설정. 값은 .env 또는 OS 환경변수에서 온다(.env.example 참고)."""

from __future__ import annotations

from typing import Literal, Self

from pydantic import SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# 공급자별 기본 모델과 키 환경변수. `DAVI_MODEL`을 비워 두면 여기 값이 쓰인다.
# 셋 다 "짧은 JSON 하나를 빠르게"에 맞춘 저지연 등급이다 — 품질이 더 필요하면 DAVI_MODEL로
# 올린다(예: gemini-3.6-flash, gpt-5.6-terra, claude-sonnet-5).
DEFAULT_MODELS: dict[str, str] = {
    "gemini": "gemini-3.5-flash-lite",
    "openai": "gpt-5.6-luna",
    "anthropic": "claude-haiku-4-5",
}
API_KEY_SETTINGS: dict[str, str] = {
    "gemini": "GEMINI_API_KEY",
    "openai": "OPENAI_API_KEY",
    "anthropic": "ANTHROPIC_API_KEY",
}


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", env_file=".env", extra="ignore")

    # 실행 프로필. 미설정 로컬/테스트는 development로 유지한다.
    davi_env: Literal["development", "production"] = "development"

    # LLM 공급자. 셋 다 같은 구조화 JSON 계약을 쓰고, 교체는 이 값 하나다(`llm_provider.py`).
    # 기본은 gemini — 기존 배포·eval·.env가 그대로 돌아야 한다.
    davi_llm_provider: Literal["gemini", "openai", "anthropic"] = "gemini"

    # 공급자 키. 고른 공급자의 키만 있으면 된다(아래 검증). 앱엔 절대 넣지 않는다.
    gemini_api_key: SecretStr = SecretStr("")
    openai_api_key: SecretStr = SecretStr("")
    anthropic_api_key: SecretStr = SecretStr("")

    # 모델은 환경변수로 둔다. HANDOFF §6: 아키텍처가 여기 묶여 있지 않다(교체 한 줄).
    # **비우면 공급자 기본 모델**(`DEFAULT_MODELS`)을 쓴다. 공급자만 바꾸고 모델을 안 고쳐서
    # gemini id가 Anthropic으로 나가는 사고를 막으려고 이렇게 뒀다.
    davi_model: str = ""
    # 출력 토큰 상한 강제값. 0이면 공급자별로 계산한다(`llm_provider.build_provider`).
    # 모델을 바꿨는데 답이 잘리거나 너무 비쌀 때만 손댄다.
    davi_max_output_tokens: int = 0

    # Gemini thinking 토큰 상한. 낮을수록 빠르지만 판단이 얕아지고, 높을수록 정확도 여지는
    # 늘되 느려지고 maxOutputTokens 예산을 다 써 응답이 잘릴 위험도 커진다(2026-08-18
    # gemini-3.6-flash PARSE 실패 실기기 재현). 세 호출부가 각자 maxOutputTokens = 이 값 +
    # 정상 응답 여유분으로 맞춰 잘림을 막는다. thinking이 없는 모델(flash-lite 등)에 보내도
    # 조용히 무시된다(실측).
    davi_gemini_thinking_budget: int = 800
    # OpenAI에서 thinking에 해당하는 값. 속도를 위해 낮게 둔다. none이면 reasoning 토큰
    # 여유분도 얹지 않는다. 판단이 얕으면 여기를 올리기 전에 모델을 올리는 편이 낫다.
    davi_openai_reasoning_effort: Literal["none", "low", "medium", "high", "xhigh", "max"] = "low"

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

    # 2차 마스킹 우회(#203). masked/unmasked 가이드 품질 비교 실험 전용이며 기본은 꺼짐.
    # 켜면 앱이 보낸 label이 2차 치환 없이 그대로 LLM에 전달된다. 아래 검증이 development·
    # loopback·개발 토큰 없음을 모두 요구하므로 production과 공용 개발 endpoint에서는 뜨지 않는다.
    davi_disable_privacy_masking: bool = False

    # 카카오 로그인(kakao_auth.py). 앱 ID는 검증용 숫자 ID이며 REST API 키가 아니다.
    # 둘 다 채워야 로그인 endpoint가 동작한다. 비어 있으면 SERVER 오류로 닫힌다.
    davi_kakao_app_id: int = 0
    davi_auth_jwt_secret: SecretStr = SecretStr("")
    # 세션 토큰 수명(초). 기본 30일 — 시니어 사용자가 자주 재로그인하지 않게 길게 둔다.
    davi_auth_session_ttl_seconds: int = 60 * 60 * 24 * 30
    # 로그인 endpoint 인프로세스 제한(#167 리뷰 P1). 인증 전 공개 endpoint가 요청마다
    # 카카오 API를 소모하므로 dev 전용이 아니라 **모든 프로필**에 적용한다. dev 리미터와 별도 창.
    # 이 두 값은 카카오 API 소모를 막는 **전체 상한**이다. 정상 사용자 보호는 아래 출처별
    # 제한이 맡으므로, 전체 상한은 동시 사용자 수를 수용할 만큼 넉넉히 둔다.
    davi_auth_rate_limit_requests: int = 100
    davi_auth_rate_limit_window_seconds: int = 60
    # 출처별 제한(#167 재리뷰 P1). 전체 창 하나만 있으면 한 출처의 폭주가 버킷을 소모해
    # 모든 정상 사용자의 로그인을 막는다. 출처는 직접 연결 peer IP다 — X-Forwarded-For를
    # 애플리케이션에서 파싱하지 않고, 프록시 뒤에서는 uvicorn --proxy-headers 경로를 쓴다.
    davi_auth_rate_limit_per_source_requests: int = 10
    davi_auth_rate_limit_per_source_window_seconds: int = 60

    @field_validator("gemini_api_key", "openai_api_key", "anthropic_api_key", mode="after")
    @classmethod
    def normalize_api_key(cls, value: SecretStr) -> SecretStr:
        """검증한 키와 실제 HTTP 헤더에 쓰는 키가 같은 값이게 한다."""

        return SecretStr(value.get_secret_value().strip())

    @property
    def provider_api_key(self) -> SecretStr:
        """지금 고른 공급자의 키. 나머지 공급자 키는 비어 있어도 된다."""

        return {
            "gemini": self.gemini_api_key,
            "openai": self.openai_api_key,
            "anthropic": self.anthropic_api_key,
        }[self.davi_llm_provider]

    @property
    def resolved_model(self) -> str:
        """실제로 호출에 쓰는 모델 id. 비어 있으면 공급자 기본값이다."""

        return self.davi_model.strip() or DEFAULT_MODELS[self.davi_llm_provider]

    @model_validator(mode="after")
    def validate_runtime_profile(self) -> Self:
        api_key = self.provider_api_key.get_secret_value()
        dev_access_token = self.davi_dev_access_token.get_secret_value().strip()
        bind_host = self.davi_bind_host.strip().lower()

        if self.davi_env == "production" and self.davi_use_stub_llm:
            raise ValueError("production에서는 DAVI_USE_STUB_LLM=false여야 합니다")
        if not self.davi_use_stub_llm and not api_key:
            key_setting = API_KEY_SETTINGS[self.davi_llm_provider]
            raise ValueError(
                f"실제 LLM을 사용하려면 {key_setting}가 필요합니다"
                f" (DAVI_LLM_PROVIDER={self.davi_llm_provider})"
            )
        if self.davi_gemini_thinking_budget < 0:
            raise ValueError("DAVI_GEMINI_THINKING_BUDGET은 0 이상이어야 합니다")
        if self.davi_max_output_tokens < 0:
            raise ValueError("DAVI_MAX_OUTPUT_TOKENS는 0 이상이어야 합니다")
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
        # 2차 마스킹 우회는 "내 맥에서만 도는 development 서버"로 경계를 좁힌다(#203).
        # production은 물론이고, 개발 토큰이 있거나 loopback 밖에 바인딩한 순간
        # 그 서버는 팀이 공유하는 endpoint이므로 다른 사람의 기기 화면까지 원문으로 받게 된다.
        # 순서는 좁은 제약부터다. 바인딩이 loopback 밖이면 토큰 유무와 무관하게 그 서버는
        # 다른 기기가 붙을 수 있는 자리이므로, 그 사실을 먼저 알려주는 편이 고치기 쉽다.
        if self.davi_disable_privacy_masking:
            if self.davi_env != "development":
                raise ValueError(
                    "DAVI_DISABLE_PRIVACY_MASKING은 development에서만 사용할 수 있습니다"
                )
            if bind_host not in {"127.0.0.1", "localhost", "::1"}:
                raise ValueError(
                    "DAVI_DISABLE_PRIVACY_MASKING은 loopback 바인딩에서만 사용할 수 있습니다"
                )
            if dev_access_token:
                raise ValueError(
                    "공용 개발 endpoint(DAVI_DEV_ACCESS_TOKEN 설정)에서는 "
                    "DAVI_DISABLE_PRIVACY_MASKING을 사용할 수 없습니다"
                )
        if self.davi_dev_rate_limit_requests <= 0:
            raise ValueError("DAVI_DEV_RATE_LIMIT_REQUESTS는 양수여야 합니다")
        if self.davi_dev_rate_limit_window_seconds <= 0:
            raise ValueError("DAVI_DEV_RATE_LIMIT_WINDOW_SECONDS는 양수여야 합니다")
        if not self.davi_dogfood_raw_dir.strip():
            raise ValueError("DAVI_DOGFOOD_RAW_DIR은 비어 있을 수 없습니다")
        auth_secret = self.davi_auth_jwt_secret.get_secret_value().strip()
        if auth_secret and len(auth_secret) < 32:
            raise ValueError("DAVI_AUTH_JWT_SECRET은 32자 이상이어야 합니다")
        if self.davi_kakao_app_id < 0:
            raise ValueError("DAVI_KAKAO_APP_ID는 음수일 수 없습니다")
        if self.davi_auth_session_ttl_seconds <= 0:
            raise ValueError("DAVI_AUTH_SESSION_TTL_SECONDS는 양수여야 합니다")
        if self.davi_auth_rate_limit_requests <= 0:
            raise ValueError("DAVI_AUTH_RATE_LIMIT_REQUESTS는 양수여야 합니다")
        if self.davi_auth_rate_limit_window_seconds <= 0:
            raise ValueError("DAVI_AUTH_RATE_LIMIT_WINDOW_SECONDS는 양수여야 합니다")
        if self.davi_auth_rate_limit_per_source_requests <= 0:
            raise ValueError("DAVI_AUTH_RATE_LIMIT_PER_SOURCE_REQUESTS는 양수여야 합니다")
        if self.davi_auth_rate_limit_per_source_window_seconds <= 0:
            raise ValueError("DAVI_AUTH_RATE_LIMIT_PER_SOURCE_WINDOW_SECONDS는 양수여야 합니다")
        # 출처 창이 전역 창보다 길면 전역 창 갱신마다 새 출처 키가 쌓여
        # "활성 키 수 ≤ 전역 상한" 불변식이 깨진다(#167 4차 리뷰 P2).
        if (
            self.davi_auth_rate_limit_per_source_window_seconds
            > self.davi_auth_rate_limit_window_seconds
        ):
            raise ValueError(
                "DAVI_AUTH_RATE_LIMIT_PER_SOURCE_WINDOW_SECONDS는 "
                "DAVI_AUTH_RATE_LIMIT_WINDOW_SECONDS 이하여야 합니다"
            )
        return self

    @property
    def dogfood_device_ids(self) -> set[str]:
        return {d.strip() for d in self.davi_dogfood_device_ids.split(",") if d.strip()}

    def should_store_raw(self, device_id: str | None) -> bool:
        """이 기기의 raw 화면 트리를 저장해도 되는가(도그푸딩 기기만).

        마스킹을 우회한 실행에서는 화이트리스트 기기라도 저장하지 않는다(#203). 도그푸딩
        보존은 1차·2차 마스킹을 통과한 봉투를 전제로 승인된 채널이고(SPEC §3), 우회 실행의
        원문 label은 그 전제를 만족하지 않는다. 우회 실험은 비식별 지표만 남긴다.
        """
        if self.davi_disable_privacy_masking:
            return False
        return bool(device_id) and device_id in self.dogfood_device_ids


settings = Settings()
