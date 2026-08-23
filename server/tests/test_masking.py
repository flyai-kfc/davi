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
    ("label", "expected"),
    [
        # 국내 계좌 표기 형태(숫자는 합성). 날짜를 피하려고 좁히면서 **이것들이 계속 걸리는지**가
        # 이 변경의 관문이다(#361).
        ("110-123-456789", "•••"),
        ("1002-123-456789", "•••"),
        ("1234-56-789012", "•••"),
        ("333-01-234567", "•••"),
        ("계좌 123-456-789012로 송금", "계좌 •••로 송금"),
        # 첫 덩어리가 6자리인 형태(국민은행·우체국)는 **이번에 새로 걸린다** — 옛 `\d{2,4}`에
        # 안 맞아 통째로 새고 있었다.
        ("123456-01-123456", "•••"),
        ("012345-01-123456", "•••"),
        # 날짜가 아닌 세 덩어리는 계속 걸린다 — 달·일 범위를 보기 때문이다.
        ("12-34-56", "•••"),
        ("02-123-4567", "•••"),
    ],
)
def test_masks_account_like_numbers(label, expected):
    assert mask_text(label) == expected


@pytest.mark.parametrize(
    "label",
    [
        "2026-08-18",
        "26-08-18",
        "1999-12-31",
        "2026 08 18",
        "2026-01-01",
        "2026-12-31",
        "일정 2026-08-20 회의",
    ],
)
def test_leaves_dates_untouched(label):
    """날짜가 계좌 패턴에 걸려 가려졌다(#361).

    개인정보가 새는 문제가 아니라 **안내 품질이 조용히 깎이는** 문제였다 — 알람·캘린더처럼
    날짜가 label의 핵심인 화면에서 모델이 그 날짜를 가리킬 수 없게 된다.
    """
    assert mask_text(label) == label


@pytest.mark.parametrize(
    "label",
    [
        "123456\u00a001\u00a0123456",  # NBSP로 구분한 계좌
        "\uff11\uff12\uff13\uff14\uff15\uff16-01-123456",  # 전각 숫자
        "010\u00a01234\u00a05678",  # NBSP로 구분한 휴대폰
    ],
)
def test_masks_unicode_digit_and_space_variants(label):
    """`\\d`·`\\s`가 유니코드로 해석되는지 고정한다(#361).

    앱은 같은 문자열을 `(?U)`로 컴파일해 여기에 맞춘다. **앱이 놓치면 원본이 그대로 기기를
    떠나므로**(`SPEC.md` §2) 두 쪽이 갈리면 안 된다. 여기서 `re.ASCII`를 켜면 이 줄이 빨개진다.
    """
    assert mask_text(label) != label


@pytest.mark.parametrize("label", ["1234-05-06", "12-05-06"])
def test_date_shaped_short_number_is_a_known_tradeoff(label):
    """**날짜 모양이면 계좌처럼 생겨도 가려지지 않는다** — 4-2-2(8자리)와 2-2-2(6자리) 둘 다.

    국내 계좌는 둘 다보다 길어 겹치지 않는다고 보고 택한 맞바꿈이다. 이 줄이 빨개지면
    맞바꿈을 되짚는 것이므로, 패턴을 바꾸기 전에 `masking.py`의 근거 주석부터 읽는다.
    """
    assert mask_text(label) == label


@pytest.mark.parametrize(
    "label",
    [
        # **일부러 지우지 않는 것들이다.** 패턴 밖 고유명(사람 이름·기기명·대화방 제목)은
        # 마스킹 대상이 아니라고 `SPEC.md` §2가 정했다(#360) — 일반 규칙으로 잡으면 앱 서랍의
        # 앱 이름까지 지워져 안내가 죽는다. 이 줄들이 빨개지면 그 결정을 뒤집는 것이므로,
        # 패턴을 늘리기 전에 SPEC부터 고쳐야 한다.
        # 숫자가 섞인 형태를 넣는다 — 숫자 없는 이름은 형태 기반 패턴이 늘어도 스치지 않아
        # 가드가 되지 못한다(PR #369 리뷰).
        "지훈의 Galaxy Buds2",
        "Buds2 Pro 3C86",
        "거실 TV 65",
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
