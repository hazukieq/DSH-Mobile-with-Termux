package app.dsh.mobile.engine

import app.dsh.mobile.R

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Node 运行时安装器。
 *
 * 两条安装路径（优先级从高到低）：
 *  1. assets/runtime.zip —— CI 构建时注入的离线包（推荐，无网络依赖）
 *  2. MANIFEST.json 声明的远程 URL —— 开发期热更新，强制 SHA-256 校验
 *
 * 安装 = 解压到 filesDir/engine + 恢复可执行位。
 * zip 不保存 Unix 权限，因此解压后对 bin/ 下所有文件 chmod 755；
 * targetSdk 28 下 SELinux 允许对 filesDir 内文件 execve，无需 jniLibs 伪装。
 */
class RuntimeInstaller(private val ctx: Context) {

    data class Manifest(val version: String, val url: String?, val sha256: String?)

    private val root get() = EngineConfig.engineRoot(ctx)
    private val stampFile get() = File(root, ".runtime-version")

    fun readManifest(): Manifest {
        val raw = ctx.assets.open("runtime/MANIFEST.json").bufferedReader().use { it.readText() }
        val obj = JSONObject(raw)
        return Manifest(
            version = obj.getString("version"),
            url = obj.optString("url").takeIf { it.isNotEmpty() },
            sha256 = obj.optString("sha256").takeIf { it.isNotEmpty() },
        )
    }

    /** 确保运行时就绪；已安装且版本匹配且关键资产完整则跳过。
     *  @param onProgress 解压进度 0f..1f（仅解压阶段有值；频繁调用方自行节流）
     *
     *  m1.13 完整性校验：m1.12 事故实锤——覆盖升级后 @deepseek-ai 目录只剩空壳
     *  （JS 文件未落地）但 .runtime-version 已写成功，「engine 存在+版本匹配」即跳装
     *  → MODULE_NOT_FOUND 反复重启。故改为【版本匹配 + 关键资产存在】双重条件，
     *  任一关键资产缺失即视为安装不完整，删除重装。 */
    fun ensureInstalled(onProgress: (Float) -> Unit = {}) {
        // 2026-10-18 架构切换：包名 = com.termux 后，运行时 = Termux 官方 bootstrap
        // （<files>/usr）+ 内置适配版 dsh 引擎（不走 npm）。
        BootstrapInstaller.ensure(ctx, onProgress)
        if (true) return
        // ── 热更新中断恢复（2026-10-18）──────────────────────────────────────
        // apply/rollback 中间被系统杀进程会留下半交换的目录布局（engine 缺失、
        // engine.old 还在…）。必须先把它修成可启动状态，再做版本判断 —— 否则下面
        // 的解压重装会把用户的运行时目录结构彻底改写。幂等、极廉价。
        RuntimeUpdater.recoverIfNeeded(ctx)

        val manifest = readManifest()

        // ── 热更新保护（2026-10-18）──────────────────────────────────────────
        // 背景：agent/用户可以在运行时把新的 runtime 树换进 engine/（RuntimeUpdater），
        // 换完 engine/.runtime-version 与 assets/runtime/MANIFEST.json 的版本**必然不同**
        // —— 旧逻辑据此判定「版本变了，该重装」，下一次 App 启动就会把热更新静默回退成
        // APK 内置版本（用户视角：明明更新成功了，重启又变回去）。
        //
        // 因此：带 .runtime-source=external 标记的树不再参与和各 assets 的版本比对，
        // 只做完整性探针（该探针本来就是防「半解压空壳」的，热更新的树同样需要）。
        // 探针失败 = 这棵外部树已经不可用：清掉标记落回下面的 assets 重装兜底，
        // 保证「宁可回到出厂运行时，也不留一个起不来的引擎」。
        if (File(root, RuntimeUpdater.SOURCE_MARKER).isFile) {
            if (isRootComplete()) {
                Log.i(
                    TAG,
                    "runtime ${stampVersion()} is externally installed (hot update); " +
                        "skipping asset reinstall (assets ${manifest.version})",
                )
                return
            }
            Log.w(TAG, "externally installed runtime is incomplete; falling back to asset install")
            File(root, RuntimeUpdater.SOURCE_MARKER).delete()
        }

        if (EngineConfig.nodeBin(ctx).exists() &&
            stampFile.exists() && stampFile.readText().trim() == manifest.version &&
            isRootComplete()
        ) {
            Log.i(TAG, "runtime ${manifest.version} already installed (assets verified)")
            return
        }
        if (isRootComplete()) {
            // 版本变了才重装，属正常升级
        } else {
            Log.w(TAG, "runtime install incomplete (missing assets); forcing reinstall")
        }
        install(manifest, onProgress)
    }

