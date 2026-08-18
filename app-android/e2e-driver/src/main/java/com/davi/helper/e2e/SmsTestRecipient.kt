package com.davi.helper.e2e

/**
 * 문자 시나리오가 쓸 **전용 테스트 수신자**. 값은 이 타입 밖으로 문자열로 새지 않는다.
 *
 * 왜 타입으로 감싸나(#134):
 * - 수신자 번호를 `String`으로 들고 다니면 로그·상세 보고서·예외 메시지 어디로든 흘러간다.
 *   실수는 "적는 걸 깜빡"이 아니라 "그냥 문자열이라 자동으로 찍힘" 쪽에서 난다.
 * - 그래서 [toString]이 절대 원문을 돌려주지 않는다. 원문을 얻는 경로는 [valueForInput] 하나뿐이고,
 *   그 자리는 실제 입력 주입 지점 한 곳이다.
 *
 * 왜 기본값이 없나:
 * - 기본값을 두면 아무나 아무 기기에서 시나리오를 돌릴 수 있게 된다. 설정하지 않으면 **실행이
 *   시작되지 않는 쪽**이 맞다. [parse]는 실패를 예외로 돌려주고 호출부가 시나리오를 세운다.
 *
 * 이 타입이 보증하지 **못하는** 것: 설정된 번호가 정말 시험자의 회선인지는 코드가 알 수 없다.
 * 기계적으로 확인 가능한 것(길이·문자·긴급번호·단축번호)만 막고, 나머지는 문서와 운영 규칙이 맡는다.
 */
@JvmInline
value class SmsTestRecipient private constructor(private val digits: String) {
    /**
     * 실제 입력 주입 지점에서만 부른다. 로그·보고서·예외 메시지에 넣지 않는다.
     *
     * 이름을 `value`가 아니라 `valueForInput`으로 둔 이유는, 무심코 문자열이 필요할 때 손이
     * 먼저 가지 않게 하기 위해서다.
     */
    fun valueForInput(): String = digits

    /** 마지막 두 자리만 남긴 비식별 표기. 서로 다른 회선을 구분해야 할 때만 쓴다. */
    fun redactedHint(): String = "[redacted:••${digits.takeLast(2)}]"

    /** 로그에 그대로 찍혀도 원문이 새지 않는다. */
    override fun toString(): String = REDACTED

    companion object {
        const val REDACTED = "SmsTestRecipient[redacted]"

        /** 실행기가 넘겨줄 instrumentation 인자 이름. */
        const val ARGUMENT = "davi.smsTestRecipient"

        /**
         * 앱 `PrivacyMasker`가 **실제로 마스킹하는** 국내 휴대폰 형식(#134 안전 경계).
         *
         * 이 형식으로 좁히는 이유는 자릿수 검증이 아니다. 수신자를 입력하는 순간 그 번호는
         * 이후 모든 화면 label에 남고, 앱 1차 마스킹이 그 형식을 못 알아보면 **원문 그대로
         * 서버를 거쳐 Gemini까지 나간다.** 상세 보고서를 꺼도(`handlesPersonalData`) 이 경로는
         * 막히지 않는다. 마스킹되는 형식만 허용하는 것이 유일하게 확실한 방법이다.
         *
         * `PrivacyMasker.kt`의 전화번호 정규식과 **같은 뜻을 유지한다.** 한쪽을 바꾸면 양쪽을
         * 함께 본다. 더 넓은 형식(`+82`·국제번호 등)은 앱 마스킹 계약부터 별도 검토한다.
         *
         * 손으로 시험할 때 수신자가 우연히 `000`이라 사고가 안 났던 적이 있는데, 그런 값은
         * **운이 좋았던 것**이지 안전한 설정이 아니다. 여기서 걸린다.
         */
        private val KOREAN_MOBILE = Regex("^01[016789]\\d{7,8}$")

        /**
         * 어떤 경우에도 수신자가 될 수 없는 번호.
         *
         * 자릿수 하한만으로도 대부분 걸리지만, 긴급 회선은 "왜 막았는지"가 코드에 이름으로
         * 남아야 하는 항목이라 따로 둔다.
         */
        val FORBIDDEN: Set<String> = setOf(
            "112", "119", "911", "999", "110", "118", "113", "1339", "182",
        )

        // 허용 문자만 먼저 거른다. 실제 형식은 [KOREAN_MOBILE]이 판정한다.
        // `+`는 받지 않는다 — 국제 표기는 앱 1차 마스킹이 가리지 못한다.
        private val ALLOWED_CHARS = Regex("^[0-9\\-() ]+$")

        /**
         * 설정 문자열을 검증해 수신자를 만든다.
         *
         * 실패 메시지에는 **입력값을 넣지 않는다.** 실패 로그가 곧 번호 유출이 되면 안 된다.
         */
        fun parse(raw: String?): SmsTestRecipient {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) {
                throw IllegalArgumentException(
                    "문자 시나리오에는 전용 테스트 수신자가 필요하다. " +
                        "-e $ARGUMENT <시험자가 통제하는 회선>으로 넘긴다. 기본값은 없다.",
                )
            }
            if (!ALLOWED_CHARS.matches(trimmed)) {
                throw IllegalArgumentException("수신자 형식이 올바르지 않다(숫자와 -, 공백, 괄호만).")
            }
            val digits = trimmed.filter(Char::isDigit)
            if (digits in FORBIDDEN) {
                throw IllegalArgumentException("긴급·특수 번호는 수신자가 될 수 없다.")
            }
            if (!KOREAN_MOBILE.matches(digits)) {
                throw IllegalArgumentException(
                    "수신자는 앱 1차 마스킹이 실제로 가리는 국내 휴대폰 형식이어야 한다" +
                        "(01X-XXXX-XXXX). 다른 형식은 원문이 화면 label로 모델까지 나간다.",
                )
            }
            return SmsTestRecipient(digits)
        }
    }
}
