package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Agent 上下文种子（m1.35）：把「Android 环境说明书」预写入 `$DSH_HOME/AGENTS.md`。
 *
 * 动机：dsh Agent 冷启动到一个陌生沙箱时，会花大量 token 自行探索环境
 * （uname、which、ls /、试探 apt…），且探索结论常常错误（把 bionic 当 glibc、
 * 试图 apt install）。dsh-agent-instructions 原生发现 `$DSH_HOME/AGENTS.md`
 * （user-global 层，无条件注入每个会话），故在此预置一份高密度环境事实，
 * 让 Agent 从第一轮就带着正确世界观干活。
 *
 * 幂等策略（保护用户/AI 的手工编辑）：
 *  - 文件不存在           → 写入最新模板
 *  - 存在且含本种子标记    → 版本旧则升级覆盖
 *  - 存在但无标记（被改过）→ 绝不覆盖
 */
object AgentContextSeed {

    private const val TAG = "AgentContextSeed"
    private const val FILE_NAME = "AGENTS.md"
    private const val MARKER_PREFIX = "<!-- dsh-android AGENTS seed v"
    /** 当前模板版本：改文案必须同步递增，旧版才会被升级覆盖 */
    private const val SEED_VERSION = 50

    fun ensure(ctx: Context) {
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        val existing = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
        if (existing != null && !existing.contains(MARKER_PREFIX)) {
            Log.i(TAG, "AGENTS.md exists without seed marker (user-authored); leaving untouched")
            return
        }
        if (existing != null && containsVersion(existing, SEED_VERSION)) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(render(ctx))
            Log.i(TAG, "AGENTS.md seeded (v$SEED_VERSION, ${file.length()} bytes)")
        }.onFailure { Log.w(TAG, "seed AGENTS.md failed: ${it.message}") }
    }

    private fun containsVersion(text: String, v: Int): Boolean =
        text.contains("$MARKER_PREFIX$v ")

    private fun render(ctx: Context): String {
        val mode = Privilege.getMode(ctx).name.lowercase()
        // 正文放在 assets/agents/AGENTS.md：改文案不用碰 Kotlin 字符串转义，
        // 也方便 review 成纯 Markdown 的 diff。
        val body = runCatching {
            ctx.assets.open("agents/AGENTS.md").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse {
            Log.w(TAG, "agents/AGENTS.md asset missing: ${it.message}")
            "# Environment: Android\n\n(seed asset missing; run `pkg install -y ...` as needed.)\n"
        }
        val shz = if (mode == "shizuku") {
            "- shz: run a command as the adb identity (uid 2000) — process management (`shz \"ps -A\"`, `shz \"am force-stop <pkg>\"`), system properties, package queries. Shizuku mode only."
        } else {
            ""
        }
        val rendered = body.replace("{{SHIZUKU_LINE}}", shz).replace("\n\n\n", "\n\n")
        return "$MARKER_PREFIX$SEED_VERSION — managed by DSH Mobile. Edits below this line are preserved until the app upgrades this seed.\n" + rendered
    }
}
