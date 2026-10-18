package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 插件启动故障的有限自愈。
 *
 * Cordis 会把插件 import/init 失败留在 Loader 树里而不一定让 HTTP 进程退出，
 * 所以仅看端口或 supervisor 的崩溃计数无法判断工作区能力是否真的存在。本类读取
 * 内置 health probe 的一次性标记，按失败 entry 反查 profile bundle，并只从
 * `dsh.profile.bundles` 摘掉一个第三方 bundle。package.json、lockfile 与插件文件
 * 保留，用户可在插件中心重新启用；修改前另存备份，且连续自愈有硬上限。
 */
object PluginRecovery {

    private const val TAG = "PluginRecovery"
    private const val HEALTH_MARKER = ".android-plugin-health.json"
    private const val STATE_FILE = "plugin-recovery.state"
    private const val BACKUP_DIR = "plugin-recovery"
    private const val MAX_ATTEMPTS = 3

    data class FailedEntry(val id: String, val name: String, val error: String = "")

    data class Health(
        val ok: Boolean,
        val missingServices: List<String>,
        val failedEntries: List<FailedEntry>,
        val bundles: List<String>,
    )

    data class Action(
        val packageName: String,
        val reason: String,
        val attempt: Int,
    )

    fun markerFile(ctx: Context): File = File(EngineConfig.dshHome(ctx), HEALTH_MARKER)

    /** 每次 fork 前清掉旧标记，避免上一次崩溃的诊断结果误伤下一次启动。 */
    fun prepareForBoot(ctx: Context) {
        runCatching { markerFile(ctx).delete() }
            .onFailure { Log.w(TAG, "cannot clear health marker: ${it.message}") }
    }

    /** 本次 boot 已通过健康检查：允许下一次独立故障重新使用有限自愈额度。 */
    fun onHealthy(ctx: Context) {
        clearState(ctx)
    }

    fun readHealth(ctx: Context, expectedBootId: String? = null): Health? = runCatching {
        val file = markerFile(ctx)
        if (!file.isFile) return@runCatching null
        val json = JSONObject(file.readText())
        // 删除 marker 可能因旧 Root 属主失败；bootId 是第二道防线，
        // 防止把上一次引擎的 degraded 结果应用到本次正常启动。
        if (expectedBootId != null && json.optString("bootId") != expectedBootId) {
            return@runCatching null
        }
        val failed = mutableListOf<FailedEntry>()
        val failedArray = json.optJSONArray("failedEntries") ?: JSONArray()
        for (i in 0 until failedArray.length()) {
            val row = failedArray.optJSONObject(i) ?: continue
            failed += FailedEntry(
                id = row.optString("id"),
                name = row.optString("name"),
                error = row.optString("error"),
            )
        }
        Health(
            ok = json.optBoolean("ok", false),
            missingServices = json.optStringArray("missingServices"),
            failedEntries = failed,
            bundles = json.optStringArray("bundles"),
        )
    }.onFailure {
        Log.w(TAG, "cannot read health marker: ${it.message}")
    }.getOrNull()

    /**
     * 对一次 degraded boot 做有限处理；返回 null 表示没有可安全归因的用户 bundle，
     * 此时由监督器继续普通退避并把完整诊断留在 engine.log，不会盲目删除用户配置。
     */
    fun quarantineOne(ctx: Context, health: Health): Action? {
        if (health.ok) {
            clearState(ctx)
            return null
        }
        val state = readState(ctx)
        if (state.attempts >= MAX_ATTEMPTS) {
            Log.e(
                TAG,
                "auto-disable limit reached ($MAX_ATTEMPTS); " +
                    "leaving profile unchanged (missing=${health.missingServices}, failed=${health.failedEntries})",
            )
            return null
        }

        val profileDir = File(EngineConfig.dshHome(ctx), "profiles/web")
        val manifestFile = File(profileDir, "package.json")
        if (!manifestFile.isFile) {
            Log.w(TAG, "web profile manifest is missing; cannot quarantine a plugin")
            return null
        }
        val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrElse {
            Log.e(TAG, "cannot parse web profile manifest: ${it.message}", it)
            return null
        }
        val bundles = manifest.optJSONObject("dsh")
            ?.optJSONObject("profile")
            ?.optJSONArray("bundles")
            ?: JSONArray()
        val names = (0 until bundles.length()).mapNotNull { bundles.optString(it).takeIf(String::isNotBlank) }
        val userBundles = names.filter(::isUserBundle)
        if (userBundles.isEmpty()) {
            Log.e(TAG, "degraded boot has no removable third-party bundle; profile was not changed")
            return null
        }

        val candidates = userBundles.filter { bundle ->
            val rows = readBundleRows(profileDir, bundle)
            health.failedEntries.any { failed ->
                rows.any { row ->
                    (failed.id.isNotBlank() && row.first == failed.id) ||
                        (failed.name.isNotBlank() && row.second == failed.name)
                }
            }
        }
        // 没有失败 entry 与用户 bundle 的精确对应关系时绝不猜测：
        // workspaceRegistry 的持久化故障、内置包损坏等情况不能靠“最后安装的插件”背锅。
        // 这时只保留健康探针和 engine.log 诊断，交给下一层 ProfileGuardian/用户处理。
        val target = candidates.lastOrNull() ?: run {
            Log.e(TAG, "degraded boot has no exact third-party bundle attribution; profile was not changed")
            return null
        }
        val attempt = state.attempts + 1
        val reason = buildReason(health, exact = true)

        return runCatching {
            backupManifest(ctx, manifestFile, target)
            val nextBundles = JSONArray()
            names.filterNot { it == target }.forEach(nextBundles::put)
            val dsh = manifest.optJSONObject("dsh") ?: JSONObject().also { manifest.put("dsh", it) }
            val profile = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
            profile.put("bundles", nextBundles)
            atomicWrite(manifestFile, manifest.toString(2) + "\n")
            writeState(ctx, attempt, target)
            Log.e(
                TAG,
                "auto-disabled third-party plugin $target (attempt=$attempt/$MAX_ATTEMPTS, " +
                    "reason=$reason); package and lockfile were kept",
            )
            Action(target, reason, attempt)
        }.onFailure {
            Log.e(TAG, "failed to disable plugin $target: ${it.message}", it)
        }.getOrNull()
    }

