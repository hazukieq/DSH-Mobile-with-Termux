package app.dsh.mobile.bridge

import org.json.JSONArray

/** 从 AgentBridge 提出的纯数据类型（读屏 / OCR 结果，跨 handler 共享）。 */
internal data class Box(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val conf: Double) {
        val cx: Int get() = (left + right) / 2
        val cy: Int get() = (top + bottom) / 2
        val h: Int get() = bottom - top
    }

internal data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val cx: Int get() = (left + right) / 2
        val cy: Int get() = (top + bottom) / 2
    }

internal data class OcrPrefetch(val ts: Long, val w: Int, val h: Int, val nodes: JSONArray)

