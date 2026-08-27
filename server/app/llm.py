"""LLM 클라이언트 경계. 스텁과 실제 공급자 두 구현.

공급자(Gemini·OpenAI·Anthropic) 선택과 HTTP·스키마·오류 분류는 `llm_provider.py`가 맡는다.
여기 남는 것은 **다음 안내 하나를 뽑는 대화 흐름**이다:

- 스키마 400 시 무스키마 재시도 폴백(스키마 하나로 앱 전체가 죽는 사고 방지).
- scroll 응답에 direction이 빠졌을 때의 1회 교정.
- GuideAction 계약 검증과 PARSE 오류 분류.

development 스텁 덕에 키 없이도 서버가 뜨고 eval 하니스가 돈다(DAVI_USE_STUB_LLM).
"""

from __future__ import annotations

import json
import logging
import time
from functools import cache

import httpx

from .config import settings
from .errors import LlmError
from .gemini_response import strip_json_fence
from .llm_provider import (
    ProviderCallError,
    ResponseContract,
    StructuredJsonProvider,
    build_provider,
)
from .observability import LLM_LOGGER_NAME, log_event
from .prompt import build_prompt, fallback_prompt
from .schemas import (
    ActionType,
    ErrorKind,
    GuideAction,
    NextStepRequest,
    SwipeDirection,
)

_OPERATION = "next_step"
# 모델 선언에서 뽑은 응답 계약. propertyOrdering(선언 순서)이 곧 추론 순서다(C1).
# import 시점에 만들어 두므로, 스키마를 못 만드는 변경은 서버 기동 때 바로 드러난다.
GUIDE_ACTION_CONTRACT = ResponseContract.of(_OPERATION, GuideAction)
# 기존 이름 유지 — Gemini 표현이다. 공급자 중립 계약은 GUIDE_ACTION_CONTRACT를 쓴다.
GUIDE_ACTION_SCHEMA = GUIDE_ACTION_CONTRACT.gemini_schema

# 정상 GuideAction 하나를 쓰는 데 필요한 출력 토큰. 공급자가 보이지 않는 추론에 쓰는 몫은
# `llm_provider.build_provider`가 여기에 더한다.
_RESPONSE_HEADROOM_TOKENS = 1024
_DIRECTION_RETRY_INSTRUCTION = (
    "이전 응답은 action_type이 scroll인데 direction이 빠져 유효하지 않았습니다. "
    "direction을 up, down, left, right 중 하나로 반드시 포함해 JSON 객체 하나만 다시 반환하세요."
)
llm_logger = logging.getLogger(LLM_LOGGER_NAME)


class LlmClient:
    """구현 교체 지점. reason()이 다음 안내 하나를 돌려준다."""

    def reason(self, req: NextStepRequest) -> GuideAction:  # pragma: no cover - interface
        raise NotImplementedError


class _MissingScrollDirectionError(Exception):
    """scroll 응답만 한 번 복구하기 위해 일반 파싱 실패와 구분하는 내부 예외."""


# 스텁의 scroll 문장은 **`direction`에서 뽑는다**(#109).
#
# 고정 문장("화면을 쓸어서 더 내려 보세요")은 방향이 넷인데 하나만 말할 수 있었고 `direction`과
# 어긋나기도 했다. 이 경로는 죽은 코드가 아니라 `eval/harness.py`가 직접 도는 자리이고,
# 앱이 방향을 화면에 그리기 시작하면 그 어긋남이 그대로 보인다(#152).
#
# 문장은 계약과 **같은 말**로 쓴다 — 보러 가는 쪽이지 손가락 방향이 아니다(C17).
_SCROLL_INSTRUCTIONS = {
    SwipeDirection.UP: "위쪽으로 스크롤해 주세요.",
    SwipeDirection.DOWN: "아래쪽으로 스크롤해 주세요.",
    SwipeDirection.LEFT: "왼쪽 화면으로 넘겨 주세요.",
    SwipeDirection.RIGHT: "오른쪽 화면으로 넘겨 주세요.",
}


