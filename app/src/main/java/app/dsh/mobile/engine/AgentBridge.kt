package app.dsh.mobile.engine

import app.dsh.mobile.bridge.RuntimeHandler
import app.dsh.mobile.bridge.PtyHandler
import app.dsh.mobile.bridge.a11ySelfCheck
import app.dsh.mobile.bridge.Router
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

/**
 * Agent 能力桥：监听 127.0.0.1:3083，把能力端点暴露给引擎内 Agent（原生 ServerSocket 环回 HTTP）。
 *
 * 业务端点已按组拆到 bridge/：屏幕与 OCR 见 ScreenHandler，设备动作见 ActionHandler，
 * App/Intent 与通知见 AppHandler，视觉定位见 VisionHandler；路由分发见 bridge/Router.kt，
 * 无障碍门禁分类见 bridge/A11yGate.kt（维护规范见 docs/A11Y-SCOPE.md）。
 * 本文件只保留生命周期（start/stop）、HTTP 鉴权与请求处理（handle）、回执装饰。
 */
object AgentBridge {
    internal const val TAG = "AgentBridge"
    const val PORT = 3083
    internal const val CHANNEL_ID = "agent_notify"

    @Volatile internal var server: ServerSocket? = null
    @Volatile internal var thread: Thread? = null

    /** 上一次动作发生时的前台包名（用于回报 foregroundChanged，见 tap 的注释） */
    @Volatile internal var lastActionPkg: String? = null

    /** 最近一次坐标 tap 的 (x, y, via)，用于回执自证落点。 */
    @Volatile internal var lastTapXy: Triple<Int, Int, String>? = null
    @Volatile internal var lastTapDetail: JSONObject? = null

    /** 包名匹配：完全相等，或一方是另一方的前缀（容忍 caller 只写包名前缀） */
    internal fun pkgMatches(current: String, expect: String): Boolean =
        current == expect || current.startsWith(expect) || expect.startsWith(current)

    /** 应用上下文（start 时记住，供截图落盘等需要路径的操作使用） */
    @Volatile internal var shotCtx: Context? = null

    /**
     * 每个动作入口都调一次：让悬浮窗立刻显示「执行中 · <动作类型>」+ 具体指令。
     *
     * 🔴 v1.2.72 修主人实测「展开悬浮窗也看不到 AI 干活的指令」：
     * 此前**只有 `/tap`** 会通知悬浮窗，滑动 / 按键 / 输入文字 / 滚动查找 / 批量**全都没有**。
     * AI 的实际操作里 tap 只占一部分，所以用户展开也经常一片空白。
     */
    internal fun announceAction(actionRes: Int, detail: String) {
        runCatching {
            val c = shotCtx ?: return@runCatching
            // v2.3.7（用户反馈「无障碍点击动作占用思考链 layout」）：
            // 旧实现把动作写进 overlayStatus（即 [思考]/[工具]/[结果] 链的正文视图），
            // 一次 tap 就把整段思考链覆盖成「点击」，而且占用滚动区高度。
            // 动作只属于独立的动作行（overlayAction），不碰思考链正文。
            val label = c.getString(actionRes)
            StatusOverlay.setAction(if (detail.isBlank()) label else "$label · $detail")
        }
    }

    /**
     * 桥访问 token（v1.2.99）。
     *
     * ## 为什么需要
     * 桥监听 `127.0.0.1:3083`，把读屏 / 点击 / 通知 / 手势等能力暴露成 HTTP。
     * 未鉴权时，**同设备上任何 App** 都能直接调用 —— 等于把无障碍能力借给第三方。
     * 加 token 门控后，只有拿到 token 的引擎内 Agent 能调。
     *
     * ## 传递方式
     * token 每次进程启动随机生成（不落盘、不复用），写入引擎环境变量
     * `DSH_BRIDGE_TOKEN`；`_dsh_http.sh` 自动带上 `X-DSH-Token` 头。
     * 因此**只有引擎子进程**（及其派生的 AI 命令）能通过校验。
     *
     * ## 兼容
     * 引擎未重启时（旧进程无 token）→ 会 403。调用方（scr/notify 等闸门脚本）
     * 读的是当前进程环境变量，与引擎同源，故不受影响。
     */
    @Volatile internal var token: String? = null

    /** 当前 token（供 EngineConfig 注入环境变量） */
    fun currentToken(): String? = token

