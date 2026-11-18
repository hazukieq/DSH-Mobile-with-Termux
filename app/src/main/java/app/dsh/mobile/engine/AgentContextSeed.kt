package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/** App-owned environment and preloaded discipline skills, followed by a preserved user section. */
object AgentContextSeed {

    private const val TAG = "AgentContextSeed"
    private const val FILE_NAME = "AGENTS.md"
    private const val MARKER_PREFIX = "<!-- dsh-android AGENTS seed v"
    /** Seed format version; managed text is also reconciled on each startup. */
    private const val SEED_VERSION = 120

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
        "<!-- USER-ONLY SECTION: edit this area yourself (Settings → Agent instructions,\n" +
            "     or a file editor). Agents and tools must never write this file; the app\n" +
            "     reverts external writes and logs them to log/instructions-audit.log. -->"

    fun ensure(ctx: Context) {
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        val existing = runCatching { if (file.isFile) file.readText() else null }.getOrNull()

        deploySkills(ctx)
        val expected = render(ctx)

        // ① 文件不存在 → 写入完整模板（含空的用户段）
        if (existing == null) {
            write(file, expected + userSection(null))
            Log.i(TAG, "AGENTS.md seeded (v$SEED_VERSION)")
            return
        }

        // ② 旧版用户自写文件（无种子标记）：既有内容整体作为用户段保留，
        //    App 段补在上方 —— 既能升级环境说明，也不丢用户内容。
        if (!existing.contains(MARKER_PREFIX)) {
            Log.i(TAG, "AGENTS.md without seed marker; preserved as user section")
            write(file, expected + userSection(existing))
            return
        }

        // ③ 版本最新 → 只在用户段缺失时补占位
        if (containsVersion(existing, SEED_VERSION) && appSectionOf(existing).trimEnd() == expected.trimEnd()) {
            if (!existing.contains(USER_SECTION_BEGIN)) {
                write(file, existing.trimEnd() + "\n" + userSection(null))
            }
            return
        }

        // ④ 需要升级 → 只替换 App 段，用户段原样保留
        val userContent = extractUserSection(existing)
        write(file, expected + userSection(userContent))
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

    /** Update known bundled copies; unrelated or edited skills remain user-owned. */
    private fun deploySkills(ctx: Context) {
        val home = EngineConfig.dshHome(ctx)
        val root = File(home, "skills")
        val state = File(home, ".bundled-skill-state")
        for (name in BundledAgentSkills.names) {
            runCatching {
                val text = ctx.assets.open("skills/$name/SKILL.md").use { it.readBytes().toString(Charsets.UTF_8) }
                if (!BundledAgentSkills.deploy(root, state, name, text)) {
                    Log.w(TAG, "edited skill preserved: $name; APK rules remain preloaded")
                }
            }.onFailure { Log.w(TAG, "skill deployment failed ($name): ${it.message}") }
        }
        // Supporting files are app-owned only when the entrypoint still belongs to this bundle.
        val name = "task-execution-discipline"
        runCatching {
            val text = ctx.assets.open("skills/$name/SKILL.md").use { it.readBytes().toString(Charsets.UTF_8) }
            if (File(root, "$name/SKILL.md").readText() == text) {
                val path = "references/step-reminder.md"
                val reminder = ctx.assets.open("skills/$name/$path").use { it.readBytes().toString(Charsets.UTF_8) }
                if (!BundledAgentSkills.deploy(root, state, name, reminder, path)) {
                    Log.w(TAG, "edited skill reference preserved: $name/$path")
                }
            }
        }.onFailure { Log.w(TAG, "skill reference deployment failed: ${it.message}") }
    }

    /** App-owned content before the user section. */
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
        appWriting.set(true)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
            lastGood = text
        }.onFailure { Log.w(TAG, "write AGENTS.md failed: ${it.message}") }
        appWriting.set(false)
    }

    private fun containsVersion(text: String, v: Int): Boolean =
        text.contains("$MARKER_PREFIX$v ")

    /** 随包内置、且会话一开就必须生效的技能（正文直接注入 AGENTS.md）。 */
    private val ALWAYS_ON_SKILLS = BundledAgentSkills.names

    private fun alwaysOnSkillBlock(ctx: Context): String = buildString {
        for (name in ALWAYS_ON_SKILLS) {
            val body = runCatching {
                ctx.assets.open("skills/$name/SKILL.md").use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: continue
            val text = BundledAgentSkills.body(body)
            append("\n\n## Preloaded discipline skill: ").append(name).append("\n\n")
            append("Apply these rules within the current user task; no skill-tool call is required.\n")
            append("Discovery copy: ${'$'}DSH_HOME/skills/").append(name).append("/SKILL.md\n\n")
            append(text.trim()).append('\n')
        }
    }

    @Volatile private var guardStarted = false
    private val appWriting = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastGood: String? = null

    /**
     * 防止 agent 自改系统提示：agent 与 App 同 uid，文件系统层面挡不住，
     * 所以在进程内看守——外部写入（agent 的 write/edit 工具）会被还原并审计。
     * 用户改文件请走设置页（App 写入时会置位 appWriting，不会被还原）。
     */
    fun startGuard(ctx: Context) {
        if (guardStarted) return
        guardStarted = true
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        lastGood = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
        Log.i(TAG, "AGENTS.md guard armed (bytes=${lastGood?.length ?: 0}, skills=${ALWAYS_ON_SKILLS.joinToString()})")
        Thread({
            while (true) {
                try {
                    Thread.sleep(1500)
                    if (appWriting.get()) continue
                    val expected = lastGood ?: continue
                    val current = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: continue
                    if (current == expected) continue
                    val stamp = System.currentTimeMillis()
                    val backup = File(file.parentFile, "AGENTS.md.tampered-$stamp")
                    runCatching { backup.writeText(current) }
                    appWriting.set(true)
                    runCatching { file.writeText(expected) }
                    appWriting.set(false)
                    lastGood = expected
                    audit(ctx, "reverted external write to AGENTS.md (bytes ${current.length} -> ${expected.length}); backup=${backup.name}")
                    Log.w(TAG, "AGENTS.md guard: reverted external modification (backup ${backup.name})")
                } catch (t: Throwable) {
                    runCatching { Log.w(TAG, "AGENTS.md guard: ${t.message}") }
                    runCatching { Thread.sleep(5000) }
                }
            }
        }, "agents-md-guard").apply { isDaemon = true }.start()
    }

    private fun audit(ctx: Context, line: String) = runCatching {
        val dir = File(EngineConfig.dshHome(ctx), "log")
        dir.mkdirs()
        val f = File(dir, "instructions-audit.log")
        f.appendText("${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).format(java.util.Date())} $line\n")
    }

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
        return "$MARKER_PREFIX$SEED_VERSION — managed by DSH Mobile. App section is managed; only the user section is preserved.\n" +
            rendered + alwaysOnSkillBlock(ctx)
    }
}
