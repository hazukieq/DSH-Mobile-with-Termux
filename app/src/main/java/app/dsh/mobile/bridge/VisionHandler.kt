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
import app.dsh.mobile.engine.AgentBridge

/**
 * VisionHandler：Phase 2 从 AgentBridge 拆出的端点组（成员原样迁移，行为不变）。
 *
 * 函数体包 `with(bridge)`，因此原文件里对 AgentBridge 成员的裸引用不用改名；
 * 跨组裸调用由本包的 AgentBridge 扩展别名（CrossCalls.kt）兜住。
 */
internal class VisionHandler(private val bridge: AgentBridge) {
    internal fun visionCrop(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val id = obj.optString("id").ifBlank { "t${System.currentTimeMillis()}" }
        // 同 trackOnce：直接调用，不经 screenExec（避免被节点树 IPC 拖死）
        val shot = runCatching { svc.screenshotArgb() }.getOrNull()
            ?: return 500 to """{"ok":false,"error":"screenshot unavailable (needs API 30+ capability)"}"""
        val (_, sw, sh) = shot
        // 归一化坐标优先（云端看截图后给的通常是比例）
        val x: Int; val y: Int; val w: Int; val h: Int
        if (obj.has("nx")) {
            val nx = obj.optDouble("nx"); val ny = obj.optDouble("ny")
            val nw = obj.optDouble("nw", 0.2); val nh = obj.optDouble("nh", 0.2)
            x = (nx * sw).toInt(); y = (ny * sh).toInt()
            w = (nw * sw).toInt(); h = (nh * sh).toInt()
        } else {
            x = obj.optInt("x"); y = obj.optInt("y")
            w = obj.optInt("w"); h = obj.optInt("h")
        }
        val cropped = app.dsh.mobile.VisionTracker.crop(shot, x, y, w, h)
            ?: return 400 to """{"ok":false,"error":"crop rect out of bounds ($x,$y,$w,$h) screen=${sw}x$sh"}"""
        val (arr, cw, ch) = cropped
        // 有效性检查（实测教训：裁到纯色背景/黑帧时模板无特征，匹配会随机跳到
        // 任意区域且分数虚高——例如裁到深蓝背景后匹配到屏幕底部、分数 0.956）
        val variance = run {
            var sum = 0.0; var sum2 = 0.0
            val step = maxOf(1, arr.size / 2000)
            var n = 0
            var i = 0
            while (i < arr.size) {
                val c = arr[i]
                val g = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000.0
                sum += g; sum2 += g * g; n++
                i += step
            }
            val mean = sum / n
            sum2 / n - mean * mean
        }
        if (variance < 120.0) {
            return 400 to """{"ok":false,"error":"cropped area is too flat (variance=${"%.0f".format(variance)}) — likely a solid-colour background; verify the coordinates with /screenshot"}"""
        }
        val png = app.dsh.mobile.VisionTracker.encodePng(arr, cw, ch)
            ?: return 500 to """{"ok":false,"error":"png encode failed"}"""
        val f = java.io.File(system.visionDir(ctx), "$id.png")
        f.writeBytes(png)
        return 200 to """{"ok":true,"id":$id,"w":$cw,"h":$ch,"pngBytes":${png.size},"path":"${f.absolutePath}"}"""
        }
    }

    internal fun trackOnce(
        svc: DshAccessibilityService,
        ctx: Context,
        id: String,
        region: FloatArray? = null,
    ): app.dsh.mobile.VisionTracker.Match? {
        with(bridge) {
        val tmpl = system.loadTemplate(ctx, id) ?: return null
        // ⚠️ 截图**直接调用**，不走 withScreenTimeout/screenExec —— 实测该线程池
        // 会被节点树 IPC 挂起拖死，导致截图排队全部超时（/screenshot 端点不受影响
        // 正是因为它直接调用）。screenshotArgb 自带 latch 超时，不会无限阻塞。
        var shot: Triple<IntArray, Int, Int>? = null
        for (i in 0 until 3) {
            shot = runCatching { svc.screenshotArgb() }.getOrNull()
            if (shot != null) break
            Thread.sleep(300)
        }
        val s = shot ?: return null
        return app.dsh.mobile.VisionTracker.locate(s, tmpl.first, tmpl.second, tmpl.third, region)
        }
    }

    internal fun parseRegion(obj: JSONObject): FloatArray? {
        with(bridge) {
        val r = obj.optJSONObject("region") ?: return null
        return floatArrayOf(
            r.optDouble("nx", 0.0).toFloat(),
            r.optDouble("ny", 0.0).toFloat(),
            r.optDouble("nw", 1.0).toFloat(),
            r.optDouble("nh", 1.0).toFloat(),
        )
        }
    }

    internal fun visionTrack(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val id = runCatching { JSONObject(body).optString("id") }.getOrDefault("")
        if (id.isBlank()) return 400 to """{"ok":false,"error":"field 'id' required"}"""
        if (system.loadTemplate(ctx, id) == null) return 404 to """{"ok":false,"error":"template '$id' not found (use /vision/crop first)"}"""
        val region = parseRegion(runCatching { JSONObject(body) }.getOrDefault(JSONObject()))
        val m = trackOnce(svc, ctx, id, region)
            ?: return 500 to """{"ok":false,"error":"match failed (screenshot unavailable?)"}"""
        return 200 to buildString {
            append("""{"ok":true,"id":${JSONObject.quote(id)},"x":${m.x},"y":${m.y},""")
            append(""""score":${"%.3f".format(m.score)},"costMs":${m.costMs},""")
            append(""""proc":{"x":${m.procX},"y":${m.procY},"w":${m.procW},"h":${m.procH},"fw":${m.frameW},"fh":${m.frameH}}}""")
        }
        }
    }

    internal fun visionTap(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val id = obj.optString("id")
        if (id.isBlank()) return 400 to """{"ok":false,"error":"field 'id' required"}"""
        val minScore = obj.optDouble("minScore", 0.5)
        val m = trackOnce(svc, ctx, id, parseRegion(obj))
            ?: return 500 to """{"ok":false,"error":"match failed"}"""
        if (m.score < minScore) {
            return 200 to """{"ok":false,"error":"score too low","score":${"%.3f".format(m.score)},"x":${m.x},"y":${m.y},"minScore":$minScore}"""
        }
        val ok = withTimeoutTap(svc, m.x, m.y)
        return 200 to """{"ok":$ok,"tapped":$ok,"x":${m.x},"y":${m.y},"score":${"%.3f".format(m.score)},"costMs":${m.costMs}}"""
        }
    }

    internal fun withTimeoutTap(svc: DshAccessibilityService, x: Int, y: Int): Boolean = with(bridge) { 
        runCatching { svc.dispatchTapRect(android.graphics.Rect(x, y, x, y)) }.getOrDefault(false)
    }

    internal fun visionDrag(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val id = obj.optString("id")
        if (id.isBlank()) return 400 to """{"ok":false,"error":"field 'id' required"}"""
        val m = trackOnce(svc, ctx, id, parseRegion(obj))
            ?: return 500 to """{"ok":false,"error":"match failed"}"""
        val (sw, sh) = svc.screenSize()
        val x2: Float; val y2: Float
        if (obj.has("toNx")) {
            x2 = (obj.optDouble("toNx") * sw).toFloat()
            y2 = (obj.optDouble("toNy") * sh).toFloat()
        } else {
            x2 = (m.x + obj.optDouble("dx", 0.0)).toFloat()
            y2 = (m.y + obj.optDouble("dy", 0.0)).toFloat()
        }
        val ms = obj.optLong("ms", 500L).coerceIn(100L, 3000L)
        val ok = runCatching { svc.dispatchSwipe(m.x.toFloat(), m.y.toFloat(), x2, y2, ms) }.getOrDefault(false)
        return 200 to """{"ok":$ok,"fromX":${m.x},"fromY":${m.y},"toX":${x2.toInt()},"toY":${y2.toInt()},"ms":$ms,"score":${"%.3f".format(m.score)}}"""
        }
    }

}
