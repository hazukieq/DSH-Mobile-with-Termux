package app.dsh.mobile.engine

import app.dsh.mobile.ProgressHub

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
 * Agent 能力桥（m1.37/v1.1.0）：监听 127.0.0.1:3083，把「通知 / 读屏 / 点击」暴露给引擎内 Agent。
 *
 * 与 ShizukuHttpBridge 同构（原生 ServerSocket 环回 HTTP），但【不依赖任何特权模式】——
 * 通知是无障碍是 App 自身能力，普通模式即可用。
 *
 * 路由：
 *   POST /notify        body: {"title":"...", "body":"..."}          → Android 系统通知
 *   GET  /screen        → 当前屏幕可见文本+坐标 JSON（需无障碍服务已开启）
 *   POST /tap           body: {"x":123,"y":456} 或 {"text":「确定」}   → 模拟点击（需无障碍服务）
 *   POST /input         body: {"text":"...", "target":「<输入框提示>」} → 向输入框写文字（v1.2.54）
 *   POST /scroll-find   body: {"text":"...", "tap":true}             → 滚动查找并可选点击（v1.2.54）
 *   POST /idle          body: {"timeoutMs":3000}                     → 等待界面稳定（v1.2.54）
 *
 * 运行时热更新（2026-10-18，配套 engine/bin/dsh-update 门脚本）：
 *   GET  /runtime/status   [?format=line] → 运行时版本/来源/暂存/回滚/引擎状态
 *   GET  /runtime/feed     [?format=line][&manifest=url] → dsh 版本检查（本机 / npm 最新 / feed）
 *   POST /runtime/pull     body: {"version":"0.2.1","manifest":"https://…"} → 202（按 dsh 版本拉取）
 *
 *   引擎侧终端（2026-10-18，node-pty 桥）：
 *   POST /pty/open         {file,args,cwd,env,rows,cols,name} → {id,pid}
 *   POST /pty/write        {id,data(base64)}
 *   POST /pty/resize       {id,rows,cols}
 *   POST /pty/kill         {id,sig?}
 *   POST /pty/close        {id}
 *   GET  /pty/read?id&seq&timeout  → 增量字节（base64，长轮询）
 *   POST /runtime/stage    body: {"path":"/abs/x.zip"} 或 {"url":"https://…","sha256":"…"} → 202
 *   POST /runtime/apply    → 202（停引擎 → 换目录 → 启动 → 健康检查，失败自动回滚）
 *   POST /runtime/rollback → 202（回到 engine.old）
 *   POST /engine/restart   → 202（等价于设置页「重启引擎」）
 *
 * 配套注入 engine/bin 的 `notify` 与 `scr` 包装器（EngineConfig.applyAgentGates）。
 */
object AgentBridge {

    private const val TAG = "AgentBridge"
    const val PORT = 3083
    private const val CHANNEL_ID = "agent_notify"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null

    /** 上一次动作发生时的前台包名（用于回报 foregroundChanged，见 tap 的注释） */
    @Volatile private var lastActionPkg: String? = null

    /** 包名匹配：完全相等，或一方是另一方的前缀（容忍 caller 只写包名前缀） */
    private fun pkgMatches(current: String, expect: String): Boolean =
        current == expect || current.startsWith(expect) || expect.startsWith(current)

    /** 应用上下文（start 时记住，供截图落盘等需要路径的操作使用） */
    @Volatile private var shotCtx: Context? = null

