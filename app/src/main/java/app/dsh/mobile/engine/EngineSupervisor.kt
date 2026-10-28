package app.dsh.mobile.engine

import app.dsh.mobile.R

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 引擎监督器：冷启动 → 健康检查 → 运行中 → 崩溃退避重启 的完整状态机。
 *
 * 状态流转：
 *   Idle → Installing → Starting → Healthy(port)
 *        ↘ Backoff(delay, attempt) ↘ Failed(reason) → Stopped
 * 连续健康一次即重置退避计数；达到 MAX_RESTART 后进入 Failed 终态。
 *
 * 自愈层（ProfileGuardian）： Healthy 时快照配置；同签名连续失败触发
 * last-good 回滚；仍失败进入安全模式（归档坏配置空跑）。
 * SafeMode 会作为独立状态暴露给 UI 展示「引擎运行于安全模式」。
 */
class EngineSupervisor(private val ctx: Context) {

    sealed interface State {
        data object Idle : State
        data object Installing : State
        data object Starting : State

        /** 正常健康；tokenUrl = 0.2.0+ WebUI 认证地址（engine.log 的 dsh web: 输出） */
        data class Healthy(
            val port: Int,
            val tokenUrl: String? = null,
            /** 本次启动前自动摘掉的第三方插件；仅在恢复后的首个 Healthy 状态携带。 */
            val recoveredPlugin: String? = null,
        ) : State

        /** 安全模式：配置被隔离后以空配置拉起，功能受限但可用 */
        data class SafeMode(
            val port: Int,
            val tokenUrl: String? = null,
            val recoveredPlugin: String? = null,
        ) : State
        data class Backoff(val delayMs: Long, val attempt: Int) : State
        data class Failed(val reason: String) : State
        data object Stopped : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 运行时解压进度 0f..1f；null = 不在解压中（UI 据此切换 indeterminate） */
    private val _installProgress = MutableStateFlow<Float?>(null)
    val installProgress: StateFlow<Float?> = _installProgress

    /** 当前健康端口，UI 层据此加载 WebView */
    val healthyPort: Int get() = when (val s = _state.value) {
        is State.Healthy -> s.port
        is State.SafeMode -> s.port
        else -> EngineConfig.DEFAULT_PORT
    }

    /**
     * 健康态下轮询 token（最多 60s），拿到后以 copy(tokenUrl=…) 更新 state。
     * 为什么需要：0.2.1 的启动顺序里 `dsh web:` 输出可能晚于健康探测，而 MainActivity
     * 只在 state 变化时决定是否加载/重载 WebView —— 不补发 state 就永远停在裸地址上
     * （白屏 + "dsh web authentication required"）。
     */
    private fun retryTokenUrlWhenReady() {
        val scope = scopeRef ?: return
        scope.launch(Dispatchers.IO) {
            repeat(120) {
                delay(500)
                val cur = _state.value
                if (cur !is State.Healthy && cur !is State.SafeMode) return@launch
                val found = process?.tokenUrl ?: extractTokenUrl() ?: return@repeat
                when (cur) {
                    is State.Healthy -> _state.value = cur.copy(tokenUrl = found)
                    is State.SafeMode -> _state.value = cur.copy(tokenUrl = found)
                    else -> Unit
                }
                Log.i(TAG, "token URL arrived after health poll; state updated")
                return@launch
            }
        }
    }

    /** 从 engine.log 提取**本次启动**的 `dsh web:` 输出（带 token 完整 URL）。
     *  0.2.0 起 WebUI 强制认证，裸 127.0.0.1:3080 会显示认证提示页。
     *  引擎 stdout 由 EngineProcess 泵入 engine.log（环形 2MB，最新在末尾）。
     *  只搜 [logOffsetAtSpawn] 之后的日志段：健康探测可能早于 stdout 送达，
     *  搜全文会拿到上一次启动的旧 token（已失效 → WebView 401 白页，实测）。
     *  stdout 泵有毫秒级延迟，最多重试 ~600ms 等该行出现。 */
    private val TOKEN_REGEX = Regex("dsh web: (http://127\\.0\\.0\\.1:\\d+/\\?token=\\S+)")

    private fun extractTokenUrl(): String? = runCatching {
        repeat(20) {
            val f = logFile()
            if (f.isFile) {
                val text = f.readText()
                val from = logOffsetAtSpawn.coerceIn(0L, text.length.toLong()).toInt()
                // 1) 优先只看本次启动之后的日志段
                TOKEN_REGEX.findAll(text.substring(from)).lastOrNull()?.let {
                    return@runCatching it.groupValues[1]
                }
                // 2) 回退：engine.log 是 2MB 环形文件，超过上限会从头部截断 →
                //    logOffsetAtSpawn 失效，此时在文件尾部找**最后一条** token。
                //    0.2.1 实测：health 探测早于 `dsh web:` 输出，且截断后 offset 指向
                //    文件末尾，seg 永远为空 → 必须回退，否则永远拿不到 token（白屏）。
                val tail = if (text.length > 8192) text.substring(text.length - 8192) else text
                TOKEN_REGEX.findAll(tail).lastOrNull()?.let {
                    return@runCatching it.groupValues[1]
                }
            }
            Thread.sleep(100)
        }
        Log.w(TAG, "extractTokenUrl failed: no dsh web: token in ${logFile().absolutePath}")
        null
    }.getOrNull()

