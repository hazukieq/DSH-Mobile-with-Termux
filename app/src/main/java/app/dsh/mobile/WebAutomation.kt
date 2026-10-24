package app.dsh.mobile

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * v2.5.0：内嵌调试/自动化浏览器（WebActivity）的控制层。
 *
 * Agent 通过 AgentBridge 的 `/webview/…` 端点或直接 `POST /activity {"name":"web","url":…}`
 * 打开它；页面内的脚本也可以通过 `window.DshWeb` 桥读写基本状态。
 * WebView 的 DevTools（CDP）始终开启，便于外部工具做 DOM/网络级自动化。
 */
object WebAutomation {

    private const val TAG = "WebAutomation"
    @Volatile private var ref: WeakReference<WebActivity>? = null

    fun attach(a: WebActivity) { ref = WeakReference(a) }
    fun detach(a: WebActivity) { if (ref?.get() === a) ref = null }
    fun current(): WebActivity? = ref?.get()

    fun launch(
        ctx: Context, url: String,
        desktop: Boolean = false, insecure: Boolean = false,
        ua: String? = null, filePath: String? = null,
    ): Boolean = runCatching {
        val i = Intent(ctx, WebActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
        if (url.isNotBlank()) i.putExtra("url", url)
        i.putExtra("desktop", desktop)
        i.putExtra("insecure", insecure)
        ua?.takeIf { it.isNotBlank() }?.let { i.putExtra("ua", it) }
        filePath?.takeIf { it.isNotBlank() }?.let { i.putExtra("file", it) }
        ctx.startActivity(i)
        true
    }.getOrElse {
        Log.w(TAG, "launch failed: ${it.message}")
        false
    }

    fun status(): JSONObject = current()?.status() ?: JSONObject()
        .put("ok", false).put("attached", false)
        .put("hint", "open it with POST /activity {\"name\":\"web\",\"url\":\"...\"}")

    fun eval(js: String, timeoutMs: Long = 4000L): Pair<Boolean, String> =
        current()?.evaluate(js, timeoutMs) ?: (false to "webview not open")

    fun action(name: String): Pair<Boolean, String> =
        current()?.action(name) ?: (false to "webview not open")
}
