package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

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
 *  2. **适配版 dsh 引擎**：assets/engine-dsh-aarch64.zip（node_modules 闭包 + ripgrep）
 *     解到同一个前缀的 `lib/node_modules`。
 *     ⚠️ **不能用 npm 安装 dsh** —— 上游包在 Termux/Android 上装不起来，
 *        本项目存在的意义就是这份适配过的运行时。npm 还会把**更新的嵌套依赖**
 *        （如 node-addon-require-builtin）带进来，覆盖适配版、导致
 *        "No usable native binding found for node-addon-require-builtin-android-arm64"。
 *
 * 首次启动还要装 node（Termux 原生包，nodejs-lts）。之所以不把 node 打进 APK：
 * 它和引擎的 node_modules 需要版本匹配，交给仓库统一升级更稳。
 */
object BootstrapInstaller {
    private const val TAG = "BootstrapInstaller"
    const val ASSET = "bootstrap-aarch64.zip"
    const val VERSION = "bootstrap-2026.10.11-r1"
    const val ENGINE_ASSET = "engine-dsh-aarch64.zip"
    const val ENGINE_VERSION = "dsh-engine-0.2.1-rc.2-20261018"
    private const val STAMP = ".bootstrap-version"
    private const val ENGINE_STAMP = ".dsh-engine-version"
    const val DSH_PKG = "lib/node_modules/@deepseek-ai/dsh/lib/bin.js"

    fun ensure(ctx: Context, onProgress: (Float) -> Unit = {}) {
        val root = EngineConfig.engineRoot(ctx)            // <filesDir>/usr
        File(ctx.filesDir, "home").mkdirs()
        File(root, "tmp").mkdirs()
        val stamp = File(root, STAMP)
        val installed = File(root, "bin/bash").isFile && File(root, "bin/pkg").isFile
        if (!installed || !stamp.isFile || stamp.readText().trim() != VERSION) {
            extractAsset(ctx, root, ASSET)
            stamp.writeText(VERSION)
            // 只有 bootstrap 的 zip 会丢权限位；引擎包是 tar（保留 mode），不用扫。
            restoreExecBits(root)
        }
        ensureDshEngine(ctx, root, onProgress)
        // 包名从 app.dsh.mobile 换成 com.termux 后，老数据里的绝对路径要改写，
        // 否则会话恢复会去 mkdir 旧包名目录（EACCES）→ 主页起不来。
        LegacyDataMigrator.migrate(ctx)
        onProgress(1f)
    }

    /** node（Termux 原生包）+ 内置适配版 dsh 引擎（不走 npm）。 */
    private fun ensureDshEngine(ctx: Context, root: File, onProgress: (Float) -> Unit) {
        val node = File(root, "bin/node")
        if (!node.canExecute()) {
            Log.i(TAG, "installing nodejs-lts via pkg")
            onProgress(0.7f)
            val rc = exec(root, listOf("bin/pkg", "install", "-y", "nodejs-lts"), 1800)
            if (rc != 0) exec(root, listOf("bin/pkg", "install", "-y", "nodejs"), 1800)
        }
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
        onProgress(1f)
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

    private fun exec(root: File, argv: List<String>, timeoutSec: Long): Int {
        val pb = ProcessBuilder(argv)
        pb.directory(root)
        val env = pb.environment()
        env["PATH"] = File(root, "bin").absolutePath + ":/system/bin:/system/xbin"
        env["PREFIX"] = root.absolutePath
        env["HOME"] = File(root.parentFile, "home").absolutePath
        env["TMPDIR"] = File(root, "tmp").absolutePath
        env["LD_LIBRARY_PATH"] = File(root, "lib").absolutePath
        env["TERM"] = "xterm-256color"
        env["DEBIAN_FRONTEND"] = "noninteractive"
        pb.redirectErrorStream(true)
        val p = pb.start()
        val t = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { line -> Log.i(TAG, argv.joinToString(" ") + ": " + line) } }
        }
        t.start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); Log.w(TAG, "timeout: $argv") }
        t.join(2000)
        val rc = runCatching { p.exitValue() }.getOrDefault(-1)
        Log.i(TAG, argv.joinToString(" ") + " exit=" + rc)
        return rc
    }
}
