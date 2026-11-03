package app.dsh.mobile.bridge

import app.dsh.mobile.bridge.RuntimeHandler
import app.dsh.mobile.bridge.PtyHandler
import app.dsh.mobile.bridge.BridgeSupport
import app.dsh.mobile.bridge.ScreenHandler
import app.dsh.mobile.bridge.ActionHandler
import app.dsh.mobile.bridge.AppHandler
import app.dsh.mobile.bridge.VisionHandler
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
import app.dsh.mobile.engine.AudioPlayer
import app.dsh.mobile.engine.SceneMemory

/**
 * 路由分发（原 AgentBridge.route 原样迁出）：只做 method/path 分发，语义不变。
 * 无障碍门禁分类表在 A11yGate.kt（同包，直接引用）。
 */
internal class Router(private val bridge: AgentBridge) {

    internal fun route(ctx: Context, method: String, path: String, query: String, body: String): Pair<Int, String> {
        with(bridge) {
        // 每次请求都顺手预取一次 OCR（TTL 防刷）：a11y 受限时可直接兜底
        DshAccessibilityService.instance?.let { screen.prefetchOcr(it) }
        // v1.2.182（用户需求）：AI 进行「一大串」无障碍操作时发一条系统通知，
        // 让用户随时知道它真的在干活（而不是界面自己变来变去像 bug）。
        // 只统计**动作类**端点（点击/输入/滑动/按键/启动…），读屏类不计入 ——
        // 否则每看一眼屏幕都算一步，通知会烦人。
        // ⚠️ ACTING_PATHS 只影响通知计数，**不是**无障碍门禁集合（见 docs/A11Y-SCOPE.md）。
        if (method == "POST" && path in ACTING_PATHS) screen.noteA11yOperation(ctx, path)
        // v1.7.3：无障碍「已启用但未绑定」时先等 1.5s，再给出可区分的错误，
        // 避免 Agent 预检/动作拿到笼统的 503 就判定「无障碍没开」。
        // v2.5.3（事故修复，见 docs/A11Y-SCOPE.md）：门禁**只查 A11Y_DEPENDENT_PATHS**，
        // 即「读/写无障碍节点树 + 注入手势/按键/文本 + 无障碍截图 + 无障碍事件缓存」的并集；
        // 集合里每个 handler 都必然拿 DshAccessibilityService.instance 才能完成主功能。
        // 反例（v2.5.2 事故）：旧集合把 /launch、/launch-intent、/open、/app-screenshot 也算进去，
        // 用户一关无障碍连「打开微信」「截自家界面」都得到 503 a11y_off —— 那是分类 bug，
        // startActivity / Intent / PixelCopy 跟无障碍毫无关系。
        // 条件依赖端点（/ocr /wait-app /memory/lookup /memory/store /overlay /activity /open）
        // 故意不在这里：它们有不需要无障碍就能成功的路径，由 handler 自己判定。
        if (path in A11Y_DEPENDENT_PATHS && !DshAccessibilityService.isConnected()) {
            val enabled = DshAccessibilityService.isEnabled(ctx)
            if (enabled) DshAccessibilityService.awaitConnection(1_500L)
            if (!DshAccessibilityService.isConnected()) {
                val listed = !enabled && DshAccessibilityService.isListedButDisabled(ctx)
                // 「为什么需要」逐路径写死在 A11Y_DEPENDENT_REASONS；这里只拼统一的「怎么开」。
                // 不允许再回一句笼统的 a11y_off：用户和 Agent 都需要知道是读树、手势还是截图。
                val why = A11Y_DEPENDENT_REASONS[path] ?: "needs the accessibility service"
                val how = "enable 'DSH Mobile Helper (optional)' in Settings > Accessibility - the app cannot enable it itself; POST /a11y/enable opens that page"
                val msg = when {
                    enabled -> "$path $why; the service is enabled in system settings but not connected yet - retry in a moment, or toggle it off/on in Settings > Accessibility if it persists"
                    listed -> "$path $why; Android disabled the previously granted service (force-stop or app update) - re-enable 'DSH Mobile Helper (optional)' in Settings > Accessibility; POST /a11y/enable opens that page"
                    else -> "$path $why. To fix: $how"
                }
                return 503 to JSONObject()
                    .put("ok", false)
                    // v1.8.0（提案 P1-5）：err 用稳定 code，便于上层一次判断；人读文案放 reason。
                    .put("error", if (enabled) "a11y_connecting" else "a11y_off")
                    .put("code", if (enabled) "a11y_connecting" else "a11y_off")
                    .put("endpoint", path)
                    .put("because", why)
                    .put("reason", msg)
                    .put("message", msg)
                    .put("how_to_fix", how)
                    .put("accessibility_enabled", enabled)
                    .put("accessibility_connected", false)
                    .put("accessibility_listed_but_disabled", listed)
                    .put("retryable", enabled)
                    .put("action", if (listed || !enabled) "open_a11y_settings" else "retry")
                    .put("actionHint", "POST /a11y/enable opens the system accessibility page")
                    .toString()
            }
        }
        return when {
            method == "POST" && path == "/notify" -> appHandler.notify(ctx, body)
            method == "GET" && path == "/screen" -> screen.screen(query)
            method == "GET" && path == "/ocr" -> screen.ocr(ctx, query)
            method == "GET" && path == "/screen-changed" -> screen.screenChanged()
            method == "GET" && path == "/scene" -> screen.scene(ctx, query)
            method == "POST" && path == "/act" -> action.act(ctx, body)
            method == "POST" && path == "/export" -> appHandler.mediaExport(ctx, body)
            method == "POST" && path == "/pick" -> screen.pick(body)
            method == "GET" && path == "/local/status" -> system.localStatus(ctx)
            method == "GET" && path == "/frame-diff" -> screen.frameDiff()
            method == "POST" && path == "/frame-wait" -> screen.frameWait(body)
            method == "POST" && path == "/loop/run" -> action.loopRun(ctx, body)
            method == "POST" && path == "/vision/crop" -> vision.visionCrop(ctx, body)
            method == "POST" && path == "/vision/track" -> vision.visionTrack(ctx, body)
            method == "POST" && path == "/vision/tap" -> vision.visionTap(ctx, body)
            method == "POST" && path == "/vision/drag" -> vision.visionDrag(ctx, body)
            method == "GET" && path == "/vision/list" -> system.visionList(ctx)
            method == "GET" && path == "/memory/list" -> 200 to SceneMemory.listJson(ctx)
            method == "GET" && path == "/memory/stats" -> 200 to JSONObject().put("ok", true).put("summary", SceneMemory.stats(ctx)).toString()
            method == "POST" && path == "/memory/lookup" -> system.memoryLookup(ctx, body)
            method == "POST" && path == "/memory/store" -> system.memoryStore(ctx, body)
            method == "POST" && path == "/memory/confirm" -> system.memoryConfirm(ctx, body)
            method == "POST" && path == "/memory/clear" -> {
                SceneMemory.clear(ctx)
                200 to """{"ok":true,"cleared":true}"""
            }
            method == "POST" && path == "/local/ensure" -> system.localEnsure(ctx, query)
            method == "GET" && path == "/daemon/status" -> system.daemonStatus()
            method == "POST" && path == "/daemon/stop" -> system.daemonStop()
            method == "POST" && path == "/tap" -> action.tap(body)
            method == "GET" && path == "/screenshot" -> screen.screenshot(query)
            method == "GET" && path == "/calib" -> screen.calib(ctx, query)
            method == "GET" && path == "/yolo" -> screen.yoloBench(ctx)
            method == "GET" && path == "/log" -> system.logHose(ctx, query)
            method == "GET" && path == "/app-screenshot" -> screen.appScreenshot(query)
            method == "GET" && path == "/process" -> system.processInfo()
            method == "GET" && path == "/status" -> system.status()
            method == "GET" && path == "/settings" -> system.settingsGet()
            method == "POST" && path == "/settings" -> system.settingsSet(body)
            method == "POST" && path == "/activity" -> appHandler.openActivity(body)
            method == "POST" && path == "/media/play" -> appHandler.mediaPlay(ctx, body)
            method == "POST" && path == "/media/pause" -> appHandler.mediaPause(ctx)
            method == "POST" && path == "/media/resume" -> appHandler.mediaResume(ctx)
            method == "POST" && path == "/media/stop" -> appHandler.mediaStop(ctx)
            method == "GET" && path == "/media/status" -> 200 to AudioPlayer.status().toString()
            method == "POST" && path == "/open/file" -> appHandler.openPath(ctx, body, "file")
            method == "POST" && path == "/open/folder" -> appHandler.openPath(ctx, body, "folder")
            method == "POST" && path == "/open/share" -> appHandler.openPath(ctx, body, "share")
            method == "POST" && path == "/share/text" -> appHandler.shareText(ctx, body)
            method == "GET" && path == "/apps" -> appHandler.appsFor(query)
            method == "POST" && path == "/gesture" -> action.gesture(body)
            method == "POST" && path == "/key" -> action.key(body)
            method == "POST" && path == "/wait" -> action.wait(body)
            method == "POST" && path == "/input" -> action.input(body)
            method == "POST" && path == "/scroll-find" -> action.scrollFind(body)
            method == "POST" && path == "/scroll-center" -> action.scrollCenter(body)
            method == "GET" && path == "/diff" -> screen.diffScreen()
            method == "POST" && path == "/idle" -> action.idle(body)
            method == "POST" && path == "/batch" -> action.batch(body)
            method == "GET" && path == "/interference" -> action.interference(query)
            method == "POST" && path == "/overlay" -> action.overlay(ctx, body)
            method == "POST" && path == "/launch" -> appHandler.launch(ctx, body)
            // /launch-intent 与 /open 的 component 分支等价（extras/flags 都支持），
            // 统一走 openTarget，避免两套组件解析与回执格式。
            method == "POST" && path == "/launch-intent" -> appHandler.openTarget(ctx, body)
            method == "POST" && path == "/open" -> appHandler.openTarget(ctx, body)
            method == "POST" && path == "/wait-app" -> appHandler.waitApp(ctx, body)
            method == "POST" && path == "/tap-id" -> action.tapId(body)
            // /wait-text 是 /wait 的别名（同一套节点树轮询），只保留一个实现。
            method == "POST" && path == "/wait-text" -> action.wait(body, "wait-text")
            method == "POST" && path == "/scroll-edge" -> action.scrollEdge(body)
            method == "GET" && path == "/notif" -> appHandler.notif()
            method == "GET" && path == "/plugin/list" -> system.pluginList()
            method == "POST" && path == "/plugin/install" -> system.pluginInstall(body)
            method == "GET" && path == "/diag" -> system.diag(ctx)
            method == "POST" && path == "/say" -> appHandler.say(ctx, body)
            method == "GET" && path == "/say" -> appHandler.sayGet(ctx, query)
            // 运行时热更新（2026-10-18）：门脚本 dsh-update 驱动的五个端点
            method == "GET" && path == "/runtime/status" -> RuntimeHandler.status(ctx, query)
            method == "GET" && path == "/runtime/feed" -> RuntimeHandler.feed(ctx, query)
            method == "POST" && path == "/runtime/pull" -> RuntimeHandler.pull(ctx, body)
            // 引擎侧终端：dsh WebUI 侧边栏的 node-pty shim 通过这组端点用 App 的真 PTY
            method == "POST" && path == "/pty/open" -> PtyHandler.open(ctx, body)
            method == "POST" && path == "/pty/write" -> PtyHandler.write(body)
            method == "POST" && path == "/pty/resize" -> PtyHandler.resize(body)
            method == "POST" && path == "/pty/kill" -> PtyHandler.kill(body)
            method == "POST" && path == "/pty/close" -> PtyHandler.close(body)
            method == "GET" && path == "/pty/read" -> PtyHandler.read(query)
            method == "POST" && path == "/runtime/stage" -> RuntimeHandler.stage(ctx, body)
            method == "POST" && path == "/runtime/apply" -> RuntimeHandler.apply(ctx)
            method == "POST" && path == "/runtime/rollback" -> RuntimeHandler.rollback(ctx)
            method == "POST" && path == "/engine/restart" -> appHandler.engineRestart(ctx, body)
            method == "POST" && path == "/a11y/enable" -> appHandler.a11yEnable(ctx)
            else -> 404 to """{"ok":false,"error":"unknown route"}"""
        }
        }
    }
}
