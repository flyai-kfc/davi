package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchableAppIndexTest {
    @Test
    fun `normalizes badge folder suffix compatibility and whitespace`() {
        assertEquals("메시지", normalizeLaunchableLabel("  메시지, 알림 12개  "))
        assertEquals("samsung store", normalizeLaunchableLabel("Ｓａｍｓｕｎｇ   Store, 폴더"))
        assertEquals("앱", normalizeLaunchableLabel("앱, 알림 2개, 폴더"))
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
    fun `normalized label alias matches badge suffix`() {
        val result = AppCandidateMatcher.match(
            plan(candidate(aliases = listOf("메시지"))),
            completeSnapshot(record("com.example.message", "메시지, 알림 3개", "Main")),
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
        val first = index.current()

        source.query = LaunchableAppQuery.Available(emptyList(), complete = true)
        source.notifyChanged()
        val second = index.current()

        assertEquals(1, first.records.size)
        assertEquals(emptyList<LaunchableAppRecord>(), second.records)
        assertEquals(first.revision + 1, second.revision)
    }

    @Test
    fun `query failure clears stale availability and becomes unknown`() {
        val source = FakeSource(
            LaunchableAppQuery.Available(
                listOf(record("com.example.one", "하나", "Main")),
                complete = true,
            )
        )
        val index = LaunchableAppIndex(source) {}
        index.start()
        source.query = LaunchableAppQuery.Unavailable
        source.notifyChanged()

        val snapshot = index.current()
        assertEquals(false, snapshot.complete)
        assertEquals(emptyList<LaunchableAppRecord>(), snapshot.records)
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
        LaunchableAppRecord(packageName, "$packageName/$component", label, profileId = 0)

    private class FakeSource(var query: LaunchableAppQuery) : LaunchableAppSource {
        private var callback: (() -> Unit)? = null
        override fun query(): LaunchableAppQuery = query
        override fun register(onChanged: () -> Unit) {
            callback = onChanged
        }
        override fun unregister() {
            callback = null
        }
        fun notifyChanged() = callback?.invoke()
    }
}
