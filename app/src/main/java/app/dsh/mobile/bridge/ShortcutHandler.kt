package app.dsh.mobile.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import app.dsh.mobile.engine.AgentBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * App 入口探测：manifest 里 exported 的「快捷方式类组件」+ 通用 Intent 解析。
 *
 * 为什么不走 LauncherApps / dumpsys：
 * - 跨包 `LauncherApps.getShortcuts` 要求调用方是默认桌面，普通 App 只拿得到自己的；
 * - `dumpsys shortcut` 需要 root / Shizuku，且一次 3–20s，设备上经常不可用。
 * - 但 manifest 里的 exported 组件（`CREATE_SHORTCUT`、`SET_ALARM`、`VIEW_DOWNLOADS` …）
 *   用 PackageManager 就能读，**只读、免权限、免 a11y、单包 < 100ms**，正是 agent 最稳的入口。
 *
 * 本类因此以 manifest 层为主（`list`），动态快捷方式仅在显式 `?dynamic=1` 时补充查询。
 * `resolveIntent` 等价 `PackageManager.queryIntentActivities`，用于「只知道 action 不知道 component」的场景。
 */
internal class ShortcutHandler(private val bridge: AgentBridge) {

    /** `GET /shortcuts?package=pkg[&dynamic=1]` */
    internal fun list(ctx: Context, query: String): Pair<Int, String> = with(bridge) {
        val q = parseQuery(query)
        val pkg = q["package"].orEmpty()
        if (pkg.isBlank()) return 400 to """{"ok":false,"error":"package required"}"""
        val wantDynamic = q["dynamic"] == "1" || q["dynamic"] == "true"
        val t0 = System.currentTimeMillis()
        val items = runCatching { manifestEntries(ctx, pkg) }.getOrDefault(emptyList())
        val out = JSONArray()
        items.forEach { out.put(it) }
        var dynamicCount = 0
        if (wantDynamic) {
            val seen = items.map { it.optString("component") }.toSet()
            val dyn = runCatching { launcherShortcuts(ctx, pkg) }.getOrDefault(emptyList())
            dyn.forEach { o -> if (o.optString("component") !in seen) { out.put(o); dynamicCount++ } }
        }
        return 200 to JSONObject().put("ok", true)
            .put("package", pkg)
            .put("count", out.length())
            .put("dynamic", dynamicCount)
            .put("shortcuts", out)
            .put("elapsed_ms", System.currentTimeMillis() - t0)
            .toString()
    }

    /** 探测用的已知 action（manifest 的 intent-filter 无法用公开 API 读，改为正向探测）。 */
    private val PROBES = listOf(
        "android.intent.action.CREATE_SHORTCUT" to null,
        Intent.ACTION_MAIN to Intent.CATEGORY_LAUNCHER,
        "android.intent.action.SET_ALARM" to null,
        "android.intent.action.SET_TIMER" to null,
        "android.intent.action.SHOW_ALARMS" to null,
        "android.intent.action.SET_STOPWATCH" to null,
        "android.intent.action.VIEW_DOWNLOADS" to null,
        Intent.ACTION_VIEW to null,
        Intent.ACTION_SEND to null,
        "android.intent.action.WEB_SEARCH" to null,
        Intent.ACTION_DIAL to null,
        Intent.ACTION_CALL to null,
        Intent.ACTION_EDIT to null
    )

    /**
     * 扫 manifest：只收 exported 的 activity / activity-alias。
     * intent-filter 不在公开 API 里（`GET_INTENT_FILTERS` 是隐藏标记），因此改为「按已知 action 正向探测」，
     * 把探测到的 action / category 挂回对应组件——单包十来次 queryIntentActivities，实测远低于 100ms。
     */
    private fun manifestEntries(ctx: Context, pkg: String): List<JSONObject> {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33)
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
        else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
        val acts = info.activities.orEmpty().filter { it.exported }
        if (acts.isEmpty()) return emptyList()

