package com.davi.helper

/**
 * 읽는 중 표시가 **안내창을 가져갈지**의 판정(GD-2r, #295). Android 타입을 쓰지 않아 JVM
 * 테스트로 고정한다 — [ReadingBorder]가 "테두리를 켤지"를 맡고 여기는 "창을 가져갈지"다.
 *
 * **왜 뷰에서 뺐나** — 이 판정이 틀렸을 때 증상이 "두 번째 화면 전환부터 문구가 안 바뀐다"라
 * 실기기에서 두 번 전환해야 드러난다. 실제로 #295 구현 중 실기기에서만 잡혔다. 판정만
 * 순수 함수로 두면 그 회귀가 테스트 한 줄로 고정된다.
 */
object ReadingWindow {

    /**
     * 지금 읽는 중 표시를 **예약해야 하는가**.
     *
     * [windowTaken]은 창을 이미 읽는 중이 가져갔는가, [onsetPending]은 예약이 이미 큐에
     * 있는가다. 서비스가 폴링마다 부르므로 둘 다 없을 때만 예약해야 겹치지 않는다.
     *
     * **`on`의 전이로 판정하지 않는다.** 읽는 중이 켜진 채로 서버 안내가 도착하면 창은
     * 안내가 가져가는데([windowTaken]이 false가 된다) `on`은 계속 true다. 전이로만 예약하면
     * 그 뒤 화면이 다시 움직여도 예약이 안 걸려 **두 번째 구간부터 문구가 영영 안 바뀐다.**
     */
    fun shouldArmOnset(on: Boolean, windowTaken: Boolean, onsetPending: Boolean): Boolean =
        on && !windowTaken && !onsetPending
}
