"""추론 파이프라인: 실행 -> 명시적 검증 (SPEC §4, HANDOFF §5-3).

이 모듈은 HTTP를 모른다. 순수 함수로 두어 eval 하니스가 직접 import해 회귀 테스트한다
(server/CLAUDE.md, eval/CLAUDE.md). FastAPI 라우터는 이걸 호출만 한다.

**계획(sub-task) 단계는 무기한 연기됐다**(SPEC §3 비목표). PoC의 단일 스텝 반응형을 넘어선 것은
완료 검증과 가드레일 단일 결정 지점이지 계획이 아니다 — 스텁과 TODO를 여기서 걷었다(#230).
가드레일은 `_apply_guardrails` 한 곳에 우선순위를 드러내 통합한다(HANDOFF §5-4).
"""

from __future__ import annotations

import hashlib
import json
import re
from typing import NamedTuple

from .config import settings
from .errors import LlmError
from .llm import LlmClient
from .masking import MaskingPolicy, active_masking_policy, mask_text
from .schemas import (
    ActionType,
    ErrorKind,
    GuideAction,
    NextStepRequest,
    NextStepResponse,
    Screen,
    SwipeDirection,
    UiElement,
)
from .screen_view import (
    has_scroll_metadata,
    obscured_observation_unreliable,
    screen_has_cover,
    visible_elements,
)

_APP_DRAWER_FALLBACK_PACKAGES = {"com.sec.android.app.launcher"}

# 근거 대조 전에 양쪽에서 지우는 것: 구두점·공백 등 의미를 바꾸지 않는 문자.
#
# **글자는 문자 종류를 가리지 않고 남긴다.** 한글·라틴만 남기면 label이 한자·가나·키릴인 화면
# (微信, 設定, Настройки)에서 정규화 결과가 통째로 비어 검사가 조용히 꺼진다(PR #62 리뷰 2번).
_EVIDENCE_NOISE = re.compile(r"[^\w]+", re.UNICODE)
_EVIDENCE_WORD = re.compile(r"\w+", re.UNICODE)

# **한 값을 이어 붙이는 구두점**(PR #406 리뷰). 어절 토큰화(`_EVIDENCE_WORD`)는 구두점을
# 경계로 삼는데, 그 경계가 **원문에는 없던 것**일 때가 있다 — `010-1234-5678`은 사람이 읽기에
# 값 하나지만 토큰은 셋이라, 잘린 `010-1234`가 두 어절의 경계에 정확히 맞아떨어져 "인용됨"으로
# 통과했다(리뷰 재현). 그래서 **이 문자들로만 이어진 자리는 어절 경계로 보지 않는다.**
#
# 괄호·쉼표는 **넣지 않는다.** 저것들은 값을 잇는 것이 아니라 묶거나 나열하는 자리라, 넣으면
# `설정(Settings)`의 `설정`이나 `사과,배`의 `사과` 같은 **정당한 인용이 막힌다** — 완료 근거
# 대조가 같은 함수를 쓰므로 그 손해가 먼저 온다.
_VALUE_CONNECTORS = frozenset("-./:")

# 한 글자 label(아이콘·숫자)은 우연 일치가 잦고, 숫자 키패드에서는 거의 모든 문장에 걸린다.
# 대조할 긴 label이 없더라도 한 글자로 폴백하지 않는다(#71 fixture 평가).
_MIN_EVIDENCE_LABEL_CHARS = 2

# 복사 문구의 하한. 근거는 매칭이 아니라 **버튼이 아끼는 타이핑**이다 — 한 글자를 대신 쳐 주는
# 버튼은 사용자가 지우고 다시 치는 것보다 낫지 않다(`_instruction_quotes_input_text`).
_MIN_INPUT_TEXT_CHARS = 2

# label 뒤에 붙어도 **같은 어절을 인용한 것**으로 볼 수 있는 한국어 조사·서술격 어미.
# 이 목록 밖의 글자까지 부분 일치로 받으면 `사용 중`과 `사용 중지`처럼 상태가 뒤집힌다.
# 일반 동사 접미사는 일부러 넣지 않는다. 예를 들어 버튼 label `저장`과 `저장되었습니다`를
# 같은 근거로 보면 저장 전 화면의 버튼만으로 완료를 통과시키기 때문이다.
_KOREAN_EVIDENCE_SUFFIXES = frozenset(
    {
        "가",
        "고",
        "과",
        "까지",
        "께",
        "께서",
        "나",
        "는",
        "도",
        "라",
        "라고",
        "랑",
        "로",
        "를",
        "마다",
        "만",
        "며",
        "보다",
        "부터",
        "에",
        "에게",
        "에서",
        "예요",
        "였습니다",
        "와",
        "으로",
        "은",
        "을",
        "의",
        "이",
        "이고",
        "이며",
        "이나",
        "이랑",
        "이라",
        "이라고",
        "이었습니다",
        "이에요",
        "입니다",
        "인",
        "처럼",
    }
)

# 격조사 뒤에 붙을 수 있는 보조사 한 개. `에서는`·`에도`를 완성형으로 하나씩 적으면
# `에서도`·`에는`·`으로는`이 빠질 때마다 목록을 늘려야 한다. 결합 가능한 앞 조사와 뒤 보조사를
# 따로 두어 같은 문법을 한 규칙으로 받되, 두 번 이상 이어 붙인 임의 문자열까지 넓히지는 않는다.
_KOREAN_EVIDENCE_COMPOUND_BASES = frozenset(
    {
        "과",
        "까지",
        "께",
        "께서",
        "로",
        "보다",
        "부터",
        "에",
        "에게",
        "에서",
        "와",
        "으로",
        "처럼",
    }
)
_KOREAN_EVIDENCE_AUXILIARY_SUFFIXES = frozenset({"는", "도", "만", "까지"})


