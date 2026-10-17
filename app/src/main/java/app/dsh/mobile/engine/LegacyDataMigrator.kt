package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 包名从 `app.dsh.mobile` 换成 `com.termux` 之后的一次性数据迁移。
 *
 * 为什么需要：dsh 的 workspace registry / session 项目缓存里存的是**绝对路径**
 * （`/data/user/0/app.dsh.mobile/files/dsh-home/workspaces/...`）。应用换了包名后
 * 这些路径指向旧包的数据目录，Android 不允许访问 → 会话恢复时报
 * `failed to ensure project directory ... EACCES: permission denied, mkdir ...`，
 * 主页因此看起来"起不来"。
 *
 * 这里只改写**文本类数据**（workspace.json / session 项目缓存 / AGENTS.md / 工具脚本），
 * 并把 workspace.json 里登记的工作区目录重新创建出来（内容可能已随旧包的公开存储
 * 被系统删除，但会话仍可打开）。幂等：迁移完成写 marker，不重复扫描。
 */
object LegacyDataMigrator {
    private const val TAG = "LegacyDataMigrator"
    private const val OLD_USER = "/data/user/0/app.dsh.mobile/files"
    private const val OLD_DATA = "/data/data/app.dsh.mobile/files"
    private const val NEW_USER = "/data/user/0/com.termux/files"
    private const val NEW_DATA = "/data/data/com.termux/files"
    private const val MARKER = ".path-migrated-com.termux"

    fun migrate(ctx: Context) {
        val home = EngineConfig.dshHome(ctx)
        val marker = File(home, MARKER)
        if (marker.isFile) return
        var changed = 0
        home.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.length() > (4L shl 20)) return@forEach
            if (!isTextlike(f.name)) return@forEach
            val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
            if (!text.contains("app.dsh.mobile")) return@forEach
            val out = text.replace(OLD_USER, NEW_USER).replace(OLD_DATA, NEW_DATA)
            if (out != text) runCatching { f.writeText(out); changed++ }
        }
        runCatching {
            val ws = File(home, "storages/workspace.json")
            if (ws.isFile) {
                Regex("\"path\"\\s*:\\s*\"([^\"]+)\"").findAll(ws.readText()).forEach { m ->
                    val p = m.groupValues[1]
                    if (p.startsWith(NEW_USER) || p.startsWith(NEW_DATA)) File(p).mkdirs()
                }
            }
        }
        runCatching { marker.writeText(System.currentTimeMillis().toString()) }
        if (changed > 0) Log.i(TAG, "migrated legacy app.dsh.mobile paths in $changed file(s)")
    }

    private fun isTextlike(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".json") || n.endsWith(".jsonl") || n.endsWith(".md") ||
            n.endsWith(".mjs") || n.endsWith(".js") || n.endsWith(".ts") ||
            n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".toml") ||
            n.endsWith(".txt") || n.endsWith(".sh")
    }
}
