"""pydantic 선언 -> strict JSON Schema 변환(OpenAI·Anthropic 구조화 출력)."""

from __future__ import annotations

import enum

import pytest
from app.json_schema import build_json_schema
from app.schemas import GuideAction
from pydantic import BaseModel, Field


class _Color(enum.StrEnum):
    RED = "red"
    BLUE = "blue"


class _Nested(BaseModel):
    label: str
    scores: list[int] = Field(default_factory=list)


class _Blank(enum.StrEnum):
    NONE = ""


class _BlankEnumModel(BaseModel):
    value: _Blank


class _UnsupportedModel(BaseModel):
    value: dict[str, str]


class _Sample(BaseModel):
    first: str = Field(description="첫 필드")
    optional_enum: _Color | None = Field(default=None, description="색")
    child: _Nested
    flag: bool = False


def test_strict_object_shape():
    schema = build_json_schema(_Sample)

    assert schema["type"] == "object"
    assert schema["additionalProperties"] is False
    # strict 모드는 선택 필드를 인정하지 않는다. 기본값이 있는 flag도 required다.
    assert schema["required"] == ["first", "optional_enum", "child", "flag"]


def test_property_order_follows_declaration_order():
    """속성 순서가 곧 생성 순서 = 추론 순서다(C1). 근거를 결정보다 먼저 쓰게 하는 자리."""

    assert list(build_json_schema(GuideAction)["properties"]) == [
        "expected_result",
        "evidence",
        "action_type",
        "direction",
        "target_node_id",
        "instruction",
    ]


def test_optional_field_becomes_nullable_with_a_null_instruction():
    """Gemini의 '생략' 규약을 strict 모드의 null 규약으로 옮긴다.

    스키마는 값을 요구하는데 설명은 "생략한다"고 말하는 상태를 만들면 안 된다.
    """

    field = build_json_schema(_Sample)["properties"]["optional_enum"]

    assert field["anyOf"] == [
        {"type": "string", "enum": ["red", "blue"]},
        {"type": "null"},
    ]
    assert field["description"].endswith("해당 없으면 null을 넣는다.")


def test_non_optional_field_with_a_default_stays_non_nullable():
    """`list[str] = Field(default_factory=list)`에 null이 오면 pydantic 검증이 깨진다."""

    scores = build_json_schema(_Nested)["properties"]["scores"]

    assert scores == {"type": "array", "items": {"type": "integer"}}


def test_nested_model_is_expanded_with_the_same_rules():
    child = build_json_schema(_Sample)["properties"]["child"]

    assert child["additionalProperties"] is False
    assert child["required"] == ["label", "scores"]


def test_blank_enum_value_is_rejected():
    with pytest.raises(ValueError, match="빈 문자열"):
        build_json_schema(_BlankEnumModel)


def test_unsupported_type_is_rejected_instead_of_silently_dropped():
    with pytest.raises(TypeError):
        build_json_schema(_UnsupportedModel)


def test_guide_action_schema_accepts_a_null_direction():
    """계약이 null을 요구하니, 그 null을 모델 검증이 받아야 한다."""

    action = GuideAction.model_validate(
        {
            "expected_result": "카카오톡이 열린다",
            "evidence": "'카카오톡' 아이콘이 보임",
            "action_type": "tap",
            "direction": None,
            "target_node_id": "n1",
            "instruction": "'카카오톡'을 눌러 주세요.",
        }
    )

    assert action.direction is None
