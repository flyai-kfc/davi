import pytest
from app.masking import mask_screen, mask_screen_model, mask_text
from app.schemas import Screen, UiElement


def test_masks_common_pii():
    assert "@" not in mask_text("연락처 hong@example.com")
    assert mask_text("010-1234-5678") == "•••"
    assert mask_text("주민 900101-1234567") == "주민 •••"
    assert mask_text("카드 1234-5678-9012-3456") == "카드 •••"


def test_leaves_plain_text_untouched():
    assert mask_text("카카오톡") == "카카오톡"
    assert mask_text("") == ""


def test_mask_screen_is_non_destructive():
    screen = {
        "package_name": "p",
        "elements": [{"node_id": "n1", "label": "문의 hong@example.com", "clickable": True}],
    }
    masked = mask_screen(screen)
    # 원본 dict는 그대로여야 한다.
    assert "hong@example.com" in screen["elements"][0]["label"]
    assert "hong@example.com" not in masked["elements"][0]["label"]
    # 다른 필드는 보존.
    assert masked["elements"][0]["node_id"] == "n1"


def test_mask_screen_model_is_non_destructive():
    screen = Screen(elements=[UiElement(node_id="n1", label="문의 hong@example.com")])

    masked = mask_screen_model(screen)

    assert "hong@example.com" in screen.elements[0].label
    assert "hong@example.com" not in masked.elements[0].label


@pytest.mark.parametrize(
    ("label", "expected"),
    [
        ("문의 hong@example.com", "문의 •••"),
        ("전화 010-1234-5678", "전화 •••"),
        ("일반 앱", "일반 앱"),
    ],
)
def test_dict_and_model_screens_share_the_same_masking_rules(label, expected):
    raw = {
        "package_name": "p",
        "elements": [{"node_id": "n1", "label": label, "role": "button"}],
    }

    masked_dict = mask_screen(raw)
    masked_model = mask_screen_model(Screen.model_validate(raw))

    assert masked_dict["elements"][0]["label"] == expected
    assert masked_model.elements[0].label == expected
