"""pydantic 모델 -> Gemini `responseSchema` 변환 (C1).

**왜 이 모듈이 있나.** `propertyOrdering`은 표준 JSON Schema가 아니라 Gemini 전용 키다.
그래서 pydantic이 만들어주지 않고, 손으로 배열을 적어두면 모델 정의와 반드시 어긋난다.
어긋나면 HANDOFF §3-7이 경고한 실패(근거 필드를 나중에 대충 채움)가 조용히 재발한다.
그래서 **선언 순서를 유일한 출처로 삼아 여기서 뽑는다.**

여기서 흡수하는 HANDOFF §10 함정:
- enum에 빈 문자열을 넣으면 400 (`cannot be empty`). 값에 빈 문자열이 있으면 여기서 막는다.
- "해당 없음"은 빈 값이 아니라 **필드 생략**이다. 그래서 선택 필드는 `required`에 넣지 않고,
  그 규약 문장을 description 끝에 덧붙인다. 모델 선언에는 이 문장을 두지 않는다 — 규약이
  공급자마다 다르므로(strict 모드는 null이다) 한쪽을 박으면 다른 쪽과 모순된다(`json_schema.py`).
- 스키마가 거부되면 모든 호출이 실패한다. 호출부는 무스키마 재시도 폴백을 반드시 둘 것(llm.py).

GuideAction의 평평한 모델뿐 아니라 앱 후보 계약의 중첩 객체·목록도 같은 선언에서 만든다.
지원하지 않는 타입은 조용히 넘기지 않고 예외를 던진다 — 잘못된 스키마가 배포되는 것보다 낫다.
"""

from __future__ import annotations

import enum
import types
import typing

from pydantic import BaseModel

# Gemini 스키마의 타입 이름. 표준 JSON Schema의 소문자와 다르다.
# 선택 필드 description에 붙는 규약. 무스키마 폴백에서는 이 문장이 유일한 지시가 된다.
_OMIT_HINT = " 해당 없으면 이 필드를 생략한다."

_PRIMITIVES: dict[type, str] = {
    str: "STRING",
    int: "INTEGER",
    float: "NUMBER",
    bool: "BOOLEAN",
}


def _unwrap_optional(tp: typing.Any) -> typing.Any:
    """`X | None` -> `X`. Optional 여부는 required 판정에서 따로 본다."""
    origin = typing.get_origin(tp)
    if origin is typing.Union or origin is types.UnionType:
        args = [a for a in typing.get_args(tp) if a is not type(None)]
        if len(args) != 1:
            raise TypeError(f"Union은 'X | None' 형태만 지원한다: {tp}")
        return args[0]
    return tp


def _field_schema(tp: typing.Any) -> dict:
    tp = _unwrap_optional(tp)

    origin = typing.get_origin(tp)
    if origin is list:
        (item_type,) = typing.get_args(tp)
        return {"type": "ARRAY", "items": _field_schema(item_type)}

    if isinstance(tp, type) and issubclass(tp, BaseModel):
        return build_response_schema(tp)

    if isinstance(tp, type) and issubclass(tp, enum.Enum):
        values = [str(m.value) for m in tp]
        if any(v == "" for v in values):
            raise ValueError(
                f"{tp.__name__}의 enum에 빈 문자열이 있다. Gemini가 400으로 거부한다. "
                "'해당 없음'은 빈 값이 아니라 필드 생략으로 표현할 것."
            )
        return {"type": "STRING", "enum": values}

    if tp in _PRIMITIVES:
        return {"type": _PRIMITIVES[tp]}

    raise TypeError(f"아직 지원하지 않는 타입이다: {tp!r}. 필요하면 이 변환기를 넓힐 것.")


def build_response_schema(model: type[BaseModel]) -> dict:
    """모델 선언에서 Gemini responseSchema를 만든다.

    `propertyOrdering`은 **필드 선언 순서 그대로**다. 이것이 생성 순서 = 추론 순서다.
    `required`에는 기본값이 없는 필드만 넣는다(선택 필드는 생략으로 "해당 없음"을 표현).
    """
    hints = typing.get_type_hints(model)

    properties: dict[str, dict] = {}
    ordering: list[str] = []
    required: list[str] = []

    for name, field in model.model_fields.items():
        schema = _field_schema(hints[name])
        optional = not field.is_required()
        if field.description:
            schema["description"] = field.description + (_OMIT_HINT if optional else "")
        properties[name] = schema
        ordering.append(name)
        if not optional:
            required.append(name)

    return {
        "type": "OBJECT",
        "properties": properties,
        "required": required,
        "propertyOrdering": ordering,
    }