    /** engine/.runtime-version 的当前值（诊断日志用；不抛异常） */
    private fun stampVersion(): String =
        runCatching { if (stampFile.isFile) stampFile.readText().trim() else "?" }
            .getOrDefault("?")

    /**
     * 运行时根目录完整性探针（任意目标目录版）。
     *
     * 原实现写死 [EngineConfig.engineRoot]，热更新需要对 engine.staging / engine.new
     * 做同样的探针（解压完先验一遍再发布，别把半截树 swap 进 engine/）。
     * 判定内容与原实现**逐条一致**，只是把根目录改成参数：
     *  - 引擎入口 bin.js
     *  - bash 依赖的 readline SONAME 别名
     *  - node 本体可执行位（restoreExecBits 之后应为 true）
     *  - @deepseek-ai 作用域下至少 MIN_DSH_AI_FILES 个真实文件（空壳检测）
     */
    fun probeRootComplete(dir: File): Boolean {
        // 1) 引擎入口 bin.js（MODULE_NOT_FOUND 的直接案发现场）
        if (!File(dir, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile) return false
        // 2) Termux bootstrap 的 bash 必须在位（原 readline SONAME 探针随自制运行时退役）
        if (!File(dir, "bin/bash").isFile) return false
        // 3) node 本体可执行位
        if (!File(dir, "bin/node").canExecute()) return false
        // 4) @deepseek-ai 作用域下至少要有一批真实文件（空壳=部分解压）
        val dshAi = File(dir, "lib/node_modules/@deepseek-ai")
        val fileCount = dshAi.walkTopDown().filter { it.isFile }.count()
        if (fileCount < MIN_DSH_AI_FILES) return false
        return true
    }

    /**
     * 解压一份 runtime.zip 到 target（含解压后的可执行位恢复）。
     *
     * 抽出来的动机：热更新（[RuntimeUpdater]）暂存的包与 APK 内置包是**同一种格式**，
     * 里面同样有 `LINK:` 注释去重条目、同样不保存 Unix 权限位。让热更新复用这里
     * 而不是复制一份解压逻辑，才能保证两条安装路径的语义永远一致（否则会出现
     * 「内置包装得上、热更新包装上却 CANNOT LINK」这类割裂）。
     */
    fun extractZip(zip: File, target: File, onProgress: (Float) -> Unit = {}) {
        target.mkdirs()
        unzip(zip, target, onProgress)
        restoreExecBits(target)
    }

    private fun isRootComplete(): Boolean = probeRootComplete(root)

    private fun install(manifest: Manifest, onProgress: (Float) -> Unit) {
        Log.i(TAG, "installing runtime ${manifest.version}")
        installing = true
        try {
            val assetZip = File(ctx.cacheDir, "runtime.zip")
            try {
                // 【v1.2.97】空间预检（用户邮件反馈：覆盖升级后引擎反复崩溃重启）。
                // 覆盖升级 = 旧 runtime（~450MB）还在盘上 + zip 缓存（~170MB）+ 新解压
                // （~450MB）峰值同卷并存，空间紧张的设备解压半途 ENOSPC → 部分文件
                // 落地 → isRootComplete 探针失败 → 删了再装 → 更紧 → **无限崩溃重启**，
                // 而"回滚旧版"因旧 runtime 已装好且版本匹配（跳过重装）反而不受影响。
                // 与其让用户看玄学退避，不如一开始就给出明确错误。
                fun mb(v: Long) = v / (1L shl 20)
                fun requireFree(needMb: Long) {
                    val usable = root.usableSpace
                    check(usable >= needMb * (1L shl 20)) {
                        ctx.getString(
                            R.string.error_runtime_no_space,
                            needMb, mb(usable),
                        )
                    }
                }
                requireFree(600)   // 覆盖升级峰值：旧 runtime + zip 缓存 + 新解压并存
                ctx.assets.open("runtime.zip").use { input ->
                    assetZip.outputStream().use { input.copyTo(it) }
                }
                // 解压前再校验一次：此时 zip 真实大小已知
                requireFree(assetZip.length() / (1L shl 20) + 400)
            } catch (e: Exception) {
                // 路径 2：远程下载（必须带 SHA-256）
                val url = manifest.url ?: throw IllegalStateException(
                    ctx.getString(R.string.error_runtime_no_source), e,
                )
                downloadTo(url, assetZip)
                manifest.sha256?.let { expected ->
                    val actual = sha256(assetZip)
                    check(actual.equals(expected, ignoreCase = true)) {
                        "Runtime checksum mismatch: expected=$expected actual=$actual"
                    }
                }
            }

            // v1.2.21 事故修复：原 root.deleteRecursively() 会把 engine/ 整个删光，
            // extensions/（用户下载的全家扩展）一起陪葬；且删除 70MB 目录耗时较长，
            // 与用户点扩展下载并发 → 刚发布的扩展目录被删到只剩 lib → rename 报
            // 「扩展目录发布失败」。现在按 zip 实际顶层目录（bin/etc/lib/share/usr）
            // 精确替换，extensions/ 等用户资产永不触碰。
            root.mkdirs()
            val topDirs = java.util.zip.ZipFile(assetZip).use { zf ->
                zf.entries().asSequence()
                    .map { it.name.substringBefore('/') }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
            topDirs.forEach { top ->
                File(root, top).deleteRecursively()
            }

            // 解压 + 恢复可执行位（与热更新共用 extractZip，见其注释）
            extractZip(assetZip, root, onProgress)
            stampFile.writeText(manifest.version)
            // 出厂安装的树没有 .runtime-source 标记：语义上「来源=assets」。
            // 若上一棵树是热更新（external），走到这里说明它已被判定不可用并回退，
            // 标记在 ensureInstalled 里已删除，这里再保险清一次。
            File(root, RuntimeUpdater.SOURCE_MARKER).delete()
            assetZip.delete()
            Log.i(TAG, "runtime installed at $root (kept top dirs: ${topDirs.joinToString()})")
        } finally {
            installing = false
        }
    }

    private fun downloadTo(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        // getInputStream 隐式触发连接；非 2xx 视为失败
        val code = conn.responseCode
        check(code in 200..299) { "Download failed with HTTP $code: $url" }
        conn.inputStream.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    private fun unzip(zip: File, target: File, onProgress: (Float) -> Unit) {
        // 预扫 central directory 拿总未压缩字节（ZipInputStream 流式读时 size 可能为 -1，
        // ZipFile 走 central directory 是精确的）→ 真实确定性进度而非假转圈
        var total = 0L
        java.util.zip.ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) total += entries.nextElement().size
        }
        var done = 0L
        // 别名去重（v1.2.98）：Termux 的 SONAME 别名（libicu.so.78.3 → libicu.so.78
        // → libicu.so）在打包时若各自存一份，仅 ICU 一家就浪费 ~95MB（实测全库 89.4MB）。
        // 打包侧改为「只存一份数据，其余别名条目的 comment 写 LINK:<本体路径>」，
        // 这里解压时**复制成实体文件**还原（linker 不认链接，见 materialiseLink 的说明）。
        //
        // ⚠️ 必须**两阶段**：zip 里别名条目可能排在本体之前（实测 libz.so.1 排在
        // libz.so 之前），若边解压边建链，链接时本体还不存在 → 变成 0 字节空文件
        // （正是「CANNOT LINK ... libz.so.1 not found」那类故障的成因）。
        // 所以先落全部本体，再统一建链接 —— 与 ExtensionManager 的 deb 解包同一套路。
        val linkTargets = readLinkTargets(zip)
        if (linkTargets.isNotEmpty()) {
            Log.i(TAG, "unzip: ${linkTargets.size} deduplicated alias entries (copied)")
        }
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(target, entry.name).canonicalFile
                // 防 zip-slip：解压目标必须落在 engineRoot 内
                check(out.path.startsWith(target.canonicalPath)) { "zip-slip: ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else if (linkTargets.containsKey(entry.name)) {
                    // 别名条目：zip 里数据为空，跳过写入，阶段 2 统一建链
                    out.parentFile?.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { done += zis.copyTo(it) }
                    if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                }
                zis.closeEntry()
            }
        }
        // 阶段 2：本体已全部落地，建硬链接（失败自动回退复制）
        linkTargets.forEach { (alias, linkTo) ->
            materialiseLink(target, linkTo, File(target, alias).canonicalFile)
        }
    }

    /** 别名条目表：条目 comment 形如 `LINK:lib/libicu.so.78.3` → 该条目只需建硬链接 */
    private fun readLinkTargets(zip: File): Map<String, String> {
        val map = HashMap<String, String>()
        java.util.zip.ZipFile(zip).use { zf ->
            val it = zf.entries()
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                val c = e.comment ?: continue
                if (c.startsWith(LINK_PREFIX)) map[e.name] = c.removePrefix(LINK_PREFIX)
            }
        }
        return map
    }

    /**
     * 还原别名（v1.2.98/v1.2.99 定稿）：**复制成实体文件**。
     *
     * ## 为什么最终只能复制（三轮实测的结论）
     * 打包侧把内容相同的 SONAME 别名去重（zip 里只存一份数据，省 33MB APK），
     * 解压侧必须把别名还原成**真实文件**，因为 Android 的 linker 在 app 域
     * **既不认硬链接也不认符号链接**：
     *
     * - 硬链接：SELinux 直接拒绝
     *   `avc: denied { link } ... scontext=u:r:untrusted_app_27 tclass=file`
     * - 符号链接：能创建，但 **linker 不跟随** →
     *   `CANNOT LINK EXECUTABLE ".../bin/node": library "libz.so.1" not found`
     *   （`libz.so.1 -> libz.so` 明明存在却找不到，引擎完全起不来）
     *
     * 这也解释了 collect-termux-runtime.sh 里那句注释的原意 ——
     * 「Android SELinux 禁 symlink/link()，必须 cp 出普通文件别名」。
     *
     * ## 结果
     * APK 体积省下来了（156→124MB）；解压后磁盘占用与优化前相同（~582MB）。
     * 体积与磁盘不可兼得，优先保 APK（下载/分享成本更直观），磁盘靠用户清理。
     */
    private fun materialiseLink(root: File, linkTo: String, dest: File) {
        val src = File(root, linkTo).canonicalFile
        if (!src.isFile) {
            Log.w(TAG, "alias source missing: $linkTo (for ${dest.name})")
            return
        }
        runCatching {
            if (dest.exists()) dest.delete()
            src.copyTo(dest, overwrite = true)
        }.onFailure { Log.w(TAG, "alias copy failed for ${dest.name}: ${it.message}") }
    }

    /**
     * 恢复可执行位（zip 不保存 Unix 权限，解压后一律是 0644）。
     *
     * ## 为什么不能只扫 bin/ 和 usr/bin/（v1.2.100 修）
     * AI 自查发现 `grep` / `glob` 工具报
     * 「ripgrep provider failure」，但 bash 里 `rg` 正常 —— 因为工具走的**不是**
     * PATH 里的 `engine/bin/rg`，而是 npm 包内的副本：
     *
     *     lib/node_modules/@vscode/ripgrep-android-arm64/bin/rg   ← 权限 0600，spawn 时 EACCES
     *     engine/bin/rg                                           ← 正常（旧逻辑扫到了）
     *
     * `dsh-tool-fs-search` 的 `resolveRgPath()` 从 `@vscode/ripgrep` 解析路径，
     * 拿到的是前者。旧实现只扫两个固定目录 → 漏掉 node_modules 里的可执行文件。
     *
     * ## 现在的做法
     * ① 固定目录（bin / usr/bin）全量置可执行 —— 保持原有语义
     * ② 全树扫描 node_modules 下的 bin 目录与已知可执行名 —— 覆盖包内副本
     * ③ 只对**确实是 ELF 或脚本**的文件动手（读前 4 字节），避免给 .md/.json 加 x 位
     */
    private fun restoreExecBits(root: File) {
        fun grant(f: File) {
            runCatching {
                f.setExecutable(true, false)
                f.setReadable(true, false)
            }
        }
        // ① 固定目录
        listOf("bin", "usr/bin").forEach { dir ->
            File(root, dir).takeIf { it.isDirectory }?.listFiles()?.forEach { grant(it) }
        }
        // ② node_modules 下的 bin/ 目录（ripgrep / esbuild / 各类 CLI 副本都在这）
        val nm = File(root, "lib/node_modules")
        if (!nm.isDirectory) return
        nm.walkTopDown()
            .maxDepth(6)
            .filter { it.isFile }
            .filter { f ->
                val p = f.invariantSeparatorsPath
                // 只处理 bin/ 目录内的，或已知需要执行位的名字
                p.contains("/bin/") || f.name in EXECUTABLE_NAMES
            }
            .forEach { f ->
                if (isElfOrScript(f)) grant(f)
            }
    }

    /** 前 4 字节是 ELF 魔数，或首行以 #! 开头（脚本） */
    private fun isElfOrScript(f: File): Boolean = runCatching {
        f.inputStream().use { ins ->
            val head = ByteArray(4)
            if (ins.read(head) < 2) return@use false
            val isElf = head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
            isElf || (head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte())
        }
    }.getOrDefault(false)

    companion object {
        /** runtime 装配进行中：ExtensionManager 拒绝在此窗口安装扩展（防互删） */
        @Volatile
        var installing: Boolean = false
            private set

        private const val TAG = "RuntimeInstaller"

        /**
         * 别名条目标记（v1.2.98）：打包侧把重复内容的 so 只存一份，其余条目在 zip
         * comment 里写 `LINK:<本体相对路径>`；解压时据此建硬链接而非写重复数据。
         */
        const val LINK_PREFIX = "LINK:"

        /**
         * 除 bin/ 目录外仍需可执行位的文件名（v1.2.100）。
         * ripgrep 的包内副本在 `@vscode/ripgrep-<arch>/bin/rg`（已被 /bin/ 规则覆盖），
         * 这里列的是可能放在其他位置的常见可执行文件。
         */
        private val EXECUTABLE_NAMES = setOf(
            "rg", "esbuild", "node", "sharp", "swc", "dprint", "biome",
        )

        /** @deepseek-ai 作用域下「完整安装」至少应存在的文件数（m1.12 空壳事故阈值） */
        private const val MIN_DSH_AI_FILES = 100

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