def _apply_guardrails(action: GuideAction, req: NextStepRequest) -> GuideAction:
    """모든 행동 교정의 단일 결정 지점. 위에서 아래로 우선순위를 적용한다.

    HANDOFF §5-4 경고: 가드가 서로 덮어써 불안정했던 전례가 있다.
    새 가드는 이 함수에 우선순위를 드러내 추가하고, 서로 연쇄 적용하지 않는다.
    """
    # 0. **가려진 대상은 어떤 행동으로도 내보낼 수 없다.** 이것만은 교정이 아니라 전제 검사라
    #    아래 교정들과 연쇄하지 않는다 — 통과하거나 실패로 닫거나 둘뿐이다.
    _reject_obscured_target(action, req.screen)

    # 1. 복사 신호는 **안내문이 집어 말한 말**만 남긴다(C21). 건드리는 필드가 `input_text`
    #    하나뿐이고 아래 어느 가드도 그 값을 읽지 않아 연쇄하지 않는다.
    action = _keep_input_text_named_by_instruction(action, req.goal)

    # 2. 동작별 교정. **분기를 if/elif로 두고 반환을 하나로 모은다**(PR #406 재리뷰) —
    #    early return이 있으면 그 앞뒤로 가드 순서 제약이 생기고, 그 제약을 주석으로만
    #    강제하게 된다. 여기서는 위 가드가 어디 있든 도달하므로 확인할 것이 하나 준다.
    if action.action_type is ActionType.TAP:
        # tap 대상은 눌렀을 때 반응하는 요소여야 한다(C20 · #311).
        _reject_unclickable_tap(action, req.screen)
    elif action.action_type is ActionType.SCROLL:
        # 대상 없는 scroll은 안내를 그 자리에서 죽인다(#202).
        action = _resolve_scroll(action, req.screen)
    return action


def _reject_obscured_target(action: GuideAction, screen: Screen) -> None:
    """모델이 **가려진 요소**를 대상으로 골랐으면 실패로 닫는다.

    실패 모양이 #202의 대상 없는 scroll과 같다: 사용자가 볼 수도 누를 수도 없는 자리를
    가리키면 화면이 그대로고 → 정체 판정 → **정체에서는 서버를 다시 부르지 않으므로**(#39)
    회복 경로가 없다. 실패로 닫으면 앱이 백오프 후 같은 화면을 다시 물어본다(`retryThisScreen`).

    프롬프트는 가려진 요소를 **목록에서 뺀다**(`prompt._visible_elements`). 그래도 여기서 한 번
    더 막는 이유는 미관측(`None`)과 경계 사례가 새기 때문이고, 무엇보다 **프롬프트를 거치지
    않고 대상이 정해지는 자리**가 있기 때문이다(`_resolve_scroll` 2단이 대상을 직접 채운다).
    실측에서 모델은 시트 뒤의 '치킨' 버튼을 골랐다.

    **다른 대상을 대신 고르지 않는다** — 가드는 교정이지 재선택이 아니다(`_resolve_scroll`과
    같은 경계). 무엇을 눌러야 하는지는 가려지지 않은 요소를 보고 모델이 다시 정할 일이다.

    `obscured`가 `None`(낡은 앱의 미관측)이면 아무 판정도 하지 않는다 — 침묵을 "가려짐"으로도
    "안 가려짐"으로도 읽지 않는다(C10의 세 상태 규약).
    """
    # 전부 가려졌다고 관측된 화면에서는 **판정하지 않는다.** 그런 화면은 실존하지 않아
    # (덮는 쪽은 항상 보인다) 관측 오작동의 신호이고, 프롬프트도 같은 판정으로 원본 목록을
    # 폴백으로 싣는다(`prompt._visible_elements`). 여기서만 계속 거부하면 폴백으로 실린
    # 요소를 모델이 고를 때마다 닫혀서, 백오프 → 같은 프롬프트 → 같은 거부의 뺑뺑이가 된다.
    if obscured_observation_unreliable(screen.elements):
        return
    target = _element_by_id(screen, action.target_node_id)
    if target is None or target.obscured is not True:
        return
    raise LlmError(
        ErrorKind.SERVER,
        "안내 대상이 다른 요소에 완전히 가려져 있어 사용자가 보거나 누를 수 없음",
    )


def _keep_input_text_named_by_instruction(action: GuideAction, goal: str) -> GuideAction:
    """`input_text`는 **안내문이 집어 말한 말**일 때만 남긴다(C21, 2026-08-24 개정).

    **동의를 받는 면은 안내문이다.** 사용자는 「검색창에 '한국 미국 시차'라고 입력하세요」를
    읽고 그 아래 버튼을 누른다 — 채워지는 값이 방금 읽은 말과 같으면, 버튼은 사용자가 눈으로
    확인한 일만 한다.

    **목표 문장을 출처로 요구하지는 않는다**(개정 전 규칙). 검색어는 목표에 그대로 있지 않은
    것이 정상이라 — 목표 `한국이랑 미국의 시차를 알고 싶어`에 사람이 실제로 칠 말은
    `한국 미국 시차`다 — 출처를 목표로 좁히면 **모델이 어색한 검색어를 쓰도록 강요하거나**
    (실측) 정상 안내에서 버튼이 사라진다.

    **그 대가를 두 가드가 받는다**(PR #406 재리뷰). 안내문 대조만 남기면 `input_text`와
    `instruction`이 **같은 호출에서 나온 두 필드**라, 대조가 검증하는 것은 내용의 사실 여부가
    아니라 둘의 자기 일관성뿐이다 — 지어낸 전화번호도 안내문에 같이 써 주면 통과한다(재현됨).
    그래서 코드가 두 자리를 따로 막는다:

    - **[_looks_sensitive]** — 값이 개인정보 패턴(전화·계좌·카드·주민·이메일)에 걸리면 비운다.
      C21이 프롬프트로만 말하던 「값이 곧바로 확정되는 칸에는 쓰지 마세요」를 **가장 위험한
      모양에 한해 코드로** 받는 것이다. 이런 값은 지어냈을 때의 피해가 검색어와 급이 다르고,
      **사용자가 스스로 정해야 하는 값**이라 애초에 버튼이 대신할 자리가 아니다.
    - **[_is_whole_goal]** — 목표 문장을 그대로 echo한 값은 비운다. 실기기에서 재현됐던
      버그이고(목적어 없는 목표가 통째로 입력칸에 들어갔다), 프롬프트만으로 막으면 모델이
      지시를 어겼을 때 받아 줄 것이 없다. **대가를 안다**: 목표가 검색어 자체인 짧은 경우
      (`종로 칼국수`)에는 버튼이 안 뜬다. 그쪽은 사용자가 직접 치면 되는 손해라, 요청 문장이
      입력칸에 들어가는 쪽보다 실패 방향이 안전하다.

    **실패로 닫지 않고 비운다.** 안내 문장 자체는 여전히 맞을 수 있고, 값이 없으면 사용자는
    지금과 똑같이 직접 입력한다 — 잃는 것은 부수 기능 하나뿐이다.
    `GuideAction.screen_summary`의 공백 검증을 두지 않은 결정과 같은 결.

    **`type`이 아니면 비운다.** 계약상 뜻이 없는 값이고, tap 화면에 입력 제안이 뜨는 경로가
    된다. 프롬프트도 같은 말을 하지만(`prompt.INPUT_TEXT_INSTRUCTION`), 필드 순서상
    `action_type`이 앞이라 이미 고른 동작과 어긋난 값이 뒤따라 나올 수 있다.
    """
    if action.input_text is None:
        return action
    if (
        action.action_type is ActionType.TYPE
        and _instruction_quotes_input_text(action.input_text, action.instruction)
        and not _looks_sensitive(action.input_text)
        and not _is_whole_goal(action.input_text, goal)
    ):
        return action
    return action.model_copy(update={"input_text": None})


