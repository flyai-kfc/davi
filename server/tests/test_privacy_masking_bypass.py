"""2차 마스킹 우회 플래그(#203)의 경계를 고정한다.

우회는 "실험용 편의"가 아니라 **개인정보 약속의 예외**다. 그래서 이 파일은 기능이 도는지가
아니라 *경계가 새지 않는지*를 본다 — 기본값, 프로필 제한, raw 저장 차단, 그리고 우회했을 때
실제로 LLM에 무엇이 전달되는지.
"""

from __future__ import annotations

import pytest
from app.config import Settings
from app.llm import LlmClient
from app.masking import (
    DISABLED_MASKING,
    ENABLED_MASKING,
    active_masking_policy,
)
from app.reasoning import next_step
from app.schemas import (
    ActionType,
    GuideAction,
    NextStepRequest,
    Screen,
    UiElement,
)
from pydantic import ValidationError

_PII_LABEL = "문의 hong@example.com"
_PII_INSTRUCTION = "010-1234-5678 을 누르세요."


class _CapturingLlmClient(LlmClient):
    """모델에 넘어간 요청을 그대로 붙잡아 두는 스파이."""

    def __init__(self) -> None:
        self.seen: NextStepRequest | None = None

    def reason(self, req: NextStepRequest) -> GuideAction:
        self.seen = req
        return GuideAction(
            expected_result="완료",
            evidence="근거",
            action_type=ActionType.TAP,
            target_node_id="n1",
            instruction="누르세요.",
        )


def _request() -> NextStepRequest:
    return NextStepRequest(
        goal="문의하기",
        screen=Screen(elements=[UiElement(node_id="n1", label=_PII_LABEL)]),
        recent_instructions=[_PII_INSTRUCTION],
    )


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


# --- 기본값 -------------------------------------------------------------------


def test_masking_is_enabled_by_default():
    assert _settings().davi_disable_privacy_masking is False


def test_default_policy_masks_screen_and_history():
    spy = _CapturingLlmClient()

    next_step(_request(), spy)

    assert spy.seen is not None
    assert "hong@example.com" not in spy.seen.screen.elements[0].label
    assert "010-1234-5678" not in " ".join(spy.seen.recent_instructions)


# --- 우회 동작 ----------------------------------------------------------------


def test_disabled_policy_passes_original_labels_to_the_model():
    """우회의 목적 자체 — 같은 화면이 치환 없이 모델까지 간다."""
    spy = _CapturingLlmClient()

    next_step(_request(), spy, masking=DISABLED_MASKING)

    assert spy.seen is not None
    assert spy.seen.screen.elements[0].label == _PII_LABEL
    assert spy.seen.recent_instructions == [_PII_INSTRUCTION]


def test_disabled_policy_still_does_not_mutate_the_request():
    """우회해도 비파괴 계약은 그대로다. 호출자 객체가 바뀌면 로깅·재시도가 흔들린다."""
    spy = _CapturingLlmClient()
    req = _request()

    next_step(req, spy, masking=DISABLED_MASKING)

    assert req.screen.elements[0].label == _PII_LABEL
    assert req.recent_instructions == [_PII_INSTRUCTION]


def test_explicit_policy_overrides_settings(set_runtime_profile):
    """주입한 정책이 설정을 이긴다 — 테스트가 설정 전역에 의존하지 않게 한다."""
    set_runtime_profile(davi_disable_privacy_masking=True)
    spy = _CapturingLlmClient()

    next_step(_request(), spy, masking=ENABLED_MASKING)

    assert spy.seen is not None
    assert "hong@example.com" not in spy.seen.screen.elements[0].label


def test_settings_select_the_policy_when_none_is_injected(set_runtime_profile):
    set_runtime_profile(davi_disable_privacy_masking=True)
    assert active_masking_policy() is DISABLED_MASKING

    set_runtime_profile(davi_disable_privacy_masking=False)
    assert active_masking_policy() is ENABLED_MASKING


# --- 프로필 경계 --------------------------------------------------------------


def test_production_rejects_the_bypass():
    with pytest.raises(ValidationError, match="development에서만"):
        _settings(
            davi_env="production",
            davi_use_stub_llm=False,
            gemini_api_key="key",
            davi_disable_privacy_masking=True,
        )


def test_shared_dev_endpoint_rejects_the_bypass():
    """개발 토큰이 있으면 팀이 공유하는 endpoint다 — 남의 기기 화면까지 원문으로 받게 된다.

    loopback에 바인딩한 채로도 토큰을 두면 `adb reverse`로 다른 사람의 기기가 붙을 수 있다.
    그래서 바인딩과 토큰을 각각 따로 막는다.
    """
    with pytest.raises(ValidationError, match="공용 개발 endpoint"):
        _settings(
            davi_bind_host="127.0.0.1",
            davi_dev_access_token="x" * 32,
            davi_disable_privacy_masking=True,
        )


def test_non_loopback_binding_rejects_the_bypass():
    # development의 LAN 바인딩은 개발 토큰을 요구하므로 두 값이 함께 온다. 좁은 제약인
    # 바인딩 쪽을 먼저 알려야 "토큰만 지우면 되는가"로 오해하지 않는다.
    with pytest.raises(ValidationError, match="loopback 바인딩에서만"):
        _settings(
            davi_bind_host="192.168.0.10",
            davi_dev_access_token="x" * 32,
            davi_disable_privacy_masking=True,
        )


def test_local_development_allows_the_bypass():
    configured = _settings(davi_disable_privacy_masking=True)
    assert configured.davi_disable_privacy_masking is True


# --- raw 저장 차단 ------------------------------------------------------------


def test_bypass_disables_raw_storage_even_for_dogfood_devices():
    """도그푸딩 보존은 마스킹을 통과한 봉투를 전제로 승인된 채널이다(SPEC §3)."""
    device = "a" * 16
    masked = _settings(davi_dogfood_device_ids=device)
    assert masked.should_store_raw(device) is True

    bypassed = _settings(
        davi_dogfood_device_ids=device,
        davi_disable_privacy_masking=True,
    )
    assert bypassed.should_store_raw(device) is False
