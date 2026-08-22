package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchableAppIndexTest {
    @Test
    fun `normalizes manifest label compatibility and whitespace`() {
        assertEquals("메시지", normalizeLaunchableLabel("  메시지  "))
        assertEquals("samsung store", normalizeLaunchableLabel("Ｓａｍｓｕｎｇ   Store"))
    }

    @Test
    fun `package exact match wins before aliases`() {
        val snapshot = completeSnapshot(
            record("com.example.exact", "정확한 앱", "ExactActivity"),
            record("com.example.alias", "후보 별칭", "AliasActivity"),
        )
        val result = AppCandidateMatcher.match(
            plan(candidate(packages = listOf("com.example.exact"), aliases = listOf("후보 별칭"))),
            snapshot,
        )

        assertEquals(AppCandidateAvailability.LAUNCHABLE, result.single().status)
    }

    @Test
    fun `normalized manifest label alias matches`() {
        val result = AppCandidateMatcher.match(
            plan(candidate(aliases = listOf("메시지"))),
            completeSnapshot(record("com.example.message", "메시지", "Main")),
        )
        assertEquals(AppCandidateAvailability.LAUNCHABLE, result.single().status)
    }

    @Test
    fun `multiple launcher activities in one package are launchable`() {
        val result = AppCandidateMatcher.match(
            plan(candidate(packages = listOf("com.example.message"))),
            completeSnapshot(
                record("com.example.message", "메시지", "Main"),
                record("com.example.message", "메시지", "Alias"),
            ),
        )
        assertEquals(AppCandidateAvailability.LAUNCHABLE, result.single().status)
    }

    @Test
    fun `same label across launch targets is ambiguous`() {
        val result = AppCandidateMatcher.match(
            plan(candidate(aliases = listOf("도우미"))),
            completeSnapshot(
                record("com.example.one", "도우미", "Main"),
                record("com.example.two", "도우미", "Main"),
            ),
        )
        assertEquals(AppCandidateAvailability.AMBIGUOUS, result.single().status)
    }

    @Test
    fun `partial profile query never turns no match into not found`() {
        val result = AppCandidateMatcher.match(
            plan(candidate(packages = listOf("com.example.hidden"))),
            LaunchableAppSnapshot(emptyList(), complete = false, revision = 1),
        )
        assertEquals(AppCandidateAvailability.UNKNOWN, result.single().status)
    }

    @Test
    fun `callbacks refresh revision and remove stale records`() {
        val source = FakeSource(
            LaunchableAppQuery.Available(
                listOf(record("com.example.one", "하나", "Main")),
                complete = true,
            )
        )
        val index = LaunchableAppIndex(source) {}
        index.start()
        assertEquals(0, source.queryCount)
        val first = index.current()

        source.query = LaunchableAppQuery.Available(emptyList(), complete = true)
        source.notifyChanged()
        assertEquals(1, source.queryCount)
        val second = index.current()

        assertEquals(1, first.records.size)
        assertEquals(emptyList<LaunchableAppRecord>(), second.records)
        assertEquals(first.revision + 1, second.revision)
        assertEquals(2, source.queryCount)
    }

    @Test
    fun `transient query failure retries and recovers in the same turn and revision`() {
        val recoveredQuery = LaunchableAppQuery.Available(
            listOf(record("com.example.recovered", "복구", "Main")),
            complete = true,
        )
        val source = FakeSource(LaunchableAppQuery.Unavailable).apply {
            queuedQueries += LaunchableAppQuery.Unavailable
            queuedQueries += recoveredQuery
        }
        val refreshed = mutableListOf<LaunchableAppSnapshot>()
        val index = LaunchableAppIndex(source, refreshed::add)
        index.start()
        val recovered = index.current()

        assertTrue(recovered.complete)
        assertEquals("com.example.recovered", recovered.records.single().packageName)
        assertEquals(2, source.queryCount)
        assertEquals(listOf(false, true), refreshed.map { it.complete })
        assertEquals(refreshed.first().revision, refreshed.last().revision)
        assertEquals(refreshed.last().revision, recovered.revision)
    }

    @Test
    fun `persistent incomplete query is bounded per turn and remains dirty for the next turn`() {
        val source = FakeSource(LaunchableAppQuery.Unavailable)
        val index = LaunchableAppIndex(source) {}
        index.start()

        val first = index.current()
        val second = index.current()

        assertFalse(first.complete)
        assertFalse(second.complete)
        assertEquals(first.revision, second.revision)
        assertEquals(4, source.queryCount)
    }

    @Test
    fun `repeated invalidation returns an unstable snapshot until refresh succeeds`() {
        val source = FakeSource(
            LaunchableAppQuery.Available(
                listOf(record("com.example.one", "하나", "Main")),
                complete = true,
            )
        )
        val index = LaunchableAppIndex(source) {}
        index.start()
        source.invalidationsDuringQuery = 2

        val unstable = index.current()
        val refreshed = index.current()

        assertFalse(unstable.isStable)
        assertTrue(refreshed.isStable)
        assertEquals(unstable.revision, refreshed.revision)
        assertEquals(3, source.queryCount)
    }

    private fun plan(vararg candidates: AppCandidate) =
        AppCandidatePlan("test", candidates.toList())

    private fun candidate(
        packages: List<String> = emptyList(),
        aliases: List<String> = emptyList(),
    ) = AppCandidate("candidate-1", "후보", packages, aliases, 1.0)

    private fun completeSnapshot(vararg records: LaunchableAppRecord) =
        LaunchableAppSnapshot(records.toList(), complete = true, revision = 1)

    private fun record(packageName: String, label: String, component: String) =
        LaunchableAppRecord(
            packageName,
            "$packageName/$component",
            label,
            profileId = 0,
            isPersonalProfile = true,
        )

    private class FakeSource(var query: LaunchableAppQuery) : LaunchableAppSource {
        private var callback: (() -> Unit)? = null
        val queuedQueries = mutableListOf<LaunchableAppQuery>()
        var queryCount = 0
        var invalidationsDuringQuery = 0
        override fun query(): LaunchableAppQuery {
            queryCount++
            if (invalidationsDuringQuery > 0) {
                invalidationsDuringQuery--
                callback?.invoke()
            }
            return if (queuedQueries.isEmpty()) query else queuedQueries.removeAt(0)
        }
        override fun register(onChanged: () -> Unit) {
            callback = onChanged
        }
        override fun unregister() {
            callback = null
        }
        fun notifyChanged() = callback?.invoke()
    }
}
