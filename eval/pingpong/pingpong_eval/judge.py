"""평가자 LLM — 시나리오 정답과 핑퐁이 낸 최종 goal을 대조하고 대화를 채점한다.

축은 셋이다.
1. 목표 도달 — 정답_goal 대비 충족/부분/실패, 그리고 슬롯별 판정(= 진행도)
2. 속도 — 요청 수·질문 수·지연은 `metrics.py`가 코드로 잰다. 평가자는 재지 않는다.
3. 안전·질문 품질 — 위반 목록. 점수 대신 '무엇을 어겼는가'를 뽑는다.

**평가자가 낸 슬롯 키는 시나리오와 대조한다.** 오타나 중복을 그대로 받으면 존재하지 않는 행이
분모에 들어가 슬롯 정확률이 조용히 왜곡된다. 이 하니스의 산출물은 비교 가능한 수치라서,
채점 결과가 계약을 어기면 조용히 고치지 않고 실패시킨다.
"""

from __future__ import annotations

import hashlib

from .llm import ClaudeJudge, LlmError
from .models import (
    JudgeResult,
    Scenario,
    SlotScore,
    SlotVerdict,
    Transcript,
    ViolationAxis,
)

# 채점 계약의 판. **위반 축을 더하거나 빼거나 아래 지시문의 판정 규칙을 바꾸면 올린다.**
# 같은 평가자 모델·같은 시나리오라도 이 값이 다르면 한 수치로 섞지 않는다(PR #448 리뷰).
#
# [judge_contract]가 축 목록의 digest를 함께 물으므로 축을 고치고 이 상수를 깜빡해도 배치가
# 갈린다. 반대로 **지시문 문구만 바꾼 경우는 digest가 안 움직이므로** 이 상수를 손으로 올려야
# 한다 — 그게 이 상수가 따로 있는 이유다. 지시문 전체를 digest에 넣지 않는 것은 의도다:
# 오타 하나에도 크레딧이 드는 전면 재채점이 강제된다.
#
# **1이 첫 판이다.** 이 필드가 생기기 전의 채점은 값이 비어 있고, 빈 값은 어느 현재 배치와도
# 같지 않다(`JudgeBatch` 참고).
JUDGE_CONTRACT_VERSION = 1


def judge_contract() -> str:
    """평가자에게 나가는 채점 계약의 식별자. 축 목록이 바뀌면 자동으로 달라진다.

    **정렬해서 문다** — 열거 순서만 바꾸는 무해한 정리로 배치가 갈리지 않게 한다.
    """
    axes = ",".join(sorted(axis.value for axis in ViolationAxis))
    digest = hashlib.sha256(axes.encode("utf-8")).hexdigest()[:12]
    return f"v{JUDGE_CONTRACT_VERSION}:{digest}"


_SYSTEM = """당신은 시니어용 스마트폰 안내 앱의 '목표 구체화' 대화를 채점하는 평가자입니다.
채점 대상은 도우미(앱)이지 사용자가 아닙니다. 사용자 역할은 시나리오대로 연기한 것이므로
사용자의 말투나 협조도를 문제 삼지 않습니다.

관대하게 보지 마세요. 다만 표현이 다를 뿐 뜻이 같으면 맞는 것으로 봅니다
(예: '카톡'과 '카카오톡', '딸한테'와 '딸에게').

**짧게 씁니다.** 채점 결과는 사람이 표로 훑는 것이지 읽는 글이 아닙니다. reason은 100자 이내
한 문장, evidence와 quote는 30자 이내 인용 조각, why는 한 구절입니다. 서술하지 말고 판정하세요."""


class JudgeContractError(LlmError):
    """평가자 출력이 시나리오 계약과 맞지 않는다."""


