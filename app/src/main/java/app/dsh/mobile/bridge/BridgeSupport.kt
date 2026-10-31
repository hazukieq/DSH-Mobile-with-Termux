package app.dsh.mobile.bridge

import org.json.JSONObject

/**
 * 桥处理器共用的小工具（Phase 2 拆分时从 AgentBridge 提出）。
 *
 * 只放「纯函数、无状态、无 Context」的东西：query 解析、202/409 回执、URL 解码。
 * AgentBridge 里保留同名私有方法做一行委托，调用点不用改。
 */
internal object BridgeSupport {

    /** "a=1&b=2" → Map（UTF-8 解码，非法转义原样返回） */
    fun parseQuery(query: String): Map<String, String> =
        query.split("&").filter { it.contains("=") }.associate {
            val i = it.indexOf('=')
            java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to
                java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }

    /** 202 = 已受理；409 = 被拒绝（忙 / 无暂存 / 无旧版 …），错误文本原样给调用方 */
    fun acceptedOrRejected(action: String, err: String?): Pair<Int, String> =
        if (err == null) {
            202 to JSONObject().put("ok", true).put("action", action).put("accepted", true).toString()
        } else {
            409 to JSONObject().put("ok", false).put("action", action).put("error", err).toString()
        }

    fun urlDecode(s: String): String = runCatching {
        java.net.URLDecoder.decode(s, "UTF-8")
    }.getOrDefault(s)
}
