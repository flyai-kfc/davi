package com.davi.helper

/**
 * 서버 오류를 **언제 사용자에게 알릴지**만 판정한다(#29).
 *
 * 세는 값은 **연속으로 실패한 요청 수이고 원인을 가리지 않는다.** 네트워크로 못 닿은 것,
 * 4xx/5xx, 계약과 다른 200 — 전부 한 번으로 센다. 사용자 입장에서 결과가 "안내를 못 받았다"로
 * 같기 때문이고, `guide_error_*` 세 문구는 이 카운터가 아니라 [GuideErrorCopy]가 가른다.
 *
 * **"서버에 닿았으면 성공"으로 읽지 말 것**(PR #189 리뷰). `ApiFailure`·`HttpFailure`는 닿긴
 * 닿았지만 안내를 못 그렸다 — 그 분기에서 [onRequestSucceeded]를 부르면 서버가 500·429를
 * 계속 돌리는 동안 카운터가 매번 0으로 돌아가 **`guide_error_server`·`guide_error_busy`가
 * 영원히 안 뜬다.** 그 두 버킷이 존재하는 이유가 바로 그 상황이다.
 *
 * [ScreenStabilityTracker.markFailed]의 `failures`를 쓰지 않는 이유가 이 클래스의 존재 이유다 —
 * 그 값의 뜻은 "이 화면을 안 보낸 것으로 되돌린다"(백오프 대상)이고 `onConfirmReject`와
 * done 불신 `RETRY`에서도 올라간다. 그 값으로 게이트를 열면 사용자가 ×아직이에요를 누른
 * 직후의 **첫** 실패에서 문구가 뜬다(PR #189 리뷰에서 실제로 걸렸다). 두 뜻을 한 값에 담지 않는다.
 *
 * 메인 스레드에서만 만진다([ScreenStabilityTracker]와 같은 계약).
 */
class ErrorNoticeGate(private val minFailures: Int = DEFAULT_MIN_FAILURES) {

    private var consecutiveFailures = 0

    /**
     * 요청 하나가 실패했다(원인 무관). @return 지금 사용자에게 알려야 하는가.
     *
     * 첫 실패에 false를 돌려주는 이유는 백오프가 2초라서다 — 순간적인 끊김에도 안내가 2초간
     * 오류 문구로 덮였다 돌아오면, 그 깜빡임이 어르신에게는 안내보다 먼저 눈에 들어온다.
     */
    fun onRequestFailed(): Boolean {
        consecutiveFailures++
        return consecutiveFailures >= minFailures
    }

    /**
     * 요청 하나가 **성공했다** — `NextStepResult.Success` 전용이다. 안내를 그릴 응답을 받은
     * 것이 조건이지 서버에 닿은 것이 조건이 아니다(위 KDoc).
     */
    fun onRequestSucceeded() {
        consecutiveFailures = 0
    }

    /** 세션이 바뀌었다. 이전 세션의 실패를 새 세션으로 끌고 가지 않는다. */
    fun reset() {
        consecutiveFailures = 0
    }

    /** 로그용. 판정에는 쓰지 않는다. */
    fun failureCount(): Int = consecutiveFailures

    companion object {
        const val DEFAULT_MIN_FAILURES = 2
    }
}
