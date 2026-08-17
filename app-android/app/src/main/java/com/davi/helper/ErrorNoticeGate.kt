package com.davi.helper

/**
 * 서버 오류를 **언제 사용자에게 알릴지**만 판정한다(#29).
 *
 * [ScreenStabilityTracker.markFailed]의 `failures`를 쓰지 않는 이유가 이 클래스의 존재 이유다 —
 * 그 값의 뜻은 "이 화면을 안 보낸 것으로 되돌린다"(백오프 대상)이고, `onConfirmReject`와
 * done 불신 `RETRY`에서도 올라간다. 표시 정책이 필요한 값은 **서버에 연속으로 못 닿은 횟수**뿐이라,
 * 그 카운터를 빌려 쓰면 사용자가 ×아직이에요를 누른 직후의 **첫** 전송 실패에서 문구가 뜬다
 * (PR #189 리뷰에서 실제로 걸렸다). 두 뜻을 한 값에 담지 않는다.
 *
 * 메인 스레드에서만 만진다([ScreenStabilityTracker]와 같은 계약).
 */
class ErrorNoticeGate(private val minFailures: Int = DEFAULT_MIN_FAILURES) {

    private var consecutiveFailures = 0

    /**
     * 전송이 실패했다. @return 지금 사용자에게 알려야 하는가.
     *
     * 첫 실패에 false를 돌려주는 이유는 백오프가 2초라서다 — 순간적인 끊김에도 안내가 2초간
     * 오류 문구로 덮였다 돌아오면, 그 깜빡임이 어르신에게는 안내보다 먼저 눈에 들어온다.
     */
    fun onTransportFailure(): Boolean {
        consecutiveFailures++
        return consecutiveFailures >= minFailures
    }

    /** 서버에 닿았다. 다음 끊김은 다시 처음부터 센다. */
    fun onReached() {
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