def _looks_sensitive(input_text: str) -> bool:
    """값이 개인정보 패턴에 걸리는가 — 걸리면 버튼이 대신 채울 자리가 아니다.

    **2차 마스킹과 같은 패턴을 쓴다**(`masking.mask_text`). 새 목록을 만들지 않는 이유는 둘이다:
    무엇이 민감한지에 대한 판단이 이미 그 한 곳에 모여 있고, 목록이 둘이면 한쪽만 늘어난다.

    **정책이 아니라 패턴 함수를 직접 부른다.** dev 비교 실험(#203)이 마스킹을 끈 빌드에서도
    이 판정은 서야 한다 — 저 스위치는 "무엇이 서버로 나가는가"를 재려는 것이지 버튼이 무엇을
    채워도 되는지를 정하는 스위치가 아니다.

    **패턴 밖 고유명은 여기서도 못 잡는다**(SPEC §2) — 사람 이름·주소는 그대로 통과한다.
    막을 수 있는 모양만 막는 것이고, 못 막는 자리는 안내문을 읽는 사용자에게 남는다.
    """
    return mask_text(input_text) != input_text


def _is_whole_goal(input_text: str, goal: str) -> bool:
    """값이 **목표 문장 그 자체**인가(실기기 재현, PR #406).

    목적어가 없는 목표에서 모델이 목표를 통째로 복사해 검색창에 부탁하는 말이 들어갔다.
    구두점·띄어쓰기만 다른 재작성도 같은 것으로 본다.
    """
    return _normalize_for_evidence(input_text) == _normalize_for_evidence(goal)


def _instruction_quotes_input_text(input_text: str, instruction: str) -> bool:
    """**사용자가 지금 읽는 문장이 그 말을 집어 말하는가.**

    「바로 입력」이 서는 유일한 근거다([_keep_input_text_named_by_instruction]) — 버튼이 채우는
    값과 사용자가 방금 읽은 문장이 같아야 버튼이 눈으로 확인된 일만 한다.

    **따옴표를 파싱하지 않는다.** C21이 구조화 필드를 둔 이유가 "모델 문장이 조금만 달라져도
    조용히 깨진다"였고 그 판단은 그대로다 — 여기서는 문장을 해석하지 않고, 모델이 준 값이 문장
    안에 있는지만 **완료 근거와 같은 매처**로 본다(`'한국 미국 시차'를`·`한국 미국 시차라고`
    모두 조사 허용으로 걸린다). 파싱은 형태를 맞춰야 하지만 대조는 맞출 것이 없다.

    같은 매처를 쓰므로 **값 연결자 경계 배제도 그대로 적용된다** — 안내문이
    `010-1234-5678을 입력하세요`인데 값이 `010-1234`면 잘린 조각이라 걸러진다(PR #406 리뷰).
    """
    normalized = _normalize_for_evidence(input_text)
    if len(normalized) < _MIN_INPUT_TEXT_CHARS:
        return False
    return _contains_quoted_phrase(_phrase_offsets(instruction), normalized)


