package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 运行时装配（2026-10-18 包名切换为 com.termux 之后）。
 *
 * ## 两层结构，互不覆盖
 *
 *  1. **Termux 基础环境**：assets/bootstrap-aarch64.zip 解到 `<filesDir>/usr`，
 *     这是官方 Termux bootstrap（apt / dpkg / pkg / bash / coreutils…）。
 *     包名就是 com.termux、前缀就是 `/data/data/com.termux/files/usr`，
 *     所以仓库里的 deb 路径天然正确 —— 不需要 proot、不需要改 deb。
 *
 *  2. **适配版 dsh 引擎**：assets/engine-dsh-aarch64.zip（node + node_modules + bash + rg；
 *    node/rg 走 wrapper，真实二进制与依赖库在 `lib/dsh-engine/` 私有目录）。
 *    ⚠️ **不能用 npm 安装 dsh** —— 上游包在 Termux/Android 上装不起来，
 *       本项目存在的意义就是这份适配过的运行时。npm 还会把**更新的嵌套依赖**
 *       （如 node-addon-require-builtin）带进来，覆盖适配版、导致
 *       "No usable native binding found for node-addon-require-builtin-android-arm64"。
 *
 * ## 启动顺序（首启不再卡 bootstrap）
 *
 *  - **同步**：只解引擎层（一次性，约 30s），随后立刻起引擎 —— UI 马上可用。
 *    node/bash/rg 由引擎层自带，不依赖 Termux 仓库。
 *  - **后台**：Termux bootstrap（apt/dpkg/pkg/coreutils）异步解压，解完把 apt 源
 *    写成国内镜像（默认清华 TUNA）。首启不再等 23MB 的 nodejs-lts 下载。
 *
 * ⚠️ 不再 `pkg install nodejs-lts`：那会覆盖 `bin/node` 的 wrapper，还多下 116MB。
 *    引擎层自带 node 后完全不需要；用户手动装了也会被 wrapper 自愈逻辑修回来。
 */
object BootstrapInstaller {
    private const val TAG = "BootstrapInstaller"
    const val ASSET = "bootstrap-aarch64.zip"
    const val VERSION = "bootstrap-2026.10.11-r1"
    const val ENGINE_ASSET = "engine-dsh-aarch64.zip"
    const val ENGINE_VERSION = "dsh-engine-0.2.1-rc.2-20261019"
    private const val STAMP = ".bootstrap-version"
    private const val ENGINE_STAMP = ".dsh-engine-version"
    const val DSH_PKG = "lib/node_modules/@deepseek-ai/dsh/lib/bin.js"

    fun ensure(ctx: Context, onProgress: (Float) -> Unit = {}) {
        val root = EngineConfig.engineRoot(ctx)            // <filesDir>/usr
        File(ctx.filesDir, "home").mkdirs()
        // 默认 dotfiles（v1.3.8）：Termux 版基础 zshrc，不覆盖用户已有配置
        TermuxDotfiles.ensure(ctx)
        listOf("tmp", "var", "etc/apt", "etc/tls").forEach { File(root, it).mkdirs() }
        ensureDshEngine(ctx, root, onProgress)
        // 包名从 app.dsh.mobile 换成 com.termux 后，老数据里的绝对路径要改写，
        // 否则会话恢复会去 mkdir 旧包名目录（EACCES）→ 主页起不来。
        LegacyDataMigrator.migrate(ctx)
        // 源文件便宜，每次都确认（旧版可能是国外默认源）。
        writeChinaAptSources(root)
        // Termux 基础环境后台补：不阻塞引擎启动。
        if (!bootstrapReady(root)) startBootstrapInBackground(ctx)
        onProgress(1f)
    }

    /** bootstrap 是否已是最新（只看标记；bin/bash 由引擎层 wrapper 先顶着）。 */
    private fun bootstrapReady(root: File): Boolean {
        val stamp = File(root, STAMP)
        return stamp.isFile && stamp.readText().trim() == VERSION &&
            File(root, "bin/pkg").isFile && File(root, "bin/apt").isFile
    }

    /** 后台解 Termux bootstrap + 写国内 apt 源。幂等；失败只记日志，下次启动重试。 */
    private fun startBootstrapInBackground(ctx: Context) {
        Thread({
            val root = EngineConfig.engineRoot(ctx)
            runCatching {
                val t0 = System.currentTimeMillis()
                Log.i(TAG, "bootstrap: background install started")
                extractAsset(ctx, root, ASSET)
                restoreExecBits(root)
                writeChinaAptSources(root)
                File(root, STAMP).writeText(VERSION)
                Log.i(TAG, "bootstrap: ready in ${System.currentTimeMillis() - t0}ms")
            }.onFailure { Log.w(TAG, "bootstrap background install failed: ${it::class.simpleName}: ${it.message}", it) }
        }, "termux-bootstrap").apply { isDaemon = true }.start()
    }

    /**
     * 默认 apt 源写国内镜像（清华 TUNA），避免首启访问国外 bootstrap 默认源。
     * 只在文件缺失时写一次，之后用户可以自己改。
     */
    private fun writeChinaAptSources(root: File) {
        runCatching {
            val f = File(root, "etc/apt/sources.list")
            if (f.isFile && f.readText().contains("tuna.tsinghua.edu.cn")) return
            f.parentFile?.mkdirs()
            f.writeText(
                "# The main termux repository (China mirror: TUNA).\n" +
                "# DSH Mobile writes this once; edit freely afterwards.\n" +
                "deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main\n"
            )
            Log.i(TAG, "bootstrap: apt sources set to TUNA mirror")
        }
    }

