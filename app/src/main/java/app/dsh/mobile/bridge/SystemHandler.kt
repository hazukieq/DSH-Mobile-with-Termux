package app.dsh.mobile.bridge

import app.dsh.mobile.engine.AgentBridge
import app.dsh.mobile.engine.*

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

/**
 * 系统/运维类端点：状态、本地运行时、守护、插件、设置、内存、日志诊断。
 *
 * Phase 2 从 AgentBridge 提出；与无障碍/手势无关，AgentBridge 内部成员通过 [bridge] 访问。
 */
internal class SystemHandler(private val bridge: AgentBridge) {
   fun status(): Pair<Int, String> {
        val ctx = bridge.shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        return runCatching {
            val pm = ctx.packageManager
            val pi = pm.getPackageInfo(ctx.packageName, 0)
            val verCode = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            val app = JSONObject()
                .put("package", ctx.packageName)
                .put("version_name", pi.versionName)
                .put("version_code", verCode)
                .put("pid", android.os.Process.myPid())
                .put("uid", android.os.Process.myUid())
                .put("foreground", AppState.isForeground(ctx))
                .put("resumed_activity", AppState.resumedActivityName() ?: JSONObject.NULL)
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("model", android.os.Build.MODEL)

            val engine = JSONObject()
                .put("port", 3080)
                .put("priv_mode", Privilege.getMode(ctx).name.lowercase())
                .put("webview_debug", bridge.webViewDebugEnabled(ctx))

            val net = JSONObject()
            runCatching {
                val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
                val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                val type = when {
                    caps == null -> "none"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_USB) -> "usb"
                    else -> "other"
                }
                net.put("connected", caps != null)
                    .put("type", type)
                    .put("validated", caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
                    .put("internet", caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false)
                    .put("vpn", caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ?: false)
                    .put("metered", cm.isActiveNetworkMetered)
                    .put("down_kbps", caps?.linkDownstreamBandwidthKbps ?: 0)
                    .put("up_kbps", caps?.linkUpstreamBandwidthKbps ?: 0)
            }.onFailure { net.put("error", it.message) }

            val battery = JSONObject()
            runCatching {
                val i = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val statusInt = i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                battery.put("percent", pct)
                    .put("charging", statusInt == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        statusInt == android.os.BatteryManager.BATTERY_STATUS_FULL)
                    .put("temperature_c", (i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
            }

            val memory = JSONObject()
            runCatching {
                val am = ctx.getSystemService(android.app.ActivityManager::class.java)
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                memory.put("avail_mb", mi.availMem / 1048576).put("total_mb", mi.totalMem / 1048576).put("low", mi.lowMemory)
            }

            val storage = JSONObject()
            runCatching {
                val fs1 = android.os.StatFs(ctx.filesDir.absolutePath)
                storage.put("files_free_mb", fs1.availableBytes / 1048576)
                runCatching { storage.put("sdcard_free_mb", android.os.StatFs("/sdcard").availableBytes / 1048576) }
            }

            val screen = JSONObject()
            runCatching {
                val pm2 = ctx.getSystemService(android.os.PowerManager::class.java)
                val km = ctx.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                screen.put("on", pm2.isInteractive).put("locked", km.isKeyguardLocked)
            }

            val perms = JSONObject()
                .put("accessibility", DshAccessibilityService.isEnabled(ctx))
                .put("accessibility_connected", DshAccessibilityService.isConnected())
                .put("all_files", runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false))
                .put("overlay", runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false))
                .put("notifications", if (android.os.Build.VERSION.SDK_INT >= 33)
                    ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED
                else true)
                .put("battery_optimization_ignored", runCatching {
                    (ctx.getSystemService(android.os.PowerManager::class.java))
                        .isIgnoringBatteryOptimizations(ctx.packageName)
                }.getOrDefault(false))

            val media = JSONObject()
                .put("workspaceMediaVisible", MediaHygiene.workspaceMediaVisible(ctx))
                .put("trashMediaVisible", MediaHygiene.trashMediaVisible())
            return 200 to JSONObject()
                .put("ok", true)
                .put("app", app)
                .put("media", media)
                .put("engine", engine)
                .put("net", net)
                .put("battery", battery)
                .put("memory", memory)
                .put("storage", storage)
                .put("screen", screen)
                .put("permissions", perms)
                .toString()
        }.getOrElse { 500 to """{"ok":false,"error":"${it.message}"}""" }
    }

