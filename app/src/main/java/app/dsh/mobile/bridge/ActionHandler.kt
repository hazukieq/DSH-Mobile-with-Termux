package app.dsh.mobile.bridge

import app.dsh.mobile.bridge.RuntimeHandler
import app.dsh.mobile.bridge.PtyHandler
import app.dsh.mobile.bridge.BridgeSupport
import app.dsh.mobile.ProgressHub
import app.dsh.mobile.Calib
import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.dsh.mobile.DshAccessibilityService
import app.dsh.mobile.DshApp
import app.dsh.mobile.OrientationMode
import app.dsh.mobile.OrientationPrefs
import app.dsh.mobile.R
import app.dsh.mobile.PpOcr
import app.dsh.mobile.ScreenOcr
import app.dsh.mobile.StatusOverlay
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import app.dsh.mobile.engine.LocalLoopExecutor
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.engine.AgentBridge

/**
 * ActionHandler：Phase 2 从 AgentBridge 拆出的端点组（成员原样迁移，行为不变）。
 *
 * 函数体包 `with(bridge)`，因此原文件里对 AgentBridge 成员的裸引用不用改名；
 * 跨组裸调用由本包的 AgentBridge 扩展别名（CrossCalls.kt）兜住。
 */
internal class ActionHandler(private val bridge: AgentBridge) {
    internal fun tapViaShell(x: Float, y: Float): Boolean {
        with(bridge) {
        val c = shotCtx ?: return false
        if (!Privilege.isElevated(c)) return false
        return Privilege.runPrivileged(c, "input tap ${x.toInt()} ${y.toInt()}") != null
        }
    }

    internal fun tapDispatch(svc: DshAccessibilityService, x: Float, y: Float, smart: Boolean): JSONObject {
        with(bridge) {
        val via = if (smart) svc.tapSmart(x, y) else if (svc.tapAtExact(x, y)) "gesture" else "failed"
        val out = JSONObject()
        out.put("ok", via != "failed")
        out.put("via", via)
        out.put("sent", JSONObject().put("x", x.toInt()).put("y", y.toInt()))
        val at = runCatching { svc.lastTapAt }.getOrNull()
        if (at != null && at.isNotBlank()) {
            val parts = at.split(",")
            if (parts.size == 2) {
                out.put("dispatchedAt", JSONObject()
                    .put("x", parts[0].trim().toIntOrNull() ?: 0)
                    .put("y", parts[1].trim().toIntOrNull() ?: 0))
            }
        }
        val node = runCatching { svc.lastTapNode }.getOrNull()
        if (node != null && node.isNotBlank()) runCatching { out.put("node", JSONObject(node)) }
        val win = runCatching { svc.windowInfoAt(x.toInt(), y.toInt()) }.getOrNull()
        if (win != null && win.isNotBlank()) runCatching { out.put("window", JSONObject(win)) }
        val stale = runCatching { svc.staleFullscreenNode() }.getOrNull()
        if (stale != null && stale.isNotBlank()) out.put("staleFullScreenNode", stale)
        if (runCatching { svc.pointInOwnOverlay(x.toInt(), y.toInt()) }.getOrDefault(false)) {
            out.put("ownOverlay", true)
            out.put("ownOverlayHint", "point is inside our own accessibility overlay; synthetic gestures may be swallowed — use POST /overlay {\"show\":false} or tap the node instead")
        }
        return out
        }
    }

