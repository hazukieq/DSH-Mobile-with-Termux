package app.dsh.mobile.webview

import android.content.Context
import android.util.Log

/**
 * WebView 注入脚本的资产加载器（Phase 4）。
 *
 * 脚本正文放在 assets/js 目录下的独立 js 文件，好处是可被编辑器/lint 当 JS 检查、也能直接在
 * WebView DevTools 里对照源码，而 Kotlin 侧只保留占位符替换与缓存。
 * 用 [init] 在进程启动时注入 applicationContext；读取失败返回空串（调用方降级）。
 */
internal object JsAssets {

    private const val TAG = "DshJsAssets"

    private var appCtx: Context? = null
    private val cache = HashMap<String, String>()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun read(name: String): String {
        cache[name]?.let { return it }
        val ctx = appCtx ?: run {
            Log.w(TAG, "not initialised; returning empty script for $name")
            return ""
        }
        val text = runCatching {
            ctx.assets.open("js/$name").bufferedReader().use { it.readText() }
        }.onFailure { Log.w(TAG, "read js/$name failed: ${it.message}") }.getOrDefault("")
        cache[name] = text
        return text
    }
}
