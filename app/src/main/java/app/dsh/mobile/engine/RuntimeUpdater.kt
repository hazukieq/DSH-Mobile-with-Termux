package app.dsh.mobile.engine

import android.content.Context
import android.os.Build
import android.util.Log
import app.dsh.mobile.DshApp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 运行时热更新（2026-10-18）：把 APK 内置的 runtime.zip 从「唯一来源」降级为「出厂来源」。
 *
 * 背景：现在升级 dsh 运行时只有一条路 —— 换 APK 重装。可运行时（node + @deepseek-ai/dsh）
 * 与 App 壳（Kotlin/前端）本来就是两回事：agent 在设备上发现运行时缺东西、或想试新版
 * 运行时，都不该逼用户重装 185MB 的 APK（更不能丢 dsh-home 里的会话）。
 *
 * ## 目录布局
 * ```
 * filesDir/
 *   engine/          活动运行时（EngineSupervisor 启动的就是它）
 *   engine.staging/  解压中（临时，随时可删）
 *   engine.new/      已就绪待应用（.runtime-version 为标记）
 *   engine.old/      上一版（回滚用；下一次健康启动后回收）
 *   .runtime-swap    预写注记（apply/rollback 进行中；启动时据此恢复）
 *   .runtime-reclaim 上一版可回收标记（apply 成功且健康后写；下次健康启动删 engine.old）
 * ```
 *
 * ## 状态机
 * ```
 * IDLE --stage--> STAGING --> IDLE
 * IDLE --apply--> APPLYING --> IDLE   (健康检查失败自动回滚)
 * IDLE --rollback--> ROLLING_BACK --> IDLE
 * ```
 * 所有入口都过同一把 [lock]：并发 stage/apply/rollback 直接被拒绝（返回 busy），
 * 不做排队 —— 这类操作动的是引擎的根目录，排队反而让「用户以为没反应」。
 *
 * ## 为什么状态放内存而不是落盘
 * phase / progress / lastResult 是**本次进程**的进行态；跨进程有意义的只有目录与标记文件
 * （.runtime-version / .runtime-source / .runtime-swap / .runtime-reclaim），
 * 它们在 [status] 里重新推导。App 被杀后重启，status 依然能说清「现在跑的是什么、
 * 有没有暂存、能不能回滚」。
 *
 * ## 安全边界
 * - dsh-home / workspaces **一行代码都不碰**（热更新只动 engine* 目录）
 * - 解压后的目录先落在 staging，再原子 rename 成 engine.new；只有 apply 才换活动目录
 * - 换目录前写 .runtime-swap；启动时 [recoverIfNeeded] 按注记把半交换状态修好
 * - 新运行时 90s 内不 Healthy 就自动换回旧树；旧树也起不来则清掉 external 标记，
 *   让下一次启动从 APK 内置 runtime 重装（最后防线：App 永远有一个能跑的引擎）
 */
object RuntimeUpdater {

    private const val TAG = "RuntimeUpdater"

    /** 版本标记（相对 engine 根）；RuntimeInstaller 也读它 */
    const val VERSION_MARKER = ".runtime-version"

    /**
     * 来源标记：内容 `external` 表示这棵树来自热更新，**不是** APK 内置 runtime。
     * RuntimeInstaller.ensureInstalled() 见到它就不再按 MANIFEST 版本比对重装
     * （否则热更新后下一次启动会被静默回退到内置版本）。
     */
    const val SOURCE_MARKER = ".runtime-source"

    /** 热更新元数据（JSON，随树移动）：sha256 / 来源 / 时间 / 设备 ABI */
    const val META_MARKER = ".runtime-meta"

    private const val SWAP_NOTE = ".runtime-swap"
    private const val RECLAIM_FLAG = ".runtime-reclaim"

    /** 健康窗口：与引擎冷启动「装运行时 + spawn + 等待 :3080」同量级，留足余量 */
    private const val HEALTH_WINDOW_MS = 90_000L

    /** 停引擎宽限：与 EngineSupervisor.restart() 的用户重启一致（不干等 10s） */
    private const val STOP_GRACE_MS = 3_500L

    /** 暂存空间余量（解压后的目录树之外还要留给 rename/journal 的余量） */
    private const val STAGE_MARGIN_BYTES = 128L shl 20

    /** .runtime-reclaim 的回收年龄门（见 onEngineHealthy；避免与 apply 收尾竞态） */
    private const val RECLAIM_MIN_AGE_MS = 120_000L

    /** 卡死自愈阈值：phase 非 idle 超过这个时长视为 worker 已死（见 sweepStalePhase） */
    private const val STALE_PHASE_MS = 12 * 60_000L

    /** runtime.zip 里必须存在的两个锚点（缺一说明包不是运行时） */
    private const val ANCHOR_NODE = "bin/node"
    private const val ANCHOR_ENTRY = "lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
    private const val DSH_PACKAGE_JSON = "lib/node_modules/@deepseek-ai/dsh/package.json"

    // ── 进行态（仅本次进程有效）─────────────────────────────────────────────