def _reject_unclickable_tap(action: GuideAction, screen: Screen) -> None:
    """모델이 **눌러도 반응하지 않는 요소**를 tap 대상으로 골랐으면 실패로 닫는다(C20 · #311).

    실패 모양은 가림 가드와 같다: 눌러도 아무 일이 없는 자리를 가리키면 화면이 그대로고 →
    정체 판정 → 회복 경로가 없다(#39). 실패로 닫으면 앱이 백오프 후 같은 화면을 다시 물어
    모델이 다시 고를 기회가 생긴다. 실측 세 건이 이 모양이었다 — 셰이드의 Wi-Fi 상태
    아이콘(ImageView), 웹뷰의 안내 문구(같은 화면에 정답 버튼이 있었는데 두 번 연속 문구를
    골랐다), 스플래시의 유일한 요소.

    프롬프트도 같은 사실을 말한다(`prompt.CLICKABLE_TARGET_INSTRUCTION`) — 그래도 여기서 한 번
    더 막는 이유는 응답 필드 순서 때문이다. 모델은 대상(`target_node_id`)보다 근거(`evidence`)를
    먼저 쓰는데(C1), 근거에 label 하나를 인용하는 순간 그 요소에 닻이 내려가 몇 필드 뒤의
    선택 규칙 문장이 이기지 못한다. 완료 검증을 위한 순서가 대상 선택에 내는 부수효과라,
    순서(근간)를 두고 마지막에 코드로 받는다.

    `clickable`은 계약 초기부터 non-null bool이라(낡은 앱도 항상 보낸다) 미관측 분기가 없다 —
    C10의 세 상태 규약 대상이 아니다. `False`를 믿는 근거는 2026-08-22 프로브 실측이다:
    clickable=False로 나간 요소 205개 전수(배민·삼성런처·셰이드 120 + 디동 웹뷰 85)에서
    "클릭을 받는데 이름이 없어 목록에서 탈락한 조상"이 0건 — 즉 이 값이 거짓이 되는 직렬화
    경로(병합 탈락)는 관측되지 않았다. 웹뷰를 따로 잰 것은 거기서 role이 뭉개져 clickable이
    사실상 유일한 구별 축이기 때문이다. C18의 "clickable은 설정값이지 상태가 아니다"와
    충돌하지 않는다 — 그 서술은 True를 믿지 말라는 방향이고(비활성 버튼도 True로 온다),
    이 가드는 False의 역방향만 쓴다. 그래서 `enabled`는 보지 않는다 — 비활성 버튼은
    clickable=True로 오므로 여기를 그대로 통과하고, 그 판정은 별도 결정 트랙이다(C20).

    **입력칸(`editable=True`)은 면제한다.** 직렬화는 `isClickable || isEditable`로 요소를
    방출하므로 clickable=False인 입력칸이 계약상 존재할 수 있고, 프롬프트의
    [FOCUSED_INPUT_KEYBOARD_CLOSED]는 키보드가 닫힌 입력칸을 **tap하라고 명시적으로 안내**한다
    — 면제하지 않으면 프롬프트가 시킨 안내를 가드가 반려하는 모순이 된다. 실측(디동 웹뷰)의
    EditText는 전부 clickable=true였지만, isClickable을 세우지 않는 provider를 배제할 근거가
    없다.

    **다른 대상을 대신 고르지 않는다** — 가드는 교정이지 재선택이 아니다(가림 가드와 같은 경계).
    대상이 목록에 없는 tap(환각 id)은 여기서 판정하지 않는다 — 이 가드의 주제는 계약에 있는
    사실 하나이고, 없는 대상은 앱의 `highlight miss`가 따로 드러낸다.
    page_dot도 이 가드에 걸린다 — 노드 자체는 clickable=true지만 `UiTreeSerializer`가
    `clickable = false`로 고정해 내보내므로, "정보원이지 탭 대상이 아니다"라는 결정
    (HANDOFF §3-3)이 그대로 이 가드에 실린다.

    **전부 clickable=False인 화면(스플래시·로딩)은 이 가드가 닫기만 하고 대안을 주지 못한다.**
    백오프 재질의가 같은 거부를 반복할 수 있고, 근본 처치는 계약에 "기다리기"를 넣는 #370이다.
    """
    target = _element_by_id(screen, action.target_node_id)
    if target is None or target.clickable or target.editable:
        return
    raise LlmError(
        ErrorKind.SERVER,
        "안내 대상이 눌러도 반응하지 않는 요소(clickable=False)라 tap을 내보낼 수 없음",
    )


def _resolve_scroll(action: GuideAction, screen: Screen) -> GuideAction:
    """`scroll`의 대상을 요구한다. 교정하거나, 안전한 대체 행동으로 바꾸거나, 실패로 닫는다.

    실측된 실패는 이렇게 흘렀다(#202): `target=-`로 나간 scroll → 앱이 강조할 것이 없어
    `highlight miss` → 사용자가 화면 전체를 쓸어도 아무 일 없음 → 정체 판정 →
    **정체에서는 서버를 다시 부르지 않으므로**(#39, 폴링이 곧 호출량) 회복 경로가 없다.
    같은 모양이 서로 다른 앱에서 두 번 났고 한쪽은 `has_scrollable_element=true`였다 —
    "스크롤할 수 없는 화면"만의 문제가 아니라 **대상 해석이 비는 것 자체**가 원인이다.

    **앱 이름이 아니라 "대상 해석 실패"라는 일반 조건으로 쓴다**(루트 CLAUDE.md).
    """

    # 1. 관측하지 않은 것은 판정하지 않는다. 스크롤 메타를 하나도 안 보낸 낡은 앱의 침묵을
    #    "대상이 없다"로 읽으면, 실제로는 넘어가던 화면의 안내를 서버가 없애 버린다(C10).
    if not _observed_scroll_state(screen):
        return action

    # 2. 그 방향으로 스크롤할 수 있는 요소가 있으면 대상을 채워 내보낸다.
    candidates = _scroll_candidates(screen, action.direction)
    if candidates:
        named = _element_by_id(screen, action.target_node_id)
        if named is not None and any(e.node_id == named.node_id for e in candidates):
            return action
        return action.model_copy(update={"target_node_id": _widest(candidates).node_id})

    # 3. **덮인 화면에서는 "화면 자체"를 조작하라고 하지 않는다.** 아래 두 단은 대상이 요소가
    #    아니라 화면이라(페이지 넘김·앱서랍) 2단의 요소 필터가 닿지 않는다 — 페이지 위치는
    #    화면 단위 상태이고(C5) 앱은 가림 판정과 무관하게 채운다. 모달이 덮은 화면에서
    #    "옆으로 넘기세요"·"위로 쓸어올리세요"가 나가면 팝업이 제스처를 먹어 화면이 그대로고
    #    → 정체 → **정체에서는 서버를 다시 부르지 않으므로**(#39) 회복 경로가 없다. 이 PR이
    #    tap과 2단에서 닫은 실패와 같은 모양이라 같은 근거로 닫는다(PR #336 리뷰).
    #    **조건은 런처가 아니다** — page dot은 "N 페이지 중 M 페이지" 글자로 잡으므로
    #    (`UiTreeSerializer.parsePageDot`) 배너 캐러셀·온보딩처럼 페이지 안내가 있는 화면이면
    #    어디서든 4단이 열린다. 오히려 팝업이 뜬 화면일수록 2단 후보가 가림으로 비어 여기까지
    #    내려온다. 4단만 막고 5단을 두면 덮인 런처에서 앱서랍 안내가 대신 나가 같은 실패다.
    #    막힌 화면은 6단(실패)으로 간다 — 앱이 백오프 후 다시 물어보고, 그때 모델은 가려지지
    #    않은 요소(팝업 자신)만 보므로 "닫기"를 고를 수 있다.
    covered = screen_has_cover(screen.elements)

    # 4. 페이지로 넘기는 화면은 **대상 노드가 없는 것이 정상이다** — 대상이 요소가 아니라
    #    화면 자체다. 삼성 런처 홈·앱서랍은 페이지가 여러 장이어도 scrollable 노드가 0개다
    #    (HANDOFF §3-2). 아직 넘길 쪽이 남았으면 그대로 통과시킨다. 여기를 오류로 닫으면
    #    통과하던 정상 안내를 이 가드가 없애고(#251 리뷰), SPEC §3 시나리오 3의
    #    "앱서랍에서 다음 쪽"이 끊긴다.
    #    모델이 이 화면에서 `page_dot` 같은 요소를 target으로 골라도 비운다 — page dot은
    #    정보원이지 탭 대상이 아니고(HANDOFF §3-3), 대상이 화면 자체라는 근거와도 어긋난다
    #    (#251 리뷰).
    if not covered and _page_move_is_open(screen, action.direction):
        return action.model_copy(update={"target_node_id": None})

    # 5. 대상이 없다 — 이 scroll은 내보낼 수 없다. 안전한 대체 행동이 있는 화면은 그쪽으로 돌린다.
    if not covered and _can_open_app_drawer(screen):
        return GuideAction(
            expected_result=action.expected_result,
            # 행동만 바꾸는 교정이라 화면 요약은 모델이 쓴 것을 그대로 승계한다. 여기서 새로
            # 지어내면 앱이 이력에 담을 `screen_hint`가 실제 화면 관측과 어긋난다.
            screen_summary=action.screen_summary,
            evidence="런처 화면에 스크롤할 수 있는 요소가 없음",
            action_type=ActionType.OPEN_APP_DRAWER,
            instruction="화면 가운데를 위로 쓸어올려 전체 앱 목록을 열어 주세요.",
        )

    # 6. 그 밖에는 대체할 안전한 행동이 계약에 없다 — `done`은 성공 의미이고 "진행 불가"는
    #    보류 항목이다(server/CLAUDE.md). **틀린 안내를 내보내는 대신 실패로 닫는다.**
    #    앱은 이 화면을 백오프 후 다시 물어보므로(`retryThisScreen`), 정체와 달리 회복 경로가
    #    남는다.
    raise LlmError(ErrorKind.SERVER, _no_scroll_target_reason(screen, action.direction))


