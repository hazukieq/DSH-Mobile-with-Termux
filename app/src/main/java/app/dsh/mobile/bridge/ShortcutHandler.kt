package app.dsh.mobile.bridge

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process
import app.dsh.mobile.engine.AgentBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * App 快捷方式（launcher shortcuts）探测与启动。
 *
 * 为什么单独一层：Android 的「长按图标」菜单（静态 manifest 快捷方式 + 动态快捷方式 +
 * 已固定快捷方式）**只能**通过 LauncherApps 查询，普通 PackageManager 看不到；而很多 App 的
 * 高频入口（微信「扫一扫」、支付宝「付款码」、B 站「稍后再看」）恰好只以快捷方式暴露。
 * 有了它，agent 不必自己长按 → 截图 → 找坐标，直接列 id 再 startShortcut。
 */
internal class ShortcutHandler(private val bridge: AgentBridge) {

    // dumpsys shortcut 要 3–20s，连续查询（列多个 App / 启动前再查）必须复用结果。
    @Volatile private var dumpCache: String = ""
    @Volatile private var dumpCacheAt: Long = 0L

    private fun shortcutDump(ctx: Context): String {
        val now = System.currentTimeMillis()
        val cached = dumpCache
        if (cached.isNotEmpty() && now - dumpCacheAt < 30_000L) return cached
        val dump = runCatching { app.dsh.mobile.engine.Privilege.runSu("dumpsys shortcut", 30_000L) }.getOrNull()
            ?: runCatching { app.dsh.mobile.engine.Privilege.shizukuExec(ctx, "dumpsys shortcut") }.getOrNull()
            ?: ""
        if (dump.isNotBlank()) { dumpCache = dump; dumpCacheAt = now }
        return dump
    }

    private fun launcherApps(ctx: Context): LauncherApps? =
        ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

    /**
     * GET /shortcuts?package=<pkg>：列出某个 App 的快捷方式。
     *
     * 两条来源，先能拿到的先用：
     * 1) **静态快捷方式**（`AndroidManifest` 的 `android.app.shortcuts` 元数据指向的 XML）——
     *    用 `getResourcesForApplication(pkg)` 直接解析，**不需要任何权限**，fragment 里能拿到
     *    `shortcutId` / 标签 / 目标 intent，agent 可以直接照着 intent 起。
     * 2) **动态/已固定快捷方式**只能通过 `LauncherApps` 查，而跨包查询要求调用方是默认桌面
     *    （否则 `getShortcuts` 只返回自己的），所以这里作为补充；拿不到就如实说明。
     */
    internal fun list(ctx: Context, query: String): Pair<Int, String> = with(bridge) {
        val pkg = parseQuery(query)["package"].orEmpty()
        if (pkg.isBlank()) return 400 to """{"ok":false,"error":"package required"}"""
        val out = JSONArray()
        val staticList = runCatching { readManifestShortcuts(ctx, pkg) }.getOrDefault(emptyList())
        staticList.forEach { out.put(it) }
        val dynamic = runCatching { launcherShortcuts(ctx, pkg) }.getOrDefault(emptyList())
        val privileged = runCatching { privilegedShortcuts(ctx, pkg) }.getOrDefault(emptyList())
        val seen = staticList.map { it.getString("id") }.toSet()
        dynamic.forEach { o -> if (o.getString("id") !in seen) out.put(o) }
        val seenAll = (0 until out.length()).map { out.getJSONObject(it).getString("id") }.toSet()
        privileged.forEach { o -> if (o.getString("id") !in seenAll) out.put(o) }
        return 200 to JSONObject().put("ok", true).put("package", pkg)
            .put("count", out.length())
            .put("static", staticList.size)
            .put("dynamic", dynamic.size)
            .put("privileged", privileged.size)
            .put("shortcuts", out).toString()
    }

