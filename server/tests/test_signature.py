"""화면 구조 서명 자체의 성질.

**함수가 `app/signature.py`로 나온 자리다**(#386) — `prompt`와 `reasoning`이 함께 쓰므로
어느 한쪽 테스트 파일에 두면 다음 사람이 못 찾는다.

C18·C24 상태 필드(`enabled`·`check_state`·`showing_hint_text`·`window_title`)가 서명에 반영되는지는
그 필드의 계약을 함께 보는 `test_screen_state_fields.py`에 있다. 여기 있는 것은 그 계약보다
먼저 있던 입력 상태(`focused`·`ime_visible`)라 옮기지 않았다.
"""

from app.schemas import Screen, UiElement
from app.signature import screen_signature


def test_screen_signature_changes_with_focus_and_ime_state():
    before = Screen(
        package_name="com.example.app",
        ime_visible=False,
        elements=[UiElement(node_id="n1", role="EditText", editable=True)],
    )
    ready = Screen(
        package_name="com.example.app",
        ime_visible=True,
        elements=[UiElement(node_id="n1", role="EditText", editable=True, focused=True)],
    )

    assert screen_signature(before) != screen_signature(ready)
