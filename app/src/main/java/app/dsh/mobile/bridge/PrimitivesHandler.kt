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
    /**
     * 取当前屏「第一条有内容的控件文本」——用来给滚动方向提供可比较的锚点。
     *
     * 只读 a11y 的紧凑 dump（不截图、不 OCR），失败返回空串：
     * 调用方看到 `first_before` 为空即表示「这次取不到锚点」，不应据此判断方向。
     */
    private fun firstVisibleText(bridge: AgentBridge): String = runCatching {
        app.dsh.mobile.DshAccessibilityService.instance?.dumpScreenCompact("text")
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?.take(60)
            ?: ""
    }.getOrDefault("")

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
        // 复盘 issue(retro-alarm-scroll)：只给 moved 无法判断「滚的是不是目标方向」，
        // 于是 agent 在两个方向都 moved=true 时彻底迷失。这里直接给出滚动前后的**首条可见文本**，
        // 让调用方用「首条内容变早/变晚」自行判定语义方向（Android 上没有通用的时间序信息源）。
        val firstBefore = firstVisibleText(bridge)
        val r = bridge.action.gesture(steps)
        val gestureOk = r.first == 200 && !r.second.contains("\"ok\":false")
        Thread.sleep(o.optLong("settle_ms", 260L).coerceIn(80L, 1200L))
        val after = bridge.screen.frameDiff().second
        val firstAfter = firstVisibleText(bridge)
        val moved = gestureOk && before != after
        return 200 to JSONObject()
            .put("ok", true)
            .put("direction", o.optString("direction", "down"))
            .put("moved", moved)
            .put("first_before", firstBefore)
            .put("first_after", firstAfter)
            .put("content_changed", firstBefore.isNotEmpty() && firstAfter.isNotEmpty() && firstBefore != firstAfter)
            .put("gesture", r.second)
            .put("hint", if (moved) "\u65b9\u5411\u6709\u6548\uff0c\u753b\u9762\u5df2\u6eda\u52a8" else "\u753b\u9762\u6ca1\u52a8\uff1a\u53ef\u80fd\u5df2\u5230\u8fb9\u754c\u6216\u8be5\u533a\u57df\u4e0d\u53ef\u6eda")
            .toString()
    }

    /**
     * `op:"scroll-node"` —— 直接对可滚动节点派发 `ACTION_SCROLL_FORWARD/BACKWARD`。
     *
     * 为什么要这条：手势滚动会被三层东西干扰（我家悬浮窗吞手势、列表惯性、速率不稳），
     * 复盘 issue(retro-alarm-scroll) 里 30 次 swipe 全被卡片吃掉就是典型。节点滚动没有惯性、
     * 不会穿过悬浮窗，方向语义由参数显式给出，所以长列表粗定位优先用它；
     * 拿不到可滚动节点（Compose/自绘）时返回 `via:"none"`，调用方再退回 swipe。
     *
     * 参数：`{"op":"scroll-node","direction":"forward|backward","count":1}`
     * （也收 `down/up` 作别名，与 scroll-probe 的写法保持一致）
     */
    internal fun scrollNode(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        val raw = o.optString("direction", "forward").lowercase()
        val forward = when (raw) {
            "forward", "down", "next" -> true
            "backward", "back", "up", "prev" -> false
            else -> return 400 to """{"ok":false,"error":"direction must be forward|backward"}"""
        }
        val count = o.optInt("count", 1).coerceIn(1, 12)
        val svc = app.dsh.mobile.DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not connected"}"""
        val steps = runCatching { svc.scrollNodeSteps(forward, count) }.getOrDefault(0)
        val before = firstVisibleText(bridge)
        Thread.sleep(o.optLong("settle_ms", 160L).coerceIn(60L, 800L))
        val after = firstVisibleText(bridge)
        return 200 to JSONObject()
            .put("ok", steps > 0)
            .put("via", if (steps > 0) "node" else "none")
            .put("direction", if (forward) "forward" else "backward")
            .put("steps", steps)
            .put("first_before", before)
            .put("first_after", after)
            .put("hint", if (steps > 0) "node scroll applied" else "no scrollable node found; fall back to swipe (POST /gesture) or /act steps")
            .toString()
    }

    /** 短命控件（进度条/临时按钮）：在 holdMs 窗口内做一次连贯拖动。 */
    internal fun dragFast(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        // 两种写法都收：{x1,y1,x2,y2} 或 {from:[x,y], to:[x,y]}（agent 常按坐标对写）。
        val x1: Double; val y1: Double; val x2: Double; val y2: Double
        if (o.has("from") && o.has("to")) {
            val f = o.getJSONArray("from"); val t = o.getJSONArray("to")
            x1 = f.getDouble(0); y1 = f.getDouble(1); x2 = t.getDouble(0); y2 = t.getDouble(1)
        } else {
            x1 = o.getDouble("x1"); y1 = o.getDouble("y1")
            x2 = o.optDouble("x2", x1); y2 = o.optDouble("y2", y1)
        }
        val msRaw = if (o.has("duration_ms")) o.optLong("duration_ms", 180L) else o.optLong("durationMs", 180L)
        // 进度条/滑块这类「必须真拖」的控件：<400ms 会被系统当 fling，直接抬到 400ms。
        val ms = msRaw.coerceIn(60L, 1200L).let { if (it < 400L) 400L else it }
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


    /**
     * 语义封装：点击 → 控件出现（默认 2s 内会消失）→ 立刻对它做动作。
     * body: {tap:[x,y]|tapText, expect:"\u8fdb\u5ea6\u6761"|{text,desc,id}, appear_ms=600,
     *        action:{kind:"tap"|"swipe"|"drag-to"|"none", dx,dy,x,y,duration_ms}}
     */
    internal fun transient(ctx: android.content.Context, body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        val svc = app.dsh.mobile.DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"a11y_off"}"""
        val t0 = System.currentTimeMillis()
        // 1) 触发：点一下让控件出现
        val tapArr = o.optJSONArray("tap")
        val tapText = o.optString("tapText")
        // 触发是可选的：控件已经可见时不要 tap（tap 视频画面会**切换**控制条显隐，反而把它藏起来）。
        if (tapArr != null && tapArr.length() >= 2) {
            svc.dispatchTap(tapArr.optDouble(0).toFloat(), tapArr.optDouble(1).toFloat())
        } else if (tapText.isNotBlank()) {
            runCatching { svc.tapText(tapText) }
        }
        // 2) 在 appear_ms 内轮询无障碍树，等目标控件出现
        val expectObj = o.optJSONObject("expect")
        val wantText = expectObj?.optString("text").orEmpty().ifBlank { if (expectObj == null) o.optString("expect") else "" }
        val wantDesc = expectObj?.optString("desc").orEmpty()
        val wantId = expectObj?.optString("id").orEmpty()
        // 多候选 + 全字段（text/desc/id/label/hint）包含匹配，且大小写不敏感：
        // 数值会变的控件（如「视频进度，已播放到32秒」）只给稳定片段即可命中。
        val wants = ArrayList<String>()
        listOf(wantText, wantDesc, wantId).forEach { if (it.isNotBlank()) wants.add(it) }
        expectObj?.optJSONArray("any")?.let { a -> for (k in 0 until a.length()) a.optString(k).takeIf { it.isNotBlank() }?.let(wants::add) }
        if (wants.isEmpty()) o.optString("expect").takeIf { it.isNotBlank() }?.let(wants::add)
        val appearMs = o.optLong("appear_ms", 600L).coerceIn(60L, 1900L)
        val deadline = System.currentTimeMillis() + appearMs
        var node: JSONObject? = null
        var scanned = 0
        val samples = ArrayList<String>()
        while (System.currentTimeMillis() < deadline) {
            val nodes = runCatching {
                JSONObject(svc.dumpScreenJson("all", ocrFallback = false)).optJSONArray("nodes")
            }.getOrNull()
            if (nodes != null) {
                scanned = nodes.length()
                for (i in 0 until nodes.length()) {
                    val n = nodes.optJSONObject(i) ?: continue
                    val hay = (n.optString("text") + " " + n.optString("desc") + " " + n.optString("id") + " " +
                               n.optString("label") + " " + n.optString("hint"))
                    if (samples.size < 6) samples.add(hay.trim().take(24))
                    if (wants.any { hay.contains(it, ignoreCase = true) }) { node = n; break }
                }
            }
            if (node != null) break
            Thread.sleep(40)
        }
        val foundMs = System.currentTimeMillis() - t0
        if (node == null) {
            return 200 to JSONObject().put("ok", false).put("error", "not_found")
                .put("expect", wants.joinToString("|")).put("appear_ms", appearMs).put("elapsed_ms", foundMs)
                .put("scanned", scanned).put("sample", JSONArray(samples))
                .put("hint", "\u63a7\u4ef6\u6ca1\u51fa\u73b0\uff1a\u53ef\u80fd\u89e6\u53d1\u70b9\u4e0d\u5bf9\uff0c\u6216\u5b83\u5728 a11y \u6811\u91cc\u6ca1\u6709\u6587\u5b57/\u63cf\u8ff0").toString()
        }
        val cx = node.optInt("x"); val cy = node.optInt("y")
        val appearAt = System.currentTimeMillis()
        // 硬预算：控件出现后必须在 budget_ms（默认 2000）内完成动作，否则判定失败
        val budgetMs = o.optLong("budget_ms", 2000L).coerceIn(200L, 5000L)
        if (appearAt - t0 >= budgetMs) {
            return 200 to JSONObject().put("ok", false).put("error", "budget_exceeded")
                .put("expect", wantText).put("budget_ms", budgetMs)
                .put("elapsed_ms", appearAt - t0).toString()
        }
        // 3) 立刻对控件做动作（在它消失前）
        val act = o.optJSONObject("action") ?: JSONObject().put("kind", "none")
        val kind = act.optString("kind", "none")
        val a0 = System.currentTimeMillis()
        var actionResult = "none"
        var actionDetail: JSONObject? = null
        when (kind) {
            "tap" -> actionResult = if (svc.dispatchTap(cx.toFloat(), cy.toFloat())) "ok" else "fail"
            "swipe", "drag-to" -> {
                // 滑块/进度条：手势必须从**手柄**起手，落点才被识别为拖动。
                // from 显式给起点；不给则用命中节点的中心（对 seekbar 常常是整条的中点，会跳）。
                val dur = if (act.has("duration_ms")) act.optLong("duration_ms", 400L).coerceIn(60L, 2000L)
                          else act.optLong("durationMs", 400L).coerceIn(60L, 2000L)
                val fromArr = act.optJSONArray("from")
                val x1 = fromArr?.optDouble(0) ?: cx.toDouble()
                val y1 = if (fromArr != null) fromArr.optDouble(1) else cy.toDouble()
                val x2 = if (kind == "drag-to") act.optDouble("x", cx.toDouble()) else x1 + act.optDouble("dx", 0.0)
                val y2 = if (kind == "drag-to") act.optDouble("y", y1) else y1 + act.optDouble("dy", 0.0)
                val g = JSONObject().apply {
                    put("type", "swipe")
                    put("x1", x1); put("y1", y1)
                    put("x2", x2); put("y2", y2)
                    put("durationMs", dur)
                }.toString()
                val r = bridge.action.gesture(g)
                actionResult = if (r.first == 200 && !r.second.contains("\"ok\":false")) "ok" else r.second
                actionDetail = JSONObject().apply {
                    put("from", JSONArray().put(x1).put(y1))
                    put("to", JSONArray().put(x2).put(y2))
                    put("duration_ms", dur)
                }
            }
        }
        val spent = System.currentTimeMillis() - appearAt
        val withinBudget = spent <= budgetMs
        return 200 to JSONObject().put("ok", withinBudget && actionResult != "fail")
            .put("error", if (withinBudget) "" else "budget_exceeded")
            .put("budget_ms", budgetMs).put("spent_ms", spent)
            .put("expect", wantText).put("node", JSONObject().put("x", cx).put("y", cy)
                .put("text", node.optString("text")).put("id", node.optString("id")))
            .put("appear_ms", foundMs).put("action", kind).put("action_result", actionResult)
            .put("action_detail", actionDetail ?: JSONObject())
            .put("action_ms", System.currentTimeMillis() - a0)
            .put("total_ms", System.currentTimeMillis() - t0)
            .toString()
    }
}