    internal fun ensureToken(ctx: Context) {
        if (token == null) {
            token = java.util.UUID.randomUUID().toString().replace("-", "")
            Log.i(TAG, "bridge token generated")
        }
        writeTokenFile(ctx)
    }

    /**
     * 把桥 token 同步写一份到 `<engineRoot>/.bridge-token`（0600）。
     *
     * 为什么需要文件而不是只靠环境变量：dsh 的 subprocess 在构造子进程环境时会
     * **剔掉所有 `DSH_*` 变量**（`dsh-subprocess` 的 `scrubbedParentEnv`），
     * 于是 AI 的 bash 工具、WebUI 侧边栏终端里都没有 `DSH_BRIDGE_TOKEN`，
     * 所有门脚本（pkg / notify / scr / say / dsh-update）请求桥都被 403。
     * 文件放在 App 私有目录、权限 0600：只有同一 uid 的 App 子进程可读，
     * 第三方 App 依然拿不到。
     */
    internal fun writeTokenFile(ctx: Context) {
        val tok = token ?: return
        runCatching {
            val root = EngineConfig.engineRoot(ctx)
            if (!root.isDirectory) return@runCatching
            val f = java.io.File(root, ".bridge-token")
            if (!f.isFile || f.readText().trim() != tok) {
                f.writeText(tok)
                f.setReadable(false, false)
                f.setReadable(true, true)
                f.setWritable(false, false)
                f.setWritable(true, true)
            }
        }.onFailure { Log.w(TAG, "write private bridge token file failed") }
    }

    /**
     * 校验请求：`X-DSH-Token` 头或 `?token=` 查询参数须等于当前 token。
     *
     * fail-closed：token 未生成（异常状态）时**拒绝**而非放行 —— 桥暴露的是
     * 高权限设备能力，宁可让 Agent 报错也不要静默开放。
     */
    internal fun authorised(headers: List<String>): Boolean =
        Privilege.authorisedBridgeRequest(headers)

