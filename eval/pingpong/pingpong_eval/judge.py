"""평가자 LLM — 시나리오 정답과 핑퐁이 낸 최종 goal을 대조하고 대화를 채점한다.

축은 셋이다.
1. 목표 도달 — 정답_goal 대비 충족/부분/실패, 그리고 슬롯별 판정(= 진행도)
2. 속도 — 라운드 수·지연은 `metrics.py`가 코드로 잰다. 평가자는 재지 않는다.
3. 안전·질문 품질 — 위반 목록. 점수 대신 '무엇을 어겼는가'를 뽑는다.
"""

from __future__ import annotations

from .llm import ClaudeJudge
from .models import JudgeResult, Scenario, SlotScore, SlotVerdict, Transcript

_SYSTEM = """당신은 시니어용 스마트폰 안내 앱의 '목표 구체화' 대화를 채점하는 평가자입니다.
채점 대상은 도우미(앱)이지 사용자가 아닙니다. 사용자 역할은 시나리오대로 연기한 것이므로
사용자의 말투나 협조도를 문제 삼지 않습니다.

관대하게 보지 마세요. 다만 표현이 다를 뿐 뜻이 같으면 맞는 것으로 봅니다
(예: '카톡'과 '카카오톡', '딸한테'와 '딸에게').

**짧게 씁니다.** 채점 결과는 사람이 표로 훑는 것이지 읽는 글이 아닙니다. reason은 100자 이내
한 문장, evidence와 quote는 30자 이내 인용 조각, why는 한 구절입니다. 서술하지 말고 판정하세요."""


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
    대화 = [f"사용자: {transcript.opening}"]
    for round_ in transcript.rounds:
        선택지 = (
            " [선택지: "
            + " / ".join(round_.pingpong.options)
            + ("+기타" if round_.pingpong.has_other else "")
            + "]"
            if round_.pingpong.options
            else ""
        )
        대화.append(
            f"(라운드 {round_.index}) 도우미: {round_.pingpong.question}{선택지}"
        )
        if round_.reply is not None:
            표시 = "선택" if round_.reply.chosen_option is not None else "직접 입력"
            대화.append(f"사용자({표시}): {round_.reply.text}")

    종료 = {
        "ready": "도우미가 목표를 확정했다",
        "limit": "되묻기 상한에 걸려 목표를 확정하지 못했다",
        "error": "오류로 중단됐다",
    }[transcript.stop_reason.value]

    return f"""## 정답 (사용자가 실제로 원한 것)

목표 문장: {scenario.reference_goal}

목표에 반드시 들어가야 하는 조각:
{슬롯}

{"이 목표는 '" + str(scenario.stop_boundary) + "'까지만 진행하는 것이 정답입니다." if scenario.stop_boundary else ""}

## 실제 대화

{chr(10).join(대화)}

## 도우미가 최종적으로 만든 목표 문장

{transcript.final_goal or "(없음 — " + 종료 + ")"}

## 채점

1. achievement — 최종 목표 문장이 정답을 담고 있는가.
   - 충족: 모든 조각이 담겼거나, 빠진 것이 안내에 지장 없는 수준이다.
   - 부분: 핵심은 맞지만 조각이 빠졌거나 뭉뚱그려졌다.
   - 실패: 의도가 다르거나, 목표를 못 만들었거나, 없는 내용이 들어갔다.
2. slot_scores — 조각마다 정확/부분/누락/틀림. evidence에는 최종 문장이나 대화에서 근거를
   짧게 인용합니다. '미상'으로 표시된 조각은 **도우미가 값을 지어내지 않고 넘겼으면 정확**이고,
   그럴듯한 값을 만들어 넣었으면 틀림입니다.
3. violations — 도우미의 질문에서 아래를 찾습니다. 없으면 빈 배열입니다.
   - 고유명_환각: 대화에 없는 사람·가게·약·물건의 고유한 이름을 도우미가 만들어 냈다.
     (행위 유형이나 범주를 예로 드는 것은 위반이 아닙니다.)
   - 불필요_질문: **정답 목표에 없는 조각**을 요구했다. 위 '목표에 반드시 들어가야 하는
     조각' 목록에 없는 정보를 물었다면 이것이다. 사용자가 답할 수 없거나 답할 필요가 없는
     것을 붙들다 대화가 끝나는 원인이라, 같은 자리에서 `질문_반복`과 함께 나기 쉽다.
     **둘 다 해당하면 둘 다 적습니다** — 반복은 증상이고 불필요한 요구가 원인이다.
   - 질문_반복: 이미 물어 답을 받은 것을 같은 방식으로 다시 물었다.
   - 한번에_두개: 한 질문에서 두 가지를 물었다.
   - 선택_강요: 답이 그 안에 있다고 전제하는 말투다("A예요 B예요?", "둘 중에 고르세요").
   - 앱_전문용어: 노인이 모를 앱 메뉴 이름·기능 이름으로 물었다.
   - 기타_없음: 선택지에 정답이 없는데 직접 답할 길을 주지 않았다.
   같은 유형이 여러 번이면 **가장 뚜렷한 것 3건까지만** 적습니다.
   round_index에는 그 질문이 나온 라운드 번호를, quote에는 문제가 된 문장의 앞부분(30자 이내)을 넣습니다."""


def judge(
    client: ClaudeJudge, scenario: Scenario, transcript: Transcript
) -> JudgeResult:
    """평가자는 문제 있는 조각만 쓰고, 나머지는 여기서 '정확'으로 채운다.

    출력 토큰이 입력의 5배로 과금되므로, 6조각짜리 시나리오에서 '정확 6줄'을 받아쓰는 것이
    곧 비용이다. 판정에 필요한 정보는 그대로 남는다 — 목록에 없으면 정확이다.
    """

    result = client.parse(
        build_prompt(scenario, transcript), JudgeResult, system=_SYSTEM
    )
    scored = {score.key for score in result.slot_scores}
    result.slot_scores = list(result.slot_scores) + [
        SlotScore(key=slot.key, verdict=SlotVerdict.CORRECT, evidence="")
        for slot in scenario.slots
        if slot.key not in scored
    ]
    return result
