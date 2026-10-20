package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Termux 默认 dotfiles（v1.3.8）。
 *
 * 动机：Termux 里 `pkg install zsh` 之后没有任何 zshrc，用户拿到的是 zsh 裸配置；
 * 这里随包带一份「Termux 版」基础 zshrc（历史、补全、`alias cls` 等），落到
 * 交互终端真正的 `$HOME`（dsh-home）以及 Termux 标准 home（files/home）各一份。
 *
 * 幂等策略：
 *  - 文件不存在            → 写入默认配置
 *  - 含本文件版本标记       → 版本旧则覆盖（升级文案）
 *  - 无标记（用户自写/改过）→ 绝不覆盖
 */
object TermuxDotfiles {
    private const val TAG = "TermuxDotfiles"
    private const val ASSET = "termux/zshrc"
    private const val PLUGIN_ASSET = "termux/zsh-autosuggestions.zsh"
    private const val PLUGIN_DIR = ".dsh-mobile"
    private const val PLUGIN_FILE = "zsh-autosuggestions.zsh"
    private const val HIGHLIGHT_ASSET_DIR = "termux/zsh-syntax-highlighting"
    private const val HIGHLIGHT_DIR = "zsh-syntax-highlighting"
    private const val RM_ASSET = "termux/rm-trash.sh"
    private const val RM_MARKER = "DSH Mobile rm wrapper"
    private const val TRASH_CLI_ASSET = "termux/trash-cli.sh"
    private const val TRASH_CLI_MARKER = "DSH Mobile trash-cli"
    private const val APP_DOC_ASSET = "agents/DSH-MOBILE.md"
    private const val APP_DOC_MARKER = "dsh-mobile app doc v"
    private const val APP_DOC_MARKER_VERSION = "dsh-mobile app doc v16 "
    private const val CDP_HELPER_ASSET = "termux/webview-cdp.mjs"
    private const val CDP_WRAPPER_ASSET = "termux/dsh-mobile.sh"
    private const val CDP_HELPER_MARKER = "DSH Mobile helper"
    private const val FILE_NAME = ".zshrc"
    private const val MARKER = "# ~/.zshrc - DSH Mobile default config (v"
    private const val VERSION = 23

