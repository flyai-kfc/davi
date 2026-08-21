"""앱과 서버의 계약 enum이 어긋나지 않았는지 확인한다.

**왜 이 테스트가 있나.** 앱은 Kotlin, 서버는 Python이라 같은 계약을 두 번 적는다.
한쪽만 고쳐도 컴파일은 되고 각자의 단위 테스트도 통과한다 — 자기가 쓴 픽스처로 자기 enum을
검사하기 때문이다. 실제로 그렇게 어긋난 적이 있다(이슈 #17):

- 앱이 `scroll`을 몰라서, 서버가 "누를 게 없으니 스크롤하세요"를 줄 때마다 파싱이 터졌다.
- 앱이 `SERVER`를 몰라서, 막힘 에러를 분류하지 못했다.
- 겹치는 값이 `tap`과 `NETWORK`뿐이었는데도 양쪽 테스트가 다 초록이었다.

kotlinx.serialization은 모르는 enum 값에 예외를 던진다. `ignoreUnknownKeys`는 모르는 **키**만
무시할 뿐 값은 막아주지 못한다. 그래서 한 글자만 어긋나도 그 액션이 오는 순간 안내가 끊긴다.

Kotlin 파일을 정규식으로 읽는 방식이 투박하다는 것은 안다. 다만 "사람이 기억해서 맞추기"를
"CI가 잡기"로 바꾸는 값이 그 투박함보다 크다. 계약이 바뀌면 **양쪽을 함께** 고쳐야 통과한다.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest
from app.schemas import ActionType, ErrorKind, RateLimitScope, ScrollAxis, SwipeDirection

CONTRACT_KT = (
    Path(__file__).resolve().parents[2]
    / "app-android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "davi"
    / "helper"
    / "Contract.kt"
)
ANDROID_MANIFEST = CONTRACT_KT.parents[4] / "AndroidManifest.xml"


def _kotlin_enum_values(source: str, enum_name: str) -> set[str]:
    """`enum class <이름> { @SerialName("x") X, ... }` 에서 x들을 뽑는다."""
    match = re.search(
        rf"enum\s+class\s+{re.escape(enum_name)}\s*\{{(.*?)\}}",
        source,
        re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"Contract.kt에서 enum class {enum_name} 을 찾지 못했다")
    return set(re.findall(r'@SerialName\("([^"]*)"\)', match.group(1)))


@pytest.fixture(scope="module")
def contract_source() -> str:
    if not CONTRACT_KT.exists():
        pytest.skip(f"앱 소스가 없다: {CONTRACT_KT}")
    return CONTRACT_KT.read_text(encoding="utf-8")


@pytest.mark.parametrize(
    ("kotlin_enum", "python_enum"),
    [
        ("ActionType", ActionType),
        ("SwipeDirection", SwipeDirection),
        ("ScrollAxis", ScrollAxis),
        ("ApiErrorKind", ErrorKind),
        ("RateLimitScope", RateLimitScope),
    ],
)
def test_app_enum_matches_server(contract_source: str, kotlin_enum: str, python_enum) -> None:
    app_values = _kotlin_enum_values(contract_source, kotlin_enum)
    server_values = {member.value for member in python_enum}

    only_app = app_values - server_values
    only_server = server_values - app_values

    assert not only_app and not only_server, (
        f"{kotlin_enum}(앱) 과 {python_enum.__name__}(서버) 의 값이 다르다.\n"
        f"  앱에만 있음:   {sorted(only_app) or '없음'}\n"
        f"  서버에만 있음: {sorted(only_server) or '없음'}\n"
        "계약은 앱·서버·평가 셋이 동시에 무는 지점이다. 한쪽만 고치지 말 것 "
        "(server/CLAUDE.md 'API 계약 결정')."
    )


def test_step_history_fields_exist_on_both_sides(contract_source: str) -> None:
    """enum 외의 새 요청/응답 필드도 한쪽만 고치는 드리프트를 막는다(C11 개정, #270)."""

    assert "val steps: List<GuideStep> = emptyList()" in contract_source
    assert '@SerialName("screen_signature") val screenSignature: String' in contract_source
    assert '@SerialName("screen_summary") val screenSummary: String' in contract_source

    from app.schemas import GuideAction, GuideStep, NextStepRequest, NextStepResponse

    assert "steps" in NextStepRequest.model_fields
    assert "screen_signature" in NextStepResponse.model_fields
    assert "screen_summary" in GuideAction.model_fields
    assert {
        "instruction",
        "action_type",
        "direction",
        "target_label",
        "screen_hint",
        "structure_signature",
        "screen_changed",
        "performed",
        "content_signature",
    } == set(GuideStep.model_fields)


def test_step_wire_names_match_the_kotlin_serial_names(contract_source: str) -> None:
    """스텝은 필드가 많아 이름 하나가 조용히 어긋나기 쉽다. wire 이름을 통째로 대조한다.

    **선언 블록을 잘라내서 그 안에서만 찾는다.** 파일 전체를 대상으로 하면 `val instruction`·
    `val direction`이 `GuideAction`에도 있어, Kotlin `GuideStep`에서 그 필드가 사라져도 이
    테스트가 초록으로 남는다 — 드리프트 테스트가 잡아야 할 바로 그 경우다(#289 셀프 리뷰).
    """

    from app.schemas import GuideStep

    step_block = _kotlin_class_body(contract_source, "GuideStep")
    for name in GuideStep.model_fields:
        # snake_case 필드만 @SerialName이 필요하다. 나머지는 Kotlin 이름이 곧 wire 이름이다.
        expected = f'@SerialName("{name}")' if "_" in name else f"val {name}"
        assert expected in step_block, f"GuideStep.{name}이 Kotlin 쪽에 없다"


def _kotlin_class_body(source: str, name: str) -> str:
    """`data class <name>(...)`의 괄호 안만 돌려준다. 없으면 그 자체가 드리프트다."""

    start = source.find(f"data class {name}(")
    assert start != -1, f"Kotlin에 data class {name}이 없다"
    end = source.find("\n)", start)
    assert end != -1, f"data class {name}의 선언이 닫히지 않았다"
    return source[start:end]


def test_input_state_fields_exist_on_both_sides(contract_source: str) -> None:
    """#112의 요소/화면 상태가 Kotlin·Python 중 한쪽에서만 바뀌지 않게 한다."""

    assert "val editable: Boolean = false" in contract_source
    assert "val focused: Boolean = false" in contract_source
    assert '@SerialName("ime_visible") val imeVisible: Boolean? = null' in contract_source

    from app.schemas import Screen, UiElement

    assert {"editable", "focused"} <= set(UiElement.model_fields)
    assert "ime_visible" in Screen.model_fields


def test_app_resolution_contract_exists_on_both_sides(contract_source: str) -> None:
    """#128의 후보·대조 상태가 Kotlin/Python 중 한쪽에만 생기지 않게 한다."""

    from app.schemas import AppCandidateAvailability, AppResolutionStatus

    assert _kotlin_enum_values(contract_source, "AppCandidateAvailability") == {
        member.value for member in AppCandidateAvailability
    }
    assert _kotlin_enum_values(contract_source, "AppResolutionStatus") == {
        member.value for member in AppResolutionStatus
    }
    assert "data class AppResolutionRequest" in contract_source
    assert "data class AppCandidatePlan" in contract_source


def test_launcher_visibility_is_narrow_and_query_all_packages_is_absent() -> None:
    manifest = ANDROID_MANIFEST.read_text(encoding="utf-8")
    assert "android.permission.QUERY_ALL_PACKAGES" not in manifest
    assert '<action android:name="android.intent.action.MAIN"' in manifest
    assert '<category android:name="android.intent.category.LAUNCHER"' in manifest
