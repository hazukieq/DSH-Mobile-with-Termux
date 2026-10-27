package app.dsh.mobile

import android.webkit.JavascriptInterface

/** 发布包日志出口：页面里 window.DshLog.tail(200) 直接拿 JSON。 */
class DshLogBridge {
    @JavascriptInterface
    fun tail(n: Int): String = DshLog.tailJson(n.coerceIn(1, 800))

    @JavascriptInterface
    fun crash(): String {
        val ctx = appCtx ?: return "[]"
        val f = DshLog.crashFile(ctx)
        if (!f.exists()) return "[]"
        return org.json.JSONArray(f.readLines().takeLast(120)).toString()
    }

    companion object {
        @Volatile var appCtx: android.content.Context? = null
    }
}
