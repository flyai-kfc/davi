package com.davi.helper

import android.util.Log

/**
 * 목표 구체화 구간의 **전문 진단 로그 — debug 구현**(#470).
 *
 * 발화·질문 문구·보기 문구는 사용자가 말한 것과 그것을 받아 만든 문장이다. `SPEC.md` §4(#92)가
 * 이 경로에 **패턴 마스킹을 일부러 안 걸었고**(전화번호를 지우면 `"010-…한테 전화 걸어줘"`의
 * 대상이 사라져 목표가 깨진다) 그 대가로 세운 방어선이 "운영 로그·도그푸딩 raw·픽스처에 안
 * 남긴다"다. **그래서 release 산출물에는 이 상세가 아예 들어가지 않는다** — 같은 이름의
 * release 구현이 no-op이고, `ci.yml`의 「release 산출물에 debug 전용 흔적이 없는지 확인」이
 * dex 문자열로 그 경계를 강제한다. 화면 경로의 [GuideScreenLog]와 같은 구조다(#58).
 *
 * **debug 안에서도 기본은 꺼짐이다.** 이 기기는 며칠 뒤 외부 시니어에게 간다. 그때는
 * release로 바꿔 깔 수가 없으므로(#215: release는 카카오 로그인이 막혀 있다) 빌드 종류만으로는
 * 안 닫힌다. 그래서 [ENABLED_TAG]를 한 겹 더 둔다 —
 *
 *     adb shell setprop log.tag.GuideSvcRaw D      # 켠다
 *     adb shell setprop log.tag.GuideSvcRaw INFO   # 되돌린다
 *
 * 속성이 없으면 안드로이드 기본 문턱이 INFO라 `isLoggable(DEBUG)`가 거짓이다. **잊으면
 * 켜져 있는 쪽이 아니라 잊으면 꺼져 있는 쪽으로 실패한다.** 관측 스크립트가 시작할 때 켜고
 * 끝날 때 되돌린다.
 *
 * **찍는 태그는 그대로 `GuideSvc`다** — 스위치 이름과 로그 태그는 별개라, 관측 도구의
 * `logcat -s GuideSvc` 필터를 고치지 않아도 이 줄이 같은 흐름에 끼어 시각 순서가 유지된다.
 */
object ConversationLog {
    /** 이 태그는 **스위치일 뿐 로그 태그가 아니다.** 여기에 찍히는 줄은 하나도 없다. */
    private const val ENABLED_TAG = "GuideSvcRaw"

    private fun enabled(): Boolean = Log.isLoggable(ENABLED_TAG, Log.DEBUG)

    /** 사용자가 말한 것(또는 개발판에서 입력한 것). 무엇을 시켰는지가 모든 판정의 출발점이다. */
    fun utterance(kind: String, text: String) {
        if (!enabled()) return
        Log.d(GuideAccessibilityService.TAG, "raw utterance kind=$kind text='$text'")
    }

    /** 서버가 준 질문과 보기. 「질문이 이상했다」는 이 줄이 있어야 읽을 수 있다. */
    fun question(index: Int, count: Int, prompt: String, options: List<String>) {
        if (!enabled()) return
        Log.d(
            GuideAccessibilityService.TAG,
            "raw question $index/$count prompt='$prompt' options=${options.joinToString("|")}",
        )
    }

    /** 확정된 목표 문장. 구체화가 무엇으로 수렴했는지. */
    fun goal(goal: String) {
        if (!enabled()) return
        Log.d(GuideAccessibilityService.TAG, "raw goal='$goal'")
    }

    /** 사용자가 고르거나 말한 답. */
    fun answer(source: String, answer: String) {
        if (!enabled()) return
        Log.d(GuideAccessibilityService.TAG, "raw answer source=$source text='$answer'")
    }
}
