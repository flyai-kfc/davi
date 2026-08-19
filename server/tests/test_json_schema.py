"""pydantic 선언 -> strict JSON Schema 변환(OpenAI·Anthropic 구조화 출력)."""

from __future__ import annotations

import enum

import pytest
from app.gemini_schema import build_response_schema
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


def test_optional_field_description_carries_exactly_one_convention():
    """한 프롬프트에 "생략" 과 "null" 이 함께 들어가면 안 된다 (PR #62 리뷰가 금지한 패턴).

    무스키마 폴백에서는 이 description이 **유일한 지시**다. 강제하는 스키마가 없으므로
    정반대 지시가 둘 있으면 모델이 어느 쪽이든 고르고, scroll에서 direction이 빠지면
    direction_recovery 한 번을 더 태운 뒤 PARSE로 실패한다.
    """

    strict = build_json_schema(GuideAction)["properties"]["direction"]["description"]
    gemini = build_response_schema(GuideAction)["properties"]["direction"]["description"]

    assert "null" in strict and "생략" not in strict
    assert "생략" in gemini and "null" not in gemini


def test_model_declaration_does_not_pin_a_provider_convention():
    """규약은 변환기가 붙인다. 선언에 한쪽을 박으면 다른 쪽 스키마와 어긋난다."""

    for name in ("direction", "target_node_id"):
        declared = GuideAction.model_fields[name].description or ""
        assert "생략" not in declared
        assert "null" not in declared