    fun start(ctx: Context) {
        app.dsh.mobile.DshLog.init(ctx)
        if (server != null) return
        shotCtx = ctx.applicationContext
        // v1.2.160：LAN 代理需要 app context 推导引擎目录（部署脚本 / 拉起 node 进程）
        runCatching { LanWebProxy.setContext(ctx) }
        // 桥 token（v1.2.99）：每次启动随机生成，只通过环境变量交给引擎内的 Agent。
        // 同设备第三方 App 拿不到 → 无法调用读屏/点击/通知等能力。
        ensureToken(ctx)
        try {
            // 释放栈里绝不能出现明文 token：既不打日志、也不写共享存储。
            // 第三方 App 只要拿到它就能读屏/注入点击，所以 token 只留在
            // App 私有目录 `<engineRoot>/.bridge-token`（0600，仅同 uid 可读），
            // 供门脚本读取。这里只保证「不再写出去」，不去删除用户存储里已有的文件。
            val ss = ServerSocket(PORT, 16, java.net.InetAddress.getByName("127.0.0.1"))
            server = ss
            thread = Thread({
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        handle(ctx.applicationContext, client)
                    } catch (e: Exception) {
                        if (!ss.isClosed) Log.w(TAG, "accept: ${e.message}")
                    }
                }
            }, "AgentBridge").apply { isDaemon = true; start() }
            Log.i(TAG, "agent bridge started on 127.0.0.1:$PORT")
            // 对话完成监听（v1.2.57）：不依赖 AI 主动调 notify —— 只要会话写入
            // turn/end 事件就提示用户（用户反馈「任务完成后通知栏没有任何信息」）。
            TurnWatcher.start(ctx.applicationContext) { session ->
                onTurnEnd(ctx.applicationContext, session)
            }
        } catch (e: Exception) {
            // ⚠️ v1.2.104：**bind 失败绝不能再静默**（主人反馈的起点）。
            // 旧实现只打一行 Log.w，于是「notify / scr / say 全哑」在现场被反复
            // 误判成「无障碍没开 / 桥没就绪」，真因（端口被占）谁也看不见。
            // 现在把占用者一并查出来 —— 典型元凶是继承了桥 listen socket 的历史
            // 孤儿（3083 无 O_CLOEXEC，见 OrphanReaper 顶部说明），并主动告知用户。
            val holder = portHolder()
            Log.e(
                TAG,
                "bridge bind FAILED on 127.0.0.1:$PORT: ${e.message}" +
                    (holder?.let { "; holder=$it" } ?: ""),
            )
            // 也落 engine.log：用户/支持要把这个文件发出来，logcat 抓不到历史
            runCatching {
                java.io.File(EngineConfig.engineRoot(ctx), "engine.log")
                    .appendText("[bridge] bind FAILED on 127.0.0.1:$PORT: ${e.message}; holder=${holder ?: "?"}\n")
            }
            runCatching {
                appHandler.notify(
                    ctx,
                    JSONObject().apply {
                        put("title", ctx.getString(R.string.bridge_down_title))
                        put(
                            "body",
                            ctx.getString(
                                R.string.bridge_down_body,
                                PORT,
                                holder ?: e.message.orEmpty(),
                            ),
                        )
                    }.toString(),
                )
            }.onFailure { Log.w(TAG, "bridge-down notify failed: ${it.message}") }
        }
    }

    /**
     * 谁占着 [PORT]（v1.2.104）。
     *
     * `/proc/net/tcp` 的 LISTEN 行给出 socket inode 与所有者 uid；再遍历
     * `/proc/<pid>/fd` 反查哪个进程打开着这个 inode（**同 uid 才看得见**，
     * 别的 uid 直接跳过 —— 不做猜测）。返回形如 `uid=10228 inode=39939 pid=6760(node ...)`。
     */
    internal fun portHolder(): String? = runCatching {
        val f = java.io.File("/proc/net/tcp").readLines().drop(1)
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { c -> c.size > 9 && c[1].endsWith("%04X".format(PORT)) && c[3] == "0A" }
            ?: return@runCatching null
        val inode = f[9]
        val uid = f[7]
        val owner = File("/proc").listFiles()?.firstNotNullOfOrNull { p ->
            val n = p.name.toIntOrNull() ?: return@firstNotNullOfOrNull null
            if (!p.isDirectory) return@firstNotNullOfOrNull null
            val hit = runCatching {
                File(p, "fd").list()?.any { fd ->
                    runCatching {
                        java.nio.file.Files.readSymbolicLink(File(p, "fd/$fd").toPath()).toString()
                    }.getOrNull()?.contains("socket:[$inode]") == true
                } == true
            }.getOrDefault(false)
            if (!hit) return@firstNotNullOfOrNull null
            val cmd = runCatching {
                File(p, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ').trim()
            }.getOrNull().orEmpty()
            "pid=$n(${cmd.take(50)})"
        }
        "uid=$uid inode=$inode " + (owner ?: "owner-hidden")
    }.getOrNull()

    /**
     * 一轮对话结束时的处理（v1.2.57）。
     * 发系统通知 + 悬浮窗高亮；若用户开了 TTS 播报则一并朗读。
     *
     * 与 AI 主动调 `notify` 的关系：两者互补 —— AI 的 notify 可带自定义内容
     * （如「构建完成，测试全过」），本监听是**兜底**：AI 忘了调也不会漏提示。
     */
    internal fun onTurnEnd(ctx: Context, session: String) {
        runCatching {
            Log.i(TAG, "turn/end detected in $session")
            StatusOverlay.flashComplete(ctx.getString(R.string.overlay_turn_done))
            appHandler.notify(
                ctx,
                JSONObject().apply {
                    put("title", ctx.getString(R.string.notif_turn_done_title))
                    put("body", ctx.getString(R.string.notif_turn_done_body, session.take(8)))
                }.toString(),
            )
            // TTS 播报（v1.2.57）：用户可在设置里开关；默认关闭避免打扰。
            // 与 say 门脚本共用 TtsManager（系统 TTS，离线免费）。
            if (ttsOnComplete(ctx)) {
                Thread(
                    { TtsManager.speak(ctx, ctx.getString(R.string.overlay_tts_done), false) },
                    "dsh-tts-turn",
                ).apply { isDaemon = true; start() }
            }
        }.onFailure { Log.w(TAG, "turn-end handling failed: ${it.message}") }
    }

    /** 是否开启「完成时语音播报」（设置页开关；缺省关闭） */
    internal fun ttsOnComplete(ctx: Context): Boolean =
        runCatching {
            ctx.getSharedPreferences("dsh_ui", Context.MODE_PRIVATE)
                .getBoolean("tts_on_complete", false)
        }.getOrDefault(false)

    fun stop() {
        runCatching { server?.close() }
        server = null; thread = null
        Log.i(TAG, "agent bridge stopped")
    }

    internal val system by lazy { app.dsh.mobile.bridge.SystemHandler(this) }
    internal val router by lazy { Router(this) }
    internal val screen by lazy { ScreenHandler(this) }
    internal val action by lazy { ActionHandler(this) }
    internal val appHandler by lazy { AppHandler(this) }
    internal val vision by lazy { VisionHandler(this) }

    internal fun handle(ctx: Context, client: Socket) {
        Thread({
            try {
                client.soTimeout = 5_000
                // ⚠️ v1.2.43 修复：必须按**字节**读头与读体。Content-Length 是字节数而
                // 旧实现用 CharArray/Reader 按「字符数」读 → UTF-8 多字节字符（中文每字 3 字节）
                // 永远等不到足量字符 → SocketTimeoutException → 返回
                // {"ok":false,"error":"Read timed out"}。实测：AI 用 `say` 说中文必挂、
                // `/notify` 带中文同挂（App 自身测试不走 HTTP，故看起来「TTS 是好的」）。
                val ins = client.getInputStream()
                val head = java.io.ByteArrayOutputStream()
                var crlf = 0
                while (head.size() < (16 shl 10)) {
                    val b = ins.read()
                    if (b < 0) break
                    head.write(b)
                    crlf = when {
                        crlf == 0 && b == 13 -> 1
                        crlf == 1 && b == 10 -> 2
                        crlf == 2 && b == 13 -> 3
                        crlf == 3 && b == 10 -> 4
                        b == 13 -> 1
                        else -> 0
                    }
                    if (crlf == 4) break
                }
                val lines = String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n")
                val parts = (lines.firstOrNull() ?: return@Thread).split(" ")
                if (parts.size < 2) return@Thread
                val method = parts[0]
                val rawPath = parts[1]
                val path = rawPath.substringBefore('?')
                val query = rawPath.substringAfter('?', "")
                var contentLength = 0
                lines.drop(1).forEach { l ->
                    if (l.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = l.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                val bodyBytes = ByteArray(contentLength.coerceIn(0, 4 shl 20))
                var n = 0
                while (n < bodyBytes.size) {
                    val r = ins.read(bodyBytes, n, bodyBytes.size - n)
                    if (r < 0) break
                    n += r
                }
                val body = if (n > 0) String(bodyBytes, 0, n, StandardCharsets.UTF_8) else ""

                // 桥鉴权（v1.2.99）：只允许携带本机 token 的请求。
                // 为什么需要：桥监听 127.0.0.1:3083，**同设备上任何 App 都能访问** ——
                // 未鉴权时第三方应用可以直接读屏、点击、发通知（等于把无障碍能力
                // 借给任意 App）。token 每次启动随机生成、只写进引擎环境变量，
                // 因此只有引擎内的 Agent 拿得到。
                if (!authorised(lines)) {
                    Log.w(TAG, "unauthorised request: $method $path")
                    appHandler.respond(client, 403, """{"ok":false,"error":"unauthorised"}""")
                    return@Thread
                }

                val (status, json) = action.routeGuarded(ctx, method, path, query, body)
                appHandler.respond(client, status, decorateActionMeta(method, path, status, json))
            } catch (e: Exception) {
                Log.w(TAG, "handle: ${e.message}")
                runCatching { appHandler.respond(client, 500, """{"ok":false,"error":"${e.message}"}""") }
            } finally {
                runCatching { client.close() }
            }
        }, "AgentBridge-req").apply { isDaemon = true; start() }
    }

    /**
     * 即时动作 / 打开页面的回执语义（v1.3.36，用户反馈「即时性太强」）。
     *
     * 模拟动作（tap/swipe/scroll/key/input/vision tap…）是**已派发即完成**的：界面可能
     * 立刻开始变化，甚至用户自己已经操作/返回。Agent 常见的浪费是「动作后马上截图确认」，
     * 得到的往往是动画中间帧或已变化的界面，然后反复重试。
     *
     * 这里统一给动作类响应补充字段，让调用方知道：
     *  - instant=true：动作已派发，不需要即时截图确认；
     *  - verify="none"：默认不需要校验；要校验时先等界面稳定（scr idle / cdp wait）再读一次；
     *  - do_not：不要做的事（立刻截图 / 立刻重试）。
     * 打开页面类（launch/launch-intent/activity）补 handoff=true 同语义。
     */
    internal fun decorateActionMeta(method: String, path: String, status: Int, json: String): String {
        if (status != 200 || method != "POST" || !json.trimStart().startsWith("{")) return json
        val kind = when {
            path in INSTANT_ACTION_PATHS -> "instant"
            path in OPEN_ACTION_PATHS -> "handoff"
            path == "/settings" -> "write"
            else -> return json
        }
        return runCatching {
            val obj = JSONObject(json)
            when (kind) {
                "instant" -> {
                    // v1.8.1：带内联 expect 的 tap 已经自己校验过，不要把 verify/hint 覆盖成
                    // "none/去 dump"；只补 instant 元数据。
                    val inlineVerified = obj.has("verified")
                    obj.put("instant", true)
                    if (!inlineVerified) {
                        obj.put("verify", "none")
                        obj.put("do_not", org.json.JSONArray().put("screenshot_immediately").put("retry_immediately"))
                        if (!obj.has("hint")) {
                            obj.put("hint", "Action dispatched; the UI may already be changing or the user may have moved on. Do not screenshot right away. If the task needs confirmation, wait for the UI to settle (scr idle / dsh-mobile cdp wait) and read once; if nothing changes after two tries, ask the user.")
                        }
                    } else {
                        obj.put("verify", "inline_expect")
                    }
                }
                "handoff" -> {
                    obj.put("handoff", true)
                        .put("verify", "none")
                        // v2.5.38：机器可读的终止标记——agent 不必读 prose 也知道该停
                        .put("stop", true)
                        .put("next", "none")
                        .put("do_not", org.json.JSONArray().put("screenshot").put("poll").put("retry"))
                    // /open 自己会在「没换前台」时给出更具体的原因（无障碍关着/选择器/BAL），别覆盖
                    if (path == "/open") {
                        if (!obj.has("hint")) {
                            obj.put(
                                "hint",
                                "Opened (hand-off). To wait for the user coming back from another app " +
                                    "(payment/OAuth), call POST /wait-app with the target package. " +
                                    "Payment deep links only launch the app - landing on a pay page is not " +
                                    "guaranteed, so never try to verify or drive the payment UI. " +
                                    "Do not screenshot in a loop and do not open it twice."
                            )
                        }
                    } else {
                        obj.put("hint", "Opened (hand-off). The user may interact and come back before you check - that is expected. Do not screenshot/poll/re-run to confirm; continue or wait for the user.")
                    }
                }
                else -> {
                    obj.put("verify", "none")
                        .put("hint", "Setting applied in the app process; no UI verification needed.")
                }
            }
            obj.toString()
        }.getOrDefault(json)
    }

    /** 即时动作端点（派发即完成，界面状态随时可被用户改变）。 */
    internal val INSTANT_ACTION_PATHS = setOf(
        "/tap", "/tap-id", "/gesture", "/key", "/input",
        "/scroll-find", "/scroll-center", "/scroll-edge",
        "/vision/tap", "/vision/drag", "/pick", "/overlay", "/loop/run",
    )

    /** 打开页面端点（hand-off；/activity 自己带更详细的 hand-off 字段，不在此集合）。 */
    internal val OPEN_ACTION_PATHS = setOf("/launch", "/launch-intent", "/open")

    /** 路由分发委托：实现见 bridge/Router.kt。 */
    internal fun route(ctx: Context, method: String, path: String, query: String, body: String): Pair<Int, String> =
        router.route(ctx, method, path, query, body)

    // 兼容既有调用点（SystemHandler / CoordAutoCalibrator 等）的薄委托：
    // 实现已迁到 handler，这里只保留原方法名，避免跨包调用点跟着改。
    internal fun calib(ctx: Context, query: String): Pair<Int, String> = screen.calib(ctx, query)

    internal fun webViewDebugEnabled(ctx: Context): Boolean = appHandler.webViewDebugEnabled(ctx)

    internal fun notify(ctx: Context, body: String): Pair<Int, String> = appHandler.notify(ctx, body)

    internal fun acceptedOrRejected(action: String, err: String?): Pair<Int, String> =
        BridgeSupport.acceptedOrRejected(action, err)

    /**
     * POST /say body {"text":"...", "flush":false} → 系统语音合成朗读。
     * Agent 语音输出的出口（issues #2 语音功能方向的第一块）。
     */
    /** "text=...&flush=1" → Map（query 参数，GET /say 用；UTF-8 URL 解码） */
    internal fun urlDecode(s: String): String = BridgeSupport.urlDecode(s)

    init {
        // 无障碍分类自检：见 bridge/A11yGate.kt
        a11ySelfCheck()
    }

    /** query 串（"a=1&b=2"）解析；值做 UTF-8 URL 解码 */
    internal fun parseQuery(query: String): Map<String, String> = BridgeSupport.parseQuery(query)
}
