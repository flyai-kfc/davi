package com.davi.helper

import java.security.MessageDigest

/**
 * 도그푸딩 화이트리스트용 기기 해시(#41).
 *
 * `ANDROID_ID` 원문 대신 SHA-256 앞 16자(hex)만 만든다. 사람이 정한 라벨을 쓰지 않는 이유는
 * `local.properties` 복사 실수로 개인 기기가 화이트리스트 값과 겹치는 경로를 없애기 위해서다.
 * 계산·전송 자체도 옵트인 debug 빌드(`DAVI_DOGFOOD=true`)에서만 한다 — 옵트인 없는 빌드는
 * 이 함수를 부르지 않는다(#77 리뷰: 화이트리스트는 저장만 거르고 전송은 못 막는다).
 *
 * `ANDROID_ID`는 앱 서명키·기기 조합별 값이라(Android 8+) 다른 debug 키스토어로 빌드하면
 * 해시가 바뀐다 — 서버 화이트리스트(`DAVI_DOGFOOD_DEVICE_IDS`)에 새 값을 추가하면 된다.
 * 같은 이유로 `adb shell settings get secure android_id`(셸 스코프)로는 이 값을 못 얻는다 —
 * 서비스 연결 로그의 `dogfoodDeviceId=`가 유일한 추출 경로다.
 */
object DogfoodDeviceId {
    private const val HASH_LENGTH = 16

    /** null·공백 입력이면 null — 요청에서 필드가 통째로 빠진다. */
    fun hash(androidId: String?): String? {
        val source = androidId?.trim().orEmpty()
        if (source.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
    }
}
