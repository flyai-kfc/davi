package com.davi.helper

/**
 * 서버로 나갈 label에 1차 개인정보 마스킹을 적용할지 정하는 정책(#203).
 *
 * 기본은 언제나 [PrivacyMaskingLabelMasker]다. 우회는 **debug variant에서 빌드 시
 * 명시적으로 opt-in한 비교 실험**에서만 쓴다. release 산출물은
 * `BuildConfig.PRIVACY_MASKING_DISABLED`가 상수 false라 우회 경로 자체가 없다
 * (`app/build.gradle.kts`의 `verifyReleaseConfiguration` 참고).
 *
 * 정책을 값으로 분리한 이유는 [UiTreeSerializer]가 빌드 플래그를 직접 읽지 않게 하려는
 * 것이다. 그래야 마스킹된 경로와 우회 경로를 둘 다 단위 테스트로 고정할 수 있다.
 */
fun interface LabelMasker {
    fun mask(text: String): String
}

/** 제품 기본값 — 서버로 나가기 전 PII 패턴을 토큰으로 치환한다(SPEC §2). */
object PrivacyMaskingLabelMasker : LabelMasker {
    override fun mask(text: String): String = PrivacyMasker.maskText(text)
}

/**
 * debug 비교 실험 전용 우회.
 *
 * **원문 label이 서버를 거쳐 실제 Gemini까지 그대로 나간다.** 합성 데이터만 넣은 전용
 * 테스트 기기에서만 쓴다. 연락처·메시지·계정 등 실제 개인정보가 있는 기기에서는 쓰지 않는다.
 *
 * 치환만 생략하고 label 정규화(줄바꿈·중복 공백 접기, 길이 제한)는 그대로 둔다 — 비교의
 * 목적은 "마스킹이 가이드 품질에 주는 영향"이지 직렬화 형태의 차이가 아니다.
 */
object PassthroughLabelMasker : LabelMasker {
    override fun mask(text: String): String = text
}

/**
 * 빌드 플래그 하나를 정책으로 옮긴다.
 *
 * 플래그 해석을 이 함수 하나에 모아 두면 "우회가 켜졌을 때 실제로 무엇이 바뀌는가"를
 * 빌드 없이 테스트로 검증할 수 있다.
 */
fun labelMaskerFor(maskingDisabled: Boolean): LabelMasker =
    if (maskingDisabled) PassthroughLabelMasker else PrivacyMaskingLabelMasker
