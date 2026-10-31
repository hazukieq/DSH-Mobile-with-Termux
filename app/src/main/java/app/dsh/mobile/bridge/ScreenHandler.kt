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
import app.dsh.mobile.engine.AppShot
import app.dsh.mobile.engine.FastPicker
import app.dsh.mobile.engine.ShotFallback
import app.dsh.mobile.engine.AgentBridge

/**
 * ScreenHandler：Phase 2 从 AgentBridge 拆出的端点组（成员原样迁移，行为不变）。
 *
 * 函数体包 `with(bridge)`，因此原文件里对 AgentBridge 成员的裸引用不用改名；
 * 跨组裸调用由本包的 AgentBridge 扩展别名（CrossCalls.kt）兜住。
 */
internal class ScreenHandler(private val bridge: AgentBridge) {
    internal fun prefetchOcr(svc: DshAccessibilityService, tier: String = "tiny") {
        with(bridge) {
        val now = System.currentTimeMillis()
        val cur = ocrPrefetch
        if (cur != null && now - cur.ts < 2500) return
        if (ocrPrefetching) return
        ocrPrefetching = true
        Thread {
            try {
                val t0 = System.currentTimeMillis()
                val shot = svc.screenshotBase64(2500L) ?: return@Thread
                val bytes = android.util.Base64.decode(shot.third, android.util.Base64.NO_WRAP)
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@Thread
                val r = PpOcr.recognize(svc, bmp, tier, useCache = true, regionOnly = false)
                bmp.recycle()
                val arr = JSONArray()
                r.blocks.forEach { b ->
                    arr.put(JSONObject().put("text", b.text)
                        .put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom)))
                        .put("center", JSONArray(listOf(b.cx, b.cy)))
                        .put("source", "ocr").put("role", "text"))
                }
                ocrPrefetch = OcrPrefetch(System.currentTimeMillis(), shot.first, shot.second, arr)
                app.dsh.mobile.DshLog.d("prefetch", "ocr ready n=${arr.length()} ms=${System.currentTimeMillis() - t0}")
            } catch (e: Throwable) {
                app.dsh.mobile.DshLog.e("prefetch", "ocr prefetch failed: ${e.message}", e)
            } finally { ocrPrefetching = false }
        }.start()
        }
    }

    internal fun ocrFallback(reason: String, maxMs: Long = 1500L): Pair<Int, String>? {
        with(bridge) {
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            val p = ocrPrefetch
            if (p != null) {
                val scene = JSONObject().put("ok", true).put("fallback", "ocr-prefetch")
                    .put("reason", reason).put("itemsKey", "nodes")
                    .put("screenshot", JSONObject().put("w", p.w).put("h", p.h))
                    .put("nodes", p.nodes)
                return 200 to scene.toString()
            }
            Thread.sleep(80)
        }
        return null
        }
    }

    internal fun a11yGone(): Pair<Int, String> = with(bridge) { 
        ocrFallback("accessibility restricted") ?: (503 to JSONObject()
        .put("ok", false)
        .put("error", "a11y_off")
        .put("code", "a11y_off")
        .put("reason", "the accessibility service disconnected while handling this request; this endpoint needs it to read the UI tree, inject gestures/keys, or capture the screen")
        .put("how_to_fix", "re-enable 'DSH Mobile Helper (optional)' in Settings > Accessibility; POST /a11y/enable opens that page")
        .put("actionHint", "POST /a11y/enable opens the system accessibility page")
        .put("retryable", true)
        .toString())
    }

    @Volatile internal var a11ySeqCount = 0

    @Volatile internal var a11ySeqLastAt = 0L

    internal val a11yNotifHandler = android.os.Handler(android.os.Looper.getMainLooper())

    internal var a11yNotifIdle: Runnable? = null

    internal fun a11yOpLabel(ctx: Context, path: String): String = with(bridge) { when (path) {
        "/tap", "/tap-id", "/vision/tap" -> ctx.getString(R.string.notif_a11y_op_tap)
        "/gesture", "/vision/drag" -> ctx.getString(R.string.notif_a11y_op_swipe)
        "/scroll-find", "/scroll-center", "/scroll-edge" -> ctx.getString(R.string.notif_a11y_op_scroll)
        "/input" -> ctx.getString(R.string.notif_a11y_op_input)
        "/key" -> ctx.getString(R.string.notif_a11y_op_key)
        "/launch", "/launch-intent", "/open" -> ctx.getString(R.string.notif_a11y_op_launch)
        "/loop/run" -> ctx.getString(R.string.notif_a11y_op_loop)
        else -> ctx.getString(R.string.notif_a11y_op_other)
    }
    }

    internal fun noteA11yOperation(ctx: Context, path: String) {
        with(bridge) {
        // 悬浮窗模式下不再发系统通知（用户反馈：开了悬浮窗还弹多条通知，常驻引擎那条除外）
        if (ProgressHub.mode(ctx) == ProgressHub.MODE_OVERLAY) return
        try {
            val now = android.os.SystemClock.elapsedRealtime()
            val count: Int
            synchronized(this) {
                if (a11ySeqLastAt == 0L || now - a11ySeqLastAt > A11Y_NOTIF_WINDOW_MS) {
                    a11ySeqCount = 0
                }
                // v1.2.188（审查 P2）：/loop/run 是"整段连续操作"——它的内部每一步
                // 在进程内执行、不再经过 HTTP，若只计 1 步，daemon 的长任务永远
                // 凑不够阈值 3，通知在最主要场景下不出现。这里按整段计 3 步。
                a11ySeqCount += if (path == "/loop/run") A11Y_NOTIF_THRESHOLD else 1
                a11ySeqLastAt = now
                count = a11ySeqCount
            }
            if (count < A11Y_NOTIF_THRESHOLD) return
            showA11yNotification(ctx, count, a11yOpLabel(ctx, path))
            synchronized(this) {
                a11yNotifIdle?.let { a11yNotifHandler.removeCallbacks(it) }
                val r = Runnable {
                    runCatching {
                        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(A11Y_NOTIF_ID)
                    }
                    synchronized(this) {
                        a11ySeqCount = 0
                        a11ySeqLastAt = 0L
                        a11yNotifIdle = null
                    }
                }
                a11yNotifIdle = r
                a11yNotifHandler.postDelayed(r, A11Y_NOTIF_IDLE_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "a11y notify skipped: ${e.message}")
        }
        }
    }

    internal fun showA11yNotification(ctx: Context, step: Int, label: String) {
        with(bridge) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return   // 没授权就不打扰；操作照常进行
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.notif_channel_agent),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(ctx, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(ctx)
        }
        // 点通知回到 App（用户想插手/看进度时一步到位）
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        if (launch != null) {
            val pi = android.app.PendingIntent.getActivity(
                ctx, 0, launch,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            )
            builder.setContentIntent(pi)
        }
        builder.setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(R.string.notif_a11y_active_title))
            .setContentText(ctx.getString(R.string.notif_a11y_active_text, step, label))
            .setOnlyAlertOnce(true)      // 更新时不重复响铃
            .setAutoCancel(true)
            .setOngoing(false)
        nm.notify(A11Y_NOTIF_ID, builder.build())
        }
    }

    internal var lastScreenSig: Set<String>? = null

    internal var lastCompactSig: Set<String>? = null

    internal val screenExec = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "dsh-screen-ipc").apply { isDaemon = true }
    }

    internal val SCREEN_IPC_TIMEOUT_MS = 2_000L

    internal val SCREEN_IPC_COLD_RETRY_MS = 5_000L

    internal fun calib(ctx: Context, query: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance ?: return a11yGone()
        val q = parseQuery(query)
        if (q["clear"] in listOf("1", "true", "yes")) {
            Calib.clear(ctx)
            return 200 to JSONObject().put("ok", true).put("cleared", true).toString()
        }
        if (q["list"] in listOf("1", "true", "yes")) {
            val arr = JSONArray()
            Calib.load(ctx).forEach { (k, v) -> arr.put(v.toJson().put("key", k)) }
            return 200 to JSONObject().put("ok", true).put("params", arr).toString()
        }
        val tier = (q["tier"] ?: "tiny").lowercase()
        // 1) 真值：无障碍节点里带文字的项（中心点）
        val dump = withScreenTimeout("calib") { svc.dumpScreenJson("all", false) }
            ?: return 504 to """{"ok":false,"error":"node tree read timed out"}"""
        val root = runCatching { JSONObject(dump.getOrElse { throw it }) }.getOrNull()
            ?: return 500 to """{"ok":false,"error":"bad node dump"}"""
        val nodes = root.optJSONArray("nodes") ?: JSONArray()
        // 同一段文字在树里可能出现多次（整行容器 + 内部 TextView + 标题副本）：
        // 全部收下来，配对时按「宽度最接近」挑一个——容器的中心是行中心，不是文字中心，
        // 直接拿容器中心当真值会把 x 算歪（实测「双卡与移动网络」容器 539 / 文字 331）。
        val truth = HashMap<String, MutableList<FloatArray>>()
        for (i in 0 until nodes.length()) {
            val o = nodes.optJSONObject(i) ?: continue
            val t = normCalibText(o.optString("text").ifBlank { o.optString("desc") })
            if (t.length < 2) continue
            val b = o.optJSONArray("bounds") ?: continue
            if (b.length() < 4) continue
            // 记**左上角**而不是中心：整行容器的中心会随宽度漂，左上角却与内部文字对齐，
            // 对「a11y 给容器 / OCR 给文字」这种最常见的错位天然免疫（实测 Settings 行容器
            // 中心 539、文字中心 331，但左上角一致）。
            truth.getOrPut(t) { ArrayList() }.add(
                floatArrayOf(
                    b.optInt(0).toFloat(), b.optInt(1).toFloat(),
                    o.optInt("w").toFloat(), o.optInt("h").toFloat(),
                )
            )
        }
        // 2) 观测：同一屏的 OCR 文本块
        val shot = svc.screenshotBase64()
            ?: return 503 to """{"ok":false,"error":"screenshot_failed","reason":"accessibility screenshot unavailable"}"""
        val bw = shot.first
        val bh = shot.second
        val bytes = android.util.Base64.decode(shot.third, android.util.Base64.NO_WRAP)
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return 500 to """{"ok":false,"error":"bitmap_decode_failed"}"""
        val boxes = ArrayList<Box>()
        val usePp = PpOcr.ready(ctx) && PpOcr.isTierDeployed(ctx, tier)
        if (usePp) {
            val r = PpOcr.recognize(svc, bmp, tier, useCache = false, regionOnly = false)
            if (r.ok) r.blocks.forEach { boxes.add(Box(it.text, it.left, it.top, it.right, it.bottom, it.conf)) }
        }
        bmp.recycle()
        if (boxes.isEmpty()) {
            val ml = runCatching { ScreenOcr.recognize(svc, true, 0.5f) }.getOrNull()
            if (ml != null && !ml.unavailable) {
                ml.blocks.forEach { boxes.add(Box(it.text, it.left, it.top, it.right, it.bottom, 0.0)) }
            }
        }
        // 3) 配对：同一文本 → (a11y 中心, OCR 中心)
        val pairs = ArrayList<FloatArray>()
        val sample = JSONArray()
        var skippedShape = 0
        var skippedAmbiguous = 0
        // OCR 侧同一文本出现几次（>1 视为歧义，直接跳过）
        val ocrCount = HashMap<String, Int>()
        for (b in boxes) {
            val k = normCalibText(b.text)
            ocrCount[k] = (ocrCount[k] ?: 0) + 1
        }
        for (b in boxes) {
            val key = normCalibText(b.text)
            val cands = truth[key] ?: continue
            if ((ocrCount[key] ?: 0) > 1 || cands.size > 1) { skippedAmbiguous++; continue }
            val bw = (b.right - b.left).toFloat().coerceAtLeast(1f)
            val bh = (b.bottom - b.top).toFloat().coerceAtLeast(1f)
            // 只接受尺寸量级接近的候选：容器中心 ≠ 文字中心，混进来就是系统性偏差
            val a = cands
                .filter { c ->
                    // 只按宽度量级筛：Compose/整行容器的高度常带大片 padding（与文字框差 5-10 倍），
                    // 高度比不能当门槛，否则大部分配对会被误杀（实测 GitHub App 只剩 2 对）。
                    val rw = bw / c[2].coerceAtLeast(1f)
                    rw in 0.2f..5f
                }
                .minByOrNull { c ->
                    kotlin.math.abs(kotlin.math.ln(bw / c[2].coerceAtLeast(1f)))
                }
            if (a == null) { skippedShape++; continue }
            pairs.add(floatArrayOf(a[0], a[1], b.left.toFloat(), b.top.toFloat()))
            if (sample.length() < 10) {
                sample.put(JSONObject()
                    .put("text", b.text)
                    .put("a11y", JSONArray().put(a[0].toInt()).put(a[1].toInt()))
                    .put("ocr", JSONArray().put(b.left).put(b.top))
                    .put("wh", JSONArray().put(bw.toInt()).put(bh.toInt())))
            }
        }
        val engine = when {
            boxes.isEmpty() -> "none"
            usePp -> "ppocr6-$tier"
            else -> "mlkit"
        }
        if (pairs.size < 3) {
            return 200 to JSONObject()
                .put("ok", true).put("calibrated", false).put("engine", engine)
                .put("screenshot", JSONObject().put("w", bw).put("h", bh))
                .put("a11yTexts", truth.size).put("ocrBoxes", boxes.size).put("pairs", pairs.size)
                .put("skippedShape", skippedShape).put("skippedAmbiguous", skippedAmbiguous)
                .apply {
                    if (q["debug"] in listOf("1", "true", "yes")) {
                        put("a11ySample", JSONArray(truth.keys.take(8)))
                        put("ocrSample", JSONArray(boxes.take(8).map { it.text }))
                    }
                }
                .put("reason", "need >=3 pairs of (a11y text node, OCR box with the same text) on this screen; open a text-rich screen and retry")
                .toString()
        }
        val fit = Calib.fit(pairs) ?: return 500 to """{"ok":false,"error":"fit_failed"}"""
        val apply = q["apply"] in listOf("1", "true", "yes")
        // 只有「偏移够大」且「拟合后误差真的下降」才落库：
        //  - 差几个像素（实测 GitHub App 是 +5/-6px）多半是文字墨水与节点内边距之差，
        //    校准它等于给点击加偏置，不算坐标漂移；
        //  - 缩放/裁剪/横竖屏那种漂移是几十到几百像素，会命中下面的门槛。
        val meaningful = kotlin.math.abs(fit.sx - 1f) > 0.01f || kotlin.math.abs(fit.sy - 1f) > 0.01f ||
            kotlin.math.abs(fit.dx) > 8f || kotlin.math.abs(fit.dy) > 8f
        val worthwhile = meaningful && fit.maeAfter < fit.maeBefore * 0.9f && fit.maeAfter < 24f
        if (apply && worthwhile) {
            Calib.put(ctx, Calib.Params(bw, bh, fit.sx, fit.sy, fit.dx, fit.dy,
                fit.n, fit.maeBefore, fit.maeAfter, System.currentTimeMillis()))
        }
        return 200 to JSONObject()
            .put("ok", true).put("calibrated", true).put("engine", engine)
            .put("screenshot", JSONObject().put("w", bw).put("h", bh))
            .put("pairs", pairs.size).put("a11yTexts", truth.size).put("ocrBoxes", boxes.size)
            .put("skippedShape", skippedShape).put("skippedAmbiguous", skippedAmbiguous)
            .put("fit", JSONObject()
                .put("sx", fit.sx.toDouble()).put("sy", fit.sy.toDouble())
                .put("dx", fit.dx.toDouble()).put("dy", fit.dy.toDouble()))
            .put("maeBefore", fit.maeBefore.toDouble()).put("maeAfter", fit.maeAfter.toDouble())
            .put("applied", apply && worthwhile)
            .put("meaningful", meaningful)
            .apply { if (q["debug"] in listOf("1", "true", "yes")) put("sample", sample) }
            .apply {
                if (apply && !worthwhile) {
                    put(
                        "applySkipped",
                        if (!meaningful) {
                            "offset is within noise (|dx|,|dy| <= 8px and scale ~= 1): that is text-ink padding, not coordinate drift - nothing written"
                        } else {
                            "fit did not improve enough (maeAfter >= 0.9*maeBefore or > 24px); nothing written"
                        }
                    )
                }
            }
            .toString()
        }
    }

    internal fun annotateTapPoints(r: Pair<Int, String>): Pair<Int, String> = with(bridge) { runCatching {
        val scene = org.json.JSONObject(r.second)
        val key = listOf("items", "nodes", "list").firstOrNull { scene.optJSONArray(it) != null } ?: return r
        val arr = scene.optJSONArray(key) ?: return r
        val list = ArrayList<org.json.JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
        val shot = scene.optJSONObject("screenshot")
        val W = shot?.optInt("w", 1080) ?: 1080
        val H = shot?.optInt("h", 2376) ?: 2376
        fun b4(o: org.json.JSONObject): IntArray? {
            val b = o.optJSONArray("bounds") ?: return null
            if (b.length() < 4) return null
            return intArrayOf(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))
        }
        fun clampPt(x: Int, y: Int): Triple<Int, Int, Boolean> {
            val cx = x.coerceIn(2, W - 2); val cy = y.coerceIn(90, H - 40)
            return Triple(cx, cy, cx != x || cy != y)
        }
        // 文字节点 → 最近的可点祖先
        fun clickableAncestor(b: IntArray): org.json.JSONObject? {
            var best: org.json.JSONObject? = null; var bestArea = Int.MAX_VALUE
            for (o in list) {
                if (!o.optBoolean("clickable", false)) continue
                val cb = b4(o) ?: continue
                if (cb[0] <= b[0] && cb[1] <= b[1] && cb[2] >= b[2] && cb[3] >= b[3]) {
                    val area = (cb[2] - cb[0]) * (cb[3] - cb[1])
                    if (area < bestArea) { bestArea = area; best = o }
                }
            }
            return best
        }
        val screenArea = W.toLong() * H
        var cards = 0; var controls = 0; var tabs = 0; var offscreen = 0
        for (o in list) {
            val b = b4(o) ?: continue
            val w = b[2] - b[0]; val h = b[3] - b[1]
            val txt = (o.optString("text", "") + o.optString("desc", "")).trim()
            if (o.optBoolean("clickable", false)) {
                val isCard = w.toLong() * h >= screenArea * 8 / 100 || h >= 220
                val rawX = if (isCard) b[0] + (w * 45 / 100) else (b[0] + b[2]) / 2
                val pt = clampPt(rawX, (b[1] + b[3]) / 2)
                o.put("tapPoint", org.json.JSONArray(listOf(pt.first, pt.second)))
                    .put("tapSource", if (isCard) "card" else "control")
                    .put("role", if (isCard) "card" else "control")
                if (pt.third) { o.put("offscreen", true); offscreen++ }
                if (isCard) cards++ else controls++
            } else if (txt.isNotEmpty()) {
                val anc = clickableAncestor(b)
                val pt = clampPt((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
                if (anc != null) {
                    o.put("role", "control").put("tapSource", "text-ancestor")
                        .put("tapPoint", org.json.JSONArray(listOf(pt.first, pt.second)))
                    controls++
                } else {
                    // 无边框文字控件：边框被隐藏，a11y 也不标 clickable，只能靠特征判断可点
                    val hasArrow = txt.contains(">") || txt.contains("\u203a") || txt.contains("\u3009") || txt.contains("\u2192")
                    val core = txt.replace(Regex("[>\u203a\u3009\u2192\\s]+"), "")
                    val actionWords = DshAccessibilityService.instance?.let { actionWordsFromAssets(it) } ?: emptyList()
                    val byWord = actionWords.any { core == it || core.startsWith(it) || core.endsWith(it) }
                    val rowStrip = list.count { q ->
                        if (q === o) return@count false
                        val qb = b4(q) ?: return@count false
                        val qc = (qb[1] + qb[3]) / 2
                        val qt = (q.optString("text", "") + q.optString("desc", "")).replace(Regex("[>\u203a\u3009\u2192\\s]+"), "")
                        qt.isNotEmpty() && qt.length <= 8 && Math.abs(qc - (b[1] + b[3]) / 2) < 60 &&
                            !q.optBoolean("clickable", false)
                    } >= 2
                    val guess = core.isNotEmpty() && core.length <= 10 && (byWord || rowStrip) && !hasArrow   // 带 ">" 的是容器型（选集…>），不算无边框文字控件
                    if (guess) {
                        o.put("role", "action").put("tapSource", if (rowStrip && !byWord) "row-strip" else "text-keyword")
                            .put("tapPoint", org.json.JSONArray(listOf(pt.first, pt.second)))
                            .put("guess", true)
                            .put("note", "borderless text control: no clickable container; tap the text and verify state change")
                        tabs++
                    } else {
                        o.put("role", "text")
                            .put("note", "mobile: text is not clickable; tap the enclosing card/control")
                    }
                }
                if (pt.third) { o.put("offscreen", true); offscreen++ }
            }
        }
        list.sortBy { when (it.optString("role")) { "card" -> 0; "control" -> 1; "action" -> 2; "text" -> 3; else -> 4 } }
        val out = org.json.JSONArray(); list.forEach { out.put(it) }
        scene.put(key, out).put("cards", cards).put("controls", controls).put("tabs", tabs).put("offscreen", offscreen)
        200 to scene.toString()
    }.getOrElse { r }
    }

    internal fun mergeSceneIcons(ctx: Context, r: Pair<Int, String>): Pair<Int, String> {
        with(bridge) {
        if (r.first != 200) return r
        val t0 = System.currentTimeMillis()
        val det = yoloBench(ctx)
        if (det.first != 200) return r
        return runCatching {
            val detObj = org.json.JSONObject(det.second)
            val boxes = detObj.optJSONArray("boxes") ?: org.json.JSONArray()
            val scene = org.json.JSONObject(r.second)
            val itemsKey = listOf("items", "nodes", "list").firstOrNull { scene.optJSONArray(it) != null } ?: "nodes"
            val items = scene.optJSONArray(itemsKey) ?: org.json.JSONArray()
            val kept = org.json.JSONArray()
            for (i in 0 until items.length()) kept.put(items.get(i))
            fun centerInNode(cx: Int, cy: Int, nodes: org.json.JSONArray): Boolean {
                for (j in 0 until nodes.length()) {
                    val it = nodes.optJSONObject(j) ?: continue
                    val bd = it.optJSONArray("bounds") ?: continue
                    if (bd.length() < 4) continue
                    val nw = bd.getInt(2) - bd.getInt(0); val nh = bd.getInt(3) - bd.getInt(1)
                    if (nw.toLong() * nh > 1080L * 2376L * 35 / 100) continue   // 巨型容器不算覆盖
                    if (cx > bd.getInt(0) + 4 && cx < bd.getInt(2) - 4 &&
                        cy > bd.getInt(1) + 4 && cy < bd.getInt(3) - 4) return true
                }
                return false
            }
            var sizeOk = 0
            var added = 0
            val addedArr = org.json.JSONArray()
            for (i in 0 until boxes.length()) {
                val b = boxes.getJSONObject(i)
                val bx = b.getInt("x"); val by = b.getInt("y"); val bw = b.getInt("w"); val bh = b.getInt("h")
                val conf = b.optDouble("conf", 0.0)
                if (conf < 0.45) continue
                if (bw < 24 || bh < 24 || bw > 240 || bh > 240) continue
                val cx = bx + bw / 2; val cy = by + bh / 2
                if (cy < 90) continue                      // 状态栏
                sizeOk++
                if (centerInNode(cx, cy, items)) continue   // 已被 a11y/OCR 覆盖
                if (centerInNode(cx, cy, addedArr)) continue
                addedArr.put(org.json.JSONObject().put("bounds", org.json.JSONArray(listOf(bx, by, bx + bw, by + bh))))
                kept.put(org.json.JSONObject().put("desc", "icon").put("source", "icon")
                    .put("clickable", true).put("icon", true)
                    .put("bounds", org.json.JSONArray(listOf(bx, by, bx + bw, by + bh)))
                    .put("center", org.json.JSONArray(listOf(cx, cy)))
                    .put("conf", conf))
                added++
            }
            scene.put(itemsKey, kept).put("itemsKey", itemsKey)
                .put("iconAdded", added).put("iconMs", System.currentTimeMillis() - t0)
            app.dsh.mobile.DshLog.d("scene", "icon merge raw=${boxes.length()} sizeOk=$sizeOk added=$added total=${kept.length()} ms=${System.currentTimeMillis() - t0}")
            200 to scene.toString()
        }.getOrElse {
            app.dsh.mobile.DshLog.e("scene", "icon merge failed: ${it.message}", it)
            r
        }
        }
    }

    internal var iconSession: ai.onnxruntime.OrtSession? = null

    internal var iconLoadMs = 0L

    internal fun yoloBench(ctx: Context): Pair<Int, String> = with(bridge) { try { yoloBench0(ctx) } catch (t: Throwable) {
        app.dsh.mobile.DshLog.e("yolo", "bench failed: ${t.javaClass.simpleName}: ${t.message}", t)
        500 to org.json.JSONObject().put("ok", false).put("error", t.javaClass.simpleName)
            .put("message", t.message ?: "").put("stack", t.stackTraceToString().lineSequence().take(12).joinToString(" | ")).toString()
    }
    }

    internal fun yoloBench0(ctx: Context): Pair<Int, String> {
        with(bridge) {
        val model = java.io.File(ctx.cacheDir, "omni_icon_640_int8.onnx")
        if (!model.exists()) runCatching {
            ctx.assets.open("models/omni_icon.onnx").use { i -> model.outputStream().use { o -> i.copyTo(o) } }
        }
        if (!model.exists()) return 501 to """{"ok":false,"error":"model_missing"}"""
        val svc = DshAccessibilityService.instance ?: return a11yGone()
        val shot = svc.screenshotArgb() ?: return 500 to """{"ok":false,"error":"screenshot_failed"}"""
        val bmp = android.graphics.Bitmap.createBitmap(shot.first, shot.second, shot.third, android.graphics.Bitmap.Config.ARGB_8888)
        val S = 640
        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, S, S, true)
        val px = IntArray(S * S); scaled.getPixels(px, 0, S, 0, 0, S, S)
        val buf = java.nio.FloatBuffer.allocate(3 * S * S)
        for (c in 0 until 3) for (i in px.indices) {
            val p = px[i]
            val v = when (c) { 0 -> (p shr 16) and 0xFF; 1 -> (p shr 8) and 0xFF; else -> p and 0xFF }
            buf.put(c * S * S + i, v / 255f)
        }
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val t0 = System.currentTimeMillis()
        val sess = iconSession ?: env.createSession(model.absolutePath, ai.onnxruntime.OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
        }).also { iconSession = it }
        val loadMs = if (iconLoadMs == 0L) System.currentTimeMillis() - t0 else 0L
        if (iconLoadMs == 0L) iconLoadMs = System.currentTimeMillis() - t0
        val tensor = ai.onnxruntime.OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, S.toLong(), S.toLong()))
        val inName = sess.inputNames.iterator().next()
        var prev: ai.onnxruntime.OrtSession.Result? = null
        val times = ArrayList<Long>()
        var last: ai.onnxruntime.OrtSession.Result? = null
        repeat(3) {
            val t = System.currentTimeMillis()
            prev?.close()
            val r = sess.run(mapOf(inName to tensor))
            last = r; prev = r
            times.add(System.currentTimeMillis() - t)
        }
        val ot = last!!.get(0) as ai.onnxruntime.OnnxTensor
        val shape = ot.info.shape
        val fb = ot.floatBuffer
        val n = if (shape.size == 3 && shape[1] == 5L) shape[2].toInt() else if (shape.size == 3) shape[1].toInt() else 0
        val chFirst = shape.size == 3 && shape[1] == 5L
        val raw = ArrayList<FloatArray>()
        for (k in 0 until n) {
            val cx: Float; val cy: Float; val w: Float; val h: Float; val cf: Float
            if (chFirst) {
                cx = fb.get(k); cy = fb.get(n + k); w = fb.get(2 * n + k); h = fb.get(3 * n + k); cf = fb.get(4 * n + k)
            } else {
                val o5 = k * 5
                cx = fb.get(o5); cy = fb.get(o5 + 1); w = fb.get(o5 + 2); h = fb.get(o5 + 3); cf = fb.get(o5 + 4)
            }
            if (cf > 0.2f) raw.add(floatArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, cf))
        }
        val sw = S.toFloat()
        // NMS：同类单类，IoU>0.5 去重
        val nms = ArrayList<FloatArray>();
        for (cand in raw.sortedByDescending { it[4] }) {
            var dup = false
            for (k in nms) {
                val ix = maxOf(0f, minOf(cand[2], k[2]) - maxOf(cand[0], k[0]))
                val iy = maxOf(0f, minOf(cand[3], k[3]) - maxOf(cand[1], k[1]))
                val inter = ix * iy
                val ua = (cand[2] - cand[0]) * (cand[3] - cand[1]) + (k[2] - k[0]) * (k[3] - k[1]) - inter
                if (ua > 0 && inter / ua > 0.45f) { dup = true; break }
            }
            if (!dup) nms.add(cand)
        }
        val kept = nms.filter { it[2] - it[0] < sw * 0.95f && (it[2] - it[0]) / (it[3] - it[1]) < 4f && (it[2] - it[0]) * (it[3] - it[1]) < S * S * 0.08f }
        last?.close(); tensor.close()
        times.sort()
        val arrAll = org.json.JSONArray()
        kept.sortedByDescending { it[4] }.forEach { b ->
            arrAll.put(org.json.JSONObject().put("x", (b[0] / S * bmp.width).toInt()).put("y", (b[1] / S * bmp.height).toInt())
                .put("w", ((b[2] - b[0]) / S * bmp.width).toInt()).put("h", ((b[3] - b[1]) / S * bmp.height).toInt())
                .put("conf", (b[4] * 100).toInt() / 100.0))
        }
        val arr = org.json.JSONArray()
        kept.sortedByDescending { it[4] }.take(6).forEach { b ->
            arr.put(org.json.JSONObject().put("x", (b[0] / S * bmp.width).toInt()).put("y", (b[1] / S * bmp.height).toInt())
                .put("w", ((b[2] - b[0]) / S * bmp.width).toInt()).put("h", ((b[3] - b[1]) / S * bmp.height).toInt())
                .put("conf", (b[4] * 100).toInt() / 100.0))
        }
        val js = org.json.JSONObject().put("ok", true)
            .put("screen", "${bmp.width}x${bmp.height}").put("modelMb", model.length() / 1024 / 1024)
            .put("loadMs", loadMs).put("medianMs", times[1]).put("allMs", org.json.JSONArray(times))
            .put("rawBoxes", raw.size).put("kept", kept.size).put("top", arr).put("boxes", arrAll).toString()
        app.dsh.mobile.DshLog.d("yolo", js)
        return 200 to js
        }
    }

    internal fun normCalibText(s: String): String {
        with(bridge) {
        val sb = StringBuilder(s.length)
        for (ch in s) if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
        return sb.toString()
        }
    }

    internal fun applyCalibToBoxes(ctx: Context, w: Int, h: Int, boxes: MutableList<Box>) {
        with(bridge) {
        if (boxes.isEmpty()) return
        val rects = boxes.map { floatArrayOf(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
            .toMutableList()
        if (!Calib.applyTo(ctx, w, h, rects)) return
        for (i in boxes.indices) {
            val b = boxes[i]
            val r = rects[i]
            boxes[i] = Box(b.text, r[0].toInt(), r[1].toInt(), r[2].toInt(), r[3].toInt(), b.conf)
        }
        }
    }

    internal fun screen(query: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        return try {
            val params = parseQuery(query)
            val filter = when (params["filter"]) {
                null, "", "all" -> "all"
                "clickable", "editable" -> params["filter"]!!
                else -> return 400 to """{"ok":false,"error":"unknown filter '${params["filter"]}'; valid: all|clickable|editable"}"""
            }
            // 紧凑为默认；显式 format=json 才走完整 JSON
            val wantJson = params["format"] == "json"
            when {
                params["xml"] == "1" -> {
                    val r = withScreenTimeout("screenXml") { svc.screenXml() }
                        ?: return 504 to """{"ok":false,"error":"accessibility node tree read timed out (service may be stuck)"}"""
                    r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
                    val xml = r.getOrThrow()
                    200 to """{"ok":true,"xml":${JSONObject.quote(xml)}}"""
                }
                !wantJson -> {
                    val r = withScreenTimeout("dumpScreenCompact") {
                        svc.dumpScreenCompact(filter)
                    } ?: return 504 to """{"ok":false,"error":"accessibility node tree read timed out (service may be stuck)"}"""
                    val text = r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
                    // ⚡ 紧凑模式也支持 diff（v1.2.58）：只回「与上次不同」的行。
                    // 连续观察同一界面时（等加载、确认状态），重复行是纯 token 浪费。
                    // 按行做集合差；保留首行（屏幕尺寸/节点数）。
                    if (params["diff"] == "1") {
                        val lines = text.lines()
                        val header = lines.firstOrNull().orEmpty()
                        val body = lines.drop(1).filter { it.isNotBlank() }
                        val prev = lastCompactSig
                        val changed = if (prev == null) body else body.filter { it !in prev }
                        lastCompactSig = body.toSet()
                        val out = buildString {
                            append(header)
                            if (prev != null) append(" (diff ${changed.size}/${body.size})")
                            append('\n')
                            changed.forEach { append(it).append('\n') }
                        }
                        return 200 to out
                    }
                    // 紧凑格式直接用 text/plain 返回，省掉 JSON 转义的额外膨胀
                    200 to text
                }
                else -> {
                    val r = withScreenTimeout("dumpScreenJson") { svc.dumpScreenJson(filter) }
                        ?: return 504 to """{"ok":false,"error":"accessibility node tree read timed out (service may be stuck)"}"""
                    r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
                    val json = JSONObject(r.getOrThrow())
                    if (params["diff"] == "1") {
                        val nodes = json.getJSONArray("nodes")
                        val sig = HashSet<String>()
                        val changed = org.json.JSONArray()
                        for (i in 0 until nodes.length()) {
                            val n = nodes.getJSONObject(i)
                            val key = n.optString("text") + "|" + n.optString("desc") + "|" +
                                n.optInt("x") + "," + n.optInt("y")
                            sig.add(key)
                            val prev = lastScreenSig
                            if (prev == null || key !in prev) changed.put(n)   // 新出现/位置变化
                        }
                        lastScreenSig = sig
                        json.put("nodes", changed)
                        json.put("diff", true)
                    }
                    200 to json.toString()
                }
            }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun ocr(ctx: Context, query: String): Pair<Int, String> {
        with(bridge) {
        val params = parseQuery(query)
        val tier = (params["tier"] ?: "tiny").lowercase()
        // v1.9.6（P1-4 收尾）：medium 档按需下载；也可查状态。均不需要无障碍。
        if (params["engine"]?.lowercase() == "ppocr6" && params["download"] != null) {
            val rawDownload = params["download"].orEmpty()
            val want = rawDownload.takeIf { it.isNotBlank() && it != "1" } ?: tier
            val err = PpOcr.ensureModels(ctx, want)
            return if (err == null) {
                200 to JSONObject().put("ok", true).put("engine", "ppocr6").put("tier", want)
                    .put("deployed", PpOcr.isTierDeployed(ctx, want))
                    .put("sizes", JSONObject(PpOcr.tierSizes(ctx, want).toMap())).toString()
            } else {
                503 to JSONObject().put("ok", false).put("engine", "ppocr6").put("tier", want)
                    .put("unavailable", true).put("reason", err).toString()
            }
        }
        if (params["engine"]?.lowercase() == "ppocr6" && params["models"] == "1") {
            val list = JSONArray()
            listOf("tiny", "medium").forEach { t ->
                list.put(JSONObject().put("tier", t).put("deployed", PpOcr.isTierDeployed(ctx, t))
                    .put("sizes", JSONObject(PpOcr.tierSizes(ctx, t).toMap())))
            }
            return 200 to JSONObject().put("ok", true).put("tiers", list).toString()
        }
        // v1.9.4：装 OCR 依赖不需要无障碍（只需 pkg/context），放在 svc 判空之前。
        if (params["engine"]?.lowercase() == "ppocr6" && params["ensure"] in listOf("1", "true", "yes")) {
            val err = PpOcr.ensureDeps(ctx)
            if (err != null) {
                return 503 to JSONObject().put("ok", false).put("engine", "ppocr6")
                    .put("unavailable", true).put("reason", err).toString()
            }
            // v1.9.5：纯安装请求不需要无障碍；装完直接回执，不再走截图
            return 200 to JSONObject()
                .put("ok", true)
                .put("engine", "ppocr6")
                .put("deps", true)
                .put("deployed", PpOcr.deployed(ctx))
                .put("realBinding", PpOcr.realBindingReady(ctx))
                .put("hint", "deps installed; OCR itself still needs the accessibility screenshot (or call /a11y/enable)")
                .toString()
        }
        val svc = DshAccessibilityService.instance
        if (svc == null) {
            // v1.9.6：无障碍不可用时，尝试 root/Shizuku 的 screencap 回退
            if (params["engine"]?.lowercase() == "ppocr6") {
                val shot = ShotFallback.capture(ctx)
                if (shot != null) {
                    val r = PpOcr.recognizeFile(ctx, shot, tier)
                    shot.delete()
                    if (r.ok) {
                        val arr = JSONArray()
                        r.blocks.forEach { b ->
                            arr.put(JSONObject().apply {
                                put("text", b.text); put("x", b.cx); put("y", b.cy)
                                put("left", b.left); put("top", b.top); put("right", b.right); put("bottom", b.bottom)
                                put("conf", b.conf)
                            })
                        }
                        return 200 to JSONObject().put("ok", true).put("engine", "ppocr6-$tier")
                            .put("via", "privileged-screencap").put("costMs", r.costMs)
                            .put("lines", r.blocks.size).put("blocks", arr).toString()
                    }
                    return 503 to JSONObject().put("ok", false).put("engine", "ppocr6-$tier")
                        .put("unavailable", true).put("reason", r.reason).toString()
                }
            }
            // 条件依赖：默认靠无障碍截图；engine=ppocr6 + Shizuku/root 时上一段已尝试
            // privileged screencap（ShotFallback）。走到这里说明两条路都不通。
            return 503 to JSONObject()
                .put("ok", false)
                .put("error", "a11y_off")
                .put("code", "a11y_off")
                .put("reason", "OCR needs a screen capture: the default path uses the accessibility screenshot API, and the privileged screencap fallback (environment=ppocr6 + Shizuku/root) is not available either")
                .put("how_to_fix", "enable 'DSH Mobile Helper (optional)' in Settings > Accessibility (POST /a11y/enable), or run with engine=ppocr6 and Shizuku/root so screencap can be used")
                .put("retryable", true)
                .toString()
        }
        // v1.8.2（提案 P1-4）：PP-OCRv6 tiny 本地兜底（Termux python+onnxruntime）
        if (params["engine"]?.lowercase() == "ppocr6") {
            if (!PpOcr.ready(ctx)) {
                return 503 to JSONObject().put("ok", false).put("engine", "ppocr6")
                    .put("unavailable", true)
                    .put("reason", "ppocr6 deps missing: run /ocr?engine=ppocr6&ensure=1 (installs python + onnxruntime)")
                    .put("deployed", PpOcr.deployed(svc)).toString()
            }
            val shot = svc.screenshotBase64()
                ?: return 503 to """{"ok":false,"error":"screenshot_failed","reason":"accessibility screenshot unavailable"}"""
            val bytes = android.util.Base64.decode(shot.third, android.util.Base64.NO_WRAP)
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return 500 to """{"ok":false,"error":"bitmap_decode_failed"}"""
            val useCache = params["cache"] != "0"
            val regionOnly = params["region"] in listOf("1", "true", "yes")
            val ifChanged = params["ifChanged"] in listOf("1", "true", "yes")
            val r = PpOcr.recognize(svc, bmp, tier, useCache, regionOnly, ifChanged)
            // v2.5.28：把 OCR 的截图像素坐标校正到显示坐标（没有校准参数时恒等）
            val mapped = r.blocks
                .map { Box(it.text, it.left, it.top, it.right, it.bottom, it.conf) }
                .toMutableList()
            applyCalibToBoxes(ctx, shot.first, shot.second, mapped)
            bmp.recycle()
            val arr = JSONArray()
            mapped.forEach { b ->
                arr.put(JSONObject().apply {
                    put("text", b.text); put("x", b.cx); put("y", b.cy)
                    put("left", b.left); put("top", b.top); put("right", b.right); put("bottom", b.bottom)
                    put("conf", b.conf)
                })
            }
            return if (r.ok) 200 to JSONObject().put("ok", true).put("engine", "ppocr6-$tier")
                .put("costMs", r.costMs).put("cached", r.cached).put("changed", r.changed)
                .apply { r.region?.let { rg -> put("region", JSONArray().put(rg.left).put(rg.top).put(rg.right).put(rg.bottom)) } }
                .put("lines", r.blocks.size).put("blocks", arr).toString()
            else 503 to JSONObject().put("ok", false).put("engine", "ppocr6-tiny")
                .put("unavailable", r.unavailable).put("reason", r.reason).toString()
        }
        val force = params["force"] in listOf("1", "true", "yes")
        val scale = params["scale"]?.toFloatOrNull() ?: 0.5f
        val r = app.dsh.mobile.ScreenOcr.recognize(svc, force, scale)
        // v2.5.28：ML Kit 结果是显示坐标；仍按显示尺寸查一次校准（通常无参数 → 恒等）
        val dm = svc.resources.displayMetrics
        val mlBoxes = r.blocks
            .map { Box(it.text, it.left, it.top, it.right, it.bottom, 0.0) }
            .toMutableList()
        applyCalibToBoxes(ctx, dm.widthPixels, dm.heightPixels, mlBoxes)
        val arr = JSONArray()
        for (b in mlBoxes) {
            arr.put(JSONObject().apply {
                put("text", b.text)
                put("x", b.cx)
                put("y", b.cy)
                put("left", b.left)
                put("top", b.top)
                put("right", b.right)
                put("bottom", b.bottom)
            })
        }
        return 200 to JSONObject().apply {
            put("ok", !r.unavailable)
            put("unavailable", r.unavailable)
            put("cached", r.cached)
            put("costMs", r.costMs.toInt())
            put("lines", r.blocks.size)
            put("blocks", arr)
        }.toString()
        }
    }

    internal fun pick(body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val target = obj.optString("target").trim()
        if (target.isBlank()) return 400 to """{"ok":false,"error":"field 'target' required"}"""
        val wantTap = obj.optBoolean("tap", false)
        val clickableOnly = obj.optBoolean("clickableOnly", true)

        val dumpRes = withScreenTimeout("pick.dump") { svc.dumpScreenCompact("all") }
            ?: return 504 to """{"ok":false,"error":"screen read timed out"}"""
        val dump = dumpRes.getOrElse {
            return 500 to """{"ok":false,"error":"${it.message}"}"""
        }
        val t0 = System.currentTimeMillis()
        val r = FastPicker.pick(dump, target, clickableOnly)
        val costMs = System.currentTimeMillis() - t0

        val c = r.candidate
        if (c == null) {
            // 本地判断不了：把候选列表一并给出，方便 AI 一轮内决定（减少往返）
            // 候选 = 有文本的节点（可点击容器文本是 "-"，见 FastPicker 的说明）
            val pool = FastPicker.parseCompact(dump)
                .filter { it.text.isNotBlank() && it.text != "-" }
                .take(24)
                .joinToString(",") { """{"i":${it.index},"t":${JSONObject.quote(it.text)},"x":${it.x},"y":${it.y}}""" }
            return 200 to """{"ok":true,"via":"none","costMs":$costMs,"candidates":[$pool]}"""
        }
        val tapped = if (wantTap) {
            svc.dispatchTapRect(android.graphics.Rect(c.x, c.y, c.x, c.y))
        } else false
        return 200 to buildString {
            append("""{"ok":true,"via":"${r.via}","score":${"%.3f".format(r.score)},""")
            append(""""text":${JSONObject.quote(c.text)},"x":${c.x},"y":${c.y},""")
            append(""""costMs":$costMs,"tapped":$tapped}""")
        }
        }
    }

    internal fun frameDiff(): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val r = withScreenTimeout("frame-diff", 5_000) {
            app.dsh.mobile.Framer.captureAndDiff(svc)
        }?.getOrNull() ?: return 504 to """{"ok":false,"error":"frame capture timed out"}"""
        if (r.unavailable) {
            return 400 to """{"ok":false,"error":"screenshot unavailable (needs API 30+ and screenshot capability; toggle the service off/on)"}"""
        }
        val regions = r.hotRegions.joinToString(",") {
            """{"nx":${"%.3f".format(it.nx)},"ny":${"%.3f".format(it.ny)},"ratio":${"%.3f".format(it.ratio)}}"""
        }
        return 200 to buildString {
            append("""{"ok":true,"changed":${r.changed},"changedRatio":${"%.4f".format(r.changedRatio)},""")
            append(""""costMs":${r.costMs},"isFirst":${r.isFirst},""")
            append(""""cx":${"%.3f".format(r.cx)},"cy":${"%.3f".format(r.cy)},""")
            append(""""hotRegions":[$regions]}""")
        }
        }
    }

    internal fun frameWait(body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val timeoutMs = obj.optLong("timeoutMs", 5000L).coerceIn(300L, 30_000L)
        val intervalMs = obj.optLong("intervalMs", 250L).coerceIn(100L, 2000L)
        val start = System.currentTimeMillis()
        var polls = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            polls++
            val r = runCatching { app.dsh.mobile.Framer.captureAndDiff(svc) }.getOrNull()
            if (r != null && r.changed) {
                return 200 to """{"ok":true,"changed":true,"costMs":${System.currentTimeMillis() - start},"polls":$polls,"changedRatio":${"%.4f".format(r.changedRatio)}}"""
            }
            Thread.sleep(intervalMs)
        }
        return 200 to """{"ok":true,"changed":false,"timedOut":true,"polls":$polls,"waitedMs":$timeoutMs}"""
        }
    }

    internal fun screenChanged(): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val text = runCatching { svc.changedSummary() }.getOrDefault("")
        return if (text.isBlank()) {
            200 to """{"ok":true,"changed":false,"events":[]}"""
        } else {
            val arr = text.lineSequence().joinToString(",") { JSONObject.quote(it) }
            200 to """{"ok":true,"changed":true,"events":[$arr]}"""
        }
        }
    }

    internal fun appScreenshot(query: String): Pair<Int, String> {
        with(bridge) {
        val ctx = shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        if (!AppShot.hasWindow()) {
            return 503 to """{"ok":false,"error":"app foreground window not available; open the DSH Mobile UI (main activity) first"}"""
        }
        val maxDim = parseQuery(query)["max"]?.toIntOrNull()?.coerceIn(256, 4096) ?: 1280
        val shot = AppShot.capture(ctx, maxDim)
            ?: return 500 to """{"ok":false,"error":"app screenshot failed (PixelCopy could not capture the window)"}"""
        return 200 to """{"ok":true,"path":"${shot.first.absolutePath}","width":${shot.second},"height":${shot.third},"hint":"read it with the read_image tool"}"""
        }
    }

    internal fun screenshot(query: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val caps = svc.serviceInfo?.capabilities ?: 0
        if (Build.VERSION.SDK_INT >= 30 &&
            caps and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0
        ) {
            return 400 to """{"ok":false,"error":"accessibility service lacks screenshot capability — toggle the service off/on in system settings to grant it"}"""
        }
        return try {
            val shot = svc.screenshotBase64()
                ?: return 400 to """{"ok":false,"error":"screenshot failed (timeout or unsupported)"}"""
            // ?max=N：把最长边缩到 N 像素（省 base64 体积 + 省上下文）
            val maxDim = parseQuery(query)["max"]?.toIntOrNull()?.coerceIn(256, 4096)
            var w = shot.first; var h = shot.second; var b64 = shot.third
            if (maxDim != null && maxOf(w, h) > maxDim) {
                val scale = maxDim.toFloat() / maxOf(w, h)
                w = (w * scale).toInt(); h = (h * scale).toInt()
                val srcBmp = android.graphics.BitmapFactory.decodeByteArray(
                    android.util.Base64.decode(b64, android.util.Base64.NO_WRAP), 0,
                    android.util.Base64.decode(b64, android.util.Base64.NO_WRAP).size)
                val bmp = android.graphics.Bitmap.createScaledBitmap(srcBmp, w, h, true)
                srcBmp.recycle()
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 85, baos)
                b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                bmp.recycle()
            }
            // ?save=1（v1.2.138）：保存到文件**只回路径**——base64 极吃上下文
            // （1080p PNG ≈ 100-200KB），AI 用 read_image 按需读图更省。
            // 顺带清理旧图（保留最近 12 张 shot-*），避免目录无限膨胀。
            if (parseQuery(query)["save"] == "1") {
                val c = shotCtx
                if (c != null) {
                    val dir = java.io.File(c.filesDir, "screens").apply { mkdirs() }
                    val f = java.io.File(dir, "shot-${System.currentTimeMillis()}.png")
                    f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                    dir.listFiles { file -> file.name.startsWith("shot-") }
                        ?.sortedBy { it.lastModified() }?.dropLast(12)?.forEach { it.delete() }
                    return 200 to """{"ok":true,"path":"${f.absolutePath}","width":$w,"height":$h,"hint":"read it with the read_image tool"}"""
                }
            }
            return 200 to """{"ok":true,"format":"png","width":$w,"height":$h,"base64":"$b64"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun diffScreen(): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val (added, removed) = svc.diffScreen()
        return 200 to JSONObject().apply {
            put("ok", true)
            put("changed", added.isNotEmpty() || removed.isNotEmpty())
            put("added", org.json.JSONArray(added.toList()))
            put("removed", org.json.JSONArray(removed.toList()))
        }.toString()
        }
    }

    internal fun captureStepShot(): String? = with(bridge) { runCatching {
        val c = shotCtx ?: return@runCatching null
        val svc = DshAccessibilityService.instance ?: return@runCatching null
        val shot = svc.screenshotBase64(1500L) ?: return@runCatching null
        val dir = java.io.File(c.filesDir, "screens").apply { mkdirs() }
        val f = java.io.File(dir, "step-${shotSeq.incrementAndGet()}.png")
        f.writeBytes(android.util.Base64.decode(shot.third, android.util.Base64.DEFAULT))
        dir.listFiles { file -> file.name.startsWith("step-") }
            ?.sortedBy { it.lastModified() }
            ?.dropLast(KEEP_STEP_SHOTS)
            ?.forEach { it.delete() }
        f.absolutePath
    }.getOrNull()
    }

    internal fun scene(ctx: Context, query: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val params = parseQuery(query)
        val wantOcr = params["ocr"] !in listOf("0", "false", "no")
        val t0 = System.currentTimeMillis()
        val baseCsv = withScreenTimeout("sceneA11y") { svc.dumpScreenJson("all", ocrFallback = false) }
            ?: return 504 to """{"ok":false,"error":"accessibility node tree read timed out"}"""
        val base = runCatching { JSONObject(baseCsv.getOrThrow()) }.getOrElse {
            return 500 to """{"ok":false,"error":"bad a11y json: ${it.message}"}"""
        }
        val a11yMs = System.currentTimeMillis() - t0
        val a11yNodes = base.optJSONArray("nodes") ?: JSONArray()
        val out = JSONArray()
        val clickables = ArrayList<JSONObject>()
        // a11y 节点树被限制（空或极少）时，直接用预取的 OCR 结果兜底（同一张截图、同一套坐标）
        var sceneFallback: String? = null
        if (a11yNodes.length() < 3) {
            val p = ocrPrefetch
            if (p != null && System.currentTimeMillis() - p.ts < 5000) {
                val seen = HashSet<String>()
                for (i in 0 until p.nodes.length()) {
                    val n = p.nodes.optJSONObject(i) ?: continue
                    val t = n.optString("text")
                    if (t.isBlank() || !seen.add(t)) continue
                    out.put(n)
                }
                if (out.length() > 0) sceneFallback = "ocr-prefetch"
            }
        }
        val verbose = params["verbose"] in listOf("1", "true", "yes")
        for (i in 0 until a11yNodes.length()) {
            val o = a11yNodes.optJSONObject(i) ?: continue
            // v2.4.1（用户反馈 /scene 超 20KB 被截断成非法 JSON）：
            // 默认只输出 agent 真正需要的字段；cls/rid/desc 等空字段与冗余字段省略，
            // verbose=1 才回完整 a11y 节点。100 节点可从 ~35KB 降到 ~12KB。
            val n = JSONObject()
            n.put("index", o.optInt("index"))
            n.put("text", o.optString("text"))
            o.optString("desc").takeIf { it.isNotBlank() }?.let { n.put("desc", it) }
            n.put("bounds", o.optJSONArray("bounds"))
            n.put("center", o.optJSONArray("center"))
            n.put("source", "a11y")
            if (o.optBoolean("clickable")) n.put("clickable", true)
            if (o.optBoolean("editable")) n.put("editable", true)
            if (o.optBoolean("scrollable")) n.put("scrollable", true)
            if (o.optBoolean("checkable")) {
                n.put("checkable", true); n.put("checked", o.optBoolean("checked"))
            }
            o.optString("listId").takeIf { it.isNotBlank() }?.let { n.put("listId", it) }
            if (o.has("itemIndex")) n.put("itemIndex", o.optInt("itemIndex"))
            if (verbose) {
                n.put("x", o.optInt("x")); n.put("y", o.optInt("y"))
                n.put("w", o.optInt("w")); n.put("h", o.optInt("h"))
                n.put("cls", o.optString("cls")); n.put("rid", o.optString("rid"))
                n.put("hint", o.optString("hint"))
            }
            n.put("centerX", o.optJSONArray("center")?.optInt(0) ?: o.optInt("x"))
            n.put("centerY", o.optJSONArray("center")?.optInt(1) ?: o.optInt("y"))
            out.put(n)
            if (o.optBoolean("clickable")) clickables.add(n)
        }

        var ocrEngine = "none"
        var ocrMs = 0L
        var ocrCached = false
        var ocrChanged = true
        var ocrRegion: JSONArray? = null
        if (wantOcr) {
            val t1 = System.currentTimeMillis()
            val boxes = ArrayList<Box>()
            val enginePref = params["engine"]?.lowercase()
            val tier = (params["tier"] ?: "tiny").lowercase()
            // v2.1.6：默认走 PP-OCRv6 的区域增量（region=1），agent 无感享受增量；
            // engine=mlkit 可强制 ML Kit，region=0 关闭增量。
            val usePp = enginePref == "ppocr6" ||
                (enginePref == null && PpOcr.ready(ctx) && PpOcr.isTierDeployed(ctx, tier))
            if (usePp) {
                val shot = runCatching { svc.screenshotBase64() }.getOrNull()
                if (shot != null) {
                    val bytes = android.util.Base64.decode(shot.third, android.util.Base64.NO_WRAP)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        val regionOnly = params["region"] !in listOf("0", "false", "no")
                        val r = PpOcr.recognize(
                            ctx, bmp, tier,
                            useCache = params["cache"] != "0",
                            regionOnly = regionOnly,
                            ifChanged = params["ifChanged"] in listOf("1", "true", "yes"),
                        )
                        bmp.recycle()
                        if (r.ok) {
                            ocrEngine = "ppocr6-$tier"
                            ocrCached = r.cached
                            ocrChanged = r.changed
                            r.region?.let { rg ->
                                ocrRegion = JSONArray().put(rg.left).put(rg.top).put(rg.right).put(rg.bottom)
                            }
                            r.blocks.forEach { boxes.add(Box(it.text, it.left, it.top, it.right, it.bottom, it.conf)) }
                            applyCalibToBoxes(ctx, shot.first, shot.second, boxes)
                        }
                    }
                }
            }
            if (boxes.isEmpty() && enginePref != "ppocr6") {
                val ml = runCatching { ScreenOcr.recognize(svc, false, 0.5f) }.getOrNull()
                if (ml != null && !ml.unavailable && ml.blocks.isNotEmpty()) {
                    ocrEngine = "mlkit"
                    ml.blocks.forEach { boxes.add(Box(it.text, it.left, it.top, it.right, it.bottom, 0.0)) }
                }
            }
            ocrMs = System.currentTimeMillis() - t1
            mergeOcrLines(boxes).forEach { line ->
                val node = JSONObject().apply {
                    put("text", line.text)
                    put("x", line.cx); put("y", line.cy)
                    put("bounds", JSONArray().put(line.left).put(line.top).put(line.right).put(line.bottom))
                    put("center", JSONArray().put(line.cx).put(line.cy))
                    put("source", "ocr")
                    put("clickable", false)
                }
                // 落在某个可点 a11y 节点 bounds 内 → 继承 clickable/id（提案 hint:near-clickable）
                val host = clickables.firstOrNull { a ->
                    val b = a.optJSONArray("bounds") ?: return@firstOrNull false
                    line.cx in b.optInt(0)..b.optInt(2) && line.cy in b.optInt(1)..b.optInt(3)
                }
                if (host != null) {
                    node.put("clickable", true)
                    node.put("hint", "near-clickable")
                    node.put("a11yIndex", host.optInt("index"))
                    host.optJSONArray("center")?.let { node.put("a11yCenter", it) }
                } else {
                    node.put("hint", "custom control (no a11y node); tap the center if it is a button")
                }
                out.put(node)
            }
        }

        val perf = JSONObject().put("a11y", a11yMs).put("ocr", ocrMs)
        // v2.4.1：分页返回，任何一页都远低于 20KB；nextOffset=null 表示结束。
        val total = out.length()
        var limit = (params["limit"]?.toIntOrNull() ?: 80).coerceIn(1, 1000)
        val offset = (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        fun buildPage(n: Int): JSONArray {
            val p = JSONArray()
            var k = offset
            while (k < total && k < offset + n) { p.put(out.get(k)); k++ }
            return p
        }
        var page = buildPage(limit)
        // 硬保证单页 <18KB（工具层 20KB 截断），超了就自动缩页；不返回非法 JSON。
        while (page.toString().length > 18_000 && limit > 1) {
            limit = (limit * 3) / 4
            page = buildPage(limit)
        }
        return 200 to JSONObject().apply {
            put("ok", true)
            put("nodes", page)
            if (DshAccessibilityService.instance?.systemUiShadeVisible() == true) {
                put("hint", "notification shade / system UI: coordinate gestures are often swallowed; use tap-desc or tap-id (node ACTION_CLICK) and key expand-notifications|dismiss-shade; --shell is the last resort")
            } else {
                DshAccessibilityService.instance?.staleFullscreenNode()?.let {
                    put("staleFullScreenNode", it)
                    put("spaceHint", "a stale full-screen clickable node ($it) is attached: this window's a11y bounds may be out of sync with the rendered UI, so centres derived from nodes can be off by an inset — measure from a screenshot, or tap node-first with /tap {\"x\":..,\"y\":..,\"via\":\"auto\"}")
                }
            }
            put("count", page.length())
            put("total", total)
            put("offset", offset)
            put("limit", limit)
            put("nextOffset", if (offset + limit < total) offset + limit else JSONObject.NULL)
            put("timings", perf)
            put("ocrEngine", ocrEngine)
            put("ocrCached", ocrCached)
            put("ocrChanged", ocrChanged)
            ocrRegion?.let { put("ocrRegion", it) }
            put("scale", JSONArray().put(base.optInt("width")).put(base.optInt("height")))
        }.toString()
        }
    }

    internal fun mergeOcrLines(boxes: List<Box>): List<Line> {
        with(bridge) {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedWith(compareBy({ it.cy }, { it.left }))
        val lines = ArrayList<Line>()
        var cur: MutableList<Box> = ArrayList()
        fun flush() {
            if (cur.isEmpty()) return
            val left = cur.minOf { it.left }; val top = cur.minOf { it.top }
            val right = cur.maxOf { it.right }; val bottom = cur.maxOf { it.bottom }
            val text = cur.sortedBy { it.left }.joinToString(" ") { it.text.trim() }.trim()
            if (text.isNotEmpty()) lines.add(Line(text, left, top, right, bottom))
            cur = ArrayList()
        }
        for (b in sorted) {
            if (cur.isEmpty()) { cur.add(b); continue }
            val anchor = cur.first()
            val sameBand = kotlin.math.abs(b.cy - anchor.cy) <= 18
            val gap = b.left - cur.maxOf { it.right }
            val maxH = maxOf(anchor.h, b.h).coerceAtLeast(8)
            if (sameBand && gap < (0.8f * maxH).toInt()) cur.add(b) else { flush(); cur.add(b) }
        }
        flush()
        return lines
        }
    }

    internal fun <T> withScreenTimeout(
        what: String, timeoutMs: Long = SCREEN_IPC_TIMEOUT_MS, block: () -> T,
    ): Result<T>?  {
        with(bridge) {
        val first = screenReadOnce(what, timeoutMs, block)
        if (first != null) return first
        // ⚠️ v1.2.152 实测修复（模拟器连续 5 次读屏取证）：同一个静止页面，
        // 前两次各 2075ms 超时→504，随后三次 57-77ms 成功。也就是说 2s 上限
        // 会把「屏幕刚变化、节点树正在重建」的**冷读**误报成「无障碍卡死」，
        // 而 AI 收到 504 只会重试一次——正好撞上重建完成，白白多花一轮。
        // 对上位机来说这是"某些页面莫名停留更久"的直接来源。
        // 只对短超时路径做一次放宽重试（loop/run 那种长时间预算不重试，
        // 重试没有意义且会占用执行线程）。
        if (timeoutMs > 20_000L) return null
        Log.w(TAG, "screen ipc timeout: $what (>${timeoutMs}ms) — retrying once (cold node tree)")
        return screenReadOnce(what, SCREEN_IPC_COLD_RETRY_MS, block)
        }
    }

    internal fun <T> screenReadOnce(what: String, timeoutMs: Long, block: () -> T): Result<T>? = with(bridge) { try {
        val future = screenExec.submit(java.util.concurrent.Callable { block() })
        Result.success(future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS))
    } catch (e: java.util.concurrent.TimeoutException) {
        Log.w(TAG, "screen ipc timeout: $what (>${timeoutMs}ms) — node tree stalled")
        null
    } catch (e: java.util.concurrent.ExecutionException) {
        Result.failure(e.cause ?: e)
    }
    }

    internal val shotSeq = java.util.concurrent.atomic.AtomicInteger(0)

    internal val KEEP_STEP_SHOTS = 3

    @Volatile internal var ocrPrefetch: OcrPrefetch? = null

    @Volatile internal var ocrPrefetching = false

    internal val A11Y_NOTIF_THRESHOLD = 3

    internal val A11Y_NOTIF_WINDOW_MS = 30_000L

    internal val A11Y_NOTIF_IDLE_MS = 10_000L

    internal val A11Y_NOTIF_ID = 0x0A11

}
