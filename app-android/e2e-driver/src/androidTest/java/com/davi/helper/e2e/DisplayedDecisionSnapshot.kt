package com.davi.helper.e2e

import org.json.JSONObject

/** 앱이 실제 전송한 요청 snapshot에서 모델이 고른 요소의 label만 안전 검사에 사용한다. */
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
