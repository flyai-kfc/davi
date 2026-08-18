package com.davi.helper.e2e

import org.json.JSONObject

/** 앱이 실제 전송한 요청 snapshot에서 모델이 고른 요소의 label만 이탈·하드 경계 판정에 사용한다. */
internal fun DisplayedDecision.requestTargetLabel(): String? {
    val nodeId = action.targetNodeId ?: return null
    return runCatching {
        val elements = JSONObject(requestJson)
            .getJSONObject("screen")
            .getJSONArray("elements")
        for (index in 0 until elements.length()) {
            val element = elements.getJSONObject(index)
            if (element.optString("node_id") != nodeId || element.isNull("label")) continue
            return@runCatching element.optString("label").takeIf { it.isNotBlank() }
        }
        null
    }.getOrNull()
}

/**
 * 같은 결정 시점 화면의 요소 전체를 순수 판정부가 읽을 수 있는 형태로 옮긴다.
 *
 * 전송 차단은 label 하나가 아니라 **화면에 전송 컨트롤이 어디 있는지**를 알아야 판정할 수 있다
 * (#134). snapshot을 쓰는 이유는 모델이 본 화면과 같은 좌표계를 쓰기 때문이다.
 *
 * 파싱에 실패하면 빈 목록이 아니라 `null`을 돌려준다 — "전송 버튼이 없다"와 "확인하지 못했다"를
 * 같은 값으로 뭉개면 안전 판정이 조용히 통과한다.
 */
internal fun DisplayedDecision.requestScreenElements(): List<MessagingSendSafety.ScreenElement>? =
    runCatching {
        val elements = JSONObject(requestJson)
            .getJSONObject("screen")
            .getJSONArray("elements")
        (0 until elements.length()).map { index ->
            val element = elements.getJSONObject(index)
            MessagingSendSafety.ScreenElement(
                nodeId = element.optString("node_id").takeIf { it.isNotBlank() },
                label = if (element.isNull("label")) null else element.optString("label"),
                bounds = element.optJSONArray("bounds")?.let { raw ->
                    if (raw.length() != 4) {
                        null
                    } else {
                        MessagingSendSafety.Box.fromValues(
                            IntArray(4) { position -> raw.optInt(position) },
                        )
                    }
                },
            )
        }
    }.getOrNull()
