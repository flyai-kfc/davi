package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RequestTracingTest {
    @Test
    fun `default logical request id is a canonical random uuid`() {
        val request = LogicalRequestTracker().next("screen-a", 1)

        assertEquals(UUID.fromString(request.logicalRequestId).toString(), request.logicalRequestId)
        assertEquals(1, request.attempt)
    }

    @Test
    fun `network retry keeps logical id and increments attempt`() {
        val ids = ArrayDeque(listOf("id-1", "id-2"))
        val tracker = LogicalRequestTracker { ids.removeFirst() }

        val first = tracker.next("screen-a", 1)
        val retry = tracker.next("screen-a", 1)

        assertEquals("id-1", first.logicalRequestId)
        assertEquals(1, first.attempt)
        assertEquals(first.logicalRequestId, retry.logicalRequestId)
        assertEquals(2, retry.attempt)
    }

    @Test
    fun `screen or session change creates a new logical request`() {
        var nextId = 0
        val tracker = LogicalRequestTracker { "id-${++nextId}" }

        val first = tracker.next("screen-a", 1)
        val changedScreen = tracker.next("screen-b", 1)
        val changedSession = tracker.next("screen-b", 2)

        assertNotEquals(first.logicalRequestId, changedScreen.logicalRequestId)
        assertNotEquals(changedScreen.logicalRequestId, changedSession.logicalRequestId)
        assertEquals(1, changedScreen.attempt)
        assertEquals(1, changedSession.attempt)
    }

    @Test
    fun `successful response closes the logical request even on the same screen`() {
        var nextId = 0
        val tracker = LogicalRequestTracker { "id-${++nextId}" }
        val first = tracker.next("screen-a", 1)

        tracker.markHttpResponse(first, 200)
        val modelRetry = tracker.next("screen-a", 1)

        assertNotEquals(first.logicalRequestId, modelRetry.logicalRequestId)
        assertEquals(1, modelRetry.attempt)
    }

    @Test
    fun `late success cannot close a newer request`() {
        var nextId = 0
        val tracker = LogicalRequestTracker { "id-${++nextId}" }
        val old = tracker.next("screen-a", 1)
        val current = tracker.next("screen-b", 1)

        tracker.markHttpResponse(old, 200)
        val retry = tracker.next("screen-b", 1)

        assertEquals(current.logicalRequestId, retry.logicalRequestId)
        assertEquals(2, retry.attempt)
    }

    @Test
    fun `only retryable http responses keep the logical request`() {
        var nextId = 0
        val tracker = LogicalRequestTracker { "id-${++nextId}" }
        val first = tracker.next("screen-a", 1)

        tracker.markHttpResponse(first, 502)
        val retryable = tracker.next("screen-a", 1)
        assertEquals(first.logicalRequestId, retryable.logicalRequestId)
        assertEquals(2, retryable.attempt)

        tracker.markHttpResponse(retryable, 401)
        val nonRetryable = tracker.next("screen-a", 1)
        assertNotEquals(retryable.logicalRequestId, nonRetryable.logicalRequestId)
        assertEquals(1, nonRetryable.attempt)
    }

    @Test
    fun `reset discards the previous logical request`() {
        var nextId = 0
        val tracker = LogicalRequestTracker { "id-${++nextId}" }
        val first = tracker.next("screen-a", 1)

        tracker.reset()
        val restarted = tracker.next("screen-a", 1)

        assertNotEquals(first.logicalRequestId, restarted.logicalRequestId)
        assertEquals(1, restarted.attempt)
    }

    @Test
    fun `telemetry formatter omits null fields and never needs raw content`() {
        val event = GuideTelemetry.event(
            "request_finished",
            "logical_request_id" to "id-1",
            "attempt" to 1,
            "server_request_id" to null,
        )

        assertTrue(event.contains("event=request_finished"))
        assertTrue(event.contains("logical_request_id=id-1"))
        assertTrue(event.contains("attempt=1"))
        assertFalse(event.contains("server_request_id"))
    }
}
