"""반복 대상 배제 가드의 발화 조건을 재는 계측(#429 1단계).

배제는 모델의 선택지를 지우는 개입이라 **오발화 비용을 먼저 수치로 만든다.** 선례인
`reasoning._reject_obscured_target`은 요청을 실패로 닫아서, 모델이 같은 선택을 반복하면
사용자에게는 「다른 안내」가 아니라 **안내가 아예 안 나오는 정지**로 나타난다(#411 2번이
그 모양을 실측했다). 시니어 대상이라 그 비용을 모르고 넣지 않는다.

**판정을 다시 구현하지 않는다.** 프롬프트가 쓰는 그 함수를 그대로 읽는다 — 사본을 만들면
계측이 서버가 아니라 사본을 재게 되고, 한쪽만 고쳐지는 순간 수치가 조용히 거짓이 된다.
그래서 `prompt`의 비공개 헬퍼를 직접 부른다. 이 결합은 의도이고
`test_loop_axes.py`가 프롬프트 문장과 함께 고정한다.

세 축은 #429 본문의 표 그대로다.

| 축 | 여기서 | 무엇으로 |
|---|---|---|
| 루프 판정 | `loop_tried` ∨ `loop_returned` | 요소 줄의 시도 횟수 · 이력의 회귀 표시 |
| 대상 특정 | `target_identifiable` | 구조가 일치하는 구간에서만 참 |
| 인과 | `causal_performed` · `causal_observed` · `causal_widened` | 좁은 축 · 중간 축 · 넓힌 축 |

인과를 세 값으로 내는 이유는 #429의 계측 항목 넷째다 — 「인과 축을 넓혔을 때 발화가
살아나는가」는 **같은 요청에서 함께 재야** 답이 나온다. 가운데 축(`causal_observed`)을 따로
두는 것은 이슈 표의 문구가 「`performed=true` ∨ 그 화면에서 **조작이 관측됨**」이기 때문이다 —
`performed=False`는 「안내한 대상이 아닌 곳을 눌렀다」라 **조작이 관측된** 경우다. 넓힌 축만
재면 그 구간이 `screen_changed`에 묻혀 사후에 못 가른다.

**비식별 값만 담는다.** 전부 bool·정수이고 label·goal·서명 원문은 넣지 않는다
(`observability`의 허용 필드 규약, `server/CLAUDE.md` 로그 절).
"""

from __future__ import annotations

from dataclasses import asdict, dataclass

from .prompt import (
    _content_confirms,
    _recent,
    _returned_to,
    _tried_counts,
)
from .schemas import NextStepRequest
from .signature import screen_signature

# **계측의 반복 정의**이지 가드의 임계가 아니다. 가드의 N은 이 계측이 낸 수치로 정한다
# (#429 「정할 것」 첫 줄 — 모델의 자발적 전환점이 2~3회라 표본이 얇다).
# 여기서 2를 쓰는 것은 "같은 대상에 두 번 이상 안내했다"가 반복의 최소 정의라서다.
REPEAT_THRESHOLD = 2


@dataclass(frozen=True)
class LoopAxes:
    """한 요청에서 본 세 축의 상태. 그대로 로그 필드가 된다."""

    # 모수 — 이력이 짧으면 축이 설 수 없으므로 비율의 분모로 쓴다.
    steps: int
    # 루프 판정 ①: 구조 축. 지금 화면에서 한 요소에 몇 번까지 안내했나.
    tried_max: int
    tried_repeat_targets: int
    # 루프 판정 ②: 회귀 표시. `returned_confirmed`는 그중 내용 축까지 확인된 것(#410).
    returned_marks: int
    returned_confirmed: int
    # 대상 특정: 구조가 같은 스텝 수. 0이면 `node_id`가 다른 요소를 가리킨다(#429 ①).
    structure_matches: int
    loop_tried: bool
    loop_returned: bool
    target_identifiable: bool
    # 반복 대상이 특정되는가 — 위가 「대상이 하나라도 있다」면 이쪽은 「두 번 이상 안내한
    # 대상이 있다」다. 회귀 표시 단독으로 루프가 설 때 둘이 갈리고, 그 차이가 ①의 상·하한이다.
    target_repeat_identifiable: bool
    causal_performed: bool
    causal_observed: bool
    causal_widened: bool
    # 마지막 스텝이 지금 화면의 것인가. 이슈 표의 인과는 「**그 화면에서** 조작이 관측됨」인데
    # A→B→A 회귀에서 마지막 스텝은 B다 — 어느 정의로 셀지 사후에 가를 수 있게 남긴다.
    last_step_here: bool
    # 세 축이 모두 참인가. 인과를 좁은 축·중간 축·넓힌 축으로 각각 세어 ④의 델타를 낸다.
    axes_all: bool
    axes_all_observed: bool
    axes_all_narrow: bool


