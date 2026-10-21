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
    private const val SEED_VERSION = 76

    /**
     * 用户段起始标记（v1.3.2，issue #19）。
     *
     * 引擎只加载 `$DSH_HOME/AGENTS.md` 这一个 user-global 指令文件，所以用户补充的
     * 长期指令只能写进同一个文件 —— 于是把文件切成 **App 段**（随升级整段替换）
     * 与 **用户段**（App 永不触碰），两边互不干扰。
     */
    private const val USER_SECTION_BEGIN = "<!-- >>> user instructions (app never edits below) >>> -->"

    /** 用户段结束标记。 */
    private const val USER_SECTION_END = "<!-- <<< user instructions <<< -->"

    /** 用户段为空时的占位提示（告诉用户/设置页写在哪里）。 */
    private const val USER_SECTION_PLACEHOLDER =
        "<!-- Add your own instructions below this line. DSH Mobile never overwrites\n" +
            "     this section, and upgrades keep it as-is. Ask the AI to write here for you. -->"

    fun ensure(ctx: Context) {
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        val existing = runCatching { if (file.isFile) file.readText() else null }.getOrNull()

        // ① 文件不存在 → 写入完整模板（含空的用户段）
        if (existing == null) {
            write(file, render(ctx) + userSection(null))
            Log.i(TAG, "AGENTS.md seeded (v$SEED_VERSION)")
            return
        }

        // ② 旧版用户自写文件（无种子标记）：既有内容整体作为用户段保留，
        //    App 段补在上方 —— 既能升级环境说明，也不丢用户内容。
        if (!existing.contains(MARKER_PREFIX)) {
            Log.i(TAG, "AGENTS.md without seed marker; preserved as user section")
            write(file, render(ctx) + userSection(existing))
            return
        }

        // ③ 版本最新 → 只在用户段缺失时补占位
        if (containsVersion(existing, SEED_VERSION)) {
            if (!existing.contains(USER_SECTION_BEGIN)) {
                write(file, existing.trimEnd() + "\n" + userSection(null))
            }
            return
        }

        // ④ 需要升级 → 只替换 App 段，用户段原样保留
        val userContent = extractUserSection(existing)
        write(file, render(ctx) + userSection(userContent))
        Log.i(TAG, "AGENTS.md upgraded to v$SEED_VERSION (user section preserved, ${userContent?.length ?: 0} chars)")
    }

    /** 读取用户段（设置页编辑用）；null 表示还没有用户内容。 */
    fun readUserSection(ctx: Context): String? {
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        val text = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return null
        return extractUserSection(text)
    }

    /** 覆写用户段（设置页保存）；App 段永不被此处修改。 */
    fun writeUserSection(ctx: Context, content: String?) {
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        val existing = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
        val body = if (existing == null) render(ctx) else appSectionOf(existing)
        write(file, body + userSection(content))
        Log.i(TAG, "AGENTS.md user section saved (${content?.length ?: 0} chars)")
    }

    /** 取 App 段（用户段之前的部分）；没有用户段就是全文。 */
    private fun appSectionOf(text: String): String {
        val i = text.indexOf(USER_SECTION_BEGIN)
        return if (i < 0) text.trimEnd() + "\n" else text.substring(0, i)
    }

    /** 从正文里抽出用户段内容；无分隔标记时返回 null。 */
    private fun extractUserSection(text: String): String? {
        val begin = text.indexOf(USER_SECTION_BEGIN)
        if (begin < 0) return null
        val bodyStart = text.indexOf('\n', begin).let { if (it < 0) text.length else it + 1 }
        val end = text.indexOf(USER_SECTION_END, bodyStart)
        val body = if (end < 0) text.substring(bodyStart) else text.substring(bodyStart, end)
        val cleaned = body.replace(USER_SECTION_PLACEHOLDER, "").trim()
        return cleaned.ifEmpty { null }
    }

    /** 组装用户段（空内容时给占位提示）。 */
    private fun userSection(content: String?): String = buildString {
        append("\n\n").append(USER_SECTION_BEGIN).append('\n')
        val body = content?.trim().orEmpty()
        if (body.isEmpty()) {
            append(USER_SECTION_PLACEHOLDER).append('\n')
        } else {
            append(body).append('\n')
        }
        append(USER_SECTION_END).append('\n')
    }

    /** 写文件（父目录按需创建；异常只记日志）。 */
    private fun write(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.onFailure { Log.w(TAG, "write AGENTS.md failed: ${it.message}") }
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
