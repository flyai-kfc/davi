package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class InferenceHttpClientTest {
    @Test
    fun `추론 호출은 서버 전체 deadline보다 긴 공용 call timeout을 쓴다`() {
        val client = InferenceHttpClient.create()

        assertEquals(5_000, client.connectTimeoutMillis)
        assertEquals(45_000, client.readTimeoutMillis)
        assertEquals(45_000, client.writeTimeoutMillis)
        assertEquals(45_000, client.callTimeoutMillis)
    }
}
