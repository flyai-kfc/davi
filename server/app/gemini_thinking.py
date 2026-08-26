"""Gemini model id와 thinking 설정의 단일 정책 경계."""

from __future__ import annotations

from dataclasses import dataclass

_NON_TEXT_MODEL_SEGMENTS = frozenset({"audio", "image", "live", "native", "tts"})


@dataclass(frozen=True)
class GeminiThinkingPolicy:
    """요청 wire와 출력 reserve가 서로 어긋나지 않게 함께 계산한 결과."""

    model_id: str
    request_config: dict[str, int] | None
    output_reserve: int


def gemini_thinking_policy(model: str, thinking_budget: int) -> GeminiThinkingPolicy:
    """모델 계약에 맞는 numeric wire·출력 reserve·허용 범위를 결정한다.

    `DAVI_GEMINI_THINKING_BUDGET`은 2.5에서는 실제 thinking hard cap이고, 3.x와
    알 수 없는 모델에서는 wire로 보내지 않는 출력 reserve다. 0은 thinking을 끌 수 있다고
    확인된 2.5 Flash 계열에서만 허용한다.
    """

    model_id = normalize_gemini_model_id(model)
    normalized = model_id.casefold()

    if thinking_budget < 0:
        raise _budget_error(model_id, "0 이상이어야 합니다")

    if _is_text_generation_model(normalized, "gemini-2.5-pro"):
        _require_range(model_id, thinking_budget, minimum=128, maximum=32768)
        return GeminiThinkingPolicy(
            model_id=model_id,
            request_config={"thinkingBudget": thinking_budget},
            output_reserve=thinking_budget,
        )

    if _is_text_generation_model(normalized, "gemini-2.5-flash-lite"):
        if thinking_budget != 0:
            _require_range(model_id, thinking_budget, minimum=512, maximum=24576)
        return GeminiThinkingPolicy(
            model_id=model_id,
            request_config={"thinkingBudget": thinking_budget},
            output_reserve=thinking_budget,
        )

    if _is_text_generation_model(normalized, "gemini-2.5-flash"):
        _require_range(model_id, thinking_budget, minimum=0, maximum=24576)
        return GeminiThinkingPolicy(
            model_id=model_id,
            request_config={"thinkingBudget": thinking_budget},
            output_reserve=thinking_budget,
        )

    if thinking_budget == 0:
        raise _budget_error(
            model_id,
            "0은 thinking 비활성화를 지원하는 Gemini 2.5 Flash 계열에서만 허용됩니다",
        )

    # Gemini 3.x는 thinkingLevel 계약이고 rolling alias·알 수 없는 모델의 실제 세대도
    # 단정할 수 없다. numeric wire는 생략하되 응답 자리를 잠식하지 않도록 reserve는 유지한다.
    return GeminiThinkingPolicy(
        model_id=model_id,
        request_config=None,
        output_reserve=thinking_budget,
    )


def normalize_gemini_model_id(model: str) -> str:
    """환경변수의 bare id와 API resource 이름(`models/...`)을 같은 id로 정규화한다."""

    model_id = model.strip()
    if model_id.casefold().startswith("models/"):
        model_id = model_id[len("models/") :]
    if not model_id or "/" in model_id:
        raise ValueError(
            "DAVI_MODEL에는 Gemini bare model id 또는 models/<id>만 사용할 수 있습니다"
        )
    return model_id


def _require_range(model_id: str, value: int, *, minimum: int, maximum: int) -> None:
    if not minimum <= value <= maximum:
        raise _budget_error(model_id, f"{minimum} 이상 {maximum} 이하여야 합니다")


def _is_text_generation_model(model_id: str, family: str) -> bool:
    if model_id == family:
        return True
    prefix = f"{family}-"
    if not model_id.startswith(prefix):
        return False
    suffix_segments = set(model_id[len(prefix) :].split("-"))
    return suffix_segments.isdisjoint(_NON_TEXT_MODEL_SEGMENTS)


def _budget_error(model_id: str, detail: str) -> ValueError:
    return ValueError(f"DAVI_GEMINI_THINKING_BUDGET은 {model_id}에서 {detail}")