    /**
     * 每个动作入口都调一次：让悬浮窗立刻显示「执行中 · <动作类型>」+ 具体指令。
     *
     * 🔴 v1.2.72 修主人实测「展开悬浮窗也看不到 AI 干活的指令」：
     * 此前**只有 `/tap`** 会通知悬浮窗，滑动 / 按键 / 输入文字 / 滚动查找 / 批量**全都没有**。
     * AI 的实际操作里 tap 只占一部分，所以用户展开也经常一片空白。
     */
    private fun announceAction(actionRes: Int, detail: String) {
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

    /** 单步截图序号 */
    private val shotSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** 保留最近几张单步截图（更旧的当场删除 = 主人要的「用不到自动销毁」） */
    private const val KEEP_STEP_SHOTS = 3

    /**
     * 每步自动截图（v1.2.70，主人要求）。
     *
     * 「每做完一个步骤就截一张图，免去 AI 自己截图的过程；假如 AI 用不到图则自动销毁」。
     * 做法：动作派发后截屏落盘到 `files/screens/step-<n>.png`，**路径写进动作响应**；
     * 只保留最近 [KEEP_STEP_SHOTS] 张，更旧的当场删除 —— 这就是「用不到自动销毁」：
     * AI 不读它也不会堆积，要读时它还在。
     *
     * ⚠️ 截图失败绝不影响动作本身（返回 null，响应里 shot 为空串）。
     */
    private fun captureStepShot(): String? = runCatching {
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
    @Volatile private var token: String? = null

    /** 当前 token（供 EngineConfig 注入环境变量） */
    fun currentToken(): String? = token

    private fun ensureToken(ctx: Context) {
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
     * 所有门脚本（pkg / notify / scr / say / linux / dsh-update）请求桥都被 403。
     * 文件放在 App 私有目录、权限 0600：只有同一 uid 的 App 子进程可读，
     * 第三方 App 依然拿不到。
     */
    private fun writeTokenFile(ctx: Context) {
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
        }.onFailure { Log.w(TAG, "write bridge token file failed: ${it.message}") }
    }

    /**
     * 校验请求：`X-DSH-Token` 头或 `?token=` 查询参数须等于当前 token。
     *
     * fail-closed：token 未生成（异常状态）时**拒绝**而非放行 —— 桥暴露的是
     * 高权限设备能力，宁可让 Agent 报错也不要静默开放。
     */
    private fun authorised(headers: List<String>): Boolean =
        Privilege.authorisedBridgeRequest(headers)

    fun start(ctx: Context) {
        if (server != null) return
        shotCtx = ctx.applicationContext
        // v1.2.160：LAN 代理需要 app context 推导引擎目录（部署脚本 / 拉起 node 进程）
        runCatching { LanWebProxy.setContext(ctx) }
        // 桥 token（v1.2.99）：每次启动随机生成，只通过环境变量交给引擎内的 Agent。
        // 同设备第三方 App 拿不到 → 无法调用读屏/点击/通知等能力。
        ensureToken(ctx)
        try {
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
                notify(
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
    private fun portHolder(): String? = runCatching {
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
    private fun onTurnEnd(ctx: Context, session: String) {
        runCatching {
            Log.i(TAG, "turn/end detected in $session")
            StatusOverlay.flashComplete(ctx.getString(R.string.overlay_turn_done))
            notify(
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
    private fun ttsOnComplete(ctx: Context): Boolean =
        runCatching {
            ctx.getSharedPreferences("dsh_ui", Context.MODE_PRIVATE)
                .getBoolean("tts_on_complete", false)
        }.getOrDefault(false)

    fun stop() {
        runCatching { server?.close() }
        server = null; thread = null
        Log.i(TAG, "agent bridge stopped")
    }

    private fun handle(ctx: Context, client: Socket) {
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
                    respond(client, 403, """{"ok":false,"error":"unauthorised"}""")
                    return@Thread
                }

                val (status, json) = route(ctx, method, path, query, body)
                respond(client, status, decorateActionMeta(method, path, status, json))
            } catch (e: Exception) {
                Log.w(TAG, "handle: ${e.message}")
                runCatching { respond(client, 500, """{"ok":false,"error":"${e.message}"}""") }
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
    private fun decorateActionMeta(method: String, path: String, status: Int, json: String): String {
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
                        .put("do_not", org.json.JSONArray().put("screenshot").put("poll").put("retry"))
                        .put("hint", "Opened (hand-off). The user may interact and come back before you check - that is expected. Do not screenshot/poll/re-run to confirm; continue or wait for the user.")
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
    private val INSTANT_ACTION_PATHS = setOf(
        "/tap", "/tap-id", "/gesture", "/key", "/input",
        "/scroll-find", "/scroll-center", "/scroll-edge",
        "/vision/tap", "/vision/drag", "/pick", "/overlay", "/loop/run",
    )

    /** 打开页面端点（hand-off；/activity 自己带更详细的 hand-off 字段，不在此集合）。 */
    private val OPEN_ACTION_PATHS = setOf("/launch", "/launch-intent")

    private fun route(ctx: Context, method: String, path: String, query: String, body: String): Pair<Int, String> {
        // v1.2.182（用户需求）：AI 进行「一大串」无障碍操作时发一条系统通知，
        // 让用户随时知道它真的在干活（而不是界面自己变来变去像 bug）。
        // 只统计**动作类**端点（点击/输入/滑动/按键/启动…），读屏类不计入 ——
        // 否则每看一眼屏幕都算一步，通知会烦人。
        if (method == "POST" && path in A11Y_ACTION_PATHS) noteA11yOperation(ctx, path)
        // v1.7.3：无障碍「已启用但未绑定」时先等 1.5s，再给出可区分的错误，
        // 避免 Agent 预检/动作拿到笼统的 503 就判定「无障碍没开」。
        // v1.9.3：`/ocr?engine=ppocr6&ensure=1` 只是装依赖，不需要无障碍截图，
        // 不应当因为服务未连接就被 503 挡住。
        val ensureOnly = path == "/ocr" && parseQuery(query)["ensure"] in listOf("1", "true", "yes")
        if (!ensureOnly && path in A11Y_REQUIRED_PATHS && !DshAccessibilityService.isConnected()) {
            val enabled = DshAccessibilityService.isEnabled(ctx)
            if (enabled) DshAccessibilityService.awaitConnection(1_500L)
            if (!DshAccessibilityService.isConnected()) {
                val listed = !enabled && DshAccessibilityService.isListedButDisabled(ctx)
                val msg = when {
                    enabled -> "accessibility service is enabled in system settings but not connected yet; retry in a moment, or toggle the service off/on in Settings > Accessibility if it persists"
                    listed -> "accessibility was granted before but Android disabled the service (force-stop or app update); re-enable it in Settings > Accessibility -> DSH Screen Helper - the app cannot enable it itself. Bridge can open the page: POST /activity {\"name\":\"accessibility-settings\"}"
                    else -> "accessibility service not enabled (enable 'DSH Screen Helper' in Settings > Accessibility)"
                }
                return 503 to JSONObject()
                    .put("ok", false)
                    // v1.8.0（提案 P1-5）：err 用稳定 code，便于上层一次判断；人读文案放 reason。
                    .put("error", if (enabled) "a11y_connecting" else "a11y_off")
                    .put("code", if (enabled) "a11y_connecting" else "a11y_off")
                    .put("reason", msg)
                    .put("message", msg)
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
            method == "POST" && path == "/notify" -> notify(ctx, body)
            method == "GET" && path == "/screen" -> screen(query)
            method == "GET" && path == "/ocr" -> ocr(ctx, query)
            method == "GET" && path == "/screen-changed" -> screenChanged()
            method == "GET" && path == "/scene" -> scene(ctx, query)
            method == "POST" && path == "/act" -> act(ctx, body)
            method == "POST" && path == "/export" -> mediaExport(ctx, body)
            method == "POST" && path == "/pick" -> pick(body)
            method == "GET" && path == "/local/status" -> localStatus(ctx)
            method == "GET" && path == "/frame-diff" -> frameDiff()
            method == "POST" && path == "/frame-wait" -> frameWait(body)
            method == "POST" && path == "/loop/run" -> loopRun(ctx, body)
            method == "POST" && path == "/vision/crop" -> visionCrop(ctx, body)
            method == "POST" && path == "/vision/track" -> visionTrack(ctx, body)
            method == "POST" && path == "/vision/tap" -> visionTap(ctx, body)
            method == "POST" && path == "/vision/drag" -> visionDrag(ctx, body)
            method == "GET" && path == "/vision/list" -> visionList(ctx)
            method == "GET" && path == "/memory/list" -> 200 to SceneMemory.listJson(ctx)
            method == "GET" && path == "/memory/stats" -> 200 to JSONObject().put("ok", true).put("summary", SceneMemory.stats(ctx)).toString()
            method == "POST" && path == "/memory/lookup" -> memoryLookup(ctx, body)
            method == "POST" && path == "/memory/store" -> memoryStore(ctx, body)
            method == "POST" && path == "/memory/confirm" -> memoryConfirm(ctx, body)
            method == "POST" && path == "/memory/clear" -> {
                SceneMemory.clear(ctx)
                200 to """{"ok":true,"cleared":true}"""
            }
            method == "POST" && path == "/local/ensure" -> localEnsure(ctx, query)
            method == "GET" && path == "/daemon/status" -> daemonStatus()
            method == "POST" && path == "/daemon/stop" -> daemonStop()
            method == "POST" && path == "/tap" -> tap(body)
            method == "GET" && path == "/screenshot" -> screenshot(query)
            method == "GET" && path == "/app-screenshot" -> appScreenshot(query)
            method == "GET" && path == "/process" -> processInfo()
            method == "GET" && path == "/status" -> status()
            method == "GET" && path == "/settings" -> settingsGet()
            method == "POST" && path == "/settings" -> settingsSet(body)
            method == "POST" && path == "/activity" -> openActivity(body)
            method == "POST" && path == "/media/play" -> mediaPlay(ctx, body)
            method == "POST" && path == "/media/pause" -> mediaPause(ctx)
            method == "POST" && path == "/media/resume" -> mediaResume(ctx)
            method == "POST" && path == "/media/stop" -> mediaStop(ctx)
            method == "GET" && path == "/media/status" -> 200 to AudioPlayer.status().toString()
            method == "POST" && path == "/open/file" -> openPath(ctx, body, "file")
            method == "POST" && path == "/open/folder" -> openPath(ctx, body, "folder")
            method == "POST" && path == "/open/share" -> openPath(ctx, body, "share")
            method == "POST" && path == "/share/text" -> shareText(ctx, body)
            method == "GET" && path == "/apps" -> appsFor(query)
            method == "POST" && path == "/gesture" -> gesture(body)
            method == "POST" && path == "/key" -> key(body)
            method == "POST" && path == "/wait" -> wait(body)
            method == "POST" && path == "/input" -> input(body)
            method == "POST" && path == "/scroll-find" -> scrollFind(body)
            method == "POST" && path == "/scroll-center" -> scrollCenter(body)
            method == "GET" && path == "/diff" -> diffScreen()
            method == "POST" && path == "/idle" -> idle(body)
            method == "POST" && path == "/batch" -> batch(body)
            method == "GET" && path == "/interference" -> interference(query)
            method == "POST" && path == "/overlay" -> overlay(ctx, body)
            method == "POST" && path == "/launch" -> launch(ctx, body)
            method == "POST" && path == "/launch-intent" -> launchIntent(ctx, body)
            method == "POST" && path == "/tap-id" -> tapId(body)
            method == "POST" && path == "/wait-text" -> waitText(body)
            method == "POST" && path == "/scroll-edge" -> scrollEdge(body)
            method == "GET" && path == "/notif" -> notif()
            method == "GET" && path == "/plugin/list" -> pluginList()
            method == "POST" && path == "/plugin/install" -> pluginInstall(body)
            method == "GET" && path == "/diag" -> diag(ctx)
            method == "POST" && path == "/say" -> say(ctx, body)
            method == "GET" && path == "/say" -> sayGet(ctx, query)
            // 运行时热更新（2026-10-18）：门脚本 dsh-update 驱动的五个端点
            method == "GET" && path == "/runtime/status" -> runtimeStatus(ctx, query)
            method == "GET" && path == "/runtime/feed" -> runtimeFeed(ctx, query)
            method == "POST" && path == "/runtime/pull" -> runtimePull(ctx, body)
            // 引擎侧终端：dsh WebUI 侧边栏的 node-pty shim 通过这组端点用 App 的真 PTY
            method == "POST" && path == "/pty/open" -> ptyOpen(ctx, body)
            method == "POST" && path == "/pty/write" -> ptyWrite(body)
            method == "POST" && path == "/pty/resize" -> ptyResize(body)
            method == "POST" && path == "/pty/kill" -> ptyKill(body)
            method == "POST" && path == "/pty/close" -> ptyClose(body)
            method == "GET" && path == "/pty/read" -> ptyRead(query)
            method == "POST" && path == "/runtime/stage" -> runtimeStage(ctx, body)
            method == "POST" && path == "/runtime/apply" -> runtimeApply(ctx)
            method == "POST" && path == "/runtime/rollback" -> runtimeRollback(ctx)
            method == "POST" && path == "/engine/restart" -> engineRestart(ctx, body)
            method == "POST" && path == "/a11y/enable" -> a11yEnable(ctx)
            else -> 404 to """{"ok":false,"error":"unknown route"}"""
        }
    }

    // ── 运行时热更新（2026-10-18）────────────────────────────────────────────
    //
    // 这组端点的共同约定：
    //  - 动作类（stage/apply/rollback/restart）一律 **202 + 立即返回**：apply 要停引擎、
    //    换 500MB 目录、再等健康检查，几十秒起步；同步做会撞翻 `_dsh_http.sh` 的读超时。
    //    真正结果在 /runtime/status 的 phase（回到 idle）+ last_action/last_ok/error 里。
    //  - 拒绝（忙 / 参数错 / 无暂存）返回 **409** + {"ok":false,"error":"..."}，
    //    错误文本是 ASCII、可读、可 grep（门脚本直接打给 agent）。
    //  - 鉴权沿用 handle() 里统一的 X-DSH-Token 校验，这组端点不另开门。

    /** GET /runtime/status [?format=line] → 状态 JSON；format=line 时返回 key: value 行（给 bash 门脚本） */
    private fun runtimeStatus(ctx: Context, query: String): Pair<Int, String> {
        return try {
            val st = RuntimeUpdater.status(ctx)
            if (query.split('&').any { it == "format=line" }) 200 to st.toLine()
            else 200 to st.toJson()
        } catch (e: Exception) {
            500 to JSONObject().put("ok", false).put("error", e.message ?: "status failed").toString()
        }
    }

    /** POST /runtime/stage body {"path":"/abs/x.zip"} 或 {"url":"https://…","sha256":"…"} */
    private fun runtimeStage(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val path = obj.optString("path").takeIf { it.isNotBlank() }
        val url = obj.optString("url").takeIf { it.isNotBlank() }
        val src = path ?: url
        if (src == null) {
            return 400 to JSONObject().put("ok", false)
                .put("error", "missing 'path' or 'url'").toString()
        }
        val sha = obj.optString("sha256").takeIf { it.isNotBlank() }
        val err = RuntimeUpdater.stage(ctx, src, sha, isUrl = path == null)
        return acceptedOrRejected("stage", err)
    }

    // ── 引擎侧终端（node-pty 桥，2026-10-18）────────────────────────────────
    //
    // 为什么在 App 侧开 PTY：Android 上 node-pty 没有可用预编译（它依赖 glibc/x64 的
    // .node），运行时里的 node-pty 是构建期空壳，WebUI 侧边栏一开终端就 throw。
    // App 已经有 libdshpty.so（fork/exec + /dev/ptmx + PR_SET_PDEATHSIG），于是把
    // 真实 PTY 留在 App 进程里，用这组端点暴露给引擎的 node-pty shim —— 侧边栏
    // 因此拿到的是**真 tty**（作业控制、Ctrl-C、resize 都成立），不是管道假终端。
    //
    // 鉴权沿用 handle() 里的统一 X-DSH-Token；数据用 base64（二进制安全）。

    /** POST /pty/open {file,args,cwd,env,rows,cols,name} → {ok,id,pid} */
    private fun ptyOpen(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val file = obj.optString("file").trim()
        if (file.isEmpty()) {
            return 400 to JSONObject().put("ok", false).put("error", "missing 'file'").toString()
        }
        val args = ArrayList<String>()
        obj.optJSONArray("args")?.let { a -> for (i in 0 until a.length()) args.add(a.optString(i)) }
        val env = LinkedHashMap<String, String>()
        obj.optJSONObject("env")?.let { e -> for (k in e.keys()) env[k] = e.optString(k) }
        val cwd = obj.optString("cwd").trim().takeIf { it.isNotEmpty() }
            ?: EngineConfig.dshHome(ctx).absolutePath
        val rows = obj.optInt("rows", 24).let { if (it in 1..512) it else 24 }
        val cols = obj.optInt("cols", 80).let { if (it in 1..512) it else 80 }
        val term = obj.optString("name").trim().ifEmpty { "xterm-256color" }
        return try {
            val s = PtyBridge.open(ctx, file, args, cwd, env, rows, cols, term)
            200 to JSONObject().put("ok", true).put("id", s.id).put("pid", s.pid).toString()
        } catch (e: Exception) {
            409 to JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    /** POST /pty/write {id,data(base64)} */
    private fun ptyWrite(body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val id = obj.optInt("id", -1)
        val b64 = obj.optString("data")
        return try {
            val data = if (b64.isEmpty()) ByteArray(0) else android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            val n = PtyBridge.write(id, data)
            200 to JSONObject().put("ok", true).put("bytes", n).toString()
        } catch (e: Exception) {
            409 to JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    /** POST /pty/resize {id,rows,cols} */
    private fun ptyResize(body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        return try {
            PtyBridge.resize(obj.optInt("id", -1), obj.optInt("rows", 24), obj.optInt("cols", 80))
            200 to JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            409 to JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    /** POST /pty/kill {id,sig}（默认 SIGTERM=15；只管信号，不释放会话） */
    private fun ptyKill(body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        return try {
            PtyBridge.signal(obj.optInt("id", -1), obj.optInt("sig", 15))
            200 to JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            409 to JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    /** POST /pty/close {id}（杀进程组 + 回收槽位，幂等） */
    private fun ptyClose(body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        return try {
            PtyBridge.close(obj.optInt("id", -1))
            200 to JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            409 to JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    /** GET /pty/read?id&seq&timeout → {ok,seq,data,exited,exit_code,signal,lost} */
    private fun ptyRead(query: String): Pair<Int, String> {
        val p = parseQuery(query)
        val id = p["id"]?.toIntOrNull() ?: -1
        val seq = p["seq"]?.toLongOrNull() ?: 0L
        val timeout = p["timeout"]?.toIntOrNull() ?: 20_000
        return try {
            val c = PtyBridge.read(id, seq, timeout)
            val b64 = if (c.data.isEmpty()) "" else android.util.Base64.encodeToString(c.data, android.util.Base64.NO_WRAP)
            200 to JSONObject().put("ok", true)
                .put("seq", c.nextSeq)
                .put("data", b64)
                .put("exited", c.exited)
                .put("exit_code", c.exitCode)
                .put("signal", c.signal)
                .put("lost", c.lost)
                .toString()
        } catch (e: Exception) {
            409 to JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    /**
     * GET /runtime/feed [?format=line][&manifest=<url>] → dsh 版本检查。
     *
     * 回答三个问题：装的是哪个 dsh 版本、上游 npm 最新是哪个、feed 能为当前 ABI 提供哪个。
     * 只读：不发任何文件改动，也不触发下载（下载只在 /runtime/pull 里做）。
     */
    private fun runtimeFeed(ctx: Context, query: String): Pair<Int, String> {
        return try {
            val params = parseQuery(query)
            val check = RuntimeUpdater.feedCheck(ctx, params["manifest"])
            if (params["format"] == "line") 200 to check.toLine()
            else 200 to check.toJson()
        } catch (e: Exception) {
            500 to JSONObject().put("ok", false).put("error", e.message ?: "feed check failed").toString()
        }
    }

    /**
     * POST /runtime/pull body {"version":"0.2.1","manifest":"https://…"} → 202。
     *
     * 注意语义：比较与拉取都以 **dsh 版本**（@deepseek-ai/dsh）为准，与 APK 版本无关。
     * 下载 + 解压耗时几十秒到几分钟，因此和 stage 一样是「立即受理 + 轮询 status」。
     */
    private fun runtimePull(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val err = RuntimeUpdater.pull(
            ctx,
            obj.optString("version").takeIf { it.isNotBlank() },
            obj.optString("manifest").takeIf { it.isNotBlank() },
        )
        return acceptedOrRejected("pull", err)
    }

    /** POST /runtime/apply → 202（Async: stop → swap → start → health check → auto rollback） */
    private fun runtimeApply(ctx: Context): Pair<Int, String> {
        val err = RuntimeUpdater.apply(ctx)
        return acceptedOrRejected("apply", err)
    }

    /** POST /runtime/rollback → 202 */
    private fun runtimeRollback(ctx: Context): Pair<Int, String> {
        val err = RuntimeUpdater.rollback(ctx)
        return acceptedOrRejected("rollback", err)
    }

    /** POST /a11y/enable（提案 P1-5）：一次调用打开系统无障碍设置页。 */
    private fun a11yEnable(ctx: Context): Pair<Int, String> {
        return try {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            200 to JSONObject()
                .put("ok", true)
                .put("opened", "accessibility-settings")
                .put("hint", "enable the DSH Screen Helper / DSH Screen Control service, then retry")
                .toString()
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"cannot open accessibility settings: ${e.message}"}"""
        }
    }

    /**
     * POST /engine/restart → 202（等价设置页「重启引擎」）。
     *
     * v1.7.0：延迟 ~500ms 再真正重启。调用方常是引擎子进程里的门脚本（dsh-update），
     * HTTP 202 必须先写回、脚本先打印，再停引擎；立刻 stop 会把请求方一起带走，
     * 表现为「命令没反应」。设置页按钮同样走这里，500ms 无感。
     */
    private fun engineRestart(ctx: Context, body: String = ""): Pair<Int, String> {
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

    /** 202 = 已受理；409 = 被拒绝（忙 / 无暂存 / 无旧版 …），错误文本原样给调用方 */
    private fun acceptedOrRejected(action: String, err: String?): Pair<Int, String> =
        if (err == null) {
            202 to JSONObject().put("ok", true).put("action", action).put("accepted", true).toString()
        } else {
            409 to JSONObject().put("ok", false).put("action", action).put("error", err).toString()
        }

    /**
     * POST /say body {"text":"...", "flush":false} → 系统语音合成朗读。
     * Agent 语音输出的出口（issues #2 语音功能方向的第一块）。
     */
    /** "text=...&flush=1" → Map（query 参数，GET /say 用；UTF-8 URL 解码） */
    private fun urlDecode(s: String): String = runCatching {
        java.net.URLDecoder.decode(s, "UTF-8")
    }.getOrDefault(s)

    /** GET /say?text=...&flush=1 → 构造 JSON body 后走同一 say 流程 */
    private fun sayGet(ctx: Context, rawPath: String): Pair<Int, String> {
        val q = rawPath   // 传入的已是去掉 '?' 的纯 query 串
        val text = urlDecode(q.substringAfter("text=", "").substringBefore("&flush"))
        val flush = q.contains("flush=1")
        if (text.isBlank()) return 400 to """{"ok":false,"error":"text is blank"}"""
        val obj = org.json.JSONObject().put("text", text).put("flush", flush)
        return say(ctx, obj.toString())
    }

    private fun say(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"body must be {\"text\":\"...\",\"flush\":false}"}"""
        val text = obj.optString("text")
        if (text.isBlank()) return 400 to """{"ok":false,"error":"text is blank"}"""
        // TTS init/speak 可能阻塞数秒（系统 TTS 服务冷启动），异步执行防 HTTP 超时
        Thread({ TtsManager.speak(ctx, text, obj.optBoolean("flush", false)) }, "dsh-tts").apply { isDaemon = true; start() }
        return 200 to """{"ok":true,"result":"accepted"}"""
    }

    /**
     * GET /diag → HTML 自诊断页（Android 11 等老设备排障）：
     * WebView UA 与 JS API 缺失检测、引擎状态、3080 HTTP/WS 探测、
     * runtime 版本、polyfill 落地、engine.log 尾部。用户在设备浏览器打开截图即可。
     */
    private fun diag(ctx: Context): Pair<Int, String> {
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

    // ---------- v1.2.182：长串无障碍操作的系统通知 ----------

    /** 计入「AI 在操作设备」的动作类端点（读屏/查询类不计）。 */
    private val A11Y_ACTION_PATHS = setOf(
        "/tap", "/tap-id", "/gesture", "/key", "/input",
        "/scroll-find", "/scroll-center", "/scroll-edge",
        "/launch", "/launch-intent", "/vision/tap", "/vision/drag",
        "/loop/run",
    )

    /**
     * v1.7.3：真正依赖无障碍服务的端点（读屏 + 动作 + 视觉 + 截图）。
     * 进入路由前统一做「已启用但尚未连接」的短暂等待，而不是让每个端点各自 503。
     */
    private val A11Y_REQUIRED_PATHS = A11Y_ACTION_PATHS + setOf(
        "/screen", "/screen-changed", "/pick", "/diff", "/interference",
        "/wait-text", "/screenshot", "/app-screenshot", "/frame-diff", "/frame-wait",
        "/vision/track", "/vision/crop", "/vision/drag",
    )

    /** 连续操作够几步才提示（太少会打扰，太多用户又看不到过程）。 */
    private const val A11Y_NOTIF_THRESHOLD = 3
    /** 序列粘性窗口：超过这么久没动作就视为新序列。 */
    private const val A11Y_NOTIF_WINDOW_MS = 30_000L
    /** 最后一次动作后多久自动撤掉通知。 */
    private const val A11Y_NOTIF_IDLE_MS = 10_000L
    /** 固定的通知 id：每次更新同一条，不刷屏。 */
    private const val A11Y_NOTIF_ID = 0x0A11

    @Volatile private var a11ySeqCount = 0
    @Volatile private var a11ySeqLastAt = 0L
    private val a11yNotifHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var a11yNotifIdle: Runnable? = null

    /**
     * 动作标签（通知正文里给人类看的那半句）。
     *
     * 走字符串资源而不是内联中文：项目自带 i18n 门禁（scripts/check-i18n.py）
     * 禁止 Kotlin 字面量里出现 CJK，中英两套 locale 必须同步。
     */
    private fun a11yOpLabel(ctx: Context, path: String): String = when (path) {
        "/tap", "/tap-id", "/vision/tap" -> ctx.getString(R.string.notif_a11y_op_tap)
        "/gesture", "/vision/drag" -> ctx.getString(R.string.notif_a11y_op_swipe)
        "/scroll-find", "/scroll-center", "/scroll-edge" -> ctx.getString(R.string.notif_a11y_op_scroll)
        "/input" -> ctx.getString(R.string.notif_a11y_op_input)
        "/key" -> ctx.getString(R.string.notif_a11y_op_key)
        "/launch", "/launch-intent" -> ctx.getString(R.string.notif_a11y_op_launch)
        "/loop/run" -> ctx.getString(R.string.notif_a11y_op_loop)
        else -> ctx.getString(R.string.notif_a11y_op_other)
    }

    /**
     * 记录一次无障碍动作；达到阈值后显示/更新一条系统通知。
     *
     * 设计取舍：
     *  · **不每条都弹**（会烦）—— 连续第 3 步起才提示，之后原地更新步数；
     *  · **同一条通知**（固定 id + setOnlyAlertOnce）→ 只在第一次提示时响一下；
     *  · 空闲 10s 自动撤掉；任务结束时用户也能手动划走。
     *  · 没有通知权限时静默跳过（不影响操作本身）。
     */
    private fun noteA11yOperation(ctx: Context, path: String) {
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

    /** 构建并更新那条「正在操作」通知。 */
    private fun showA11yNotification(ctx: Context, step: Int, label: String) {
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

    /** POST /notify → 系统通知（任务完成推送） */
    private fun notify(ctx: Context, body: String): Pair<Int, String> {
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

    /** 上一次 /screen 的节点签名集（diff=1 模式用；进程级缓存） */
    @Volatile
    private var lastScreenSig: Set<String>? = null

    /** 紧凑格式的上一次行集合（diff=1 用；与 lastScreenSig 分开存，两种格式互不干扰） */
    @Volatile
    private var lastCompactSig: Set<String>? = null

    // ================= 无障碍 IPC 有界等待（v1.2.50） =================
    //
    // 问题：dumpScreenJson / screenXml 是对无障碍服务的同步 IPC，节点树僵死时
    // 会无限期阻塞。而 handle() 的 client.soTimeout 只覆盖「读请求头」阶段，管不到
    // 这里；客户端门脚本也没有超时 → 一次卡死会把 /screen 与 /wait 一起挂住
    // （用户实测 scr dump 挂 >60s、notify 挂 >120s）。
    // 解法：把节点树读取丢进单线程 executor，有界等待；超时返回 504 并明确报错，
    // 让上层能区分「服务未开启(503)」与「IPC 卡住(504)」。单线程同时限制了并发，
    // 避免多个僵死任务堆积。
    // v1.2.152 审查修复：原先只有 1 条线程，而 `/loop/run` 会在这个线程上占满
    // 整段执行时间（最长 180s）——期间任何并发读屏（`/screen`、`/pick`、`/memory`）
    // 都排在后面，2s 上限一到全部 504，AI 收到"无障碍坏了"的错误信号。
    // 2 条线程：长任务占一条，快速读屏仍有可用线程（读操作互不冲突）。
    private val screenExec = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "dsh-screen-ipc").apply { isDaemon = true }
    }

    /** 节点树 IPC 的默认上限：稳定后读屏几十毫秒，冷读（见下）约 2.5s */
    private val SCREEN_IPC_TIMEOUT_MS = 2_000L

    /** 冷读重试预算：实测屏幕刚变化后的第一次读树要 2.1-2.5s，稳定后 60-80ms */
    private val SCREEN_IPC_COLD_RETRY_MS = 5_000L

    /**
     * 有界执行无障碍节点树读取。
     * @return 成功返回值；超时或异常返回 null 并记录原因（调用方据此回 504）
     */
    private fun <T> withScreenTimeout(
        what: String, timeoutMs: Long = SCREEN_IPC_TIMEOUT_MS, block: () -> T,
    ): Result<T>?  {
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

    /** 单次有界读树：超时返回 null，异常返回 failure */
    private fun <T> screenReadOnce(what: String, timeoutMs: Long, block: () -> T): Result<T>? = try {
        val future = screenExec.submit(java.util.concurrent.Callable { block() })
        Result.success(future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS))
    } catch (e: java.util.concurrent.TimeoutException) {
        Log.w(TAG, "screen ipc timeout: $what (>${timeoutMs}ms) — node tree stalled")
        null
    } catch (e: java.util.concurrent.ExecutionException) {
        Result.failure(e.cause ?: e)
    }

    /**
     * GET /screen → 无障碍读屏（服务未开启时 503）。
     * query 参数：
     *   compact=1 → **紧凑文本格式（推荐，压缩 6.8 倍）**；缺省也走紧凑
     *   format=json → 强制完整 JSON（需要 cls/rid/精确尺寸时用）
     *   xml=1 → 树形转储（含 viewId/scrollable/editable/层级）
     *   filter=clickable → 只输出可点击节点；diff=1 → 只输出与上次不同的节点（省 token）
     *
     * ⚠️ v1.2.55：默认改为**紧凑格式**。完整 JSON 每节点 11 字段，200 节点约 44KB
     * ≈ 1.5 万 tokens，每一轮对话都要重新过一遍注意力 —— 用户实测「五六秒才动一次」，
     * 主因就是这个（不是动作慢，是喂给模型的上下文太肥）。紧凑格式 44KB → 6.5KB。
     */
    private fun screen(query: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled (enable 'DSH Screen Control' in system settings)"}"""
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

    /** query 串（"a=1&b=2"）解析；值做 UTF-8 URL 解码 */
    private fun parseQuery(query: String): Map<String, String> =
        query.split("&").filter { it.contains("=") }.associate {
            val i = it.indexOf('=')
            java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to
                java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }

    /** POST /tap → 坐标点击 / 按文本点击（text+desc 打分匹配，trim 忽略大小写）/ 按 desc 点击 */
    private fun tap(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            val obj = JSONObject(body)
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
                obj.has("x") && obj.has("y") -> svc.dispatchTap(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
                else -> false
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
                200 to """{"ok":true,"dispatched":true,"foreground":"${fg ?: ""}","foregroundChanged":$fgChanged,"shot":"${shot ?: ""}","hint":"ok means the gesture was dispatched, not that the UI reacted — dump again to verify"}"""
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

    /** GET /screenshot → 截屏 PNG base64（takeScreenshot，API 30+）。
     *  能力缺失时给出准确指引：服务配置 canTakeScreenshot 需用户重新开启服务生效。 */
    /** GET /plugin/list：社区插件清单（远程拉取 + 内置兜底，v1.2.113） */
    private fun pluginList(): Pair<Int, String> {
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

    /** POST /plugin/install：一键安装 dsh 插件（后台执行，202=已开始，v1.2.113） */
    private fun pluginInstall(body: String): Pair<Int, String> {
        val pkg = runCatching { JSONObject(body).optString("package") }
            .getOrDefault("").trim()
        if (pkg.isBlank()) return 400 to """{"ok":false,"error":"field 'package' required"}"""
        val ctx = shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        PluginCenter.install(ctx, pkg) { ok, msg ->
            runCatching {
                notify(
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

    /**
     * GET /ocr —— 离线 OCR 识别当前屏幕（v1.2.123）。
     * 无障碍节点树拿不到文字的场景（OPPO 浏览器/WebView/自绘界面）用它
     * 作为独立文本来源；`/screen` 在文本稀少时会自动附带 OCR 结果
     * （dumpScreenCompact 兜底），本端点用于强制刷新与诊断。
     * ?force=1 忽略缓存重新识别。
     */
    private fun ocr(ctx: Context, query: String): Pair<Int, String> {
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
            return 503 to """{"ok":false,"error":"a11y_off","reason":"accessibility service not enabled for screenshot capture"}"""
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
            bmp.recycle()
            val arr = JSONArray()
            r.blocks.forEach { b ->
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
        val arr = JSONArray()
        for (b in r.blocks) {
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

    /** GET /local/status —— 本地快速决策层的就绪状态（v1.2.121） */
    private fun localStatus(ctx: Context): Pair<Int, String> =
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

    /**
     * POST /local/ensure —— 确保本地模型与服务就绪（后台执行，v1.2.121）。
     * 首次调用会下载 15MB 模型并启动 llama-server；返回 202 立即开始，
     * 结果通过 /local/status 轮询（或等通知）。
     */
    private fun localEnsure(ctx: Context, query: String): Pair<Int, String> {
        val install = parseQuery(query)["install"] in listOf("1", "true", "yes")
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

    /**
     * POST /pick —— 本地快速决策（v1.2.120）。
     *
     * 主人设计的架构：本地只做「文本 → 选哪个节点」的判断题（毫秒级），
     * 坐标由无障碍层直接取，云端模型负责规划与兜底。
     *
     * body: {"target":"WLAN 开关", "tap":true, "clickableOnly":true}
     * resp: {"ok":true,"via":"exact|semantic|none","text":"...","x":..,"y":..,
     *        "score":0.93,"tapped":true}
     *
     * via=none 表示本地判断不了 → 调用方（AI）应自己 dump 决定（云端兜底）。
     */
    private fun pick(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    /**
     * GET /frame-diff —— 帧差：本地 <5ms 判断「画面变了没」（v1.2.122）。
     *
     * 事件流覆盖不了**动态视觉场景**（Canvas 游戏、视频、动画、WebGL）
     * —— 那些没有节点树也没有可用事件。帧差是这类场景唯一可靠的判据。
     */
    private fun frameDiff(): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    /**
     * POST /frame-wait —— 等画面变化（超时返回 changed:false，替代固定 sleep）。
     */
    private fun frameWait(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    /**
     * POST /loop/run —— 本地闭环执行「决策程序」（v1.2.123）。
     *
     * body: 决策程序 JSON（字段说明见 LocalLoopExecutor.run 的 KDoc）
     * resp: 执行报告 JSON —— 每步 via/score/耗时/尝试记录，escalate 时附候选池证据。
     *
     * 同步执行（一条连接跑到结束，上限 180s）：调用方一次请求即可拿到完整闭环报告。
     * 与 /pick 的区别：/pick 是单步判别，/loop/run 是**连续多步闭环**（PICK→ACT→VERIFY
     * →RETRY→ESCALATE），即「云端编译一次、本地跑完一串」的那条快路径。
     */
    private fun loopRun(ctx: Context, body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    // ==================== 视觉定位端点组（v1.2.124） ====================
    //
    // 无节点树场景（游戏 / Canvas / 纯图片）的「目标在哪」。
    // 分工：云端看懂画面并圈出目标（/vision/crop 存模板）→ 本地每帧模板匹配
    // 定位（/vision/track）→ 点击 / 拖动（/vision/tap、/vision/drag）。
    // 手机游戏的操作无非「虚拟按钮点按 + 摇杆/视角拖动」，手势层都能做，
    // 缺的只是视觉定位——这组端点补的就是它，毫秒级、不需要云端往返。

    /**
     * GET /daemon/status —— 悬浮窗用的：daemon 是否在跑任务 / 是否 needsHelp。
     * v1.2.157 用户反馈：daemon 卡住时用户无法主动取消，且一切回应用它又切回浏览器干活。
     * 悬浮窗第三档据此显示/隐藏「退出 daemon」按钮，并可在 needsHelp 时提示用户。
     */
    private fun daemonStatus(): Pair<Int, String> {
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

    /** POST /daemon/stop —— 立即停止当前 daemon 任务（悬浮窗「退出 daemon」按钮） */
    private fun daemonStop(): Pair<Int, String> {
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

    /** $DSH_HOME（引擎子进程的 dsh-home 目录；悬浮窗/daemon 端点共用） */
    private fun dshHome(): java.io.File {
        val c = shotCtx ?: return java.io.File("")
        return java.io.File(EngineConfig.engineRoot(c).parentFile, "dsh-home")
    }

    private fun visionDir(ctx: Context): java.io.File =
        java.io.File(ctx.filesDir, "vision").apply { mkdirs() }

    /**
     * POST /vision/crop —— 从当前屏幕裁剪一块存为模板（云端圈目标用）。
     * body: {"id":"btn_attack", "x":..,"y":..,"w":..,"h":..} 绝对像素
     *   或  {"id":"..", "nx":0.7,"ny":0.8,"nw":0.2,"nh":0.2} 归一化坐标
     * resp: {"ok":true,"id":"..","w":..,"h":..,"pngBytes":N}
     */
    private fun visionCrop(ctx: Context, body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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
        val f = java.io.File(visionDir(ctx), "$id.png")
        f.writeBytes(png)
        return 200 to """{"ok":true,"id":$id,"w":$cw,"h":$ch,"pngBytes":${png.size},"path":"${f.absolutePath}"}"""
    }

    /** 读模板文件并解码（/vision/track 与 tap/drag 共用） */
    private fun loadTemplate(ctx: Context, id: String): Triple<IntArray, Int, Int>? {
        val f = java.io.File(visionDir(ctx), "$id.png")
        if (!f.exists()) return null
        return app.dsh.mobile.VisionTracker.decodePng(f.readBytes())
    }

    private fun trackOnce(
        svc: DshAccessibilityService,
        ctx: Context,
        id: String,
        region: FloatArray? = null,
    ): app.dsh.mobile.VisionTracker.Match? {
        val tmpl = loadTemplate(ctx, id) ?: return null
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

    /** 解析可选的搜索区域（归一化 nx/ny/nw/nh） */
    private fun parseRegion(obj: JSONObject): FloatArray? {
        val r = obj.optJSONObject("region") ?: return null
        return floatArrayOf(
            r.optDouble("nx", 0.0).toFloat(),
            r.optDouble("ny", 0.0).toFloat(),
            r.optDouble("nw", 1.0).toFloat(),
            r.optDouble("nh", 1.0).toFloat(),
        )
    }

    /**
     * POST /vision/track —— 定位模板在当前屏幕的位置（本地模板匹配，毫秒级）。
     * body: {"id":"btn_attack"}
     * resp: {"ok":true,"x":..,"y":..,"score":0.88,"costMs":12,...}
     */
    private fun visionTrack(ctx: Context, body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val id = runCatching { JSONObject(body).optString("id") }.getOrDefault("")
        if (id.isBlank()) return 400 to """{"ok":false,"error":"field 'id' required"}"""
        if (loadTemplate(ctx, id) == null) return 404 to """{"ok":false,"error":"template '$id' not found (use /vision/crop first)"}"""
        val region = parseRegion(runCatching { JSONObject(body) }.getOrDefault(JSONObject()))
        val m = trackOnce(svc, ctx, id, region)
            ?: return 500 to """{"ok":false,"error":"match failed (screenshot unavailable?)"}"""
        return 200 to buildString {
            append("""{"ok":true,"id":${JSONObject.quote(id)},"x":${m.x},"y":${m.y},""")
            append(""""score":${"%.3f".format(m.score)},"costMs":${m.costMs},""")
            append(""""proc":{"x":${m.procX},"y":${m.procY},"w":${m.procW},"h":${m.procH},"fw":${m.frameW},"fh":${m.frameH}}}""")
        }
    }

    /**
     * POST /vision/tap —— 定位并点击（虚拟按钮的标准操作）。
     * body: {"id":"btn_attack", "minScore":0.6}
     */
    private fun visionTap(ctx: Context, body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    private fun withTimeoutTap(svc: DshAccessibilityService, x: Int, y: Int): Boolean =
        runCatching { svc.dispatchTapRect(android.graphics.Rect(x, y, x, y)) }.getOrDefault(false)

    /**
     * POST /vision/drag —— 定位并拖动（摇杆 / 视角滑动）。
     * body: {"id":"joystick", "dx":80, "dy":0, "ms":600}  从目标中心拖 dx,dy（原图像素）
     *   或  {"id":"joystick", "toNx":0.5,"toNy":0.8}      拖到屏幕归一化位置
     */
    private fun visionDrag(ctx: Context, body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    /** GET /vision/list —— 已存模板列表 */
    private fun visionList(ctx: Context): Pair<Int, String> {
        val files = visionDir(ctx).listFiles { f -> f.name.endsWith(".png") } ?: emptyArray()
        val arr = org.json.JSONArray()
        files.forEach { arr.put(JSONObject().put("id", it.nameWithoutExtension).put("bytes", it.length())) }
        return 200 to JSONObject().put("ok", true).put("templates", arr).toString()
    }

    /**
     * POST /memory/lookup —— 查场景记忆（v1.2.125）。
     * body: {"goal":"...", "dump":"..."(可选，不给则自动读屏)}
     * resp: {"ok":true,"hit":true,"similarity":0.88,"reused":3,"plan":{...}}
     */
    private fun memoryLookup(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val goal = obj.optString("goal").trim()
        if (goal.isBlank()) return 400 to """{"ok":false,"error":"field 'goal' required"}"""
        var dump = obj.optString("dump")
        if (dump.isBlank()) {
            val svc = DshAccessibilityService.instance
                ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
            dump = withScreenTimeout("memory.dump") { svc.dumpScreenCompact("all") }
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

    /**
     * POST /memory/store —— 存一条成功决策（v1.2.125）。
     * body: {"goal":"...", "plan":{...}, "dump":"..."(可选)}
     */
    private fun memoryStore(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val goal = obj.optString("goal").trim()
        val plan = obj.optJSONObject("plan")
        if (goal.isBlank() || plan == null) return 400 to """{"ok":false,"error":"fields 'goal' and 'plan' required"}"""
        var dump = obj.optString("dump")
        if (dump.isBlank()) {
            val svc = DshAccessibilityService.instance
                ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
            dump = withScreenTimeout("memory.dump") { svc.dumpScreenCompact("all") }
                ?.getOrNull() ?: return 504 to """{"ok":false,"error":"screen read timed out"}"""
        }
        val fp = SceneMemory.fingerprint(dump, DshAccessibilityService.instance?.foregroundPackage() ?: "")
        SceneMemory.store(ctx, goal, fp, plan)
        return 200 to JSONObject().put("ok", true).put("stored", true)
            .put("fpSize", fp.size).put("summary", SceneMemory.stats(ctx)).toString()
    }

    /**
     * POST /memory/confirm —— 任务级确认（v1.2.126）：云端判定目标达成后调用，
     * 把本次成功执行产生的 **pending** 记忆转正（pending 不参与命中，防污染）。
     * body: {"goal":"...", "dump":"..."(可选)}
     * resp: {"ok":true,"promoted":N,"pendingLeft":M}
     */
    private fun memoryConfirm(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val goal = obj.optString("goal").trim()
        if (goal.isBlank()) return 400 to """{"ok":false,"error":"field 'goal' required"}"""
        val n = SceneMemory.confirm(ctx, goal)
        return 200 to JSONObject().put("ok", true).put("promoted", n)
            .put("pendingLeft", SceneMemory.pendingCount(ctx)).toString()
    }

    /** /screen-changed：事件驱动变化摘要（v1.2.113）——毫秒级，不 dump 整树 */
    private fun screenChanged(): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val text = runCatching { svc.changedSummary() }.getOrDefault("")
        return if (text.isBlank()) {
            200 to """{"ok":true,"changed":false,"events":[]}"""
        } else {
            val arr = text.lineSequence().joinToString(",") { JSONObject.quote(it) }
            200 to """{"ok":true,"changed":true,"events":[$arr]}"""
        }
    }

    /**
     * GET /app-screenshot?save=1&max=N —— App 自身窗口的原生截图（v1.3.24）。
     *
     * 与 /screenshot 的区别：
     *  - /screenshot 走无障碍服务抓整屏（包含系统状态栏/其它 App），需要服务开启；
     *  - /app-screenshot 用 PixelCopy 抓当前 DSH Mobile 窗口（WebView 的合成结果），
     *    不需要任何权限，但要求 App 在前台（MainActivity 已注册窗口）。
     * 返回 {"ok":true,"path":...,"width":...,"height":...}；读图用 read_image。
     */
    /**
     * GET /process —— 宿主进程信息（v1.3.26）。
     *
     * 为什么加这个：Android 的 untrusted_app 域读不了 `/proc/net/unix`（SELinux EACCES），
     * 设备内 helper 之前靠枚举 `/proc` 找 WebView 的 devtools 抽象 socket，必然失败。
     * 主进程自己知道 pid，而 socket 名是确定规则 `webview_devtools_remote_<app pid>`，
     * 由宿主直接告诉 helper，比扫描/读 /proc 都可靠。
     */
    /**
     * GET /status —— App 运行时状态总览（v1.3.30，Agent 原生控制 API）。
     *
     * 一次返回 Agent 判断上下文所需的一切：进程/前台 Activity、引擎端口与调试开关、
     * 联网状态（传输类型/已验证/计费）、电量、内存、存储、屏幕与权限状态。
     * 全部是只读探测，不做任何危险操作。
     */
    private fun status(): Pair<Int, String> {
        val ctx = shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
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
                .put("webview_debug", webViewDebugEnabled(ctx))

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

    /**
     * GET /settings —— 原生设置当前值（v1.3.30）。
     *
     * 只暴露白名单键；密码类字段打码。写入口见 [settingsSet]。
     */
    private fun settingsGet(): Pair<Int, String> {
        val ctx = shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
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

    /** POST /settings {"set":{"page_scale":100,"orientation":"auto",...}} */
    private fun settingsSet(body: String): Pair<Int, String> {
        val ctx = shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
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

    /**
     * POST /activity {"name":"settings|terminal|main|<Class>"} —— 打开 App 自己的 Activity。
     *
     * 与 /launch-intent 的区别：这里只允许本 App 的 Activity（安全），并在 startActivity
     * 之后 350ms 回读 [AppState.resumedActivityName]：如果前台 Activity 没变，说明系统以
     * BAL 拦下了后台启动（或用户切走了），回执会如实说明，而不是假 ok。
     */
    private fun openActivity(body: String): Pair<Int, String> {
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


    /**
     * POST /media/play {"path"|"url", "title","artist","loop"} —— 原生音频播放（v1.3.40）。
     *
     * 解码与输出交给平台 MediaPlayer（AudioTrack/音频 HAL），不依赖 Termux 里的
     * ALSA/PulseAudio，也不需要在 Termux 安装额外播放器；音频焦点按 USAGE_MEDIA 申请。
     * 支持本机路径（App 同 uid，Termux 文件可直接放）与 http(s) URL。
     */
    private fun mediaPlay(ctx: Context, body: String): Pair<Int, String> {
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

    /** POST /share/text {"text":"...","subject":"..."} —— 把文本/链接转发给其它 App（v1.3.44）。 */
    private fun shareText(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val text = obj.optString("text")
        if (text.isBlank()) return 400 to """{"ok":false,"error":"text required"}"""
        val r = OpenWith.shareText(ctx, text, obj.optString("subject"), obj.optString("app").ifBlank { obj.optString("to") })
        return (if (r.optBoolean("ok")) 200 else 500) to r.toString()
    }

    /** GET /apps?action=send|view&mime=audio/mpeg —— 列出能处理该意图的 App（v1.3.48）。 */
    private fun appsFor(query: String): Pair<Int, String> {
        val ctx = shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        val params = parseQuery(query)
        val action = params["action"] ?: "view"
        if (action != "send" && action != "view") return 400 to """{"ok":false,"error":"action must be send|view"}"""
        return 200 to OpenWith.listHandlers(ctx, action, params["mime"]).toString()
    }

    /** POST /open/file|folder|share {"path":"/sdcard/x.pdf","mime":"application/pdf"} */
    private fun openPath(ctx: Context, body: String, kind: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val path = obj.optString("path").ifBlank { obj.optString("file") }
        if (path.isBlank()) return 400 to """{"ok":false,"error":"path required"}"""
        val app = obj.optString("app").ifBlank { obj.optString("to") }
        val result = OpenWith.open(ctx, path, kind, obj.optString("mime"), app)
        val code = if (result.optBoolean("ok")) 200 else 404
        return code to result.toString()
    }

    private fun mediaPause(ctx: Context): Pair<Int, String> = 200 to AudioPlayer.pause(ctx).put("instant", true).toString()
    private fun mediaResume(ctx: Context): Pair<Int, String> = 200 to AudioPlayer.resume(ctx).put("instant", true).toString()
    private fun mediaStop(ctx: Context): Pair<Int, String> = 200 to AudioPlayer.stop(ctx).let { AudioPlayer.status() }.put("instant", true).toString()

    /** BAL 拦截时的兜底：发一条可点击的通知，用户点一下就能打开目标页。 */
    private fun postOpenNotification(ctx: Context, target: String, cls: Class<out android.app.Activity>?): Boolean = runCatching {
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



    /** v1.3.61：WebView 远程调试默认开启；放 `webview-debug-off` 可关闭。 */
    private fun webViewDebugEnabled(ctx: android.content.Context): Boolean =
        runCatching {
            ctx.getExternalFilesDir(null)?.let { java.io.File(it, "webview-debug-off") }?.isFile != true
        }.getOrDefault(true)

    private fun processInfo(): Pair<Int, String> {
        val ctx = shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        val pid = android.os.Process.myPid()
        val flag = webViewDebugEnabled(ctx)
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

    private fun appScreenshot(query: String): Pair<Int, String> {
        val ctx = shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        if (!AppShot.hasWindow()) {
            return 503 to """{"ok":false,"error":"app foreground window not available; open the DSH Mobile UI (main activity) first"}"""
        }
        val maxDim = parseQuery(query)["max"]?.toIntOrNull()?.coerceIn(256, 4096) ?: 1280
        val shot = AppShot.capture(ctx, maxDim)
            ?: return 500 to """{"ok":false,"error":"app screenshot failed (PixelCopy could not capture the window)"}"""
        return 200 to """{"ok":true,"path":"${shot.first.absolutePath}","width":${shot.second},"height":${shot.third},"hint":"read it with the read_image tool"}"""
    }

    private fun screenshot(query: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    /** POST /gesture → 手势注入（无障碍 dispatchGesture，无需 Shizuku/Root）。
     *  swipe: {"type":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"durationMs":300}
     *  long_press: {"type":"long_press","x":..,"y":..,"durationMs":600}
     *  tap: {"type":"tap","x":..,"y":..} */
    private fun gesture(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    /** POST /key → 全局动作（back/home/recents/notifications/quick_settings）。
     *  ⚠️ 实测本应用内 back 会把整个 Activity 弹到桌面而非关闭弹层，
     *  调用方应配合 /screen 检查前台是否仍在目标界面。 */
    private fun key(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
            // 悬浮窗：导航键也要显示（此前完全没有）
            announceAction(R.string.a11y_action_key, "key " + JSONObject(body).optString("action"))
        return try {
            val action = JSONObject(body).optString("action")
            val ok = svc.performGlobalActionByName(action)
            if (ok) 200 to """{"ok":true,"action":"$action"}"""
            else 400 to """{"ok":false,"error":"unknown action (back|home|recents|notifications|quick_settings)"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** POST /wait → 服务端轮询等待文本出现/消失（省客户端轮询）。
     *  {"text":"..","gone":false,"timeoutMs":5000}；200ms 间隔，上限 15s。
     *  v1.2.31 实测：screenContains 的节点树 IPC 在请求线程上偶发挂起
     *  （found 分支 Read timed out），故改走 dumpScreenJson —— 与 /screen
     *  同一条已验证路径，且整体包 try/catch。 */
    private fun wait(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            val obj = JSONObject(body)
            val text = obj.optString("text").ifEmpty {
                return 400 to """{"ok":false,"error":"missing text"}"""
            }
            val gone = obj.optBoolean("gone", false)
            val timeout = obj.optLong("timeoutMs", 5000L).coerceIn(100L, 15000L)
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
            200 to """{"ok":false,"error":"timeout: text ${if (gone) "still present" else "not found"}"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * GET /diff → 界面差分（v1.2.82，无障碍提案 P1-3）。
     *
     * 只回**变化**的节点（新增/消失），而不是整屏重 dump。
     * 用途：判断"点完界面到底有没有反应"，以及等待列表加载。
     * 首次调用建立基线（added/removed 为空）。
     * 传 ?reset=1 强制重建基线。
     */
    private fun diffScreen(): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val (added, removed) = svc.diffScreen()
        return 200 to JSONObject().apply {
            put("ok", true)
            put("changed", added.isNotEmpty() || removed.isNotEmpty())
            put("added", org.json.JSONArray(added.toList()))
            put("removed", org.json.JSONArray(removed.toList()))
        }.toString()
    }

    /**
     * POST /scroll-center → 滚动查找并让目标居中（v1.2.82，提案 P2-6）。
     * body: {"text":"...","maxSwipes":8,"back":false,"tap":false}
     *
     * 与 /scroll-find 的区别：命中后若目标贴近屏幕边缘，会补一次小幅滑动把它
     * 带到中间区域 —— 避免"点完该项在屏幕边缘，长按/拖拽打偏"。
     */
    private fun scrollCenter(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    /**
     * POST /input → 向输入框写文字（v1.2.54）。
     * body: {"text":"...", "append":false, "target":「<输入框提示文字>」}
     *
     * 此前 AI 能点开搜索框却打不了字 —— 搜索/登录/发消息/填表全部卡死在这一步。
     * 走无障碍 ACTION_SET_TEXT（不走 IME，不受输入法语言/联想干扰），失败回退剪贴板粘贴。
     */
    private fun input(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    /**
     * POST /scroll-find → 在可滚动容器内滚动查找并可选点击（v1.2.54）。
     * body: {"text":"...", "tap":true, "maxSwipes":8, "back":false}
     *
     * 把「swipe → dump → 没找到 → 再 swipe」的手动循环压到服务端一次调用
     * （此前一次找元素要烧 5-10 个工具往返）。
     */
    private fun scrollFind(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    /**
     * POST /idle → 等待界面稳定（v1.2.54）。
     * body: {"timeoutMs":3000, "quietMs":120, "stableReads":3}
     *
     * 点击后立刻读屏会拿到旧界面 → AI 以为没生效 → 重复点。
     * 稳定检测把「点完等它安静下来」变成一次调用，替代硬 sleep 或猜文本。
     * 返回里带 pkg（前台包名），便于确认「还在不在目标界面」。
     */
    private fun idle(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    /**
     * POST /batch → 一次执行一串动作（v1.2.54）。**治「慢」的关键。**
     * body: {"steps":[{"type":"tap","text":"WLAN"},{"type":"idle"}],
     *        "settleMs":2000, "stopOnError":true}
     *
     * 为什么需要：单次动作的桥接往返只有几十毫秒，慢的是**每个动作都要 AI 往返
     * 一次**（dump → LLM 思考是秒级 → 点 → dump…）。10 步流程 = 10 次 LLM 调用。
     * 批量把整串动作压到一次调用里，AI 只思考一次。
     */
    private fun batch(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    /**
     * GET /interference → 外部干预快照（v1.2.54，v1.2.56 修正判定依据）。
     * query: since=<ms> 时返回「自该时刻起是否有人碰过屏幕」。
     *
     * 用户反馈：「人操作突然接管，AI 也不知道」。AI 可在关键步骤前记下时间戳，
     * 执行后再查一次，据此判断流程是否被打断（结果是否可信）。
     *
     * ⚠️ v1.2.56 修复假阳性：**只有触摸能作为「人接管」的判据**。
     * 应用自己启动/弹联想下拉/切页都会触发 window change，旧实现把它也算作干预，
     * 导致实测「启动 Edge、弹下拉时 interfered:true 但 touchCount:0」（误判）。
     * `lastWindowChangeAt` 仍返回但仅供诊断，不参与 interfered 判定。
     */
    private fun interference(query: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    /**
     * POST /overlay → 控制状态悬浮窗（v1.2.57）。
     * body: {"status":"<short status>","action":"tap-text \"<label>\"","show":true,"hide":false}
     *
     * Lets the agent report what it is doing right now. The overlay itself is shown
     * automatically when the accessibility service connects;
     * 本端点用于更新内容；`hide:true` 尊重用户不想看的选择。
     */
    private fun overlay(ctx: android.content.Context, body: String): Pair<Int, String> {
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
                )
                return 200 to """{"ok":true,"structured":true}"""
            }
            // v1.5.7：无障碍/自动化的 status/action 走独立 notice 布局，不覆盖思考链
            val notice = listOfNotNull(
                obj.optString("status").takeIf { it.isNotBlank() },
                obj.optString("action").takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (notice.isNotBlank()) StatusOverlay.setNotice(notice)
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


    /**
     * POST /launch → 启动任意 App（v1.2.59）。
     * body: {"package":"com.example.app"} or {"label":"<displayed name>"}
     *
     * 用户实测确认：normal 模式下 `am start` / `monkey` 被系统拒
     * （SecurityException / cannot find libbinder_ndk.so），
     * 但 App 自身的 PackageManager + startActivity 不需要任何特权。
     * This is the only clean way to launch an arbitrary app by name.
     */
    private fun launch(ctx: Context, body: String): Pair<Int, String> {
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
                    return 200 to """{"ok":true,"launched":"${matches[0].packageName}"}"""
                }
            }
            400 to """{"ok":false,"error":"need 'package' or 'label' field"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * POST /launch-intent —— 按 Intent action 直达系统页面（v1.2.137）。
     * 确定性导航的快捷命令（android.settings.DISPLAY_SETTINGS 等），
     * 供 scr macro 的设置直达与 loop 的 intent 动作共用。
     */
    private fun launchIntent(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val act = obj.optString("action")
        val component = obj.optString("component").ifBlank { obj.optString("activity") }.orEmpty()
        if (act.isBlank() && component.isBlank()) {
            return 400 to """{"ok":false,"error":"need 'action' and/or 'component'"}"""
        }
        if (component.isNotBlank()) return launchComponent(ctx, component, obj)
        return runCatching {
            val intent = android.content.Intent(act)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            obj.optString("data").takeIf { it.isNotBlank() }?.let {
                intent.data = android.net.Uri.parse(it)
            }
            applyIntentExtras(intent, obj)
            ctx.startActivity(intent)
            200 to """{"ok":true,"launched":"$act"}"""
        }.getOrElse { 500 to """{"ok":false,"error":"${it.message}"}""" }
    }

    /**
     * 按显式 component 启动 Activity（v1.3.27）。
     *
     * 回执必须可信（issue 的核心诉求）：
     *  - component 不存在 / 不可见 → 404 component not found；
     *  - 存在但系统 BAL 拦截后台启动 → startActivity 不抛异常，回执带
     *    backgroundLaunchMayBeBlocked 让调用方核对界面，而不是假装一定成功。
     */
    private fun launchComponent(ctx: Context, component: String, obj: JSONObject): Pair<Int, String> {
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
            200 to """{"ok":true,"launched":"$component","component":true,"backgroundLaunchMayBeBlocked":true,"hint":"Android may block background activity starts; if no UI appeared, open it from the app UI (top-bar gear) instead"}"""
        }.getOrElse { 500 to """{"ok":false,"error":"startActivity failed: ${it.message}"}""" }
    }

    /** extras：{"extras":{"name":"text","count":3,"flag":true,"big":1234567890123}} */
    private fun applyIntentExtras(intent: android.content.Intent, obj: JSONObject) {
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

    /** POST /tap-id —— 按 resource-id 点击（v1.2.141）。比文本定位稳：不受界面语言、OCR 误差影响 */
    private fun tapId(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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

    /**
     * GET /scene（v1.8.2，提案 P0-2）：无障碍节点 + OCR 的**单一合并视图**。
     * query: ocr=0 关闭 OCR；engine=mlkit|ppocr6 指定 OCR 引擎（默认自动）。
     * 返回 {ok,nodes:[{text,x,y,bounds,source,clickable,hint?,a11yIndex?}],timings,ocrEngine,scale}。
     */
    private fun scene(ctx: Context, query: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"a11y_off","reason":"accessibility service not enabled"}"""
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

    private data class Box(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val conf: Double) {
        val cx: Int get() = (left + right) / 2
        val cy: Int get() = (top + bottom) / 2
        val h: Int get() = bottom - top
    }

    private data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val cx: Int get() = (left + right) / 2
        val cy: Int get() = (top + bottom) / 2
    }

    /** 提案实测规则：同一 y 带（±18px）且横向间距 < 0.8 字高 → 合成一行。 */
    private fun mergeOcrLines(boxes: List<Box>): List<Line> {
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

    /**
     * POST /act（v1.9.8，提案 P2-7）：事务化批量操作。
     *
     * 一次调用执行多步，每步可带内联 expect；返回每步 status/verified/elapsedMs，
     * 避免「一动作一往返」。step 支持：
     *   tap/tapDesc/tapId/tapXY/listId+itemIndex · input/append · key · sleepMs · expect
     * 顶层：onFail=stop|continue，returnScreens=true。
     */
    private fun act(ctx: Context, body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"a11y_off","reason":"accessibility service not enabled"}"""
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
                    actionOk = xy != null && xy.length() >= 2 &&
                        svc.dispatchTap(xy.optDouble(0).toFloat(), xy.optDouble(1).toFloat())
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

    /**
     * POST /export（v2.3.0）：唯一“导出给用户”出口。
     * body: {"paths":[...],"target":"download|pictures|custom","dir":"..."}；默认 Download/DSH/。
     */
    private fun mediaExport(ctx: Context, body: String): Pair<Int, String> {
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

    /** POST /wait-text —— 等文本出现/消失（v1.2.141）：{text, gone?, timeoutMs?} */
    private fun waitText(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val text = obj.optString("text")
        val gone = obj.optBoolean("gone", false)
        val timeoutMs = obj.optLong("timeoutMs", 8000L).coerceIn(200L, 60_000L)
        if (text.isBlank()) return 400 to """{"ok":false,"error":"text required"}"""
        val t0 = System.currentTimeMillis()
        val deadline = t0 + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val dump = svc.dumpScreenCompact("all")
            if (dump.contains(text, true) != gone) {
                return 200 to """{"ok":true,"text":"$text","gone":$gone,"waitedMs":${System.currentTimeMillis() - t0}}"""
            }
            Thread.sleep(200)
        }
        return 200 to JSONObject().put("ok", false).put("error", "timeout")
            .put("failedAt", "wait-text").put("text", text).put("gone", gone).put("timeoutMs", timeoutMs)
            .put("screenHash", runCatching { svc.dumpScreenCompact("all").hashCode() }.getOrDefault(0))
            .put("hint", "text did not ${if (gone) "disappear" else "appear"} in time; check the page state or retry with a looser anchor")
            .toString()
    }

    /** POST /scroll-edge —— 滚到顶/底（v1.2.141）：{direction: top|bottom} */
    private fun scrollEdge(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val dir = runCatching { JSONObject(body).optString("direction", "bottom") }.getOrDefault("bottom")
        val n = runCatching { svc.scrollToEdge(forward = dir != "top") }.getOrDefault(0)
        return 200 to """{"ok":true,"direction":"$dir","swipes":$n}"""
    }

    /** GET /notif —— 最近的通知（v1.2.141，来自无障碍通知事件缓存） */
    private fun notif(): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val arr = JSONArray()
        svc.recentNotifications().forEach { arr.put(it) }
        return 200 to JSONObject().put("ok", true).put("notifications", arr).toString()
    }

    private fun respond(client: Socket, status: Int, json: String) {
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
