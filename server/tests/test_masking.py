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


# 아래 값은 전부 합성이다(실측 값은 이슈·테스트에 옮기지 않는다, #312).


@pytest.mark.parametrize(
    ("label", "expected"),
    [
        # 조사가 붙으면 `\b`가 성립하지 않아 통째로 새어 나갔다 — 연락처·메시지·송금
        # 화면의 label이 정확히 이 형태다(#312 ①).
        ("010-1234-5678에게 전화", "•••에게 전화"),
        ("연락처 010-1234-5678번", "연락처 •••번"),
        ("계좌 123-456-789012로 송금", "계좌 •••로 송금"),
        ("900101-1234567은 주민번호", "•••은 주민번호"),
        ("1234-5678-9012-3456으로 결제", "•••으로 결제"),
        # email은 `\b`를 쓰지 않아 원래 걸렸다. 회귀로 남긴다.
        ("hong@example.com으로 보내기", "•••으로 보내기"),
    ],
)
def test_masks_numbers_followed_by_korean_particles(label, expected):
    assert mask_text(label) == expected


@pytest.mark.parametrize(
    ("label", "expected"),
    [
        ("3C:86:9A:1B:2C:3D", "•••"),
        ("버즈 3C:86:9A:1B:2C:3D", "버즈 •••"),
        ("aa:bb:cc:dd:ee:ff", "•••"),
        # 앞 라벨이 콜론으로 붙어 공백이 없는 표기. 경계가 콜론을 금지하면 통째로 샌다
        # (PR #357 리뷰에서 실측). 라벨 단어 자체는 보존돼야 한다.
        ("MAC:3C:86:9A:1B:2C:3D", "MAC:•••"),
        ("주소:3C:86:9A:1B:2C:3D", "주소:•••"),
        ("MAC: 3C:86:9A:1B:2C:3D", "MAC: •••"),
        # 뒤에 콜론이 남는 표기.
        ("3C:86:9A:1B:2C:3D:", "•••:"),
        # 7바이트 이상은 앞 6바이트만 지우지 않고 통째로 삼킨다.
        ("3C:86:9A:1B:2C:3D:4E", "•••"),
    ],
)
def test_masks_mac_addresses(label, expected):
    """블루투스 기기 목록의 MAC 주소가 그대로 서버에 도달했다(#312 ②)."""
    assert mask_text(label) == expected


@pytest.mark.parametrize(
    "label",
    [
        # lookaround로 바꾸면서 오탐이 늘지 않았는지 — 숫자가 섞인 평범한 label들.
        "내 갤럭시 버즈",
        "2만 3000원",
        "재생목록 1-2-3",
        "오후 3:45",
        "배터리 85%",
        "12:34",
        "자막 01:02:03",
        # MAC처럼 보이지만 6바이트에 못 미친다.
        "3C:86:9A",
        "3C:86:9A:1B:2C",
        # IPv6는 4자리 그룹이라 걸리지 않는다.
        "2001:0db8:85a3:0000:0000:8a2e",
    ],
)
def test_leaves_non_pii_labels_untouched(label):
    assert mask_text(label) == label


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
