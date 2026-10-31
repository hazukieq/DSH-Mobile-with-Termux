package app.dsh.mobile.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebView 装配器（Phase 1.3 从 MainActivity 提取）。
 *
 * 只负责「把 WebView 配置成引擎宿主需要的形态」——settings、JS 桥、两个 Client。
 * 所有**需要 Activity 状态**的动作（收遮罩、viewport 改写、渲染进程自愈、文件选择器）
 * 都通过 [Host] 回调上抛，避免这个类反向依赖 Activity 的几十个字段。
 *
 * 为什么值得单独成类：MainActivity 曾是 1840 行，其中约 250 行只是 WebView 装配，
 * 与「Activity 生命周期/状态栏/分享/引擎联动」等真正的职责混在一起，
 * 任何一处 WebView 行为改动都要在超长文件里翻找。
 */
class WebViewManager(
    private val webView: WebView,
    private val host: Host,
) {

    /** Activity 侧必须提供的能力（全部在主线程调用）。 */
    interface Host {
        fun log(message: String)
        fun applyZoomControls(enable: Boolean)
        fun isDesktopMode(): Boolean
        fun onPageFinished(view: WebView, url: String?)
        fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean
        fun onShowFileChooser(
            callback: ValueCallback<Array<Uri>>?,
            params: WebChromeClient.FileChooserParams?,
        ): Boolean
        fun onVisitedHistory(url: String?)
    }

    /**
     * 装配 WebView：settings + JS 桥 + WebChromeClient + WebViewClient。
     *
     * ⚠️ 必须在 `webView` 字段赋值之后调用（MainActivity 里就是 findViewById 紧跟着）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false       // 关闭文件域，收窄 WebView 攻击面
            allowContentAccess = false
            // 登录/OAuth 常见 `window.open(url)` 或 `target=_blank`：WebView 默认
            // 不允许多窗口，弹窗请求被静默丢弃 → 页面只剩空白（issue：登录 try to
            // openPopup 失败）。这里开多窗口，并在 onCreateWindow 里把弹窗首跳接到
            // 外部浏览器（见下方注释）。
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            // 竖屏页面缩小靠 viewport meta 改写（同桌面模式机制），useWideViewPort 必须开。
            useWideViewPort = true
            loadWithOverviewMode = true
            host.applyZoomControls(host.isDesktopMode())
        }
        // v1.3.68：WebUI 的 mobile-progress 插件通过这个桥把当前思考/步骤报给原生，
        // 不需要无障碍就能在后台/通知栏展示进度。
        runCatching {
            webView.addJavascriptInterface(ProgressJsBridge(webView.context), "DshNative")
        }
        runCatching {
            app.dsh.mobile.DshLogBridge.appCtx = webView.context.applicationContext
            webView.addJavascriptInterface(app.dsh.mobile.DshLogBridge(), "DshLog")
        }
        webView.webChromeClient = chromeClient()
        webView.webViewClient = viewClient()
    }

    // ── WebChromeClient：console / 弹窗 / 文件选择器 ────────────────────────────
    private fun chromeClient(): WebChromeClient = object : WebChromeClient() {

        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
            msg ?: return false
            val text = msg.message() ?: return false
            // 只记有价值的信息：错误/警告，或我们自己的自检标记
            val important = msg.messageLevel() >= ConsoleMessage.MessageLevel.WARNING ||
                text.contains("[dsh-android]")
            if (important) {
                host.log("console/${msg.messageLevel()} ${msg.sourceId()}:${msg.lineNumber()} $text")
            }
            return false
        }

        /**
         * 弹窗（window.open / target=_blank）处理 —— v2.5.57。
         *
         * ## 为什么必须实现
         * WebView 默认不支持多窗口：网页调 `window.open()` 时，若宿主没实现本回调并
         * 通过 `WebViewTransport` 交出一个子 WebView，**弹窗请求会被静默丢弃**。
         * 表现就是第三方登录 / OAuth（"try to openPopup"）点了没反应、页面变空白。
         *
         * ## 做法
         * 不去真的开一个应用内子窗口：登录页多半是外部站点，在应用内渲染既拿不稳
         * Cookie/SSO，也容易被 X-Frame/UA 判断卡死。这里用一个一次性「探测 WebView」
         * 截获弹窗的**首个 URL**，然后：
         *  · http/https 且非回环 → `ACTION_VIEW` 交给系统浏览器（登录在那里完成）；
         *  · 回环（127.0.0.1/localhost）或 about:blank → 直接在主 WebView 打开，
         *    因为那本来就是 App 自己的页面（例如 WebUI 的内部弹层）。
         */
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?,
        ): Boolean {
            val msg = resultMsg ?: return false
            val transport = msg.obj as? WebView.WebViewTransport ?: return false
            val context = webView.context
            val probe = WebView(context)
            probe.settings.javaScriptEnabled = false
            probe.settings.blockNetworkLoads = false
            val handedOff = AtomicBoolean(false)

            fun handoff(rawUrl: String?) {
                val url = rawUrl?.trim().orEmpty()
                if (url.isEmpty() || url == "about:blank") return
                if (!handedOff.compareAndSet(false, true)) return
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                host.log("popup handoff: $url")
                runCatching { probe.stopLoading() }
                probe.destroy()
                if (uri == null) return
                val loopback = uri.host == "127.0.0.1" || uri.host == "localhost"
                if (loopback) {
                    // 自己人的弹层：主 WebView 直接打开（等价于单窗口浏览器行为）
                    webView.post { webView.loadUrl(url) }
                    return
                }
                val launched = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (!launched) {
                    host.log("popup handoff: no browser for ${uri.scheme}; loading in-app")
                    webView.post { webView.loadUrl(url) }
                }
            }

            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView?, request: WebResourceRequest?,
                ): Boolean {
                    handoff(request?.url?.toString()); return true
                }

                override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    // 有些站点的弹窗是直接 document.write 一个带脚本的页，首帧就是最终 URL
                    if (!url.isNullOrEmpty() && !url.startsWith("about:")) handoff(url)
                }
            }
            transport.webView = probe
            msg.sendToTarget()
            // 兜底：弹窗既没跳转也没加载（例如被站点自己取消）→ 释放资源
            webView.postDelayed({
                if (!handedOff.get() && probe.url == null) runCatching { probe.destroy() }
            }, 3000)
            return true
        }

        /**
         * 网页 `<input type="file">` / WebUI 附件按钮 → 系统文件选择器。
         *
         * ⚠️ 2026-10-05（Issue #5）：此前**未实现**此回调，WebView 收到文件选择请求时
         * 无人应答，表现为「上传什么都失败、也弹不出选择窗口」（WebUI 附件功能整体不可用）。
         * Android WebView 不会自行弹选择器，必须由宿主实现本方法并通过
         * filePathCallback.onReceiveValue() 回传结果 —— 不回传则页面永远等待。
         *
         * 具体启动 Intent / 请求码属于 Activity 的职责，这里只上抛。
         */
        override fun onShowFileChooser(
            view: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            params: FileChooserParams?,
        ): Boolean = host.onShowFileChooser(filePathCallback, params)
    }

    // ── WebViewClient：错误上报 / 导航策略 / 渲染进程自愈 / 首屏后处理 ───────────
    private fun viewClient(): WebViewClient = object : WebViewClient() {

        override fun onReceivedError(
            view: WebView?, request: WebResourceRequest?, error: WebResourceError?,
        ) {
            // 主文档加载失败才是致命的；子资源失败常见（favicon 等），降级记录
            val url = request?.url?.toString() ?: "?"
            val desc = error?.description?.toString() ?: "?"
            val fatal = request?.isForMainFrame == true
            host.log("${if (fatal) "LOAD-FAIL" else "subresource"} $url — $desc")
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            // 子框架导航一律交给 WebView 自己处理。
            // ⚠️ 2026-10-19：侧边栏「HTML 预览」空白就是这里造成的 —— 预览把文档
            // 放进 src=blob:... 的 iframe，WebView 为该子框架导航回调本方法，
            // 旧实现只认 127.0.0.1（blob:/data: 的 host 为空）→ 当成外部链接
            // `startActivity(ACTION_VIEW)` 并 return true（取消加载）→ iframe 永远空白。
            // 主框架之外没有「跳去外部浏览器」的语义，直接放行。
            if (!request.isForMainFrame) return false
            // 内联/本地伪协议不可能是外部页面
            if (uri.scheme == "blob" || uri.scheme == "data" || uri.scheme == "about" || uri.scheme == "javascript") return false
            // 仅允许回环导航；外部链接交给系统浏览器
            if (uri.host == "127.0.0.1" || uri.host == "localhost") return false
            runCatching {
                webView.context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return true
        }

        /**
         * 渲染进程崩溃自愈（v1.2.100，酷安 issue #8）。
         *
         * 返回 true 表示「我自己处理」，阻止系统杀 App；具体恢复动作由 Activity 做
         * （重建 WebView 并重载，引擎不重启、会话与进行中的任务都在）。
         */
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean =
            host.onRenderProcessGone(view, detail)

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (view == null) return
            host.onPageFinished(view, url)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            host.onVisitedHistory(url)
        }
    }
}
