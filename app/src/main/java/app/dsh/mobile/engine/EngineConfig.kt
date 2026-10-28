package app.dsh.mobile.engine

import android.util.Log
import java.io.File
import java.nio.file.Files

/**
 * 引擎静态配置与目录拓扑。
 *
 * 目录约定（全部位于 app 私有存储内，targetSdk 28 下可执行）：
 *   filesDir/
 *     engine/          运行时根（node + node_modules，升级时整体替换）
 *       bin/node       bionic 编译的 Node 二进制（Termux 构建产物）
 *       lib/           动态库（libuv/openssl 等）
 *     dsh-home/        $DSH_HOME（凭证、会话、配置）
 *     workspaces/      默认工作区根
 *     tmp/             TMPDIR
 *
 * 升级引擎时绝不能触碰 dsh-home 与 workspaces —— 会话数据是用户资产。
 */
object EngineConfig {

    const val DEFAULT_PORT = 3080
    const val HEALTH_TIMEOUT_MS = 45_000L
    const val HEALTH_INTERVAL_MS = 500L

    /** web listen 后的稳定观察窗：挺过此窗才判定 Healthy 并快照 last-good */
    const val STABLE_WINDOW_MS = 3_000L

    /** 崩溃退避序列（毫秒），连续健康后重置 */
    val BACKOFF_STEPS = longArrayOf(2_000, 4_000, 8_000, 16_000, 32_000)
    // 必须 > guardian 两阶段所需（5 回滚 + 5 归档 = 10 次连续真死），
    // 否则第 8 次就进 Failed 停机、安全模式在数学上永远无法触发（实测事故）
    const val MAX_RESTART = 14

    /** 运行时前缀目录名（Termux 标准是 `usr`；改包名后必须与 Termux 对齐） */
    const val PREFIX_DIR = "usr"

    /**
     * 端口冲突自动清场的最大连续次数（v1.3.1）。
     *
     * 端口被**我们的**孤儿引擎占用时，清场后即可恢复，通常一次足够；但若端口被
     * 完全无关的进程（用户自己跑的、别的 app 的）永久占用，清场永远不会成功 ——
     * 那时必须放行走正常退避与 State.Failed，让用户看到明确错误，
     * 而不是绕过 MAX_RESTART 无声地无限重试。
     */
    const val MAX_PORT_RECLAIMS = 8

    fun engineRoot(ctx: android.content.Context): File =
        File(ctx.filesDir, EngineConfig.PREFIX_DIR).apply { mkdirs() }

    fun nodeBin(ctx: android.content.Context): File =
        File(engineRoot(ctx), "bin/node")

    fun dshEntry(ctx: android.content.Context): File =
        File(engineRoot(ctx), "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")

    /**
     * 用户数据根（v1.2.99）：**放在公开目录**，卸载重装不丢。
     *
     * ## 为什么外置
     * 原先 dsh-home / workspaces 都在 `filesDir`（应用私有）——**卸载即删**。
     * 用户重装、换机、清数据都会丢掉全部会话与配置，这是最痛的体验问题
     * （同类项目 DSHA 的卖点之一就是「卸载重装数据不丢」）。
     *
     * ## 做法
     * 真实数据放外部存储的 `Android/data/<pkg>/files/dshdata`（文件管理器可见、可备份），
     * 私有目录留**符号链接**指向它 —— 引擎代码与既有路径完全不用改。
     *
     * ## 兼容
     * - 已有私有目录数据：首次调用时**迁移**到公开目录（先复制校验再删旧）
     * - 无法创建（如未授予存储权限、Documents 不可写）：**静默回退**私有目录，
     *   功能不受影响（只是卸载会丢），并在日志说明
     */
    /**
     * 目录是否**真正可用**（写 + 读回 + 列目录三步，v1.2.101）。
     *
     * ## 为什么不能只看 canWrite()
     * Android 11+ 的 FUSE（scoped storage）下，`canWrite()` 对某些路径返回 true，
     * 而实际 `listFiles()` / 读取会被拒绝 —— 实测（模拟器）：
     *   `canWrite()=true` 但 `ls /storage/emulated/0/Android/data/<pkg>/files/` → Permission denied
     * 只看 canWrite 就采用该目录，会造成「报告成功、实际读不到」的静默失败
     * （主人实测：悬浮条永远空白且无任何错误日志）。
     */
    private fun isDirUsable(dir: File): Boolean = runCatching {
        val probe = File(dir, ".dsh-probe")
        probe.writeText("ok")
        val readBack = probe.readText() == "ok"
        probe.delete()
        // 列目录也要能过（有些路径允许创建文件但拒绝枚举）
        readBack && dir.list() != null
    }.getOrDefault(false)

    private fun publicDataRoot(ctx: android.content.Context): File? = runCatching {
        // ⚠️ v1.2.110：**Documents 升为首选**（用户反馈：卸载重装后对话无法恢复）。
        // 旧首选 `Android/data/<pkg>/files` 在**卸载时被系统清除**：覆盖升级数据在，
        // 卸载重装全丢 —— 已有人实际遇到。`Documents/` 是共享存储，卸载后保留。
        // 代价是需要 MANAGE_EXTERNAL_STORAGE（设置页「权限中心」可授）；未授权时
        // 探针失败自动回退 app external（至少保证覆盖升级不丢数据）。
        //
        // 探针为什么必须用「写入→读回→列目录」三步（isDirUsable）而不是 canWrite：
        // Android 11+ 的 FUSE（scoped storage）下 canWrite() 可能返回 true 而实际
        // list()/读取被拒（文件属 media_rw 组、mode 660）—— 历史踩坑两次：
        // 软链建了、App 却读不到，会话文件找不到、悬浮条空白，且无任何错误日志。
        val docs = File(android.os.Environment.getExternalStorageDirectory(), "Documents/dshdata")
        // 诊断（v1.2.110）：记录存储管理权限的真实状态与探针结果，便于确认
        // 「用户已授权却仍不可用」是权限没生效还是探针误判。
        val manager = android.os.Build.VERSION.SDK_INT >= 30 &&
            android.os.Environment.isExternalStorageManager()
        val docsProbe = runCatching {
            (docs.exists() || docs.mkdirs()) && isDirUsable(docs)
        }.getOrDefault(false)
        Log.i(TAG, "publicDataRoot probe: docs=$docsProbe storageManager=$manager mkdirs=${runCatching { docs.mkdirs() }.getOrDefault(false)}")
        if (docsProbe) {
            Log.i(TAG, "publicDataRoot: ${docs.absolutePath} (Documents, survives uninstall)")
            return@runCatching docs
        }
        Log.w(TAG, "publicDataRoot: Documents unusable (grant All Files Access in Settings; storageManager=$manager), trying app external")

        // 回退：App 专属外部目录（零权限，但卸载清除）
        val ext = ctx.getExternalFilesDir(null)
        if (ext != null) {
            val root = File(ext, "dshdata")
            if ((root.exists() || root.mkdirs()) && isDirUsable(root)) {
                Log.i(TAG, "publicDataRoot: ${root.absolutePath} (app external, cleared on uninstall)")
                return@runCatching root
            }
            Log.w(TAG, "publicDataRoot: app external not readable (FUSE?)")
        }
        null
    }.getOrElse {
        Log.w(TAG, "publicDataRoot failed: ${it.message}")
        null
    }

