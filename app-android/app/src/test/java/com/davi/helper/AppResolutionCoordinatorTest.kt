package com.davi.helper

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(AppAvailabilityPreflight.UNCONFIRMED, outcome.availability)
        assertEquals(1, requests.size)
    }

    @Test
    fun `candidate request failures remain advisory and complete as unconfirmed`() {
        val failures = listOf(
            AppResolutionResult.ApiFailure(
                500,
                ApiError(ApiErrorKind.SERVER, "후보 서버 오류"),
                trace(),
            ),
            AppResolutionResult.HttpFailure(503, trace()),
            AppResolutionResult.NetworkFailure(IOException("offline")),
            AppResolutionResult.ParseFailure(IllegalStateException("invalid response"), trace()),
        )
        val index = LaunchableAppIndex(FakeSource(emptyList())) {}.also { it.start() }

        failures.forEach { failure ->
            val outcome = AppResolutionCoordinator(index) { failure }
                .resolve("이미 완성된 목표", 1) { true }

            assertTrue(outcome is AppResolutionCoordinator.Outcome.Completed)
            outcome as AppResolutionCoordinator.Outcome.Completed
            assertEquals(AppAvailabilityPreflight.UNCONFIRMED, outcome.availability)
        }
    }

    @Test
    fun `one local match confirms availability without feedback or goal rewrite`() {
        val source = FakeSource(
            listOf(record("com.example.delivery", "로컬 전용 label")),
        )
        val index = LaunchableAppIndex(source) {}.also { it.start() }
        val requests = mutableListOf<AppResolutionRequest>()
        val gateway = AppResolutionGateway { request ->
            requests += request
            candidateResponse()
        }

        val outcome = AppResolutionCoordinator(index, gateway).resolve("치킨 주문", 7) { true }

        assertTrue(outcome is AppResolutionCoordinator.Outcome.Completed)
        outcome as AppResolutionCoordinator.Outcome.Completed
        assertEquals(AppAvailabilityPreflight.CONFIRMED, outcome.availability)
        assertEquals(1, requests.size)
        assertEquals(null, requests.single().feedback)
        assertTrue("로컬 전용 label" !in requests.single().toString())
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
    fun `stable index without a launchable candidate is unconfirmed`() {
        val index = LaunchableAppIndex(FakeSource(emptyList())) {}.also { it.start() }

        val outcome = AppResolutionCoordinator(index) { candidateResponse() }
            .resolve("치킨 주문", 1) { true }

        assertTrue(outcome is AppResolutionCoordinator.Outcome.Completed)
        outcome as AppResolutionCoordinator.Outcome.Completed
        assertEquals(AppAvailabilityPreflight.UNCONFIRMED, outcome.availability)
    }

    @Test
    fun `any launchable candidate confirms availability regardless of other statuses`() {
        val results = listOf(
            AppCandidateResult("launchable", AppCandidateAvailability.LAUNCHABLE),
            AppCandidateResult("not-found", AppCandidateAvailability.NOT_FOUND),
            AppCandidateResult("ambiguous", AppCandidateAvailability.AMBIGUOUS),
            AppCandidateResult("unknown", AppCandidateAvailability.UNKNOWN),
        )

        assertEquals(AppAvailabilityPreflight.CONFIRMED, appAvailabilityPreflightOf(results))
    }

    @Test
    fun `candidate absence and non-launchable statuses remain unconfirmed`() {
        assertEquals(
            AppAvailabilityPreflight.UNCONFIRMED,
            appAvailabilityPreflightOf(emptyList()),
        )
        assertEquals(
            AppAvailabilityPreflight.UNCONFIRMED,
            appAvailabilityPreflightOf(
                listOf(
                    AppCandidateResult("not-found", AppCandidateAvailability.NOT_FOUND),
                    AppCandidateResult("ambiguous", AppCandidateAvailability.AMBIGUOUS),
                    AppCandidateResult("unknown", AppCandidateAvailability.UNKNOWN),
                ),
            ),
        )
    }

    @Test
    fun `repeatedly unstable index completes as unconfirmed without feedback`() {
        val source = FakeSource(
            listOf(record("com.example.delivery", "배달 앱")),
            // LaunchableAppIndex 2회 × coordinator 2회 모두 조회 중 무효화한다.
            invalidationsDuringQuery = 4,
        )
        val index = LaunchableAppIndex(source) {}.also { it.start() }
        val requests = mutableListOf<AppResolutionRequest>()
        val gateway = AppResolutionGateway { request ->
            requests += request
            candidateResponse()
        }

        val outcome = AppResolutionCoordinator(index, gateway).resolve("치킨 주문", 1) { true }

        assertTrue(outcome is AppResolutionCoordinator.Outcome.Completed)
        outcome as AppResolutionCoordinator.Outcome.Completed
        assertEquals(AppAvailabilityPreflight.UNCONFIRMED, outcome.availability)
        assertEquals(1, requests.size)
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

    @Test
    fun `바로가기로 꺼내는 앱은 LAUNCHABLE 하나이고 개인 프로필의 것이다`() {
        fun match(status: AppCandidateAvailability, record: LaunchableAppRecord?) =
            LaunchableAppMatch(AppCandidateResult("cand-1", status), record)

        val personal = record("com.example", "예시")
        val work = personal.copy(profileId = 11, isPersonalProfile = false)

        // 좁혀진 하나를 그대로 꺼낸다 — 고르는 규칙이 새로 생기지 않는다.
        assertEquals(
            personal,
            launchableAppOf(listOf(match(AppCandidateAvailability.LAUNCHABLE, personal))),
        )

        // 판정이 안 선 것에서는 앱을 꺼내지 않는다.
        for (status in listOf(
            AppCandidateAvailability.AMBIGUOUS,
            AppCandidateAvailability.NOT_FOUND,
            AppCandidateAvailability.UNKNOWN,
        )) {
            assertNull("$status 에서 앱이 나왔다", launchableAppOf(listOf(match(status, personal))))
        }

        // **직장·보안 폴더에만 있는 앱은 다비가 못 연다** — PackageManager로는 아이콘도
        // 실행 인텐트도 안 나와 행이 빈 슬롯으로 서고 눌러도 안 열린다.
        assertNull(launchableAppOf(listOf(match(AppCandidateAvailability.LAUNCHABLE, work))))
    }

    private fun record(packageName: String, label: String) =
        LaunchableAppRecord(packageName, "$packageName/.Main", label, 0, isPersonalProfile = true)

    private fun trace() = ServerResponseTrace(null, null, null)

    private class FakeSource(
        val records: List<LaunchableAppRecord>,
        var invalidationsDuringQuery: Int = 0,
    ) : LaunchableAppSource {
        private var callback: (() -> Unit)? = null
        override fun query(): LaunchableAppQuery {
            if (invalidationsDuringQuery > 0) {
                invalidationsDuringQuery--
                callback?.invoke()
            }
            return LaunchableAppQuery.Available(records, complete = true)
        }
        override fun register(onChanged: () -> Unit) {
            callback = onChanged
        }
        override fun unregister() {
            callback = null
        }
    }
}
