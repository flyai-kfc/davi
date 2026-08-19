"""pydantic 모델 -> 표준 JSON Schema 변환 (OpenAI·Anthropic 구조화 출력용).

Gemini는 대문자 타입 이름과 `propertyOrdering`이라는 전용 키를 써서 변환기가 따로 있다
(`gemini_schema.py`). 여기서 만드는 것은 OpenAI `text.format`과 Anthropic
`output_config.format`이 **함께** 요구하는 표준 JSON Schema strict 형태다. 두 공급자가 같은
제약을 요구해서 하나로 합쳤다 — 갈라지면 그때 나눈다.

strict 모드가 요구하는 것과, 그래서 Gemini 쪽과 달라지는 것:
- 모든 객체에 `additionalProperties: false`.
- **모든** 속성이 `required`에 들어간다. Gemini에서 "해당 없음 = 필드 생략"으로 표현하던
  선택 필드를 여기서는 **null 허용**으로 바꾼다(`anyOf: [..., {"type": "null"}]`).
- null을 허용하는 것은 `X | None`으로 선언된 필드뿐이다. 기본값만 있는 list 필드
  (`Field(default_factory=list)`)에 null이 오면 pydantic 검증이 깨지므로, 그런 필드는
  required이면서 non-null로 둔다 — 모델은 빈 배열을 써야 한다.
- 필드 description은 그대로 싣되, null 허용 필드에는 지시를 한 문장 덧붙인다. 모델 선언의
  설명이 Gemini 기준("그 외에는 생략한다")으로 쓰여 있어서, 그대로 보내면 스키마는 값을
  요구하는데 설명은 생략하라고 말하는 상태가 된다.

속성 순서는 선언 순서 그대로다. JSON 객체는 순서를 보존하므로 그것이 곧 생성 순서 =
추론 순서다(C1, `gemini_schema.py`의 propertyOrdering과 같은 뜻).
"""

from __future__ import annotations

import enum
import types
import typing

from pydantic import BaseModel

_PRIMITIVES: dict[type, str] = {
    str: "string",
    int: "integer",
    float: "number",
    bool: "boolean",
}

# 선택 필드 설명에 붙는 지시. Gemini의 "생략" 규약과 strict 모드의 "null" 규약을 잇는다.
_NULL_HINT = " 해당 없으면 null을 넣는다."


def _is_optional(tp: typing.Any) -> bool:
    origin = typing.get_origin(tp)
    if origin is not typing.Union and origin is not types.UnionType:
        return False
    return any(arg is type(None) for arg in typing.get_args(tp))


def _unwrap_optional(tp: typing.Any) -> typing.Any:
    """`X | None` -> `X`. null 허용 여부는 `_is_optional`이 따로 본다."""

    if not _is_optional(tp):
        return tp
    args = [arg for arg in typing.get_args(tp) if arg is not type(None)]
    if len(args) != 1:
        raise TypeError(f"Union은 'X | None' 형태만 지원한다: {tp}")
    return args[0]


def _field_schema(tp: typing.Any) -> dict:
    tp = _unwrap_optional(tp)

    origin = typing.get_origin(tp)
    if origin is list:
        (item_type,) = typing.get_args(tp)
        return {"type": "array", "items": _field_schema(item_type)}

    if isinstance(tp, type) and issubclass(tp, BaseModel):
        return build_json_schema(tp)

    if isinstance(tp, type) and issubclass(tp, enum.Enum):
        values = [str(member.value) for member in tp]
        if any(value == "" for value in values):
            raise ValueError(
                f"{tp.__name__}의 enum에 빈 문자열이 있다. "
                "'해당 없음'은 빈 값이 아니라 null로 표현할 것."
            )
        return {"type": "string", "enum": values}

    if tp in _PRIMITIVES:
        return {"type": _PRIMITIVES[tp]}

    raise TypeError(f"아직 지원하지 않는 타입이다: {tp!r}. 필요하면 이 변환기를 넓힐 것.")


def build_json_schema(model: type[BaseModel]) -> dict:
    """모델 선언에서 strict JSON Schema를 만든다."""

    hints = typing.get_type_hints(model)

    properties: dict[str, dict] = {}
    for name, field in model.model_fields.items():
        annotation = hints[name]
        schema = _field_schema(annotation)
        nullable = _is_optional(annotation)
        if nullable:
            schema = {"anyOf": [schema, {"type": "null"}]}
        if field.description:
            schema["description"] = field.description + (_NULL_HINT if nullable else "")
        properties[name] = schema

    return {
        "type": "object",
        "properties": properties,
        # strict 모드는 선택 필드를 인정하지 않는다. "해당 없음"은 null이 맡는다.
        "required": list(properties),
        "additionalProperties": False,
    }
