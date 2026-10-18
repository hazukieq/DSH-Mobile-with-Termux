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
    private const val FILE_NAME = ".zshrc"
    private const val MARKER = "# ~/.zshrc - DSH Mobile default config (v"
    private const val VERSION = 4

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
                        pf.writeBytes(plugin)
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
                            dst.writeBytes(bytes)
                            Log.i(TAG, "zsh-syntax-highlighting metadata deployed: $name")
                        }
                    }.onFailure { Log.w(TAG, "deploy $name failed: ${it.message}") }
                }
                val dst = File(home, FILE_NAME)
                val existing = if (dst.isFile) dst.readText() else null
                when {
                    existing == null -> {
                        dst.writeText(body)
                        Log.i(TAG, "zshrc seeded at ${dst.absolutePath}")
                    }
                    !existing.contains(MARKER) -> {
                        Log.i(TAG, "zshrc exists and is user-managed at ${dst.absolutePath}; left alone")
                    }
                    existing.contains("$MARKER$VERSION)") -> Unit
                    else -> {
                        dst.writeText(body)
                        Log.i(TAG, "zshrc upgraded to v$VERSION at ${dst.absolutePath}")
                    }
                }
            }.onFailure { Log.w(TAG, "deploy zshrc failed: ${it.message}") }
        }
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
                    if (!dst.isFile || !dst.readBytes().contentEquals(bytes)) dst.writeBytes(bytes)
                }
            } else {
                deployAssetTree(ctx, childAsset, File(dstDir, child))
            }
        }
    }
}