    /** WebUI 应加载的地址：优先带 token 的完整 URL，裸地址仅作回退 */
    fun webUrl(): String = when (val s = _state.value) {
        is State.Healthy -> s.tokenUrl ?: "http://127.0.0.1:${s.port}/"
        is State.SafeMode -> s.tokenUrl ?: "http://127.0.0.1:${s.port}/"
        else -> "http://127.0.0.1:${EngineConfig.DEFAULT_PORT}/"
    }

    private var process: EngineProcess? = null
    /** 启动前摘掉的插件名；恢复成功后的首个 Healthy 状态会带上它，随后清空。 */
    @Volatile private var recoveredPlugin: String? = null
    /** 本次引擎启动时 engine.log 的长度：extractTokenUrl 只搜此偏移之后的日志段 */
    @Volatile private var logOffsetAtSpawn = 0L
    private var loopJob: Job? = null
    private var userStop = false
    private var scopeRef: CoroutineScope? = null

    /**
     * 用户是否显式退出过且尚未重新启动（通知栏「退出」置位）。
     * 期间 MainActivity.onResume 的自动拉起必须跳过，否则「退出」会被生命周期
     * 立刻撤销（用户实测：通知栏点退出 → 引擎又被拉起来 ✗）。
     * 重新启动（重启按钮 / 权限模式切换等显式动作）会清掉该状态。
     */
    fun isUserStopped(): Boolean = userStop && (loopJob?.isActive != true)

    /** v1.3.73：用户主动打开 App 时清除「显式退出」标记，允许正常启动引擎。 */
    fun clearUserStop(reason: String) {
        if (userStop) {
            userStop = false
            Log.i(TAG, "user-stop flag cleared: $reason")
        }
    }

    /**
     * 用户**从通知栏点「退出」**时的回调（v1.2.187，v1.2.188 审查后改为回调式）。
     *
     * 由 MainActivity 在 onCreate 注册、onDestroy 注销。收到回调时它负责
     * `finishAndRemoveTask()` —— 销毁 WebView、释放 ~380MB。
     *
     * ## 为什么不用「标记位」而是回调
     * 标记位必须在某个时刻清零，否则用户**下次打开 App 会被立刻关掉**
     * （审查发现 P0：explicitExit 只有 start() 会清零，而 start() 被
     * `isUserStopped()` 拦住永不执行）。回调方式天然满足"只对本次退出生效"：
     * Activity 不在（已被系统回收）时回调为 null → 什么也不做，也不留残留状态。
     */
    @Volatile var onExplicitExit: (() -> Unit)? = null

    /** 派发显式退出回调到主线程（任何线程调用都安全）。 */
    private fun dispatchExplicitExit() {
        val cb = onExplicitExit
        if (cb == null) {
            Log.i(TAG, "explicit exit: no live Activity to finish (nothing to release)")
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { cb() }
                .onFailure { Log.w(TAG, "explicit exit callback failed: ${it.message}") }
        }
    }

    /** 重启互斥：重复点击重启按钮是 no-op（并发 stop/start 会互相踩踏，实测事故） */
    @Volatile private var restarting = false
    /** 用户重启的起点时间戳（healthy 时打印总耗时，用于定位「重启很久」这类反馈） */
    @Volatile private var restartStartedAt: Long? = null
    private val guardian by lazy { ProfileGuardian(ctx) }

    fun start(scope: CoroutineScope) {
        scopeRef = scope
        if (loopJob?.isActive == true) return
        userStop = false
        loopJob = scope.launch(Dispatchers.Default) { supervisionLoop() }
    }

    /**
     * 停止引擎。
     * @param graceMs 优雅退出等待上限。
     * @param explicit true = 用户在通知栏点了「退出」（会结束主界面并销毁 WebView
     *                 以释放内存）；false = 服务销毁等被动停止。
     */
    fun stop(graceMs: Long = 10_000, explicit: Boolean = false) {
        userStop = true
        loopJob?.cancel()
        process?.stop(graceMs)
        process = null
        // v1.2.188（审查 P2）：stop() 只 cancel 不 join 监督循环 —— 若恰好在
        // spawnEngine() 的阻塞段里，已 fork 的 node 可能没有引用、无人 stop。
        // 这里补一次孤儿清理（端口占用检测 + 同 uid pkill），覆盖该窗口。
        runCatching { cleanupOrphanEngine() }
        // 引擎侧终端（WebUI 侧边栏 / dsh-terminal）随引擎结束一起收掉：
        // 这些 PTY 的读者是引擎里的 node-pty shim，引擎没了就没人取数据了；
        // 不清理会占着 App 的 PTY 槽（上限 6），重启几次后新终端就开不出来。
        // App 内终端（TerminalActivity）用的是另一套 PtyNative 调用，不受影响。
        runCatching { PtyBridge.closeAll() }
        _state.value = State.Stopped
        if (explicit) dispatchExplicitExit()
    }

