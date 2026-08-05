package com.davi.helper

import java.util.UUID

/** JSON 본문을 바꾸지 않고 HTTP 전송만 연결하는 #81 계약. */
object RequestTracingContract {
    const val LOGICAL_REQUEST_ID_HEADER = "X-Davi-Logical-Request-ID"
    const val ATTEMPT_HEADER = "X-Davi-Attempt"
    const val REQUEST_ID_HEADER = "X-Request-ID"
}

data class LogicalRequestAttempt(
    val logicalRequestId: String,
    val attempt: Int,
)

data class ServerResponseTrace(
    val requestId: String?,
    val logicalRequestId: String?,
    val attempt: Int?,
)

/**
 * 한 화면에서 유효한 안내 하나를 얻는 논리 요청의 수명주기.
 *
 * network 실패와 retryable 429/502는 현재 ID를 남겨 다음 전송의 attempt만 올린다. 그 밖의 HTTP
 * 응답은 [markHttpResponse]로 닫아 같은 화면의 다음 판단도 새 ID가 생긴다. 화면 내용과 기기 ID는
 * UUID에 들어가지 않으며 프로세스 밖에 저장하지 않는다.
 */
class LogicalRequestTracker(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Active(
        val signature: String,
        val generation: Long,
        val id: String,
        var attempt: Int = 0,
    )

    private var active: Active? = null

    @Synchronized
    fun next(signature: String, generation: Long): LogicalRequestAttempt {
        val current = active
        val selected = if (
            current == null ||
            current.signature != signature ||
            current.generation != generation
        ) {
            Active(signature, generation, idFactory()).also { active = it }
        } else {
            current
        }
        check(selected.attempt < Int.MAX_VALUE) { "logical request attempt overflow" }
        selected.attempt++
        return LogicalRequestAttempt(selected.id, selected.attempt)
    }

    @Synchronized
    fun markHttpResponse(request: LogicalRequestAttempt, statusCode: Int) {
        if (statusCode !in RETRYABLE_HTTP_STATUSES && active?.id == request.logicalRequestId) {
            active = null
        }
    }

    @Synchronized
    fun reset() {
        active = null
    }

    private companion object {
        val RETRYABLE_HTTP_STATUSES = setOf(429, 502)
    }
}
