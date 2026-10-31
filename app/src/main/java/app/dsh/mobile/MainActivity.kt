package app.dsh.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.service.EngineService
import app.dsh.mobile.webview.ShareHandler
import app.dsh.mobile.webview.WebViewJs
import app.dsh.mobile.webview.WebViewManager
import app.dsh.mobile.webview.SplashController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * WebView 容器。
 *
 * UI 策略：不重写官方 WebUI（上游 developer preview 迭代快，追协议是无底洞），
 * 只做原生外壳 —— 引擎 Healthy 后加载 127.0.0.1 回环页面，状态条显示引擎生命周期。
 *
 * 设置入口：右上角 ⋯ 跳转独立设置页（SettingsActivity，MIUI 风格分组卡片），
 * 不再使用悬浮弹窗菜单。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var statusBar: TextView
    private var urlLoaded = false
    /** 引擎重启后要清一次 WebView 返回栈（见 loadEngineUrlIfNeeded） */
    private var clearHistoryOnLoad = false
    /** 最近一次加载的引擎 URL 与时间（用于压掉健康抖动导致的重复重载） */
    private var lastLoadUrl: String? = null
    private var lastLoadAt = 0L
    /** 已经加载过的引擎 URL（含 token）。引擎热更新/重启后 token 会变，据此重载 WebView。 */
    private var loadedWebUrl: String? = null

    /** 桌面模式：桌面 UA + 固定 1280px 视口 + 手势缩放（手机浏览器「电脑模式」等价物） */
    private var desktopMode = false
    private var defaultUa: String = ""

    /** 屏幕朝向偏好（v1.2.120）：portrait 锁竖 / landscape 锁横+桌面布局 / auto 跟随旋转 */
    private var orientationMode: OrientationMode = OrientationMode.PORTRAIT

    /** 页面缩放百分比（竖屏时应用；等价浏览器 Ctrl+/Ctrl-）。横屏桌面模式交给 1280px meta，不叠加 */
    private var pageScale = DEFAULT_PAGE_SCALE

    /** v1.3.44：其它 App 分享进来的待写入内容（页面未就绪时暂存）。 */
    private var pendingSharePrompt: String? = null
    /** 分享接收（Phase 1.2 起独立成类）：后台复制 + 大小上限 + 正文组装。 */
    private lateinit var shareHandler: ShareHandler
    /** WebView 装配器（Phase 1.3 起独立成类）。 */
    private lateinit var webViewManager: WebViewManager
    private var shareDeliverAttempts = 0
    /** 首次投递前先读取现有草稿并合并，避免分享覆盖用户正在写的内容。 */
    private var shareMergeChecked = false

    /** 本页当前生效的语言代码（onResume 比对，变了就重建） */
    private var appliedLocale: String = ""

    private val uiScope = CoroutineScope(Dispatchers.Main)

    /**
     * 显式退出回调（v1.3.0 审查：实例感知注销）。
     *
     * Android 允许 recreate() 时**旧实例的 onDestroy 晚于新实例的 onCreate**；
     * 若 onDestroy 无条件置 null，会把新实例刚注册的回调清掉（退出后界面不关）。
     * 注销时比对实例引用即可避免。
     */
    private val exitCallback: () -> Unit = {
        Log.i("MainActivity", "explicit exit → finishing task to release WebView")
        finishAndRemoveTask()
    }

    /** 待回传的文件选择结果（onShowFileChooser → onActivityResult 之间持有；null 表示无进行中请求） */
    private var pendingFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    // ---------- 启动动画（v1.3.1；Phase 1.1 起由 SplashController 负责） ----------
    private lateinit var splashController: SplashController

    @SuppressLint("SetJavaScriptEnabled")
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 首启保护：若用户尚未完成引导（如直接拉起 MainActivity），先跳 Onboarding
        if (!Privilege.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        // v1.3.73：只有「用户主动打开 App」才清除正常退出标记并允许启动引擎；
        // 旋转/系统重建（savedInstanceState != null）不算。
        if (savedInstanceState == null) {
            runCatching {
                app.dsh.mobile.service.EngineService.setUserExited(this, false)
                (application as DshApp).supervisor.clearUserStop("user opened the app")
            }
        }


        // 记录本页生效的语言（onResume 里比对，设置页改过就重建本页）
        appliedLocale = LocaleHelper.get(this)

        splashController = SplashController(this) { runCatching { webView }.getOrNull() }
        shareHandler = ShareHandler(this, uiScope) { body, files -> deliverShareBody(body, files) }
        setupSplash()
        statusBar = findViewById(R.id.statusBar)
        // 先赋值字段再配置：setupWebView 内部读取的是 this.webView，
        // 若写在 apply{} 里会在赋值完成前执行而触发 UninitializedPropertyAccessException。
        webView = findViewById<WebView>(R.id.webView)
        setupWebView()
        // v1.3.44：冷启动时若由其它 App 分享拉起，处理分享内容
        runCatching { handleIncomingShare(intent) }
        // 读回用户保存的页面缩放与屏幕朝向偏好
        readUiPrefs()
        applyOrientation(orientationMode)
        // v1.2.120：冷启动也要把桌面渲染落到偏好 —— 旧实现只在 onResume 的「偏好变化」
        // 分支里 setDesktop，导致 landscape=true 冷启动时 UA/视口/缩放手势全是竖屏那套，
        // 直到用户在设置里动过一次或旋转设备才恢复。
        setDesktop(shouldUseDesktop())

        // 热重启：用户显式动作，完整 stop→start 链路；urlLoaded 复位让 Healthy 后重载 3080。
        // restart() 自身立即返回（内部串行 + 先置「启动中」），无需再套线程；重复点击幂等。
        // Toast 给即时反馈：引擎优雅退出最长等 10s，期间状态栏可能来不及刷新（实测观感
        // 是「点了没反应」于是连点三下 → 触发并发 stop/start 踩踏）。
        findViewById<TextView>(R.id.btnRestart).setOnClickListener {
            urlLoaded = false
            (application as DshApp).supervisor.restart()
            Toast.makeText(this, getString(R.string.engine_restarting), Toast.LENGTH_SHORT).show()
        }
        // 隐藏工具栏：一键收起让网页全屏（点顶部小把手唤回）
        findViewById<TextView>(R.id.btnHide).setOnClickListener { toggleToolbar() }
        // 齿轮按钮：跳转独立设置页
        findViewById<View>(R.id.btnMore).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // 预览模式返回：一键从 AI 起的服务页回引擎主界面
        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            loadLocalUrl((application as DshApp).supervisor.webUrl())
        }
        // 工具栏收起/唤回：点横栏文字空白区收起（网页全屏）
        statusBar.setOnClickListener { toggleToolbar() }
        // 把手：可拖到屏幕边缘任意位置（避让遮挡）；移动距离小于阈值视为点击唤回工具栏
        setupHandleBar()

        val app = application as DshApp
        // v1.2.187/188：用户从通知栏「退出」→ 结束界面并销毁 WebView，释放 ~380MB。
        // 用回调而不是"标记位"：Activity 不在时回调为 null，不会留下残留状态
        // （标记方案曾导致"退出后重开 App 秒退"，审查 P0）。
        app.supervisor.onExplicitExit = exitCallback
        uiScope.launch {
            app.supervisor.state.collectLatest { render(it) }
        }
        uiScope.launch {
            app.supervisor.installProgress.collectLatest { renderProgress(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        app.dsh.mobile.engine.AppShot.register(this)
        app.dsh.mobile.engine.AppState.onResume(this)
        // 🔴 v1.2.91：恢复 WebView 的 JS 定时器。
        // 主人实测「回到后台时通过语音发送消息，消息发出去了但模型不响应，
        // 切回前台才开始响应」—— 根因就是这里缺 webView.onResume()：
        // Android 在 Activity 进入后台时会**冻结 WebView 的 JS 定时器**
        // （前端靠定时器/轮询驱动流式渲染），所以后台期间 AI 的回复渲染不出来。
        if (::webView.isInitialized) {
            runCatching { webView.onResume() }
            runCatching { webView.resumeTimers() }
        }
        // v1.3.68：回到前台收起后台进度提示
        runCatching { ProgressHub.onAppVisibility(this, true) }
        // v1.4.7：悬浮窗模式下打开 App 就确保它已经在（不再等第一次进度事件）
        runCatching { ProgressHub.ensureOverlayVisible(this, placeholder = true) }
        // Web 内核版本门禁（v1.2.100，酷安用户反馈引出的需求）：
        // WebView < Chrome 85 时引擎前端必白屏（??= 等解析期语法 polyfill 补不了），
        // 与其让用户看「进程异常退出/白屏」无限循环，不如先检测、给出明确升级指引，
        // 由用户决定要不要继续。见 checkWebViewGate()。
        if (AppGate.webViewState != AppGate.State.OK &&
            AppGate.webViewState != AppGate.State.OVERRIDDEN && !checkWebViewGate()
        ) {
            return   // 门禁未过：不启动引擎（引擎起了前端也渲染不了，纯粹耗电）
        }
        // v2.3.6（用户实测：退出后再点图标，引擎不启动）：
        // 显式退出/划掉任务后 Activity 可能仍以 savedInstanceState 重建（不新建），
        // onCreate 里「savedInstanceState == null 才清标记」就不会执行 → 引擎永远跳过启动。
        // 用户回到前台本身就是明确的启动意图，这里补一次清理。
        clearUserExitedIfNeeded("resume")
        // 前台进入即拉起前台服务；服务存在则幂等
        EngineService.start(this)
        // 页面卡死看门狗（v1.2.100）：前台时才检测（后台由系统节流，探针会误判）
        startHangWatchdog()
        // 从设置页返回：语言若被改过，原地重建本页（设置页只重建了它自己，
        // 主界面返回时也得跟上，否则会出现「设置页已是新语言、主界面还是旧的」）
        val langNow = LocaleHelper.get(this)
        if (langNow != appliedLocale) {
            appliedLocale = langNow
            recreate()
            return
        }
        // 从设置页返回：重新读取朝向/缩放偏好，若被改则同步并重载
        val oldScale = pageScale
        val oldMode = orientationMode
        readUiPrefs()
        if (oldMode != orientationMode) {
            setDesktop(shouldUseDesktop())
            applyOrientation(orientationMode)
        }
        if (oldScale != pageScale || oldMode != orientationMode) {
            webView.reload()
        }
    }

    /**
     * v1.2.91：**不**暂停 WebView。
     *
     * 默认实现（或主动调 `webView.onPause()`）会冻结 JS 定时器 —— 那正是
     * 「后台时 AI 不响应、切回前台才响应」的根因。
     * 本项目的前端需要**在后台继续驱动**（AI 回复的流式渲染、会话状态更新），
     * 而进程本身已由前台服务 + 无障碍服务保活，所以这里保持 WebView 活跃。
     *
     * ⚠️ 代价：后台时 WebView 仍会消耗少量 CPU（前端本身的轮询）。
     * 这是「后台也要能收到 AI 回复」这个需求的必要成本。
     */
    override fun onPause() {
        // 后台停看门狗：系统会节流后台 WebView，探针超时会造成误判并反复重建页面
        stopHangWatchdog()
        // 不在前台就不再提供「App 内部截图」窗口
        app.dsh.mobile.engine.AppShot.unregister(this)
        app.dsh.mobile.engine.AppState.onPause(this)
        // v1.3.77：离开 App 前让 WebUI 立刻上报一次当前思考/步骤，避免浮窗/通知先出现空白再等下一轮。
        if (::webView.isInitialized) {
            runCatching {
                webView.evaluateJavascript("window.__dshMobileProgressPoke&&window.__dshMobileProgressPoke()", null)
            }
        }
        // v1.3.68：离开 App 后展示当前进度（通知/悬浮窗）
        runCatching { ProgressHub.onAppVisibility(this, false) }
        // 刻意不调用 super.onPause() 里的 WebView 暂停逻辑；
        // 同时显式 resumeTimers，确保切后台瞬间定时器仍在跑。
        if (::webView.isInitialized) {
            runCatching { webView.resumeTimers() }
        }
        super.onPause()
    }

    /** 读取用户持久化的屏幕朝向与缩放偏好（设置页与首次引导共用同一组 prefs） */
    /** v1.3.44：singleTask 下其它 App 再次分享会走 onNewIntent。 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        clearUserExitedIfNeeded("new intent")
        if (intent != null) {
            setIntent(intent)
            runCatching { handleIncomingShare(intent) }
        }
    }

    /** v2.3.6：用户重新回到 App（resume/new intent）时清除「正常退出」标记并允许启动引擎。 */
    private fun clearUserExitedIfNeeded(why: String) {
        runCatching {
            if (EngineService.userExited(this)) {
                android.util.Log.i("MainActivity", "user reopened app ($why): clearing userExited and starting engine")
                EngineService.setUserExited(this, false)
                (application as DshApp).supervisor.clearUserStop("user reopened the app ($why)")
            }
        }
    }

    /**
     * 接收其它 App 的分享（文本/链接/文件）。
     *
     * 文件先复制进 `$DSH_HOME/inbox/`（引擎与终端可直接读的稳定路径），然后把
     * 「文件路径 + 分享文本」写进当前会话的输入框（只是预填，不自动发送）。
     * 没有打开的会话/输入框时给出明确状态提示，而不是静默失败。
     */
    private fun handleIncomingShare(intent: Intent) {
        shareHandler.handle(intent)
    }

    /** 分享正文投递（由 [ShareHandler] 在主线程回调）：只预填，不自动发送。 */
    private fun deliverShareBody(body: String, fileCount: Int = 0) {
        pendingSharePrompt = body
        shareDeliverAttempts = 0
        shareMergeChecked = false
        Log.i("DshShare", "prompt len=${body.length} files=$fileCount webViewReady=${::webView.isInitialized}")
        webView.postDelayed({ deliverPendingShare() }, 400)
    }

    /**
     * 把待写入的分享内容放进 WebUI 输入框。
     *
     * 不能只看 JS 返回值：Lexical 的 paste 管线是**异步**的，且 paste 可能完全无效
     * （是语音输入踩过的坑）。所以这里按语音同样的「写入 → 读回 → 必要时 execCommand 补写」
     * 三步走，但**不发送**，只预填给用户确认。
     */
    private fun deliverPendingShare() {
        val prompt = pendingSharePrompt ?: return
        if (!::webView.isInitialized) return
        if (!shareMergeChecked) {
            shareMergeChecked = true
            readSendText { existing ->
                if (!existing.isNullOrBlank() && existing != "none" && !existing.contains(prompt.take(16))) {
                    pendingSharePrompt = existing.trimEnd() + "\n\n" + prompt
                }
                deliverPendingShare()
            }
            return
        }
        if (shareDeliverAttempts >= 8) {
            pendingSharePrompt = null
            val inbox = java.io.File(app.dsh.mobile.engine.EngineConfig.dshHome(this), "inbox").absolutePath
            Toast.makeText(
                this,
                getString(R.string.share_no_composer, inbox),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        shareDeliverAttempts++
        Log.i("DshShare", "deliver attempt=$shareDeliverAttempts len=${prompt.length}")
        webView.evaluateJavascript(WebViewJs.buildVoiceInputJs(prompt)) { raw ->
            Log.i("DshShare", "write result=${jsString(raw)}")
            when (jsString(raw)) {
                "entering-session", "no-input-found" ->
                    webView.postDelayed({ deliverPendingShare() }, 900)
                else -> webView.postDelayed({ verifyShareWrite(prompt) }, 700)
            }
        }
    }

    private fun verifyShareWrite(prompt: String) {
        val probe = prompt.take(16)
        readSendText { inBox ->
            when {
                inBox.contains(probe) -> {
                    pendingSharePrompt = null
                    Toast.makeText(this, getString(R.string.share_prefilled), Toast.LENGTH_SHORT).show()
                }
                inBox.isEmpty() || inBox == "none" -> {
                    // paste 没生效 → execCommand 补写，再读一次
                    webView.evaluateJavascript(WebViewJs.buildVoiceRepairJs(prompt)) { _ ->
                        webView.postDelayed({
                            readSendText { after ->
                                if (after.contains(probe)) {
                                    pendingSharePrompt = null
                                    Toast.makeText(this, getString(R.string.share_prefilled), Toast.LENGTH_SHORT).show()
                                } else {
                                    deliverPendingShare()
                                }
                            }
                        }, 350)
                    }
                }
                else -> {
                    // 用户已有草稿：追加在末尾，不覆盖
                    webView.evaluateJavascript(WebViewJs.buildVoiceRepairJs("\n\n" + prompt)) { _ ->
                        webView.postDelayed({
                            readSendText { after ->
                                if (after.contains(probe)) {
                                    pendingSharePrompt = null
                                    Toast.makeText(this, getString(R.string.share_appended), Toast.LENGTH_SHORT).show()
                                } else {
                                    deliverPendingShare()
                                }
                            }
                        }, 350)
                    }
                }
            }
        }
    }

    private fun readUiPrefs() {
        val p = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        pageScale = p.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)
        orientationMode = OrientationPrefs.get(this)
    }

    /**
     * v1.2.120：当前是否应该走桌面渲染。
     * landscape = 恒桌面；portrait = 恒移动；auto = 看**实际**朝向
     * （物理横屏 → 桌面布局，竖屏 → 移动布局），旋转时由
     * onConfigurationChanged 重新求值。
     */
    private fun shouldUseDesktop(): Boolean = when (orientationMode) {
        OrientationMode.LANDSCAPE -> true
        OrientationMode.PORTRAIT -> false
        OrientationMode.AUTO ->
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    private fun setupWebView() {
        defaultUa = webView.settings.userAgentString
        webViewManager = WebViewManager(webView, object : WebViewManager.Host {
            override fun log(message: String) = logWebView(message)
            override fun applyZoomControls(enable: Boolean) = this@MainActivity.applyZoomControls(enable)
            override fun isDesktopMode(): Boolean = desktopMode
            override fun onPageFinished(view: WebView, url: String?) = onEnginePageFinished(view, url)
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean =
                onEngineRenderProcessGone(view, detail)
            override fun onShowFileChooser(
                callback: android.webkit.ValueCallback<Array<Uri>>?,
                params: android.webkit.WebChromeClient.FileChooserParams?,
            ): Boolean = onEngineFileChooser(callback, params)
            override fun onVisitedHistory(url: String?) = updatePreviewChrome(url)
        })
        webViewManager.configure()
    }

    /**
     * WebView 首屏完成后的 Activity 侧动作。
     *
     * （Phase 1.3 从旧 `onPageFinished` 原样搬出：清历史、环境自检、收遮罩、
     *  按横竖屏改写 viewport —— 这些都要读 Activity 的字段，所以留在 Activity。）
     */
    private fun onEnginePageFinished(view: WebView, url: String?) {
        // 引擎重启（token 变了）→ 旧页面的返回栈必须作废：否则 BACK 会退回
        // 上一个 token 的引擎页/预览页，那些页面连的是已死的引擎，表现为白屏。
        // 放在最早处：任何分支 return 之前都要清。
        if (clearHistoryOnLoad) {
            clearHistoryOnLoad = false
            runCatching { view.clearHistory() }
            Log.i("MainActivity", "webview history cleared after engine restart; canGoBack=${view.canGoBack()}")
        }
        // 环境自检：把 WebView 版本与关键 API 存在性写进日志，用户报障时一眼定位
        view.evaluateJavascript(WebViewJs.WEBVIEW_SELFCHECK_JS, null)
        // v1.3.1 补丁（issue #17「选择横屏进入卡死」）：收遮罩必须放在
        // 模式分支**之前**。此前 desktopMode 分支先 return，遮罩永不淡出，
        // 全屏可点击的遮罩把后续所有触摸都吃掉 = 看起来卡死。
        dismissSplash()
        // 桌面模式：视口改写为固定 1280px（响应式走桌面分支，侧栏完整展开）。
        if (desktopMode) {
            view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
            return
        }
        // 竖屏：全交给 viewport meta 的 initial-scale 控制显示缩放（浏览器 Ctrl- 做法）。
        view.evaluateJavascript(portraitViewportJs(pageScale / 100f), null)
        // 竖屏「对话流被挤压」修复已改为**引擎侧客户端插件**
        // （assets/android-plugins/narrow-sidebar/，v1.2.175）：用户要求「不要直接改源代码，
        // 要做成插件形式」——外部注入 JS 会和其它 UI 插件抢 DOM，且引擎升级后哈希类名一变
        // 就失效；插件走 dsh-client-modules 的官方发现机制，卸载时自清理，互不干扰。
    }

    /** 渲染进程崩溃：记日志并自愈（不重启引擎），返回 true 阻止系统杀 App。 */
    private fun onEngineRenderProcessGone(
        view: WebView?,
        detail: android.webkit.RenderProcessGoneDetail?,
    ): Boolean {
        logWebView(
            "render process gone: crashed=${detail?.didCrash()} " +
                "priority=${detail?.rendererPriorityAtExit()} — recovering"
        )
        recoverWebView()
        return true
    }

    /** 文件选择器请求（<input type=file>）：交给系统 SAF 选择器并回传结果。 */
    private fun onEngineFileChooser(
        filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
        params: android.webkit.WebChromeClient.FileChooserParams?,
    ): Boolean {
        filePathCallback ?: return false
        // 上一个请求未结束（用户连点/页面切换）→ 先回传空值释放，否则 WebView 卡死
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = filePathCallback
        val intent = runCatching { params?.createIntent() }.getOrNull()
            ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        return runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_FILE_CHOOSER)
            true
        }.getOrElse {
            logWebView("file chooser failed to launch: ${it.message}")
            pendingFileCallback = null
            filePathCallback.onReceiveValue(null)
            false
        }
    }

    /**
     * 预览 chrome：WebView 导航到非引擎端口的回环页面（用户点击 AI 在对话里给的
     * http://127.0.0.1:PORT 链接）时，状态栏切预览模式并亮出返回按钮；
     * 回到引擎主界面自动恢复。AI 无需任何特殊协议，输出普通链接即可。
     */
    private fun updatePreviewChrome(url: String?) {
        val uri = url?.let { Uri.parse(it) } ?: return
        val loopback = uri.host == "127.0.0.1" || uri.host == "localhost"
        val enginePort = (application as DshApp).supervisor.healthyPort
        val preview = loopback && uri.port != enginePort
        findViewById<View>(R.id.btnBack).visibility = if (preview) View.VISIBLE else View.GONE
        if (preview) statusBar.text = getString(R.string.status_preview, uri.port)
    }

    /** 统一的回环页加载入口：缩放统一由 onPageFinished 的 viewport meta 接管，这里只导航。 */
    private fun loadLocalUrl(url: String) {
        logWebView("load-url: $url")
        webView.loadUrl(url)
    }

    /** 手势缩放开关：竖屏关闭（锁死固定全屏，禁止双指捏合/拖动移动），桌面模式开启（保留双指缩放）。
     *  displayZoomControls 恒 false，只保留捏合不显示 +/- 浮层按钮。 */
    private fun applyZoomControls(enable: Boolean) {
        webView.settings.apply {
            setSupportZoom(enable)
            builtInZoomControls = enable
            displayZoomControls = false
        }
    }

    /** 只改桌面渲染的 UA + 手势缩放开关，不 reload——reload 统一由旋转/onResume 回调做（避免双重整页重载卡顿）。
     *  viewport 改写统一在 onPageFinished 里做。 */
    private fun setDesktop(enable: Boolean) {
        desktopMode = enable
        webView.settings.userAgentString = if (enable) DESKTOP_UA else defaultUa
        applyZoomControls(enable)
    }

    /** 工具栏收起/唤回：收起时网页全屏，仅留顶部小把手提示可恢复 */
    private fun toggleToolbar() {
        val row = findViewById<View>(R.id.statusBarRow)
        val handle = findViewById<View>(R.id.handleBar)
        if (row.visibility == View.VISIBLE) {
            row.visibility = View.GONE
            handle.visibility = View.VISIBLE
        } else {
            row.visibility = View.VISIBLE
            handle.visibility = View.GONE
        }
    }

    /**
     * 把手拖拽：按住可在父容器内自由移动（clamp 保住 60% 宽度在屏内，防止拖丢），
     * 松手时若位移小于 touch slop（约 12px）则视为点击 → 唤回工具栏。
     * 位置仅本次隐藏期间保持（重显工具栏后再收起回到上次位置，rotation/重建回默认）。
     */
    private fun setupHandleBar() {
        val handle = findViewById<View>(R.id.handleBar)
        val slop = 12f
        var rawX0 = 0f; var rawY0 = 0f
        var initTx = 0f; var initTy = 0f
        handle.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    rawX0 = ev.rawX; rawY0 = ev.rawY
                    initTx = v.translationX; initTy = v.translationY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val parent = v.parent as View
                    v.translationX = (initTx + (ev.rawX - rawX0))
                        .coerceIn(-v.width * 0.6f, parent.width - v.width * 0.4f)
                    v.translationY = (initTy + (ev.rawY - rawY0))
                        .coerceIn(0f, (parent.height - v.height).coerceAtLeast(0).toFloat())
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - rawX0; val dy = ev.rawY - rawY0
                    if (dx * dx + dy * dy < slop * slop) toggleToolbar()
                    true
                }
                else -> false
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后屏宽变化 → 需要重算适配视口（reload 后 onPageFinished 会按当前模式
        // 与 pageScale 重写 viewport meta）。竖屏旋转屏宽同样变，故统一 reload。
        // 同时同步手势缩放开关（竖屏锁死、桌面保留），防止切屏后对手势失效。
        // v1.2.120：auto 模式下桌面渲染跟随**实际**朝向——旋转后先按新朝向求值，
        // 否则横屏后仍是竖屏那套 UA/视口。setDesktop 自身不 reload，reload 紧随其后。
        setDesktop(shouldUseDesktop())
        applyZoomControls(desktopMode)
        webView.reload()
    }

    /**
     * 竖屏视口缩放 JS：把 viewport meta 写成
     * `width=device-width, initial-scale=R, minimum-scale=R, maximum-scale=R, user-scalable=no`。
     * - width=device-width → 布局宽=屏宽，不产生横向滚动；
     * - initial-scale=R   → 浏览器 Ctrl- 式显示缩放（R<1 缩小看更多，R>1 放大）；
     * - min=max=R         → 锁死缩放（既不会被 clamp 回 100%，也不许用户手动捏合）；
     * ratio 由用户 pageScale 推导，范围 0.5–1.5。
     */
    private fun portraitViewportJs(ratio: Float): String {
        val r = ratio.coerceIn(0.5f, 1.5f)
        return "(function(){" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
            "(document.head||document.documentElement).appendChild(m);}" +
            "m.setAttribute('content','width=device-width, initial-scale=$r, minimum-scale=$r, maximum-scale=$r, user-scalable=no');" +
            "})()"
    }

    /** 解压进度：null=不在解压（不确定转圈），有值=真实百分比确定性进度 */
    private fun renderProgress(frac: Float?) {
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        if (frac == null) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.progress = (frac * 10000).toInt()
        }
        // 启动遮罩里的进度条同步真实解压百分比（就绪后转为不确定动画）
        runCatching { splashController.updateProgress(frac) }
    }

    // ==================== 启动遮罩（v1.3.1） ====================

    /**
     * 初始化启动遮罩：logo 淡入 + 缩放入场，随后进入缓慢呼吸。
     *
     * 设计取舍：动画只用 ViewPropertyAnimator（零依赖、不引第三方库）；
     * 遮罩不是纯装饰——它同时承载**真实解压进度**与引擎阶段文案，
     * 让冷启动那段时间「有东西看、也知道在干什么」。
     */
    /** 初始化启动遮罩（委托 [SplashController]）。 */
    private fun setupSplash() {
        splashController.setup()
    }

    /** 更新遮罩上的阶段文案。 */
    private fun setSplashStatus(resId: Int) {
        splashController.setStatus(resId)
    }

    /**
     * 淡出启动遮罩（幂等）。
     *
     * 触发点：WebView 首屏 onPageFinished（页面真的可用了），或引擎进入失败/停止态
     * （不能让用户卡在遮罩里看不到工具栏与错误）。保证最短展示 700ms，避免一闪而过。
     */
    private fun dismissSplash() {
        splashController.dismiss()
    }

    /**
     * 屏幕朝向：把用户偏好真正落到 requestedOrientation（v1.2.120 三态）。
     *
     * ⚠️ 竖屏必须显式用 SCREEN_ORIENTATION_PORTRAIT。SCREEN_ORIENTATION_UNSPECIFIED
     * 的语义是「交给系统/传感器决定」，**不是**「锁定竖屏」—— 旧实现给竖屏填的是
     * UNSPECIFIED，所以设置里选「竖屏」后物理旋转设备照样变横屏，看起来就是开关失灵。
     * auto 用 USER（跟随传感器且尊重系统旋转锁），见 OrientationPrefs.toActivityInfo。
     */
    private fun applyOrientation(mode: OrientationMode) {
        requestedOrientation = OrientationPrefs.toActivityInfo(mode)
    }

    /** 启动阶段秒表：引擎启动本身耗时（真机可达十几秒），显示已用秒数避免被误判为卡死 */
    private var startTicker: Runnable? = null

    private fun stopStartTicker() {
        startTicker?.let { statusBar.removeCallbacks(it) }
        startTicker = null
    }

    private fun startStartTicker() {
        if (startTicker != null) return
        val t0 = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                val sec = (System.currentTimeMillis() - t0) / 1000
                statusBar.text = getString(R.string.status_starting) + " " + sec + "s"
                statusBar.postDelayed(this, 1000)
            }
        }
        startTicker = r
        statusBar.postDelayed(r, 1000)
    }

    private fun render(state: EngineSupervisor.State) {
        // ⚠️ v1.2.187（用户反馈「状态栏退出不彻底，后台仍以 380MB 运行、耗电」）：
        // 用户在通知栏点「退出」后，引擎停了但 Activity 还活着 —— WebView 与它
        // 的渲染进程继续占着 ~380MB。收到**显式退出**信号就结束任务栈：
        // finishAndRemoveTask → onDestroy → webView.destroy()，内存随之释放。
        // 显式退出（Stopped）后按钮文案变「启动」，提示用户点这里重新拉起引擎
        findViewById<TextView>(R.id.btnRestart).text =
            if (state is EngineSupervisor.State.Stopped) getString(R.string.btn_start_engine)
            else getString(R.string.btn_restart)
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        bar.visibility =
            if (state is EngineSupervisor.State.Installing || state is EngineSupervisor.State.Starting)
                View.VISIBLE else View.GONE
        if (state !is EngineSupervisor.State.Starting) stopStartTicker()
        // 启动遮罩：阶段文案 + 失败/停止时兜底（不能让用户卡在遮罩里看不到错误）
        when (state) {
            is EngineSupervisor.State.Installing -> setSplashStatus(R.string.splash_preparing)
            is EngineSupervisor.State.Starting -> setSplashStatus(R.string.splash_starting)
            is EngineSupervisor.State.Healthy, is EngineSupervisor.State.SafeMode ->
                setSplashStatus(R.string.splash_loading)
            is EngineSupervisor.State.Failed, is EngineSupervisor.State.Backoff,
            is EngineSupervisor.State.Stopped -> dismissSplash()
            else -> Unit
        }
        statusBar.text = when (state) {
            is EngineSupervisor.State.Idle -> getString(R.string.status_idle)
            is EngineSupervisor.State.Installing -> getString(R.string.status_installing)
            is EngineSupervisor.State.Starting -> getString(R.string.status_starting).also { startStartTicker() }
            is EngineSupervisor.State.Healthy -> {
                loadEngineUrlIfNeeded()
                getString(R.string.status_healthy)
            }
            is EngineSupervisor.State.SafeMode -> {
                loadEngineUrlIfNeeded()
                getString(R.string.status_safe_mode)
            }
            is EngineSupervisor.State.Backoff ->
                getString(R.string.status_backoff, state.delayMs / 1000, state.attempt)
            is EngineSupervisor.State.Failed -> getString(R.string.status_failed, state.reason)
            is EngineSupervisor.State.Stopped -> {
                urlLoaded = false
                loadedWebUrl = null
                getString(R.string.status_idle)
            }
        }
    }

    /**
     * 引擎页面加载（2026-10-18 热更新修复）。
     *
     * 为什么不能只在第一次 Healthy 时加载：dsh-update 热更新会换掉整套客户端 bundle
     * （插件 URL 里带 rev 哈希）并重启引擎、换 token。旧页面继续用旧客户端连新引擎，
     * 浏览器控制台会报 `sessions service unavailable`、
     * `renderSlot('root') before any 'root' registration (boot order)`，表现为白屏。
     *
     * 判据：`webUrl()` 里的 token 每次引擎启动都不同 → URL 变了就清缓存并重载。
     */
    private fun loadEngineUrlIfNeeded() {
        val url = (application as DshApp).supervisor.webUrl()
        if (loadedWebUrl == url) return
        // 同一 URL 短时间内重复加载：引擎健康状态抖动（Stopped→Healthy）会让这里重入。
        // 每次重载都会把 SPA 打回「新会话草稿」并丢掉侧边栏里的当前会话，用户表现为
        // 「回不到刚才的会话」。3s 内的重复加载直接跳过（首次加载不受影响）。
        val now = System.currentTimeMillis()
        if (url == lastLoadUrl && now - lastLoadAt < 3_000L) {
            Log.i("MainActivity", "skip duplicate engine load (<3s): $url")
            loadedWebUrl = url
            return
        }
        lastLoadUrl = url
        lastLoadAt = now
        val firstLoad = loadedWebUrl == null
        loadedWebUrl = url
        urlLoaded = true
        if (!firstLoad) {
            clearHistoryOnLoad = true
            // 引擎重启后 token 变了：旧的 **cookie** 还留在 WebView 里，新引擎会把它
            // 当成无效会话并直接返回纯文本 "dsh web authentication required"
            // （0.2.1 实测：白屏 + 这段文字）。缓存和 cookie 都要清，再带新 token 打开。
            runCatching { webView.clearCache(true) }
            runCatching {
                val cm = android.webkit.CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
            }
        }
        loadLocalUrl(url)
    }

    override fun onBackPressed() {
        // WebView 有历史则先回退，保持类原生浏览体验
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /**
     * 文件选择器结果回传。
     *
     * ⚠️ 必须调用 onReceiveValue（哪怕是 null）：WebView 在回调前会**挂起页面的文件选择
     * 请求**，不调用则页面永久等待（表现为「点了上传没反应」）。用户取消时传 null 即取消。
     */
    @Deprecated("startActivityForResult is the established contract for the WebView file chooser")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE_CHOOSER) {
            val cb = pendingFileCallback
            pendingFileCallback = null
            if (cb == null) {
                super.onActivityResult(requestCode, resultCode, data)
                return
            }
            val uris: Array<Uri>? = when {
                resultCode != RESULT_OK -> null
                else -> {
                    val fromData = android.webkit.WebChromeClient.FileChooserParams
                        .parseResult(resultCode, data)
                    // 部分 ROM 的选择器把结果放在 clipData（多选/分享式选择器），parseResult 拿不到
                    if (fromData != null && fromData.isNotEmpty()) fromData
                    else data?.clipData?.let { cd -> Array(cd.itemCount) { cd.getItemAt(it).uri } }
                        ?: data?.data?.let { arrayOf(it) }
                }
            }
            runCatching { cb.onReceiveValue(uris) }
                .onFailure { logWebView("file chooser result delivery failed: ${it.message}") }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * Web 内核版本门禁（v1.2.100）。
     *
     * ## 为什么需要
     * 引擎前端用了 `??=` / 类字段等**解析期**语法（Chrome 85+），polyfill 只能补
     * **API 层**（13 个现代 API 注入），补不了语法 —— WebView 过旧时页面必白屏。
     * 之前的处理是「照常启动引擎 + 日志里记录 SyntaxError」：用户看到的是
     * 白屏/无限重启，不知道原因（酷安用户反馈的正是这种体验）。
     *
     * ## 做法
     * 直接查 WebView Provider 的包版本（不走 JS，无需加载页面）：
     *   · ≥ [MIN_WEBVIEW_CHROME]：放行（本会话内不再重复检查）
     *   · < 门槛：弹对话框，给出当前版本、升级路径（应用商店/Play），并提供
     *     「仍然继续」—— 用户明确坚持才启动引擎（尊重知情选择）；对话框
     *     不会因 AlertDialog 取消而反复弹出，选择「继续」后本会话记入记忆。
     *
     * @return true = 放行（版本达标或用户坚持）
     */
    /**
     * 当前显示的版本门禁对话框（v1.2.100）。
     * ⚠️ 必须持有引用并判重：`onResume` 会被反复触发（切回前台、息屏亮屏、点按钮后
     * 重入），旧实现每次都 `show()` 一个新对话框 → **无限叠堆**（且 setCancelable(false)
     * 无法取消）。独立审查指出后补上这道守卫。
     */
    private var gateDialog: android.app.AlertDialog? = null

    private fun checkWebViewGate(): Boolean {
        val ver = currentWebViewVersion()
        if (ver >= AppGate.MIN_WEBVIEW_CHROME) {
            AppGate.markWebViewOk()
            return true
        }
        AppGate.markWebViewBlocked()
        val appCtx = applicationContext
        // 升级指引：优先指向 Play 商店的 WebView 页（绝大多数设备），失败则给系统设置
        val updateIntent = runCatching {
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"))
        }.getOrElse {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:com.google.android.webview"))
        }
        val msg = getString(
            R.string.webview_gate_body,
            if (ver > 0) ver.toString() else "??",
            AppGate.MIN_WEBVIEW_CHROME,
        )
        if (gateDialog?.isShowing == true) {
            logWebView("webview gate: dialog already showing, skip")
            return false
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.webview_gate_title))
            .setMessage(msg)
            .setPositiveButton(R.string.webview_gate_update) { _, _ ->
                runCatching { startActivity(updateIntent) }
                // 升级完回来 onResume 会重新检测（AppGate 仍是 BLOCKED，会再判一次）
            }
            .setNegativeButton(R.string.webview_gate_continue) { _, _ ->
                AppGate.overrideWebViewGate()
                onResume()   // 重新走一遍（这次会启动引擎）
            }
            .setCancelable(false)
            .create()
            .also { gateDialog = it }
            .show()
        logWebView("webview gate: chrome=$ver < ${AppGate.MIN_WEBVIEW_CHROME}, prompting user")
        return false
    }

    /**
     * 当前 WebView 内核的 Chrome 版本号（0 = 读不到）。
     *
     * ## 三级取法（都失败才返回 0）
     *  ① `WebView.getCurrentWebViewPackage()` —— 官方 API，但实测有坑：
     *     **onResume 时序上可能返回 null**（provider 尚未敲定），部分 ROM 还不实现
     *  ② 扫已知 provider 包名取 versionName —— 覆盖 Google WebView / AOSP /
     *     国产 ROM 内置 provider；Android 对 WebView provider 查询有 visibility 豁免
     *  ③ 当前 webView 实例的 UA —— 只在实例已建时可用
     * UA 里的 `Chrome/NN` 永远反映真正干活的内核版本，与 provider 包名无关。
     */
    private fun currentWebViewVersion(): Int {
        // ① 官方 API
        runCatching {
            android.webkit.WebView.getCurrentWebViewPackage()?.versionName?.let { v ->
                Regex("Chrome/(\\d+)").find(v)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
                // provider 版本号首段即 Chrome 大版本（如 133.0.6943.137）
                v.substringBefore('.')?.toIntOrNull()?.let { if (it > 0) return it }
            }
        }
        // ② 扫已知 provider
        runCatching {
            for (pkg in WEBVIEW_PROVIDERS) {
                val info = runCatching { packageManager.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
                val vn = info.versionName ?: continue
                Regex("Chrome/(\\d+)").find(vn)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
                vn.substringBefore('.')?.toIntOrNull()?.let { if (it > 0) return it }
            }
        }
        // ③ 现有实例的 UA
        if (::webView.isInitialized) {
            runCatching {
                Regex("Chrome/(\\d+)").find(webView.settings.userAgentString ?: "")
                    ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

    /**
     * 解析 `evaluateJavascript` 返回的**字符串值**（v1.2.94）。
     *
     * 用 JSON 解析，完整保留反斜杠/引号等原字符（供文本比对使用）。
     */
    private fun jsString(raw: String?): String = runCatching {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "null") ""
        else org.json.JSONTokener(s).nextValue() as? String ?: s
    }.getOrDefault(raw?.trim('"').orEmpty())

    /**
     * 读输入框里的**实际文字**（v1.2.94）。
     *
     * 与前端同步读值钩子的区别：那个只回答"空不空"，这个给出内容 ——
     * 必须靠它区分「我们刚写进去的那段」和「用户自己打的字」，
     * 否则会把用户的输入覆盖掉、或把我们那段提交两遍（主人实测的「两段」）。
     */
    private fun readSendText(cb: (String) -> Unit) {
        webView.evaluateJavascript("window.__dshText ? window.__dshText() : ''") { raw ->
            cb(jsString(raw))
        }
    }

    override fun onDestroy() {
        // 注意：引擎由前台服务持有，Activity 销毁不影响后台任务。
        // 首启跳 Onboarding 时本 Activity 立即销毁，webView 尚未初始化——
        // lateinit 直接访问会崩（Android 11 新用户首启闪退实测）。
        uiScope.cancel()
        // 注销显式退出回调（避免持有已销毁 Activity）。
        // ⚠️ 实例感知：只在**当前注册的就是自己**时才清，否则 recreate 的时序
        //（旧 onDestroy 晚于新 onCreate）会把新实例的回调误清掉。
        runCatching {
            val sup = (application as DshApp).supervisor
            if (sup.onExplicitExit === exitCallback) sup.onExplicitExit = null
        }
        stopHangWatchdog()
        runCatching { splashController.cancelWatchdog() }
        // 释放未完成的文件选择请求，否则 WebView 侧回调悬空
        pendingFileCallback?.let { runCatching { it.onReceiveValue(null) } }
        pendingFileCallback = null
        // v2.3.1（用户反馈「App 退出后悬浮窗还在」）：Activity 真正结束（不是旋转/recreate）
        // 时收起悬浮窗；引擎/服务仍活着时下次打开会重新显示。
        if (isFinishing && !isChangingConfigurations) {
            runCatching { StatusOverlay.hideAuto(this) }
        }
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    // ==================== WebView 卡死自愈（v1.2.100，issue #8） ====================
    //
    // ## 问题
    // 用户反馈：「运行到一半会话内容卡住，计时器还在正常计数，模型仍在运行，
    // 需要关闭进程重启应用，偶发」。症状 = **后端正常、前端停止更新**。
    // 两种成因：
    //  ① WebView 渲染进程崩溃/被系统回收 → 页面残留但不再刷新
    //     （未实现 onRenderProcessGone 时 Android 甚至会直接杀掉整个 App）
    //  ② 前端 JS 事件循环被长任务卡住（渲染进程还活着，但不再处理消息）
    //
    // ## 做法：两级看门狗
    //  ① 渲染进程崩溃 → onRenderProcessGone 里 [recoverWebView]（重建 WebView + 重载）
    //  ② 页面活着但不响应 → 定时注入轻量探针，连续失败则同样走 [recoverWebView]
    //
    // ⚠️ 引擎**不重启**（它由前台服务持有，会话与进行中的任务都在），
    // 只重建渲染层，用户视角是「页面自己恢复了」。

    private val hangWatchdog = android.os.Handler(android.os.Looper.getMainLooper())

    /** 连续探针失败次数（成功即清零；达阈值判定卡死） */
    private var probeFails = 0

    /** 页面探针：更新一个时间戳；若 eval 回调迟迟不回来，说明主线程/JS 卡住 */
    private val HANG_PROBE_JS =
        "(function(){window.__dshProbe=Date.now();return String(window.__dshProbe);})()"

    private fun startHangWatchdog() {
        stopHangWatchdog()
        hangWatchdog.postDelayed(hangTick, HANG_CHECK_INTERVAL_MS)
    }

    /** 看门狗的一次心跳：探针 → 判定 → 排下一次 */
    private val hangTick = object : Runnable {
        override fun run() {
            if (!::webView.isInitialized) return
            probePage { alive ->
                if (alive) {
                    probeFails = 0
                } else {
                    probeFails++
                    logWebView("hang probe failed ($probeFails/$HANG_FAIL_LIMIT)")
                    if (probeFails >= HANG_FAIL_LIMIT) {
                        logWebView("page unresponsive — recovering webview (engine untouched)")
                        recoverWebView()
                        probeFails = 0
                        return@probePage   // recoverWebView 内部会重启看门狗
                    }
                }
            }
            hangWatchdog.postDelayed(this, HANG_CHECK_INTERVAL_MS)
        }
    }

    private fun stopHangWatchdog() {
        hangWatchdog.removeCallbacks(hangTick)
        probeFails = 0
    }

    /**
     * 探针：注入一段极短的 JS 并等待回调。
     * @param cb true = 页面有响应；false = 超时或异常
     */
    private fun probePage(cb: (Boolean) -> Unit) {
        var done = false
        val timeout = Runnable {
            if (!done) {
                done = true
                cb(false)
            }
        }
        hangWatchdog.postDelayed(timeout, HANG_PROBE_TIMEOUT_MS)
        runCatching {
            webView.evaluateJavascript(HANG_PROBE_JS) { raw ->
                if (!done) {
                    done = true
                    hangWatchdog.removeCallbacks(timeout)
                    cb(raw != null && raw.contains("1"))   // 时间戳必然含数字
                }
            }
        }.onFailure {
            if (!done) {
                done = true
                hangWatchdog.removeCallbacks(timeout)
                cb(false)
            }
        }
    }

    /**
     * 重建渲染层：销毁旧 WebView、新建并重新加载当前 URL。
     * 引擎与会话完全不受影响（它们不在渲染进程里）。
     */
    private fun recoverWebView() {
        runCatching {
            stopHangWatchdog()
            val parent = webView.parent as? android.view.ViewGroup
            val oldUrl = runCatching { webView.url }.getOrNull()
            parent?.removeView(webView)
            runCatching { webView.destroy() }
            val fresh = WebView(this)
            fresh.id = R.id.webView
            parent?.addView(fresh, android.view.ViewGroup.LayoutParams(-1, -1))
            webView = fresh
            setupWebView()
            // 引擎仍在跑 → 直接回到它给的地址（没有则等 Healthy 回调重载）
            val target = oldUrl ?: runCatching {
                (application as DshApp).supervisor.webUrl()
            }.getOrNull()
            if (!target.isNullOrBlank()) {
                urlLoaded = false   // 交给 Healthy/加载流程统一处理
                webView.loadUrl(target)
            }
            startHangWatchdog()   // 新页面继续受监控
            logWebView("webview recovered (reloaded ${target ?: "engine url pending"})")
        }.onFailure {
            logWebView("webview recovery failed: ${it.message} — recreating activity")
            runCatching { recreate() }
        }
    }

    /** WebView 诊断日志（写 logcat + engine.log，用户可导出） */
    private fun logWebView(message: String) {
        android.util.Log.w("DshWebView", message)
        runCatching {
            val f = app.dsh.mobile.engine.EngineConfig.engineRoot(this).let {
                java.io.File(it, "engine.log")
            }
            f.appendText("[webview] $message\n")
        }
    }

    companion object {
        // 分享落盘上限（安全评估 H1）：导出 SEND 是外部可触发的入口，必须有硬上限。
        /** WebView 文件选择器的请求码（onShowFileChooser → onActivityResult） */
        private const val REQ_FILE_CHOOSER = 1001

        /**
         * 页面卡死看门狗的时序（v1.2.100，issue #8）。
         * 探针注入极短 JS 并要求回执；连续 [HANG_FAIL_LIMIT] 次无响应即判定卡死。
         * 间隔取 15s：既不会漏掉「跑了很久才发现」的情况，也不会频繁打扰正常页面。
         */
        private const val HANG_CHECK_INTERVAL_MS = 15_000L
        /** 单次探针等待上限（超过即算无响应） */
        private const val HANG_PROBE_TIMEOUT_MS = 3_000L
        /** 连续失败几次判定卡死（3 次 ≈ 45s 无响应，足够排除偶发抖动） */
        private const val HANG_FAIL_LIMIT = 3


        /** 已知 WebView provider 包名（v1.2.100，三级取版本的 ② 号路径） */
        private val WEBVIEW_PROVIDERS = listOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.trichromelibrary",
            "com.miui.webview",
            "com.huawei.webview",
            "com.hihonor.webview",
            "com.oplus.webview",
            "com.heytap.webview",
        )

        /** 页面缩放持久化：SharedPreferences 名 + key（设置页与引导共用）。
         *  屏幕朝向偏好（v1.2.120 起三态 portrait/landscape/auto）集中在
         *  OrientationPrefs，此处不再持有布尔键。 */
        private const val PREFS_UI = "dsh_ui"
        private const val KEY_PAGE_SCALE = "page_scale"

        /** 竖屏页面缩放范围/步长/默认值（等价浏览器 Ctrl- 缩小一点，用户可再调） */
        private const val DEFAULT_PAGE_SCALE = 90
        private const val MIN_PAGE_SCALE = 50
        private const val MAX_PAGE_SCALE = 150
        private const val SCALE_STEP = 5

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 桌面视口改写：width=1280 让响应式 CSS 命中桌面断点；
         * initial-scale = 屏宽/1280 整页缩进屏内；maximum-scale=5 + user-scalable
         * 允许双指缩放细看。执行时机 onPageFinished（此时 clientWidth=设备宽）。
         */
        private const val DESKTOP_VIEWPORT_JS =
            "(function(){" +
                "var W=1280;" +
                "var m=document.querySelector('meta[name=\"viewport\"]');" +
                "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
                "(document.head||document.documentElement).appendChild(m);}" +
                "var cw=document.documentElement.clientWidth||412;" +
                "m.setAttribute('content','width='+W+', initial-scale='+(cw/W).toFixed(4)+', maximum-scale=5, user-scalable=yes');" +
                "})()"
    }

}
