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
import app.dsh.mobile.engine.AppState
import app.dsh.mobile.engine.AudioPlayer
import app.dsh.mobile.engine.MediaHygiene
import app.dsh.mobile.engine.OpenWith
import app.dsh.mobile.engine.RuntimeUpdater
import app.dsh.mobile.engine.TtsManager
import app.dsh.mobile.engine.AgentBridge

/**
 * AppHandler：Phase 2 从 AgentBridge 拆出的端点组（成员原样迁移，行为不变）。
 *
 * 函数体包 `with(bridge)`，因此原文件里对 AgentBridge 成员的裸引用不用改名；
 * 跨组裸调用由本包的 AgentBridge 扩展别名（CrossCalls.kt）兜住。
 */
internal class AppHandler(private val bridge: AgentBridge) {
    internal fun launch(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        return try {
            val obj = JSONObject(body)
            // v1.2.131 修：接受 pkg 别名——gates 的 `scr macro open-settings` 一直传
            // {"pkg":...}，端点只认 "package"，于是该 macro 长期 100% 失败
            // （AI 体验反馈实测抓到：报 "need 'package' or 'label' field"）。
            val pkg = obj.optString("package").ifBlank { obj.optString("pkg") }.orEmpty()
            val label = obj.optString("label").orEmpty()
            val component = obj.optString("component").ifBlank { obj.optString("activity") }.orEmpty()
            val pm = ctx.packageManager

            // 显式 component（v1.3.27）：以前被静默忽略（只开 launcher），现在按 component 打开
            if (component.isNotBlank()) return launchComponent(ctx, component, obj)

            // 按包名直接启动
            if (pkg.isNotBlank()) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    noteOpen(ctx, pkg)
                    return 200 to """{"ok":true,"launched":"$pkg"}"""
                }
                return 404 to """{"ok":false,"error":"no launchable activity for package '$pkg'"}"""
            }

