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

    /**
     * 이 세션에서 **연속으로** 믿지 않고 넘긴 `done` 수. done이 아닌 안내가 실제로 표시되면
     * 0으로 돌아간다 — 누적으로 세면 긴 세션에서 정상 진행 중에도 상한 알림이 뜬다.
     *
     * 보통은 미검증 done이지만, 사용자가 "아직요"로 부정한 화면에서는 검증된 done도 여기서
     * 센다([onDone]) — 근거를 잃은 완료 신호라는 점이 같고, 세는 목적(재질의 총량 제한)도 같다.
     */
    private var unverifiedDones = 0

    /**
     * 사용자의 확인 응답을 기다리는 중인가(#139). 그동안 서비스는 화면을 계속 보되 서버에
     * 묻지 않는다. 샘플 루프가 매 폴링 읽으므로 volatile로 둔다.
     */
    @Volatile
    var awaitingConfirm: Boolean = false
        private set

    /** 확인 화면을 띄운 화면의 서명. ×아직요를 받으면 이 값이 부정 이력이 된다. */
    private var pendingConfirmSignature: String? = null

    /**
     * 사용자가 "아직요"로 완료를 부정한 화면의 서명. 그 화면에서 다시 오는 done은 확인 화면을
     * 띄우지 않는다 — 안 그러면 같은 화면이 done을 계속 주는 동안 확인 화면이 무한 반복된다.
     *
     * **유효기간이 있다.** 다른 화면의 done이 오거나 **안내가 실제로 나가면**(= 사용자가
     * 진행했다) 지운다. 후자가 없으면 완료 신호가 드문 실제 세션에서 이력이 끝까지 남는다
     * ([recordInstruction], PR #153 리뷰 P2).
     */
    private var rejectedSignature: String? = null

    /** `done` 응답 하나를 세션이 어떻게 처리할지. [onDone]이 돌려준다. */
    enum class DoneOutcome {
        /** 검증된 완료. 확신 있는 문구로 사용자에게 끝났는지 묻는다(GD-3). */
        CONFIRM_CONFIDENT,

        /** 미검증 완료가 [MAX_UNVERIFIED_DONE]에 닿았다. 확신 없는 문구로 같은 화면을 띄운다. */
        CONFIRM_UNSURE,

        /** 미검증 완료. 세션을 두고 같은 화면을 다시 물어본다. */
        RETRY,

        /**
         * 사용자가 이미 "아직요"라고 답한 화면에서 완료 신호가 상한까지 반복됐다. 확인 화면
         * 대신 문구만 띄운다 — 같은 질문을 다시 하지 않으면서 재질의도 멈춘다.
         */
        NOTICE_ONLY,
    }

    /**
     * 서버의 `done` 응답 하나를 판정한다. **여기서 세션이 끝나지는 않는다**(#139) — 검증된
     * 완료도 확인 화면을 거치고, 종료는 사용자의 "됐어요"([confirmAccepted])만이 만든다.
     * 성공 경로가 오버레이만 조용히 사라지던 무표시 종료를 없애려는 것이다.
     *
     * `verified=false`인 done은 완료 검증(#54)이 화면 근거를 못 찾은 것이라 종료 신호로 믿지
     * 않는다(#55). 대신 몇 번 다시 물었는지 세고, 상한부터는 확인 화면으로 넘긴다.
     * **상한에서도 done으로 끝내지 않는다** — 막힘을 성공으로 오인시킨다(server/CLAUDE.md C11이
     * fallback에서 done을 배제한 것과 같은 근거).
     *
     * [screenSignature]는 그 done이 온 화면이다. 사용자가 이미 부정한 화면인지 이 값으로 가린다.
     *
     * 이전 세대의 늦은 done은 여기서도 버린다(null).
     */
    @Synchronized
    fun onDone(generation: Long, verified: Boolean, screenSignature: String): DoneOutcome? {
        if (!isCurrent(generation)) return null

        // 사용자가 부정한 그 화면인가. 다른 화면이면 진행했다는 뜻이라 기억을 버린다.
        val rejected = screenSignature == rejectedSignature
        if (!rejected) rejectedSignature = null

        if (verified && !rejected) {
            awaitConfirm(screenSignature)
            return DoneOutcome.CONFIRM_CONFIDENT
        }

        // 부정된 화면의 done은 검증 여부와 무관하게 여기서 센다. 사용자가 아니라고 한 이상
        // 그 화면의 완료 신호는 근거가 없고, 세는 이유도 재질의 총량을 묶는 것으로 같다.
        unverifiedDones++
        if (unverifiedDones < MAX_UNVERIFIED_DONE) return DoneOutcome.RETRY
        if (rejected) return DoneOutcome.NOTICE_ONLY
        awaitConfirm(screenSignature)
        return DoneOutcome.CONFIRM_UNSURE
    }

    private fun awaitConfirm(screenSignature: String) {
        awaitingConfirm = true
        pendingConfirmSignature = screenSignature
    }

    /** 사용자가 "됐어요"를 눌렀다. 세션을 끝내는 정상 경로다. 앱을 앞으로 가져오지 않는다. */
    fun confirmAccepted() {
        if (!awaitingConfirm) return
        // stop() -> update()가 확인 상태까지 함께 비운다. 리스너를 부르므로 잠금 밖에서.
        stop()
    }

    /**
     * 사용자가 "아직요"를 눌렀다. 세션은 그대로 두고 안내를 잇는다.
     *
     * 그 화면을 부정 이력에 남겨 확인 화면이 곧바로 다시 뜨지 않게 하고, 미검증 카운터는
     * 0으로 돌린다 — 재질의 예산을 이 시점부터 새로 준다.
     */
    @Synchronized
    fun confirmRejected() {
        if (!awaitingConfirm) return
        awaitingConfirm = false
        rejectedSignature = pendingConfirmSignature
        pendingConfirmSignature = null
        unverifiedDones = 0
    }

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
        // done이 아닌 안내가 실제로 나갔다 — 미검증 done의 "연속"이 여기서 끊긴다.
        unverifiedDones = 0
        // 같은 근거로 부정 이력의 유효기간도 여기서 끝난다. ×아직요는 "지금 이 순간 아직 안
        // 됐다"는 답인데, 이력을 done으로만 지우면 완료 신호가 드문 실제 세션에서 세션이 끝날
        // 때까지 남는다 — 사용자가 여러 단계를 거쳐 그 화면에서 실제로 목표를 달성해도 성공
        // 확인 대신 "확인하지 못했어요"가 뜬다(PR #153 리뷰 P2).
        // 무한 반복 가드는 그대로 산다: 그 경로에서는 done만 반복되고 안내가 나가지 않는다.
        rejectedSignature = null
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
     * 이전 세대의 늦은 응답은 [isCurrent] 검사에서 버려 새 세션의 반복 상태에 섞이지 않는다.
     */
    @Synchronized
    fun recordAction(generation: Long, screenSignature: String, action: GuideAction) {
        if (!isCurrent(generation)) return
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

    /**
     * 세션을 끝낸다. 사용자가 중지했거나(칩·앱의 중지 버튼), 확인 화면에서 "됐어요"를 눌렀거나,
     * 권한이 사라져 진행할 수 없을 때. **서버의 `done`은 더 이상 여기로 오지 않는다**(#139).
     */
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
            unverifiedDones = 0
            // 확인 대기와 부정 이력도 세션에 매인다. 남기면 다음 세션의 첫 done이 이전 세션에서
            // 부정당한 화면이라는 이유로 확인 화면을 건너뛴다.
            awaitingConfirm = false
            pendingConfirmSignature = null
            rejectedSignature = null
        }
        listeners.forEach { it.onSessionChanged(next) }
    }

    private const val MAX_HISTORY = 10

    /**
     * 미검증 done을 이만큼 받으면 다시 묻지 않고 사용자에게 알린다. 재시도 하나가 곧 모델 호출
     * 하나이고(무료 티어 15 RPM), 같은 화면에 같은 요청이라 답이 바뀔 여지도 작아 크게 둘 수
     * 없다. 다른 시간 상수처럼 첫 추정치다 — 실사용 로그로 다시 정한다.
     */
    const val MAX_UNVERIFIED_DONE = 3
}
