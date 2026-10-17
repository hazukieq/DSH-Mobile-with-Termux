package app.dsh.mobile.linux

import android.content.Context
import android.system.Os
import android.util.Log
import app.dsh.mobile.engine.ShellSpec
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Linux 环境（proot 用户空间）的唯一门面：安装 / 卸载 / 生成终端会话 / 提供门脚本环境。
 *
 * 布局（全部在 App 私有目录，绝不写用户存储）：
 * ```
 * <filesDir>/linux/
 *   proot/                     # Termux proot 负载（DebInstaller 拍平 usr 下的内容）
 *     bin/proot
 *     lib/{libtalloc.so.2, libandroid-shmem.so}
 *     libexec/proot/loader
 *   tmp/                       # PROOT_TMP_DIR（proot 自己需要可写临时目录）
 *   cache/                     # 下载缓存（装完即删）
 *   distros/
 *     <id>/.installed          # 安装成功标记（内容含版本/ABI/哈希，供升级判断）
 *     <id>/rootfs/             # 发行版根文件系统
 * ```
 *
 * 设计取舍：
 *  - **按需下载**：rootfs 4~30MB 不进 APK（体积 + 许可证双重原因），装完卸载 APK 仍可用；
 *  - **原子发布**：先解到 `rootfs.tmp`，post-configure 全部成功后才 rename 成 `rootfs`，
 *    任何一步失败都不会让「看起来装好了、其实半套」的 rootfs 出现在 final 路径；
 *  - **proot 与发行版解耦**：proot 负载只装一次（`proot/`），多个发行版共用；`remove()`
 *    只删 `distros/<id>`，绝不会碰 `proot/`；
 *  - 所有阻塞操作（下载/解压/写盘）由调用方放到后台线程（UI 用 Dispatchers.IO）。
 */
object LinuxEnv {

    private const val TAG = "LinuxEnv"

    /** 已安装标记文件名（内容见 writeMarker） */
    private const val MARKER = ".installed"

    /** 默认发行版的 prefs key（门脚本 `linux exec` 用） */
    private const val PREFS = "dsh_linux"
    private const val KEY_DEFAULT = "default_distro"

    /** distro id 白名单：只允许小写字母/数字/_-，防止 id 变成路径穿越 */
    private val ID_RE = Regex("[a-z0-9][a-z0-9_-]*")

    // 同一发行版的安装互斥（UI 可能连点两次 / AI 通道与 UI 同时装）：
    // 不同发行版仍可并行（各自独立 stage 目录），proot 负载另有自己的锁。
    private val installLocks = ConcurrentHashMap<String, Any>()
    private val prootLock = Any()

    private fun lockFor(id: String): Any = installLocks.computeIfAbsent(id) { Any() }

    // ================= 路径 =================

    /** Linux 环境根目录：`<filesDir>/linux` */
    fun home(ctx: Context): File = File(ctx.filesDir, "linux").apply { mkdirs() }

    private fun distrosDir(ctx: Context): File = File(home(ctx), "distros").apply { mkdirs() }

    private fun defaultTmp(ctx: Context): File = File(home(ctx), "tmp").apply { mkdirs() }

    private fun cacheDir(ctx: Context): File = File(home(ctx), "cache").apply { mkdirs() }

    /** 某个发行版的目录：`<home>/distros/<id>` */
    private fun distroDir(ctx: Context, id: String): File = File(distrosDir(ctx), safeId(id))

    /** 发行版 rootfs 路径：`<home>/distros/<id>/rootfs`（不创建） */
    fun rootfs(ctx: Context, id: String): File = File(distroDir(ctx, id), "rootfs")

    /** proot 负载目录：`<home>/proot` */
    fun prootDir(ctx: Context): File = File(home(ctx), "proot")

    private fun markerOf(ctx: Context, id: String): File = File(distroDir(ctx, id), MARKER)

    private fun safeId(id: String): String {
        require(ID_RE.matches(id)) { "Invalid distro id: $id" }
        return id
    }

    // ================= 目录与状态 =================

    fun distros(): List<LinuxDistro> {
        val all = LinuxDistros.all()
        // x86_64 模拟器上 Alpine 不可用（musl 的 fork() 被 Android seccomp 拦截，
        // 详见 LinuxDistros.ALPINE 的 abiNote）：把 Ubuntu 排到前面，避免用户
        // 第一次就装上唯一不能用的那个。
        return if (LinuxDistros.deviceAbi() == LinuxDistros.ABI_X86_64) {
            all.sortedBy { if (it.id == "alpine") 1 else 0 }
        } else {
            all
        }
    }