    /**
     * 用户数据外置（v1.2.99）—— **选择性外置**，不是整个 DSH_HOME。
     *
     * ## 为什么不整体外置（实测踩坑两次）
     * 1. 最初用「私有目录留软链指向公开位置」→ 引擎起不来：dsh 的插件配置走
     *    `file://` URL 做 ESM 解析，**不跟随符号链接**，模块 id 对不上。
     * 2. 改成「DSH_HOME 直接指向公开目录」→ 仍然起不来：
     *
     *        credentials-local: .../.credentials.yaml is readable beyond its owner
     *        (mode 664); run "chmod 600 ..." before starting again
     *        at assertOwnerOnly (dsh-credentials-local/lib/index.js:104)
     *
     *    上游 `assertOwnerOnly` 强制凭证文件必须 0600（`mode & GROUP_OTHER_BITS == 0`），
     *    而 `Documents/` 是共享存储（sdcardfs/FUSE），**给不出 Unix 权限位** —— 必然失败。
     *
     * ## 现在的分工
     * | 内容 | 位置 | 理由 |
     * |---|---|---|
     * | `dsh-home/sessions/`（对话） | **公开** | 用户最在意、最需要备份/迁移 |
     * | `dsh-home/workspaces/`（工作区） | **公开** | 用户文件，文件管理器要能看 |
     * | `dsh-home/profiles/`（凭证+插件配置） | **私有** | 上游强制 0600，共享存储给不了 |
     *
     * DSH_HOME 仍在私有目录，只把内部的 sessions / workspaces 用**软链**指到公开位置。
     * 子目录软链不影响 `file://` 解析（只有 profiles 那棵树参与 ESM 解析，它原地不动）。
     */
    private fun externaliseSubdir(ctx: android.content.Context, name: String): Boolean {
        val priv = File(ctx.filesDir, "dsh-home/$name")
        val pubRoot = publicDataRoot(ctx) ?: run {
            // 无公开目录可用（未授权等）→ 若此前留过指向公开位置的软链，
            // 必须解开并恢复真实目录，否则引擎读到的是「权限拒绝」而非数据。
            runCatching {
                if (Files.isSymbolicLink(priv.toPath())) {
                    Log.w(TAG, "$name: removing stale symlink (no public storage access)")
                    priv.delete()
                }
            }
            priv.mkdirs()
            return false
        }
        val pub = File(pubRoot, name)
        var isLink = runCatching { Files.isSymbolicLink(priv.toPath()) }.getOrDefault(false)
        // 诊断（v1.2.111）：迁移分支的选择依据一次打全，避免「sessions=false 无日志」再出现
        Log.i(
            TAG,
            "externalise $name: pub=${pub.absolutePath} link=$isLink privExists=${priv.exists()} privFiles=${runCatching { priv.list()?.size }.getOrNull()}",
        )

        // ⚠️ v1.2.110：pubRoot 变更（app external → Documents）后，软链还指向旧位置、
        // 数据也还躺在旧位置 —— 必须「重指向 + 搬数据」两步都做，否则会话继续写旧
        // 位置，卸载重装依然全丢（用户反馈「删了应用重新下回来对话无法恢复」）。
        // ① 重指向：软链目标与当前 pub 不一致 → 删旧软链重建（数据在目标目录里，删链不删数据）
        if (isLink) {
            val target = runCatching { Files.readSymbolicLink(priv.toPath()).toString() }.getOrNull()
            if (target != pub.absolutePath) {
                Log.i(TAG, "$name: relink $target -> ${pub.absolutePath}")
                priv.delete()
                isLink = false
            }
        }
        // ② 历史数据迁移：旧首选位置（app external）还有数据 → **逐文件合并**到新位置。
        //    ⚠️ 不能用 copyRecursively(overwrite=false)：目标已有同名文件时会**抛异常**中断。
        //    逐文件判断「目标不存在才补」，既保留目标里已有的（假定更新），又不丢源里独有的
        //    （实测场景：Documents 有 10-08 的旧会话、app external 有 10-09 的新会话，双份并存，
        //    合并后两者都在）。搬完核对「目标文件数 >= 源文件数」才删源；不满足保留源（不删）。
        val legacyRoot = legacyPublicRoot(ctx)
        if (legacyRoot != null && pubRoot.absolutePath != legacyRoot.absolutePath) {
            val legacy = File(legacyRoot, name)
            if (legacy.exists() && legacy.list()?.isNotEmpty() == true) {
                runCatching {
                    var copied = 0
                    legacy.walkTopDown().forEach { f ->
                        if (f.isFile) {
                            val dst = File(pub, f.relativeTo(legacy).path)
                            if (!dst.exists()) {
                                dst.parentFile?.mkdirs()
                                f.copyTo(dst)
                                copied++
                            }
                        }
                    }
                    val missing = legacy.walkTopDown().any { f ->
                        f.isFile && !File(pub, f.relativeTo(legacy).path).isFile
                    }
                    if (!missing) {
                        legacy.deleteRecursively()
                        Log.i(TAG, "migrated legacy $name -> ${pub.absolutePath} ($copied files)")
                    } else {
                        Log.w(TAG, "legacy migration of $name incomplete; keeping legacy copy")
                    }
                }.onFailure { Log.w(TAG, "legacy migration of $name failed: ${it.message}") }
            }
        }

        // ⚠️ v1.2.159 修复「对话丢失」疑根：链接前做**真实读写探测**。
        // 日志里反复出现 `session-persistence-jsonl: EACCES scandir .../sessions`——
        // 指向外部存储（Documents/dshdata）的软链存在，但**引擎进程实际列目录被拒**
        // （Android 13+ 分区存储 / run-as 受限挂载等场景下 File 操作看似可用、真读写失败）
        // → 会话根本没持久化 → 用户重进应用对话全部丢失。
        // 探测失败 → 回退内部存储（不建软链、拆旧链），数据至少写在本机私有区。
        val pubWritable: Boolean = runCatching {
            // ⚠️ v1.2.159 真机取证（PLZ110）：以 app uid `ls <dir>` 报 Permission denied，
            // 但 **Java `File.list()` 在 FUSE 上对这种目录返回空数组而不是 null**（老坑），
            // 所以"list() != null"永远为真、探测形同虚设。
            // 改为**写入 + 列名字 + 读回**三步，用真实文件名验证（不依赖 null 语义）。
            val marker = ".dsh-probe-" + System.nanoTime()
            val pf = File(pub, marker)
            pf.writeText("ok")
            val names = pub.list()?.toList().orEmpty()
            val seen = names.contains(marker)
            val readBack = pf.isFile && pf.readText() == "ok"
            pf.delete()
            val ok = seen && readBack
            ok
        }.getOrDefault(false)
        if (!pubWritable) {
            // v1.2.159 实测取证（真机 PLZ110）：目录 drwxrws--- u0_a299 media_rw ——
            // **重装 app 后 uid 变了（安装 uid 会变），旧目录 770 直接拒绝新 uid**，
            // 于是引擎 scandir EACCES、会话永不落盘、重进应用对话全丢。
            // 先尝试**接管**：su 改属主/权限 + 清掉别的 uid 留下的不可读文件；
            // 不行再回退内部存储（数据至少留在本机私有区）。
            val q = "" + 34.toChar() + ""
            val suCmd = "chown -R " + android.os.Process.myUid() + ":" + android.os.Process.myUid() +
                " " + q + "$pub" + q +
                " 2>/dev/null; chmod -R u+rwX " + q + "$pub" + q +
                " 2>/dev/null; echo ok"
            val fixed = runCatching { Privilege.runSu(suCmd)?.contains("ok") == true }
                .getOrDefault(false)
            val retried = fixed && runCatching {
                val marker2 = ".dsh-probe-" + System.nanoTime()
                val pf2 = File(pub, marker2)
                pf2.writeText("ok")
                val ok = (pub.list()?.toList().orEmpty().contains(marker2)) &&
                    pf2.isFile && pf2.readText() == "ok"
                pf2.delete()
                ok
            }.getOrDefault(false)
            if (retried) {
                Log.w(TAG, "$name: took ownership of $pub (uid changed after reinstall) — external storage OK now")
            } else {
                Log.w(TAG, "$name: public storage NOT writable ($pub) — keeping data internal (no symlink)")
                runCatching { if (Files.isSymbolicLink(priv.toPath())) priv.delete() }
                priv.mkdirs()
                return false
            }
        }

        // 已就绪：软链在、目标在
        if (isLink && pub.exists()) return true

        // ---- 迁移：把私有目录的真实数据搬到公开位置 ----
        //
        // ⚠️ v1.2.100 二次修复（独立审查发现的数据丢失风险）：
        // 旧实现的条件是 `!pub.exists()`，且删 priv 前**不核对内容**。两条丢数据路径：
        //  ① 上次迁移中途失败（ENOSPC/IO）→ pub 残留部分数据、priv 仍是全量。
        //     下次启动：pub.exists() 为真 → 跳过迁移 → 直接删 priv → **全量数据没了**。
        //  ② 外置不可用期间回退到私有目录写入了新会话；存储恢复后 pub（旧数据）已存在
        //     → 跳过迁移 → 删 priv → **新会话连同删除**。
        // 现在：迁移前先合并、迁移后**核对条目数一致才删**，任何不一致都保留私有副本。
        val privHasData = !isLink && priv.exists() && priv.list()?.isNotEmpty() == true
        Log.i(TAG, "externalise $name: privHasData=$privHasData (list=${runCatching { priv.list()?.joinToString() }.getOrNull()})")
        if (privHasData) {
            runCatching {
                // ⚠️ v1.2.111：**逐文件合并**取代 copyRecursively(overwrite=false)。
                // 实测踩坑：pub 里已存在同名文件时 copyRecursively 抛
                // FileAlreadyExistsException 中断整次迁移 → sessions 整个不外置，
                // 数据留在私有目录（sessions=false）→ 卸载重装即丢。
                // 逐文件「目标不存在才补」：任何场景都能推进，pub 已有的保留（假定更新），
                // 缺的补上；最后按文件数核对，不满足才保留私有副本。
                pub.mkdirs()
                var copied = 0
                priv.walkTopDown().forEach { f ->
                    if (f.isFile) {
                        val dst = File(pub, f.relativeTo(priv).path)
                        if (!dst.exists()) {
                            dst.parentFile?.mkdirs()
                            // ⚠️ 不能用 overwrite=true：Documents/FUSE 上「覆盖」= 先删目标，
                            // 而删除非本 app 创建的文件会失败 —— 实测抛
                            // FileAlreadyExistsException: Tried to overwrite the destination,
                            // but failed to delete it（迁移整体中断，sessions=false）。
                            // 已存在的视为已迁移：半途残留的文件由「缺的补上」补齐，
                            // 内容完整的不会被碰；pub 独有的文件（多外置过一次）保留。
                            f.copyTo(dst)
                            copied++
                        }
                    }
                }
                // 核对**按相对路径**（不能用文件总数：pub 原本自带文件会让总数恒 >= 源，
                // 掩盖漏拷；反过来 pub 文件多时又误判失败 —— 实测 `50 < 51` 就是这个）。
                // 正确的语义是：priv 的每个文件都必须落到 pub 对应的位置。
                val missing = priv.walkTopDown().any { f ->
                    f.isFile && !File(pub, f.relativeTo(priv).path).isFile
                }
                if (missing) {
                    // 复制不全（磁盘满/权限）→ 保留私有目录，本次不外置
                    Log.w(TAG, "migration of $name incomplete; keeping private copy")
                    return false
                }
                priv.deleteRecursively()
                Log.i(TAG, "migrated dsh-home/$name -> ${pub.absolutePath} ($copied files)")
            }.onFailure {
                Log.w(TAG, "migration of dsh-home/$name failed: ${it.message}", it)
                return false
            }
        }

        pub.mkdirs()
        return runCatching {
            // 只有确认 pub 可用、且私有目录已无未迁移数据时，才删私有目录并建链
            if (priv.exists() && !runCatching { Files.isSymbolicLink(priv.toPath()) }.getOrDefault(false)) {
                if (priv.list()?.isNotEmpty() == true) {
                    // 走到这里说明上面没搬（如 priv 为空但 pub 也刚建）——再核一次
                    val src = priv.walkTopDown().count()
                    val dst = pub.walkTopDown().count()
                    if (dst < src) {
                        Log.w(TAG, "$name: private dir still holds data ($src > $dst); not linking")
                        return false
                    }
                }
                priv.deleteRecursively()
            }
            if (!priv.exists()) Files.createSymbolicLink(priv.toPath(), pub.toPath())
            Log.i(TAG, "dsh-home/$name -> ${pub.absolutePath} (symlink)")
            true
        }.getOrElse {
            Log.w(TAG, "symlink for dsh-home/$name failed: ${it.message}; using private")
            false
        }
    }

    /**
     * v1.2.110：旧首选外置位置（app external，卸载即被系统清除）。
     * 仅用于升级迁移检测 —— pubRoot 从它改为 Documents 后，把残留数据搬过去。
     */
    private fun legacyPublicRoot(ctx: android.content.Context): File? = runCatching {
        val ext = ctx.getExternalFilesDir(null) ?: return null
        val root = File(ext, "dshdata")
        if (root.isDirectory) root else null
    }.getOrNull()

    /**
     * 确保 sessions / workspaces 已外置（引擎启动前调用；幂等，可重复调用）。
     * profiles 保持私有 —— 见 [externaliseSubdir] 的说明。
     */
    fun ensureUserDataExternal(ctx: android.content.Context) {
        runCatching {
            File(ctx.filesDir, "dsh-home").mkdirs()
            val a = externaliseSubdir(ctx, "sessions")
            val b = externaliseSubdir(ctx, "workspaces")
            // v2.3.0：工作区/回收站加 .nomedia，避免内部产物被系统相册扫描
            MediaHygiene.ensureAll(ctx)
            Log.i(TAG, "user data external: sessions=$a workspaces=$b")
        }.onFailure { Log.w(TAG, "externalise failed: ${it.message}") }
    }

    /** $DSH_HOME（凭证、会话、配置）—— 保持私有；sessions/ 由 ensureUserDataExternal 外置 */
    fun dshHome(ctx: android.content.Context): File =
        File(ctx.filesDir, "dsh-home").apply { mkdirs() }

    /** 默认工作区根（$DSH_HOME/workspaces） */
    fun workspaces(ctx: android.content.Context): File =
        File(dshHome(ctx), "workspaces").apply { mkdirs() }

    /**
     * 用户数据在公开目录中的位置；null = 落在应用私有目录（卸载会删除）。
     * 文案由 UI 层按语言给出，此处只回路径。
     */
    fun userDataLocation(ctx: android.content.Context): String? =
        publicDataRoot(ctx)?.absolutePath

    fun tmpDir(ctx: android.content.Context): File =
        File(engineRoot(ctx), "tmp").apply { mkdirs() }

