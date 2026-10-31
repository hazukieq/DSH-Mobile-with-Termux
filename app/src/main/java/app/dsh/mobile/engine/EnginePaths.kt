package app.dsh.mobile.engine

import android.util.Log
import java.io.File
import java.nio.file.Files

/**
 * 引擎目录拓扑与用户数据位置（从 EngineConfig 拆出，Phase 3）。
 *
 * 只负责「路径怎么算、数据放哪儿、外置目录怎么软链」；运行时启动、环境变量、
 * Android overlay 各自有独立协作者。EngineConfig 保留原公开入口做薄委托，
 * 外部调用点不需要改动。
 */
internal object EnginePaths {

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
        Log.i(EngineConfig.TAG, "publicDataRoot probe: docs=$docsProbe storageManager=$manager mkdirs=${runCatching { docs.mkdirs() }.getOrDefault(false)}")
        if (docsProbe) {
            Log.i(EngineConfig.TAG, "publicDataRoot: ${docs.absolutePath} (Documents, survives uninstall)")
            return@runCatching docs
        }
        Log.w(EngineConfig.TAG, "publicDataRoot: Documents unusable (grant All Files Access in Settings; storageManager=$manager), trying app external")

        // 回退：App 专属外部目录（零权限，但卸载清除）
        val ext = ctx.getExternalFilesDir(null)
        if (ext != null) {
            val root = File(ext, "dshdata")
            if ((root.exists() || root.mkdirs()) && isDirUsable(root)) {
                Log.i(EngineConfig.TAG, "publicDataRoot: ${root.absolutePath} (app external, cleared on uninstall)")
                return@runCatching root
            }
            Log.w(EngineConfig.TAG, "publicDataRoot: app external not readable (FUSE?)")
        }
        null
    }.getOrElse {
        Log.w(EngineConfig.TAG, "publicDataRoot failed: ${it.message}")
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
                    Log.w(EngineConfig.TAG, "$name: removing stale symlink (no public storage access)")
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
            EngineConfig.TAG,
            "externalise $name: pub=${pub.absolutePath} link=$isLink privExists=${priv.exists()} privFiles=${runCatching { priv.list()?.size }.getOrNull()}",
        )

        // ⚠️ v1.2.110：pubRoot 变更（app external → Documents）后，软链还指向旧位置、
        // 数据也还躺在旧位置 —— 必须「重指向 + 搬数据」两步都做，否则会话继续写旧
        // 位置，卸载重装依然全丢（用户反馈「删了应用重新下回来对话无法恢复」）。
        // ① 重指向：软链目标与当前 pub 不一致 → 删旧软链重建（数据在目标目录里，删链不删数据）
        if (isLink) {
            val target = runCatching { Files.readSymbolicLink(priv.toPath()).toString() }.getOrNull()
            if (target != pub.absolutePath) {
                Log.i(EngineConfig.TAG, "$name: relink $target -> ${pub.absolutePath}")
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
                        Log.i(EngineConfig.TAG, "migrated legacy $name -> ${pub.absolutePath} ($copied files)")
                    } else {
                        Log.w(EngineConfig.TAG, "legacy migration of $name incomplete; keeping legacy copy")
                    }
                }.onFailure { Log.w(EngineConfig.TAG, "legacy migration of $name failed: ${it.message}") }
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
                .onFailure { Log.w(EngineConfig.TAG, "$name: su chown failed: ${it.message}") }
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
                Log.w(EngineConfig.TAG, "$name: took ownership of $pub (uid changed after reinstall) — external storage OK now")
            } else {
                Log.w(EngineConfig.TAG, "$name: public storage NOT writable ($pub) — keeping data internal (no symlink)")
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
        Log.i(EngineConfig.TAG, "externalise $name: privHasData=$privHasData (list=${runCatching { priv.list()?.joinToString() }.getOrNull()})")
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
                    Log.w(EngineConfig.TAG, "migration of $name incomplete; keeping private copy")
                    return false
                }
                priv.deleteRecursively()
                Log.i(EngineConfig.TAG, "migrated dsh-home/$name -> ${pub.absolutePath} ($copied files)")
            }.onFailure {
                Log.w(EngineConfig.TAG, "migration of dsh-home/$name failed: ${it.message}", it)
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
                        Log.w(EngineConfig.TAG, "$name: private dir still holds data ($src > $dst); not linking")
                        return false
                    }
                }
                priv.deleteRecursively()
            }
            if (!priv.exists()) Files.createSymbolicLink(priv.toPath(), pub.toPath())
            Log.i(EngineConfig.TAG, "dsh-home/$name -> ${pub.absolutePath} (symlink)")
            true
        }.getOrElse {
            Log.w(EngineConfig.TAG, "symlink for dsh-home/$name failed: ${it.message}; using private")
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
            Log.i(EngineConfig.TAG, "user data external: sessions=$a workspaces=$b")
        }.onFailure { Log.w(EngineConfig.TAG, "externalise failed: ${it.message}") }
    }

    /** $DSH_HOME（凭证、会话、配置）—— 保持私有；sessions/ 由 ensureUserDataExternal 外置 */
    fun dshHome(ctx: android.content.Context): File =
        File(ctx.filesDir, "dsh-home").apply { mkdirs() }

    /** 默认工作区根（$DSH_HOME/workspaces） */
    fun workspaces(ctx: android.content.Context): File =
        File(EnginePaths.dshHome(ctx), "workspaces").apply { mkdirs() }

    /**
     * 用户数据在公开目录中的位置；null = 落在应用私有目录（卸载会删除）。
     * 文案由 UI 层按语言给出，此处只回路径。
     */
    fun userDataLocation(ctx: android.content.Context): String? =
        publicDataRoot(ctx)?.absolutePath

    fun tmpDir(ctx: android.content.Context): File =
        File(EngineConfig.engineRoot(ctx), "tmp").apply { mkdirs() }
}
