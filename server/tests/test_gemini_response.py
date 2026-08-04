import pytest
from app.gemini_response import (
    GeminiCandidateError,
    GeminiCandidateErrorReason,
    extract_candidate_text,
    strip_json_fence,
)


def test_extract_candidate_text_joins_text_parts():
    payload = {
        "candidates": [
            {
                "finishReason": "STOP",
                "content": {"parts": [{"text": "앞"}, {"text": "뒤"}]},
            }
        ]
    }

    assert extract_candidate_text(payload) == "앞뒤"


def test_extract_candidate_text_classifies_max_tokens_without_text():
    payload = {"candidates": [{"finishReason": "MAX_TOKENS"}]}

    with pytest.raises(GeminiCandidateError) as raised:
        extract_candidate_text(payload)

    assert raised.value.reason is GeminiCandidateErrorReason.MAX_TOKENS


@pytest.mark.parametrize(
    ("payload", "reason"),
    [
        ({}, GeminiCandidateErrorReason.MISSING_TEXT),
        (
            {"candidates": [{"content": {"parts": [{"text": "  "}]}}]},
            GeminiCandidateErrorReason.EMPTY_TEXT,
        ),
    ],
)
def test_extract_candidate_text_classifies_safe_structure_errors(payload, reason):
    with pytest.raises(GeminiCandidateError) as raised:
        extract_candidate_text(payload)

    assert raised.value.reason is reason


def test_strip_json_fence_accepts_plain_and_fenced_json():
    assert strip_json_fence(' {"ok":true} ') == '{"ok":true}'
    assert strip_json_fence('```json\n{"ok":true}\n```') == '{"ok":true}'
