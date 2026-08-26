package com.davi.helper

/**
 * 진행도 칩 줄이 **자리를 차지하는가**를 판정한다(#452). Android 타입을 쓰지 않아 JVM 테스트로 고정한다.
 *
 * 칩은 카드 위 열의 **흐름 자식**이다. 그래서 「보이는가」와 「자리를 먹는가」가 따로다 —
 * 이 둘을 하나로 묶어 GONE으로 걷으면 아래가 통째로 63dp 올라가고, 카드 상단선에 걸린 ✕가
 * 따라 올라가 **상태바에 16dp 잘린다**(2026-08-25 실측: 질문 1개짜리 화면 전부, 배율 무관).
 *
 * **[RESERVED]가 이 파일이 있는 이유다.** 예전에는 칩이 걷힌 63dp를 배지 위 여백을 −19/44dp로
 * 갈라 상쇄했는데, 그 상쇄는 **배지가 서는 화면에서만** 성립한다 — #443이 구체화에서 배지를
 * 빼자 갈 곳을 잃었다. 자리를 비워 두면 상쇄할 것 자체가 없고, ✕ 크기·칩 높이·인셋 중
 * 무엇이 바뀌어도 다시 뽑을 값이 없다.
 */
enum class StepChipSlot {
    /** 칩이 서고 자리도 먹는다 — 질문이 둘 이상. */
    SHOWN,

    /** 칩은 안 서지만 **자리는 그대로 먹는다** — 구체화 흐름 안의 1문항. */
    RESERVED,

    /** 칩도 자리도 없다 — 구체화 흐름 밖. */
    GONE,
}

/**
 * **[ConversationRenderModel.inClarificationFlow]를 본다 — clarificationVisible이 아니다.**
 * 그쪽은 「직접 말하기」로 넘어간 청취에서 꺼져서, 그 화면만 칩 자리를 잃고 카드가 뛴다
 * (2026-08-23에 배지 여백으로 같은 함정을 겪었다).
 *
 * 「질문 1/1」은 진행도로서 아무 말도 하지 않으므로 **서지는 않는다**(2026-08-23 결정) —
 * 자리를 남기는 것은 그 결정을 뒤집는 게 아니라, 그 결정이 배치를 흔들지 않게 하는 것이다.
 */
fun stepChipSlotOf(model: ConversationRenderModel): StepChipSlot = when {
    model.stepChipVisible -> StepChipSlot.SHOWN
    model.inClarificationFlow -> StepChipSlot.RESERVED
    else -> StepChipSlot.GONE
}
