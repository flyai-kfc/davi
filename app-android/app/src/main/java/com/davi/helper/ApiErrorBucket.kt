package com.davi.helper

/**
 * 서버 오류를 **사용자에게 말할 단위**로 묶은 것. [ApiErrorKind] 6종과 제한 scope가 4종이 된다.
 *
 * 묶는 기준은 원인이 아니라 **사용자가 할 일**이다(#29 문구 확정) — AUTH·SERVER·PARSE·
 * BAD_REQUEST는 원인이 다 다르지만 어르신 입장에서는 전부 "우리 쪽 문제이고 기다리면 된다"라
 * 한 문구로 묶는다. 반대로 QUOTA와 NETWORK는 갈라야 한다: 하나는 기다리면 풀리고 다른
 * 하나는 사용자가 연결을 볼 수 있다. 일일 한도는 대기 시간이 길어 짧은 QUOTA와도 가른다.
 *
 * **화면마다 다시 분기하지 않는다.** 이 정책이 안내창(`guide_error_*`)과 대화 화면
 * (`voice_*`) 두 곳에 복사돼 있으면 한쪽만 고쳐져 같은 원인이 화면마다 다른 말로 나온다 —
 * #29 문구 검토가 실제로 그 어긋남을 잡아냈다. 버킷은 여기 하나만 두고, 버킷 → 문구
 * 대응만 화면이 정한다.
 */
enum class ApiErrorBucket {
    /** 인터넷이 불안정하다. 사용자가 볼 수 있는 유일한 원인이다. */
    NETWORK,

    /** 사용량 한도. 기다리면 풀린다. */
    BUSY,

    /** 외부 공개 프로필의 24시간 사용 한도. 짧은 혼잡과 다른 행동 안내가 필요하다. */
    DAILY_LIMIT,

    /** 그 밖의 서버 문제 전부. 사용자가 할 수 있는 일은 없다. */
    SERVER,
}

fun bucketOf(kind: ApiErrorKind): ApiErrorBucket = when (kind) {
    ApiErrorKind.NETWORK -> ApiErrorBucket.NETWORK
    ApiErrorKind.QUOTA -> ApiErrorBucket.BUSY
    ApiErrorKind.AUTH,
    ApiErrorKind.SERVER,
    ApiErrorKind.PARSE,
    ApiErrorKind.BAD_REQUEST,
    -> ApiErrorBucket.SERVER
}

/** 같은 QUOTA라도 24시간 창은 수초·수분짜리 공급자/폭주 제한과 구분한다. */
fun bucketOf(error: ApiError): ApiErrorBucket = if (
    error.kind == ApiErrorKind.QUOTA && error.rateLimitScope == RateLimitScope.DAILY
) {
    ApiErrorBucket.DAILY_LIMIT
} else {
    bucketOf(error.kind)
}