    /**
     * 特权路径：`dumpsys shortcut` 能给出**动态/已固定**快捷方式（含 id、标签、目标 intent），
     * 这是普通 App 无法通过 LauncherApps 跨包拿到的。需要 root 或 Shizuku。
     */
    private fun privilegedShortcuts(ctx: Context, pkg: String): List<JSONObject> {
        val dump = shortcutDump(ctx)
        if (dump.isEmpty()) return emptyList()
        if (dump.isBlank()) return emptyList()
        val list = ArrayList<JSONObject>()
        var inTarget = false
        var cur: JSONObject? = null
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("Package: ")) {
                val name = line.removePrefix("Package: ").substringBefore(' ').trim()
                inTarget = name == pkg
                cur = null
                continue
            }
            if (!inTarget) continue
            val idx = line.indexOf("ShortcutInfo {id=")
            if (idx >= 0) {
                cur?.let { list.add(it) }
                val id = line.substring(idx + "ShortcutInfo {id=".length).substringBefore(',')
                cur = JSONObject().put("id", id.trim()).put("source", "dumpsys")
                continue
            }
            val o = cur ?: continue
            when {
                line.startsWith("shortLabel=") -> o.put("shortLabel", line.removePrefix("shortLabel=").substringBefore(','))
                line.startsWith("longLabel=") -> o.put("longLabel", line.removePrefix("longLabel=").substringBefore(','))
                line.startsWith("disabledReason=") -> o.put("disabledReason", line.removePrefix("disabledReason=").trim())
                line.contains("intents=[Intent {") -> {
                    Regex("act=([^\\s]+)").find(line)?.let { o.put("action", it.groupValues[1]) }
                    Regex("cmp=([^\\s}/]+/[^\\s}]+)").find(line)?.let { m ->
                        o.put("activity", m.groupValues[1])
                        val parts = m.groupValues[1].split("/")
                        o.put("targetPackage", parts[0])
                    }
                    o.put("enabled", o.optString("disabledReason").contains("Not disabled", true))
                }
            }
        }
        cur?.let { list.add(it) }
        return list
    }

    /** 解析 `android.app.shortcuts` 元数据里的 XML（纯读资源，无权限要求）。 */
    private fun readManifestShortcuts(ctx: Context, pkg: String): List<JSONObject> {
        val pm = ctx.packageManager
        val ai = pm.getApplicationInfo(pkg, android.content.pm.PackageManager.GET_META_DATA)
        val resId = ai.metaData?.getInt("android.app.shortcuts", 0) ?: 0
        if (resId == 0) return emptyList()
        val res = pm.getResourcesForApplication(ai)
        val parser = res.getXml(resId)
        val list = ArrayList<JSONObject>()
        var depth = 0
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                if (parser.name == "shortcut") {
                    val o = JSONObject()
                        .put("id", parser.getAttributeValue(null, "shortcutId").orEmpty())
                        .put("shortLabel", parser.getAttributeValue(null, "shortLabelRes").orEmpty())
                        .put("enabled", parser.getAttributeValue(null, "enabled")?.toBoolean() ?: true)
                        .put("source", "manifest")
                    list.add(o)
                    // 解析该 <shortcut> 下的 <intent>
                    val inner = readShortcutIntent(parser)
                    if (inner != null) {
                        o.put("action", inner.first)
                        inner.second?.let { o.put("data", it) }
                        o.put("activity", inner.third)
                    }
                }
            }
        }
        return list
    }

    /** 读取当前 <shortcut> 子元素里的第一个 <intent>，返回 (action, data, component)。 */
    private fun readShortcutIntent(parser: org.xmlpull.v1.XmlPullParser): Triple<String, String?, String?>? {
        val startDepth = parser.depth
        var action = ""
        var data: String? = null
        var target = ""
        while (true) {
            val ev = parser.next()
            if (ev == org.xmlpull.v1.XmlPullParser.END_DOCUMENT) return null
            if (ev == org.xmlpull.v1.XmlPullParser.END_TAG && parser.depth == startDepth) return null
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "action" -> action = parser.getAttributeValue(null, "action").orEmpty()
                    "data" -> data = parser.getAttributeValue(null, "data").orEmpty()
                    "targetClass" -> target = parser.getAttributeValue(null, "class").orEmpty()
                }
                if (action.isNotEmpty() && target.isNotEmpty()) return Triple(action, data, target)
            }
        }
    }

    /** LauncherApps 视角（跨包通常只返回已固定/自己的，拿不到就空）。 */
    private fun launcherShortcuts(ctx: Context, pkg: String): List<JSONObject> {
        val la = launcherApps(ctx) ?: return emptyList()
        val q = LauncherApps.ShortcutQuery().apply {
            setPackage(pkg)
            setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
        }
        val items = runCatching { la.getShortcuts(q, Process.myUserHandle()) }.getOrNull()
            ?: return emptyList()
        return items.map { toJson(ctx, it) }
    }

    /** POST /shortcut/launch {package, id}：直接启动某个快捷方式。 */
    internal fun launch(ctx: Context, body: String): Pair<Int, String> = with(bridge) {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val pkg = obj.optString("package").ifBlank { obj.optString("app") }
        val id = obj.optString("id")
        if (pkg.isBlank() || id.isBlank()) return 400 to """{"ok":false,"error":"package and id required"}"""
        val la = launcherApps(ctx) ?: return 503 to """{"ok":false,"error":"launcher apps unavailable"}"""
        // 优先 LauncherApps（能处理动态快捷方式）；失败则退回静态 XML 里解析出的 intent 直接起。
        val err = runCatching {
            la.startShortcut(pkg, id, null, null, Process.myUserHandle())
        }.exceptionOrNull()
        if (err != null) {
            val viaDump = runCatching { launchFromDumpIntent(ctx, pkg, id) }.getOrNull()
            if (viaDump == true) {
                return 200 to JSONObject().put("ok", true).put("package", pkg).put("id", id)
                    .put("method", "dumpsys-intent").put("started", "$pkg#${id}").toString()
            }
            val fallback = runCatching { launchFromManifestIntent(ctx, pkg, id) }.getOrNull()
            if (fallback == true) {
                return 200 to JSONObject().put("ok", true).put("package", pkg).put("id", id)
                    .put("method", "manifest-intent").put("started", "$pkg#${id}").toString()
            }
        }
        return if (err == null) {
            200 to JSONObject().put("ok", true).put("package", pkg).put("id", id)
                .put("started", "$pkg#${id}").toString()
        } else {
            404 to JSONObject().put("ok", false).put("package", pkg).put("id", id)
                .put("error", err.message ?: err.javaClass.simpleName).toString()
        }
    }

    private fun toJson(ctx: Context, s: ShortcutInfo): JSONObject {
        val o = JSONObject()
            .put("id", s.id)
            .put("package", s.`package`)
            .put("shortLabel", s.shortLabel?.toString().orEmpty())
            .put("longLabel", s.longLabel?.toString().orEmpty())
            .put("enabled", s.isEnabled)
            .put("disabledMessage", s.disabledMessage?.toString().orEmpty())
            .put("pinned", s.isPinned)
            .put("declaredInManifest", s.isDeclaredInManifest)
            .put("dynamic", s.isDynamic)
        s.intent?.let { i ->
            o.put("activity", i.component?.flattenToShortString().orEmpty())
            o.put("action", i.action.orEmpty())
            o.put("data", i.dataString.orEmpty())
        }
        o.put("categories", JSONArray(s.categories ?: emptySet<String>()))
        return o
    }

    /** 用 manifest 里声明的 intent 直接启动（LauncherApps 被拒时的兜底）。 */
    private fun launchFromManifestIntent(ctx: Context, pkg: String, id: String): Boolean? {
        val item = readManifestShortcuts(ctx, pkg).firstOrNull { it.optString("id") == id } ?: return null
        val action = item.optString("action")
        if (action.isBlank()) return null
        val intent = android.content.Intent(action).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            item.optString("data").takeIf { it.isNotBlank() }?.let { data = android.net.Uri.parse(it) }
            item.optString("activity").takeIf { it.isNotBlank() }?.let { cls ->
                val parts = cls.split("/")
                if (parts.size == 2) {
                    val p = parts[0]
                    val c = if (parts[1].startsWith(".")) p + parts[1] else parts[1]
                    component = android.content.ComponentName(p, c)
                }
            }
        }
        if (intent.component == null) intent.setPackage(pkg)
        return runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
    }

    /** 用 dumpsys 里该快捷方式的显式 intent 启动（动态快捷方式走这条）。 */
    private fun launchFromDumpIntent(ctx: Context, pkg: String, id: String): Boolean? {
        val item = privilegedShortcuts(ctx, pkg).firstOrNull { it.optString("id") == id } ?: return null
        val action = item.optString("action").ifBlank { android.content.Intent.ACTION_MAIN }
        val intent = android.content.Intent(action).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            item.optString("activity").takeIf { it.contains('/') }?.let { cls ->
                val p = cls.substringBefore('/')
                val c = cls.substringAfter('/').let { if (it.startsWith(".")) p + it else it }
                component = android.content.ComponentName(p, c)
            }
        }
        if (intent.component == null) intent.setPackage(pkg)
        return runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
    }
}
