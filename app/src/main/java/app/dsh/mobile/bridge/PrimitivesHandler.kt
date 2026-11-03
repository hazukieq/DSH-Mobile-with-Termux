package app.dsh.mobile.bridge

import app.dsh.mobile.engine.AgentBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动化原语：把「快速判断 + 短命控件操作 + 编排」做成可直接编排的端点。
 *
 * 设计意图：这些原语全部是幂等的**观察或单步动作**，可以被上层 /chain 串成点击链路。
 * 所有端点都返回 `{ok, ...}`，失败带 `error`，不抛异常。
 */
internal class PrimitivesHandler(private val bridge: AgentBridge) {

    /** 画面是否在动：连续采样 frameDiff，任意两次变化超过阈值即视为「在动」。 */
    internal fun motion(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        val samples = o.optInt("samples", 3).coerceIn(2, 8)
        val gapMs = o.optLong("gap_ms", 180L).coerceIn(60L, 800L)
        val changes = ArrayList<Int>()
        var last = bridge.screen.frameDiff().second
        repeat(samples - 1) {
            Thread.sleep(gapMs)
            val now = bridge.screen.frameDiff().second
            changes.add(if (now != last) 1 else 0)
            last = now
        }
        val movingCount = changes.count { it == 1 }
        val moving = movingCount > 0
        return 200 to JSONObject()
            .put("ok", true)
            .put("moving", moving)
            .put("samples", samples)
            .put("changed", movingCount)
            .put("hint", if (moving) "\u753b\u9762\u5728\u53d8\uff0c\u5148\u522b\u70b9\u56fa\u5b9a\u5750\u6807" else "\u753b\u9762\u9759\u6b62\uff0c\u53ef\u4ee5\u6309\u5750\u6807\u70b9")
            .toString()
    }

    /** 滚动方向确认：执行一次滑动，然后用 frameDiff 判断画面是否真的动了。 */
    internal fun scrollProbe(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        val dir = o.optString("direction", "down")
        val (sy, ey) = if (dir == "up") Pair(1500.0, 700.0) else Pair(700.0, 1500.0)
        val steps = JSONObject().apply {
            put("type", "swipe")
            put("x1", 540.0); put("y1", sy); put("x2", 540.0); put("y2", ey)
            put("durationMs", o.optLong("duration_ms", 260L))
        }.toString()
        val before = bridge.screen.frameDiff().second
        val r = bridge.action.gesture(steps)
        val gestureOk = r.first == 200 && !r.second.contains("\"ok\":false")
        Thread.sleep(o.optLong("settle_ms", 260L).coerceIn(80L, 1200L))
        val after = bridge.screen.frameDiff().second
        val moved = gestureOk && before != after
        return 200 to JSONObject()
            .put("ok", true)
            .put("direction", o.optString("direction", "down"))
            .put("moved", moved)
            .put("gesture", r.second)
            .put("hint", if (moved) "\u65b9\u5411\u6709\u6548\uff0c\u753b\u9762\u5df2\u6eda\u52a8" else "\u753b\u9762\u6ca1\u52a8\uff1a\u53ef\u80fd\u5df2\u5230\u8fb9\u754c\u6216\u8be5\u533a\u57df\u4e0d\u53ef\u6eda")
            .toString()
    }

    /** 短命控件（进度条/临时按钮）：在 holdMs 窗口内做一次连贯拖动。 */
    internal fun dragFast(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        val x1 = o.getDouble("x1"); val y1 = o.getDouble("y1")
        val x2 = o.getDouble("x2"); val y2 = o.getDouble("y2")
        val ms = o.optLong("duration_ms", 180L).coerceIn(60L, 600L)
        val g = JSONObject().apply {
            put("type", "swipe")
            put("x1", x1); put("y1", y1); put("x2", x2); put("y2", y2)
            put("durationMs", ms)
        }.toString()
        val t0 = System.currentTimeMillis()
        val r = bridge.action.gesture(g)
        return 200 to JSONObject()
            .put("ok", r.first == 200)
            .put("elapsed_ms", System.currentTimeMillis() - t0)
            .put("gesture", r.second)
            .toString()
    }

    /** 开屏广告/弹窗的跳过：按常见文案在无障碍树里找「跳过」，找到就点，找不到直接返回。 */
    internal fun skip(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        val words = listOf("\u8df3\u8fc7", "\u8df3\u8fc7\u5e7f\u544a", "\u5173\u95ed\u5e7f\u544a", "skip", "SKIP", "\u5173\u95ed", "×", "x")
            .joinToString("|")
        val query = o.optString("words").ifBlank { words }
        val hits = mutableListOf<String>()
        for (w in query.split("|")) {
            val w2 = w.trim(); if (w2.isEmpty()) continue
            val r = bridge.action.tap(JSONObject().apply { put("text", w2); put("exact", false) }.toString())
            if (r.first == 200 && !r.second.contains("\"ok\":false")) { hits.add(w2); break }
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("clicked", JSONArray(hits))
            .put("hint", if (hits.isEmpty()) "\u6ca1\u6709\u53ef\u70b9\u7684\u8df3\u8fc7/\u5173\u95ed\u63a7\u4ef6" else "\u5df2\u70b9\uff1a${hits.joinToString(",")}")
            .toString()
    }

}