    /**
     * 热重启：完整走一遍 stop → start（TERM→KILL 优雅停止 + 监督循环重进）。
     * 与进程被杀后的自动退避不同，这是用户显式动作：退避计数天然从零开始，
     * guardian 的 Healthy 快照/计数不受影响。
     *
     * ⚠️ v1.2.37 修复（用户实测「点三下才重启」）：旧实现里
     *   a) `process.stop()` 要等引擎优雅退出**最长 10 秒**，且 Stopped 状态在其之后才置位
     *      → 第一次点击界面毫无反馈（用户以为没点到，继续点）；
     *   b) 调用方（MainActivity/SettingsActivity）每次点击各起一条线程，多点几下就是
     *      多条 restart 线程并发 stop/start 互相踩踏 → 「待启动→进程异常→2 秒后自愈重启」。
     * 现在：立即置 Starting（UI 立刻有反馈）、整个重启跑在监督循环同一个 scope 上、
     * [restarting] 互斥保证重复点击是 no-op（幂等）。本方法立即返回、不阻塞调用线程。
     */
    fun restart() {
        val scope = scopeRef ?: return
        if (restarting) {
            Log.i(TAG, "restart already in progress, ignoring duplicate tap")
            return
        }
        Log.i(TAG, "restart: hot restart requested by user")
        restarting = true
        val t0 = System.currentTimeMillis()
        restartStartedAt = t0
        scope.launch(Dispatchers.IO) {
            try {
                _state.value = State.Starting
                // 用户重启：3.5s 宽限（足够引擎落盘收尾；TERM 无响应时尽快 KILL，不再干等 10s）
                stop(graceMs = 3_500)
                Log.i(TAG, "restart: previous engine stopped (${System.currentTimeMillis() - t0}ms)")
                _state.value = State.Starting   // stop() 会置 Stopped，重启路径立即回到启动中
                start(scope)
            } catch (e: Exception) {
                Log.w(TAG, "restart failed: ${e.message}")
            } finally {
                restarting = false
            }
        }
    }

    /** 手动导出引擎日志（用户反馈通道） */
    fun logFile(): File = File(EngineConfig.engineRoot(ctx), "engine.log")

    /**
     * 失败签名：用「引擎日志尾部 + 退出码」的哈希近似。
     * 确定性崩溃（坏配置）每次堆栈一致 → 同签名；
     * 偶发崩溃（OOM/被杀）尾部随机 → 不同签名不累计。
     */
    private fun failureSignature(status: Int?): String {
        // 签名必须稳定：崩溃堆栈里的行号/内存地址/时间戳每次都变，原文哈希会让
        // streak 永远重置 → 永远到不了阶段阈值（自愈失效实测根因）。
        // 数字全部归一为 #，保留错误结构与字面（不同异常仍然不同签名）。
        val tail = runCatching {
            logFile().readText().takeLast(4096)
                .lineSequence()
                .filter { it.contains("Error") || it.contains("at ") }
                .toList()
                .takeLast(12)
                .joinToString("\n") { it.replace(Regex("\\d+"), "#") }
        }.getOrDefault("")
        return "$status:${tail.hashCode()}"
    }

    /**
     * **本次启动**的日志尾部是否出现「端口被占」类失败。
     *
     * 只读 [logOffsetAtSpawn] 之后的内容 —— 引擎日志是**追加**的，整份读会把
     * 上一次启动留下的 EADDRINUSE 当成本次失败（那样每次启动都会误触发清场）。
     */
    private fun looksLikePortConflict(): Boolean = runCatching {
        val f = logFile()
        if (!f.isFile) return@runCatching false
        val len = f.length()
        val from = logOffsetAtSpawn.coerceIn(0L, len)
        // 只取本次启动写下的尾部（最多 8KB，足够覆盖一段启动堆栈）
        val tail = java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(maxOf(from, len - 8192))
            val buf = ByteArray((len - maxOf(from, len - 8192)).toInt())
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
        tail.contains("EADDRINUSE") || tail.contains("address already in use", ignoreCase = true)
    }.getOrDefault(false)