    private data class State(val attempts: Int, val lastPackage: String?)

    private fun readState(ctx: Context): State {
        val file = File(EngineConfig.dshHome(ctx), STATE_FILE)
        val parts = runCatching { file.readText().trim().split('|', limit = 2) }.getOrNull()
            ?: return State(0, null)
        return State(parts.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(0) ?: 0, parts.getOrNull(1))
    }

    private fun writeState(ctx: Context, attempts: Int, packageName: String) {
        val file = File(EngineConfig.dshHome(ctx), STATE_FILE)
        atomicWrite(file, "$attempts|$packageName\n")
    }

    private fun clearState(ctx: Context) {
        runCatching { File(EngineConfig.dshHome(ctx), STATE_FILE).delete() }
    }

    private fun isUserBundle(name: String): Boolean =
        !name.startsWith("@deepseek-ai/") &&
            name != "dsh-base" &&
            name != "dsh-web-app"

    /** 只读取 bundle patch 的 id/name，不执行其中的 JavaScript 表达式。 */
    private fun readBundleRows(profileDir: File, packageName: String): Set<Pair<String, String>> {
        val packageDir = File(File(profileDir, "node_modules"), packageName)
            .let { runCatching { it.canonicalFile }.getOrDefault(it) }
        val manifestFile = File(packageDir, "package.json")
        val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull() ?: return emptySet()
        val bundle = manifest.optJSONObject("dsh")?.optJSONObject("bundle") ?: return emptySet()
        val patch = bundle.opt("patch")
        val patchFiles = when (patch) {
            is String -> listOf(patch)
            is JSONArray -> (0 until patch.length()).mapNotNull { patch.optString(it).takeIf(String::isNotBlank) }
            else -> emptyList()
        }
        return patchFiles.flatMap { relative ->
            val file = File(packageDir, relative)
            parsePatchRows(runCatching { file.readText() }.getOrDefault(""))
        }.toSet()
    }

    private fun parsePatchRows(text: String): Set<Pair<String, String>> {
        val rows = mutableSetOf<Pair<String, String>>()
        var id: String? = null
        var name = ""
        fun flush() {
            val current = id ?: return
            rows += current to name
            id = null
            name = ""
        }
        for (line in text.lineSequence()) {
            val idMatch = Regex("^\\s*-\\s*id:\\s*(.+?)\\s*$").find(line)
            if (idMatch != null) {
                flush()
                id = yamlScalar(idMatch.groupValues[1])
                continue
            }
            if (id != null) {
                val nameMatch = Regex("^\\s*name:\\s*(.+?)\\s*$").find(line)
                if (nameMatch != null) name = yamlScalar(nameMatch.groupValues[1])
            }
        }
        flush()
        return rows
    }

    private fun yamlScalar(value: String): String = value.trim()
        .removeSuffix(",")
        .removeSurrounding("'", "'")
        .removeSurrounding("\"", "\"")
        .substringBefore(" #")
        .trim()

    private fun buildReason(health: Health, exact: Boolean): String = buildString {
        append(if (exact) "failed profile row matched bundle" else "degraded host service with no exact bundle row")
        if (health.missingServices.isNotEmpty()) append("; missing=" + health.missingServices.joinToString(","))
        if (health.failedEntries.isNotEmpty()) {
            append("; failed=")
            append(health.failedEntries.joinToString(",") {
                "${it.id}:${it.name}" + if (it.error.isNotBlank()) " (${it.error.lineSequence().firstOrNull()?.take(240)})" else ""
            })
        }
    }

    private fun backupManifest(ctx: Context, source: File, packageName: String) {
        val dir = File(EngineConfig.dshHome(ctx), BACKUP_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val safeName = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        source.copyTo(File(dir, "$stamp-$safeName.package.json"), overwrite = false)
    }

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
        temp.writeText(text, StandardCharsets.UTF_8)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IllegalStateException("rename failed for ${file.name}")
        }
        file.setReadable(true, false)
    }

    private fun JSONObject.optStringArray(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }
}