    enum class Phase(val id: String) {
        IDLE("idle"),
        STAGING("staging"),
        APPLYING("applying"),
        ROLLING_BACK("rolling_back"),
    }

    data class LastResult(val action: String, val ok: Boolean, val detail: String, val at: Long)

    data class Status(
        val installedVersion: String,
        val source: String,
        val stagedVersion: String?,
        val stagedSha: String?,
        val rollbackVersion: String?,
        val engine: String,
        val enginePort: Int,
        val phase: String,
        val progress: Float,
        val lastAction: String,
        val lastOk: Boolean,
        val lastDetail: String,
        val lastAt: Long,
        val error: String,
    ) {
        /** JSON 形态：缺失字段一律空串（不用 null，bash 侧解析更简单） */
        fun toJson(): String = org.json.JSONObject()
            .put("ok", true)
            .put("version", installedVersion)
            .put("source", source)
            .put("staged", stagedVersion.orEmpty())
            .put("staged_sha", stagedSha.orEmpty())
            .put("rollback", rollbackVersion.orEmpty())
            .put("engine", engine)
            .put("engine_port", enginePort)
            .put("phase", phase)
            .put("progress", progress.toDouble())
            .put("last_action", lastAction)
            .put("last_ok", lastOk)
            .put("last_detail", lastDetail)
            .put("last_at", lastAt)
            .put("error", error)
            .toString()

        /**
         * `key: value` 行格式（`GET /runtime/status?format=line`）。
         *
         * 为什么要它：门脚本是纯 bash，没有 jq；用 grep/sed 去啃 JSON 字符串转义
         * 一旦值里出现引号/反斜杠就会解析错。行格式键值稳定、ASCII、`^key: ` 精确匹配，
         * 是「agent 可读」这条要求最省事的实现。
         */
        fun toLine(): String = buildString {
            appendLine("ok: true")
            appendLine("version: $installedVersion")
            appendLine("source: $source")
            appendLine("staged: ${stagedVersion.orEmpty()}")
            // 行格式只给前 16 位（终端里 64 位会换行成噪音）；完整值在 JSON 里
            appendLine("staged_sha: " + stagedSha.orEmpty().take(16))
            appendLine("rollback: ${rollbackVersion.orEmpty()}")
            appendLine("engine: $engine")
            appendLine("engine_port: $enginePort")
            appendLine("phase: $phase")
            appendLine("progress: " + String.format(java.util.Locale.US, "%.2f", progress))
            appendLine("last_action: $lastAction")
            appendLine("last_ok: $lastOk")
            appendLine("last_detail: " + oneLine(lastDetail))
            appendLine("last_at: $lastAt")
            appendLine("error: " + oneLine(error))
        }
    }

    private val lock = ReentrantLock()

    @Volatile private var phase = Phase.IDLE
    /**
     * 本次 phase 的进入时间戳。用途：卡死自愈 —— 若某个 worker 线程被系统杀掉
     * （进程没死但线程没了），phase 会永远停在 staging/applying，之后所有动作都被
     * 当成 busy 拒绝。status() 里超过 [STALE_PHASE_MS] 就把 phase 复位（见 sweepStalePhase）。
     */
    @Volatile private var phaseSince = 0L
    @Volatile private var progress = 0f
    @Volatile private var last: LastResult? = null
    @Volatile private var lastError = ""

    // ── 目录 ─────────────────────────────────────────────────────────────────

    fun stagingDir(ctx: Context): File = File(ctx.filesDir, "engine.staging")
    fun newDir(ctx: Context): File = File(ctx.filesDir, "engine.new")
    fun oldDir(ctx: Context): File = File(ctx.filesDir, "engine.old")

    private fun swapNote(ctx: Context): File = File(ctx.filesDir, SWAP_NOTE)
    private fun reclaimFlag(ctx: Context): File = File(ctx.filesDir, RECLAIM_FLAG)

    // ── 查询 ─────────────────────────────────────────────────────────────────

    /** 当前状态（只读少量标记文件，可在主线程调用；不做完整性全树扫描） */
    fun status(ctx: Context): Status {
        sweepStalePhase()
        val live = EngineConfig.engineRoot(ctx)
        val newD = newDir(ctx)
        val old = oldDir(ctx)
        val sup = supervisorOf(ctx)
        val stagedMeta = readMeta(newD)
        return Status(
            installedVersion = readVersion(live) ?: "unknown",
            source = readSource(live),
            stagedVersion = readVersion(newD),
            stagedSha = stagedMeta?.optString("sha256")?.takeIf { it.isNotBlank() },
            rollbackVersion = readVersion(old),
            engine = engineLabel(sup),
            enginePort = runCatching { sup?.healthyPort ?: EngineConfig.DEFAULT_PORT }
                .getOrDefault(EngineConfig.DEFAULT_PORT),
            phase = phase.id,
            progress = progress,
            lastAction = last?.action.orEmpty(),
            lastOk = last?.ok ?: false,
            lastDetail = last?.detail.orEmpty(),
            lastAt = last?.at ?: 0L,
            error = lastError,
        )
    }

