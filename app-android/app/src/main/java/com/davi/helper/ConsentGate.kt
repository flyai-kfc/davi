package com.davi.helper

/**
 * ON-1.5 개인정보 동의 판정. [AuthStore]의 [LoginGate]와 같은 결 — SharedPreferences 없이
 * JVM 테스트가 가능하도록 순수 함수로 분리한다.
 */
object ConsentGate {

    /** 필수 셋(#389) — strings.xml `consent_required_missing`이 "세 가지"라고 말하는 그 개수. */
    val REQUIRED_KEYS = setOf(ConsentItemKey.SCREEN, ConsentItemKey.VOICE, ConsentItemKey.USAGE)

    /** 필수가 다 켜져야 다음으로 갈 수 있다. */
    fun canProceed(checkedKeys: Set<ConsentItemKey>): Boolean = checkedKeys.containsAll(REQUIRED_KEYS)

    /**
     * 저장된 동의 문안 버전이 지금 문안과 다르면 다시 물어야 한다.
     * **문안을 바꾸면 consent_version을 올린다**(strings.xml) — 그 값이 다르다는 것 자체가
     * "이 동의는 그 문안에 대한 것이 아니다"라는 뜻이다.
     */
    fun needsConsent(storedVersion: String?, currentVersion: String): Boolean =
        storedVersion != currentVersion

    /**
     * 저장된 동의가 **지금 기준으로도** 유효한가. 버전이 같아도 **저장된 항목이 지금의
     * [REQUIRED_KEYS]를 덮지 못하면 다시 받아야 한다** — 필수 목록은 코드라 문안 버전과
     * 따로 움직인다(근거는 [ConsentStore.hasAgreedCurrentVersion]).
     */
    fun hasAgreed(storedVersion: String?, storedItems: Set<String>, currentVersion: String): Boolean =
        !needsConsent(storedVersion, currentVersion) &&
            storedItems.containsAll(REQUIRED_KEYS.map { it.name })
}

/** 화면에 서는 동의 항목 키. 저장 형식이라 이름을 바꾸면 기존 동의 이력의 `consent_items`가 깨진다. */
enum class ConsentItemKey {
    SCREEN,
    VOICE,
    USAGE,
    KAKAO,
}