def _page_move_is_open(screen: Screen, direction: SwipeDirection | None) -> bool:
    """페이지로 넘기는 화면에서 요청 방향에 아직 넘길 쪽이 남았는가.

    **판정 근거는 프롬프트가 쓰는 것과 같은 사실이다**(`prompt._page_boundary` — #217).
    저쪽은 모델에게 "지금이 첫 쪽/마지막 쪽"이라고 말해 주고, 이쪽은 그 사실로 통과 여부를
    정한다. 새 지식이 아니라 이미 관측한 상태다.

    쪽 번호가 어긋나면(`current > total` 등) 아무 결론도 내지 않는다 — 저쪽과 같은 규칙이다.
    세로 방향은 페이지 이동이 아니므로 여기서 통과시키지 않는다.
    """

    current, total = screen.current_page, screen.total_pages
    if current is None or total is None:
        return False
    if total < 1 or not 1 <= current <= total:
        return False
    if direction is SwipeDirection.RIGHT:
        return current < total
    if direction is SwipeDirection.LEFT:
        return current > 1
    return False


def _can_open_app_drawer(screen: Screen) -> bool:
    """실기기로 확인된 런처 화면에만 현재의 안전한 대체 행동을 적용한다(C9).

    **반복 3회를 기다리지 않는다**(#202). 반복 카운트는 "이 scroll이 듣지 않는다"는 증거를
    모으는 장치였는데, 대상 해석 실패가 그 증거를 첫 요청에 결정론적으로 준다. 기다리는 동안
    나가던 헛안내 두 번은 그 자체로 #202가 없애려는 것이다.
    """

    if screen.package_name not in _APP_DRAWER_FALLBACK_PACKAGES:
        return False
    if screen.has_scrollable_element is not False:
        return False
    # 삼성 앱서랍도 같은 package + has_scrollable_element=False다. 앱서랍에는 page 정보가
    # 관측되므로, 페이지가 있는 런처 화면을 앱서랍 열기로 덮지 않는다. 일부 메타만 온 경우도
    # 안전하게 적용하지 않는다.
    return screen.current_page is None and screen.total_pages is None


def _observed_scroll_state(screen: Screen) -> bool:
    """앱이 스크롤 상태를 관측해 보냈는가. 침묵(`None`)은 사실이 아니다."""

    if screen.has_scrollable_element is not None:
        return True
    return any(has_scroll_metadata(element) for element in screen.elements)


def _scroll_candidates(screen: Screen, direction: SwipeDirection | None) -> list[UiElement]:
    """그 방향으로 더 갈 수 있다고 **앱이 관측한** 요소들.

    여유를 모르는 요소를 축만 보고 후보로 올리지 않는다. 앱은 스크롤 상태를 다섯 필드
    (`can_scroll_*`·`scroll_axis`·`next/prev_swipe_direction`) 통째로 채우거나 하나도 안 채우거나
    둘 중 하나라(`UiTreeSerializer.scrollStateOf`), "축만 아는 요소"는 계약상 표현될 뿐 관측된 적이
    없다. 없는 입력을 위한 두 번째 단은 검증할 수 없는 분기라 두지 않는다(#218이
    `SCROLL_ALL_ENDS_REACHED`를 걷어낸 것과 같은 근거).
    """

    if direction is None:  # pragma: no cover - 스키마가 scroll의 direction을 강제한다(C3)
        return []

    # **가려진 요소는 후보가 아니다.** 여기서 거르지 않으면 이 파일 맨 앞의 가림 검사를
    # 우회한다 — 그 검사는 모델이 고른 대상만 보는데, 아래 2단은 대상을 **직접 채워 넣는다**.
    # 모달이 뜬 화면에서 스크롤 가능한 것은 대개 뒤에 깔린 목록이라 흔한 경로다(PR #336 리뷰).
    # `None`(미관측)은 거르지 않는다 — 낡은 앱의 침묵을 "가려짐"으로 읽지 않는다(C10).
    # 전부 가려진 화면에서는 필터를 끈다 — `_reject_obscured_target`과 같은 근거·같은 판정이다.
    if obscured_observation_unreliable(screen.elements):
        return [e for e in screen.elements if _scrolls_toward(e, direction)]
    return [e for e in screen.elements if e.obscured is not True and _scrolls_toward(e, direction)]


