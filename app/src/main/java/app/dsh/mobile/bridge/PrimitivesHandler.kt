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
            .put("hint", if (moving) "画面在变，先别点固定坐标" else "画面静止，可以按坐标点")
            .toString()
    }

    /** 滚动方向确认：执行一次滑动，然后用 frameDiff 判断画面是否真的动了。 */
    internal fun scrollProbe(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        val steps = JSONObject().apply {
            put("direction", o.optString("direction", "down"))
            put("amount", o.optInt("amount", 1))
        }.toString()
        val before = bridge.screen.frameDiff().second
        val r = bridge.action.gesture(steps)
        Thread.sleep(o.optLong("settle_ms", 260L).coerceIn(80L, 1200L))
        val after = bridge.screen.frameDiff().second
        val moved = before != after
        return 200 to JSONObject()
            .put("ok", true)
            .put("direction", o.optString("direction", "down"))
            .put("moved", moved)
            .put("gesture", r.second)
            .put("hint", if (moved) "方向有效，画面已滚动" else "画面没动：可能已到边界或该区域不可滚")
            .toString()
    }

    /** 短命控件（进度条/临时按钮）：在 holdMs 窗口内做一次连贯拖动。 */
    internal fun dragFast(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        val x1 = o.getDouble("x1"); val y1 = o.getDouble("y1")
        val x2 = o.getDouble("x2"); val y2 = o.getDouble("y2")
        val ms = o.optLong("duration_ms", 180L).coerceIn(60L, 600L)
        val g = JSONObject().apply {
            put("x1", x1); put("y1", y1); put("x2", x2); put("y2", y2)
            put("duration", ms)
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
        val words = listOf("跳过", "跳过广告", "关闭广告", "skip", "SKIP", "关闭", "×", "x")
            .joinToString("|")
        val query = o.optString("words").ifBlank { words }
        val hits = mutableListOf<String>()
        for (w in query.split("|")) {
            val w2 = w.trim(); if (w2.isEmpty()) continue
            val r = bridge.action.tap(JSONObject().apply { put("text", w2); put("exact", false) }.toString())
            if (r.first == 200 && !r.second.contains("\"ok\":false")) hits.add(w2)
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("clicked", JSONArray(hits))
            .put("hint", if (hits.isEmpty()) "没有可点的跳过/关闭控件" else "已点：${hits.joinToString(",")}")
            .toString()
    }

    /** 编排：把一个步骤数组顺序执行，任一步失败即停止并回报。 */
    internal fun chain(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        val steps = o.optJSONArray("steps") ?: JSONArray()
        val results = JSONArray()
        var ok = true
        for (i in 0 until steps.length()) {
            if (!ok) break
            val st = steps.optJSONObject(i) ?: continue
            val op = st.optString("op")
            val wait = st.optLong("wait_ms", 0L)
            if (wait > 0) Thread.sleep(wait)
            val r: Pair<Int, String> = when (op) {
                "tap" -> bridge.action.tap(st.toString())
                "gesture", "scroll" -> bridge.action.gesture(st.toString())
                "skip" -> skip(st.toString())
                "motion" -> motion(st.toString())
                "wait" -> bridge.action.idle(st.toString())
                else -> 404 to "{\"ok\":false,\"error\":\"unknown op: $op\"}"
            }
            ok = r.first == 200 && !r.second.contains("\"ok\":false")
            results.put(JSONObject().put("op", op).put("code", r.first).put("result", r.second))
        }
        return 200 to JSONObject()
            .put("ok", ok)
            .put("steps", results)
            .toString()
    }
}