    internal fun tap(body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        return try {
            val obj = JSONObject(body)
            lastTapDetail = null
            // 悬浮窗（v1.2.57）：让用户实时看到 AI 正在执行什么动作
            announceAction(
                R.string.a11y_action_tap,
                when {
                    obj.has("desc") -> "tap-desc \"${obj.getString("desc")}\""
                    obj.has("text") -> "tap-text \"${obj.getString("text")}\""
                    obj.has("x") -> "tap ${obj.getDouble("x").toInt()},${obj.getDouble("y").toInt()}"
                    else -> "tap"
                },
            )
            // v1.2.68「动作前校验前台包名」（内置 AI 建议）——
            // 它上轮误点抖音的直接原因就是「以为还在目标应用里，其实前台已经换了」。
            // 两层：① 调用方显式传 expectPkg 时**硬校验**，不符直接拒绝；
            //      ② 不传也自动对比「上次动作时的前台包名」，在响应里回报 foregroundChanged，
            //         让 AI 一步之内就能发现跑偏（不必等它自己 dump 察觉）。
            val fg = svc.foregroundPackage()
            val expect = obj.optString("expectPkg").takeIf { it.isNotBlank() }
            if (expect != null && fg != null && !pkgMatches(fg, expect)) {
                return 409 to """{"ok":false,"error":"foreground is $fg, expected $expect","foreground":"$fg"}"""
            }
            val fgChanged = lastActionPkg != null && fg != null && fg != lastActionPkg
            lastActionPkg = fg
            // 选择器歧义检测（v1.2.82，无障碍提案 P1-4）：
            // 提案原文「tap-text "7" 在计算器上同时匹配显示区和键盘，当前静默取第一个，
            // 这次恰好对，纯属运气」。现在：匹配到多个**同分**候选时默认拒绝并列出候选，
            // 调用方可传 index 明确选择，或传 allowAmbiguous:true 沿用旧行为。
            val sel = when {
                obj.has("desc") -> obj.getString("desc")
                obj.has("text") -> obj.getString("text")
                else -> null
            }
            if (sel != null) {
                val matches = svc.findMatches(sel, byDesc = obj.has("desc"))
                val index = if (obj.has("index")) obj.getInt("index") else -1
                val allowAmbiguous = obj.optBoolean("allowAmbiguous", false)
                if (matches.size > 1 && matches.first().ties > 1 && index < 0 && !allowAmbiguous) {
                    val cands = matches.take(5).map {
                        val rect = android.graphics.Rect().also { r -> it.node.getBoundsInScreen(r) }
                        JSONObject().apply {
                            put("label", it.label)
                            put("byDesc", it.byDesc)
                            put("clickable", it.clickable)
                            put("x", rect.centerX()); put("y", rect.centerY())
                        }
                    }
                    return 409 to JSONObject().apply {
                        put("ok", false)
                        put("error", "ambiguous selector: ${matches.size} nodes match \"$sel\"")
                        put("candidates", org.json.JSONArray(cands))
                        put("hint", "pass index:N to pick one, or allowAmbiguous:true to take the first")
                    }.toString()
                }
            }

            val index = if (obj.has("index")) obj.getInt("index") else -1
            // v1.8.2（提案 P1-6）：按 (listId, itemIndex) 点击列表行 —— 坐标由 App 实时算，
            // 滚动后也不会拿旧坐标点错。
            val listId = obj.optString("listId").takeIf { it.isNotBlank() }
            val itemIndex = if (obj.has("itemIndex")) obj.getInt("itemIndex") else -1
            if (listId != null && itemIndex >= 0) {
                val nodes = runCatching {
                    JSONObject(svc.dumpScreenJson("all", ocrFallback = false)).optJSONArray("nodes")
                }.getOrNull()
                var target: JSONObject? = null
                if (nodes != null) {
                    for (i in 0 until nodes.length()) {
                        val n = nodes.optJSONObject(i) ?: continue
                        if (n.optString("listId") == listId && n.optInt("itemIndex", -1) == itemIndex) {
                            target = n; break
                        }
                    }
                }
                val t = target ?: return 404 to JSONObject().put("ok", false)
                    .put("error", "no node with listId=$listId itemIndex=$itemIndex")
                    .put("failedAt", "tap")
                    .put("hint", "re-read /screen?format=json and use the current listId/itemIndex; lists are re-numbered per screen")
                    .toString()
                val cx = t.optInt("x"); val cy = t.optInt("y")
                val ok2 = svc.dispatchTap(cx.toFloat(), cy.toFloat())
                return 200 to JSONObject().apply {
                    put("ok", ok2)
                    put("dispatched", ok2)
                    put("via", "list")
                    put("listId", listId)
                    put("itemIndex", itemIndex)
                    put("x", cx); put("y", cy)
                    t.optJSONArray("bounds")?.let { put("bounds", it) }
                    t.optString("text").takeIf { it.isNotBlank() }?.let { put("text", it) }
                }.toString()
            }
            // v2.5.25：显式走提权通道（部分 App 拒绝无障碍注入，见 tapViaShell 注释）
            val via = obj.optString("via").lowercase()
            if (via == "shell") {
                val pt: Pair<Float, Float>? = when {
                    obj.has("x") && obj.has("y") ->
                        obj.getDouble("x").toFloat() to obj.getDouble("y").toFloat()
                    sel != null -> {
                        val m = svc.findMatches(sel, byDesc = obj.has("desc"))
                        val i = (if (obj.has("index")) obj.getInt("index") else 0)
                            .coerceIn(0, (m.size - 1).coerceAtLeast(0))
                        if (m.isEmpty()) null else android.graphics.Rect()
                            .also { m[i].node.getBoundsInScreen(it) }
                            .let { it.exactCenterX() to it.exactCenterY() }
                    }
                    else -> null
                }
                if (pt == null) {
                    return 400 to """{"ok":false,"error":"via=shell needs x/y, or text/desc that resolves on screen"}"""
                }
                if (!Privilege.isElevated(shotCtx ?: svc)) {
                    return 400 to """{"ok":false,"error":"via=shell requires Shizuku or root","how_to_fix":"enable privilege in settings, or drop via:shell"}"""
                }
                val okShell = tapViaShell(pt.first, pt.second)
                return if (okShell) {
                    200 to org.json.JSONObject().apply {
                        put("ok", true); put("dispatched", true); put("via", "shell")
                        put("x", pt.first.toInt()); put("y", pt.second.toInt())
                        put("hint", "shell input tap executed; verify with dump (IME focus or app-level filters may still swallow it)")
                    }.toString()
                } else {
                    500 to """{"ok":false,"error":"shell tap failed","failedAt":"tap","hint":"check privilege mode / Shizuku alive"}"""
                }
            }
            val ok = when {
                sel != null && index >= 0 -> {
                    // 按 index 选候选（歧义消解）
                    val m = svc.findMatches(sel, byDesc = obj.has("desc"))
                    if (index < m.size) svc.dispatchTapRect(
                        android.graphics.Rect().also { r -> m[index].node.getBoundsInScreen(r) }
                    ) else false
                }
                obj.has("desc") -> svc.tapDesc(obj.getString("desc"))
                obj.has("text") -> svc.tapText(obj.getString("text"))
                obj.has("x") && obj.has("y") -> {
                    val x = obj.getDouble("x").toFloat()
                    val y = obj.getDouble("y").toFloat()
                    // v2.5.30（用户实测“tap xy 变得不可靠”）：坐标调用语义是像素，默认只发合成手势；
                    // 需要节点语义时用 via:auto|node（或 smart:true）。
                    val smart = via == "node" || via == "auto" || obj.optBoolean("smart", false)
                    lastTapDetail = tapDispatch(svc, x, y, smart)
                    app.dsh.mobile.DshLog.d("tap", "xy $x,$y smart=$smart via=${lastTapDetail?.optString("via")} dispatched=${svc.lastTapAt}")
                    lastTapDetail!!.optBoolean("ok")
                }
                else -> false
            }
            // 坐标 tap：回执给足自证信息（实际落点 / 是否命中节点 / 残留全屏节点）
            val detail = lastTapDetail
            if (detail != null && ok) {
                val shot0 = captureStepShot()
                detail.put("dispatched", true)
                if (shot0 != null) detail.put("shot", shot0)
                if (!detail.has("hint")) {
                    detail.put("hint", "ok means the gesture was dispatched, not that the UI reacted — verify with dump; if it misses, retry with via:auto (node semantics) or tap-desc")
                }
                return 200 to detail.toString()
            }
            // ⚠️ ok:true 只表示**手势已派发**，不代表页面真的响应了（用户实测：
            // 点不可点击节点的祖先中心时返回 ok:true 但毫无变化）。
            // 故在响应里带上 dispatched 语义 + 提示，让调用方知道要自行校验。
            // 每步自动截图（v1.2.70）：路径直接给 AI，省掉它自己再调一次截图接口
            val shot = if (ok) captureStepShot() else null
            // v1.8.0（点击链路提案 P0-3）：tap 支持内联期望，点完就地校验，省掉一轮 dump。
            // 例：{"text":"搜索","expect":{"anyText":["综合","番剧"],"timeoutMs":4000}}
            val expectObj = obj.optJSONObject("expect")
            if (ok && expectObj != null) {
                val anyText = ArrayList<String>()
                expectObj.optJSONArray("anyText")?.let { a ->
                    for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(anyText::add)
                }
                val timeoutMs = expectObj.optLong("timeoutMs", 4_000L).coerceIn(200L, 15_000L)
                val t0 = System.currentTimeMillis()
                var verified = false
                var verifyHow = ""
                var lastDump = ""
                while (System.currentTimeMillis() - t0 < timeoutMs) {
                    lastDump = runCatching { svc.dumpScreenCompact("all") }.getOrDefault("")
                    val hitText = anyText.firstOrNull { lastDump.contains(it, true) }
                    if (hitText != null) {
                        verified = true
                        verifyHow = "anyText:$hitText"
                        break
                    }
                    Thread.sleep(250)
                }
                val elapsed = System.currentTimeMillis() - t0
                val fg2 = runCatching { svc.foregroundPackage() }.getOrNull()
                val obj2 = JSONObject().apply {
                    put("ok", true)
                    put("dispatched", true)
                    put("verified", verified)
                    put("verifyHow", verifyHow)
                    put("elapsedMs", elapsed)
                    put("foreground", fg2 ?: "")
                    put("foregroundChanged", fg2 != null && fg2 != fg)
                    if (shot != null) put("shot", shot)
                    if (!verified) {
                        put("failedAt", "tap")
                        put("screenHash", lastDump.hashCode())
                        put(
                            "hint",
                            "expect not met within ${timeoutMs}ms: the page may still be loading. " +
                                "Wait with /idle or dump once; retry with looser anyText before assuming the tap missed.",
                        )
                    }
                }
                return 200 to obj2.toString()
            }
            if (ok) {
                200 to """{"ok":true,"dispatched":true,"via":"${svc.lastTapVia ?: ""}","foreground":"${fg ?: ""}","foregroundChanged":$fgChanged,"shot":"${shot ?: ""}","hint":"ok means the gesture was dispatched, not that the UI reacted — dump again to verify"}"""
            } else {
                // 失败时附上"最接近的候选"（提案 P0-2）——
                // 省掉 AI「再花一整轮 dump 去找」的往返；常见真相是目标在 contentDescription 里
                // （计算器的 d:加 / d:等于），候选会带 d: 前缀标出来。
                val nearest = if (sel != null) svc.nearestCandidates(sel, 5) else emptyList()
                JSONObject().apply {
                    put("ok", false)
                    put("error", "tap failed / text not found")
                    put("failedAt", "tap")
                    put("screenHash", runCatching { svc.dumpScreenCompact("all").hashCode() }.getOrDefault(0))
                    put("foreground", fg ?: "")
                    put("foregroundChanged", fgChanged)
                    if (nearest.isNotEmpty()) {
                        put("nearest", org.json.JSONArray(nearest))
                    }
                    // P3-9：无论有没有候选都要给 hint，失败回执才可自解释
                    put(
                        "hint",
                        if (nearest.isNotEmpty()) {
                            "closest labels on screen; d: prefix means it is a contentDescription (use desc: not text:)"
                        } else {
                            "text not found; dump /screen or /scene once, then tap by the current label/rid or by listId+itemIndex"
                        },
                    )
                }.let { 500 to it.toString() }
            }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun gesture(body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
            // 悬浮窗：让用户看到「正在滑动/长按」（此前只有 /tap 会通知，见 announceAction 注释）
            announceAction(
                R.string.a11y_action_swipe,
                runCatching {
                    val o = JSONObject(body)
                    val t = o.optString("type").ifBlank { "gesture" }
                    if (o.has("x1")) "$t ${o.optDouble("x1").toInt()},${o.optDouble("y1").toInt()} -> ${o.optDouble("x2").toInt()},${o.optDouble("y2").toInt()}"
                    else "$t ${o.optDouble("x").toInt()},${o.optDouble("y").toInt()}"
                }.getOrDefault("gesture"),
            )
        return try {
            val obj = JSONObject(body)
            val gestureWarn = runCatching {
                if (obj.optString("type") == "swipe" && obj.has("x1")) {
                    val dx = obj.getDouble("x2") - obj.getDouble("x1")
                    val dy = obj.getDouble("y2") - obj.getDouble("y1")
                    val dist = kotlin.math.hypot(dx, dy)
                    val dur = obj.optLong("durationMs", 300L)
                    if (dist > 300 && dur < 300) {
                        "short duration (${dur}ms) with ${dist.toInt()}px distance will likely be treated as a fling; use durationMs>=800 for drags (slider/seekbar), and verify the resulting value"
                    } else ""
                } else ""
            }.getOrDefault("")
            val ok = when (obj.optString("type")) {
                // v1.2.149：默认**拟人化**滑动（弧线+变速+抖动）；human=false 精确直线
                "swipe" -> if (obj.optBoolean("human", true)) svc.dispatchSwipeHuman(
                    obj.getDouble("x1").toFloat(), obj.getDouble("y1").toFloat(),
                    obj.getDouble("x2").toFloat(), obj.getDouble("y2").toFloat(),
                    obj.optLong("durationMs", 300L),
                ) else svc.dispatchSwipe(
                    obj.getDouble("x1").toFloat(), obj.getDouble("y1").toFloat(),
                    obj.getDouble("x2").toFloat(), obj.getDouble("y2").toFloat(),
                    obj.optLong("durationMs", 300L),
                )
                "double_tap", "doubleTap" -> {
                    obj.optJSONArray("xy")?.let {
                        svc.dispatchDoubleTap(it.optDouble(0).toFloat(), it.optDouble(1).toFloat(), obj.optLong("gapMs", 90L))
                    } ?: svc.dispatchDoubleTap(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat(), obj.optLong("gapMs", 90L))
                }
                "long_press" -> svc.dispatchLongPress(
                    obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat(),
                    obj.optLong("durationMs", 600L),
                )
                "tap" -> svc.dispatchTap(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
                else -> return 400 to """{"ok":false,"error":"type must be swipe|long_press|tap"}"""
            }
            if (ok) 200 to """{"ok":true}""" else 500 to """{"ok":false,"error":"gesture dispatch failed"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun key(body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
            // 悬浮窗：导航键也要显示（此前完全没有）
            announceAction(R.string.a11y_action_key, "key " + JSONObject(body).optString("action"))
        return try {
            val action = JSONObject(body).optString("action")
            val ok = svc.performGlobalActionByName(action)
            if (ok) 200 to """{"ok":true,"action":"$action"}"""
            else if (action in listOf("dismiss-shade", "expand-notifications", "expand-settings", "notifications", "quick_settings"))
                200 to """{"ok":true,"action":"$action","note":"global action returned false — the panel is probably already in that state"}"""
            else 400 to """{"ok":false,"error":"unknown action (back|home|recents|notifications|quick_settings|expand-notifications|dismiss-shade|expand-settings)"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun wait(body: String, label: String = "wait"): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        return try {
            val obj = JSONObject(body)
            val text = obj.optString("text").ifEmpty {
                return 400 to """{"ok":false,"error":"missing text"}"""
            }
            val gone = obj.optBoolean("gone", false)
            val timeout = obj.optLong("timeoutMs", 8000L).coerceIn(200L, 60000L)
            val deadline = System.currentTimeMillis() + timeout
            while (System.currentTimeMillis() < deadline) {
                // 单次读取同样受超时约束：僵死时不再无限期阻塞轮询循环
                val found = withScreenTimeout("wait/dumpScreenJson") { svc.dumpScreenJson() }
                    ?.getOrNull()
                    ?.let { raw ->
                        val nodes = JSONObject(raw).getJSONArray("nodes")
                        (0 until nodes.length()).any { i ->
                            val n = nodes.getJSONObject(i)
                            n.optString("text").contains(text, true) ||
                                n.optString("desc").contains(text, true)
                        }
                    } ?: false
                if (found != gone) {
                    val waited = timeout - (deadline - System.currentTimeMillis())
                    return 200 to """{"ok":true,"condition":"${if (gone) "disappeared" else "appeared"}","waitedMs":$waited}"""
                }
                Thread.sleep(200)
            }
            200 to JSONObject().put("ok", false)
                .put("error", "timeout: text " + (if (gone) "still present" else "not found"))
                .put("failedAt", label).put("text", text).put("gone", gone).put("timeoutMs", timeout)
                .put("hint", "text did not " + (if (gone) "disappear" else "appear") +
                    " in time; check the page state or retry with a looser anchor").toString()
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun idle(body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        return try {
            val obj = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            val timeout = obj.optLong("timeoutMs", 3_000L).coerceIn(200L, 20_000L)
            val quietMs = obj.optLong("quietMs", 120L).coerceIn(50L, 1_000L)
            val stableReads = obj.optInt("stableReads", 3).coerceIn(2, 10)
            val r = withScreenTimeout("waitForIdle", timeout + 2_000L) {
                svc.waitForIdle(timeout, quietMs, stableReads)
            } ?: return 504 to """{"ok":false,"error":"idle wait timed out (node tree stalled)"}"""
            val stable = r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
            val pkg = svc.foregroundPackage() ?: ""
            200 to """{"ok":true,"stable":$stable,"pkg":${JSONObject.quote(pkg)}}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun act(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val steps = obj.optJSONArray("steps")
            ?: return 400 to """{"ok":false,"error":"steps[] required"}"""
        val onFail = obj.optString("onFail", "stop").lowercase()
        val returnScreens = obj.optBoolean("returnScreens", false)
        val t0 = System.currentTimeMillis()
        val results = JSONArray()
        var allOk = true
        var failedAt = -1
        val before = if (returnScreens) runCatching { svc.dumpScreenCompact("all").take(2500) }.getOrDefault("") else ""

        for (i in 0 until steps.length()) {
            val st = steps.optJSONObject(i) ?: continue
            val stepStart = System.currentTimeMillis()
            val r = JSONObject()
            var action = "?"
            var actionOk = true
            when {
                st.has("tap") -> {
                    action = "tap"
                    val v = st.get("tap")
                    when (v) {
                        is JSONArray -> actionOk = v.length() >= 2 &&
                            svc.dispatchTap(v.optDouble(0).toFloat(), v.optDouble(1).toFloat())
                        is JSONObject -> actionOk = v.has("x") && v.has("y") &&
                            svc.dispatchTap(v.optDouble("x").toFloat(), v.optDouble("y").toFloat())
                        else -> actionOk = svc.tapText(st.getString("tap"))
                    }
                }
                st.has("tapDesc") -> { action = "tap-desc"; actionOk = svc.tapDesc(st.getString("tapDesc")) }
                st.has("tapId") -> {
                    action = "tap-id"
                    actionOk = runCatching { svc.tapById(st.getString("tapId")) != null }.getOrDefault(false)
                }
                st.has("tapXY") -> {
                    action = "tap-xy"
                    val xy = st.optJSONArray("tapXY")
                    if (xy != null && xy.length() >= 2) {
                        val d = tapDispatch(svc, xy.optDouble(0).toFloat(), xy.optDouble(1).toFloat(),
                            st.optBoolean("smart", false))
                        actionOk = d.optBoolean("ok")
                        st.put("via", d.optString("via"))
                        for (k in listOf("sent", "dispatchedAt", "node", "window", "ownOverlay", "ownOverlayHint")) {
                            if (d.has(k) && !d.isNull(k)) st.put(k, d.get(k))
                        }
                        val staleNode = d.optString("staleFullScreenNode")
                        if (staleNode.isNotBlank()) st.put("staleFullScreenNode", staleNode)
                    } else actionOk = false
                }
                st.has("listId") && st.has("itemIndex") -> {
                    action = "tap-list"
                    val nodes = runCatching {
                        JSONObject(svc.dumpScreenJson("all", ocrFallback = false)).optJSONArray("nodes")
                    }.getOrNull()
                    var cx = -1; var cy = -1
                    if (nodes != null) for (j in 0 until nodes.length()) {
                        val n = nodes.optJSONObject(j) ?: continue
                        if (n.optString("listId") == st.getString("listId") &&
                            n.optInt("itemIndex", -1) == st.getInt("itemIndex")
                        ) { cx = n.optInt("x"); cy = n.optInt("y"); break }
                    }
                    actionOk = cx >= 0 && svc.dispatchTap(cx.toFloat(), cy.toFloat())
                }
                st.has("swipe") -> {
                    action = "swipe"
                    val sw = st.optJSONObject("swipe")
                    val arr = if (sw == null) st.optJSONArray("swipe") else null
                    var x1 = 0f; var y1 = 0f; var x2 = 0f; var y2 = 0f
                    var dur = 300L
                    var human = false
                    if (sw != null) {
                        x1 = sw.optDouble("x1").toFloat(); y1 = sw.optDouble("y1").toFloat()
                        x2 = sw.optDouble("x2").toFloat(); y2 = sw.optDouble("y2").toFloat()
                        dur = sw.optLong("durationMs", 300L)
                        human = sw.optBoolean("human", false)
                    } else if (arr != null && arr.length() >= 4) {
                        x1 = arr.optDouble(0).toFloat(); y1 = arr.optDouble(1).toFloat()
                        x2 = arr.optDouble(2).toFloat(); y2 = arr.optDouble(3).toFloat()
                        if (arr.length() >= 5) dur = arr.optLong(4, 300L)
                    } else actionOk = false
                    if (actionOk) {
                        actionOk = if (human) svc.dispatchSwipeHuman(x1, y1, x2, y2, dur)
                        else svc.dispatchSwipe(x1, y1, x2, y2, dur)
                    }
                }
                st.has("doubleTap") -> {
                    action = "double-tap"
                    val xy = st.optJSONArray("doubleTap")
                    val gap = st.optLong("gapMs", 90L).coerceIn(40L, 400L)
                    if (xy != null && xy.length() >= 2) {
                        val x = xy.optDouble(0).toFloat(); val y = xy.optDouble(1).toFloat()
                        actionOk = svc.dispatchDoubleTap(x, y, gap)
                    } else actionOk = false
                }
                st.has("input") -> {
                    action = "input"
                    actionOk = svc.inputText(st.getString("input"), st.optBoolean("append", false))
                }
                st.has("key") -> {
                    action = "key"
                    actionOk = svc.performGlobalActionByName(st.getString("key"))
                }
                st.has("sleepMs") -> {
                    action = "sleep"
                    runCatching { Thread.sleep(st.getLong("sleepMs").coerceIn(0L, 10_000L)) }
                }
                st.has("expect") -> action = "expect"
                else -> { action = "unknown"; actionOk = false }
            }

            var verified = true
            var verifyHow = ""
            val exp = st.optJSONObject("expect")
            if (exp != null && actionOk) {
                val anyText = ArrayList<String>()
                exp.optJSONArray("anyText")?.let { a ->
                    for (k in 0 until a.length()) a.optString(k).takeIf { it.isNotBlank() }?.let(anyText::add)
                }
                val timeoutMs = exp.optLong("timeoutMs", 4_000L).coerceIn(200L, 15_000L)
                val dead = System.currentTimeMillis() + timeoutMs
                verified = false
                while (System.currentTimeMillis() < dead) {
                    val dump = runCatching { svc.dumpScreenCompact("all") }.getOrDefault("")
                    val hit = anyText.firstOrNull { dump.contains(it, true) }
                    if (hit != null) { verified = true; verifyHow = "anyText:$hit"; break }
                    Thread.sleep(200)
                }
            }
            val stepOk = actionOk && verified
            r.put("index", i)
            r.put("action", action)
            for (k in listOf("via", "sent", "dispatchedAt", "node", "window", "ownOverlay", "ownOverlayHint", "staleFullScreenNode")) {
                val v = st.opt(k) ?: continue
                if (v is String && v.isBlank()) continue
                r.put(k, v)
            }
            r.put("ok", stepOk)
            r.put("verified", verified)
            r.put("verifyHow", verifyHow)
            r.put("elapsedMs", System.currentTimeMillis() - stepStart)
            if (!stepOk) {
                r.put("failedAt", i)
                r.put("hint", if (!actionOk) "action could not be dispatched (target not found?)" else "expect not met within timeout")
                allOk = false
                if (failedAt < 0) failedAt = i
            }
            results.put(r)
            if (!stepOk && onFail != "continue") break
        }

        val out = JSONObject()
            .put("ok", allOk)
            .put("steps", results)
            .put("elapsedMs", System.currentTimeMillis() - t0)
            .put("onFail", onFail)
        if (failedAt >= 0) {
            out.put("failedAt", failedAt)
            out.put("screenHash", runCatching { svc.dumpScreenCompact("all").hashCode() }.getOrDefault(0))
            out.put("hint", "inspect steps[failedAt]; re-read /scene, or retry with a looser expect/selector")
        }
        if (returnScreens) out.put("beforeScreen", before)
            .put("afterScreen", runCatching { svc.dumpScreenCompact("all").take(2500) }.getOrDefault(""))
        return 200 to out.toString()
        }
    }

    internal fun batch(body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
            // 悬浮窗：批量动作整体状态（每步的明细由 onAfterStep 更新动作行）
            announceAction(
                R.string.a11y_action_screen,
                "batch " + runCatching { JSONObject(body).optJSONArray("steps")?.length() ?: 0 }.getOrDefault(0) + " steps",
            )
        return try {
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("steps")
                ?: return 400 to """{"ok":false,"error":"missing steps array"}"""
            if (arr.length() == 0) return 400 to """{"ok":false,"error":"steps is empty"}"""
            if (arr.length() > 50) return 400 to """{"ok":false,"error":"too many steps (max 50)"}"""

            val steps = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.keys().asSequence().associateWith { k -> o.get(k) }
            }
            val settleMs = obj.optLong("settleMs", 2_000L).coerceIn(200L, 10_000L)
            val stopOnError = obj.optBoolean("stopOnError", true)

            // 整串动作可能跑很久（每步都等稳定），给足超时
            val budget = (steps.size * (settleMs + 1_500L)) + 5_000L
            // 每步自动截图（v1.2.70）：步骤序号 -> 截图路径，随结果一起回给 AI
            val shots = java.util.concurrent.ConcurrentHashMap<Int, String>()
            val r = withScreenTimeout("runBatch", budget) {
                svc.runBatch(steps, settleMs, stopOnError, { i ->
                    captureStepShot()?.let { shots[i] = it }
                    // 动作行跟着批量进度走，用户能实时看到「跑到第几步、在做什么」
                    runCatching {
                        val st = steps.getOrNull(i)
                        val t = (st?.get("type") as? String).orEmpty()
                        val label = (st?.get("text") ?: st?.get("desc"))?.toString().orEmpty()
                        StatusOverlay.setAction("[$i/${steps.size}] $t${if (label.isBlank()) "" else " \"" + label.take(20) + "\""}")
                    }
                }, obj.optBoolean("abortOnInterference", false))
            } ?: return 504 to """{"ok":false,"error":"batch timed out (node tree stalled)"}"""

            val results = r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
            val stepsJson = org.json.JSONArray()
            results.forEach { s ->
                stepsJson.put(JSONObject().apply {
                    put("i", s.index)
                    put("type", s.action)
                    put("ok", s.ok)
                    if (s.detail.isNotEmpty()) put("detail", s.detail)
                    if (s.retries > 0) put("retries", s.retries)
                    s.asserted?.let { put("asserted", it) }
                    shots[s.index]?.let { put("shot", it) }
                })
            }
            val allOk = results.isNotEmpty() && results.all { it.ok }
            // 附上外部干预标记：若执行期间用户碰过屏幕，AI 需要知道结果可能不可信
            val snap = svc.interferenceSnapshot()
            val interfered = svc.interferedSince(
                System.currentTimeMillis() - budget, svc.lastSelfAction(),
            )
            JSONObject().apply {
                put("ok", allOk)
                put("executed", results.size)
                put("total", steps.size)
                put("steps", stepsJson)
                if (interfered) put("userInterference", true)
                put("pkg", snap.pkg ?: "")
                // v1.2.128 动态引导（真机反馈「AI 还是在自己操作无障碍功能」）：
                // 多步 batch = AI 自己在当 picker（无本地判别/验证/重试）。静态
                // 提示词容易被忽略，在**响应里**给一次明确引导；仅 ≥3 步时出现，
                // 单步/双步不打扰。
                if (steps.size >= 3) {
                    put(
                        "tip",
                        "This multi-step flow was executed by you, not by the local loop — " +
                            "no picking, no target-state verification, no retry was applied " +
                            "(ok=true only means dispatch). Next time express it as a `loop run` " +
                            "plan: the local executor picks anchors (exact/semantic), verifies " +
                            "each step, retries failures, and needs no round trip per action.",
                    )
                }
            }.toString().let { 200 to it }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun input(body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
            // 悬浮窗：输入文字也要显示（此前完全没有）
            announceAction(
                R.string.a11y_action_input,
                "input \"" + JSONObject(body).optString("text").take(24) + "\"",
            )
        return try {
            val obj = JSONObject(body)
            val text = obj.optString("text")
            if (text.isEmpty() && !obj.has("text")) {
                return 400 to """{"ok":false,"error":"missing text"}"""
            }
            val append = obj.optBoolean("append", false)
            val target = obj.optString("target").takeIf { it.isNotBlank() }
            val r = withScreenTimeout("inputText", 4_000L) {
                svc.inputText(text, append, target)
            } ?: return 504 to """{"ok":false,"error":"input timed out (node tree stalled)"}"""
            val ok = r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
            if (ok) 200 to """{"ok":true,"chars":${text.length}}"""
            else 500 to """{"ok":false,"error":"no editable field found (tap the input box first, or pass target=)"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun tapId(body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val id = runCatching { JSONObject(body).optString("id") }.getOrNull().orEmpty()
        if (id.isBlank()) return 400 to """{"ok":false,"error":"id required"}"""
        val hit = runCatching { svc.tapById(id) }.getOrNull()
            ?: return 404 to JSONObject().put("ok", false)
                .put("error", "no node with id '$id'")
                .put("failedAt", "tap-id")
                .put("screenHash", runCatching { svc.dumpScreenCompact("all").hashCode() }.getOrDefault(0))
                .put("hint", "dump /screen?format=json or /scene and use the current rid/index; view ids can change between screens")
                .toString()
        val (cx, cy, via) = hit.first
        val r = hit.second
        return 200 to JSONObject().apply {
            put("ok", true)
            put("id", id)
            put("x", cx)
            put("y", cy)
            put("via", via)
            // v1.8.0（提案 P0-1）：回执带 bounds 与点击中心，便于核对是否点在空心图标/行上
            put("bounds", org.json.JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom))
            put("boundsCenter", org.json.JSONArray().put(cx).put(cy))
        }.toString()
        }
    }

    internal fun scrollCenter(body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        return try {
            val obj = JSONObject(body)
            val t = ((obj.optString("text").ifBlank { obj.optString("query") })).trim()
            if (t.isBlank()) return 400 to """{"ok":false,"error":"text is required"}"""
            val maxSwipes = obj.optInt("maxSwipes", 8)
            val forward = !obj.optBoolean("back", false)
            val rect = svc.scrollToCenter(t, maxSwipes, forward)
                ?: return 200 to """{"ok":false,"error":"not found after scrolling"}"""
            val tapped = if (obj.optBoolean("tap", false)) svc.dispatchTapRect(rect) else false
            JSONObject().apply {
                put("ok", true)
                put("x", rect.centerX()); put("y", rect.centerY())
                put("w", rect.width()); put("h", rect.height())
                if (tapped) put("tapped", true)
            }.let { 200 to it.toString() }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun scrollFind(body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
            // 悬浮窗：滚动查找也要显示（此前完全没有）
            announceAction(
                R.string.a11y_action_screen,
                "scroll-find \"" + JSONObject(body).optString("text").take(24) + "\"",
            )
        return try {
            val obj = JSONObject(body)
            val text = obj.optString("text")
            if (text.isBlank()) return 400 to """{"ok":false,"error":"missing text"}"""
            val wantTap = obj.optBoolean("tap", false)
            val maxSwipes = obj.optInt("maxSwipes", 8)
            val forward = !obj.optBoolean("back", false)
            // 滚动 + 多次读屏，整体给足时间（每次滚动 ~260ms + 读屏）
            val timeout = (maxSwipes.coerceIn(1, 30) * 700L) + 3_000L
            val r = withScreenTimeout("scrollToFind", timeout) {
                svc.scrollToFind(text, maxSwipes, forward)
            } ?: return 504 to """{"ok":false,"error":"scroll-find timed out (node tree stalled)"}"""
            val hit = r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
                ?: return 200 to """{"ok":false,"error":"not found after scrolling","scrolled":$maxSwipes}"""
            val cx = hit.exactCenterX()
            val cy = hit.exactCenterY()
            if (wantTap) {
                val tapped = svc.dispatchTapRect(hit)
                if (tapped) 200 to """{"ok":true,"x":${cx.toInt()},"y":${cy.toInt()},"tapped":true}"""
                else 500 to """{"ok":false,"error":"found but tap dispatch failed"}"""
            } else {
                200 to """{"ok":true,"x":${cx.toInt()},"y":${cy.toInt()},"tapped":false}"""
            }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun scrollEdge(body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val dir = runCatching { JSONObject(body).optString("direction", "bottom") }.getOrDefault("bottom")
        val n = runCatching { svc.scrollToEdge(forward = dir != "top") }.getOrDefault(0)
        return 200 to """{"ok":true,"direction":"$dir","swipes":$n}"""
        }
    }

    internal fun interference(query: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        return try {
            val params = parseQuery(query)
            val snap = svc.interferenceSnapshot()
            val since = params["since"]?.toLongOrNull()
            JSONObject().apply {
                put("ok", true)
                put("lastTouchAt", snap.lastTouchAt)
                put("touchCount", snap.touchCount)
                put("lastWindowChangeAt", snap.lastWindowChangeAt)   // 仅诊断，不参与判定
                put("lastSelfActionAt", svc.lastSelfAction())
                put("pendingSelfTouches", svc.pendingSelfTouches())
                put("pkg", snap.pkg ?: "")
                if (since != null) {
                    // 只看触摸；AI 自身手势按计数核销（时间戳会漏掉晚到的事件）
                    put("interfered", svc.interferedSince(since, svc.lastSelfAction()))
                }
            }.toString().let { 200 to it }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun overlay(ctx: android.content.Context, body: String): Pair<Int, String> {
        with(bridge) {
        return try {
            val obj = JSONObject(body)
            if (obj.optBoolean("hide", false)) {
                StatusOverlay.hide(DshAccessibilityService.instance)
                return 200 to """{"ok":true,"visible":false}"""
            }
            // v1.3.97：host 侧插件直推的结构化链路（kind/session/detail/elapsed）
            val detail = obj.optString("detail")
            if (detail.isNotBlank()) {
                android.util.Log.i("AgentBridge", "structured progress: kind=${obj.optString("kind")} session=${obj.optString("session").take(12)} len=${detail.length} preview=${detail.take(120).replace("\n", " / ")}")
                ProgressHub.update(
                    ctx,
                    obj.optString("kind").ifBlank { "thinking" },
                    obj.optString("title"),
                    detail,
                    obj.optString("session"),
                    obj.optLong("elapsed"),
                    // v2.5.8：工具行已并回 detail（思考链）；这里只保留可选的「最近一段推理」
                    think = obj.optString("think"),
                )
                return 200 to """{"ok":true,"structured":true}"""
            }
            // v1.5.7：无障碍/自动化的 status/action 走独立 notice 布局，不覆盖思考链
            val notice = listOfNotNull(
                obj.optString("status").takeIf { it.isNotBlank() },
                obj.optString("action").takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (notice.isNotBlank()) StatusOverlay.setA11yAction(notice)
            if (obj.optBoolean("complete", false)) StatusOverlay.flashCompleteDefault()
            // v1.3.68：同一份进度也进入后台提示（通知/悬浮窗），不再依赖无障碍
            ProgressHub.onAgentProgress(
                ctx,
                obj.optString("status").takeIf { it.isNotBlank() },
                obj.optString("action").takeIf { it.isNotBlank() },
                obj.optBoolean("complete", false),
            )
            200 to """{"ok":true,"available":${StatusOverlay.isAvailable()}}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun noteOpen(ctx: Context, target: String) {
        with(bridge) {
        runCatching { StatusOverlay.setA11yAction(ctx.getString(R.string.overlay_a11y_open, target)) }
        }
    }

    internal fun loopRun(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val r = withScreenTimeout("loop/run", 180_000L) {
            LocalLoopExecutor.run(ctx, svc, body)
        }
        return when {
            r == null -> 504 to """{"ok":false,"error":"loop timed out (>180s)"}"""
            r.isSuccess -> 200 to r.getOrThrow()
            else -> 500 to ("""{"ok":false,"error":"loop execution failed: """ +
                (r.exceptionOrNull()?.message ?: "unknown").replace("\"", "'") + """}""")
        }
        }
    }

    internal fun routeGuarded(ctx: Context, method: String, path: String, query: String, body: String): Pair<Int, String> = with(bridge) { 
        try {
            var r = route(ctx, method, path, query, body)
            if (path == "/scene" && r.first == 200) r = annotateTapPoints(r)
            if (path == "/scene" && query.contains("icon=1")) r = mergeSceneIcons(ctx, r)
            r
        } catch (t: Throwable) {
            app.dsh.mobile.DshLog.e("route", "uncaught in $method $path: ${t.javaClass.simpleName}: ${t.message}", t)
            500 to org.json.JSONObject().put("ok", false).put("error", t.javaClass.simpleName)
                .put("message", t.message ?: "").put("path", path).toString()
        }
    }

}