def _scrolls_toward(element: UiElement, direction: SwipeDirection) -> bool:
    """이 요소가 그 방향으로 더 갈 수 있다고 앱이 관측했는가(C4).

    `can_scroll_forward`는 그 요소의 `next_swipe_direction` 쪽 여유다 — 요소마다 축이 다르므로
    값 하나로는 못 읽고 방향과 짝지어 봐야 한다(C17).
    """

    return bool(
        (element.can_scroll_forward and element.next_swipe_direction is direction)
        or (element.can_scroll_backward and element.prev_swipe_direction is direction)
    )


def _moves_along(element: UiElement, direction: SwipeDirection | None) -> bool:
    """이 요소가 그 방향의 축으로 움직이는가(여유는 보지 않는다)."""

    if direction is None:  # pragma: no cover - 스키마가 scroll의 direction을 강제한다(C3)
        return False
    return direction in {element.next_swipe_direction, element.prev_swipe_direction}


def _no_scroll_target_reason(screen: Screen, direction: SwipeDirection | None) -> str:
    """대상이 없는 이유를 상황별로 갈라 적는다.

    한 문장으로 덮으면 실기기 로그에서 서로 다른 화면이 한 칸에 섞인다 — 스크롤 요소가 아예
    없는 화면, 요소는 있는데 그 방향만 끝난 화면(가장 흔하다), 앱은 스크롤 요소가 있다고 했는데
    요소 목록에는 안 실린 화면(`UiTreeSerializer.MAX_ELEMENTS` 절단이 유력하다), 세로 목록만
    있는데 모델이 좌우를 고른 화면, 그리고 **가림 때문에 막힌 화면 둘**(그 방향의 스크롤 요소가
    가려진 경우 · 3단이 화면 단위 조작을 막은 경우)은 다음 수가 서로 다르다.

    **"이미 끝"은 그 방향의 축으로 움직이는 요소가 있을 때만 쓴다.** 축을 안 보면 세로 목록
    화면의 `scroll right`가 "right 방향은 이미 끝"으로 나가 네 번째 상황이 첫 칸에 섞인다.
    """

    # **"가려짐"을 "이미 끝"보다 먼저 본다.** 후보가 가려져서 빈 경우 아래 첫 분기가 참이 되어
    # (가려진 목록도 `can_scroll_forward=True`다) 끝이 아닌데 "이미 끝"으로 적힌다. 이 함수의
    # 존재 이유가 상황을 갈라 적는 것이라, 실기기 로그가 이 문구를 들고 오면 엉뚱한 데를 판다.
    if direction is not None and any(
        _scrolls_toward(element, direction) and element.obscured is True
        for element in screen.elements
    ):
        return (
            f"scroll 대상을 해석할 수 없다: {direction} 방향의 스크롤 요소가 "
            "다른 요소에 가려져 있음"
        )
    # **가림이 화면 단위 조작을 막은 경우도 따로 적는다.** 3단이 4·5단을 막고 여기로 보내면
    # 아래 분기들은 "스크롤 요소가 없다"고만 적는데(런처는 실제로 0개다), 없어서가 아니라
    # 덮여서 막은 것이라 실기기 로그가 엉뚱한 데를 판다.
    if screen_has_cover(screen.elements) and (
        _page_move_is_open(screen, direction) or _can_open_app_drawer(screen)
    ):
        return (
            "scroll 대상을 해석할 수 없다: 화면이 다른 요소에 가려져 "
            "화면 단위 조작(페이지 넘김·앱서랍)을 내보내지 않음"
        )
    if any(_moves_along(element, direction) for element in screen.elements):
        return f"scroll 대상을 해석할 수 없다: 스크롤 요소는 있으나 {direction} 방향은 이미 끝"
    if any(has_scroll_metadata(element) for element in screen.elements):
        return f"scroll 대상을 해석할 수 없다: 스크롤 요소가 {direction} 축으로 움직이지 않음"
    if screen.has_scrollable_element:
        return (
            "scroll 대상을 해석할 수 없다: 화면에 스크롤 요소가 있다고 왔는데 "
            "요소 목록에 실리지 않음(요소 수 절단 의심)"
        )
    return "scroll 대상을 해석할 수 없다: 그 방향으로 스크롤할 수 있는 요소가 화면에 없음"


def _element_by_id(screen: Screen, node_id: str | None) -> UiElement | None:
    if node_id is None:
        return None
    return next((e for e in screen.elements if e.node_id == node_id), None)


def _widest(candidates: list[UiElement]) -> UiElement:
    """후보가 여럿이면 화면에서 가장 넓은 스크롤 영역을 고른다.

    사용자는 요소가 아니라 **화면을 쓴다.** 목록 위에 작은 캐러셀이 겹친 화면에서 넓은 쪽이
    화면 전체 제스처가 실제로 움직이는 영역이고, 강조 사각형도 그쪽이 알아보기 쉽다.
    좌표가 없는 요소는 넓이 0으로 두고, 같은 넓이면 화면에 실린 순서를 지킨다(결정론).
    """

    _, widest = max(enumerate(candidates), key=lambda pair: (_area(pair[1]), -pair[0]))
    return widest


def _area(element: UiElement) -> int:
    if element.bounds is None or len(element.bounds) != 4:
        return 0
    left, top, right, bottom = element.bounds
    return max(right - left, 0) * max(bottom - top, 0)