    fun ensure(ctx: Context) {
        val body = runCatching {
            ctx.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse {
            Log.w(TAG, "zshrc asset missing: ${it.message}")
            return
        }
        val plugin = runCatching {
            ctx.assets.open(PLUGIN_ASSET).use { it.readBytes() }
        }.getOrNull()
        // 交互终端 HOME = dsh-home；同时写一份到 Termux 标准 home，覆盖两种入口。
        val homes = listOf(
            EngineConfig.dshHome(ctx),
            File(ctx.filesDir, "home"),
        )
        homes.forEach { home ->
            runCatching {
                home.mkdirs()
                // 指令联想插件（v2）：内容随 APK 走，直接覆盖同步；放在 ~/.dsh-mobile/ 不碰用户文件
                if (plugin != null) {
                    val dir = File(home, PLUGIN_DIR).apply { mkdirs() }
                    val pf = File(dir, PLUGIN_FILE)
                    if (!pf.isFile || !pf.readBytes().contentEquals(plugin)) {
                        writeSafe(pf, plugin)
                        Log.i(TAG, "zsh-autosuggestions deployed at ${pf.absolutePath}")
                    }
                }
                // 语法高亮（v3）：整棵目录同步（脚本 + highlighters/*），内容比对后按需写
                val hlDst = File(home, PLUGIN_DIR + "/" + HIGHLIGHT_DIR)
                deployAssetTree(ctx, HIGHLIGHT_ASSET_DIR, hlDst)
                // AssetManager.list() 不返回以 "." 开头的文件（实测漏了版本元数据），
                // 这两个插件版本文件需要按名显式部署，否则 zsh 启动会报找不到文件。
                listOf("version.txt" to ".version", "revision-hash.txt" to ".revision-hash").forEach { (assetName, name) ->
                    runCatching {
                        val bytes = ctx.assets.open("$HIGHLIGHT_ASSET_DIR/$assetName").use { it.readBytes() }
                        val dst = File(hlDst, name)
                        if (!dst.isFile || !dst.readBytes().contentEquals(bytes)) {
                            hlDst.mkdirs()
                            writeSafe(dst, bytes)
                            Log.i(TAG, "zsh-syntax-highlighting metadata deployed: $name")
                        }
                    }.onFailure { Log.w(TAG, "deploy $name failed: ${it.message}") }
                }
                val dst = File(home, FILE_NAME)
                val existing = if (dst.isFile) dst.readText() else null
                when {
                    existing == null -> {
                        writeSafe(dst, body.toByteArray())
                        Log.i(TAG, "zshrc seeded at ${dst.absolutePath}")
                    }
                    !existing.contains(MARKER) -> {
                        Log.i(TAG, "zshrc exists and is user-managed at ${dst.absolutePath}; left alone")
                    }
                    existing.contains("$MARKER$VERSION)") -> Unit
                    else -> {
                        writeSafe(dst, body.toByteArray())
                        Log.i(TAG, "zshrc upgraded to v$VERSION at ${dst.absolutePath}")
                    }
                }
            }.onFailure { Log.w(TAG, "deploy zshrc failed: ${it.message}") }
        }
        deployRmWrapper(ctx)
        deployTrashCli(ctx)
        deployAppDoc(ctx)
        deployCdpHelper(ctx)
        repairScriptModes(ctx)
    }

    /**
     * 修复 $PREFIX/bin 里由 App 管理的脚本权限（v1.3.31 权限事故）。
     *
     * 背景：Java 在 Android 上新建文件是 0600；之前部署门脚本/包装器时没有 chmod，
     * 且用户升级后「安装运行时」步骤被跳过，导致 native/appshot/shot/alarm/record 等
     * 新脚本一直不可执行。这里每次启动都幂等地把已知脚本 chmod 0755（存在才改），
     * 同时把 bootstrap 遗留的 toybox 同名符号链接替换掉（写穿会导致脚本失效）。
     */
    private fun repairScriptModes(ctx: Context) {
        runCatching {
            val root = EngineConfig.engineRoot(ctx)
            val bin = File(root, "bin")
            if (!bin.isDirectory) return
            // 我们管理的脚本（$PREFIX/bin 与 engine/bin 同目录）
            val names = listOf(
                "rm", "trash-cli", "dsh-mobile", "_dsh_http.sh", "notify", "scr", "say",
                "psx", "killx", "loop", "fast", "linux", "dsh-update",
                "shot", "appshot", "alarm", "record", "native", "media",
            )
            var fixed = 0
            var removedLinks = 0
            names.forEach { name ->
                val f = File(bin, name)
                if (!f.exists()) return@forEach
                val isLink = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
                if (isLink) {
                    // bootstrap 时代留下的 toybox/busybox 同名符号链接（rm 的历史形态）。
                    // 写文件会穿透到 /system/bin 本体，必须先清掉；真正的 gate 部署随后重建。
                    val target = runCatching {
                        java.nio.file.Files.readSymbolicLink(f.toPath()).toString()
                    }.getOrDefault("")
                    val toyboxish = target.contains("/system/bin/") || target.contains("/system/xbin/") ||
                        target.contains("toybox") || target.contains("busybox")
                    if (toyboxish) {
                        runCatching { f.delete() }.onSuccess {
                            removedLinks++
                            Log.w(TAG, "removed toybox symlink: bin/$name -> $target")
                        }
                    } else {
                        Log.w(TAG, "bin/$name is a symlink to $target (not toybox); leaving it alone")
                    }
                    return@forEach
                }
                if (!f.isFile) return@forEach
                if (!f.canExecute()) {
                    f.setReadable(true, false)
                    f.setExecutable(true, false)
                    fixed++
                    Log.i(TAG, "script mode repaired: $name -> executable")
                }
            }
            if (fixed > 0) Log.i(TAG, "script modes repaired: $fixed file(s) in ${bin.absolutePath}")
            if (removedLinks > 0) Log.w(TAG, "removed $removedLinks toybox symlink(s) from ${bin.absolutePath}")
        }.onFailure { Log.w(TAG, "repairScriptModes failed: ${it.message}") }
    }

    /**
     * App 自述文档（v1.3.22）：落到 `$DSH_HOME/DSH-MOBILE.md`，供 Agent 了解
     * 本 App 的目录结构、原生能力（门脚本/AgentBridge）以及 WebView CDP 调试方法。
     * 带 `dsh-mobile app doc vN` 标记；版本升级才覆盖（人工改动无标记则不碰）。
     */
    private fun deployAppDoc(ctx: Context) {
        runCatching {
            val home = EngineConfig.dshHome(ctx).apply { mkdirs() }
            val dst = File(home, "DSH-MOBILE.md")
            val body = ctx.assets.open(APP_DOC_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            val existing = runCatching { if (dst.isFile) dst.readText() else null }.getOrNull()
            if (existing != null) {
                // 不是我们生成的（用户自己写的说明）→ 不动
                if (!existing.startsWith("<!-- " + APP_DOC_MARKER)) return
                // 同一版本已部署 → 不动（避免每次启动重写）
                if (existing.contains("<!-- " + APP_DOC_MARKER_VERSION)) return
            }
            writeSafe(dst, body.toByteArray())
            Log.i(TAG, "app doc deployed at ${dst.absolutePath}")
        }.onFailure { Log.w(TAG, "deploy app doc failed: ${it.message}") }
    }

    /**
     * WebView CDP 桥 + AgentBridge 客户端（v1.3.22）：
     *  - `$PREFIX/lib/dsh-mobile/webview-cdp.mjs`（纯 Node，无依赖）
     *  - `$PREFIX/bin/dsh-mobile` 包装器
     * 让 Agent 能在设备内直接开 WebView 调试、把 abstract devtools socket 桥到
     * 127.0.0.1:9222、执行 Runtime.evaluate / Page.reload，并调用全部原生桥接口。
     */
    private fun deployCdpHelper(ctx: Context) {
        runCatching {
            val root = EngineConfig.engineRoot(ctx)
            val node = File(root, "bin/node")
            if (!node.isFile) { Log.w(TAG, "engine node missing; skip cdp helper"); return }
            val libDir = File(root, "lib/dsh-mobile").apply { mkdirs() }
            val scriptBytes = ctx.assets.open(CDP_HELPER_ASSET).use { it.readBytes() }
            val script = File(libDir, "webview-cdp.mjs")
            if (!script.isFile || !script.readBytes().contentEquals(scriptBytes)) {
                writeSafe(script, scriptBytes)
            }
            val wrapperBytes = ctx.assets.open(CDP_WRAPPER_ASSET).use { it.readBytes() }
            val wrapper = File(root, "bin/dsh-mobile")
            if (!wrapper.isFile || !wrapper.readBytes().contentEquals(wrapperBytes)) {
                writeSafe(wrapper, wrapperBytes, executable = true)
            }
            Log.i(TAG, "cdp helper deployed (dsh-mobile + webview-cdp.mjs)")
        }.onFailure { Log.w(TAG, "deploy cdp helper failed: ${it.message}") }
    }

    /**
     * 部署 `$PREFIX/bin/trash-cli`（v1.3.19）：回收站的统一恢复入口。
     * 有子命令 list / restore <关键字|序号> / empty；Agent 与人都用这一条命令，
     * 不再散落 trash-list / trash-restore / trash-empty 三个函数。
     */
    private fun deployTrashCli(ctx: Context) {
        runCatching {
            val root = EngineConfig.engineRoot(ctx)
            val script = ctx.assets.open(TRASH_CLI_ASSET).use { it.readBytes() }
            val dst = File(root, "bin/trash-cli")
            val isLink = runCatching { java.nio.file.Files.isSymbolicLink(dst.toPath()) }.getOrDefault(false)
            if (!isLink && dst.isFile) {
                val text = runCatching { dst.readText() }.getOrNull()
                if (text != null && text.contains(TRASH_CLI_MARKER) && dst.readBytes().contentEquals(script)) return
                if (text != null && !text.contains(TRASH_CLI_MARKER)) {
                    Log.w(TAG, "bin/trash-cli exists and is not ours; leaving it alone")
                    return
                }
            }
            if (dst.exists() || isLink) dst.delete()
            dst.parentFile?.mkdirs()
            writeSafe(dst, script, executable = true)
            Log.i(TAG, "trash-cli deployed at ${dst.absolutePath}")
        }.onFailure { Log.w(TAG, "deploy trash-cli failed: ${it.message}") }
    }

    /**
     * 底层 rm 拦截（v1.3.18）：把 `$PREFIX/bin/rm` 换成脚本 wrapper。
     *
     * 为什么不用 zsh 函数：那只覆盖交互式 zsh，Agent 的 bash 工具、脚本、其它 shell
     * 都绕过去了。放进 PATH 最前面的 bin 目录，凡是 `rm` 都会经过 wrapper：
     *  - 目标全部在 /sdcard、/storage → 移入 /sdcard/dsh-trash，可恢复；
     *  - 其它路径 → exec /system/bin/rm，保持原有语义。
     *
     * 注意 bin/rm 原本是 bootstrap 里指向 toybox 的符号链接：必须先删除再写文件，
     * 否则写操作会穿透到 toybox 二进制本体。
     */
    private fun deployRmWrapper(ctx: Context) {
        runCatching {
            val root = EngineConfig.engineRoot(ctx)
            val script = ctx.assets.open(RM_ASSET).use { it.readBytes() }
            val dst = File(root, "bin/rm")
            val isLink = runCatching { java.nio.file.Files.isSymbolicLink(dst.toPath()) }.getOrDefault(false)
            if (!isLink && dst.isFile) {
                val text = runCatching { dst.readText() }.getOrNull()
                if (text != null && text.contains(RM_MARKER) && text.toByteArray().contentEquals(script)) {
                    return
                }
                if (text != null && !text.contains(RM_MARKER)) {
                    // 已有用户/上游的 rm（真二进制/其它 wrapper）：不覆盖，记日志
                    Log.w(TAG, "bin/rm exists and is not ours; leaving it alone")
                    return
                }
            }
            if (dst.exists() || isLink) dst.delete()
            dst.parentFile?.mkdirs()
            writeSafe(dst, script, executable = true)
            Log.i(TAG, "rm wrapper deployed at ${dst.absolutePath}")
        }.onFailure { Log.w(TAG, "deploy rm wrapper failed: ${it.message}") }
    }

    /**
     * 符号链接安全的写入（v1.3.31 审计）：$PREFIX 里存在 bootstrap 遗留的 toybox 符号链接，
     * 直接 write 会穿透到 /system/bin 本体。这里先删链接再写，并按需 chmod 0755。
     */
    private fun writeSafe(file: File, bytes: ByteArray, executable: Boolean = false) {
        val isLink = runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        if (isLink) {
            Log.w(TAG, "replacing symlink with real file: ${file.absolutePath}")
            file.delete()
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        file.setReadable(true, false)
        if (executable) file.setExecutable(true, false)
    }

    /** 把 assets 里的一棵目录树同步到 dst（内容一致则跳过；目录较浅，成本可忽略）。 */
    private fun deployAssetTree(ctx: Context, assetDir: String, dstDir: File) {
        val children = runCatching { ctx.assets.list(assetDir) }.getOrNull() ?: return
        dstDir.mkdirs()
        children.forEach { child ->
            val childAsset = "$assetDir/$child"
            val childChildren = runCatching { ctx.assets.list(childAsset) }.getOrNull().orEmpty()
            if (childChildren.isEmpty()) {
                // 叶子文件
                val bytes = runCatching { ctx.assets.open(childAsset).use { it.readBytes() } }.getOrNull()
                if (bytes != null) {
                    val dst = File(dstDir, child)
                    if (!dst.isFile || !dst.readBytes().contentEquals(bytes)) writeSafe(dst, bytes)
                }
            } else {
                deployAssetTree(ctx, childAsset, File(dstDir, child))
            }
        }
    }
}