            // 按应用名模糊匹配（遍历已安装的带 launcher 入口的包）
            if (label.isNotBlank()) {
                val q = label.lowercase()
                val matches = pm.getInstalledApplications(0).filter { appInfo ->
                    pm.getApplicationLabel(appInfo).toString().lowercase().contains(q)
                }
                if (matches.isEmpty()) {
                    return 404 to """{"ok":false,"error":"no app matching label '$label'"}"""
                }
                if (matches.size > 1) {
                    val names = matches.map { pm.getApplicationLabel(it).toString() }
                    return 300 to """{"ok":false,"error":"multiple matches","apps":${names.toString()}}"""
                }
                val intent = pm.getLaunchIntentForPackage(matches[0].packageName)
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    noteOpen(ctx, matches[0].packageName)
                    return 200 to """{"ok":true,"launched":"${matches[0].packageName}"}"""
                }
            }
            400 to """{"ok":false,"error":"need 'package' or 'label' field"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun launchComponent(ctx: Context, component: String, obj: JSONObject): Pair<Int, String> {
        with(bridge) {
        val name = runCatching { android.content.ComponentName.unflattenFromString(component) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"bad component (want pkg/cls): $component"}"""
        val exists = runCatching { ctx.packageManager.getActivityInfo(name, 0) }.isSuccess
        if (!exists) return 404 to """{"ok":false,"error":"component not found: $component"}"""
        return runCatching {
            val intent = android.content.Intent().apply {
                this.component = name
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                obj.optString("action").takeIf { it.isNotBlank() }?.let { action = it }
                obj.optString("data").takeIf { it.isNotBlank() }?.let { data = android.net.Uri.parse(it) }
            }
            applyIntentExtras(intent, obj)
            ctx.startActivity(intent)
            noteOpen(ctx, component)
            200 to """{"ok":true,"launched":"$component","component":true,"backgroundLaunchMayBeBlocked":true,"hint":"Android may block background activity starts; if no UI appeared, open it from the app UI (top-bar gear) instead"}"""
        }.getOrElse { 500 to """{"ok":false,"error":"startActivity failed: ${it.message}"}""" }
        }
    }

    internal fun openTarget(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val pkg = obj.optString("package").ifBlank { obj.optString("pkg") }.orEmpty()
        val target = obj.optString("targetPackage")
            .ifBlank { obj.optString("target") }.ifBlank { pkg }.orEmpty()
        val component = obj.optString("component").ifBlank { obj.optString("activity") }.orEmpty()
        val uri = obj.optString("uri").ifBlank { obj.optString("url") }
            .ifBlank { obj.optString("data") }.orEmpty()
        val action = obj.optString("action").ifBlank {
            if (uri.isNotBlank()) android.content.Intent.ACTION_VIEW else ""
        }
        val waitMs = obj.optInt("waitMs", 2000).coerceIn(0, 8000)
        if (component.isBlank() && uri.isBlank() && pkg.isBlank()) {
            return 400 to """{"ok":false,"error":"need 'uri', 'package' or 'component'"}"""
        }
        val svc = DshAccessibilityService.instance
        val before = runCatching { svc?.foregroundPackage() }.getOrNull()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val launched: String
        try {
            if (component.isNotBlank()) {
                val name = runCatching {
                    android.content.ComponentName.unflattenFromString(component)
                }.getOrNull() ?: return 400 to """{"ok":false,"error":"bad component: $component"}"""
                if (runCatching { ctx.packageManager.getActivityInfo(name, 0) }.isFailure) {
                    return 404 to """{"ok":false,"error":"component not found: $component"}"""
                }
                launched = component
                val intent = android.content.Intent().apply {
                    this.component = name
                    if (action.isNotBlank()) this.action = action
                    if (uri.isNotBlank()) data = android.net.Uri.parse(uri)
                }
                applyIntentExtras(intent, obj)
                startWithFlags(ctx, intent, obj, chooser = false)
            } else if (uri.isNotBlank()) {
                // intent://...;#Intent;scheme=weixin;package=com.tencent.mm;end
                // —— 网页/支付回调最常给的就是这种格式，先按 URI_INTENT_SCHEME 解析。
                val intent: android.content.Intent
                if (uri.startsWith("intent:", ignoreCase = true)) {
                    intent = runCatching {
                        android.content.Intent.parseUri(uri, android.content.Intent.URI_INTENT_SCHEME)
                    }.getOrNull() ?: return 400 to
                        """{"ok":false,"error":"bad intent uri: $uri"}"""
                } else {
                    val effective = paymentSchemeFor(uri)
                    val u = runCatching { android.net.Uri.parse(effective) }.getOrNull()
                        ?: return 400 to """{"ok":false,"error":"bad uri: $uri"}"""
                    intent = android.content.Intent().apply {
                        if (action.isNotBlank()) this.action = action
                        data = u
                    }
                    if (effective != uri) Log.i(TAG, "open: rewrote $uri -> $effective")
                }
                if (target.isNotBlank()) intent.setPackage(target)
                applyIntentExtras(intent, obj)
                launched = uri
                // 可见性只决定「能否提前解析」，不决定「能否启动」：
                // 解析不到也先真启动一次，把 ActivityNotFoundException 变成可读的 404。
                val resolved = runCatching {
                    ctx.packageManager.resolveActivity(
                        intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                    )
                }.getOrNull()
                try {
                    startWithFlags(ctx, intent, obj, obj.optBoolean("chooser"))
                } catch (e: android.content.ActivityNotFoundException) {
                    return 404 to JSONObject().put("ok", false)
                        .put("error", "no handler for uri: $uri")
                        .put(
                            "hint", "nothing on this phone can open that scheme; install the app " +
                                "(WeChat for weixin://, Alipay for alipays://) or pass package/targetPackage"
                        ).toString()
                }
                if (resolved == null) {
                    // 没解析到但启动成功：多见于包可见性受限的自定义 scheme
                    Log.i(TAG, "open: started $uri without a resolved handler (visibility)")
                }
            } else {
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return 404 to """{"ok":false,"error":"no launchable activity for package '$pkg'"}"""
                launched = pkg
                if (action.isNotBlank()) intent.action = action
                if (uri.isNotBlank()) intent.data = android.net.Uri.parse(uri)
                applyIntentExtras(intent, obj)
                startWithFlags(ctx, intent, obj, chooser = false)
            }
        } catch (e: Exception) {
            return 500 to """{"ok":false,"error":"startActivity failed: ${e.message}"}"""
        }
        // 等前台变化：不截图、不猜，只读系统前台包名
        var after = before
        while (waitMs > 0 &&
            android.os.SystemClock.elapsedRealtime() - startedAt < waitMs
        ) {
            Thread.sleep(120)
            after = runCatching { svc?.foregroundPackage() }.getOrNull() ?: after
            if (after != null && after != before) break
        }
        val waited = android.os.SystemClock.elapsedRealtime() - startedAt
        val changed = after != null && before != null && after != before
        // v2.5.6：跳转别的 App（微信/支付宝/浏览器…）→ 写进悬浮窗第④段动作链
        noteOpen(ctx, launched)
        return 200 to JSONObject().apply {
            put("ok", true)
            put("launched", launched)
            put("before", before ?: JSONObject.NULL)
            put("after", after ?: JSONObject.NULL)
            put("changed", changed)
            put("waitedMs", waited)
            if (!changed) {
                put(
                    "hint",
                    if (svc == null) {
                        "accessibility service is off, so the foreground package cannot be read; " +
                            "the launch was dispatched but unverified. Enable DSH Mobile Helper " +
                            "to get before/after, or use /wait-app once it is on."
                    } else {
                        "foreground did not change within ${waitMs}ms: the target may still be " +
                            "cold-starting (WeChat/Alipay take 2-4s), it may show an in-app/h5 " +
                            "page, or a chooser is waiting. Retry with a larger waitMs or use " +
                            "`scr wait-app <pkg>` before screenshotting."
                    }
                )
            }
        }.toString()
        }
    }

    internal fun paymentSchemeFor(uri: String): String {
        with(bridge) {
        val u = runCatching { android.net.Uri.parse(uri) }.getOrNull() ?: return uri
        if (!u.scheme.equals("https", ignoreCase = true) &&
            !u.scheme.equals("http", ignoreCase = true)
        ) {
            return uri
        }
        val host = u.host.orEmpty().lowercase()
        val alipay = host == "qr.alipay.com" || host == "render.alipay.com" ||
            host == "mclient.alipay.com" || host.endsWith(".alipay.com")
        if (!alipay) return uri
        return "alipays://platformapi/startapp?appId=20000067&url=" +
            android.net.Uri.encode(uri)
        }
    }

    internal fun startWithFlags(
        ctx: Context,
        intent: android.content.Intent,
        obj: JSONObject,
        chooser: Boolean,
    ) {
        with(bridge) {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        obj.optJSONArray("flags")?.let { arr ->
            for (i in 0 until arr.length()) {
                FLAG_NAMES[arr.optString(i).uppercase()]?.let { intent.addFlags(it) }
            }
        }
        val toStart = if (chooser) {
            android.content.Intent.createChooser(intent, null)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        } else intent
        ctx.startActivity(toStart)
        }
    }

    internal fun waitApp(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val pkg = obj.optString("package").ifBlank { obj.optString("pkg") }.orEmpty()
        if (pkg.isBlank()) return 400 to """{"ok":false,"error":"package required"}"""
        val until = obj.optString("until", "==")
        val timeoutMs = obj.optInt("timeoutMs", 15000).coerceIn(200, 60000)
        // 本 App 是否在前台，用自己的 Activity 生命周期判断 —— 不需要无障碍
        // （startActivity 本来就不需要无障碍；只有观察“别的 App 是否在前台”才需要）。
        val self = pkg == ctx.packageName
        val svc = DshAccessibilityService.instance
        if (!self && svc == null) {
            // 条件依赖：本包用 Activity 生命周期判断（上面 self 分支），只有「观察别的 App」才需要 a11y。
            return 503 to JSONObject()
                .put("ok", false)
                .put("error", "a11y_unavailable")
                .put("code", "a11y_unavailable")
                .put("reason", "observing another app's foreground state needs the accessibility service (launching it never did); use wait-app for ${ctx.packageName} to check this app instead, or enable 'DSH Mobile Helper (optional)' in Settings > Accessibility")
                .put("how_to_fix", "wait for ${ctx.packageName} without a11y, or POST /a11y/enable and enable 'DSH Mobile Helper (optional)'")
                .put("retryable", false)
                .toString()
        }
        fun currentForeground(): String? =
            if (self) {
                if (AppState.resumedActivityName() != null) ctx.packageName else null
            } else {
                runCatching { svc?.foregroundPackage() }.getOrNull()
            }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var fg: String? = currentForeground()
        fun hit(f: String?): Boolean =
            if (until == "!=") f != null && f != pkg else f == pkg
        while (true) {
            if (hit(fg)) {
                return 200 to JSONObject().apply {
                    put("ok", true)
                    put("foreground", fg)
                    put("target", pkg)
                    put("until", until)
                    put("waitedMs", android.os.SystemClock.elapsedRealtime() - startedAt)
                }.toString()
            }
            if (android.os.SystemClock.elapsedRealtime() - startedAt >= timeoutMs) {
                return 408 to JSONObject().apply {
                    put("ok", false)
                    put("error", "timeout waiting for foreground $until $pkg")
                    put("foreground", fg ?: JSONObject.NULL)
                    put("waitedMs", timeoutMs)
                    put("hint", "the user may still be paying; ask them or use /idle + /screen. " +
                        "Do not screenshot repeatedly in a loop.")
                }.toString()
            }
            Thread.sleep(150)
            fg = currentForeground() ?: fg
        }
        }
    }

    internal val FLAG_NAMES = mapOf(
        "CLEAR_TOP" to android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
        "CLEAR_TASK" to android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
        "NEW_TASK" to android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
        "SINGLE_TOP" to android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
        "NO_ANIMATION" to android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION,
        "EXCLUDE_FROM_RECENTS" to android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        "RESET_TASK_IF_NEEDED" to android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
    )

    internal fun applyIntentExtras(intent: android.content.Intent, obj: JSONObject) {
        with(bridge) {
        val extras = obj.optJSONObject("extras") ?: return
        extras.keys().forEach { key ->
            when (val v = extras.opt(key)) {
                is Boolean -> intent.putExtra(key, v)
                is Int -> intent.putExtra(key, v)
                is Long -> intent.putExtra(key, v)
                is Double -> intent.putExtra(key, v)
                is String -> intent.putExtra(key, v)
            }
        }
        }
    }

    internal fun openActivity(body: String): Pair<Int, String> {
        with(bridge) {
        val ctx = shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val wanted = obj.optString("name").ifBlank { obj.optString("activity") }
        if (wanted.isBlank()) return 400 to """{"ok":false,"error":"name required (main|settings|terminal|overlay-settings|accessibility-settings|<Class>)"}"""

        // 系统设置页（可选建议的落地入口）：打开系统界面，帮助用户授予悬浮窗/无障碍。
        // 这些**只是建议**：没授予也完整可用，走 notify / 用户切前台 的兜底。
        val systemAction: String? = when (wanted.lowercase()) {
            "overlay-settings", "overlay" -> android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            "accessibility-settings", "a11y-settings" -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> null
        }
        val cls: Class<out android.app.Activity>? = if (systemAction != null) null else when (wanted.lowercase()) {
            "main", "home" -> app.dsh.mobile.MainActivity::class.java
            "settings" -> app.dsh.mobile.SettingsActivity::class.java
            "terminal", "term" -> app.dsh.mobile.term.TerminalActivity::class.java
            else -> runCatching {
                val full = if (wanted.contains('.')) wanted else "app.dsh.mobile.$wanted"
                Class.forName(full).asSubclass(android.app.Activity::class.java)
            }.getOrNull() ?: return 404 to """{"ok":false,"error":"activity not found: $wanted (allowed: main|settings|terminal|overlay-settings|accessibility-settings|<Class>)"}"""
        }
        val requestedName = systemAction ?: cls!!.name
        val before = AppState.resumedActivityName()
        val alreadyOpen = cls != null && before == cls.name
        val overlay = runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false)
        val a11y = DshAccessibilityService.instance != null
        val foreground = AppState.isForeground(ctx)
        val likelyAllowed = foreground || overlay || a11y
        val notifyIfBlocked = obj.optBoolean("notifyIfBlocked", false)
        val startedAt = System.currentTimeMillis()
        val sinceElapsed = android.os.SystemClock.elapsedRealtime()
        return runCatching {
            val intent = if (cls != null) {
                android.content.Intent(ctx, cls).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            } else {
                android.content.Intent(systemAction).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                    if (systemAction == android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION) {
                        data = android.net.Uri.parse("package:" + ctx.packageName)
                    }
                }
            }
            obj.optString("action").takeIf { it.isNotBlank() && cls != null }?.let { intent.action = it }
            ctx.startActivity(intent)
            // 等待「真的发生」：本 App 的页面看 resume 回调；系统页面看本 App 是否离开了前台。
            var resumedAfter: String? = if (alreadyOpen) cls!!.name else null
            var leftApp = false
            val deadline = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < deadline) {
                val cur = AppState.resumedActivityName()
                if (cur == null) leftApp = true
                if (cls != null) {
                    if (alreadyOpen) break
                    resumedAfter = AppState.resumedSince(sinceElapsed)
                    if (resumedAfter == cls.name) break
                } else if (leftApp) {
                    break
                }
                Thread.sleep(60)
            }
            val opened = if (cls != null) resumedAfter == cls.name else leftApp
            val notificationPosted = if (!opened && notifyIfBlocked) postOpenNotification(ctx, requestedName, cls) else false
            val result = JSONObject()
                .put("ok", true)
                .put("opened", opened)
                .put("requested", requestedName)
                .put("requested_kind", if (cls != null) "app-activity" else "system-settings")
                .put("already_open", alreadyOpen)
                .put("handoff", true)
                .put("verify", "none")
                .put("do_not", org.json.JSONArray().put("screenshot").put("poll").put("retry"))
                .put("resumed_before", before ?: JSONObject.NULL)
                .put("observed_resumed", resumedAfter ?: JSONObject.NULL)
                .put("foreground", foreground)
                .put("bal_exempt", JSONObject().put("foreground", foreground).put("overlay", overlay).put("accessibility", a11y))
                .put("background_blocked", !opened && !likelyAllowed)
                .put("notification_posted", notificationPosted)
                .put("elapsed_ms", System.currentTimeMillis() - startedAt)
                .put(
                    "hint",
                    when {
                        opened && cls == null -> "System settings page opened for the user to grant the optional permission. No verification needed."
                        opened -> "Opened. Hand-off complete: do not screenshot or poll; the user may already be interacting with it."
                        notificationPosted -> "Android blocked the background start; a notification was posted - ask the user to tap it (or bring the app to the foreground and call again)."
                        !likelyAllowed -> "Android blocked the background start. This is only a fallback situation: ask the user to foreground the app, or use --notify; granting display-over-other-apps / accessibility is a convenience suggestion, never a requirement."
                        else -> "startActivity returned but nothing came up; the user may still be switching. Ask the user before retrying."
                    },
                )
            200 to result.toString()
        }.getOrElse { 500 to """{"ok":false,"error":"startActivity failed: ${it.message}"}""" }
        }
    }

    internal fun appsFor(query: String): Pair<Int, String> {
        with(bridge) {
        val ctx = shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        val params = parseQuery(query)
        val action = params["action"] ?: "view"
        if (action != "send" && action != "view") return 400 to """{"ok":false,"error":"action must be send|view"}"""
        return 200 to OpenWith.listHandlers(ctx, action, params["mime"]).toString()
        }
    }

    internal fun openPath(ctx: Context, body: String, kind: String): Pair<Int, String> {
        with(bridge) {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val path = obj.optString("path").ifBlank { obj.optString("file") }
        if (path.isBlank()) return 400 to """{"ok":false,"error":"path required"}"""
        val app = obj.optString("app").ifBlank { obj.optString("to") }
        val result = OpenWith.open(ctx, path, kind, obj.optString("mime"), app)
        val code = if (result.optBoolean("ok")) 200 else 404
        return code to result.toString()
        }
    }

    internal fun postOpenNotification(ctx: Context, target: String, cls: Class<out android.app.Activity>?): Boolean = with(bridge) { runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "dsh_open_page"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(channelId, "Open page", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = if (cls != null) {
            android.content.Intent(ctx, cls).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                data = android.net.Uri.parse("package:" + ctx.packageName)
            }
        }
        val pi = android.app.PendingIntent.getActivity(
            ctx, target.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                if (android.os.Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0,
        )
        val n = android.app.Notification.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("DSH Mobile")
            .setContentText("Tap to open " + target.substringAfterLast('.'))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(4711, n)
        true
    }.getOrDefault(false)
    }

    internal fun actionWordsFromAssets(ctx: Context): List<String> = with(bridge) { runCatching {
        val txt = ctx.assets.open("scene/action_words.json").bufferedReader().use { it.readText() }
        val arr = org.json.JSONArray(txt)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())
    }

    internal fun shareText(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val text = obj.optString("text")
        if (text.isBlank()) return 400 to """{"ok":false,"error":"text required"}"""
        val r = OpenWith.shareText(ctx, text, obj.optString("subject"), obj.optString("app").ifBlank { obj.optString("to") })
        return (if (r.optBoolean("ok")) 200 else 500) to r.toString()
        }
    }

    internal fun mediaPlay(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val source = obj.optString("path").ifBlank { obj.optString("url") }.ifBlank { obj.optString("source") }
        if (source.isBlank()) return 400 to """{"ok":false,"error":"need 'path' or 'url'"}"""
        val status = AudioPlayer.play(
            ctx,
            source,
            title = obj.optString("title"),
            artist = obj.optString("artist"),
            loop = obj.optBoolean("loop", false),
        )
        val code = if (status.optBoolean("ok")) 200 else 422
        return code to status.put("instant", true).put("verify", "none").toString()
        }
    }

    internal fun mediaPause(ctx: Context): Pair<Int, String> = with(bridge) { 200 to AudioPlayer.pause(ctx).put("instant", true).toString()
    }

    internal fun mediaResume(ctx: Context): Pair<Int, String> = with(bridge) { 200 to AudioPlayer.resume(ctx).put("instant", true).toString()
    }

    internal fun mediaStop(ctx: Context): Pair<Int, String> = with(bridge) { 200 to AudioPlayer.stop(ctx).let { AudioPlayer.status() }.put("instant", true).toString()
    }

    internal fun mediaExport(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val paths = obj.optJSONArray("paths")
            ?: return 400 to """{"ok":false,"error":"paths[] required"}"""
        if (paths.length() == 0) return 400 to """{"ok":false,"error":"paths[] is empty"}"""
        val target = obj.optString("target", "download").lowercase()
        if (target !in listOf("download", "pictures", "custom")) {
            return 400 to """{"ok":false,"error":"target must be download|pictures|custom"}"""
        }
        if (target == "custom" && obj.optString("dir").isBlank()) {
            return 400 to """{"ok":false,"error":"target=custom needs dir"}"""
        }
        return 200 to MediaHygiene.export(ctx, obj).toString()
        }
    }

    internal fun webViewDebugEnabled(ctx: android.content.Context): Boolean = with(bridge) { 
        runCatching {
            ctx.getExternalFilesDir(null)?.let { java.io.File(it, "webview-debug-off") }?.isFile != true
        }.getOrDefault(true)
    }

    internal fun a11yEnable(ctx: Context): Pair<Int, String> {
        with(bridge) {
        return try {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            200 to JSONObject()
                .put("ok", true)
                .put("opened", "accessibility-settings")
                .put("hint", "enable the DSH Mobile Helper (optional) service, then retry")
                .toString()
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"cannot open accessibility settings: ${e.message}"}"""
        }
        }
    }

    internal fun engineRestart(ctx: Context, body: String = ""): Pair<Int, String> {
        with(bridge) {
        // v1.7.1：支持 body {"delayMs":N}（0..30000，默认 500）。dsh-update 用 8000ms，
        // 让门脚本先打印结果、agent 先回复，再真正停引擎——否则调用方随引擎一起被杀，
        // 用户看到的就是「命令没反应」。
        val delayMs = runCatching {
            org.json.JSONObject(body.ifBlank { "{}" }).optLong("delayMs", 500L)
        }.getOrDefault(500L).coerceIn(0L, 30_000L)
        Thread({
            if (delayMs > 0) Thread.sleep(delayMs)
            runCatching { RuntimeUpdater.restart(ctx) }
                .onFailure { Log.w(TAG, "delayed engine restart failed: ${it.message}") }
        }, "engine-restart-delay").apply { isDaemon = true; start() }
        return acceptedOrRejected("restart", null)
        }
    }

    internal fun notif(): Pair<Int, String> {
        with(bridge) {
        val svc = DshAccessibilityService.instance
            ?: return a11yGone()
        val arr = JSONArray()
        svc.recentNotifications().forEach { arr.put(it) }
        return 200 to JSONObject().put("ok", true).put("notifications", arr).toString()
        }
    }

    internal fun notify(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        return try {
            val obj = JSONObject(body)
            val title = obj.optString("title").ifEmpty { ctx.getString(R.string.notif_agent_task) }
            val text = obj.optString("body").ifEmpty { ctx.getString(R.string.notif_task_complete) }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, ctx.getString(R.string.notif_channel_agent), NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
            ) {
                val routed = app.dsh.mobile.service.EngineService.pushAgentNotice(ctx.getString(R.string.notif_agent_prefix, title, text))
                return if (routed) {
                    200 to """{"ok":true,"result":"shown on engine foreground notice (grant POST_NOTIFICATIONS for heads-up alerts)"}"""
                } else {
                    403 to """{"ok":false,"error":"notification permission not granted and engine service not running"}"""
                }
            }
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(ctx, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(ctx)
            }
            builder.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
            nm.notify(System.currentTimeMillis().toInt() and 0x7FFFFFFF, builder.build())
            200 to """{"ok":true}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
        }
    }

    internal fun respond(client: Socket, status: Int, json: String) {
        with(bridge) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        // 紧凑读屏返回的是 text/plain 行格式（省掉 JSON 转义膨胀）；
        // 其余端点都是 JSON。按首字符粗判即可，避免为类型再传参。
        val isJson = json.startsWith("{") || json.startsWith("[")
        val ct = if (isJson) "application/json; charset=utf-8" else "text/plain; charset=utf-8"
        val head = "HTTP/1.1 $status OK\r\n" +
            "Content-Type: $ct\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.UTF_8))
            write(bytes)
            flush()
        }
        }
    }

    internal fun say(ctx: Context, body: String): Pair<Int, String> {
        with(bridge) {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"body must be {\"text\":\"...\",\"flush\":false}"}"""
        val text = obj.optString("text")
        if (text.isBlank()) return 400 to """{"ok":false,"error":"text is blank"}"""
        // TTS init/speak 可能阻塞数秒（系统 TTS 服务冷启动），异步执行防 HTTP 超时
        Thread({ TtsManager.speak(ctx, text, obj.optBoolean("flush", false)) }, "dsh-tts").apply { isDaemon = true; start() }
        return 200 to """{"ok":true,"result":"accepted"}"""
        }
    }

    internal fun sayGet(ctx: Context, rawPath: String): Pair<Int, String> {
        with(bridge) {
        val q = rawPath   // 传入的已是去掉 '?' 的纯 query 串
        val text = urlDecode(q.substringAfter("text=", "").substringBefore("&flush"))
        val flush = q.contains("flush=1")
        if (text.isBlank()) return 400 to """{"ok":false,"error":"text is blank"}"""
        val obj = org.json.JSONObject().put("text", text).put("flush", flush)
        return say(ctx, obj.toString())
        }
    }

}
