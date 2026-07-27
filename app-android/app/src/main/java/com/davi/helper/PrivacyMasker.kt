package com.davi.helper

/**
 * 기기에서 화면 텍스트를 서버로 보내기 전 치환하는 1차 개인정보 마스킹.
 *
 * 서버의 `server/app/masking.py`와 같은 순서·정규식을 유지한다. 규칙을 바꿀 때는
 * 반드시 양쪽을 함께 바꾸고 각각의 테스트를 갱신한다(SPEC §2, 이슈 #8).
 */
object PrivacyMasker {
    const val TOKEN = "•••"

    // 오탐을 줄이기 위해 형태가 뚜렷한 것부터 적용한다. 서버와 순서도 같다.
    private val patterns = listOf(
        Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"),
        // 주민등록번호 6-7
        Regex("\\b\\d{6}[-\\s]?[1-4]\\d{6}\\b"),
        // 카드번호 4-4-4-4
        Regex("\\b(?:\\d{4}[-\\s]?){3}\\d{4}\\b"),
        // 전화번호(휴대폰 위주). 하이픈/공백 허용.
        Regex("\\b01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}\\b"),
        // 계좌번호처럼 보이는 긴 숫자열(10~14자리, 하이픈 포함 가능)
        Regex("\\b\\d{2,4}[-\\s]\\d{2,6}[-\\s]\\d{2,7}\\b"),
    )

    /** 빈 문자열은 그대로 두고, 일치한 개인정보 문자열은 하나의 토큰으로 치환한다. */
    fun maskText(text: String): String =
        patterns.fold(text) { masked, pattern -> pattern.replace(masked, TOKEN) }
}
