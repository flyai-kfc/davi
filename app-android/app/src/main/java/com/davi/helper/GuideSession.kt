package com.davi.helper

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 안내 세션 하나. 사용자가 입력한 목표는 **시작을 누른 동안만** 존재한다.
 *
 * **영속화하지 않는다.** SharedPreferences 같은 곳에 남기면, 사용자가 중지하지 않고 앱을 종료하거나
 * 기기를 재부팅했을 때 시스템이 접근성 서비스를 다시 바인딩하면서 **시작을 누른 적도 없는데 화면을
 * 읽어 서버로 보내게 된다.** 프로세스가 죽으면 세션도 끝나는 것이 맞다(SPEC §2).
 *
 * Android 타입에 의존하지 않는다. 그래야 상태 전이를 JVM 단위 테스트로 고정할 수 있다.
 * 대신 **상태를 바꾸는 호출은 메인 스레드에서** 한다(리스너가 그 스레드에서 그대로 불린다).
 * 서비스의 네트워크 스레드에서 끝날 때는 메인 핸들러로 넘긴 뒤 부른다.
 */
object GuideSession {

    /** 안내 중인 목표. null이면 세션이 없고, 그동안 앱은 화면을 읽지 않는다. */
    @Volatile
    var goal: String? = null
        private set

    val isActive: Boolean
        get() = goal != null

    /**
     * 세션이 시작·종료될 때마다 하나씩 오른다. **같은 목표로 다시 시작해도 값이 달라진다.**
     *
     * 목표 문자열만으로는 늦게 도착한 응답을 못 거른다 — 중지했다가 같은 목표로 다시 시작하면
     * 문자열이 같아 이전 세션의 응답이 그대로 통과한다(이전 화면의 bounds로 강조하거나, 그 응답이
     * `done`이면 새 세션을 끝내버린다).
     */
    private var generation: Long = 0

    /** 요청 하나가 어느 세션에 속하는지 나타내는 스냅샷. */
    data class Active(
        val generation: Long,
        val goal: String,
        val recentInstructions: List<String>,
        val recentActions: List<RecentAction>,
    )

    /**
     * 지금 활성 세션의 스냅샷. **반드시 한 번에 가져온다** — `goal`과 `generation`을 따로 읽으면
     * 그 사이에 세션이 바뀌어 이전 목표에 새 세대가 붙을 수 있고, 그러면 이 가드가 뚫린다.
     */
    @Synchronized
    fun active(): Active? =
        goal?.let {
            Active(
                generation = generation,
                goal = it,
                recentInstructions = instructions.toList(),
                recentActions = actions.toList(),
            )
        }

    /** 이 세대가 아직 현재 세션인가. 늦게 도착한 응답을 버릴지 판정한다. */
    @Synchronized
    fun isCurrent(generation: Long): Boolean = goal != null && this.generation == generation

    fun interface Listener {
        /** 세션이 시작되거나(목표) 끝났다(null). */
        fun onSessionChanged(goal: String?)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * 이 세션에서 사용자에게 보여준 안내들. 서버 프롬프트에 실려 **같은 안내 반복을 막는다**
     * (HANDOFF §3-10: 매 스텝 무상태면 모델이 직전 안내를 모른다).
     *
     * 세션이 끝나거나 목표가 바뀌면 비운다 — 다음 목표에 이전 안내가 새면 안 된다.
     * 서비스의 네트워크 스레드가 읽고 메인 스레드가 쓰므로 접근을 잠금으로 막는다.
     */
    private val instructions = ArrayDeque<String>()

    /**
     * 서버 반복 가드에 보내는 구조화 행동 이력.
     *
     * 화면 서명은 서버가 label 없이 계산해 응답에 실은 값을 그대로 보관한다. 따라서 앱이 화면
     * 원문을 해시하거나 별도 식별자를 만들지 않는다. 세션 generation이 바뀌면 함께 폐기한다.
     */
    private val actions = ArrayDeque<RecentAction>()

    /** 빈 목표로는 시작하지 않는다. 목표는 서버 프롬프트의 첫 줄이라 공백이면 안내가 성립하지 않는다. */
    fun start(goal: String) {
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return
        update(trimmed)
    }

    /**
     * 사용자에게 실제로 보여준 안내만 기록한다. 중복도 그대로 남긴다 — 반복 자체가 신호다.
     *
     * 요청 시점의 세대를 함께 받아, 늦게 도착한 이전 세션의 안내가 새 세션 이력에 섞이지 않게 한다.
     */
    @Synchronized
    fun recordInstruction(generation: Long, instruction: String) {
        if (!isCurrent(generation)) return
        val trimmed = instruction.trim()
        if (trimmed.isEmpty()) return
        instructions.addLast(trimmed)
        while (instructions.size > MAX_HISTORY) {
            instructions.removeFirst()
        }
    }

    /**
     * 사용자에게 실제로 표시한 행동만 기록한다.
     *
     * 낡은 서버는 화면 서명을 주지 않으므로 기록하지 않는다. 이전 세대의 늦은 응답도
     * [isCurrent] 검사에서 버려 새 세션의 반복 상태에 섞이지 않는다.
     */
    @Synchronized
    fun recordAction(generation: Long, screenSignature: String?, action: GuideAction) {
        if (!isCurrent(generation) || screenSignature == null) return
        actions.addLast(
            RecentAction(
                screenSignature = screenSignature,
                actionType = action.actionType,
                direction = action.direction,
                targetNodeId = action.targetNodeId,
            ),
        )
        while (actions.size > MAX_HISTORY) {
            actions.removeFirst()
        }
    }

    /** 사용자가 중지했거나 서버가 `done`을 줬다. 둘의 상태 결과는 같다. */
    fun stop() = update(null)

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    private fun update(next: String?) {
        // 리스너는 잠금 밖에서 부른다. 안에서 부르면 리스너가 다시 세션을 건드릴 때 엉킨다.
        synchronized(this) {
            if (goal == next) return
            goal = next
            // 중지(null)와 시작이 각각 한 번씩 올린다. 그래서 중지 후 같은 목표로 다시 시작해도
            // 세대가 달라지고, 그 사이에 떠 있던 응답이 새 세션에 섞이지 않는다.
            generation++
            instructions.clear()
            actions.clear()
        }
        listeners.forEach { it.onSessionChanged(next) }
    }

    private const val MAX_HISTORY = 10
}