    /** proot 负载是否完整（启动/安装的前置条件；纯文件探测，可在主线程调用） */
    fun prootReady(ctx: Context): Boolean = DebInstaller.isComplete(home(ctx))

    /**
     * 发行版是否已安装：标记 + rootfs + 客户机 shell 三者齐备。
     * 只查标记不够 —— rootfs 被外部删除/解压半途而废时标记可能残留。
     */
    fun isInstalled(ctx: Context, id: String): Boolean {
        if (!ID_RE.matches(id)) return false
        val distro = LinuxDistros.byId(id) ?: return false
        if (!markerOf(ctx, id).isFile) return false
        val root = rootfs(ctx, id)
        if (!root.isDirectory) return false
        return guestEntryExists(root, distro.shell)
    }

    /**
     * rootfs 内某个客户机路径是否存在。
     *
     * ⚠️ 不能只用 `File.isFile`：Alpine 的 `/bin/sh -> /bin/busybox` 是**绝对**符号链接，
     * 宿主侧解析会指向宿主机的 /bin/busybox（不存在）→ 误判「没装好」并反复重装。
     * 因此「链接本身存在」也算存在（悬空/绝对链接在 rootfs 里是正常的）。
     */
    private fun guestEntryExists(root: File, guestPath: String): Boolean {
        val f = File(root, guestPath.removePrefix("/"))
        return f.isFile || Files.isSymbolicLink(f.toPath())
    }

    /** 已安装发行版 id（按目录顺序，UI 列表稳定） */
    fun installedIds(ctx: Context): List<String> =
        LinuxDistros.all().map { it.id }.filter { isInstalled(ctx, it) }

    /**
     * 默认发行版 id：prefs 里配置的那个（仍已安装才认），否则第一个已安装的。
     * 没有已安装的发行版时返回 null。
     */
    fun defaultDistroId(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val configured = prefs.getString(KEY_DEFAULT, null)
        if (configured != null && isInstalled(ctx, configured)) return configured
        return installedIds(ctx).firstOrNull()
    }