        val actionMap = HashMap<String, LinkedHashSet<String>>()
        val categoryMap = HashMap<String, LinkedHashSet<String>>()
        PROBES.forEach { (action, cat) ->
            val probe = Intent(action).apply { setPackage(pkg); if (cat != null) addCategory(cat) }
            runCatching { pm.queryIntentActivities(probe, PackageManager.MATCH_ALL) }.getOrDefault(emptyList())
                .forEach { r ->
                    val ai = r.activityInfo ?: return@forEach
                    if (ai.packageName != pkg) return@forEach
                    val key = ai.name
                    actionMap.getOrPut(key) { LinkedHashSet() }.add(action)
                    if (cat != null) categoryMap.getOrPut(key) { LinkedHashSet() }.add(cat)
                }
        }

        return acts.map { a ->
            val actions = actionMap[a.name] ?: LinkedHashSet()
            val categories = categoryMap[a.name] ?: LinkedHashSet()
            JSONObject()
                .put("kind", "activity")
                .put("component", "$pkg/${a.name}")
                .put("label", runCatching { a.loadLabel(pm).toString() }.getOrDefault(""))
                .put("exported", true)
                .put("permission", a.permission ?: JSONObject.NULL)
                .put("process", a.processName ?: JSONObject.NULL)
                .put("actions", JSONArray(actions.toList()))
                .put("categories", JSONArray(categories.toList()))
                .put("hasLauncherIntent", actions.contains(Intent.ACTION_MAIN) &&
                    categories.contains(Intent.CATEGORY_LAUNCHER))
                .put("target", JSONObject.NULL)
                .put("source", "manifest")
        }.sortedWith(compareBy(
            { (it.optJSONArray("actions")?.toString() ?: "").contains("CREATE_SHORTCUT").not() },
            { it.optBoolean("hasLauncherIntent") },
            { it.optString("component") }
        ))
    }

    /** `POST /resolve-intent`：PackageManager.queryIntentActivities，返回所有候选。 */
    internal fun resolveIntent(ctx: Context, body: String): Pair<Int, String> = with(bridge) {
        val o = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val action = o.optString("action").takeIf { it.isNotBlank() }
            ?: return 400 to """{"ok":false,"error":"action required"}"""
        val intent = Intent(action)
        o.optString("category").takeIf { it.isNotBlank() }?.let { intent.addCategory(it) }
        o.optString("data").takeIf { it.isNotBlank() }?.let { intent.data = android.net.Uri.parse(it) }
        o.optString("package").takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
        o.optString("mime").takeIf { it.isNotBlank() }?.let { intent.type = it }
        o.optJSONObject("extras")?.let { ex ->
            ex.keys().forEach { k ->
                val v = ex.get(k)
                when (v) {
                    is Int -> intent.putExtra(k, v)
                    is Long -> intent.putExtra(k, v)
                    is Boolean -> intent.putExtra(k, v)
                    is String -> intent.putExtra(k, v)
                    else -> intent.putExtra(k, v.toString())
                }
            }
        }
        val pm = ctx.packageManager
        val flags = PackageManager.MATCH_ALL or PackageManager.MATCH_DEFAULT_ONLY
        val res = runCatching { pm.queryIntentActivities(intent, flags) }.getOrDefault(emptyList())
        val arr = JSONArray()
        res.forEach { r ->
            val ai = r.activityInfo ?: return@forEach
            arr.put(JSONObject()
                .put("package", ai.packageName)
                .put("activity", ai.name)
                .put("component", "${ai.packageName}/${ai.name}")
                .put("label", runCatching { ai.loadLabel(pm).toString() }.getOrDefault(""))
                .put("exported", ai.exported)
                .put("permission", ai.permission ?: JSONObject.NULL)
                .put("priority", r.priority)
                .put("isDefault", r.isDefault)
                .put("enabled", ai.enabled)
                .put("process", ai.processName ?: JSONObject.NULL))
        }
        val best = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }
            .getOrNull()
        return 200 to JSONObject().put("ok", true)
            .put("action", action)
            .put("count", arr.length())
            .put("candidates", arr)
            .put("best", best?.let { "${it.packageName}/${it.name}" } ?: JSONObject.NULL)
            .toString()
    }

    /** `POST /shortcut/launch {package,id}`：按 dumpsys 里的 intent 启动（需要特权，拿不到就报原因）。 */
    internal fun launch(ctx: Context, body: String): Pair<Int, String> = with(bridge) {
        val o = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val pkg = o.optString("package")
        val id = o.optString("id")
        if (pkg.isBlank() || id.isBlank()) return 400 to """{"ok":false,"error":"package and id required"}"""
        val la = launcherApps(ctx)
        if (la != null) {
            val err = runCatching { la.startShortcut(pkg, id, null, null, Process.myUserHandle()) }.exceptionOrNull()
            if (err == null) {
                return 200 to JSONObject().put("ok", true).put("package", pkg).put("id", id)
                    .put("method", "launcherapps").toString()
            }
        }
        val started = runCatching { launchDynamicById(ctx, pkg, id) }.getOrDefault(false)
        return if (started) 200 to JSONObject().put("ok", true).put("package", pkg).put("id", id)
            .put("method", "dumpsys-intent").put("note", "requires root/Shizuku").toString()
        else 404 to """{"ok":false,"error":"shortcut not found or not launchable without privilege"}"""
    }

    private fun launcherApps(ctx: Context): LauncherApps? =
        ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

    private fun launcherShortcuts(ctx: Context, pkg: String): List<JSONObject> = runCatching {
        val la = launcherApps(ctx) ?: return emptyList()
        la.getShortcuts(LauncherApps.ShortcutQuery().setPackage(pkg), Process.myUserHandle())
            .orEmpty().map { s ->
                JSONObject().put("kind", "launcher-shortcut").put("id", s.id).put("label", s.shortLabel?.toString() ?: "")
                    .put("longLabel", s.longLabel?.toString() ?: "")
                    .put("enabled", s.isEnabled).put("source", "launcherapps")
                    .put("component", s.activity?.let { "${it.packageName}/${it.className}" } ?: JSONObject.NULL)
            }
    }.getOrDefault(emptyList())

    /** dumpsys shortcut 里的 intent 拼出来直接 startActivity（特权通道；无特权返回 false）。 */
    private fun launchDynamicById(ctx: Context, pkg: String, id: String): Boolean {
        val dump = runCatching { app.dsh.mobile.engine.Privilege.runSu("dumpsys shortcut", 30_000L) }.getOrNull()
            ?: runCatching { app.dsh.mobile.engine.Privilege.shizukuExec(ctx, "dumpsys shortcut") }.getOrNull()
            ?: return false
        var inPkg = false
        var inTarget = false
        var component: String? = null
        var action: String? = null
        dump.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith("Package: ")) { inPkg = t.removePrefix("Package: ").startsWith(pkg); inTarget = false }
            if (inPkg && t.contains("ShortcutInfo {id=")) inTarget = t.contains("id=$id,") || t.contains("id=$id ")
            if (inPkg && inTarget) {
                if (component == null) Regex("cmp=([\\w.]+/[\\w.$]+)").find(t)?.let { component = it.groupValues[1] }
                if (action == null) Regex("act=([\\w.]+)").find(t)?.let { action = it.groupValues[1] }
            }
        }
        val cmp = component ?: return false
        val parts = cmp.split("/")
        if (parts.size != 2) return false
        val cn = ComponentName(parts[0], if (parts[1].startsWith(".")) parts[0] + parts[1] else parts[1])
        val intent = Intent(action ?: Intent.ACTION_MAIN).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setComponent(cn)
        }
        return runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
    }
}