class StubLlmClient(LlmClient):
    """규칙 기반 가짜 추론. 계약·파이프라인 테스트용. LLM 품질을 대변하지 않는다.

    우선순위: 누를 수 있는 것을 누른다 -> 없으면 더 넘겨본다 -> 그래도 없으면 실패로 알린다.
    "기다리기"는 돌려주지 않는다(C6). 정체 판정은 앱의 폴링 담당이다.

    문장을 만드는 것은 모델이 아니라 코드지만 **나가는 자리는 같다.** 방향 표현은 `direction`과
    같은 말로 — 보러 가는 쪽으로 쓴다(C17, `prompt.SCROLL_DIRECTION_INSTRUCTION`과 같은 규칙).
    """

    def reason(self, req: NextStepRequest) -> GuideAction:
        # 프로브 손잡이(#480). 값이 기본이면 아래 규칙 그대로다 — 설정이 켜졌을 때만 갈린다.
        # 게이트(development·스텁·loopback·개발 토큰 없음)는 `config.validate_runtime_profile`에
        # 있다. 여기서 다시 세지 않는 이유는 기동 때 못 뜨게 막는 편이 요청마다 판정하는 것보다
        # 실수에 강해서다.
        if settings.davi_stub_delay_ms:
            time.sleep(settings.davi_stub_delay_ms / 1000)
        if settings.davi_stub_force_done:
            return self._forced_done(req)

        expected = f"목표 '{req.goal}' 달성"
        # 스텁은 화면을 읽지 못하므로 package 이름을 그대로 쓴다. 모델이 만드는 한 줄 요약과
        # 성격은 다르지만, 계약이 요구하는 자리를 비워 두지 않는 것이 여기서 할 일이다.
        summary = f"{req.screen.package_name or '알 수 없는 앱'}의 화면"

        clickable = [e for e in req.screen.elements if e.clickable and e.label]
        if clickable:
            target = clickable[0]
            return GuideAction(
                expected_result=expected,
                screen_summary=summary,
                evidence=f"화면에 '{target.label}' 버튼이 보임",
                action_type=ActionType.TAP,
                target_node_id=target.node_id,
                # **`을(를)`처럼 눈으로 읽는 표기를 쓰지 않는다.** 안내문은 앱이 소리로도 읽는데
                # (#450), 그 표기는 TTS가 괄호째 읽어 「티월드 을 를 눌러 주세요」가 된다
                # (2026-08-26 SM-S921N 실측). 조사를 label에 붙이려면 받침을 봐야 하고 영문·숫자
                # label에서는 그마저 갈리므로, **조사가 붙는 자리를 고정 낱말로** 옮긴다 —
                # 바로 아래 `evidence`가 이미 이 요소를 '버튼'이라 부르고 있어 말도 어긋나지 않는다.
                instruction=f"'{target.label}' 버튼을 눌러 주세요.",
            )

        scrollable = next(
            (e for e in req.screen.elements if e.can_scroll_forward),
            None,
        )
        if scrollable is not None:
            direction = scrollable.next_swipe_direction or SwipeDirection.UP
            return GuideAction(
                expected_result=expected,
                screen_summary=summary,
                evidence="누를 수 있는 요소가 없고 더 넘길 수 있는 목록이 있음",
                action_type=ActionType.SCROLL,
                direction=direction,
                target_node_id=scrollable.node_id,
                instruction=_SCROLL_INSTRUCTIONS[direction],
            )

        # 막힘 상태의 제품적 종착지는 아직 미설계다(SPEC §11). 스텁은 조용히 끝내지 않고 알린다.
        raise LlmError(
            ErrorKind.SERVER,
            "스텁이 다음 동작을 정할 수 없다: 누를 수 있는 요소도, 넘길 목록도 없음",
        )

    @staticmethod
    def _forced_done(req: NextStepRequest) -> GuideAction:
        """화면을 보지 않고 완료를 주장한다(#480 프로브).

        **`completion_evidence`를 일부러 비운다.** 채우면 서버 검증(`_classify_completion`)이
        `verified`로 올려 앱이 확인 화면으로 바로 가 버리고, 재현하려는 재질의 루프에 못
        들어간다. 비우면 4단이 `inconclusive`로 내리고 앱이 `MAX_UNVERIFIED_DONE`까지
        백오프 재질의를 돈다 — 그 구간이 관측 대상이다.

        `evidence`에 화면 label을 인용하지 않는 것도 같은 이유다(#54의 인용 대조).
        """
        return GuideAction(
            expected_result=f"목표 '{req.goal}' 달성",
            screen_summary=f"{req.screen.package_name or '알 수 없는 앱'}의 화면",
            evidence="프로브가 검증 없이 완료를 주장함",
            action_type=ActionType.DONE,
            instruction="다 되었어요.",
        )


