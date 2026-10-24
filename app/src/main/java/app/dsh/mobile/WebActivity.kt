package app.dsh.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v2.5.0：内置调试/自动化浏览器。
 *
 * 设计目标（用户要求）：给 agent 一个「充分授权」的网页载体——
 *  - 任意 URL（http/https/file/content/about:blank），不拦截导航；
 *  - JS/DOM Storage/Cookie/数据库/媒体自动播放/混合内容全部放开；
 *  - `setWebContentsDebuggingEnabled(true)`：CDP 可连（webview_devtools_remote_<pid>）；
 *  - 页面权限请求（摄像头/麦克风/地理/通知等）自动授予；
 *  - 提供 `window.DshWeb` JS 桥 + `/webview/…` 控制端点（open/eval/back/forward/reload/close）；
 *  - 下载交给系统 DownloadManager；localhost/127.0.0.1 的自签证书自动放行
 *    （其他域名仍要求 `insecure:true` 显式放行，避免默默降级安全）。
 */
class WebActivity : Activity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var attached = false
    private var insecure = false
    private var pendingFile: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)
        insecure = intent.getBooleanExtra("insecure", false)
        pendingFile = intent.getStringExtra("file")

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        setContentView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            blockNetworkLoads = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            if (intent.getBooleanExtra("desktop", false)) {
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
            }
            intent.getStringExtra("ua")?.takeIf { it.isNotBlank() }?.let { userAgentString = it }
            if (Build.VERSION.SDK_INT >= 29) {
                forceDark = WebSettings.FORCE_DARK_ON
                @Suppress("DEPRECATION")
                setForceDark(WebSettings.FORCE_DARK_ON)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                @Suppress("DEPRECATION")
                isAlgorithmicDarkeningAllowed = false
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(Bridge(), "DshWeb")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val scheme = u.scheme?.lowercase() ?: return false
                return when (scheme) {
                    "http", "https", "file", "content", "about", "data", "javascript" -> false
                    else -> {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        true
                    }
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                val host = runCatching { Uri.parse(error?.url ?: "")?.host }.getOrNull().orEmpty()
                if (insecure || host == "localhost" || host == "127.0.0.1" || host.endsWith(".local")) {
                    Log.w(TAG, "ssl error allowed for $host (insecure=$insecure)")
                    handler?.proceed()
                } else {
                    Log.w(TAG, "ssl error rejected for $host (pass insecure:true to allow)")
                    handler?.cancel()
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                // 调试/自动化载体：页面请求的摄像头/麦克风等直接给（用户显式打开该页面）
                runCatching { request?.grant(request.resources) }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, true, false)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?,
            ): Boolean {
                val p = pendingFile
                if (!p.isNullOrBlank()) {
                    pendingFile = null
                    filePathCallback?.onReceiveValue(arrayOf(Uri.fromFile(java.io.File(p))))
                    return true
                }
                return false
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                // 保持简单：进度只用于状态查询（web.progress）
            }
        }
        web.setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
            runCatching {
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                        android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType),
                    )
                }
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Log.i(TAG, "download enqueued: $url")
            }.onFailure { Log.w(TAG, "download failed: ${it.message}") }
        })
        WebAutomation.attach(this)
        attached = true

        val url = intent.getStringExtra("url")?.takeIf { it.isNotBlank() } ?: "about:blank"
        Log.i(TAG, "open $url (desktop=${intent.getBooleanExtra("desktop", false)}, insecure=$insecure)")
        web.loadUrl(url)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        intent.getStringExtra("url")?.takeIf { it.isNotBlank() }?.let { web.loadUrl(it) }
    }

    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        attached = false
        WebAutomation.detach(this)
        runCatching { web.stopLoading(); web.loadUrl("about:blank"); web.clearHistory(); web.removeAllViews(); web.destroy() }
        super.onDestroy()
    }

    // ---------------- 供 AgentBridge / 自动化调用（任意线程） ----------------

    private fun <T> onMain(timeoutMs: Long = 3000L, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching { block() }.getOrNull()
        val latch = CountDownLatch(1)
        var out: T? = null
        val ok = main.post {
            out = runCatching { block() }.getOrNull()
            latch.countDown()
        }
        if (!ok) return null
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return out
    }

    fun status(): JSONObject = onMain { JSONObject().apply {
        put("ok", true)
        put("attached", attached)
        put("url", web.url ?: "")
        put("title", web.title ?: "")
        put("loading", web.progress < 100)
        put("progress", web.progress)
        put("canGoBack", web.canGoBack())
        put("canGoForward", web.canGoForward())
        put("debug", true)
        put("jsBridge", "DshWeb")
    } } ?: JSONObject().put("ok", false).put("error", "main thread timeout")

    fun evaluate(js: String, timeoutMs: Long = 4000L): Pair<Boolean, String> {
        val latch = CountDownLatch(1)
        var result: String? = null
        main.post {
            runCatching {
                web.evaluateJavascript(js) { value ->
                    result = value
                    latch.countDown()
                }
            }.onFailure {
                result = "error: ${it.message}"
                latch.countDown()
            }
        }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) true to (result ?: "null")
        else false to "eval timeout (${timeoutMs}ms)"
    }

    fun action(name: String): Pair<Boolean, String> {
        val ok = onMain {
            when (name.lowercase()) {
                "back" -> { if (web.canGoBack()) { web.goBack(); true } else false }
                "forward" -> { if (web.canGoForward()) { web.goForward(); true } else false }
                "reload" -> { web.reload(); true }
                "stop" -> { web.stopLoading(); true }
                "close" -> { finish(); true }
                "clear" -> { web.clearHistory(); web.clearCache(true); true }
                else -> false
            }
        } ?: false
        return ok to if (ok) "ok" else "unsupported or unavailable: $name"
    }

    inner class Bridge {
        @JavascriptInterface fun info(): String = status().toString()
        @JavascriptInterface fun open(url: String) { main.post { runCatching { web.loadUrl(url) } } }
        @JavascriptInterface fun close() { main.post { finish() } }
        @JavascriptInterface fun currentUrl(): String = web.url ?: ""
        @JavascriptInterface fun log(msg: String) { Log.i("WebActivityJs", msg.take(1000)) }
    }

    companion object {
        private const val TAG = "WebActivity"
    }
}