    /**
     * Android 适配覆盖层（v1.2.36）：写成 YAML patch 文件，由启动参数 `--patch` 注入。
     *
     * 为什么必须覆盖沙箱模式：dsh 的沙箱后端是 Linux landlock / macOS seatbelt，
     * **Android 上两者都不存在** → sandbox-policy 默认 `read-only`：
     *   - read-only/workspace-write 下 bash 工具直接拒绝执行（模拟器实测原文：
     *     `sandbox mode "workspace-write" ... refusing to run the command unconfined;
     *      no sandbox backend usable on host`）→ AI 一条命令都跑不了；
     * 本项目的安全边界是 App 自身的权限模式（Normal 沙箱 / Shizuku ADB 级 / Root）
     * 与 su 闸门（applySuGate），而非引擎内部沙箱，故置 danger-full-access。
     * 用户在 WebUI 仍可逐会话选择更严格模式。
     *
     * 只覆盖 id 精确匹配的插件；上游升级若改 id，未知条目会被忽略（不阻断启动）。
     */
    /**
     * 运行期修复 engine/bin 里指向 Termux 的 wrapper shebang（v1.2.159）。
     *
     * 背景（真机 AI 反馈实测）：`getprop` 一跑就崩 `bad interpreter` —— 设备上 **37 个**
     * wrapper 的 shebang 仍是 `#!/data/data/com.termux/files/usr/bin/sh`，该路径在本机不存在。
     * 打包脚本 `collect-termux-runtime.sh` 里早有"归一为 /system/bin/sh"的一段，但它只
     * **构建期**生效；老 runtime（或扩展解出的 runtime）里仍带着旧 shebang，运行期无人兜底。
     * 这里做幂等自愈：只改第一行 shebang，其余内容一字不动（那些 shim 遮蔽系统命令是
     * **有意的**，见打包脚本注释，不能删）。
     */
    fun healTermuxShebangs(ctx: android.content.Context) {
        runCatching {
            val bin = File(engineRoot(ctx), "bin")
            if (!bin.isDirectory) return
            var fixed = 0
            bin.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val head = f.inputStream().use { ins ->
                    val buf = ByteArray(2); if (ins.read(buf) == 2) String(buf) else ""
                }
                if (head != "#!") return@forEach
                val first = f.bufferedReader().use { it.readLine() } ?: return@forEach
                if (!first.startsWith("#!")) return@forEach
                // 2026-10-18 包名切换为 com.termux 后，Termux 前缀**真实存在**：
                // 这些 shebang 是合法的（pkg/apt/codex 等脚本还要靠 bash 的 `=~`）。
                // 只有当解释器路径确实不存在时（旧的 app.dsh.mobile 前缀等）才降级到
                // /system/bin/sh —— 否则会把 bash 脚本改成 toybox sh，语法直接报错。
                val interp = first.removePrefix("#!").trim().substringBefore(' ')
                if (interp.startsWith("/") && File(interp).exists()) return@forEach
                runCatching {
                    val rest = f.readLines().drop(1)
                    f.writeText("#!/system/bin/sh\n" + rest.joinToString("\n") + "\n")
                    f.setExecutable(true, false)
                    fixed++
                }
            }
            if (fixed > 0) Log.i(TAG, "healed $fixed Termux shebang(s) in engine/bin")
        }.onFailure { Log.w(TAG, "shebang heal failed: ${it.message}") }
    }

    fun ensureAndroidOverlay(ctx: android.content.Context): File {
        val f = File(engineRoot(ctx), "android-overlay.yml")
        // NOTE: keep this file free of CJK text — the i18n gate scans Kotlin string
        // literals and would flag it (explanations live in the Kotlin comments below).
        //
        // Why all three rows are pinned here instead of relying on DSH_PERMISSION_MODE
        // alone: permission-presets derives its default from the *composed* pair
        // (sandbox mode + approval policy) and throws "composed sandbox and approval
        // defaults match no preset" when the pair names no table entry. A partially
        // applied permission stack (e.g. sandbox resolved but approval left at the
        // schema default 'ask') yields exactly {danger-full-access, ask} — which is
        // not in the table — and the entry then fails to activate on every boot.
        // Pinning the trio makes the composed pair match by construction.
        //
        // ⚠️ A patch REPLACES the targeted row's whole `config` (applyEntryPatches
        // does `target[key] = value`), so each row below restates every key it owns:
        //   · permission needs the full presets table — the plugin's own schema
        //     default only carries workspace-write + danger-full-access, and losing
        //     `read-only` would remove a preset the WebUI offers.
        //   · sandbox-policy needs workspaceRoot (schema marks it required).
        val body = """
            |# [dsh-android] Android compatibility overlay (auto-generated, do not edit)
            |# Sandbox backends (landlock/seatbelt) do not exist on Android, and any
            |# confined mode would make the AI's shell tool refuse to run every command
            |# ("no sandbox backend usable on host"). The security boundary is the app's
            |# permission mode (Normal/Shizuku/Root) plus the su gate, not an engine-side
            |# sandbox. The user can still pick a stricter per-session preset in the WebUI.
            |#
            |# DSH_PERMISSION_MODE=danger-full-access is also exported (see buildEnv) so the
            |# upstream expressions agree with this layer; these rows are the authority.
            |- id: sandbox-policy
            |  config:
            |    mode: danger-full-access
            |    workspaceRoot: !!js process.cwd()
            |
            |- id: approval
            |  config:
            |    policy: never
            |
            |- id: permission
            |  config:
            |    defaultPreset: danger-full-access
            |    presets:
            |      read-only:
            |        sandbox: read-only
            |        approval: ask
            |        name: read-only
            |        description: Read-only file access; every write requires approval.
            |      workspace-write:
            |        sandbox: workspace-write
            |        approval: ask
            |        name: workspace-write
            |        description: Write inside the workspace and permitted temporary directories; wider retries require approval.
            |      danger-full-access:
            |        sandbox: danger-full-access
            |        approval: never
            |        name: danger-full-access
            |        description: Full file access without approval prompts.
            |
            |# Critical host services are repeated in this last overlay deliberately.
            |# Profile bundle patches and the user's profile patch are applied before
            |# --patch, and a later row with the same id replaces the complete row.
            |# This prevents an installed plugin from disabling the directory picker,
            |# workspace controller, loader, or web server by accident. The complete
            |# config is repeated because patching replaces, rather than merges, config.
            |- id: typert
            |  name: '@deepseek-ai/dsh-typert-registry'
            |  disabled: false
            |
            |- id: typert-loader
            |  name: '@deepseek-ai/dsh-typert-loader'
            |  disabled: false
            |
            |- id: typert-gateway
            |  name: '@deepseek-ai/dsh-api-gateway'
            |  disabled: false
            |
            |- id: directory-picker
            |  name: '@deepseek-ai/dsh-host-directory-picker-auto'
            |  inject: [webServer, loader]
            |  disabled: false
            |
            |- id: session-persistence-jsonl
            |  name: '@deepseek-ai/dsh-session-persistence-jsonl'
            |  config:
            |    root: !!js dshHomePath('sessions')
            |  disabled: false
            |
            |- id: storage-domain
            |  name: '@deepseek-ai/dsh-storage-domain'
            |  config:
            |    backend: json
            |  disabled: false
            |
            |- id: workspace-controller
            |  name: '@deepseek-ai/dsh-api-workspace-controller'
            |  disabled: false
            |
            |- id: workspace
            |  name: '@deepseek-ai/dsh-workspace'
            |  disabled: false
            |
            |- id: webserver
            |  name: '@deepseek-ai/dsh-host-webserver'
            |  inject: [webStartup]
            |  disabled: false
            |  config:
            |    host: !!js ctx.webStartup.host ?? '127.0.0.1'
            |    port: !!js ctx.webStartup.port ?? 3080
            |    compression: gzip
            |    compressionLevel: 1
            |    compressionThresholdBytes: 1024
            |
            |# Web providers: leave this row unpatched on purpose. Patching `web`'s
            |# searchProvider/fetchProvider makes the shipped 「网页搜索」settings page
            |# reject every save ("本部署没有接受这些值") on this engine build, because
            |# the form round-trips the row config against the values it served. The
            |# composite fetch provider below is still registered, so a user (or a
            |# future patch that changes the form's defaults) can opt in by setting
            |# fetchProvider: android-fetch-auto from the settings UI.
            |- id: web
            |  name: '@deepseek-ai/dsh-web'
            |
            |# This probe is intentionally last: Cordis keeps failed imports as
            |# entries without a fiber, while the HTTP port can still be healthy.
            |# The app consumes its marker and can quarantine only a user bundle.
            |- insert:
            |    - id: android-plugin-health
            |      name: ./android-plugins/plugin-health.mjs
            |
            |- insert:
            |    - id: android-priv-mode
            |      name: ./android-plugins/priv-mode.mjs
            |    # DSH_BRIDGE_TOKEN likewise: the bridges require it, but
            |    # dsh-subprocess.scrubbedParentEnv() drops every DSH_* variable, so
            |    # without registering it the gate scripts (scr/notify/say/shz) get an
            |    # empty token, the bridge answers 403, and the agent is locked out of
            |    # the device capabilities it is meant to have (found in review).
            |    - id: android-bridge-token
            |      name: ./android-plugins/bridge-token.mjs
            |
            |# Accessibility-time thinking switch (v1.2.165): the agent runs `fast on`
            |# before an accessibility task, so later steps stop paying the reasoning
            |# cost; `fast off` restores normal conversation. The plugin reads the
            |# ${'$'}DSH_HOME/fast-mode marker file and only affects reasoningEffort.
            |- insert:
            |    - id: android-fast-mode
            |      name: ./android-plugins/fast-mode.mjs
            |
            |# Web fetch composite (v1.3.27): official DeepSeek Fetch API first, local HTTP(S)
            |# fetch as the fallback. Two reasons, one provider:
            |#  1. the official Fetch API refuses sites it cannot scrape (anti-bot / captcha
            |#     answers 404 "failed to fetch URL"), while the local hop behind the
            |#     side-router reads them fine;
            |#  2. this engine pins npm 0.2.1-rc.2, which predates the upstream
            |#     allowedNonPublicRanges knob, so the local hop's SSRF guard rejects every
            |#     fake-IP answer (198.18.0.0/15) with WEB_BLOCKED_URL.
            |# The plugin registers the composite provider below; its local resolver allows the
            |# configured fake-IP ranges and still rejects loopback / RFC1918 / link-local.
            |- insert:
            |    - id: android-fetch-auto
            |      name: ./android-plugins/android-fetch-auto.mjs
            |
            |# Narrow-screen UI fixes (v1.2.175 - v1.2.181). Each is a *client plugin
            |# package* (directory form, e.g. mobile-ui/package.json): the package
            |# declares dsh.client + exports["./client"], and the engine's client-modules
            |# scanner discovers it from Loader entries and injects the browser bundle.
            |# Why plugins instead of patching the engine source: the user asked for a
            |# plugin form so it never conflicts with other UI plugins and survives
            |# engine upgrades.
            |#
            |# dsh-mobile-ui (v0.1.17, by the user's earlier mobile-ui work): the Android
            |# app-style layer over the shipped Web UI. One client plugin that turns the
            |# sidebar into a popup drawer, collapses the conversation header behind an
            |# info button, and adapts the composer. There is no in-page rollback any more:
            |# if the skin breaks the UI, recover from the native settings screen
            |# ("Stock UI"), which flips dsh_ui/stock_ui, skips this block and deletes the
            |# deployed mobile-ui folder. Everything is gated on html[data-dsh-mobile].
            |- insert:
            |    - id: mobile-ui
            |      name: ./android-plugins/mobile-ui/index.js
            |
            |# v1.3.68 background progress: watch the current session DOM (thinking / step)
            |# and report it to native through DshNative for the notification / floating
            |# window. No-op in a plain browser where DshNative is absent. (ASCII only.)
            |- insert:
            |    - id: mobile-progress
            |      name: ./android-plugins/mobile-progress/index.js
            |
            |""".trimMargin()
        runCatching {
            if (!f.isFile || f.readText() != body) f.writeText(body)
        }
        // The overlay references the plugins by relative path, so the files must
        // sit beside it. Deploy from assets (kept in sync by the same idempotent check).
        runCatching {
            // 单文件插件（.mjs）：overlay 直接指向文件
            listOf("priv-mode.mjs", "bridge-token.mjs", "fast-mode.mjs", "plugin-health.mjs", "android-fetch-auto.mjs").forEach { fileName ->
                val src = ctx.assets.open("android-plugins/$fileName").use { it.readBytes() }
                val dst = File(f.parentFile, "android-plugins/$fileName")
                deployOne(dst, src.toString(Charsets.UTF_8), fileName)
            }
            // 目录形式的客户端插件包（v1.2.175+）：引擎按 package.json 的 name 索引
            // 浏览器端 bundle，所以 index.js / client.js / package.json 三个文件
            // 必须同目录且完整。缺任何一个 → client-modules 抛
            // "declares dsh.client but exports no ./client bundle" 或 bundle 加载后
            // 未注册（"loaded without registering"）。
            // 新增插件的步骤：① 在 assets 建目录（含这三个文件）
            //               ② 在此列表追加目录名
            //               ③ 在 overlay 的 insert 段追加一条 entry（指向 index.js）
            listOf("mobile-progress", "mobile-ui").forEach { pkg ->
                listOf("package.json", "index.js", "client.js").forEach { fileName ->
                    val src = ctx.assets.open("android-plugins/$pkg/$fileName")
                        .use { it.readBytes() }
                    val dst = File(f.parentFile, "android-plugins/$pkg/$fileName")
                    deployOne(dst, src.toString(Charsets.UTF_8), "$pkg/$fileName")
                }
            }
        }.onFailure { Log.w(TAG, "android plugin deploy failed: ${it::class.simpleName}: ${it.message}", it) }
        return f
    }

    /**
     * 把一个资产文本部署到引擎目录，带「重装后自愈」语义。
     *
     * ⚠️ v1.2.173 实测背景：每次重装 APK，Android 分配**新 uid**，而旧插件文件
     * 属主仍是旧 uid + 权限 600 → app 自己读不了（EACCES → 引擎 "failed to import"）。
     * 提权修复走不通（Shizuku 的 adb 身份无权访问 app 私有目录）。
     * 但 app 对自己的目录有写权限：删掉目录里的文件只需目录写权限，不需要文件本身的
     * 权限 → 正解 = 读不了就**删掉重建**。
     *
     * ⚠️ v1.2.174：判空必须写成 `!dst.isFile || …`。写成 `dst.isFile && …` 时
     * 文件不存在会让整式为 false（stale=false），于是文件**永不创建**。
     *
     * @param dst 目标文件（父目录会自动创建）。
     * @param text 期望内容。
     * @param label 日志用标签。
     */
    private fun deployOne(dst: File, text: String, label: String) {
        dst.parentFile?.mkdirs()
        val stale = !dst.isFile ||
            runCatching { dst.readText() != text }.getOrDefault(true)
        if (stale) {
            runCatching { dst.delete() }   // 旧属主也能删（目录归 app）
            if (!dst.isFile || runCatching { dst.readText() }.getOrNull() != text) {
                dst.writeText(text)
            }
        }
        dst.setReadable(true, false)
        dst.setReadable(true, true)
        Log.i(TAG, "plugin deployed: $label (${dst.length()} bytes, readable=${dst.canRead()})")
    }

    /**
     * 引擎默认 shell 路径（v1.3.1，issue #18）。
     *
     * 首选自带 bash（`<engineRoot>/bin/bash`，门脚本与 /dev/tcp 都依赖 bash 特性）；
     * 若因解压不完整等原因缺失，退回 runtime 里的 `bin/sh`，再退回系统 toybox sh。
     * 绝不返回 Termux 前缀——那条路径在本机永远不存在。
     */
    private fun shellPathFor(root: File): String {
        val bash = File(root, "bin/bash")
        if (bash.canExecute()) return bash.absolutePath
        val sh = File(root, "bin/sh")
        if (sh.exists()) return sh.absolutePath
        Log.w(TAG, "engine bash missing at ${bash.absolutePath}; falling back to /system/bin/sh")
        return "/system/bin/sh"
    }

    /**
     * 组装子进程环境变量。
     * PATH/LD_LIBRARY_PATH/PREFIX 对齐 Termux 布局，保证 bionic 二进制能找到依赖库。
     *
     * OPENSSL_CONF/SSL_CERT_FILE：Termux 编译的 node 将 OpenSSL 目录硬编码为
     * /data/data/com.termux/files/usr/etc/tls；设备共存真实 Termux 时 fopen 命中
     * EACCES → "OpenSSL configuration error" → node 启动即退（m1.1 真机事故根因，
     * 模拟器因无 com.termux 目录呈 ENOENT 静默通过故未暴露）。显式指回自带 etc/tls
     * 与宿主 Termux 彻底隔离。
     */
    fun buildEnv(ctx: android.content.Context, port: Int, bootId: String? = null): Array<String> {
        val root = engineRoot(ctx)
        // 运行权限模式（m1.24）：注入给 AI 侧感知，令其按模式调整执行行为
        val privMode = Privilege.getMode(ctx)
        // m1.29：按模式控制 AI 子进程能否用 su。engine/bin 在 PATH 首位，
        // 非 Root 模式往 engine/bin 放一个「拒绝执行」的 su 遮罩（覆盖 /system/bin/su），
        // Root 模式移除遮罩放行真 su。这样只有切到 Root 模式 AI 才提权。
        applySuGate(root, privMode)
        // m1.30：Shizuku 模式注入 shz 包装器（AI 显式 `shz <adb命令>` 走 ADB 级执行）；
        // 其他模式删除，AI 调 shz 将无命令。
        applyShzGate(root, privMode, port)
        // v1.1.0：notify/scr 包装器（所有模式可用——通知与无障碍是 App 自身能力，
        // 经 AgentBridge 127.0.0.1:3083 转发）。
        applyAgentGates(ctx, root)
        // 运行时 overlay（2026-10-18）：把 assets/runtime-overlay 覆盖进引擎根。
        // 当前只有一项 —— node-pty 的**真**实现（转发到 App 的 libdshpty.so），
        // 替换构建期那个「spawn() 直接 throw」的空壳，WebUI 侧边栏终端才能用。
        applyRuntimeOverlays(ctx, root)
        // v2.5.45：补齐 npm/pnpm/corepack 入口。运行时只带 node + corepack 本体
        // （lib/node_modules/corepack），bin/ 里没有 npm/pnpm/npx，导致 dsh-market
        // 的自动配置与 `dsh plugin add` 全部报「找不到 npm/corepack」。
        ensureNodeTooling(ctx, root)
        // 引擎侧 Android 补丁（2026-10-18）：dsh-subprocess-local 把「终端进程检查」
        // 限定在 linux/darwin/win32，android 直接抛 "terminal inspection is unsupported
        // on platform android" → WebUI 侧边栏终端打不开。Android 有 /proc，检查器可用。
        patchEngineForAndroid(root)
        val env = mutableListOf(
            "PATH=" + (listOf(File(root, "bin"), File(dshHome(ctx), "local/bin"),
                File(dshHome(ctx), ".local/share/pnpm")) +
                listOf("/system/bin", "/system/xbin")).joinToString(":"),
            "DSH_SHZ_PORT=${ShizukuHttpBridge.port(port)}",
            // 桥 token（v1.2.99）：只交给引擎子进程，供 scr/notify 等闸门脚本鉴权。
            // 第三方 App 拿不到 → 无法调用读屏/点击/通知等设备能力。
            "DSH_BRIDGE_TOKEN=${AgentBridge.currentToken().orEmpty()}",
            "LD_LIBRARY_PATH=" + listOf(File(root, "lib")).joinToString(":"),
            "PREFIX=$root",
            "COREPACK_HOME=${File(dshHome(ctx), ".cache/corepack").absolutePath}",
            // SHELL（v1.3.1，issue #18「终端错误」）：必须显式注入。
            // 引擎的 dsh-subprocess-local 按 `process.env.SHELL || os.userInfo().shell`
            // 推导默认 shell（terminalEnvironment），而 runtime 里的 node 是 Termux 官方
            // 构建——它在二进制内把 passwd 的 shell 硬编码成
            // /data/data/com.termux/files/usr/bin/bash（本机不存在，且与用户是否装过
            // Termux 无关）。不设 SHELL 时引擎就会拿这个坏路径去 resolveExecutable →
            // `subprocess-local: command "..." is not an executable file`（持久 bash
            // 工具与 Web 终端一起报错）。注入后 SHELL 优先级最高，且指向自带 bash。
            "SHELL=${shellPathFor(root)}",
            "HOME=${dshHome(ctx)}",
            "DSH_HOME=${dshHome(ctx)}",
            "TMPDIR=${tmpDir(ctx)}",
            "PORT=$port",
            "NODE_ENV=production",
            // 取值统一小写（normal/shizuku/root），与 AGENTS 种子里 AI 读到的文档一致。
            // 此前注入 .name（ROOT）、种子写 lowercase（root）→ AI 按文档写
            // `[ "$DSH_ANDROID_PRIV_MODE" = "root" ]` 永远不成立，静默走错分支。
            "DSH_ANDROID_PRIV_MODE=${privMode.name.lowercase()}",
            // Android 标准环境（init 对普通进程的设定）。aapt / apksigner / zipalign 靠它
            // 判断「是否运行在 Android 上」，缺失时直接报 "ANDROID_DATA not set" 并退出
            // （扩展中心主推 android-buildtools，AI 又被种子引导使用这些工具 → 开箱即坏）。
            // 引擎进程环境会被所有子 shell 与扩展二进制继承，一处修复全局生效。
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "ANDROID_STORAGE=/storage",
            // 社区插件安装（dsh plugin add → pnpm）：pnpm 10+ 的 strictDepBuilds 默认
            // 为 true，只要依赖里带 build script（mnemon、puppeteer 等）就被记成
            // "ignored builds" 并**直接让整次安装失败**：
            //   ERR_PNPM_IGNORED_BUILDS / Ignored build scripts: mnemon@1.3.0, puppeteer@…
            // 手机上没有交互式 `pnpm approve-builds`，所以一律降级为警告继续装。
            "npm_config_strict_dep_builds=false",
            "NPM_CONFIG_STRICT_DEP_BUILDS=false",
            // 让 pnpm 别把「被忽略的构建脚本」当致命错误（pnpm v12 新 strict 模式）
            "npm_config_allow_unused=false",
            // 权限三件套的单一驱动源（v1.2.52）：上游 dsh-base 用这一个变量同时决定
            // sandbox-policy.mode、approval.policy 与默认预设，保证三者组合一致。
            // Android 没有 landlock/seatbelt，任何受限模式都会让 shell 工具拒绝执行
            // （"no sandbox backend usable on host"），故固定 danger-full-access；
            // 真实安全边界是 App 的权限模式（Normal/Shizuku/Root）与 su 闸门。
            "DSH_PERMISSION_MODE=danger-full-access",
            *(bootId?.let { arrayOf("DSH_ANDROID_BOOT_ID=$it") } ?: emptyArray()),
        )
        // 包管理器镜像（2026-10-05，Issue 反馈「pnpm 连不上 GitHub」）：
        // runtime 内没有任何 .npmrc，corepack/npm 默认走 registry.npmjs.org —— 国内直连常
        // 超时或被重置。三个变量覆盖三条路径，缺一不可：
        //   · COREPACK_NPM_REGISTRY —— corepack 拉取 pnpm/yarn 本体（corepack.cjs 内读取）
        //   · npm_config_registry    —— npm / pnpm 自身的包解析（小写环境变量是 npm 的规范形式）
        //   · NPM_CONFIG_REGISTRY    —— 大写别名，部分工具只认大写
        // 用户可通过设置里已导出的同名环境变量覆盖（种子文档也据此告诉 AI 如何换源）。
        listOf("COREPACK_NPM_REGISTRY", "npm_config_registry", "NPM_CONFIG_REGISTRY")
            .filterNot { System.getenv(it)?.isNotBlank() == true }
            .forEach { env += "$it=$NPM_REGISTRY_MIRROR" }
        // 编译工具链支持（AI 交叉编译清单实测）：
        // - GOTMPDIR：Termux go 的临时目录回退硬编码 /data/data/com.termux（不存在）→ 显式指到引擎 tmp
        // - LIBRARY_PATH：链接期库搜索（rust-lld/clang 的 -lunwind 等命中扩展 lib）
        // - CPATH：头文件搜索——ndk-sysroot 的 asm/types.h 在 include/<triple>/ 子目录，
        //   clang 内置的 $PREFIX 头路径是编译期硬编码、指向已不存在的 Termux 前缀
        // - RUSTFLAGS：rustc 传给 rust-lld 的额外 -L（LIBRARY_PATH 对 lld 不生效）
        tmpDir(ctx).takeIf { it.isDirectory }?.let { env += "GOTMPDIR=$it" }
        // 扩展中心已移除（Termux 原生 pkg/apt 取代）：只保留前缀自身的库路径。
        val libPaths = listOf(File(root, "lib")).filter { it.isDirectory }.joinToString(":")
        if (libPaths.isNotEmpty()) env += "LIBRARY_PATH=$libPaths"
        File(root, "etc/tls/openssl.cnf").takeIf { it.isFile }?.let { env += "OPENSSL_CONF=$it" }
        File(root, "etc/tls/cert.pem").takeIf { it.isFile }?.let { env += "SSL_CERT_FILE=$it" }
        return env.toTypedArray()
    }

    /**
     * su 闸门（m1.29）：engine/bin 在 PATH 首位，控制 AI 子进程能否提权。
     * - 非 Root（普通/Shizuku）：写入一个拒绝执行的 su 遮罩 —— AI 调 su 立即报错退出，
     *   覆盖系统 /system/bin/su。已 root 且投过权也不放行（符合「只有切 Root 才允许」）。
     * - Root：删除遮罩，让 AI 走系统真 su（引擎整体已以 root 启动）。
     */
    private fun applySuGate(root: File, mode: PrivMode) {
        val bindir = File(root, "bin").apply { mkdirs() }
        val suShim = File(bindir, "su")
        if (mode == PrivMode.ROOT) {
            if (suShim.exists()) {
                suShim.delete()
                Log.i(TAG, "su gate: ROOT mode, removed su shim (AI can su)")
            }
            return
        }
        // 非 Root：写拒绝遮罩（幂等，总是覆盖成正确内容）
        try {
            suShim.writeText("#!/system/bin/sh\n" +
                "# [dsh-android] su gate: priv mode != ROOT, deny su.\n" +
                "echo 'su: Permission denied (dsh-android: run as Root mode to gain su)' >&2\n" +
                "exit 1\n")
            suShim.setExecutable(true, false)
            if (!suShim.canExecute()) {
                // 某些 ROM 需显式 chmod；setExecutable 失败罕见，写日志即可
                Log.w(TAG, "su gate: chmod failed on su shim")
            }
            Log.i(TAG, "su gate: mode=$mode, su denied via shim in engine/bin")
        } catch (e: Exception) {
            Log.w(TAG, "su gate: write su shim failed: ${e.message}")
        }
    }

    /**
     * shz 闸门（m1.30）：engine/bin 在 PATH 首位，控制 AI 能否用 ADB 级能力。
     * 仅 Shizuku 模式注入 `shz` 包装器：
     *   - shz <cmd>：把 <cmd> 经 HTTP POST 到 ShizukuHttpBridge（127.0.0.1:DSH_SHZ_PORT），
     *     由 Privilege.shizukuExec 以 adb 身份执行并打印输出。
     *   - 其他模式删除 shz，AI 调 shz 报 command not found（无 ADB 能力）。
     */
    private fun applyShzGate(root: File, mode: PrivMode, port: Int) {
        val bindir = File(root, "bin").apply { mkdirs() }
        val shz = File(bindir, "shz")
        if (mode != PrivMode.SHIZUKU) {
            if (shz.exists()) shz.delete()
            return
        }
        try {
            val bridgePort = ShizukuHttpBridge.port(port)
            // 关键：node -e CODE -- "$@" 时 `--` 被 node 消费掉，process.argv=[node, arg1..]，
            // 因此必须 slice(1)。旧版 slice(2) 会丢弃第一个参数（如 shz id 变 shz ），导致
            // bridge 收到空/残缺命令 → empty command / socket hang up。改用 fetch 简化并更稳。
            shz.writeText("#!/system/bin/sh\n" +
                "# [dsh-android] shz: run a command via Shizuku (adb uid) — Shizuku mode only.\n" +
                "# Posts the command to the in-process Android bridge, which executes it with\n" +
                "# IShizukuService.newProcess. Usage: shz <any shell command>\n" +
                "if [ \"$#\" -eq 0 ]; then echo 'usage: shz <command>' >&2; exit 2; fi\n" +
                // node 是动态链接的 bionic 二进制：补 LD_LIBRARY_PATH（v1.2.100，
                // 与 curl 包装器同一坑：独立调用时缺库路径会 CANNOT LINK）
                "PREFIX=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n" +
                // Kotlin 里 $ 是字符串模板起始符：shell 变量必须写成 ${'$'}{VAR}
                "export LD_LIBRARY_PATH=\"${'$'}{PREFIX}/lib:${'$'}{PREFIX}/usr/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}\"\n" +
                "exec \"$(dirname \"$0\")/node\" -e '\n" +
                "  const port = Number(process.env.DSH_SHZ_PORT || " + bridgePort + ");\n" +
                "  const cmd = process.argv.slice(1).join(\" \");\n" +
                // 桥鉴权（v1.2.100）：ShizukuHttpBridge 现在要求 X-DSH-Token。
                // AI 不知道 token，只能从环境变量读 —— 引擎子进程继承得到，第三方 App 没有。
                "  const headers = { \"Content-Type\": \"text/plain; charset=utf-8\" };\n" +
                "  if (process.env.DSH_BRIDGE_TOKEN) headers[\"X-DSH-Token\"] = process.env.DSH_BRIDGE_TOKEN;\n" +
                "  fetch(\"http://127.0.0.1:\" + port + \"/shizuku_exec\", { method: \"POST\", headers: headers, body: cmd })\n" +
                "    .then(async (res) => { const t = await res.text(); process.stdout.write(t); process.exit(res.status === 200 ? 0 : 1); })\n" +
                "    .catch((e) => { console.error(\"shz: \" + e.message); process.exit(2); });\n" +
                "' -- \"$@\"\n")
            shz.setExecutable(true, false)
            Log.i(TAG, "shz gate: mode=SHIZUKU, shz wrapper injected -> :$bridgePort")
        } catch (e: Exception) {
            Log.w(TAG, "shz gate: write shz failed: ${e.message}")
        }
    }

    /**
     * Agent 能力包装器（v1.1.0）：notify / scr，全模式注入。
     * 二者都是 node fetch 到 AgentBridge (127.0.0.1:3083) 的薄包装：
     *  - notify [message]        → POST /notify（任务完成系统通知；AGENTS.md 约定任务完成必调）
     *  - scr dump                → GET  /screen（读屏：可见文本+坐标 JSON）
     *  - scr tap <x> <y>         → POST /tap 坐标点击
     *  - scr tap-text <文本>     → POST /tap 按文本点击（无障碍服务开启才可用）
     */
    /**
     * 引擎侧 Android 兼容补丁（幂等、按内容锚点，不绑死文件名/版本）。
     *
     * 目前只有一条：`dsh-subprocess-local` 的进程检查器把平台限定成
     * linux/darwin/win32，遇到 android 直接抛
     * `subprocess-local: terminal inspection is unsupported on platform android`
     * → WebUI 侧边栏终端（以及一切用 spawnTerminal 的功能）在 Android 上完全不可用。
     *
     * Android 就是 Linux 的一个变体，检查器用的全是 `/proc/<pid>/` 下的 stat/task/fd/syscall，这些在 Android 都存在；`/bin/ps` 那两处只在 darwin 分支和
     * `foregroundPgid` 里用，且调用点已 try/catch（取不到就返回 undefined），
     * 不会把终端判死。因此把 android 归到 Linux 分支是正确且最小的改动。
     *
     * 为什么在 App 启动时打而不是构建期：dsh-subprocess-local 的文件名带内容哈希
     * （runner-launch-XXXX.js），版本一升就变；按**文件内容锚点**扫描整个包目录，
     * 既能跨版本生效，也不会在锚点消失时误改别的代码。
     */
    /** 单个 overlay 文件部署（内容一致则跳过；失败只记日志不阻断启动） */
    private fun deployOverlayFile(ctx: android.content.Context, root: File, rel: String) {
        runCatching {
            val bytes = ctx.assets.open("runtime-overlay/$rel").use { it.readBytes() }
            val dst = File(root, rel)
            if (dst.isFile && dst.readBytes().contentEquals(bytes)) return@runCatching
            dst.parentFile?.mkdirs()
            dst.writeBytes(bytes)
            // overlay 里有需要直接执行的（bin/tar 的 GNU 选项过滤器）；统一给 +x。
            dst.setExecutable(true, false)
            Log.i(TAG, "runtime overlay deployed: $rel")
        }.onFailure { Log.w(TAG, "runtime overlay $rel failed: ${it.message}") }
    }

    private fun patchEngineForAndroid(root: File) {
        val dir = File(root, "lib/node_modules/@deepseek-ai/dsh-subprocess-local/lib")
        if (!dir.isDirectory) {
            Log.w(TAG, "engine patch: subprocess-local not found, skip")
            return
        }
        val anchor = "if (platform === \"linux\") return new LinuxProcessInspector(arch, internals);"
        val replacement = "/* [dsh-android] Android has /proc: reuse the Linux inspector */\n" +
            "\tif (platform === \"linux\" || platform === \"android\") return new LinuxProcessInspector(arch, internals);"
        var patched = 0
        dir.listFiles { f -> f.isFile && f.name.endsWith(".js") }?.forEach { f ->
            val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
            if (!text.contains(anchor)) return@forEach
            runCatching {
                f.writeText(text.replace(anchor, replacement))
                patched++
                Log.i(TAG, "engine patch: android terminal inspection enabled in ${f.name}")
            }.onFailure { Log.w(TAG, "engine patch failed on ${f.name}: ${it.message}") }
        }
        if (patched == 0) Log.i(TAG, "engine patch: android terminal inspection already patched")
        patchInspectorWorkerForAndroid(root)
        patchFrontendSpaFallback(root)
        patchNativeCommandForAndroid(root)
    }

    /**
     * 让 WebUI 的「open in app / 在文件夹中显示」在 Android 上生效（v1.3.41）。
     *
     * 上游 `dsh-native-command` 把 android 当作「没有桌面」直接拒绝，于是 WebUI 显示
     * 「此主机没有可用的桌面，无法使用外部程序打开文件或文件夹」。这里按内容锚点打补丁：
     *  - `canOpenNativePath` 对 android 返回 true（让按钮出现）；
     *  - `openNativePathWithIntent` 增加 android 分支：POST 到本 App 的 AgentBridge
     *    `/open/file`，由 Kotlin 侧用 DshFileProvider + ACTION_VIEW 交给系统 App 打开。
     * token 优先取进程环境，否则回退读 $PREFIX/.bridge-token。
     */
    private fun patchNativeCommandForAndroid(root: File) {
        val file = File(root, "lib/node_modules/@deepseek-ai/dsh-native-command/lib/index.js")
        if (!file.isFile) { Log.w(TAG, "native-command patch: module not found, skip"); return }
        var text = runCatching { file.readText() }.getOrNull() ?: return
        val before = text

        // 1) 注入/升级 bridge 调用助手（android 分支共用；v73 的旧助手没有 route 参数，必须替换）
        val helperAnchor = "/** Dispatch one shell-free platform command for the requested open intent. */"
        val helper = """/** [dsh-android] android open v3: hand the path to the app's AgentBridge (v1.3.42). */
async function openAndroidPath(path, signal, env, route = "/open/file") {
	let token = env.DSH_BRIDGE_TOKEN || process.env.DSH_BRIDGE_TOKEN || "";
	if (!token) {
		try {
			token = (await readFile(process.env.PREFIX + "/.bridge-token", "utf8")).trim();
		} catch {}
	}
	console.error("[dsh-android] bridge open " + route + " path=" + path);
	const res = await fetch("http://127.0.0.1:3083" + route, {
		method: "POST",
		headers: { "content-type": "application/json", "x-dsh-token": token },
		body: JSON.stringify({ path }),
		signal
	});
	const body = await res.text();
	console.error("[dsh-android] bridge open result " + res.status + " " + body.slice(0, 400));
	if (!res.ok) throw new Error("android open failed (" + res.status + "): " + body);
	return body;
}
/** Dispatch one shell-free platform command for the requested open intent. */"""
        val oldHelperStart = text.indexOf("/** [dsh-android] android open")
        when {
            oldHelperStart >= 0 -> {
                val oldHelperEnd = text.indexOf(helperAnchor, oldHelperStart)
                if (oldHelperEnd > oldHelperStart) {
                    text = text.substring(0, oldHelperStart) + helper + text.substring(oldHelperEnd)
                    Log.i(TAG, "native-command patch: bridge helper upgraded to v2")
                }
            }
            text.contains(helperAnchor) -> {
                text = text.replace(helperAnchor, helper)
                Log.i(TAG, "native-command patch: bridge helper injected")
            }
            else -> Log.w(TAG, "native-command patch: helper anchor missing")
        }

        // 2) 默认打开（open / association / text-editor 都走这里）
        val throwAnchor = "throw new Error(`native path opener is unsupported on \${platform}`);"
        val branch = "\tif (platform === \"android\") {\n\t\tawait openAndroidPath(path, signal, env);\n\t\treturn;\n\t}\n"
        if (text.contains(throwAnchor) && !text.contains("await openAndroidPath(path, signal, env);")) {
            text = text.replace(throwAnchor, branch + throwAnchor)
        }

        // 3) 「显示文件位置」（reveal）在 android 上打开父目录
        val revealAnchor = "async function revealNativePath(path, signal, internals = {}) {"
        val revealBranch = "async function revealNativePath(path, signal, internals = {}) {\n\t// [dsh-android] android reveal: open the parent folder through the app bridge\n\tif ((internals.platform ?? process.platform) === \"android\") {\n\t\tawait openAndroidPath(dirname(path), signal, internals.env ?? process.env, \"/open/folder\");\n\t\treturn;\n\t}"
        if (text.contains(revealAnchor) && !text.contains("[dsh-android] android reveal")) {
            text = text.replace(revealAnchor, revealBranch)
        }

        // 4) canOpenNativePath 对 android 返回 true（让 UI 显示按钮）
        val canOpenAnchor = "if (platform === \"darwin\" || platform === \"win32\") return true;"
        if (text.contains(canOpenAnchor)) {
            text = text.replace(canOpenAnchor, "if (platform === \"darwin\" || platform === \"win32\" || platform === \"android\") return true;")
        }

        if (text == before) { Log.i(TAG, "native-command patch: already applied"); return }
        runCatching {
            file.writeText(text)
            Log.i(TAG, "engine patch: android open-in-app enabled (native-command)")
        }.onFailure { Log.w(TAG, "native-command patch failed: ${it.message}") }
    }

    /**
     * WebUI 客户端路由 fallback（v1.3.27，issue「设置页歧义」）。
     *
     * 上游 `dsh-host-frontend-static` 对 dist 里不存在的路径一律 404，导致任何
     * 客户端路由（/settings、/settings/general…）一刷新/直链就白屏；WebUI 的
     * 设置其实是「账号菜单 → 设置」的全屏浮层（URL 不变），不是路由。这里按
     * 内容锚点把「扩展名缺失的未知路径」改回 index.html，让深链/刷新得到 SPA
     * 入口而不是错误页（API/静态资源路径有扩展名或是具名路由，不受影响）。
     */
    private fun patchFrontendSpaFallback(root: File) {
        val file = File(root, "lib/node_modules/@deepseek-ai/dsh-host-frontend-static/lib/index.js")
        if (!file.isFile) { Log.w(TAG, "spa fallback: frontend-static not found, skip"); return }
        val text = runCatching { file.readText() }.getOrNull() ?: return
        if (text.contains("[dsh-android] SPA fallback")) return
        val anchor = """	} catch (error) {
		if (!STATIC_MISS_CODES.has(error.code)) throw error;
		res.writeHead(404);
		res.end();
		return;
	}"""
        val replacement = """	} catch (error) {
		if (!STATIC_MISS_CODES.has(error.code)) throw error;
		// [dsh-android] SPA fallback (v1.3.27): extensionless client routes such as
		// /settings must return index.html instead of 404 (refresh/deep links otherwise
		// show chrome-error and look like the settings page is broken).
		const lastSegment = target.slice(distRoot.length).split(sep).pop() ?? "";
		if (lastSegment.includes(".")) {
			res.writeHead(404);
			res.end();
			return;
		}
		if (!authorizeIndex()) return;
		const spaBody = await renderIndex();
		res.writeHead(200, { "content-type": HTML_MIME });
		res.end(spaBody);
		return;
	}"""
        if (!text.contains(anchor)) { Log.w(TAG, "spa fallback anchor missing (upstream changed?)"); return }
        runCatching {
            file.writeText(text.replace(anchor, replacement))
            Log.i(TAG, "engine patch: SPA fallback enabled in frontend-static")
        }.onFailure { Log.w(TAG, "spa fallback patch failed: ${it.message}") }
    }

    /**
     * 开发者工具（experimental-inspector）的 Worker 需要 `--expose-internals`。
     *
     * 上游 `dsh-experimental-inspector/lib/index.js` 里 `spawnWorker()` 写死
     * `execArgv: []` —— 桌面端没问题，因为它用**预编译原生 addon** 访问 Node 内部模块；
     * Android 上我们只能用 JS 兜底（`node-addon-require-builtin` 改成 createRequire），
     * 而 createRequire('internal/...') 只有在进程启动带 `--expose-internals` 时可用。
     * Worker 的 execArgv 被显式清空 → 插件激活时报：
     *   Error: Cannot find module 'internal/modules/esm/loader'
     * 这里把空的 execArgv 补成 `["--expose-internals"]`（幂等；热更新后重跑）。
     */
    private fun patchInspectorWorkerForAndroid(root: File) {
        val f = File(root, "lib/node_modules/@deepseek-ai/dsh-experimental-inspector/lib/index.js")
        if (!f.isFile) {
            Log.i(TAG, "inspector patch: experimental-inspector not present, skip")
            return
        }
        val text = runCatching { f.readText() }.getOrNull() ?: return
        if (text.contains("execArgv: [\"--expose-internals\"]")) {
            Log.i(TAG, "inspector patch: worker execArgv already patched")
            return
        }
        if (!text.contains("execArgv: []")) {
            Log.w(TAG, "inspector patch: anchor 'execArgv: []' not found (engine layout changed?)")
            return
        }
        runCatching {
            f.writeText(text.replace("execArgv: []", "execArgv: [\"--expose-internals\"]"))
            Log.i(TAG, "inspector patch: worker execArgv += --expose-internals")
        }.onFailure { Log.w(TAG, "inspector patch failed: ${it.message}") }
    }

    /**
     * 运行时 overlay：`assets/runtime-overlay/<相对路径>` → `<engineRoot>/<相对路径>`。
     *
     * 为什么需要：有些「引擎侧」的兼容层无法在运行时打包阶段解决，只能在 App 启动时
     * 覆盖进引擎树。当前唯一的一项是 node-pty 的**真实现**：
     *  - 构建期（collect-termux-runtime.sh）把 node-pty 打成空壳，因为上游预编译是
     *    glibc/x64 的 .node，Android 加载不了 —— 于是 WebUI 侧边栏终端一开就 throw；
     *  - 现在 App 进程有真 PTY（libdshpty.so），overlay 里的 node-pty 通过
     *    AgentBridge 的 pty 系列端点转发，侧边栏拿到的是真 tty。
     *
     * 部署条件（幂等、可升级）：
     *  - 目标不存在 → 写；
     *  - 目标是空壳（package.json 里含 "android-shim"）→ 覆盖；
     *  - 目标已是 overlay 版本（内容一致）→ 跳过；
     *  - 目标是**真** node-pty（有 build/Release 等）→ 不动，避免破坏未来真机可用的预编译。
     */
    /**
     * 生成 bin/{corepack,npm,npx,pnpm} 四个 POSIX 壳脚本。
     *
     * 运行时（engine-dsh-aarch64.zip）只提供 `bin/node` 与 `lib/node_modules/corepack`，
     * 没有 npm/pnpm 入口；而 dsh 的插件安装器与 dsh-market 都按 PATH 找它们。
     * 这里用 corepack 做唯一实现来源，npm/pnpm/npx 都是它的转发壳，避免再塞一份 npm 进 APK。
     */
    private fun ensureNodeTooling(ctx: android.content.Context, root: File) {
        val bin = File(root, "bin")
        if (!bin.isDirectory) return
        val node = File(bin, "node")
        val corepackJs = File(root, "lib/node_modules/corepack/dist/corepack.js")
        // corepack 的 dist/ 下每个工具都有自己的 JS 入口（npm.js/npx.js/pnpm.js），
        // 只有 corepack.js 本体是 Android shell 包装脚本。所以：
        //   corepack → 直接执行那个 shell 包装脚本
        //   npm/npx  → node corepack/dist/<tool>.js（corepack 自带的转发实现）
        //   pnpm     → 优先用随包附带的 pnpm（离线可用），否则退回 corepack 版
        val shims = mapOf(
            "corepack" to "exec \"\$(dirname \"\$0\")/../lib/node_modules/corepack/dist/corepack.js\" \"\$@\"",
            "npm" to "exec \"\$(dirname \"\$0\")/node\" \"\$(dirname \"\$0\")/../lib/node_modules/corepack/dist/npm.js\" \"\$@\"",
            "npx" to "exec \"\$(dirname \"\$0\")/node\" \"\$(dirname \"\$0\")/../lib/node_modules/corepack/dist/npx.js\" \"\$@\"",
            "pnpm" to "PNPM_JS=\"\$(dirname \"\$0\")/../dsh-home/profiles/web/node_modules/pnpm/bin/pnpm.cjs\"; [ -f \"\$PNPM_JS\" ] && exec \"\$(dirname \"\$0\")/node\" \"\$PNPM_JS\" \"\$@\"; exec \"\$(dirname \"\$0\")/node\" \"\$(dirname \"\$0\")/../lib/node_modules/corepack/dist/pnpm.js\" \"\$@\"",
        )
        shims.forEach { (name, body) ->
            val f = File(bin, name)
            val want = "#!/system/bin/sh\n# generated by DSH Mobile: forwards to corepack\n$body\n"
            val have = runCatching { if (f.isFile) f.readText() else "" }.getOrDefault("")
            if (have == want && f.canExecute()) return@forEach
            runCatching {
                f.writeText(want)
                f.setExecutable(true, false)
                Log.i(TAG, "node tooling shim written: bin/$name")
            }.onFailure { Log.w(TAG, "node tooling shim failed ($name): ${it.message}") }
        }
        if (!corepackJs.isFile) Log.w(TAG, "corepack.js missing at ${corepackJs.absolutePath}")
        if (!node.isFile) Log.w(TAG, "node missing at ${node.absolutePath}")
        // pnpm 的全局安装目录（dsh 的 PATH 探测器会查这两处）
        File(dshHome(ctx), "local/bin").mkdirs()
        File(dshHome(ctx), ".local/share/pnpm").mkdirs()
        File(dshHome(ctx), ".cache/corepack").mkdirs()
    }

    private fun applyRuntimeOverlays(ctx: android.content.Context, root: File) {
        // ① 与 node-pty 无关的 overlay：pkg 的前端（Termux 仓库 + dpkg 的 node 驱动）。
        //    `pkg` 门脚本只负责 exec node bin/pkg.js；这里保证脚本每次启动都是最新版。
        // 包管理器切换成 Termux 原生 pkg/apt 后，pkg.js / tar / sh 垫片全部删除
        // ② node-pty 真实现：仅当运行时里那份不是原生编译产物时才覆盖。
        val pkgDir = File(root, "lib/node_modules/node-pty")
        // 判定「能不能覆盖」而不是「版本号长什么样」：只要这棵树里没有**原生产物**
        // （.node / prebuilds / build/Release），就说明它是空壳或我们自己的 bridge 版，
        // 可以覆盖；一旦将来 runtime 里出现能用的真 node-pty 预编译，就绝不碰它。
        val hasNative = runCatching {
            pkgDir.walkTopDown().any { f ->
                f.isFile && (f.name.endsWith(".node") || f.path.contains("/prebuilds/") ||
                    f.path.contains("/build/Release/"))
            }
        }.getOrDefault(false)
        if (hasNative) {
            Log.i(TAG, "runtime overlay: node-pty has native artifacts, skipping")
            return
        }
        val files = listOf(
            "lib/node_modules/node-pty/package.json",
            "lib/node_modules/node-pty/lib/index.js",
        )
        for (rel in files) {
            runCatching {
                val bytes = ctx.assets.open("runtime-overlay/$rel").use { it.readBytes() }
                val dst = File(root, rel)
                if (dst.isFile && dst.readBytes().contentEquals(bytes)) return@runCatching
                dst.parentFile?.mkdirs()
                dst.writeBytes(bytes)
                Log.i(TAG, "runtime overlay deployed: $rel")
            }.onFailure { Log.w(TAG, "runtime overlay $rel failed: ${it.message}") }
        }
    }

    /**
     * 每次 App 启动都确认一次 Agent 侧产物（门脚本 / shz / su 闸门 / runtime overlay）。
     *
     * 为什么需要：这些文件以前只在「装运行时」那一步部署；用户升级 APK 后如果运行时
     * 目录已存在，启动流程会跳过安装步骤，新版本新增的门脚本（比如 appshot/record/
     * record）就永远不落地 —— Agent 调 `appshot` 会 command not found。这里做幂等复查，
     * 缺什么补什么（applyAgentGates 内部按文件写，成本毫秒级）。
     */
    fun ensureAgentArtifacts(ctx: android.content.Context) {
        runCatching { applyAgentGates(ctx, engineRoot(ctx)) }
            .onFailure { Log.w(TAG, "ensureAgentArtifacts failed: ${it.message}") }
        // v1.3.63：给已安装的第三方插件打内容锚定补丁（genui 的思考行缺图标）。
        DshPluginPatches.ensure(ctx)
        // v2.5.43：内置 dshmarket / dsh-mnemon / dsh-genui（离线可用；agent 可删可改）。
        runCatching { ensureBundledPlugins(ctx) }
            .onFailure { Log.w(TAG, "ensureBundledPlugins failed: ${it.message}") }
    }

    /** 历史上内置过、现已不再随包发行的社区插件（仅用于清理残留与修 package.json）。 */
    private val LEGACY_BUNDLED_PLUGINS = listOf("dshmarket", "dsh-mnemon", "dsh-genui")

    /** 关掉内置插件自动落地的标记文件（agent 可 touch 它，或直接删 node_modules 目录）。 */
    private fun bundledPluginsMarker(home: File) = File(home, ".dsh-bundled-off")

    /**
     * 只保留 pnpm（dsh-market 安装时需要它，离线可用）。
     *
     * 社区插件**不再随包内置**：之前内置 dshmarket/dsh-mnemon/dsh-genui 会让 dsh
     * 把它们当成“已装但来源不明”的包，插件识别与升级都出问题。现在这里只做两件事：
     *  1. 清掉历史版本解出来的这三个目录（升级后用干净的 node_modules）；
     *  2. 缺 pnpm 时从 assets/bundled-plugins.zip（现在只含 pnpm）解出来。
     */
    fun ensureBundledPlugins(ctx: android.content.Context) {
        val home = File(ctx.filesDir, "dsh-home")
        val profile = File(home, "profiles/web")
        val modules = File(profile, "node_modules")
        if (bundledPluginsMarker(home).exists()) {
            Log.i(TAG, "bundled plugins disabled by marker, skip")
            return
        }
        if (!modules.isDirectory) modules.mkdirs()
        LEGACY_BUNDLED_PLUGINS.forEach { name ->
            val dir = File(modules, name)
            if (dir.exists() && dir.deleteRecursively()) Log.i(TAG, "removed legacy bundled plugin: $name")
        }
        repairPackageJson(profile)
        if (File(modules, "pnpm").isDirectory) return
        runCatching {
            ctx.assets.open("bundled-plugins.zip").use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val rel = e.name.removePrefix("node_modules/")
                        if (rel.isEmpty() || rel == e.name) continue
                        val out = File(modules, rel)
                        if (e.isDirectory) { out.mkdirs(); continue }
                        if (out.exists()) continue
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }.onSuccess { Log.i(TAG, "pnpm toolchain installed into node_modules") }
            .onFailure { Log.w(TAG, "pnpm extract failed: ${it.message}") }
    }

    /** 清理历史版本写坏的 profile package.json（只清理自己插入的三条，并校验 JSON 通过才落盘）。 */
    private fun repairPackageJson(profile: File) {
        val pkg = File(profile, "package.json")
        if (!pkg.exists()) return
        runCatching {
            var text = pkg.readText()
            val before = text
            LEGACY_BUNDLED_PLUGINS.forEach { name ->
                text = text.replace(Regex("\n    \"$name\": \"[^\"]*\",?"), "")
            }
            text = text.replace(Regex(",\\s*\\}"), "}").replace(Regex("\\{\\s*,"), "{")
            if (text != before) {
                runCatching { org.json.JSONObject(text) }.onSuccess {
                    pkg.writeText(text)
                    Log.i(TAG, "package.json repaired (removed stray bundled-plugin entries)")
                }.onFailure { Log.w(TAG, "package.json still invalid after repair: ${it.message}") }
            }
        }.onFailure { Log.w(TAG, "package.json repair failed: ${it.message}") }
    }

    /**
     * 由 applyAgentGates 部署到 engine/bin 的门脚本清单（v1.3.31 起集中定义）。
     * 新加门脚本只改这里，部署与完整性探针共用同一份，避免探针漏检新脚本。
     */
    private val AGENT_GATES = listOf(
        "_dsh_http.sh", "notify", "scr", "say", "loop", "fast",
        "dsh-update", "shot", "appshot", "native", "media", "docs",
    )

    /**
     * 特权门脚本：只有 ROOT / SHIZUKU 模式下才部署。
     * NORMAL 下它们必然失败（`screenrecord` 要 shell uid），留在 bin 里只会让 agent
     * 撞墙 —— 所以「不部署」而不是「部署后报错」。切换回 NORMAL 时同步删除。
     */
    private val AGENT_GATES_PRIV = listOf("record")

    /**
     * 修复移动端界面（设置 → 一键还原 UI）。
     *
     * 语义：**不是**退回桌面版 WebUI，而是把被改坏的 mobile-ui 恢复成 App 内置的那一份。
     * 做法：删掉 dsh-home/android-plugins/mobile-ui/ 下已部署的文件，重启引擎时按
     * assets 重新部署；WebUI 侧的面板/抽屉状态由插件自己按默认值重建。
     *
     * 返回是否真的删到了东西（用于给用户一个诚实的回执）。
     */
    fun repairMobileUi(ctx: android.content.Context): Boolean = runCatching {
        val dir = java.io.File(java.io.File(engineRoot(ctx), "dsh-home"), "android-plugins/mobile-ui")
        val existed = dir.exists()
        if (existed) dir.deleteRecursively()
        Log.i(TAG, "repairMobileUi: existed=$existed deleted=${!dir.exists()} path=${dir.path}")
        existed && !dir.exists()
    }.onFailure { Log.w(TAG, "repairMobileUi failed: ${it.message}") }.getOrDefault(false)

    /** 权限位（用于诊断日志，如 0600/0755）。 */
    private fun octalMode(f: File): String = runCatching {
        val perms = java.nio.file.Files.getPosixFilePermissions(f.toPath())
        val order = listOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.GROUP_READ,
            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
            java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
            java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE,
        )
        order.chunked(3).joinToString("") { (r, w, x) ->
            val v = (if (perms.contains(r)) 4 else 0) +
                (if (perms.contains(w)) 2 else 0) +
                (if (perms.contains(x)) 1 else 0)
            v.toString()
        }
    }.getOrDefault("?")

    private fun applyAgentGates(ctx: android.content.Context, root: File) {
        val bindir = File(root, "bin").apply { mkdirs() }
        try {
            // ── 门脚本：从 assets/gates 部署（v1.2.50）─────────────────────────
            // 此前 notify/scr/say 内嵌在 Kotlin 字符串里，每次调用 `exec node -e fetch`
            // 都要冷启一个 Node（实测 ~150ms/次 + 数十 MB 峰值内存；内存高水位时
            // scr dump 挂 >60s）。现改为 assets 里的纯 bash 脚本（配 _dsh_http.sh
            // 共享 HTTP 客户端），零 Node 冷启 + 内建超时。
            // 放在 assets 而非 Kotlin 字符串：避免 bash/Kotlin 双层转义（易错且难维护）。
            // 门脚本 shebang 用 #!@DSH_BASH@ 占位符：部署时替换为引擎 bash 的实际路径。
            // 必须用 bash（/dev/tcp 是 bash 特性；/system/bin/sh 是 toybox，不支持）。
            val bashPath = File(root, "bin/bash").absolutePath
            val libDir = File(root, "lib").absolutePath
            val broken = mutableListOf<String>()

            val elevated = Privilege.isElevated(ctx)
            if (!elevated) AGENT_GATES_PRIV.forEach { runCatching { File(bindir, it).delete() } }
            (AGENT_GATES + if (elevated) AGENT_GATES_PRIV else emptyList()).forEach { name ->
                val dst = File(bindir, name)
                runCatching {
                    val text = ctx.assets.open("gates/$name").use { it.readBytes().toString(Charsets.UTF_8) }
                    // ⚠️ CRLF 剥离（v1.2.52 事故）：门脚本曾被以 CRLF 打进 APK，
                    // 首行变成 `#!/system/bin/sh\r` → 内核按字面找解释器，报
                    // "bad interpreter: No such file or directory"（误导性地像文件丢失）。
                    // 三个门脚本 100% 失效，而 notify/scr/say 是种子要求 agent 必用的能力。
                    // 根因是工作区文件在 .gitattributes 生效前就已以 CRLF 检出；此处
                    // 无条件剥离，作为不依赖构建环境行尾的兜底防线。
                    val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
                        .replace("@DSH_BASH@", bashPath).replace("@DSH_LIBDIR@", libDir)
                    // ⚠️ v1.3.31 权限事故：$PREFIX/bin 里 bootstrap 留下的同名条目可能是
                    // 指向 toybox 的符号链接；直接 writeText 会写穿到 toybox 本体（或失败）。
                    // 必须先删除再写。且 Java 在 Android 上新建文件是 0600 —— 不 chmod 的话
                    // native/appshot/shot/record 这些新脚本全都不可执行。
                    if (runCatching { java.nio.file.Files.isSymbolicLink(dst.toPath()) }.getOrDefault(false) || !dst.isFile) dst.delete()
                    dst.writeText(normalized)
                    dst.setReadable(true, false)
                    dst.setExecutable(true, false)
                    if (!dst.canExecute()) Log.w(TAG, "agent gate $name is NOT executable after chmod")
                }.onFailure { Log.w(TAG, "agent gate $name deploy failed: ${it.message}") }
            }
            Log.i(TAG, "agent gates deployed (${AGENT_GATES.size + if (elevated) AGENT_GATES_PRIV.size else 0}, " +
                "elevated=$elevated): ${(AGENT_GATES + if (elevated) AGENT_GATES_PRIV else emptyList()).joinToString(" ")}")
            // v1.8.2（点击链路提案 P1-4）：PP-OCRv6 tiny 兜底 —— 脚本放 bin、模型放 share。
            // 只部署 tiny（6MB）；python/onnxruntime 由 /ocr?engine=ppocr6&ensure=1 显式安装。
            runCatching {
                val share = File(root, "share/ppocr6").apply { mkdirs() }
                val pyDir = File(share, "py").apply { mkdirs() }
                listOf("onnxruntime.py", "libortshim.so", "ppocr6_worker.py").forEach { name ->
                    val bytes = ctx.assets.open("ppocr6/py/$name").use { it.readBytes() }
                    val dst = File(pyDir, name)
                    if (!dst.isFile || dst.length() != bytes.size.toLong()) dst.writeBytes(bytes)
                    dst.setReadable(true, false)
                    if (name.endsWith(".so")) dst.setExecutable(true, false)
                }
                listOf("det_tiny.onnx", "rec_tiny.onnx", "rec_tiny.yml").forEach { name ->
                    val bytes = ctx.assets.open("ppocr6/$name").use { it.readBytes() }
                    val dst = File(share, name)
                    if (!dst.isFile || dst.length() != bytes.size.toLong()) dst.writeBytes(bytes)
                }
                val scriptText = ctx.assets.open("ppocr6/ppocr6").use { it.readBytes().toString(Charsets.UTF_8) }
                    .replace("\r\n", "\n").replace("\r", "\n")
                val dst = File(bindir, "ppocr6")
                if (runCatching { java.nio.file.Files.isSymbolicLink(dst.toPath()) }.getOrDefault(false) || !dst.isFile) dst.delete()
                if (!dst.isFile || dst.readText() != scriptText) dst.writeText(scriptText)
                dst.setReadable(true, false)
                dst.setExecutable(true, false)
                Log.i(TAG, "ppocr6 deployed: script=${dst.length()}B models=${share.list()?.size ?: 0}")
            }.onFailure { Log.w(TAG, "ppocr6 deploy failed: ${it.message}") }
            // planner-daemon 已于 v1.2.167 移除（用户要求：AI 用自己的工具做无障碍）

    // LAN 反向代理脚本（v1.2.160）：与 daemon 同一套部署方式（assets → tools）。
    // 为什么用 node 而不是 app 内手写 HTTP 代理：chunked/压缩/Cookie/特殊 URL
    // （plugins/??a.js,b.js）/WebSocket 升级，手写全都要自己处理 —— 实测反复出
    // 问题（白屏 / 0 字节资源 / 满屏乱码）。node 的 http 模块天然处理这些。
    runCatching {
                val toolsDir = File(dshHome(ctx), "tools").apply { mkdirs() }
                val src = ctx.assets.open("tools/lan-web-proxy.mjs").use { it.readBytes().toString(Charsets.UTF_8) }
                val dst = File(toolsDir, "lan-web-proxy.mjs")
                // 同插件：读不了就删掉重建（重装后 uid 变化导致 EACCES 的解法）
                val stale = !dst.isFile ||
                    runCatching { dst.readText() != src }.getOrDefault(true)
                if (stale) {
                    runCatching { dst.delete() }
                    if (!dst.isFile || runCatching { dst.readText() }.getOrNull() != src) {
                        dst.writeText(src)
                    }
                }
                dst.setReadable(true, false)
                dst.setReadable(true, true)
    }.onFailure { Log.w(TAG, "lan-web-proxy deploy failed: ${it.message}") }

            listOf("notify", "scr", "say", "loop", "fast", "dsh-update").forEach { File(bindir, it).setExecutable(true, false) }
            // 已下线的门脚本（v1.3.0 USB 桥回退）：清掉设备上的旧副本
            // v2.5.43：proot 子系统移除后，老设备上残留的 linux 门脚本也一并清掉
            listOf("pc", "linux").forEach { runCatching { File(bindir, it).delete() } }
            File(bindir, "_dsh_http.sh").setReadable(true, false)

            // 部署后自检（v1.2.52）：逐个校验首行不含 \r 且指向存在的解释器。
            // 失败不静默——写进 engine.log 并给出明确原因，避免「看起来像文件丢了」的误判。
            (AGENT_GATES + if (elevated) AGENT_GATES_PRIV else emptyList()).forEach { name ->
                val f = File(bindir, name)
                val head = runCatching {
                    f.inputStream().use { ins ->
                        val buf = ByteArray(128)
                        val n = ins.read(buf)
                        String(buf, 0, maxOf(n, 0), Charsets.UTF_8).lineSequence().first()
                    }
                }.getOrNull().orEmpty()
                when {
                    !f.isFile -> broken += "$name (missing)"
                    !f.canExecute() -> broken += "$name (mode ${octalMode(f)} not executable)"
                    head.contains('\r') -> broken += "$name (CRLF in shebang: ${head.take(30)})"
                    head.startsWith("#!") && !File(head.removePrefix("#!").trim()).isFile ->
                        broken += "$name (interpreter missing: ${head.removePrefix("#!").trim()})"
                }
            }
            if (broken.isNotEmpty()) {
                Log.w(TAG, "agent gates BROKEN: ${broken.joinToString("; ")}")
            } else {
                Log.i(TAG, "agent gates: ${AGENT_GATES.size} scripts deployed, executable (bridge :3083)")
            }

            // ── Android 编译桩 jar 预置（v1.2.107）────────────────────────────
            // 动机：AGENTS.md 原先教 Agent 自己跑 `extract-android-stub.py` 去提取
            // android.jar，但那是**开发机上的脚本** —— 设备上既没有该脚本、也没有
            // git 仓库可 pull，Agent 照着做只会扑空（主人实测反馈「那个 py 文件似乎
            // 不存在」）。现在改为随包发布、启动即落位，Agent 直接 `-cp ~/android.jar`。
            // 幂等：按字节数比对（体积固定，内容变化必然反映在长度上），命中就跳过。
            runCatching {
                val jar = File(EngineConfig.dshHome(ctx), "android.jar")
                val want = ctx.assets.open("android-stub/android.jar")
                    .use { it.available().toLong() }
                if (!jar.isFile || jar.length() != want) {
                    ctx.assets.open("android-stub/android.jar").use { ins ->
                        jar.outputStream().use { ins.copyTo(it) }
                    }
                    jar.setReadable(true, false)
                    Log.i(TAG, "android.jar staged: ${jar.length()} bytes (expected $want)")
                }
            }.onFailure { Log.w(TAG, "android.jar staging failed: ${it.message}") }

        } catch (e: Exception) {
            Log.w(TAG, "agent gates: ${e.message}")
        }
    }

    private const val TAG = "EngineConfig"


    /**
     * npm 生态默认镜像。corepack / npm / pnpm 在 runtime 内无任何 .npmrc，
     * 默认 registry.npmjs.org 在国内网络下经常超时（实测反馈「pnpm 连不上」）。
     * 淘宝源为国内通用镜像，可被进程环境变量覆盖（见 buildEnv 的 filterNot 守卫）。
     */
    private const val NPM_REGISTRY_MIRROR = "https://registry.npmmirror.com"
}