def snapshot(req: NextStepRequest) -> LoopAxes:
    """이 요청에서 세 축이 각각 어떻게 서 있는지."""

    current_structure = screen_signature(req.screen)
    current_content = req.screen.content_signature
    recent = _recent(req)
    tried = _tried_counts(req, current_structure)

    tried_max = max(tried.values(), default=0)
    returned = [
        step
        for index, step in enumerate(recent)
        if _returned_to(step, index, recent, current_structure, current_content)
    ]
    structure_matches = sum(1 for step in recent if step.structure_signature == current_structure)

    # **인과는 마지막 스텝만 본다.** 배제가 발화할 자리가 "지금 이 화면에서 다음 하나를
    # 고르는" 순간이고, 그 직전 관측이 마지막 스텝이다. 이력 전체를 세면 세션 초반의 조작이
    # 지금의 인과를 세워 준다.
    last = recent[-1] if recent else None
    causal_performed = last is not None and last.performed is True
    # 「조작이 관측됨」 — `False`도 관측이다(다른 곳을 눌렀다는 뜻). `None`만 미관측이다.
    causal_observed = last is not None and last.performed is not None
    # 넓힌 축(#429 설계 3번). `screen_changed`는 「구조 변화 ∨ 인과 있는 내용 변화」라
    # 기기가 이미 접어 넣은 값이다 — 앱이 새로 실을 필요가 없다. 다만 구조 축에는 인과
    # 게이트가 없어(#456) 과대 계상이고, 그 크기를 재는 것이 이 계측의 목적이다.
    causal_widened = causal_performed or (last is not None and last.screen_changed is True)

    loop_tried = tried_max >= REPEAT_THRESHOLD
    loop_returned = bool(returned)
    # 셀 대상이 없으면 뺄 대상도 없다. `tried`가 비어 있지 않다는 것 자체가 구조 일치를
    # 뜻하므로(`_tried_counts`가 구조가 같은 스텝만 센다) 구조 조건을 겹쳐 쓰지 않는다 —
    # `structure_matches`는 판정이 아니라 사후에 볼 수치로만 남긴다.
    target_identifiable = bool(tried)
    target_repeat_identifiable = any(count >= REPEAT_THRESHOLD for count in tried.values())
    loop_detected = loop_tried or loop_returned

    return LoopAxes(
        steps=len(recent),
        tried_max=tried_max,
        tried_repeat_targets=sum(1 for count in tried.values() if count >= REPEAT_THRESHOLD),
        returned_marks=len(returned),
        returned_confirmed=sum(1 for step in returned if _content_confirms(step, current_content)),
        structure_matches=structure_matches,
        loop_tried=loop_tried,
        loop_returned=loop_returned,
        target_identifiable=target_identifiable,
        target_repeat_identifiable=target_repeat_identifiable,
        causal_performed=causal_performed,
        causal_observed=causal_observed,
        causal_widened=causal_widened,
        last_step_here=last is not None and last.structure_signature == current_structure,
        axes_all=loop_detected and target_identifiable and causal_widened,
        axes_all_observed=loop_detected and target_identifiable and causal_observed,
        axes_all_narrow=loop_detected and target_identifiable and causal_performed,
    )


def log_fields(axes: LoopAxes) -> dict[str, int | bool]:
    """로그 이벤트에 그대로 펼칠 필드. 값이 전부 bool·정수라 변환이 없다."""

    return asdict(axes)