    /** node（Termux 原生包）+ 内置适配版 dsh 引擎（不走 npm）。 */
    private fun ensureDshEngine(ctx: Context, root: File, onProgress: (Float) -> Unit) {
        val dshJs = File(root, DSH_PKG)
        val stamp = File(root, ENGINE_STAMP)
        // ⚠️ 不能拿 ENGINE_VERSION 做相等判断：dsh-update 热更新会换成别的版本
        // （stamp 随之改变），下次启动若按"等于内置版本"判断就会把热更新静默回退。
        // 判据只应该是「引擎可用」：dsh 入口在 + stamp 在。
        val engineOk = dshJs.isFile && stamp.isFile
        if (!engineOk) {
            Log.i(TAG, "extracting bundled dsh engine ($ENGINE_VERSION)")
            onProgress(0.8f)
            // 先清掉可能被 npm 装进来的树：嵌套依赖会覆盖适配版（实测灾难性）
            runCatching { File(root, "lib/node_modules").deleteRecursively() }
            extractAsset(ctx, root, ENGINE_ASSET)
            stamp.writeText(ENGINE_VERSION)
            restoreExecBits(root)
        }
        healEngineWrappers(root)
        onProgress(1f)
    }

    /**
     * 自愈引擎层 wrapper。
     *
     * 用户（或某个包）可能 `pkg install nodejs` / `ripgrep`，把 `$PREFIX/bin/node`
     * 换成真 ELF —— 引擎随即挂掉。这里检查：如果 bin/node 不再是我们的 shell wrapper
     * 而 `lib/dsh-engine/node-bin` 还在，就把 wrapper 写回去（bash/rg 同理）。
     */
    private fun healEngineWrappers(root: File) {
        listOf("node", "rg", "bash").forEach { name ->
            val real = File(root, "lib/dsh-engine/$name-bin")
            if (!real.isFile) return@forEach
            val link = File(root, "bin/$name")
            val isWrapper = runCatching {
                link.isFile && link.length() < 2048 &&
                    link.readText().take(256).contains("dsh-engine/$name-bin")
            }.getOrDefault(false)
            if (isWrapper) return@forEach
            runCatching {
                link.parentFile?.mkdirs()
                link.writeText(
                    "#!/system/bin/sh\n" +
                        "# [dsh-android] $name wrapper: private libs (do not replace with a pkg binary)\n" +
                        "d=\$(cd \"\${0%/*}/..\" && pwd)\n" +
                        "LD_LIBRARY_PATH=\"\$d/lib/dsh-engine/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\" exec \"\$d/lib/dsh-engine/$name-bin\" \"\$@\"\n"
                )
                link.setExecutable(true, false)
                Log.w(TAG, "engine wrapper healed: bin/$name")
            }.onFailure { Log.w(TAG, "heal bin/$name failed: ${it.message}") }
        }
    }

    /** 解 assets 里的 zip 到 root（合并覆盖，保留 bootstrap 已有文件）。 */
    private fun extractAsset(ctx: Context, root: File, asset: String) {
        ctx.assets.open(asset).use { input ->
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(input, 1 shl 20)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) { File(root, e.name).mkdirs(); continue }
                    val out = File(root, e.name)
                    if (out.isDirectory) continue
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> zip.copyTo(o, 1 shl 16) }
                }
            }
        }
        // bootstrap 的 SYMLINKS.txt：`target←./link/path`（Termux 安装器同一套语义）
        val linksTxt = File(root, "SYMLINKS.txt")
        if (linksTxt.isFile) {
            for (line in linksTxt.readLines()) {
                val idx = line.indexOf('←')
                if (idx <= 0) continue
                val target = line.substring(0, idx)
                val link = line.substring(idx + 1).removePrefix("./")
                if (link.isEmpty()) continue
                val f = File(root, link)
                f.parentFile?.mkdirs()
                runCatching {
                    if (f.exists() || f.isDirectory) f.delete()
                    java.nio.file.Files.createSymbolicLink(f.toPath(), File(target).toPath())
                }
            }
            linksTxt.delete()
        }
    }

    /** ZipInputStream 不保留权限位：bin/、libexec/ 以及 ELF/shebang 文件补 +x。 */
    private fun restoreExecBits(root: File) {
        val bin = File(root, "bin").absolutePath + File.separator
        val libexec = File(root, "libexec").absolutePath + File.separator
        var n = 0
        // 只扫 bin/ 与 libexec/：以前 walk 整个前缀（含 300MB node_modules）每次启动
        // 要 25s，白白拖慢引擎重启/热更新。
        listOf(File(root, "bin"), File(root, "libexec"), File(root, "sbin"), File(root, "lib/dsh-engine"), File(root, "lib/apt/methods")).forEach { dir ->
        if (!dir.isDirectory) return@forEach
        dir.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val p = f.absolutePath
            if (p.startsWith(bin) || p.startsWith(libexec) || looksExecutable(f)) {
                if (!f.canExecute()) { runCatching { f.setExecutable(true, false); n++ } }
            }
        }
        }
        if (n > 0) Log.i(TAG, "exec bits restored: $n")
    }

    private fun looksExecutable(f: File): Boolean = runCatching {
        f.inputStream().use { input ->
            val head = ByteArray(4)
            val len = input.read(head)
            if (len >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) return@use true
            if (len >= 4 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
            ) return@use true
            false
        }
    }.getOrDefault(false)

}
