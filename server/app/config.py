"""환경설정. 값은 .env 또는 OS 환경변수에서 온다(.env.example 참고)."""

from __future__ import annotations

from typing import Literal, Self

from pydantic import SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from .gemini_thinking import gemini_thinking_policy

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
    # beta는 카카오 로그인으로 실사용자를 받는 외부 공개 코드 경계다(#214) — production과
    # 마찬가지로 stub/개발 토큰을 금지하고 세션 인증을 요구한다(main.py). 현재 별도 관리형
    # beta 배포는 없고, release Container App은 가장 엄격한 production을 명시한다(SPEC §7).
    davi_env: Literal["development", "beta", "production"] = "development"

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
    # 정상 계약 응답 + 보이지 않는 추론 몫보다 작은 값은 공급자를 만들 때 거부한다. 따라서
    # 이 값은 모델을 바꿔 자동 계산보다 더 큰 예산이 필요할 때만 올리는 안전한 상향 override다.
    davi_max_output_tokens: int = 0

    # Gemini 2.5에서는 모델별 범위를 검증한 numeric thinking hard cap, 3.x·알 수 없는 모델에서는
    # wire로 보내지 않는 출력 reserve다. 0은 thinking을 끌 수 있는 2.5 Flash 계열만 허용한다.
    # 세 호출부가 maxOutputTokens = reserve + 정상 응답 여유분으로 맞춰 잘림을 막는다.
    davi_gemini_thinking_budget: int = 800
    # OpenAI에서 thinking에 해당하는 값. 속도를 위해 낮게 둔다. none이면 reasoning 토큰
    # 여유분도 얹지 않는다. 판단이 얕으면 여기를 올리기 전에 모델을 올리는 편이 낫다.
    davi_openai_reasoning_effort: Literal["none", "low", "medium", "high", "xhigh", "max"] = "low"

    # 공용 개발 endpoint 보호용 토큰(#57). production에서는 설정 자체를 금지한다.
    davi_dev_access_token: SecretStr = SecretStr("")
    # 외부 테스트 참가자에게 나눠 주는 둘째 토큰. 개발 토큰과 **병행**한다 — 서버는 둘 중
    # 하나만 맞으면 통과시킨다. 하나로 합치지 않는 이유는 **폐기 시점이 다르기** 때문이다:
    # 테스트가 끝나면 이 토큰만 지우면 되고, 팀의 개발 토큰은 그대로 살아 있다. 값이 새도
    # 폐기 범위가 테스트 참가자로 제한된다.
    davi_test_access_token: SecretStr = SecretStr("")
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

    # 스텁 프로브(#480). 앱의 **완료 재확인 루프**를 실기기에서 재현하는 두 손잡이다.
    # 실모델은 미검증 done을 마음대로 안 내주므로 그 구간을 관측할 방법이 달리 없다 —
    # 실제로 #480은 저장소 밖의 일회용 프로브로 쟀고, 그것이 사라져 재현이 두 번 막혔다.
    #
    # force_done은 화면을 보지 않고 done을 돌려준다. 앱의 완료 검증이 근거를 못 찾아
    # `inconclusive`로 내리고, `MAX_UNVERIFIED_DONE`까지 백오프 재질의가 반복된다.
    # delay_ms는 그 앞에 인위 지연을 넣는다 — **0으로도 한 번 돌려서** 백오프 침묵이
    # 서버 지연과 무관한 고정 비용임을 분리하는 것이 이 손잡이의 요점이다(#480 런 C).
    #
    # 아래 검증이 development·스텁 사용·loopback·개발 토큰 없음을 모두 요구한다.
    davi_stub_force_done: bool = False
    davi_stub_delay_ms: int = 0

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
    # 로그인 레이트리밋의 네트워크 출처를 어디서 읽을지 정한다. 기본 peer는 로컬·LAN의
    # 직접 연결 주소다. Azure Container Apps HTTP ingress에서는 플랫폼이 보장하는
    # X-Forwarded-For의 가장 오른쪽 IP만 쓴다(main.py). 사용자 식별·권한에는 쓰지 않는다.
    davi_auth_source: Literal["peer", "azure_container_apps"] = "peer"
    # 로그인 endpoint 인프로세스 제한(#167 리뷰 P1). 인증 전 공개 endpoint가 요청마다
    # 카카오 API를 소모하므로 dev 전용이 아니라 **모든 프로필**에 적용한다. dev 리미터와 별도 창.
    # 이 두 값은 카카오 API 소모를 막는 **전체 상한**이다. 정상 사용자 보호는 아래 출처별
    # 제한이 맡으므로, 전체 상한은 동시 사용자 수를 수용할 만큼 넉넉히 둔다.
    davi_auth_rate_limit_requests: int = 100
    davi_auth_rate_limit_window_seconds: int = 60
    # 출처별 제한(#167 재리뷰 P1). 전체 창 하나만 있으면 한 출처의 폭주가 버킷을 소모해
    # 모든 정상 사용자의 로그인을 막는다. 출처 선택은 위 DAVI_AUTH_SOURCE가 맡는다.
    davi_auth_rate_limit_per_source_requests: int = 10
    davi_auth_rate_limit_per_source_window_seconds: int = 60

    # beta·production에서 세션 인증된 추론 endpoint(/v1/next-step 등)를 지키는 제한(#214).
    # 사용자별 15회는 목표 정리·앱 판정 2회 뒤 최근 안내 이력 상한 10스텝을 한 분 안에
    # 진행해도 여유 3회를 남긴다. 전체 60회는 그 최댓값을 쓰는 사용자 4명을 동시에 받는다.
    # 이 값은 공급자 RPM을 대신하지 않는 앱의 남용 경계다 — 공급자 쿼터는 배포 환경에서
    # 별도로 맞추고, 실제 beta 동시 사용자가 늘면 관측 뒤 환경변수로 조정한다.
    davi_external_rate_limit_requests: int = 60
    davi_external_rate_limit_window_seconds: int = 60
    davi_external_rate_limit_per_source_requests: int = 15
    davi_external_rate_limit_per_source_window_seconds: int = 60
    # 짧은 창과 별개로 하루 단위 상한도 둔다(창은 24시간 고정 — main.py). 짧은 창은 폭주를,
    # 이 상한은 하루 동안의 누적 사용량을 막는다. 공급자 무료 티어 RPD(약 1,000)보다 낮게 둔다.
    davi_external_daily_limit_requests: int = 900
    davi_external_daily_limit_per_source_requests: int = 200

    @property
    def access_tokens(self) -> dict[str, str]:
        """이 서버가 받아들이는 개발용 토큰. 값이 있는 것만 담는다.

        비어 있으면 토큰 게이트 자체가 꺼지고 loopback 검사만 남는다(`main.py`).
        키는 로그에 남길 토큰 종류이고, 값은 절대 로그에 남기지 않는다.
        """

        candidates = {
            "dev": self.davi_dev_access_token.get_secret_value().strip(),
            "test": self.davi_test_access_token.get_secret_value().strip(),
        }
        return {kind: token for kind, token in candidates.items() if token}

    @property
    def kakao_auth_configured(self) -> bool:
        """카카오 로그인 endpoint가 열려 있는가.

        판정을 여기 하나로 둔다 — `kakao_auth.py`의 게이트와 `/health`가 각자 조건을 적으면
        한쪽만 고쳐져 "배포는 통과했는데 로그인은 닫혀 있다"가 된다. `/health`가 이 값을
        알리는 이유는 배포 검증이 `/v1/auth/kakao`를 찌를 수 없어서다(#279): 그 endpoint는
        개발 토큰 게이트 뒤에 있다. 불리언만 알리고 앱 ID·서명 비밀은 노출하지 않는다.
        """

        return bool(self.davi_auth_jwt_secret.get_secret_value().strip()) and (
            self.davi_kakao_app_id > 0
        )

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
        test_access_token = self.davi_test_access_token.get_secret_value().strip()
        bind_host = self.davi_bind_host.strip().lower()

        if self.davi_env != "development" and self.davi_use_stub_llm:
            raise ValueError("beta·production에서는 DAVI_USE_STUB_LLM=false여야 합니다")
        if not self.davi_use_stub_llm and not api_key:
            key_setting = API_KEY_SETTINGS[self.davi_llm_provider]
            raise ValueError(
                f"실제 LLM을 사용하려면 {key_setting}가 필요합니다"
                f" (DAVI_LLM_PROVIDER={self.davi_llm_provider})"
            )
        if self.davi_gemini_thinking_budget < 0:
            raise ValueError("DAVI_GEMINI_THINKING_BUDGET은 0 이상이어야 합니다")
        if self.davi_llm_provider == "gemini":
            gemini_thinking_policy(self.resolved_model, self.davi_gemini_thinking_budget)
        if self.davi_max_output_tokens < 0:
            raise ValueError("DAVI_MAX_OUTPUT_TOKENS는 0 이상이어야 합니다")
        if self.davi_env != "development" and dev_access_token:
            raise ValueError("beta·production에서는 DAVI_DEV_ACCESS_TOKEN을 설정할 수 없습니다")
        if self.davi_env != "development" and test_access_token:
            raise ValueError("beta·production에서는 DAVI_TEST_ACCESS_TOKEN을 설정할 수 없습니다")
        if self.davi_env != "production" and self.davi_auth_source != "peer":
            raise ValueError(
                "DAVI_AUTH_SOURCE=azure_container_apps는 production에서만 사용할 수 있습니다"
            )
        if dev_access_token and len(dev_access_token) < 32:
            raise ValueError("DAVI_DEV_ACCESS_TOKEN은 32자 이상이어야 합니다")
        if test_access_token and len(test_access_token) < 32:
            raise ValueError("DAVI_TEST_ACCESS_TOKEN은 32자 이상이어야 합니다")
        # 두 토큰이 같으면 "테스트 토큰만 폐기한다"가 성립하지 않는다. 폐기 범위를 나누려고
        # 둘로 나눈 것이므로 같은 값을 쓰는 설정은 기동 때 막는다.
        if dev_access_token and dev_access_token == test_access_token:
            raise ValueError("DAVI_TEST_ACCESS_TOKEN은 DAVI_DEV_ACCESS_TOKEN과 달라야 합니다")
        # beta·production의 외부 바인딩은 세션 인증이 지킨다(#214). development의 LAN
        # 바인딩만 아래에서 개발·테스트 접근 토큰 중 하나를 요구한다.
        # 토큰 게이트를 여는 것은 둘 중 **아무 토큰이나 하나**다. 테스트 기간에 개발 토큰을
        # 잠시 비우고 테스트 토큰만 두는 운용도 막지 않는다.
        if (
            self.davi_env == "development"
            and bind_host not in {"127.0.0.1", "localhost", "::1"}
            and not (dev_access_token or test_access_token)
        ):
            raise ValueError(
                "development LAN 바인딩에는 DAVI_DEV_ACCESS_TOKEN 또는 "
                "DAVI_TEST_ACCESS_TOKEN이 필요합니다"
            )
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
            if dev_access_token or test_access_token:
                raise ValueError(
                    "공용 개발 endpoint(DAVI_DEV_ACCESS_TOKEN·DAVI_TEST_ACCESS_TOKEN 설정)"
                    "에서는 DAVI_DISABLE_PRIVACY_MASKING을 사용할 수 없습니다"
                )
        if self.davi_stub_delay_ms < 0:
            raise ValueError("DAVI_STUB_DELAY_MS는 0 이상이어야 합니다")
        if self.davi_stub_force_done or self.davi_stub_delay_ms:
            # 두 값을 한 게이트로 묶는다 — 지연만 켜는 것도 응답 시간을 거짓으로 만드는
            # 실험이라 공용 endpoint에 있어서는 안 된다.
            if self.davi_env != "development":
                raise ValueError(
                    "DAVI_STUB_FORCE_DONE·DAVI_STUB_DELAY_MS는 development에서만 사용할 수 있습니다"
                )
            if not self.davi_use_stub_llm:
                raise ValueError(
                    "DAVI_STUB_FORCE_DONE·DAVI_STUB_DELAY_MS는 스텁 LLM에서만 동작합니다"
                    " (DAVI_USE_STUB_LLM=true)"
                )
            if bind_host not in {"127.0.0.1", "localhost", "::1"}:
                raise ValueError(
                    "DAVI_STUB_FORCE_DONE·DAVI_STUB_DELAY_MS는 loopback 바인딩에서만"
                    " 사용할 수 있습니다"
                )
            if dev_access_token or test_access_token:
                raise ValueError(
                    "공용 개발 endpoint(DAVI_DEV_ACCESS_TOKEN·DAVI_TEST_ACCESS_TOKEN 설정)"
                    "에서는 스텁 프로브를 사용할 수 없습니다"
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
        if self.davi_env != "development" and not self.kakao_auth_configured:
            raise ValueError(
                "beta·production에는 DAVI_KAKAO_APP_ID와 DAVI_AUTH_JWT_SECRET이 필요합니다"
            )
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
        if self.davi_external_rate_limit_requests <= 0:
            raise ValueError("DAVI_EXTERNAL_RATE_LIMIT_REQUESTS는 양수여야 합니다")
        if self.davi_external_rate_limit_window_seconds <= 0:
            raise ValueError("DAVI_EXTERNAL_RATE_LIMIT_WINDOW_SECONDS는 양수여야 합니다")
        if self.davi_external_rate_limit_per_source_requests <= 0:
            raise ValueError("DAVI_EXTERNAL_RATE_LIMIT_PER_SOURCE_REQUESTS는 양수여야 합니다")
        if self.davi_external_rate_limit_per_source_window_seconds <= 0:
            raise ValueError("DAVI_EXTERNAL_RATE_LIMIT_PER_SOURCE_WINDOW_SECONDS는 양수여야 합니다")
        # 같은 불변식(#167 4차 리뷰 P2)을 외부 추론 리미터에도 적용한다 — 근거는 위 auth 창과 같다.
        if (
            self.davi_external_rate_limit_per_source_window_seconds
            > self.davi_external_rate_limit_window_seconds
        ):
            raise ValueError(
                "DAVI_EXTERNAL_RATE_LIMIT_PER_SOURCE_WINDOW_SECONDS는 "
                "DAVI_EXTERNAL_RATE_LIMIT_WINDOW_SECONDS 이하여야 합니다"
            )
        if self.davi_external_daily_limit_requests <= 0:
            raise ValueError("DAVI_EXTERNAL_DAILY_LIMIT_REQUESTS는 양수여야 합니다")
        if self.davi_external_daily_limit_per_source_requests <= 0:
            raise ValueError("DAVI_EXTERNAL_DAILY_LIMIT_PER_SOURCE_REQUESTS는 양수여야 합니다")
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