def _screen_signature(screen: Screen) -> str:
    """PII·volatile text를 제외한 화면 구조의 결정론적 서명.

    label은 서버가 이미 받는 값이지만 해시만 장기간 남아도 흔한 문자열은 역추정할 수 있다.
    그래서 label의 원문·해시·길이 모두 제외한다. 시계·배터리 텍스트 변화도 같은 화면으로 본다.

    **`window_title`도 같은 이유로 뺀다**(#288 리뷰). 대화방 제목은 사람 이름이고 문서 뷰어는
    파일명을 싣는다 — 위험 등급이 label과 같다. 2차 마스킹을 거쳤다는 것은 근거가 못 된다.
    label도 마스킹 뒤에 제외되기 때문이다. 기기 안에서만 쓰는 `ScreenSignature`는 label과 제목을
    둘 다 포함하는데, 그 값은 기기를 떠나지 않으므로 여기의 기준과 어긋나지 않는다.
    대신 package/page/요소 순서·역할·좌표·상호작용/스크롤 상태가 바뀌면 서명이 바뀐다.

    이 선택은 구조·좌표가 같고 내용만 바뀐 화면을 구분하지 못한다. 목록 항목이 label만 바뀌면
    실제 화면 변화가 있어도 같은 서명이다. 따라서 이 서명만으로 모든 화면 동일성을 주장하면 안 된다.

    **지금 이 값을 읽는 서버 코드는 없다.** 반복 카운트를 걷으면서(#202) 소비자가 사라졌고,
    응답에 실어 앱이 이력에 붙이는 값으로만 남아 있다. 알고리즘이 바뀌면 앱이 보관한 이전
    서명과 새 서명이 달라지지만, 서버가 그 값으로 판정하지 않으므로 안내가 바뀌지는 않는다.
    """

    structure = {
        "package_name": screen.package_name,
        "current_page": screen.current_page,
        "total_pages": screen.total_pages,
        "has_scrollable_element": screen.has_scrollable_element,
        "ime_visible": screen.ime_visible,
        "elements": [
            {
                "node_id": element.node_id,
                "role": element.role,
                "clickable": element.clickable,
                "editable": element.editable,
                "focused": element.focused,
                # 행동 상태(C18). **이게 없으면 스위치를 켜도 같은 서명이 나온다** — label에
                # 입력 원문도 토글 상태도 들어가지 않으므로 이 자리가 유일한 변화 신호다(#271).
                "enabled": element.enabled,
                "checked": element.checked,
                "selected": element.selected,
                "showing_hint_text": element.showing_hint_text,
                # 모달이 뜨고 걷히면 같은 요소의 가림 여부가 뒤집힌다. 앱 시그니처
                # (`ScreenSignature`)에도 같은 이유로 들어 있어 두 서명이 대칭이다.
                "obscured": element.obscured,
                "bounds": element.bounds,
                "can_scroll_forward": element.can_scroll_forward,
                "can_scroll_backward": element.can_scroll_backward,
                "scroll_axis": element.scroll_axis,
                "next_swipe_direction": element.next_swipe_direction,
                "prev_swipe_direction": element.prev_swipe_direction,
            }
            for element in screen.elements
        ],
    }
    canonical = json.dumps(
        structure,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _verify_done(action: GuideAction, req: NextStepRequest) -> bool:
    """완료 판정 2차 검증. 근거가 **화면에 있는 것인지**까지 본다(SPEC §4 결정).

    두 근거 필드는 `GuideAction`의 필수 필드라 구조화 출력이 항상 채운다. 비었는지만 보면
    `done`은 언제나 통과했다(빈 evidence로 done을 거부한 실측 사례는 그 앞 단계에서 걸린 것이다).

    **목표 달성에 충분한 근거인지는 판정하지 않는다.** 최소 두 글자·어절 경계로 우연 일치만
    줄이고, role/clickable이나 목표 문자열 겹침은 쓰지 않는다 — #71 fixture에서 정상 완료를
    더 많이 막았다. 이 함수는 `verified` 플래그만 정확하게 만들고 안내를 막지 않는다.
    """
    if action.action_type is not ActionType.DONE:
        return False
    if not action.evidence.strip() or not action.expected_result.strip():
        return False
    return _evidence_quotes_screen(action.evidence, req.screen)


def _evidence_quotes_screen(evidence: str, screen: Screen) -> bool:
    """근거가 화면 label 중 하나를 실제로 물고 있는가.

    label은 2차 마스킹을 거친 값이다 — 모델이 본 것과 같은 값으로 비교해야 한다.
    구두점 차이는 어절 토큰화로 흡수하고, 한국어 조사·서술격 어미만 label의 마지막 어절 뒤에
    허용한다(`카카오톡` vs `카카오톡이`). 임의의 접두 일치는 허용하지 않는다: label `사용 중`을
    근거 `사용 중지했습니다`에서 찾으면 정반대 상태가 통과한다(#71).
    """
    phrases = _phrase_offsets(evidence)
    if not phrases.compact:
        return False

    # **모델이 본 목록으로만 대조한다.** 가려진 요소는 프롬프트에 실리지 않으므로
    # (`prompt._visible_elements`) 그 label에 걸리는 근거는 모델이 볼 수 없었던 것이고,
    # 대조에 넣으면 지어낸 근거를 통과시키는 쪽으로만 작용한다 — 검증이 느슨해진다.
    comparable = [
        normalized
        for element in visible_elements(screen.elements)
        if len(normalized := _normalize_for_evidence(element.label)) >= _MIN_EVIDENCE_LABEL_CHARS
    ]
    if not comparable:
        # 화면에 비교 가능한 label이 하나도 없으면 대조할 것이 없다. 검사할 수 없는 것을 통과로
        # 돌리면 앱 전환 직후 스플래시에서 온 `done`이 그대로 세션을 끝낸다(#331 실측 2건).
        # 한 글자 label도 아이콘·숫자라 아무 문장에나 걸리므로 폴백하지 않는다(#71).
        # `verified=false`는 안내를 막지 않고 앱의 재시도 예산이 받는다(SPEC §4 2026-08-21 개정).
        return False

    return any(_contains_quoted_phrase(phrases, label) for label in comparable)


class _Phrases(NamedTuple):
    """어절 토큰화 결과. [_contains_quoted_phrase]가 경계를 판정하는 데 필요한 전부다.

    대조 대상이 여럿인 호출자는 한 번만 만들어 재사용한다.
    """

    compact: str
    """어절만 이어 붙인 문자열. 구두점·공백이 빠져 `Wi-Fi`와 `WiFi`가 같은 값이 된다."""

    starts: list[int]
    ends: list[int]
    """[compact] 안에서 각 어절이 시작·끝나는 자리."""

    glued: list[bool]
    """이 어절이 **다음 어절과 한 값인가**([_VALUE_CONNECTORS]로만 이어졌는가). 마지막은 False.

    참이면 그 자리는 원문에 없던 경계이므로 인용의 시작·끝이 될 수 없다.
    """


def _phrase_offsets(text: str) -> _Phrases:
    """문장 하나를 어절로 끊어 [_Phrases]로 만든다."""
    folded = text.casefold()
    matches = list(_EVIDENCE_WORD.finditer(folded))
    starts: list[int] = []
    ends: list[int] = []
    glued: list[bool] = []
    cursor = 0
    for index, match in enumerate(matches):
        starts.append(cursor)
        cursor += match.end() - match.start()
        ends.append(cursor)
        following = matches[index + 1] if index + 1 < len(matches) else None
        separator = folded[match.end() : following.start()] if following else ""
        glued.append(bool(separator) and all(char in _VALUE_CONNECTORS for char in separator))
    return _Phrases("".join(m.group() for m in matches), starts, ends, glued)


def _contains_quoted_phrase(phrases: _Phrases, normalized_phrase: str) -> bool:
    """미리 토큰화한 문장에 정규화한 문구의 어절 연속이 있는지 판정한다.

    구두점·공백을 접은 문자열은 비교하되 시작은 어절 경계여야 하고, 끝은 어절 경계이거나 정해 둔
    한국어 조사·서술격 어미만 남아야 한다. 그래서 `Wi-Fi`/`WiFi` 차이는 받으면서도 어휘가 이어진
    부분 문자열은 받지 않는다.

    **대조 둘이 이 규칙을 공유한다** — 완료 근거가 화면 label을 물었는지
    (`_evidence_quotes_screen`)와 복사 문구가 안내문에 있는 말인지
    (`_instruction_quotes_input_text`)다. 이름에 evidence·label이 없는 이유가 그것이고,
    경계를 이렇게 정한 근거는 #71 fixture 평가다.
    """
    for index, start in enumerate(phrases.starts):
        # 앞 어절과 한 값이면 그 자리는 원문에 없던 경계다 — `010-1234-5678`의 `1234`에서
        # 시작하는 인용은 값의 조각이지 인용이 아니다.
        if index > 0 and phrases.glued[index - 1]:
            continue
        if not phrases.compact.startswith(normalized_phrase, start):
            continue
        match_end = start + len(normalized_phrase)
        # **한 번만 훑는다**(PR #406 재리뷰). `ends`가 단조증가라, 끝나는 어절을 한 번 찾으면
        # 정확 일치(`end == match_end`)와 조사 허용(`end > match_end`)이 같은 자리에서 갈린다.
        last = next(
            (i for i, end in enumerate(phrases.ends) if end >= match_end),
            None,
        )
        if last is None:
            continue
        word_end = phrases.ends[last]
        if word_end != match_end and not _is_allowed_korean_evidence_suffix(
            phrases.compact[match_end:word_end]
        ):
            continue
        # 끝나는 어절이 다음 어절과 한 값이면 **뒤가 잘린 것**이다. 조사까지 먹은 경우도
        # 그 어절은 통째로 소비된 것이라 판정이 같다.
        if not phrases.glued[last]:
            return True
    return False


def _is_allowed_korean_evidence_suffix(suffix: str) -> bool:
    """label 뒤의 조사·서술격 어미인가.

    복수 접미사 `들`은 한 번 붙을 수 있고, 격조사 뒤에는 보조사 한 개를 조합할 수 있다
    (`사진들`, `사진들이`, `설정에서도`, `사진들에서는`). 임의의 동사 접미사나 보조사를 두 번
    이어 붙인 문자열은 받지 않는다.
    """
    tail = suffix.removeprefix("들")
    if not tail or tail in _KOREAN_EVIDENCE_SUFFIXES:
        return True
    return any(
        base != auxiliary and tail == base + auxiliary
        for base in _KOREAN_EVIDENCE_COMPOUND_BASES
        for auxiliary in _KOREAN_EVIDENCE_AUXILIARY_SUFFIXES
    )


def _normalize_for_evidence(text: str) -> str:
    return _EVIDENCE_NOISE.sub("", text.casefold())


def next_step(
    req: NextStepRequest,
    llm: LlmClient,
    masking: MaskingPolicy | None = None,
) -> NextStepResponse:
    """앱 요청 하나 -> 다음 안내 하나. 파이프라인의 진입점.

    `masking`을 주지 않으면 설정이 정한 정책을 쓴다. 기본은 항상 2차 마스킹 적용이며,
    우회는 local development 비교 실험에서만 켜진다(#203).
    """
    # 2차 마스킹 그물: 모델에 넘기기 전에 한 번 더(1차는 앱에서 이미 수행).
    #
    # 스텝 이력도 함께 훑는다. 1차가 놓친 값이 label에 있었다면 모델이 그걸 instruction이나
    # 화면 요약에 인용하고, 앱은 그 값을 스텝에 담아 매 요청마다 다시 보낸다 — 화면은 넘어가도
    # 이력은 남으므로 놓친 값이 계속 재전송된다. 그물은 들어오는 모든 텍스트에 걸려야 의미가
    # 있다(SPEC §2). `target_label`은 화면 label 그 자체라 더 말할 것도 없다.
    policy = masking if masking is not None else active_masking_policy()
    masked = req.model_copy(
        update={
            "screen": policy.apply_screen(req.screen),
            "steps": policy.apply_steps(req.steps),
        }
    )

    action = llm.reason(masked)
    action = _apply_guardrails(action, masked)

    verified = _verify_done(action, masked)
    return NextStepResponse(
        action=action,
        # DAVI_MODEL을 비우면 공급자 기본 모델이 쓰인다. 앱·eval이 받는 값은
        # 설정 문자열이 아니라 **실제로 호출된 모델 id**여야 한다.
        model=settings.resolved_model,
        verified=verified,
        screen_signature=_screen_signature(masked.screen),
    )