    private suspend fun supervisionLoop() {
        var backoffIndex = 0
        // 端口清场计数（v1.3.1）：防「非引擎进程永久占端口」时绕过退避无限重试
        var portReclaimCount = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive && !userStop) {
            // 仅当引擎「自行死亡」（fork 失败 / 启动期退出）才值得让 guardian 定罪；
            // 健康超时自杀、安装异常、Healthy 后运行中退出都不算配置崩溃。
            var deterministicFailure: String? = null
            val bootT0 = System.currentTimeMillis()
            var stepT = bootT0
            fun step(name: String) {
                val now = System.currentTimeMillis()
                Log.i(TAG, "boot step $name: ${now - stepT}ms (total ${now - bootT0}ms)")
                stepT = now
            }
            try {
                // 启动前先把被误隔离的引擎内置 patch 恢复（自愈；防 ENOENT fail-loud）
                val healed = withContext(Dispatchers.IO) { guardian.restoreQuarantinedBuiltinOverlays() }
                step("guardian-restore")
                if (healed > 0) Log.w(TAG, "guardian: restored $healed quarantined builtin overlay(s)")

                // Agent 上下文种子（m1.35）：幂等预写 $HOME/AGENTS.md（dsh 原生 user-global
                // 指令），让 Agent 首轮就带 Android 世界观，省掉环境探索 token
                withContext(Dispatchers.IO) { AgentContextSeed.ensure(ctx) }
                AgentContextSeed.startGuard(ctx)
                step("agent-seed")

                // 孤儿进程回收（v1.2.104）：**必须早于桥 bind 与引擎 fork** ——
                // 历史孤儿会攥着继承来的 3080/3083 监听 socket 不撒手（无 CLOEXEC，
                // 见 OrphanReaper 顶部），不先清掉，桥会静默 bind 失败、引擎会起不来。
                withContext(Dispatchers.IO) { OrphanReaper.reap(ctx) }
                step("orphan-reap")

                // Agent 能力桥（v1.1.0）：notify/scr 的 HTTP 后端，全模式启动
                withContext(Dispatchers.IO) { AgentBridge.start(ctx) }
                step("bridge")

                // 属性自愈（v1.3.1 扩展到**所有模式**，含 Root）。
                //
                // ⚠️ 旧实现只在非 Root 模式跑（`mode != ROOT`），漏掉了两类真实故障
                // （用户反馈「安卓 11 上异常退出后无法使用，删掉重装又好了」）：
                //   · Root 引擎以 uid 0 写出的文件属主是 root，而 **App 自己**（非 root）
                //     读不了 engine/ 与 dsh-home/：`.runtime-version` 版本戳、
                //     `profile-guardian.meta`、插件、AGENTS.md → 启动链路 EACCES 累积；
                //   · 与模式无关的属性损坏（SELinux 标签错、权限位被剥）同样致命。
                // 用户只能靠「删掉重装」恢复（卸载清空私有目录 = 变相重置属性），
                // 这正是该缺陷最典型的现场指纹。
                // 现在无论什么模式都查一遍，检测到就修（chown + chmod + restorecon）。
                val needFix = withContext(Dispatchers.IO) { Privilege.dshHomeNeedsOwnershipFix(ctx) }
                if (needFix) {
                    Log.w(TAG, "found files with broken ownership/permissions (root residue or bad labels); healing")
                    val fixed = withContext(Dispatchers.IO) { Privilege.fixHomeOwnership(ctx) }
                    Log.w(TAG, "attribute healing ${if (fixed) "succeeded" else "unavailable (no shizuku/su)"}")
                }
                step("home-owner-check")

                _state.value = State.Installing
                withContext(Dispatchers.IO) {
                    var lastPct = -1
                    RuntimeInstaller(ctx).ensureInstalled { frac ->
                        // 仅整 1% 变化时发布，避免 2 万次无效状态更新
                        val pct = (frac * 100).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            _installProgress.value = frac
                        }
                    }
                }
                _installProgress.value = null
                step("runtime-install")

                // 用户数据外置（v1.2.99）：sessions/workspaces 迁到公开目录（软链），
                // profiles 保持私有（上游强制凭证 0600，共享存储给不了权限位）。
                // 必须在 spawn 前完成 —— 引擎启动即读这些目录。
                withContext(Dispatchers.IO) { EngineConfig.ensureUserDataExternal(ctx) }
                step("user-data")

                _state.value = State.Starting
                // 旧 marker 可能来自上一次被强杀的引擎；本次 fork 前先清掉，
                // 否则一个 stale 的 degraded 结果会把正常插件误摘掉。
                withContext(Dispatchers.IO) { PluginRecovery.prepareForBoot(ctx) }
                val bootId = java.util.UUID.randomUUID().toString()
                val proc = withContext(Dispatchers.IO) { spawnEngine(bootId) }
                process = proc
                step("spawn")

                val healthy = pollHealth(EngineConfig.DEFAULT_PORT, proc)
                step("health-poll")
                if (!healthy) {
                    // 端口没监听/被外来进程占着：清掉外来者，下一轮 spawn 才能拿到端口
                    runCatching { killForeignPortHolders(EngineConfig.DEFAULT_PORT) }
                }
                if (healthy) {
                    var pluginRecoveryRequested = false
                    // HTTP 已监听不代表 Cordis Loader 的关键 entry 已成功激活。
                    // 等待内置 probe 写出本次 boot 的 marker；缺失只记诊断，不阻断慢设备。
                    val health = withContext(Dispatchers.IO) { awaitPluginHealth(proc, bootId) }
                    if (health != null) {
                        Log.i(
                            TAG,
                            "plugin health: ok=${health.ok} missing=${health.missingServices.joinToString(",")} " +
                                "failed=${health.failedEntries.joinToString(",") { "${it.id}:${it.name}" }}",
                        )
                    } else {
                        Log.w(TAG, "plugin health marker missing or unreadable; keeping engine available")
                    }
                    if (health?.ok == true) {
                        withContext(Dispatchers.IO) { PluginRecovery.onHealthy(ctx) }
                    }
                    if (health != null && !health.ok) {
                        val action = withContext(Dispatchers.IO) { PluginRecovery.quarantineOne(ctx, health) }
                        if (action != null) {
                            recoveredPlugin = action.packageName
                            pluginRecoveryRequested = true
                            Log.e(
                                TAG,
                                "plugin recovery: disabled ${action.packageName}; reason=${action.reason}; " +
                                    "restart=${action.attempt}/${3}",
                            )
                            // 不把 degraded engine 暴露给 WebView；只重启一次本轮，
                            // PluginRecovery 的持久化上限负责阻止无限重启风暴。
                            proc.stop(graceMs = 1_500)
                            deterministicFailure = null
                            backoffIndex = 0
                        } else {
                            Log.e(TAG, "plugin recovery: no safe candidate; leaving profile unchanged")
                        }
                    }
                    if (pluginRecoveryRequested) {
                        // 这次是有界的插件修复重启，不进入 ProfileGuardian 的配置定罪计数。
                    } else {
                        // 端口已监听但健康探针明确报告 degraded 时，不能把坏 profile
                        // 快照成 last-good；否则后续崩溃回滚会再次恢复同一坏插件。
                        if (health?.ok != false) {
                            withContext(Dispatchers.IO) {
                                guardian.resetCrashStreak()
                                guardian.snapshotLastGood()
                            }
                        } else {
                            Log.w(TAG, "plugin health degraded; keeping previous last-good snapshot")
                        }
                        backoffIndex = 0
                        val safe = guardian.inSafeMode()
                        // 优先用进程 stdout 抓到的 token（不依赖日志文件权限/时序）
                        val tokenUrl = proc.tokenUrl ?: extractTokenUrl()
                        Log.i(TAG, "token url extracted: ${if (tokenUrl == null) "null" else "yes (len=" + tokenUrl.length + ")"}")
                        val notice = recoveredPlugin
                        recoveredPlugin = null
                        val t1 = System.currentTimeMillis()
                        val restartMs = restartStartedAt?.let { " (total restart took ${t1 - it}ms)" } ?: ""
                        restartStartedAt = null
                        Log.i(TAG, (if (safe) "engine healthy in SAFE MODE on :${EngineConfig.DEFAULT_PORT}" else "engine healthy on :${EngineConfig.DEFAULT_PORT}") + restartMs)
                        _state.value =
                            if (safe) State.SafeMode(EngineConfig.DEFAULT_PORT, tokenUrl, notice)
                            else State.Healthy(EngineConfig.DEFAULT_PORT, tokenUrl, notice)
                        // 0.2.1 起 `dsh web:` 那行可能比健康探测晚几百毫秒才打印：
                        // extractTokenUrl() 只等 600ms，拿不到就会把裸地址交给 WebView，
                        // 服务端返回纯文本 "dsh web authentication required"（白屏）。
                        // 所以健康态先放行，token 迟到时补发一次 state（UI 会据此重载）。
                        if (tokenUrl == null) retryTokenUrlWhenReady()
                        // 运行时热更新（2026-10-18）：健康启动的这一刻回收上一版 engine.old
                        // （apply 成功时登记的 .runtime-reclaim 决定要不要真删）。放在这里而不是
                        // apply 自己的健康等待里 —— apply 那次不能删，回滚点必须活到下一次启动。
                        // 内部 runCatching，任何异常都不影响启动。
                        RuntimeUpdater.onEngineHealthy(ctx)
                        // Shizuku 模式：引擎就绪后启动 ADB 级访问桥（shz 包装器回呼用）；其他模式自动关停
                        withContext(Dispatchers.IO) {
                            ShizukuHttpBridge.start(ctx, EngineConfig.DEFAULT_PORT)
                        }
                        // 阻塞等待进程退出（被杀/崩溃）。Healthy 后退出视为资源类偶发，
                        // 不计入 guardian（那是普通退避该管的事，与配置无关）。
                        val status = proc.exitFuture.get()
                        if (userStop) break
                        Log.w(TAG, "engine exited, raw status=0x${status.toString(16)}")
                    }
                } else if (proc.exitFuture.isDone) {
                    // 启动期内进程自己死了——唯一进入自愈判定的信号
                    deterministicFailure = failureSignature(lastExitStatus)
                } else {
                    // 健康检查超时：引擎没死是我们主动停的——很可能是慢启动，
                    // 绝不计入崩溃计数（m1.6.4 真机误伤教训）
                    proc.stop()
                }
            } catch (e: EngineStartException) {
                if (userStop) break
                Log.e(TAG, "supervision failure", e)
                // ⚠️ v1.3.1：su 探测失败（"no usable su"）是**环境瞬时问题**，不是坏配置。
                // 场景：App 被强杀后立刻重启，magiskd 对该 app 的 su 会话尚未重建、
                // 或设备刚开机 daemon 还没就绪 —— 三个探针各 2s 超时 → usableSuPrefix()
                // 返回 null。旧实现把它当确定性崩溃计入 guardian（签名 "null:<hash>"），
                // 于是「一次异常杀死 + 几轮 su 忙」就足以让 guardian 回滚用户配置、
                // 进而 SAFE MODE（归档配置）——这正是用户报的二次启动起不来的放大机制。
                // 现在明确隔离：这类失败只退避重试，绝不判罪。
                if (e.message?.contains("no usable su") == true) {
                    Log.w(TAG, "su unavailable right now — treating as transient, not a config failure")
                    deterministicFailure = null
                } else {
                    deterministicFailure = failureSignature(null)
                }
            } catch (e: EngineArchMismatchException) {
                // 确定性故障（v1.3.3，issue #23）：APK 里带的 runtime 就是错的架构，
                // 重试多少次都必然失败 —— 直接进 Failed 并说清原因，别让用户看着
                // 「连续 14 次启动失败」的模糊文案反复重装。
                Log.e(TAG, "runtime architecture mismatch — stopping (retrying cannot help)", e)
                _state.value = State.Failed(ctx.getString(R.string.error_runtime_arch_mismatch))
                return
            } catch (e: Exception) {
                if (userStop) break
                Log.e(TAG, "supervision failure", e)
            }