class RemoteLlmClient(LlmClient):
    """공급자 구조화 출력으로 다음 안내를 만든다.

    `provider`는 테스트에서 MockTransport를 물린 공급자를 주입하기 위한 경계이기도 하다.
    운영에서는 `get_llm_client()`가 설정이 고른 공급자를 하나 만들어 프로세스 동안 재사용한다.
    """

    def __init__(self, provider: StructuredJsonProvider) -> None:
        self.provider = provider

    def reason(self, req: NextStepRequest) -> GuideAction:
        prompt = build_prompt(req)
        active_prompt = prompt
        with_schema = True
        provider_attempt = 0

        def call_once(call_prompt: str, *, trigger: str) -> str:
            nonlocal provider_attempt
            provider_attempt += 1
            return self.provider.generate_json(
                call_prompt,
                with_schema=with_schema,
                attempt=provider_attempt,
                trigger=trigger,
            )

        try:
            response_text = call_once(active_prompt, trigger="initial")
        except ProviderCallError as error:
            if error.status_code != 400:
                raise self.provider.to_llm_error(error) from error
            # 공급자가 스키마를 거부한 경우에만, 계약을 프롬프트로 옮겨 한 번 복구한다.
            active_prompt = fallback_prompt(
                prompt,
                self.provider.contract_schema,
                omit_optional=self.provider.omits_optional_fields,
            )
            with_schema = False
            try:
                response_text = call_once(active_prompt, trigger="schema_recovery")
            except ProviderCallError as fallback_error:
                raise self.provider.to_llm_error(fallback_error) from fallback_error

        try:
            return self._parse_action(response_text)
        except _MissingScrollDirectionError:
            # 방향을 화면 상태로 추측하면 앞/뒤 의도를 바꿀 수 있다. 원문은 로그에 남기지 않고
            # 같은 schema 모드로 한 번만 재요청해 비용과 지연을 제한한다.
            log_event(
                llm_logger,
                logging.WARNING,
                "llm_parse_recovery",
                provider=self.provider.name,
                operation=_OPERATION,
                reason="scroll_direction_missing",
                action="retry",
            )
            try:
                retry_text = call_once(
                    f"{active_prompt}\n\n{_DIRECTION_RETRY_INSTRUCTION}",
                    trigger="direction_recovery",
                )
            except ProviderCallError as retry_error:
                raise self.provider.to_llm_error(retry_error) from retry_error
            try:
                return self._parse_action(retry_text)
            except _MissingScrollDirectionError as retry_parse_error:
                log_event(
                    llm_logger,
                    logging.WARNING,
                    "llm_parse_recovery",
                    provider=self.provider.name,
                    operation=_OPERATION,
                    reason="scroll_direction_missing",
                    action="fail",
                )
                raise LlmError(
                    ErrorKind.PARSE,
                    "scroll 응답에 direction이 없음 (1회 재시도 실패)",
                ) from retry_parse_error

    def _parse_action(self, response_text: str) -> GuideAction:
        try:
            payload = json.loads(strip_json_fence(response_text))
        except (ValueError, TypeError) as error:
            self._log_parse_error("invalid_json")
            raise LlmError(ErrorKind.PARSE, "응답이 GuideAction 계약과 맞지 않음") from error
        if (
            isinstance(payload, dict)
            and payload.get("action_type") == ActionType.SCROLL
            and not payload.get("direction")
        ):
            raise _MissingScrollDirectionError
        if isinstance(payload, dict):
            # 화면 요약이 빠진 응답을 **실패로 닫지 않는다**(C11 개정, #270). 스키마에서는
            # required라 모델이 보통 채우지만, 스키마가 거부돼 프롬프트로만 계약을 말하는 폴백에는
            # 강제하는 것이 없다. 거기서 요약 한 줄이 없다고 PARSE로 닫으면 **부수 필드가 멀쩡한
            # 안내를 죽인다** — 앱은 그 화면에서 오류 문구를 띄우고 백오프한다.
            # 같은 이유로 값의 공백 검증도 두지 않았다(`schemas.GuideAction.screen_summary`).
            payload.setdefault("screen_summary", "")
        try:
            return GuideAction.model_validate(payload)
        except (ValueError, TypeError) as error:
            self._log_parse_error("invalid_guide_action")
            raise LlmError(ErrorKind.PARSE, "응답이 GuideAction 계약과 맞지 않음") from error

    def _log_parse_error(self, reason: str) -> None:
        log_event(
            llm_logger,
            logging.WARNING,
            "llm_parse_error",
            provider=self.provider.name,
            operation=_OPERATION,
            reason=reason,
            action="fail",
        )


def next_step_provider(http_client: httpx.Client | None = None) -> StructuredJsonProvider:
    """설정이 고른 공급자로 다음 안내용 클라이언트를 만든다."""

    return build_provider(
        contract=GUIDE_ACTION_CONTRACT,
        operation=_OPERATION,
        response_headroom=_RESPONSE_HEADROOM_TOKENS,
        http_client=http_client,
    )


@cache
def get_llm_client() -> LlmClient:
    """설정은 프로세스 기동 시 고정되므로 HTTP 연결 풀도 프로세스 동안 재사용한다."""

    if settings.davi_use_stub_llm:
        return StubLlmClient()
    return RemoteLlmClient(next_step_provider())
