package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResolutionCoordinatorTest {
    @Test
    fun `empty candidate result completes without feedback round trip`() {
        val index = LaunchableAppIndex(FakeSource(emptyList())) {}.also { it.start() }
        val requests = mutableListOf<AppResolutionRequest>()
        val gateway = AppResolutionGateway { request ->
            requests += request
            AppResolutionResult.Success(
                AppResolutionState(AppResolutionStatus.NEEDS_APP_NAME, "앱 이름을 말씀해 주세요"),
                trace(),
            )
        }

        val outcome = AppResolutionCoordinator(index, gateway).resolve("목표", 1) { true }

        assertTrue(outcome is AppResolutionCoordinator.Outcome.Completed)
        outcome as AppResolutionCoordinator.Outcome.Completed
        assertEquals(IntentDialogueStatus.NEEDS_CLARIFICATION, outcome.state.status)
        assertEquals(1, requests.size)
    }

    @Test
    fun `one local match returns ready goal without sending local app data`() {
        val source = FakeSource(
            listOf(record("com.example.delivery", "로컬 전용 label")),
        )
        val index = LaunchableAppIndex(source) {}.also { it.start() }
        val requests = mutableListOf<AppResolutionRequest>()
        val gateway = AppResolutionGateway { request ->
            requests += request
            if (request.feedback == null) candidateResponse()
            else {
                assertEquals(
                    listOf(AppCandidateAvailability.LAUNCHABLE),
                    request.feedback.candidateResults.map { it.status },
                )
                AppResolutionResult.Success(
                    AppResolutionState(
                        AppResolutionStatus.RESOLVED,
                        "목표 정리",
                        selectedCandidateId = "candidate-1",
                        goal = "치킨 주문 (사용할 앱: 배달 앱)",
                    ),
                    trace(),
                )
            }
        }

        val outcome = AppResolutionCoordinator(index, gateway).resolve("치킨 주문", 7) { true }

        assertTrue(outcome is AppResolutionCoordinator.Outcome.Completed)
        outcome as AppResolutionCoordinator.Outcome.Completed
        assertEquals(IntentDialogueStatus.READY, outcome.state.status)
        assertEquals(2, requests.size)
        val feedbackJson = requests[1].toString()
        assertTrue("로컬 전용 label" !in feedbackJson)
        assertTrue("com.example.delivery" in feedbackJson) // 서버가 먼저 제안한 package만 echo
    }

    @Test
    fun `late candidate response from previous dialogue generation is dropped`() {
        var current = true
        val index = LaunchableAppIndex(FakeSource(emptyList())) {}.also { it.start() }
        val gateway = AppResolutionGateway {
            current = false
            candidateResponse()
        }

        val outcome = AppResolutionCoordinator(index, gateway).resolve("목표", 3) { current }

        assertEquals(AppResolutionCoordinator.Outcome.Dropped, outcome)
    }

    @Test
    fun `same goal restart drops the previous sessions late candidate response`() {
        val session = VoiceDialogueSession { "ephemeral-session" }
        session.start()
        val previous = requireNotNull(session.nextRequest("카메라 열기"))
        val index = LaunchableAppIndex(FakeSource(emptyList())) {}.also { it.start() }
        val gateway = AppResolutionGateway {
            session.cancel()
            session.start()
            requireNotNull(session.nextRequest("카메라 열기"))
            candidateResponse()
        }

        val outcome = AppResolutionCoordinator(index, gateway).resolve(
            "카메라 열기",
            previous.generation,
            isCurrent = { session.isPending(previous) },
        )

        assertEquals(AppResolutionCoordinator.Outcome.Dropped, outcome)
    }

    @Test
    fun `package callback invalidates stale result and rematches before applying`() {
        val source = FakeSource(listOf(record("com.example.delivery", "배달 앱")))
        val index = LaunchableAppIndex(source) {}.also { it.start() }
        var feedbackCalls = 0
        val gateway = AppResolutionGateway { request ->
            if (request.feedback == null) return@AppResolutionGateway candidateResponse()
            feedbackCalls++
            if (feedbackCalls == 1) {
                source.records = emptyList()
                source.notifyChanged()
            }
            val result = request.feedback.candidateResults.single()
            val state = if (result.status == AppCandidateAvailability.LAUNCHABLE) {
                AppResolutionState(
                    AppResolutionStatus.RESOLVED,
                    "목표 정리",
                    selectedCandidateId = "candidate-1",
                    goal = "치킨 주문 (사용할 앱: 배달 앱)",
                )
            } else {
                AppResolutionState(AppResolutionStatus.NEEDS_APP_NAME, "앱 이름을 말씀해 주세요")
            }
            AppResolutionResult.Success(state, trace())
        }

        val outcome = AppResolutionCoordinator(index, gateway).resolve("치킨 주문", 1) { true }

        assertEquals(2, feedbackCalls)
        assertTrue(outcome is AppResolutionCoordinator.Outcome.Completed)
        outcome as AppResolutionCoordinator.Outcome.Completed
        assertEquals(IntentDialogueStatus.NEEDS_CLARIFICATION, outcome.state.status)
    }

    private fun candidateResponse() = AppResolutionResult.Success(
        AppResolutionState(
            AppResolutionStatus.CANDIDATES,
            "후보 확인",
            plan = AppCandidatePlan(
                "food_delivery",
                listOf(
                    AppCandidate(
                        "candidate-1",
                        "배달 앱",
                        listOf("com.example.delivery"),
                        listOf("배달 앱"),
                        0.9,
                    )
                ),
            ),
        ),
        trace(),
    )

    private fun record(packageName: String, label: String) =
        LaunchableAppRecord(packageName, "$packageName/.Main", label, 0)

    private fun trace() = ServerResponseTrace(null, null, null)

    private class FakeSource(var records: List<LaunchableAppRecord>) : LaunchableAppSource {
        private var callback: (() -> Unit)? = null
        override fun query() = LaunchableAppQuery.Available(records, complete = true)
        override fun register(onChanged: () -> Unit) {
            callback = onChanged
        }
        override fun unregister() {
            callback = null
        }
        fun notifyChanged() = callback?.invoke()
    }
}
