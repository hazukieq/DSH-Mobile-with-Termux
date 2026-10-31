package app.dsh.mobile.bridge

import android.content.Context
import app.dsh.mobile.engine.RuntimeUpdater
import org.json.JSONObject

/**
 * `/runtime/…` 端点（引擎层热更新的 status/stage/pull/apply/rollback）。
 *
 * Phase 2 从 AgentBridge 提出：这组端点只依赖 RuntimeUpdater，与无障碍/屏幕/剪贴板
 * 毫无关系，独立成对象后 AgentBridge 的 route() 只留一行分发。
 */
internal object RuntimeHandler {

    /** GET /runtime/status [?format=line] */
    fun status(ctx: Context, query: String): Pair<Int, String> = try {
        val st = RuntimeUpdater.status(ctx)
        if (query.split('&').any { it == "format=line" }) 200 to st.toLine() else 200 to st.toJson()
    } catch (e: Exception) {
        500 to JSONObject().put("ok", false).put("error", e.message ?: "status failed").toString()
    }

    /** POST /runtime/stage body {"path":"/abs/x.zip"} 或 {"url":"https://…","sha256":"…"} */
    fun stage(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val path = obj.optString("path").takeIf { it.isNotBlank() }
        val url = obj.optString("url").takeIf { it.isNotBlank() }
        val src = path ?: url
        if (src == null) {
            return 400 to JSONObject().put("ok", false)
                .put("error", "missing 'path' or 'url'").toString()
        }
        val sha = obj.optString("sha256").takeIf { it.isNotBlank() }
        val err = RuntimeUpdater.stage(ctx, src, sha, isUrl = path == null)
        return BridgeSupport.acceptedOrRejected("stage", err)
    }

    /** GET /runtime/feed [?format=line][&manifest=<url>] */
    fun feed(ctx: Context, query: String): Pair<Int, String> = try {
        val params = BridgeSupport.parseQuery(query)
        val check = RuntimeUpdater.feedCheck(ctx, params["manifest"])
        if (params["format"] == "line") 200 to check.toLine() else 200 to check.toJson()
    } catch (e: Exception) {
        500 to JSONObject().put("ok", false).put("error", e.message ?: "feed check failed").toString()
    }

    /** POST /runtime/pull body {"version":"0.2.1","manifest":"https://…"} → 202 */
    fun pull(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val err = RuntimeUpdater.pull(
            ctx,
            obj.optString("version").takeIf { it.isNotBlank() },
            obj.optString("manifest").takeIf { it.isNotBlank() },
        )
        return BridgeSupport.acceptedOrRejected("pull", err)
    }

    /** POST /runtime/apply → 202（stop → swap → start → health check → auto rollback） */
    fun apply(ctx: Context): Pair<Int, String> =
        BridgeSupport.acceptedOrRejected("apply", RuntimeUpdater.apply(ctx))

    /** POST /runtime/rollback → 202 */
    fun rollback(ctx: Context): Pair<Int, String> =
        BridgeSupport.acceptedOrRejected("rollback", RuntimeUpdater.rollback(ctx))
}