    // ── 入口：三个动作 + 重启 ────────────────────────────────────────────────

    /** phase 变更的唯一入口（同时记时间戳，供 [sweepStalePhase] 判断卡死） */
    private fun enterPhase(p: Phase) {
        phase = p
        phaseSince = System.currentTimeMillis()
        if (p == Phase.IDLE) progress = 0f
    }

    /**
     * 卡死自愈：worker 线程若异常死亡（进程还在），phase 会永远停在非 idle，
     * 之后 stage/apply/rollback 全被 busy 拒绝。超过 [STALE_PHASE_MS] 没有进展
     * 就复位，并把原因写进 error（用户/agent 在 status 里能看到发生过什么）。
     *
     * 正常操作最长约 5 分钟（stage 解压 + apply 两次 90s 健康窗口），所以 12 分钟
     * 是「一定不正常」的阈值；只在 status() 被调用时触发，零后台开销。
     */
    private fun sweepStalePhase() {
        if (phase == Phase.IDLE) return
        val since = phaseSince
        if (since <= 0L || System.currentTimeMillis() - since < STALE_PHASE_MS) return
        if (!lock.tryLock()) return   // 有 worker 正持锁（刚起步）：别抢
        try {
            if (phase != Phase.IDLE && System.currentTimeMillis() - phaseSince >= STALE_PHASE_MS) {
                Log.w(TAG, "stale phase ${phase.id} (${(System.currentTimeMillis() - phaseSince) / 1000}s); resetting")
                lastError = "previous ${phase.id} operation did not finish; state reset"
                last = LastResult(phase.id, false, lastError, System.currentTimeMillis())
                enterPhase(Phase.IDLE)
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * 暂存一个运行时包（本地 zip 路径或 http(s) URL）。
     *
     * @param source 本地绝对路径或 http(s) URL
     * @param sha256 期望的 SHA-256（可选；给了就强制校验）
     * @return null = 已受理（202，后台执行）；非空 = 拒绝原因（ASCII，直接回给调用方）
     *
     * 立即返回：解压 160MB 的包在手机上要几十秒，同步做会让口 HTTP 客户端超时
     * （`_dsh_http.sh` 默认 10s 读超时）。进度走 [status] 的 phase/progress。
     */
    fun stage(ctx: Context, source: String, sha256: String?, isUrl: Boolean): String? {
        val src = source.trim()
        if (src.isEmpty()) return "empty source (need a zip path or http(s) url)"
        if (isUrl) {
            if (!src.startsWith("http://", true) && !src.startsWith("https://", true)) {
                return "not an http(s) url"
            }
        } else {
            val f = File(src)
            if (!f.isFile) return "zip not found: $src"
            if (!f.canRead()) return "zip not readable: $src"
        }
        lock.withLock {
            if (phase != Phase.IDLE) return "busy: ${phase.id} in progress"
            enterPhase(Phase.STAGING)
            progress = 0f
            lastError = ""
        }
        val appCtx = ctx.applicationContext
        Thread({
            doStage(appCtx, src, sha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }, isUrl)
        }, "runtime-stage").apply { isDaemon = true; start() }
        return null
    }

    /** 应用已暂存的运行时（202 立即返回；停引擎 → 换目录 → 启动 → 健康检查 → 必要时自动回滚） */
    fun apply(ctx: Context): String? {
        val sup = supervisorOf(ctx) ?: return "engine supervisor unavailable (app not initialised)"
        val newD = newDir(ctx)
        if (!newD.isDirectory) return "nothing staged (run 'dsh-update stage <zip|path>' first)"
        val newVer = readVersion(newD) ?: return "staged runtime has no version marker"
        lock.withLock {
            if (phase != Phase.IDLE) return "busy: ${phase.id} in progress"
            enterPhase(Phase.APPLYING)
            progress = 0f
            lastError = ""
        }
        val appCtx = ctx.applicationContext
        Thread({ doApply(appCtx, sup, newVer) }, "runtime-apply").apply {
            isDaemon = true
            start()
        }
        return null
    }

    /** 回滚到 engine.old（当前运行时会被暂存成 engine.new，方便再次应用） */
    fun rollback(ctx: Context): String? {
        val sup = supervisorOf(ctx) ?: return "engine supervisor unavailable (app not initialised)"
        val old = oldDir(ctx)
        if (!old.isDirectory) return "no previous runtime to roll back to"
        val oldVer = readVersion(old) ?: return "previous runtime has no version marker"
        lock.withLock {
            if (phase != Phase.IDLE) return "busy: ${phase.id} in progress"
            enterPhase(Phase.ROLLING_BACK)
            progress = 0f
            lastError = ""
        }
        val appCtx = ctx.applicationContext
        Thread({ doRollback(appCtx, sup, oldVer) }, "runtime-rollback").apply {
            isDaemon = true
            start()
        }
        return null
    }

    /** 重启引擎（不动任何文件；桥/前台服务/无障碍都与引擎进程无关） */
    fun restart(ctx: Context): String? {
        val sup = supervisorOf(ctx) ?: return "engine supervisor unavailable (app not initialised)"
        // restart() 自身幂等且立即返回（内部串行 + 先置 Starting），不需要再套线程
        sup.restart()
        last = LastResult("restart", true, "restart requested", System.currentTimeMillis())
        lastError = ""
        return null
    }

    // ── 启动期钩子 ───────────────────────────────────────────────────────────

    /**
     * 启动时的中断恢复（由 [RuntimeInstaller.ensureInstalled] 在一切版本判断之前调用）。
     *
     * apply 是「停引擎 → 换目录 → 启动」三步，任何一步之间都可能被系统杀进程。
     * 这里按 .runtime-swap 注记把目录恢复成**可启动**状态，判定表：
     *
     * | live 版本 | 语义 | 处置 |
     * |---|---|---|
     * | 无/空 | 换目录没走完 | engine.old → engine（旧树最可靠） |
     * | == next 且 old 是 prev | 交换完成但健康检查没跑完 | **主动回滚**到 old（宁丢更新不赌） |
     * | 其它 | 交换没发生 / 已完成 | 保持现状，交给本次启动的健康检查 |
     *
     * 另外无条件清掉 engine.staging —— 半解压的树只在解压进程里有效，跨进程没有意义。
     */
    fun recoverIfNeeded(ctx: Context) {
        // 本进程里正有 stage/apply/rollback 在跑时不做恢复 —— ensureInstalled 会在
        // 监督循环的每一轮重跑（含 apply 触发的重启），此时 engine.staging 正被
        // 解压进程使用，绝不能在这里删。
        if (phase != Phase.IDLE) return
        runCatching {
            deleteTree(stagingDir(ctx))
            val note = swapNote(ctx)
            if (!note.isFile) return@runCatching
            val kv = readNote(note)
            val live = EngineConfig.engineRoot(ctx)
            val old = oldDir(ctx)
            val newD = newDir(ctx)
            val liveVer = readVersion(live)
            val prev = kv["prev"].orEmpty()
            val next = kv["next"].orEmpty()
            Log.w(
                TAG,
                "recovering interrupted ${kv["action"] ?: "swap"} (step=${kv["step"]}," +
                    " live=$liveVer, prev=$prev, next=$next)",
            )
            when {
                liveVer.isNullOrEmpty() && old.isDirectory -> {
                    deleteTree(live)
                    if (!old.renameTo(live)) Log.e(TAG, "recovery: engine.old -> engine failed")
                }
                liveVer != null && liveVer == next && prev.isNotEmpty() &&
                    old.isDirectory && readVersion(old) == prev -> {
                    // 交换完成但没有健康检查结论：回到已知可用的旧树。
                    // 为什么主动回滚而不是「先跑跑看」：新树若起不来，监督循环会一直退避重启，
                    // 用户看到的是「引擎坏了」，而能自愈的路径只有回滚。
                    deleteTree(newD)
                    if (live.renameTo(newD) && old.renameTo(live)) {
                        Log.w(TAG, "recovery: rolled back to $prev (unverified $next discarded to engine.new)")
                    } else {
                        Log.e(TAG, "recovery: rollback rename failed; leaving live tree as-is")
                    }
                }
                else -> Unit
            }
            note.delete()
        }.onFailure { Log.w(TAG, "recovery failed: ${it.message}") }
    }

    /**
     * 引擎健康后的钩子（EngineSupervisor 在置 Healthy/SafeMode 之后调用）。
     *
     * 只做一件事：回收上一版运行时目录。为什么要等「健康」而不是 apply 成功就删 ——
     * 回滚必须真的可用，engine.old 在「新引擎证明自己能跑」之前不能动。而 apply 自己
     * 那次健康检查不能当作「下一次启动」（否则刚成功就删掉回滚点），所以用
     * .runtime-reclaim 落到**下一次**健康启动再回收。
     */
    fun onEngineHealthy(ctx: Context) {
        runCatching {
            if (phase != Phase.IDLE) return@runCatching   // apply 自己的健康等待不算
            val flag = reclaimFlag(ctx)
            if (!flag.isFile) return@runCatching
            if (swapNote(ctx).isFile) return@runCatching
            // 年龄门：apply 的收尾（写 flag → 置 idle）与监督线程的 hook 存在极窄竞态，
            // 若 hook 恰好在 idle 之后才被调度，就会把「刚建立的」回滚点当场删掉。
            // 要求 flag 至少存在 2 分钟 ⇒ 一定是「上一次会话留下的」，语义上等于
            // 「下一次成功启动」；晚一轮启动再回收无任何损失。
            val age = System.currentTimeMillis() - flag.lastModified()
            if (age < RECLAIM_MIN_AGE_MS) return@runCatching
            val old = oldDir(ctx)
            if (old.isDirectory) {
                deleteTree(old)
                Log.i(TAG, "reclaimed previous runtime (engine.old)")
            }
            flag.delete()
        }.onFailure { Log.w(TAG, "reclaim failed: ${it.message}") }
    }

    // ── stage 实现 ───────────────────────────────────────────────────────────

    private fun doStage(ctx: Context, source: String, expectedSha: String?, isUrl: Boolean) {
        var downloaded: File? = null
        val staging = stagingDir(ctx)
        try {
            val zip: File = if (isUrl) {
                val f = File(ctx.cacheDir, "runtime-hot.zip")
                if (f.exists()) f.delete()
                download(source, f)
                downloaded = f
                f
            } else {
                File(source)
            }
            if (!zip.isFile) throw StageFailure("zip not found: ${zip.name}")
            val size = zip.length()
            // 1MB 以下不可能是运行时（最小的裁剪包也在几十 MB）
            if (size < (1L shl 20)) throw StageFailure("zip too small: $size bytes")

            val actualSha = RuntimeInstaller.sha256(zip)
            if (expectedSha != null && !actualSha.equals(expectedSha, ignoreCase = true)) {
                throw StageFailure("sha256 mismatch: expected=$expectedSha actual=$actualSha")
            }

            // 架构校验：拿错架构的包会在 apply 后 exec 失败（libz.so EM_AARCH64 那类事故），
            // 与其把引擎换成一个起不来的树再回滚，不如在这里直接拒绝。
            val zipArch = elfArchOf(zip, ANCHOR_NODE)
            val devArch = deviceArch()
            if (zipArch != null && devArch != null && zipArch != devArch) {
                throw StageFailure("abi mismatch: zip is $zipArch, device is $devArch")
            }

            val info = inspectZip(zip)
            if (!info.hasNode) throw StageFailure("zip missing $ANCHOR_NODE")
            if (!info.hasEntry) throw StageFailure("zip missing $ANCHOR_ENTRY")
            val need = info.uncompressedBytes + STAGE_MARGIN_BYTES
            val usable = ctx.filesDir.usableSpace
            if (usable > 0 && usable < need) {
                throw StageFailure("not enough space: need ${mb(need)}MB, free ${mb(usable)}MB")
            }
            Log.i(
                TAG,
                "staging ${zip.name} (${mb(size)}MB, ${mb(info.uncompressedBytes)}MB unpacked," +
                    " sha=${actualSha.take(12)}..., abi=${zipArch ?: "?"})",
            )

            // 2) 解压（解到 staging；绝不碰 engine/ 与 engine.new）
            deleteTree(staging)
            staging.mkdirs()
            progress = 0f
            RuntimeInstaller(ctx).extractZip(zip, staging) { p -> progress = p * 0.99f }

            // 3) 标记 + 完整性探针（探针失败就不发布，避免 apply 换上一个半截树）
            val version = versionOf(zip, actualSha)
            File(staging, VERSION_MARKER).writeText(version)
            File(staging, SOURCE_MARKER).writeText("external\n")
            writeMeta(
                staging,
                org.json.JSONObject()
                    .put("version", version)
                    .put("sha256", actualSha)
                    .put("source", if (isUrl) "url" else "path")
                    .put("origin", if (isUrl) source else zip.name)
                    .put("bytes", size)
                    .put("abi", zipArch ?: devArch.orEmpty())
                    .put("staged_at", System.currentTimeMillis()),
            )
            if (!RuntimeInstaller(ctx).probeRootComplete(staging)) {
                throw StageFailure("staged tree incomplete ($ANCHOR_NODE / $ANCHOR_ENTRY probe failed)")
            }

            // 4) 原子发布：staging → engine.new
            val newD = newDir(ctx)
            deleteTree(newD)
            if (!staging.renameTo(newD)) throw StageFailure("publish failed: engine.staging -> engine.new")
            progress = 1f
            last = LastResult("stage", true, "staged $version", System.currentTimeMillis())
            Log.i(TAG, "staged $version (${mb(size)}MB zip) into engine.new")
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            lastError = msg
            last = LastResult("stage", false, msg, System.currentTimeMillis())
            Log.w(TAG, "stage failed: $msg")
            runCatching { deleteTree(staging) }
            progress = 0f
        } finally {
            downloaded?.delete()
            lock.withLock { enterPhase(Phase.IDLE) }
        }
    }

    // ── apply 实现 ───────────────────────────────────────────────────────────

    private fun doApply(ctx: Context, sup: EngineSupervisor, newVer: String) {
        val live = EngineConfig.engineRoot(ctx)
        val old = oldDir(ctx)
        val newD = newDir(ctx)
        val prevVer = readVersion(live).orEmpty()
        val prevSource = readSource(live)
        var failure = ""
        // 失败路径的分级处置依据（见下方 catch 之后）：
        //   !stopped → 引擎还在跑，什么都别动
        //   stopped && !swapped → 引擎停了但目录没换，把它重新拉起来即可
        //   swapped → 必须换回旧树
        var stopped = false
        var swapped = false
        try {
            try {
                // 预写注记：先落盘「我要换目录了」，再动目录。崩在任何一步都能据此恢复。
                writeNote(
                    ctx,
                    linkedMapOf(
                        "action" to "apply",
                        "step" to "begin",
                        "prev" to prevVer,
                        "next" to newVer,
                        "prev_source" to prevSource,
                        "at" to System.currentTimeMillis().toString(),
                    ),
                )
                if (!RuntimeInstaller(ctx).probeRootComplete(newD)) {
                    throw StageFailure("staged tree incomplete; refusing to swap")
                }
                // ① 停引擎（bridge/前台服务/无障碍都在 App 进程里，不受影响）
                sup.stop(STOP_GRACE_MS)
                stopped = true
                awaitPortFree(EngineConfig.DEFAULT_PORT, 5_000)
                // ② 换目录：engine → engine.old，engine.new → engine
                if (old.exists()) deleteTree(old)   // 上上版：本版上线后它已无回滚价值，删掉腾空间
                if (live.exists() && !live.renameTo(old)) {
                    throw StageFailure("cannot archive live runtime (engine -> engine.old)")
                }
                if (!newD.renameTo(live)) {
                    // 尽力恢复原状，别把 App 留在「没有 engine」的状态
                    runCatching { if (!live.exists() && old.exists()) old.renameTo(live) }
                    throw StageFailure("cannot publish staged runtime (engine.new -> engine)")
                }
                swapped = true
                // 来源标记落在**活动树**上（stage 时已写进 engine.new，rename 之后就是
                // engine/.runtime-source；这里显式再写一次，不依赖 rename 的隐含结果）：
                // RuntimeInstaller 靠它判断「这棵树来自热更新，不要按 assets 版本重装」。
                runCatching { File(live, SOURCE_MARKER).writeText("external\n") }
                updateNote(ctx, "step" to "swapped")
                // ③ 启动 + 健康检查（复用监督器的 :3080 探测，不另造轮子）
                val scope = appScopeOf(ctx)
                    ?: throw StageFailure("app scope unavailable; cannot restart engine")
                sup.start(scope)
                val st = awaitEngineState(sup, HEALTH_WINDOW_MS)
                if (st == null) {
                    throw StageFailure("new runtime not healthy within ${HEALTH_WINDOW_MS / 1000}s")
                }
                // ④ 成功：清注记，登记「下次健康启动回收 engine.old」
                swapNote(ctx).delete()
                reclaimFlag(ctx).writeText(newVer)
                lastError = ""
                last = LastResult("apply", true, "healthy on $newVer", System.currentTimeMillis())
                Log.i(TAG, "apply: runtime $newVer is healthy (previous $prevVer kept in engine.old)")
                return
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "apply failed: $failure; attempting automatic rollback")
            }

            // 分级处置（关键：别为了「回滚」把一棵本来能跑的树搬走）：
            //   !stopped            → 引擎没被动过，直接报告失败即可
            //   stopped && !swapped → 目录没换，把原来的引擎重新拉起来
            //   swapped             → 真正走到了新树上，必须换回旧树
            val detail = runCatching {
                when {
                    !stopped -> {
                        swapNote(ctx).delete()
                        "apply failed ($failure); runtime untouched"
                    }
                    !swapped -> {
                        swapNote(ctx).delete()
                        val scope = appScopeOf(ctx)
                        if (scope != null) sup.start(scope)
                        "apply failed ($failure); engine restarted on $prevVer"
                    }
                    else -> {
                        val rollbackErr = swapBack(ctx, sup, "auto")
                        if (rollbackErr == null) {
                            "apply failed ($failure); rolled back to $prevVer"
                        } else {
                            "apply failed ($failure); rollback failed ($rollbackErr)"
                        }
                    }
                }
            }.getOrElse { "apply failed ($failure); rollback crashed (${it.message})" }
            lastError = detail
            last = LastResult("apply", false, detail, System.currentTimeMillis())
            Log.e(TAG, detail)
        } finally {
            // 成功/失败/异常都必须复位：phase 卡在 applying 会让后续 stage/apply 永远
            // 被当成 busy 拒绝（2026-10-18 模拟器实测踩到过：apply 成功、状态却是 applying）。
            lock.withLock { enterPhase(Phase.IDLE) }
        }
    }

    // ── rollback 实现 ────────────────────────────────────────────────────────

    private fun doRollback(ctx: Context, sup: EngineSupervisor, oldVer: String) {
        val live = EngineConfig.engineRoot(ctx)
        val currentVer = readVersion(live).orEmpty()
        try {
            // 注记同样要写：回滚也是「换目录 + 重启」，一样会被杀在半路
            writeNote(
                ctx,
                linkedMapOf(
                    "action" to "rollback",
                    "step" to "begin",
                    "prev" to currentVer,
                    "next" to oldVer,
                    "prev_source" to readSource(live),
                    "at" to System.currentTimeMillis().toString(),
                ),
            )
            val err = swapBack(ctx, sup, "manual")
            if (err == null) {
                // 成功：engine.old 已被消耗，engine.new 里是刚被换下来的版本（可再次 apply）。
                //
                // 关于 .runtime-source 标记：这里**不清**活动树上的标记，而是让恢复后的树
                // 保留它自己的来源。原因是回滚可能回到的是一棵**更早的热更新树**
                // （v3 → 回滚到 v2，v2 本身也是 external）：若盲目清标记，下一次启动
                // RuntimeInstaller 会按 assets 版本判定「版本不符」→ 静默再回退到出厂包，
                // 等于用户点了回滚却退了两版。真正需要清标记的是「旧树也起不来」的兜底
                // 路径（swapBack 里 clearExternalMarkers → 下一轮从 assets 重装）。
                reclaimFlag(ctx).delete()
                lastError = ""
                last = LastResult("rollback", true, "rolled back to $oldVer", System.currentTimeMillis())
                Log.i(TAG, "rollback: $currentVer -> $oldVer")
            } else {
                lastError = err
                last = LastResult("rollback", false, err, System.currentTimeMillis())
                Log.e(TAG, "rollback failed: $err")
            }
        } finally {
            lock.withLock { enterPhase(Phase.IDLE) }
        }
    }

    /**
     * 换回 engine.old 并重启（apply 自动回滚与手动 rollback 共用）。
     *
     * 约定：进入时 live 是当前树、engine.old 是上一版。当前树会被搬到 engine.new
     * （不是删掉 —— 回滚后还能再次 apply，省一次 160MB 上传/解压）。
     *
     * @return null = 回滚成功且引擎健康；非空 = 失败原因
     */
    private fun swapBack(ctx: Context, sup: EngineSupervisor, reason: String): String? {
        val live = EngineConfig.engineRoot(ctx)
        val old = oldDir(ctx)
        val newD = newDir(ctx)
        return try {
            sup.stop(STOP_GRACE_MS)
            awaitPortFree(EngineConfig.DEFAULT_PORT, 5_000)
            if (live.exists()) {
                deleteTree(newD)
                if (!live.renameTo(newD)) throw StageFailure("cannot archive failed runtime")
            }
            if (!old.isDirectory) {
                // 没有可回滚的旧树：把 external 标记清掉，让监督循环下一轮
                // 从 APK 内置 runtime 重装（ensureInstalled 每轮都会重跑）。
                clearExternalMarkers(live)
                throw StageFailure("no previous runtime available; asset reinstall scheduled")
            }
            if (!old.renameTo(live)) throw StageFailure("cannot restore previous runtime")
            updateNote(ctx, "step" to "restored")
            swapNote(ctx).delete()
            val scope = appScopeOf(ctx) ?: throw StageFailure("app scope unavailable")
            sup.start(scope)
            val st = awaitEngineState(sup, HEALTH_WINDOW_MS)
            if (st == null) {
                // 双失败：最后一招 —— 清标记 + 删版本戳，监督循环下一轮从 assets 重装。
                clearExternalMarkers(live)
                return "previous runtime also unhealthy; asset reinstall scheduled"
            }
            Log.i(TAG, "swapBack($reason): engine healthy on ${readVersion(live) ?: "?"}")
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    private class StageFailure(message: String) : Exception(message)

    private fun supervisorOf(ctx: Context): EngineSupervisor? =
        runCatching { (ctx.applicationContext as? DshApp)?.supervisor }.getOrNull()

    private fun appScopeOf(ctx: Context): kotlinx.coroutines.CoroutineScope? =
        runCatching { (ctx.applicationContext as? DshApp)?.appScope }.getOrNull()

    private fun engineLabel(sup: EngineSupervisor?): String = when (sup?.state?.value) {
        is EngineSupervisor.State.Healthy -> "healthy"
        is EngineSupervisor.State.SafeMode -> "safe-mode"
        is EngineSupervisor.State.Installing -> "installing"
        is EngineSupervisor.State.Starting -> "starting"
        is EngineSupervisor.State.Backoff -> "backoff"
        is EngineSupervisor.State.Failed -> "failed"
        is EngineSupervisor.State.Stopped -> "stopped"
        EngineSupervisor.State.Idle -> "idle"
        null -> "unavailable"
    }

    /** 等引擎进入 Healthy/SafeMode；Failed 立即失败；其余状态（Starting/Installing/Backoff）继续等 */
    private fun awaitEngineState(sup: EngineSupervisor, timeoutMs: Long): EngineSupervisor.State? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (val s = sup.state.value) {
                is EngineSupervisor.State.Healthy -> return s
                is EngineSupervisor.State.SafeMode -> return s
                is EngineSupervisor.State.Failed -> return null
                else -> Thread.sleep(250)
            }
        }
        return null
    }

    /** 等端口释放（换目录前确认旧进程真的没了；超时也继续，spawn 前还有孤儿清理兜底） */
    private fun awaitPortFree(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val inUse = runCatching {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", port), 250)
                    true
                }
            }.getOrDefault(false)
            if (!inUse) return
            Thread.sleep(150)
        }
        Log.w(TAG, "port $port still busy after ${timeoutMs}ms; continuing (spawn reaps orphans)")
    }

    fun readVersion(dir: File): String? = runCatching {
        val f = File(dir, VERSION_MARKER)
        if (f.isFile) f.readText().trim().takeIf { it.isNotEmpty() } else null
    }.getOrNull()

    /** assets / external / unknown */
    fun readSource(dir: File): String = runCatching {
        val f = File(dir, SOURCE_MARKER)
        when {
            f.isFile && f.readText().trim() == "external" -> "external"
            File(dir, VERSION_MARKER).isFile -> "assets"
            else -> "unknown"
        }
    }.getOrNull() ?: "unknown"

    private fun readMeta(dir: File): org.json.JSONObject? = runCatching {
        val f = File(dir, META_MARKER)
        if (f.isFile) org.json.JSONObject(f.readText()) else null
    }.getOrNull()

    private fun writeMeta(dir: File, obj: org.json.JSONObject) {
        runCatching { File(dir, META_MARKER).writeText(obj.toString()) }
    }

    /**
     * 清掉 external 来源标记 + 版本戳：让 RuntimeInstaller 按 APK 内置 runtime 重装。
     * 这是「新旧两版都起不来」时的最后防线 —— 宁可回退到出厂运行时，也不留空窗。
     */
    private fun clearExternalMarkers(live: File) {
        runCatching { File(live, SOURCE_MARKER).delete() }
        runCatching { File(live, VERSION_MARKER).delete() }
        Log.w(TAG, "cleared external source marker; next start will reinstall from APK assets")
    }

    private fun writeNote(ctx: Context, kv: Map<String, String>) {
        runCatching {
            swapNote(ctx).writeText(kv.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        }
    }

    private fun updateNote(ctx: Context, vararg pairs: Pair<String, String>) {
        runCatching {
            val cur = readNote(swapNote(ctx)).toMutableMap()
            pairs.forEach { (k, v) -> cur[k] = v }
            writeNote(ctx, cur)
        }
    }

    private fun readNote(f: File): Map<String, String> = runCatching {
        if (!f.isFile) emptyMap()
        else f.readLines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
    }.getOrDefault(emptyMap())

    private data class ZipInfo(val uncompressedBytes: Long, val hasNode: Boolean, val hasEntry: Boolean)

    /** 只扫 central directory：不解压就能拿到总字节数与两个锚点条目 */
    private fun inspectZip(zip: File): ZipInfo {
        var total = 0L
        var node = false
        var entry = false
        java.util.zip.ZipFile(zip).use { zf ->
            val it = zf.entries()
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                total += e.size.coerceAtLeast(0)
                when (e.name) {
                    ANCHOR_NODE -> node = true
                    ANCHOR_ENTRY -> entry = true
                }
            }
        }
        return ZipInfo(total, node, entry)
    }

    /** 版本标签：runtime.zip 里没有版本文件，用 dsh 包版本 + 包 SHA 前 8 位做唯一标识 */
    private fun versionOf(zip: File, sha: String): String {
        val pkgVersion = runCatching {
            java.util.zip.ZipFile(zip).use { zf ->
                val e = zf.getEntry(DSH_PACKAGE_JSON) ?: return@use null
                val text = zf.getInputStream(e).bufferedReader().use { it.readText() }
                org.json.JSONObject(text).optString("version").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
        return (pkgVersion ?: "zip") + "+" + sha.take(8)
    }

    /**
     * 读 zip 里 bin/node 的 ELF 头判断架构。
     * @return "x86_64" / "arm64" / "arm" / "x86"，非 ELF 或读不了返回 null
     */
    private fun elfArchOf(zip: File, entryName: String): String? = runCatching {
        java.util.zip.ZipFile(zip).use { zf ->
            val e = zf.getEntry(entryName) ?: return@use null
            if (e.size < 20) return@use null
            val head = ByteArray(20)
            val n = zf.getInputStream(e).use { it.read(head) }
            if (n < 20) return@use null
            val elf = head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
            if (!elf) return@use null
            val little = head[5].toInt() == 1
            val machine = if (little) {
                (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
            } else {
                (head[19].toInt() and 0xFF) or ((head[18].toInt() and 0xFF) shl 8)
            }
            when (machine) {
                0x3E -> "x86_64"
                0xB7 -> "arm64"
                0x28 -> "arm"
                0x03 -> "x86"
                else -> null
            }
        }
    }.getOrNull()

    /** 本机主 ABI（与 zip 侧命名对齐：x86_64 / arm64 / arm / x86） */
    private fun deviceArch(): String? {
        val abi = runCatching { Build.SUPPORTED_ABIS.firstOrNull() }.getOrNull() ?: return null
        return when (abi) {
            "x86_64" -> "x86_64"
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "arm"
            "x86" -> "x86"
            else -> null
        }
    }

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 180_000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw StageFailure("download failed: HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        // 下载也报进度（整体进度的前 1/3；解压再接管到 1.0）
                        if (total > 0) progress = (done.toDouble() / total).toFloat() * 0.3f
                    }
                }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun deleteTree(f: File) {
        runCatching { if (f.exists()) f.deleteRecursively() }
    }

    private fun mb(v: Long): Long = v / (1L shl 20)

    private fun oneLine(s: String): String =
        s.replace('\n', ' ').replace('\r', ' ').trim()
}