            // ---- 自愈判定：仅对「引擎真死」且签名连续一致时逐步升级 ----
            // ---- 环境类失败处置（v1.3.1）：与配置无关，绝不交给 guardian 判罪 ----
            // 【历史事故】v1.2.22 / v1.2.32 / v1.2.108 三轮修复都写在这个分支里，
            // 但判断条件是 `deterministicFailure.contains("EADDRINUSE")` —— 而签名是
            // `"<退出码>:<哈希>"`，**永远不可能包含该字面量**，所以这段自愈从未执行过。
            // 后果（用户报的「Root 模式被杀死后二次启动起不来」）：
            //   Root 引擎的 node 是 su 的**孙进程**（PDEATHSIG 只挂到 su 外壳上），
            //   App 被强杀时 node 可能以 uid 0 成为孤儿继续占着 3080 →
            //   新引擎 EADDRINUSE 启动即退 → 本该「清场重试、不计入自愈」，
            //   实际被 guardian 当成「坏配置连续崩溃」→ 回滚 profiles →
            //   SAFE MODE（归档用户配置）→ MAX_RESTART 后 State.Failed。
            // 证据门槛（两道，必须同时成立）：
            //   ① **端口真的被占** —— 硬证据，且清场对空闲端口是无害 no-op；
            //   ② 本次确实处于失败路径（有失败签名，或日志确证端口冲突）。
            // 加②是为了防两类误判：一是日志里**历史**的 EADDRINUSE 被当成本次失败；
            // 二是「健康超时自杀」时我们刚停的进程尚未释放端口（那条路径
            // deterministicFailure 恒为 null，且日志无端口冲突 → 不触发）。
            // ⚠️ 阻塞调用（socket 探测 / Thread.sleep / su）必须切到 IO：
            // 监督循环本身跑在 Dispatchers.Default 上。
            val portEvidence = withContext(Dispatchers.IO) {
                portInUse() && (deterministicFailure != null || looksLikePortConflict())
            }
            if (portEvidence) {
                if (portReclaimCount < EngineConfig.MAX_PORT_RECLAIMS) {
                    portReclaimCount++
                    withContext(Dispatchers.IO) { reclaimPort() }
                    deterministicFailure = null   // 已处置，不计入 guardian（与配置无关）
                    backoffIndex = 0
                    delay(1_200)                  // 等端口真正释放再下一轮
                } else {
                    Log.e(TAG, "port reclaim exceeded ${EngineConfig.MAX_PORT_RECLAIMS} attempts — giving up (occupied by a foreign process?)")
                    portReclaimCount = 0
                }
            }
            deterministicFailure?.let { sig ->
                when (
                    runCatching { guardian.onFailure(sig) }
                        .getOrElse { ProfileGuardian.Action.NONE }
                ) {
                    ProfileGuardian.Action.ROLLED_BACK -> {
                        Log.w(TAG, "guardian: rolled back profiles to last-good")
                        backoffIndex = 0 // 给恢复后的启动全新的退避额度
                    }
                    ProfileGuardian.Action.SAFE_MODE -> {
                        Log.e(TAG, "guardian: verified persistent crash, entering SAFE MODE")
                        backoffIndex = 0
                    }
                    ProfileGuardian.Action.NONE -> {}
                }
            }

