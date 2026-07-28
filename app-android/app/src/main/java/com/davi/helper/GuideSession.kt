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

    /** 빈 목표로는 시작하지 않는다. 목표는 서버 프롬프트의 첫 줄이라 공백이면 안내가 성립하지 않는다. */
    fun start(goal: String) {
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return
        update(trimmed)
    }

    /** 사용자에게 실제로 보여준 안내만 기록한다. 중복도 그대로 남긴다 — 반복 자체가 신호다. */
    @Synchronized
    fun recordInstruction(instruction: String) {
        val trimmed = instruction.trim()
        if (trimmed.isEmpty()) return
        instructions.addLast(trimmed)
        while (instructions.size > MAX_HISTORY) {
            instructions.removeFirst()
        }
    }

    /** 서버로 보낼 이력 사본. 오래된 것이 앞에 온다. */
    @Synchronized
    fun recentInstructions(): List<String> = instructions.toList()

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
            instructions.clear()
        }
        listeners.forEach { it.onSessionChanged(next) }
    }

    private const val MAX_HISTORY = 10
}
