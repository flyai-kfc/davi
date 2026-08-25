package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpectedTargetStateTest {
    private val expected = ExpectedTargetState(ObservableState.CHECKED, true)
    private val target = UiElement(nodeId = "n1", label = "기능", checkState = CheckState.UNCHECKED)

    private fun action(type: ActionType) = GuideAction(
        expectedResult = "상태가 바뀐다",
        evidence = "'기능'이 보입니다",
        actionType = type,
        targetNodeId = "n1",
        expectedTargetState = expected,
        instruction = "기능을 눌러 주세요.",
    )

    @Test
    fun `tap 기대 상태는 실제로 찾은 대상과 함께 기록한다`() {
        assertEquals(expected, action(ActionType.TAP).expectedTargetStateFor(target))
    }

    @Test
    fun `대상을 못 찾으면 기대 상태도 비운다`() {
        assertNull(action(ActionType.TAP).expectedTargetStateFor(null))
    }

    @Test
    fun `tap이 아닌 행동에는 기대 상태를 기록하지 않는다`() {
        assertNull(action(ActionType.SCROLL).expectedTargetStateFor(target))
    }
}