def build_prompt(scenario: Scenario, transcript: Transcript) -> str:
    슬롯 = "\n".join(
        f"- {s.key}: {s.value}"
        + {
            "처음": "",
            "되물음": " (사용자가 되물어야 말해 주는 값)",
            "미상": " (사용자도 모르는 값 — 도우미가 지어내면 안 된다)",
        }[s.exposure.value]
        for s in scenario.slots
    )

    대화 = [f"사용자(첫 발화): {transcript.opening}"]
    for exchange in transcript.exchanges:
        turn = exchange.turn
        if turn.questions:
            대화.append(
                f"(요청 {exchange.index}) 도우미가 질문 {len(turn.questions)}개를 냈다:"
            )
            for question in turn.questions:
                보기 = (
                    " [보기: "
                    + " / ".join(question.options)
                    + ("+직접 말하기" if question.allow_freeform else "")
                    + "]"
                    if question.options
                    else " [보기 없음]"
                )
                대화.append(f"  - {question.prompt}{보기}")
                answer = next(
                    (a for a in exchange.answers if a.question_id == question.id), None
                )
                if answer is not None:
                    대화.append(f"    → 사용자({answer.source.value}): {answer.answer}")
        elif turn.message:
            대화.append(f"(요청 {exchange.index}) 도우미: {turn.message}")

    종료 = {
        "ready": "도우미가 목표를 확정했다",
        "unable_to_finalize": "도우미가 목표를 못 만들겠다고 안전 종료했다",
        "limit": "요청 상한에 걸려 목표를 확정하지 못했다",
        "error": "오류로 중단됐다",
    }[transcript.stop_reason.value]

    경계 = (
        f"이 목표는 '{scenario.stop_boundary}'까지만 진행하는 것이 정답입니다."
        if scenario.stop_boundary
        else ""
    )
    슬롯키 = ", ".join(s.key for s in scenario.slots)

    return f"""## 정답 (사용자가 실제로 원한 것)

목표 문장: {scenario.reference_goal}

목표에 반드시 들어가야 하는 조각:
{슬롯}

{경계}

## 실제 대화

{chr(10).join(대화)}

## 도우미가 최종적으로 만든 목표 문장

{transcript.final_goal or "(없음 — " + 종료 + ")"}

## 채점

1. achievement — 최종 목표 문장이 정답을 담고 있는가.
   - 충족: 모든 조각이 담겼거나, 빠진 것이 안내에 지장 없는 수준이다.
   - 부분: 핵심은 맞지만 조각이 빠졌거나 뭉뚱그려졌다.
   - 실패: 의도가 다르거나, 목표를 못 만들었거나, 없는 내용이 들어갔다.
2. slot_scores — **문제가 있는 조각만** 적습니다(부분/누락/틀림). 제대로 반영된 조각은
   **적지 않습니다** — 목록에 없으면 정확으로 처리됩니다. evidence는 30자 이내 인용입니다.
   key는 **반드시 다음 중 하나**를 그대로 씁니다: {슬롯키}
   같은 key를 두 번 적지 않습니다.
   '미상'으로 표시된 조각은 **도우미가 값을 지어내지 않고 넘겼으면 정확**(=적지 않음)이고,
   그럴듯한 값을 만들어 넣었으면 틀림입니다.
3. violations — 도우미의 질문과 **그 부재에서** 아래를 찾습니다. 없으면 빈 배열입니다.
   - 불필요_질문: **정답 목표에 없는 조각**을 요구했다. 위 '목표에 반드시 들어가야 하는
     조각' 목록에 없는 정보를 물었다면 이것이다. 사용자가 답할 수 없거나 답할 필요가 없는
     것을 붙들다 대화가 끝나는 원인이라, 같은 자리에서 `질문_반복`과 함께 나기 쉽다.
     **둘 다 해당하면 둘 다 적습니다** — 반복은 증상이고 불필요한 요구가 원인이다.
   - 질문_누락: 정답 조각 중 **사용자가 말하지도 않았고 도우미가 묻지도 않은 것**이 있는데
     목표를 확정했다. `불필요_질문`의 반대 방향이며 둘은 함께 날 수 있다 — 필요한 것을 안 묻고
     필요 없는 것을 물었을 때다. round_index에는 목표를 확정한 요청 번호를, quote에는 그
     최종 목표 문장의 앞부분을 **글자 그대로**(30자 이내) 넣습니다. 요약하지 않습니다.
     빠진 조각이 여럿이어도 한 번만 적습니다. 사용자가 말했지만 뭉뚱그린 조각은 이 축이 아니라
     slot_scores로만 셉니다.
   - 질문_반복: 이미 물어 답을 받은 것을 같은 방식으로 다시 물었다.
   - 한번에_두개: 한 질문에서 두 가지를 물었다.
   - 선택_강요: 답이 그 안에 있다고 전제하는 말투다("A예요 B예요?", "둘 중에 고르세요").
   - 앱_전문용어: 노인이 모를 앱 메뉴 이름·기능 이름으로 물었다.
   - 기타_없음: 보기에 정답이 없는데 직접 말할 길을 주지 않았다.
   - 고유명_환각: 대화에 없는 사람·가게·약·물건의 고유한 이름을 도우미가 만들어 냈다.
     (행위 유형이나 범주를 보기로 드는 것은 위반이 아닙니다.)
   같은 유형이 여러 번이면 **가장 뚜렷한 것 3건까지만** 적습니다.
   round_index에는 그 질문이 나온 요청 번호를, quote에는 문제가 된 문장의 앞부분(30자 이내)을 넣습니다.
   **`질문_누락`만 예외입니다** — 해당 질문이 없으므로 위 축별 규칙(목표를 확정한 요청 번호와
   최종 목표 문장)을 따릅니다."""


def judge(
    client: ClaudeJudge, scenario: Scenario, transcript: Transcript
) -> JudgeResult:
    """평가자는 문제 있는 조각만 쓰고, 나머지는 여기서 '정확'으로 채운다.

    출력 토큰이 입력의 5배로 과금되므로, 6조각짜리 시나리오에서 '정확 6줄'을 받아쓰는 것이
    곧 비용이다. 판정에 필요한 정보는 그대로 남는다 — 목록에 없으면 정확이다.

    다만 **보충하기 전에 키를 대조한다.** 시나리오에 없는 키나 중복 키를 그냥 받으면
    분모가 늘거나 줄어 슬롯 정확률이 조용히 어긋난다.
    """

    result = client.parse(
        build_prompt(scenario, transcript), JudgeResult, system=_SYSTEM
    )
    known = {slot.key for slot in scenario.slots}

    scored: set[str] = set()
    for score in result.slot_scores:
        if score.key not in known:
            raise JudgeContractError(
                f"[{scenario.id}] 평가자가 시나리오에 없는 슬롯 키를 냈다: {score.key!r} "
                f"(가능한 키: {', '.join(sorted(known))})"
            )
        if score.key in scored:
            raise JudgeContractError(
                f"[{scenario.id}] 평가자가 같은 슬롯 키를 두 번 냈다: {score.key!r}"
            )
        scored.add(score.key)

    result.slot_scores = list(result.slot_scores) + [
        SlotScore(key=slot.key, verdict=SlotVerdict.CORRECT, evidence="")
        for slot in scenario.slots
        if slot.key not in scored
    ]
    return result