            // 统一走退避重启
            backoffIndex++
            if (backoffIndex > EngineConfig.MAX_RESTART) {
                _state.value = State.Failed(ctx.getString(R.string.error_restart_failed_streak, EngineConfig.MAX_RESTART))
                return
            }
            val delayMs = EngineConfig.BACKOFF_STEPS[
                (backoffIndex - 1).coerceAtMost(EngineConfig.BACKOFF_STEPS.size - 1)
            ]
            _state.value = State.Backoff(delayMs, backoffIndex)
            delay(delayMs)
        }
    }

    /** 等待本次引擎写出插件健康标记；marker 本身不包含凭证或用户路径。 */
    private suspend fun awaitPluginHealth(
        proc: EngineProcess,
        bootId: String,
    ): PluginRecovery.Health? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline && !proc.exitFuture.isDone) {
            val health = PluginRecovery.readHealth(ctx, bootId)
            if (health != null) {
                Log.i(TAG, "plugin health marker received (ok=${health.ok})")
                return@withContext health
            }
            Thread.sleep(200L)
        }
        null
    }

    /** 最近一次子进程退出码；仅在进程已退出后可读，避免阻塞 */
    private val lastExitStatus: Int?
        get() = process?.exitFuture?.takeIf { it.isDone }?.get()

    /** 监听指定端口的 socket inode（解析 /proc/net/tcp{,6} 的 LISTEN 行）。 */
    private fun portListenInodes(port: Int): Set<String> {
        val hex = String.format("%04X", port)
        val out = HashSet<String>()
        for (name in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            runCatching {
                java.io.File(name).readLines().drop(1).forEach { line ->
                    val f = line.trim().split(Regex("\\s+"))
                    if (f.size > 9 && f[3] == "0A" && f[1].substringAfter(':') == hex) out.add(f[9])
                }
            }
        }
        return out
    }

    private fun pidsForSocketInodes(inodes: Set<String>): List<Int> {
        if (inodes.isEmpty()) return emptyList()
        val res = ArrayList<Int>()
        runCatching {
            java.io.File("/proc").listFiles()?.forEach { d ->
                val pid = d.name.toIntOrNull() ?: return@forEach
                runCatching {
                    java.io.File(d, "fd").listFiles()?.forEach { fd ->
                        val link = runCatching { android.system.Os.readlink(fd.path) }.getOrNull() ?: return@forEach
                        if (link.startsWith("socket:[")) {
                            val ino = link.removePrefix("socket:[").removeSuffix("]")
                            if (inode_matches(ino, inodes)) { res.add(pid); return@forEach }
                        }
                    }
                }
            }
        }
        return res.distinct()
    }

    private fun inode_matches(ino: String, set: Set<String>) = set.contains(ino)

    /**
     * v1.6.3：杀掉**不是本 App 引擎子进程**、却占着引擎端口的进程。
     *
     * 背景（issue: dshmarket 立即重启与 EngineService 竞争）：市场 helper 会 SIGTERM 当前
     * host 并 detached spawn 一个自己的 `dsh web`；本 App 监督器同时重启受管引擎，两者
     * 抢 3080，WebView 反复 ERR_CONNECTION_REFUSED。这里在 spawn 前/健康失败时把外来
     * 监听者清掉，保证只有受管引擎占端口。
     */
    private fun killForeignPortHolders(port: Int) {
        val inodes = portListenInodes(port)
        if (inodes.isEmpty()) return
        val allow = setOf(android.os.Process.myPid(), process?.pid ?: -1)
        val pids = pidsForSocketInodes(inodes).filter { it !in allow }
        if (pids.isEmpty()) return
        Log.w(TAG, "killing foreign listeners on port $port: $pids")
        for (p in pids) runCatching { android.os.Process.sendSignal(p, 9) }
    }

    /** 等端口释放（最多 ms），避免刚 kill 完立刻 spawn 又撞 EADDRINUSE。 */
    private fun waitPortFree(port: Int, ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline && portListenInodes(port).isNotEmpty()) {
            Thread.sleep(100)
        }
    }

    private fun spawnEngine(bootId: String): EngineProcess {
        // 【v1.2.32】启动前孤儿清理：App 被强杀/force-stop 后，引擎 node 可能成孤儿
        // 继续占着 3080（普通模式与 App 同 uid；Root 模式需 su）。此时新引擎必然
        // EADDRINUSE 无限重启（用户实测 62 次）。pkill 只匹配我们的引擎路径，
        // 不会误伤其他进程；正常重启（旧引擎已 stop）端口已释放，此处为 no-op。
        cleanupOrphanEngine()
        // v1.6.3：dshmarket 的自重启 helper 可能已经/即将在 3080 上起一个独立 dsh web。
        // spawn 前清掉外来监听者并等端口真正释放，避免两条重启路径抢端口导致 WebView 反复断线。
        runCatching { killForeignPortHolders(EngineConfig.DEFAULT_PORT) }
        runCatching { waitPortFree(EngineConfig.DEFAULT_PORT, 2_000L) }
        // 【v1.2.36】记录本次启动的日志起点：token 提取只认这段之后的输出。
        // 否则健康探测先于 stdout 泵送达 "dsh web: ?token=" 行时，会提取到上一次
        // 引擎的旧 token → WebView 加载已失效地址 → "Webpage not available"
        // （模拟器实测：重启后页面 401，engine.log 中新旧 token 并存）。
        logOffsetAtSpawn = runCatching { logFile().length() }.getOrDefault(0L)
        val mode = Privilege.getMode(ctx)
        var suPath: String? = null
        if (mode == PrivMode.ROOT) {
            // Root 保护壳：先备份用户资产 dsh-home，再以 su 整体提权启动引擎。
            // 即便 Root 引擎误改 dsh-home，用户仍可回滚；备份失败不阻断启动，仅告警。
            val backup = Privilege.backupHome(ctx)
            Log.w(TAG, if (backup != null) "ROOT mode: dsh-home backed up to $backup" 
                  else "ROOT mode: WARNING — dsh-home backup failed")
            suPath = Privilege.findSu() ?: throw EngineStartException(
                ctx.getString(R.string.error_root_no_su)
            )
        }
        // v1.2.159：启动前自愈 engine/bin 的 Termux shebang（真机 37 个 wrapper 指向不存在的
        // 路径 → getprop/logcat/node 直接崩）。幂等，仅改第一行。
        runCatching { EngineConfig.healTermuxShebangs(ctx) }
        return EngineProcess.spawn(
            nodeBin = EngineConfig.nodeBin(ctx),
            entryJs = EngineConfig.dshEntry(ctx),
            cwd = EngineConfig.workspaces(ctx),
            env = EngineConfig.buildEnv(ctx, EngineConfig.DEFAULT_PORT, bootId),
            logFile = logFile(),
            suPath = suPath,
            patchPath = runCatching { EngineConfig.ensureAndroidOverlay(ctx).absolutePath }.getOrNull(),
        )
    }

    /** 引擎默认端口当前是否真被占用。
     *
     * ⚠️ 旧实现把探测失败（超时/异常）当作「端口空闲」（getOrDefault(false)）——
     * 而 `listen backlog` 打满、引擎事件循环卡住时 connect 恰好会超时，于是
     * **明明占着却被判为空闲，跳过整个清场**，新引擎照样 EADDRINUSE。
     * 现在区分两种结果：连上 = 占用；连不上但内核报 RST/拒绝 = 空闲；
     * 其它（超时/未知）**按占用处理** —— 宁可多清一次（清场对空闲端口是 no-op），
     * 也不能漏掉真正的占用者。 */
    private fun portInUse(timeoutMs: Int = 700): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", EngineConfig.DEFAULT_PORT), timeoutMs)
                true
            }
        } catch (e: java.net.ConnectException) {
            false            // 明确拒绝 = 没人在听 = 空闲
        } catch (e: Exception) {
            true             // 超时/未知一律按占用（保守：清场无害，漏清致命）
        }
    }

    /**
     * 强制回收 3080：清掉所有占着它的引擎 node 残留（v1.3.1）。
     *
     * ⚠️ Root 模式的孤儿为什么清不干净（用户报的二次启动失败）：
     * 引擎以 `su -c "exec node …"` 启动，PTY 的直接子进程只是 **su 外壳** ——
     * `dsh_pty.c` 的 `PR_SET_PDEATHSIG` 挂在外壳上，App 被强杀时信号**传不到 node**，
     * 于是 node 以 uid 0 变孤儿继续监听 3080。而 app uid 的 `pkill` 对 root 进程
     * 必然 EPERM，唯一的指望是 su 那条。
     *
     * 因此这里做三件事：
     *  ① 同 uid pkill（普通模式的孤儿）；
     *  ② su 兜底 pkill（Root 模式的孤儿）—— 用 `findSu` 覆盖 Magisk/KernelSU 路径；
     *  ③ **兜底再确认**：清完仍被占就再补一轮，并把结果写进日志（不再静默）。
     *
     * ⚠️ 必须排除 LAN 反向代理（`lan-web-proxy`）：它的 cmdline 里同样含
     * `files/engine/bin/node`，误杀会让「局域网访问」在每次引擎重启后静默失效。
     */
    /**
     * v2.3.4：Root 模式孤儿清理改用**脚本文件**执行，不再拼一长串带引号的 shell。
     *
     * 实测教训：app 里 `su -c "<几百字符的 case/regex>"` 的引号在多层拼接后会被破坏，
     * su 侧 shell 直接语法错误退出（日志只有 usableSuPrefix，没有 kill），
     * uid 0 的 node-bin 继续占 3080；而同样的逻辑写成脚本、只传路径就 100% 可杀。
     */
    private fun killScript(): java.io.File? = runCatching {
        val f = java.io.File(ctx.cacheDir, "dsh-kill-orphan.sh")
        val body = """
            #!/system/bin/sh
            # dsh-android: kill orphan engine node (cmdline) then free the port via ss.
            # v2.3.5: full fd scan took ~13s as root; ss gives the pid directly.
            PORT=${'$'}{1:-3080}
            for p in /proc/[0-9]*; do
              n=${'$'}{p#/proc/}
              c=${'$'}(tr '\0' ' ' < "${'$'}p/cmdline" 2>/dev/null) || continue
              case "${'$'}c" in
                *dsh-engine/node-bin*)
                  case "${'$'}c" in *lan-web-proxy*) ;; *) echo "kill ${'$'}n"; kill -9 "${'$'}n" 2>/dev/null;; esac
                  ;;
              esac
            done
            if ! ss -ltn 2>/dev/null | grep -q ":${'$'}PORT "; then
              echo "port free"
              exit 0
            fi
            for pid in ${'$'}(ss -ltnp 2>/dev/null | grep ":${'$'}PORT " | sed -n 's/.*pid=\([0-9]*\).*/\1/p'); do
              echo "kill(port) ${'$'}pid"
              kill -9 "${'$'}pid" 2>/dev/null
            done
            exit 0
        """.trimIndent()
        f.writeText(body)
        f.setReadable(true, false)
        f.setExecutable(true, false)
        f
    }.getOrNull()

    private fun reclaimPort() {
        val port = EngineConfig.DEFAULT_PORT
        Log.w(TAG, "port $port in use — reclaiming (orphan engine node)")
        val script = killScript()
        // ① 同 uid：直接 sh 脚本（普通模式孤儿）
        runCatching {
            val out = ProcessBuilder("/system/bin/sh", script?.absolutePath ?: "", port.toString())
                .redirectErrorStream(true).start().inputStream.bufferedReader().use { it.readText() }
            if (out.isNotBlank()) Log.i(TAG, "same-uid kill: ${out.trim().take(300)}")
        }
        // ② root：su 只执行 `sh <脚本>`（不再拼引号）
        runCatching {
            val out = Privilege.runSu("sh ${script?.absolutePath} $port", timeoutMs = 15_000L)
            if (!out.isNullOrBlank()) Log.i(TAG, "su kill: ${out.trim().take(300)}")
        }
        var freed = waitPortFree(3_000)
        var round = 1
        while (!freed && round < 3) {
            Log.w(TAG, "port $port still held after sweep #$round — retrying via su")
            runCatching { Privilege.runSu("sh ${script?.absolutePath} $port", timeoutMs = 12_000L) }
            freed = waitPortFree(3_000)
            round++
        }
        if (!freed) Log.e(TAG, "port $port STILL in use after $round sweeps — next boot will likely fail")
        else Log.i(TAG, "port $port reclaimed after $round sweep(s)")
    }

    /** 轮询等端口释放，返回是否已空闲 */
    private fun waitPortFree(budgetMs: Long): Boolean {
        val until = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < until) {
            if (!portInUse(250)) return true
            Thread.sleep(120)
        }
        return !portInUse(250)
    }

    /** 启动前端口体检：占用则清场（不做静默跳过）。 */
    private fun cleanupOrphanEngine() {
        if (!portInUse()) return
        reclaimPort()
    }

    /** 稳定窗：windowMs 内进程退出返回 false（启动失败），挺过窗口返回 true */
    private suspend fun awaitStable(proc: EngineProcess, windowMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            if (proc.exitFuture.isDone) return false
            delay(250)
        }
        return !proc.exitFuture.isDone
    }

    /** 轮询 http://127.0.0.1:port 直到响应/超时/子进程提前死亡 */
    private suspend fun pollHealth(port: Int, proc: EngineProcess): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + EngineConfig.HEALTH_TIMEOUT_MS
        val url = "http://127.0.0.1:$port/"
        while (System.currentTimeMillis() < deadline &&
            kotlinx.coroutines.currentCoroutineContext().isActive
        ) {
            // 子进程已死就别干等超时——立即返回走快速退避重试
            if (proc.exitFuture.isDone) return@withContext false
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 2_000
                conn.readTimeout = 2_000
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..499) {
                    // 二次确认：ROOT 模式下前任引擎的孤儿可能还占着端口并回 401，
                    // 只凭一次 401 就宣告健康会把「新进程已 EADDRINUSE 退出」当成成功
                    // （表现为页面 401 白屏 + 引擎每 12s「异常退出」循环）。
                    delay(250)
                    if (proc.exitFuture.isDone) return@withContext false
                    val again = runCatching {
                        (URL(url).openConnection() as HttpURLConnection).run {
                            connectTimeout = 2_000; readTimeout = 2_000
                            val c = responseCode; disconnect(); c
                        }
                    }.getOrDefault(-1)
                    if (again in 200..499) return@withContext true
                }
            } catch (_: Exception) {
                // 引擎尚未监听，继续等
            }
            delay(EngineConfig.HEALTH_INTERVAL_MS)
        }
        false
    }

    companion object {
        private const val TAG = "EngineSupervisor"
    }
}