    /** 设置默认发行版（null = 清除）；门脚本 DSH_LINUX_DEFAULT 的来源 */
    fun setDefaultDistro(ctx: Context, id: String?) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (id == null) {
            prefs.edit().remove(KEY_DEFAULT).apply()
        } else {
            safeId(id)
            prefs.edit().putString(KEY_DEFAULT, id).apply()
        }
    }

    // ================= 安装 =================

    /**
     * 安装 proot 负载（幂等）。
     *
     * @return true = 已就绪；false = 失败（错误详情已通过 report 上屏）
     */
    fun ensureProot(ctx: Context, report: (Float, String) -> Unit): Boolean {
        return try {
            installProotLocked(ctx, report)
        } catch (e: Exception) {
            Log.w(TAG, "ensureProot failed: ${e.message}")
            report(0f, "proot install failed: ${e.message ?: "unknown error"}")
            false
        }
    }

    /** proot 负载安装的进程内互斥（两个发行版同时装时只有一个在下载/解包 proot） */
    private fun installProotLocked(ctx: Context, report: (Float, String) -> Unit): Boolean =
        synchronized(prootLock) {
            DebInstaller.installProot(ctx, home(ctx), report)
        }

    /**
     * 安装某个发行版（proot 负载 + rootfs 下载 + post-configure + 原子发布）。
     *
     * 已完成安装则直接返回 true。
     *
     * @param report 总进度 0..1 + 短 ASCII 状态
     * @throws IllegalStateException 网络 / SHA-256 / 解包 / 配置失败（message 可供 UI 展示）
     * @throws IllegalArgumentException id 不在目录里
     */
    fun install(ctx: Context, id: String, report: (Float, String) -> Unit): Boolean {
        val distro = LinuxDistros.byId(id)
            ?: throw IllegalArgumentException("Unknown distro id: $id")
        safeId(id)
        synchronized(lockFor(id)) {
            if (isInstalled(ctx, id)) {
                report(1f, "${distro.name} already installed")
                return true
            }
            val abi = LinuxDistros.deviceAbi()
            val urls = distro.urlFor(abi)
            if (urls.isEmpty() || distro.sha256For(abi).isEmpty()) {
                throw IllegalStateException(
                    "No rootfs available for ABI $abi — supported: ${distro.urls.keys.joinToString(", ")}",
                )
            }

            // ── 阶段 1：proot 负载（0 ~ 0.15）──────────────────────────────
            report(0f, "Preparing proot runtime...")
            try {
                installProotLocked(ctx) { p, msg ->
                    report(coerce(p * 0.15f), msg)
                }
            } catch (e: Exception) {
                throw IllegalStateException("proot runtime is not available: ${e.message}", e)
            }

            val dir = distroDir(ctx, id).apply { mkdirs() }
            val stage = File(dir, "rootfs.tmp")
            val finalRoot = File(dir, "rootfs")
            val marker = File(dir, MARKER)
            val tar = File(cacheDir(ctx), "${distro.id}-$abi.tar.gz")
            var publishStarted = false
            try {
                // 陈旧标记会令 UI 误判；从这一刻起本发行版处于「安装中」
                runCatching { marker.delete() }
                runCatching { stage.deleteRecursively() }

                // ── 阶段 2：下载 rootfs（0.15 ~ 0.85）──────────────────────
                val shaExpected = distro.sha256For(abi)
                downloadRootfs(urls, shaExpected, distro, tar) { frac ->
                    report(0.15f + frac * 0.70f, "Downloading ${distro.name} rootfs...")
                }

                // ── 阶段 3：解包到临时目录（0.85 ~ 0.95）───────────────────
                check(stage.mkdirs() || stage.isDirectory) {
                    "cannot create staging dir: ${stage.absolutePath}"
                }
                report(0.85f, "Extracting rootfs...")
                TarGzExtractor.extract(tar, stage) { done, total ->
                    val frac = if (total > 0L) (done.toDouble() / total).toFloat() else 0f
                    report(coerce(0.85f + frac * 0.09f), "Extracting rootfs...")
                }
                // 解包结果最低限度的可用性检查（shell 都不在就没有继续的意义）
                check(guestEntryExists(stage, distro.shell)) {
                    "rootfs is missing ${distro.shell} — archive extraction failed?"
                }

                // ── 阶段 4：post-configure（0.95 ~ 0.98）───────────────────
                report(0.95f, "Configuring ${distro.name}...")
                configure(distro, stage)

                // ── 阶段 5：原子发布 + 标记（0.98 ~ 1.0）───────────────────
                report(0.98f, "Publishing rootfs...")
                publishStarted = true
                if (finalRoot.exists()) finalRoot.deleteRecursively()
                if (!stage.renameTo(finalRoot)) {
                    // 同文件系统内 rename 理论上必成功；FUSE/DataStore 等实现偶尔拒绝目录
                    // rename，退化为复制 + 删除，仍然只在内容完整后才出现在 final 路径。
                    stage.copyRecursively(finalRoot, overwrite = true)
                    stage.deleteRecursively()
                }
                check(guestEntryExists(finalRoot, distro.shell)) {
                    "published rootfs is missing ${distro.shell}"
                }
                writeMarker(marker, distro, abi)
                report(1f, "${distro.name} installed")
                Log.i(TAG, "installed ${distro.id} ${distro.version} ($abi)")
                return true
            } catch (e: Exception) {
                // 发布阶段之后失败 → 删除 final，避免半套 rootfs 被误认为可用
                if (publishStarted) runCatching { finalRoot.deleteRecursively() }
                runCatching { stage.deleteRecursively() }
                throw if (e is IllegalStateException) e
                else IllegalStateException("Install ${distro.name} failed: ${e.message}", e)
            } finally {
                runCatching { stage.deleteRecursively() }
                runCatching { tar.delete() }
            }
        }
    }

    /**
     * 按镜像顺序下载 rootfs 并做 SHA-256 校验；单个镜像失败/内容不符 → 换下一个。
     * 全部失败时抛出聚合错误（含最后一次失败原因）。
     */
    private fun downloadRootfs(
        urls: List<String>,
        expectedSha: String,
        distro: LinuxDistro,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        var lastErr: Exception? = null
        for ((i, url) in urls.withIndex()) {
            try {
                val host = URL(url).host
                Log.i(TAG, "rootfs ${distro.id}: try $url")
                downloadTo(url, dest, onProgress)
                val actual = sha256(dest)
                if (expectedSha.isNotEmpty() && !actual.equals(expectedSha, ignoreCase = true)) {
                    dest.delete()
                    throw IllegalStateException(
                        "SHA-256 mismatch from $host (expected ${expectedSha.take(12)}..., " +
                            "got ${actual.take(12)}...)",
                    )
                }
                if (expectedSha.isEmpty()) {
                    // 目录未登记哈希（新增发行版首次运行）：计算并落盘，供后续升级检测
                    runCatching { File("$dest.sha256").writeText(actual) }
                    Log.w(TAG, "rootfs ${distro.id}: no catalogue hash; recorded $actual")
                }
                return
            } catch (e: Exception) {
                lastErr = e
                dest.delete()
                Log.w(TAG, "rootfs mirror failed (${i + 1}/${urls.size}): ${e.message}")
            }
        }
        throw IllegalStateException(
            "Rootfs download failed on all ${urls.size} mirrors: ${lastErr?.message}",
            lastErr,
        )
    }

    /**
     * rootfs 内落盘 post-configure（纯文件写入，不执行 rootfs 里的任何程序）。
     *
     * 目的：国内网络下开箱即用的 DNS / 包管理器镜像 / 干净的登录环境。
     * 所有内容都是**幂等覆盖**，重复安装/升级不会叠加。
     */
    private fun configure(distro: LinuxDistro, root: File) {
        // DNS：阿里云公共 DNS 优先，Google/Cloudflare 兜底（proot 共享宿主网络，直接可用）
        writeFile(File(root, "etc/resolv.conf"), RESOLV_CONF)
        writeFile(File(root, "etc/hosts"), HOSTS)
        // proot 里 /proc /sys /dev 由 -b 覆盖挂载；这里的目录只保证「挂载点存在」，
        // 首次启动、以及不绑定某个路径时不会出现 no such file。
        listOf("root", "tmp", "proc", "sys", "dev").forEach { name ->
            runCatching { File(root, name).mkdirs() }
        }
        makeWorldWritable(File(root, "tmp"))

        when (distro.id) {
            "alpine" -> {
                writeFile(File(root, "etc/apk/repositories"), APK_REPOS)
                writeProfile(root, ALPINE_PROFILE)
            }
            "ubuntu" -> {
                writeFile(File(root, "etc/apt/sources.list"), UBUNTU_SOURCES)
                // Ubuntu 24.04 的源搬到了 deb822 的 sources.list.d/ubuntu.sources：
                // 只改 sources.list 而不禁用它会两个源同时生效（等于镜像没换）。
                // 改名成 .disabled 后缀（apt 只读 .list/.sources），原文件保留可回滚。
                runCatching {
                    val dir = File(root, "etc/apt/sources.list.d")
                    dir.listFiles()?.forEach { f ->
                        if (!f.isFile) return@forEach
                        if (!f.name.endsWith(".list") && !f.name.endsWith(".sources")) return@forEach
                        val disabled = File(dir, f.name + ".dsh-disabled")
                        if (!f.renameTo(disabled)) {
                            runCatching {
                                f.writeText(
                                    "# [dsh-android] disabled: see /etc/apt/sources.list\n",
                                    StandardCharsets.UTF_8,
                                )
                            }
                        }
                    }
                }
                writeProfile(root, UBUNTU_PROFILE)
            }
        }
    }

    /** 写 /etc/profile.d/00-dsh.sh（登录 shell 会 source 它） */
    private fun writeProfile(root: File, content: String) {
        val f = File(root, "etc/profile.d/00-dsh.sh")
        writeFile(f, content.trimEnd() + "\n")
        runCatching { f.setExecutable(false, false) }
        runCatching { f.setReadable(true, false) }
    }

    /** 覆盖写文件；符号链接先删掉（否则会写穿到链接目标，甚至跑出 rootfs） */
    private fun writeFile(file: File, text: String) {
        file.parentFile?.mkdirs()
        runCatching {
            if (Files.isSymbolicLink(file.toPath()) || file.isDirectory) {
                if (Files.isSymbolicLink(file.toPath())) Files.deleteIfExists(file.toPath())
                else file.deleteRecursively()
            }
        }
        file.writeText(text, StandardCharsets.UTF_8)
        runCatching { file.setReadable(true, false) }
        runCatching { file.setWritable(true, false) }
    }

    /**
     * chmod 1777：Kotlin 的 File API 表达不了 sticky 位，用 android.system.Os.chmod
     * （01777 = 1023）。失败时退化为 0777 —— guest 是 fake-root 单用户，够用。
     */
    private fun makeWorldWritable(dir: File) {
        if (!dir.isDirectory) return
        val ok = runCatching { Os.chmod(dir.absolutePath, 0x3FF) }.isSuccess   // 0o1777
        if (!ok) {
            runCatching {
                dir.setReadable(true, false)
                dir.setWritable(true, false)
                dir.setExecutable(true, false)
            }
        }
    }

    /** 写 `.installed` 标记（含版本/ABI/SHA-256，便于「目录升级后重装」与诊断） */
    private fun writeMarker(marker: File, distro: LinuxDistro, abi: String) {
        val text = buildString {
            append("distro=").append(distro.id).append('\n')
            append("name=").append(distro.name).append('\n')
            append("version=").append(distro.version).append('\n')
            append("abi=").append(abi).append('\n')
            append("sha256=").append(distro.sha256For(abi)).append('\n')
            append("installed_at=").append(System.currentTimeMillis()).append('\n')
        }
        marker.writeText(text, StandardCharsets.UTF_8)
    }

    // ================= 卸载 =================

    /** 卸载发行版：只删 `<distros>/<id>`（proot 负载与其它发行版不受影响） */
    fun remove(ctx: Context, id: String) {
        safeId(id)
        val dir = File(distrosDir(ctx), id)
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.i(TAG, "removed distro $id")
        }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_DEFAULT, null) == id) {
            prefs.edit().remove(KEY_DEFAULT).apply()
        }
    }

    // ================= ShellSpec =================

    /**
     * 生成一条 proot 会话（终端 interactive / agent 一次性 command 共用）。
     *
     * 未安装、proot 负载缺失、id 未知时返回 null（UI 侧据此隐藏入口）。
     *
     * 交互式：
     *   proot --link2symlink -0 -r <rootfs> -b ... -w /root /bin/sh -l
     *   （客户机环境变量直接写进 proot 的 env —— 没有 `env -i`，登录 shell 会 source
     *   /etc/profile 再补 PATH 等，符合「完整登录环境」的预期）
     * 一次性：
     *   proot ... /usr/bin/env -i HOME=/root PATH=... TERM=... <shell> -lc "<cmd>"
     *   （`env -i` 清掉宿主 bionic 环境，AI 看到的是干净的 Linux 环境；cmd 作为**单个
     *   argv** 传给 -lc，不 eval、不二次拆分）
     *
     * @param command 客户机命令。**长度为 1 时按整条命令字符串处理**（可含 && | 等
     *    shell 语法）；多个元素按 argv 逐项 shell-quote 后拼成一行。
     * @param cwd 宿主侧工作目录（必须存在）；缺省用 filesDir。客户机 cwd 恒为 /root。
     */
    @Suppress("UNUSED_PARAMETER")   // interactive 只表达调用方意图；无 command 时一律 login shell
    fun shellSpec(
        ctx: Context,
        id: String,
        interactive: Boolean,
        command: List<String>? = null,
        cwd: String? = null,
    ): ShellSpec? {
        val distro = runCatching { LinuxDistros.byId(safeId(id)) }.getOrNull() ?: return null
        if (!isInstalled(ctx, id)) return null
        val home = home(ctx)
        if (!DebInstaller.isComplete(home)) return null
        val root = rootfs(ctx, id)
        val proot = DebInstaller.prootBinary(home)
        if (!proot.isFile || !proot.canExecute()) return null

        val guestEnv = listOf(
            "HOME=/root",
            "PATH=$GUEST_PATH",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "USER=root",
            "SHELL=${distro.shell}",
        )
        // 宿主侧环境：proot 二进制自己需要 loader + 两个 so + 临时目录；
        // Android 的 seccomp 过滤会让 proot 的 ptrace 路径踩坑，统一关掉 seccomp。
        val hostEnv = mutableListOf(
            "PROOT_LOADER=${DebInstaller.loader(home).absolutePath}",
            "PROOT_TMP_DIR=${defaultTmp(ctx).absolutePath}",
            "LD_LIBRARY_PATH=${DebInstaller.libDir(home).absolutePath}",
            "PROOT_NO_SECCOMP=1",
            "PATH=/system/bin:/system/xbin",
        )

        val args = mutableListOf(
            "--link2symlink",
            "--kill-on-exit",
            "-0",                                   // fake root：apt/apk 需要 root 身份
            "-r", root.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            // App 私有目录绑定到 guest 同路径：发行版里可直接访问 filesDir（DSH 数据）
            "-b", ctx.filesDir.absolutePath,
            "-w", "/root",
        )
        // 外置存储只在可访问时绑定（未授权/无卡设备不传，否则 proot 启动即失败）
        if (File(EXTERNAL_STORAGE).isDirectory) {
            args += "-b"
            args += "$EXTERNAL_STORAGE:/sdcard"
        }

        val hasCommand = !command.isNullOrEmpty()
        // 交互式也走 `env -i` 重建环境（2026-10-18 模拟器实测修正）：
        // 直接把 guestEnv 与 hostEnv 拼给 proot 会出现**同名键重复**（两边都有
        // PATH），而 getenv/execvp 取第一个还是最后一个取决于 libc 与调用方 ——
        // 实测 Ubuntu 会话里拿到的 PATH 是宿主的 /system/bin:/system/xbin，
        // 导致 `ls: command not found`。用 env -i 彻底重建：客户机只看到我们给的
        // 那几个变量，宿主的 PROOT_*/LD_LIBRARY_PATH 不再泄漏进 guest。
        val guest = if (hasCommand) {
            listOf("/usr/bin/env", "-i") +
                guestEnv +
                listOf(distro.shell, "-lc", commandLine(command!!))
        } else {
            listOf("/usr/bin/env", "-i") +
                guestEnv +
                listOf(distro.shell, "-l")
        }

        // 客户机环境由 guest 侧 `env -i` 重建，宿主 env 只服务 proot 自身
        // （loader / so / 临时目录），不进 guest。
        val env = hostEnv

        val hostCwd = cwd?.takeIf { File(it).isDirectory } ?: ctx.filesDir.absolutePath
        return ShellSpec(
            cmd = proot.absolutePath,
            args = args + guest,
            cwd = hostCwd,
            env = env,
            title = distro.name,
            kind = ShellSpec.Kind.LINUX,
            distroId = distro.id,
        )
    }

    /**
     * 门脚本（`engine/bin/linux`）需要的宿主环境。
     *
     * 注意：`LD_LIBRARY_PATH` 这一项是 **proot 专用**（proot 的 so 在
     * `<home>/proot/lib`）。调用方若与引擎已有的 LD_LIBRARY_PATH 合并，必须拼成
     * `<proot/lib>:<原值>`（proot 在前），不能直接覆盖 —— 否则引擎 bash/node
     * 会找不到自己的库。门脚本内部也会自己 export 一遍，属于双保险。
     */
    fun gateEnv(ctx: Context): List<String> {
        val home = home(ctx)
        val out = mutableListOf(
            "DSH_LINUX_HOME=${home.absolutePath}",
            "PROOT_LOADER=${DebInstaller.loader(home).absolutePath}",
            "PROOT_TMP_DIR=${defaultTmp(ctx).absolutePath}",
            "PROOT_NO_SECCOMP=1",
            "LD_LIBRARY_PATH=${DebInstaller.libDir(home).absolutePath}",
        )
        defaultDistroId(ctx)?.let { out += "DSH_LINUX_DEFAULT=$it" }
        return out
    }

    // ================= 内部工具 =================

    private fun coerce(v: Float): Float = v.coerceIn(0f, 1f)

    /**
     * 命令列表 -> 传给 `-lc` 的单行命令。
     * 单元素视为「整条命令字符串」，原样透传；多元素逐项 quote（避免参数里的空格/分号
     * 被 guest shell 当成语法）。
     */
    private fun commandLine(command: List<String>): String =
        if (command.size == 1) command[0]
        else command.joinToString(" ") { shQuote(it) }

    private fun shQuote(s: String): String {
        if (s.isEmpty()) return "''"
        if (s.all { it.isLetterOrDigit() || it in "@%+=:,./_-" }) return s
        return "'" + s.replace("'", "'\\''") + "'"
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ================= 网络（镜像 failover；与 ExtensionManager 同款 DNS 预检） =================

    private fun openConn(url: String, readTimeoutMs: Int): HttpURLConnection {
        val u = URL(url)
        val addrs = dnsResolve(u.host, 5_000)
            ?: throw IllegalStateException(
                "DNS resolution timed out: ${u.host} (restricted network or hijacked DNS?)",
            )
        check(addrs.isNotEmpty()) { "DNS resolution failed: ${u.host}" }
        val conn = u.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", HTTP_UA)
        conn.setRequestProperty("Connection", "Close")
        return conn
    }

    private fun dnsResolve(host: String, timeoutMs: Long): Array<InetAddress>? = try {
        DNS_POOL.submit(Callable { InetAddress.getAllByName(host) })
            .get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "dns resolve fail/timeout: $host (${e.message})")
        null
    }

    private fun downloadTo(url: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = openConn(url, readTimeoutMs = 180_000)
        val code = conn.responseCode
        check(code in 200..299) {
            "Download failed with HTTP $code: $url (mirror out of sync or blocked)"
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                }
            }
        }
        check(dest.length() > 0L) { "Empty download: $url" }
    }

    // ================= 常量 =================

    /** 客户机 PATH（Linux 标准路径；登录 shell 还会被 /etc/profile 再补一次） */
    private const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Android 外置存储挂载点（存在才 bind 到 /sdcard） */
    private const val EXTERNAL_STORAGE = "/storage/emulated/0"

    /**
     * 下载 rootfs/索引时的 UA。与 ExtensionManager.HTTP_UA 保持一致：
     * 用浏览器 UA 会被部分镜像的 WAF 403。
     */
    private const val HTTP_UA = "APT/2.12.10 (aarch64; android; termux)"

    /** DNS 预检线程池（daemon，防进程悬挂） */
    private val DNS_POOL = Executors.newSingleThreadExecutor { r ->
        Thread(r).apply {
            isDaemon = true
            name = "dsh-linux-dns"
        }
    }

    private val RESOLV_CONF = """
        # [dsh-android] generated at install time (proot shares the host network)
        nameserver 223.5.5.5
        nameserver 8.8.8.8
        nameserver 1.1.1.1
    """.trimIndent() + "\n"

    private val HOSTS = """
        # [dsh-android] generated at install time
        127.0.0.1 localhost
        ::1 localhost ip6-localhost
    """.trimIndent() + "\n"

    /** Alpine 源：阿里云优先；官方 CDN 以注释形式保留，方便一条命令切回 */
    private val APK_REPOS = """
        # [dsh-android] Aliyun mirror first; official CDN kept as commented fallback
        # https://dl-cdn.alpinelinux.org/alpine/v3.20/main
        # https://dl-cdn.alpinelinux.org/alpine/v3.20/community
        https://mirrors.aliyun.com/alpine/v3.20/main
        https://mirrors.aliyun.com/alpine/v3.20/community
    """.trimIndent() + "\n"

    /** Ubuntu 源：阿里云优先（ubuntu-base 官方镜像）；注释里保留官方源写法 */
    private val UBUNTU_SOURCES = """
        # [dsh-android] Ubuntu 24.04 (noble) - Aliyun mirror, generated at install time.
        # Official fallback: replace mirrors.aliyun.com/ubuntu with archive.ubuntu.com/ubuntu
        deb https://mirrors.aliyun.com/ubuntu/ noble main restricted universe multiverse
        deb https://mirrors.aliyun.com/ubuntu/ noble-updates main restricted universe multiverse
        deb https://mirrors.aliyun.com/ubuntu/ noble-security main restricted universe multiverse
        # deb https://mirrors.aliyun.com/ubuntu/ noble-backports main restricted universe multiverse
    """.trimIndent() + "\n"

    /** Alpine 登录环境：提示符 + UTF-8（busybox ash 默认没有这些） */
    private val ALPINE_PROFILE = """
        # [dsh-android] login shell defaults (generated at install time)
        export PS1='\h:\w\$ '
        export LANG=C.UTF-8
        export LC_ALL=C.UTF-8
        # package manager: /etc/apk/repositories already points at the Aliyun mirror
    """.trimIndent() + "\n"

    /** Ubuntu 登录环境：提示符 + UTF-8 */
    private val UBUNTU_PROFILE = """
        # [dsh-android] login shell defaults (generated at install time)
        export PS1='\u@\h:\w\$ '
        export LANG=C.UTF-8
        export LC_ALL=C.UTF-8
    """.trimIndent() + "\n"
}