   fun processInfo(): Pair<Int, String> {
        val ctx = bridge.shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        val pid = android.os.Process.myPid()
        val flag = bridge.webViewDebugEnabled(ctx)
        val ver = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull().orEmpty()
        val json = JSONObject()
            .put("ok", true)
            .put("app_pid", pid)
            .put("app_uid", android.os.Process.myUid())
            .put("package", ctx.packageName)
            .put("version_name", ver)
            .put("webview_debug", flag)
            .put("devtools_socket", if (flag) "webview_devtools_remote_$pid" else JSONObject.NULL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("model", android.os.Build.MODEL)
        return 200 to json.toString()
    }

   fun localStatus(ctx: Context): Pair<Int, String> =
        200 to JSONObject().apply {
            put("ok", true)
            put("modelReady", LocalModel.isModelReady(ctx))
            put("modelSize", LocalModel.modelFile(ctx).takeIf { it.isFile }?.length() ?: 0L)
            put("serverBinary", LocalModel.serverBinaryPresent(ctx))
            put("lastError", LocalModel.lastError ?: "")
            put("installHint", LocalModel.installHint())
            put("modelUrl", LocalModel.modelUrl())
            put("serverRunning", LocalModel.isServerRunning())
            put("modelPath", LocalModel.modelFile(ctx).absolutePath)
            put("port", LocalEmbedder.PORT)
            // v1.2.124：本地判决 AI 的实际用量（exact/semantic/none + verify 语义救场）
            put("stats", FastPicker.stats.snapshot())
        }.toString()

   fun localEnsure(ctx: Context, query: String): Pair<Int, String> {
        val install = bridge.parseQuery(query)["install"] in listOf("1", "true", "yes")
        Thread({
            if (install && !LocalModel.serverBinaryPresent(ctx)) {
                Log.i("LocalModel", "install requested: pkg install -y llama-cpp")
                runCatching {
                    val bash = java.io.File(EngineConfig.engineRoot(ctx), "bin/bash")
                    val pb = ProcessBuilder(bash.absolutePath, "-lc", "pkg install -y llama-cpp")
                    pb.redirectErrorStream(true)
                    pb.environment()["PREFIX"] = EngineConfig.engineRoot(ctx).absolutePath
                    pb.environment()["PATH"] = "${EngineConfig.engineRoot(ctx).absolutePath}/bin:/system/bin"
                    val p = pb.start()
                    val out = p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    p.waitFor(15, java.util.concurrent.TimeUnit.MINUTES)
                    Log.i("LocalModel", "pkg llama-cpp done: ${out.takeLast(300)}")
                }.onFailure { Log.w("LocalModel", "pkg llama-cpp failed: ${it.message}") }
            }
            val ok = LocalModel.ensureReady(ctx) { _, msg ->
                Log.i("LocalModel", "ensure: $msg")
            }
            Log.i("LocalModel", "ensureReady -> $ok")
        }, "local-model-ensure").apply { isDaemon = true }.start()
        return 202 to JSONObject().put("ok", true).put("started", true).put("install", install)
            .put("hint", "poll GET /local/status").toString()
    }

   fun daemonStatus(): Pair<Int, String> {
        val tasks = java.io.File(dshHome(), "tasks")
        val out = org.json.JSONObject()
        val arr = org.json.JSONArray()
        tasks.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { f ->
            runCatching {
                val o = org.json.JSONObject(f.readText())
                val st = o.optString("status")
                if (st == "running" || st == "queued" || o.optBoolean("needsHelp", false)) {
                    arr.put(org.json.JSONObject().apply {
                        put("id", o.optString("id", f.nameWithoutExtension))
                        put("status", st)
                        put("needsHelp", o.optBoolean("needsHelp", false))
                        put("goal", o.optString("goal", "").take(60))
                        put("rounds", o.optJSONArray("history")?.length() ?: 0)
                    })
                }
            }
        }
        out.put("active", arr.length() > 0)
        out.put("tasks", arr)
        return 200 to out.toString()
    }

   fun daemonStop(): Pair<Int, String> {
        val tasks = java.io.File(dshHome(), "tasks")
        var stopped = 0
        tasks.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { f ->
            runCatching {
                val o = org.json.JSONObject(f.readText())
                if (o.optString("status") == "running") {
                    o.put("stopRequested", true)
                    f.writeText(o.toString())
                    java.io.File(tasks, o.optString("id", f.nameWithoutExtension) + ".stop")
                        .writeText("stop")
                    stopped++
                }
            }
        }
        return 200 to """{"ok":true,"stopped":$stopped}"""
    }

   fun pluginList(): Pair<Int, String> {
        val list = runCatching { PluginCenter.list() }.getOrDefault(emptyList())
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id); put("package", p.pkg)
                    put("desc", p.desc); put("repo", p.repo)
                },
            )
        }
        return 200 to arr.toString()
    }

   fun pluginInstall(body: String): Pair<Int, String> {
        val pkg = runCatching { JSONObject(body).optString("package") }
            .getOrDefault("").trim()
        if (pkg.isBlank()) return 400 to """{"ok":false,"error":"field 'package' required"}"""
        val ctx = bridge.shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        PluginCenter.install(ctx, pkg) { ok, msg ->
            runCatching {
                bridge.notify(
                    ctx,
                    JSONObject().apply {
                        put("title", if (ok) "Plugin installed" else "Plugin install failed")
                        put("body", "$pkg — ${msg.take(120)}")
                    }.toString(),
                )
            }
        }
        return 202 to """{"ok":true,"started":true,"package":"$pkg"}"""
    }

   fun settingsGet(): Pair<Int, String> {
        val ctx = bridge.shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        val ui = ctx.getSharedPreferences(DshApp.PREFS_UI, android.content.Context.MODE_PRIVATE)
        val out = JSONObject()
            .put("page_scale", ui.getInt("page_scale", 90))
            .put("orientation", OrientationPrefs.get(ctx).key)
            .put("lan_web_access", ui.getBoolean(DshApp.KEY_LAN_WEB, false))
            .put("lan_web_password_set", (ui.getString("lan_web_password", "") ?: "").isNotBlank())
            .put("a11y_max_provider", ui.getString(DshApp.KEY_A11Y_MAX_PROVIDER, "") ?: "")
            .put("priv_mode", Privilege.getMode(ctx).name.lowercase())
            .put("writable_keys", JSONObject().put("page_scale", "int 50..120")
                .put("orientation", "portrait|landscape|auto")
                .put("lan_web_access", "boolean")
                .put("a11y_max_provider", "credential ref string")
                .put("priv_mode", "normal|shizuku|root (restarts engine)"))
        return 200 to JSONObject().put("ok", true).put("settings", out).toString()
    }

   fun settingsSet(body: String): Pair<Int, String> {
        val ctx = bridge.shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val set = obj.optJSONObject("set") ?: return 400 to """{"ok":false,"error":"need {\"set\":{...}}"}"""
        val ui = ctx.getSharedPreferences(DshApp.PREFS_UI, android.content.Context.MODE_PRIVATE).edit()
        val applied = org.json.JSONArray()
        val errors = org.json.JSONArray()
        var restartRequired = false
        val keys = set.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (key) {
                "page_scale" -> {
                    val v = set.optInt(key, -1)
                    if (v in 50..120) { ui.putInt("page_scale", v); applied.put("page_scale=$v") }
                    else errors.put("page_scale must be 50..120")
                }
                "orientation" -> {
                    val v = set.optString(key)
                    val mode = when (v) {
                        "portrait" -> OrientationMode.PORTRAIT
                        "landscape" -> OrientationMode.LANDSCAPE
                        "auto" -> OrientationMode.AUTO
                        else -> null
                    }
                    if (mode != null) {
                        OrientationPrefs.set(ctx, mode)
                        runCatching {
                            AppState.currentActivity()?.requestedOrientation =
                                OrientationPrefs.toActivityInfo(mode)
                        }
                        applied.put("orientation=$v")
                    } else errors.put("orientation must be portrait|landscape|auto")
                }
                "lan_web_access" -> {
                    val v = set.optBoolean(key, false)
                    DshApp.setLanWebEnabled(ctx, v)
                    if (v) DshApp.lanWebPassword(ctx)
                    applied.put("lan_web_access=$v")
                    restartRequired = true
                }
                "a11y_max_provider" -> {
                    ui.putString(DshApp.KEY_A11Y_MAX_PROVIDER, set.optString(key))
                    applied.put("a11y_max_provider")
                }
                "priv_mode" -> {
                    val v = set.optString(key).lowercase()
                    val mode = when (v) {
                        "normal" -> PrivMode.NORMAL
                        "shizuku" -> PrivMode.SHIZUKU
                        "root" -> PrivMode.ROOT
                        else -> null
                    }
                    if (mode != null) {
                        Privilege.setMode(ctx, mode)
                        applied.put("priv_mode=$v")
                        restartRequired = true
                    } else errors.put("priv_mode must be normal|shizuku|root")
                }
                else -> errors.put("unknown key: $key")
            }
        }
        ui.apply()
        val ok = applied.length() > 0
        val payload = JSONObject().put("ok", ok)
            .put("applied", applied)
            .put("restartRequired", restartRequired)
            .put("errors", errors)
        if (!ok) payload.put("hint", "see /settings writable_keys")
        return (if (ok) 200 else 400) to payload.toString()
    }

   fun logHose(ctx: Context, rawQuery: String): Pair<Int, String> {
        val query = LinkedHashMap<String, String>()
        rawQuery.trimStart('?').split('&').forEach { kv ->
            if (kv.isNotBlank()) { val i = kv.indexOf('='); if (i > 0) query[kv.substring(0, i)] = kv.substring(i + 1) }
        }
        query["mark"]?.let { app.dsh.mobile.DshLog.d("mark", it) }
        val arr = org.json.JSONArray()
        if (query["crash"] == "1") {
            val f = app.dsh.mobile.DshLog.crashFile(ctx)
            if (!f.exists()) return 404 to """{"ok":false,"error":"no_crash_file"}"""
            f.readLines().takeLast(120).forEach { arr.put(it) }
            return 200 to org.json.JSONObject().put("ok", true).put("file", "crash.log").put("lines", arr).toString()
        }
        val n = (query["n"]?.toIntOrNull() ?: 200).coerceIn(20, 800)
        app.dsh.mobile.DshLog.fileTail(n).forEach { arr.put(it) }
        return 200 to org.json.JSONObject().put("ok", true).put("n", arr.length())
            .put("lastError", app.dsh.mobile.DshLog.lastError ?: "")
            .put("lines", arr).toString()
    }

   fun diag(ctx: Context): Pair<Int, String> {
        val app = ctx.applicationContext as app.dsh.mobile.DshApp
        val engineState = try {
            app.supervisor.state.value.toString()
        } catch (e: Exception) { "unknown: " + e.message }
        val runtimeVer = runCatching {
            java.io.File(EngineConfig.engineRoot(ctx), ".runtime-version").readText().trim()
        }.getOrDefault("(unreadable)")
        val feHtml = runCatching {
            java.io.File(
                EngineConfig.engineRoot(ctx),
                "lib/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html"
            ).readText()
        }.getOrDefault("")
        val poly = if (feHtml.isEmpty()) "frontend index.html missing!"
            else if (feHtml.contains("legacy-webview polyfill")) "present" else "MISSING"
        val logTail = runCatching {
            val f = java.io.File(ctx.filesDir, "engine.log")
            if (f.isFile) f.readText().takeLast(2500) else "(no engine.log)"
        }.getOrDefault("(log read failed)")

        // 3080 HTTP 探测
        val httpProbe = runCatching {
            val c = (java.net.URL("http://127.0.0.1:3080/").openConnection()
                    as java.net.HttpURLConnection).apply { connectTimeout = 4000; readTimeout = 4000 }
            val code = c.responseCode
            // readNBytes(int) 是 API 33+；Android 11 上会 NoSuchMethod——用 Kotlin readBytes 截断
            val head = c.getInputStream().use { it.readBytes().take(120).toByteArray().toString(Charsets.UTF_8) }
            c.disconnect()
            "HTTP $code | head: ${head.replace(java.lang.System.lineSeparator(), " ")}"
        }.getOrElse { "FAIL: ${it.message}" }

        // WS 握手探测（多候选路径）。CRLF 由常量拼接，避免源码内嵌换行歧义。
        val crlf = "\r\n"
        val wsProbe = listOf("/ws", "/", "/api/ws", "/socket").joinToString("<br>") { p ->
            runCatching {
                java.net.Socket("127.0.0.1", 3080).use { sock ->
                    sock.soTimeout = 4000
                    val req = "GET $p HTTP/1.1" + crlf +
                        "Host: 127.0.0.1:3080" + crlf +
                        "Upgrade: websocket" + crlf +
                        "Connection: Upgrade" + crlf +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" + crlf +
                        "Sec-WebSocket-Version: 13" + crlf + crlf
                    sock.getOutputStream().write(req.toByteArray())
                    sock.getOutputStream().flush()
                    val first = ByteArray(64)
                    val n = sock.getInputStream().read(first)
                    if (n <= 0) "no response" else String(first, 0, n).split(crlf)[0]
                }
            }.getOrElse { "FAIL: ${it.message}" }
        }.let { "WS handshake:<br>" + it }

        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH diag</title><style>body{font-family:monospace;background:#111;color:#eee;padding:12px;font-size:13px;word-break:break-all}pre{white-space:pre-wrap;background:#1c1c1c;padding:8px;border-radius:6px}h3{color:#7DD3FC;margin:14px 0 4px}</style></head><body>
<h3>1. WebView UA</h3><pre id="ua"></pre>
<h3>2. JS API probe (false = missing)</h3><pre id="api"></pre>
<h3>3. Engine state</h3><pre>$engineState</pre>
<h3>4. HTTP probe on 3080</h3><pre>$httpProbe</pre>
<h3>5. WebSocket probe</h3><pre>$wsProbe</pre>
<h3>6. Runtime version</h3><pre>$runtimeVer</pre>
<h3>7. Frontend polyfill</h3><pre>$poly</pre>
<h3>8. engine.log tail</h3><pre>$logTail</pre>
<script>
document.getElementById('ua').textContent = navigator.userAgent;
var checks = [
  ['Object.hasOwn (93+)', function(){ return typeof Object.hasOwn === 'function'; }],
  ['Array.prototype.at (92+)', function(){ return typeof Array.prototype.at === 'function'; }],
  ['String.prototype.at (92+)', function(){ return typeof String.prototype.at === 'function'; }],
  ['Element.replaceChildren (86+)', function(){ return typeof Element.prototype.replaceChildren === 'function'; }],
  ['String.replaceAll (85+)', function(){ return typeof String.prototype.replaceAll === 'function'; }],
  ['crypto.randomUUID (92+)', function(){ return typeof crypto.randomUUID === 'function'; }],
  ['structuredClone (98+)', function(){ return typeof structuredClone === 'function'; }],
  ['Array.findLast (97+)', function(){ return typeof Array.prototype.findLast === 'function'; }]
];
document.getElementById('api').textContent = checks.map(function(c){
  var ok = false; try { ok = c[1](); } catch (e) { ok = 'ERR ' + e.message; }
  return c[0] + ' = ' + ok;
}).join('
');
</script></body></html>"""
        return 200 to html
    }

   fun memoryLookup(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val goal = obj.optString("goal").trim()
        if (goal.isBlank()) return 400 to """{"ok":false,"error":"field 'goal' required"}"""
        var dump = obj.optString("dump")
        if (dump.isBlank()) {
            val svc = DshAccessibilityService.instance
                // 条件依赖：给了 body.dump 就完全不碰无障碍；没给才需要读屏。
                ?: return 503 to JSONObject()
                    .put("ok", false)
                    .put("error", "a11y_off")
                    .put("code", "a11y_off")
                    .put("reason", "computing the scene fingerprint needs a screen read; pass the node-tree dump in body.dump to skip it, or enable 'DSH Mobile Helper (optional)' in Settings > Accessibility")
                    .put("how_to_fix", "add body.dump (text from /screen), or POST /a11y/enable and enable the service")
                    .put("retryable", true)
                    .toString()
            dump = bridge.withScreenTimeout("memory.dump") { svc.dumpScreenCompact("all") }
                ?.getOrNull() ?: return 504 to """{"ok":false,"error":"screen read timed out"}"""
        }
        val fp = SceneMemory.fingerprint(dump, DshAccessibilityService.instance?.foregroundPackage() ?: "")
        val (entry, sim) = SceneMemory.lookup(ctx, goal, fp)
        return if (entry != null) {
            200 to JSONObject().apply {
                put("ok", true).put("hit", true)
                put("similarity", "%.2f".format(sim).toDouble())
                put("reused", entry.hits)
                put("plan", entry.plan)
            }.toString()
        } else {
            200 to JSONObject().apply {
                put("ok", true).put("hit", false)
                put("bestSimilarity", "%.2f".format(sim).toDouble())
                put("fpSize", fp.size)
            }.toString()
        }
    }

   fun memoryStore(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val goal = obj.optString("goal").trim()
        val plan = obj.optJSONObject("plan")
        if (goal.isBlank() || plan == null) return 400 to """{"ok":false,"error":"fields 'goal' and 'plan' required"}"""
        var dump = obj.optString("dump")
        if (dump.isBlank()) {
            val svc = DshAccessibilityService.instance
                // 条件依赖：给了 body.dump 就完全不碰无障碍；没给才需要读屏。
                ?: return 503 to JSONObject()
                    .put("ok", false)
                    .put("error", "a11y_off")
                    .put("code", "a11y_off")
                    .put("reason", "computing the scene fingerprint needs a screen read; pass the node-tree dump in body.dump to skip it, or enable 'DSH Mobile Helper (optional)' in Settings > Accessibility")
                    .put("how_to_fix", "add body.dump (text from /screen), or POST /a11y/enable and enable the service")
                    .put("retryable", true)
                    .toString()
            dump = bridge.withScreenTimeout("memory.dump") { svc.dumpScreenCompact("all") }
                ?.getOrNull() ?: return 504 to """{"ok":false,"error":"screen read timed out"}"""
        }
        val fp = SceneMemory.fingerprint(dump, DshAccessibilityService.instance?.foregroundPackage() ?: "")
        SceneMemory.store(ctx, goal, fp, plan)
        return 200 to JSONObject().put("ok", true).put("stored", true)
            .put("fpSize", fp.size).put("summary", SceneMemory.stats(ctx)).toString()
    }

   fun memoryConfirm(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val goal = obj.optString("goal").trim()
        if (goal.isBlank()) return 400 to """{"ok":false,"error":"field 'goal' required"}"""
        val n = SceneMemory.confirm(ctx, goal)
        return 200 to JSONObject().put("ok", true).put("promoted", n)
            .put("pendingLeft", SceneMemory.pendingCount(ctx)).toString()
    }

   fun dshHome(): java.io.File {
        val c = bridge.shotCtx ?: return java.io.File("")
        return java.io.File(EngineConfig.engineRoot(c).parentFile, "dsh-home")
    }

   fun visionDir(ctx: Context): java.io.File =
        java.io.File(ctx.filesDir, "vision").apply { mkdirs() }

   fun loadTemplate(ctx: Context, id: String): Triple<IntArray, Int, Int>? {
        val f = java.io.File(visionDir(ctx), "$id.png")
        if (!f.exists()) return null
        return app.dsh.mobile.VisionTracker.decodePng(f.readBytes())
    }

   fun visionList(ctx: Context): Pair<Int, String> {
        val files = visionDir(ctx).listFiles { f -> f.name.endsWith(".png") } ?: emptyArray()
        val arr = org.json.JSONArray()
        files.forEach { arr.put(JSONObject().put("id", it.nameWithoutExtension).put("bytes", it.length())) }
        return 200 to JSONObject().put("ok", true).put("templates", arr).toString()
    }
}
