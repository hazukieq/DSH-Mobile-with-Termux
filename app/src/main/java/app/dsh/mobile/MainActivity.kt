package app.dsh.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.service.EngineService
import app.dsh.mobile.webview.FileChooser
import app.dsh.mobile.webview.NativeChrome
import app.dsh.mobile.webview.ShareDelivery
import app.dsh.mobile.webview.ShareHandler
import app.dsh.mobile.webview.WebViewJs
import app.dsh.mobile.webview.WebViewManager
import app.dsh.mobile.webview.SplashController
import app.dsh.mobile.webview.WebViewGate
import app.dsh.mobile.webview.WebViewHealth
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
    /** 顶部原生 chrome（状态条/按钮/把手/进度/预览返回），Phase 1.6 起独立成类。 */
    private val chrome: NativeChrome by lazy {
        NativeChrome(this, (application as DshApp).supervisor, splashController, object : NativeChrome.Host {
            override fun resetUrlLoaded() { urlLoaded = false }
            override fun loadLocalUrl(url: String) = this@MainActivity.loadLocalUrl(url)
            override fun loadEngineUrlIfNeeded() = this@MainActivity.loadEngineUrlIfNeeded()
            override fun onEngineStopped() { urlLoaded = false; loadedWebUrl = null }
        })
    }
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
    private var pageScale = DshPrefs.PAGE_SCALE_DEFAULT

    /** 分享正文投递（Phase 1.7 起独立成类）：写入/读回/补写三步走。 */
    private val shareDelivery: ShareDelivery by lazy {
        ShareDelivery(this) { runCatching { webView }.getOrNull() }
    }
    /** WebView 文件选择器（Phase 1.7 起独立成类）。 */
    private val fileChooser: FileChooser by lazy {
        FileChooser(this) { logWebView(it) }
    }
    /** WebView 装配器（Phase 1.3 起独立成类）。 */
    private lateinit var webViewManager: WebViewManager
    /** 分享入口（收 Intent → 复制文件进 inbox → 交给 [ShareDelivery] 预填输入框）。 */
    private lateinit var shareHandler: ShareHandler

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

    // ---------- 启动动画（v1.3.1；Phase 1.1 起由 SplashController 负责） ----------
    private lateinit var splashController: SplashController

    /** Web 内核版本门禁（Phase 1.4 起独立成类）。 */
    private lateinit var webViewGate: WebViewGate

    /** WebView 卡死自愈 + 诊断日志（Phase 1.5 起独立成类）。 */
    private val webViewHealth: WebViewHealth by lazy {
        WebViewHealth(this, object : WebViewHealth.Host {
            override fun currentWebView(): WebView? = runCatching { webView }.getOrNull()
            override fun currentUrl(): String? = runCatching { webView.url }.getOrNull()
            override fun installFreshWebView(fresh: WebView) {
                webView = fresh
                setupWebView()
            }
            override fun engineUrlOrNull(): String? =
                runCatching { (application as DshApp).supervisor.webUrl() }.getOrNull()
            override fun loadUrl(url: String) { webView.loadUrl(url) }
            override fun markUrlNotLoaded() { urlLoaded = false }
            override fun recreateActivity() { recreate() }
        })
    }

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
        webViewGate = WebViewGate(
            this,
            webViewProvider = { runCatching { webView }.getOrNull() },
            onGateOverridden = { onResume() },
            log = { logWebView(it) },
        )
        shareHandler = ShareHandler(this, uiScope) { body, files -> shareDelivery.deliver(body, files) }
        splashController.setup()
        chrome.bind()
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

        val app = application as DshApp
        // v1.2.187/188：用户从通知栏「退出」→ 结束界面并销毁 WebView，释放 ~380MB。
        // 用回调而不是"标记位"：Activity 不在时回调为 null，不会留下残留状态
        // （标记方案曾导致"退出后重开 App 秒退"，审查 P0）。
        app.supervisor.onExplicitExit = exitCallback
        uiScope.launch {
            app.supervisor.state.collectLatest { chrome.render(it) }
        }
        uiScope.launch {
            app.supervisor.installProgress.collectLatest { chrome.renderProgress(it) }
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
        // 由用户决定要不要继续。见 webview/WebViewGate.kt。
        if (AppGate.webViewState != AppGate.State.OK &&
            AppGate.webViewState != AppGate.State.OVERRIDDEN && !webViewGate.check()
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
        webViewHealth.start()
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
        webViewHealth.stop()
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

    private fun readUiPrefs() {
        pageScale = DshPrefs.pageScale(this)
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
            ): Boolean = fileChooser.onShowFileChooser(callback, params)
            override fun onVisitedHistory(url: String?) = chrome.onVisitedHistory(url)
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
        view.evaluateJavascript(WebViewJs.selfCheckJs(), null)
        // v1.3.1 补丁（issue #17「选择横屏进入卡死」）：收遮罩必须放在
        // 模式分支**之前**。此前 desktopMode 分支先 return，遮罩永不淡出，
        // 全屏可点击的遮罩把后续所有触摸都吃掉 = 看起来卡死。
        splashController.dismiss()
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
        webViewHealth.recover()
        return true
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
     * 文件选择器结果回传（实现见 [FileChooser]，必须调用 onReceiveValue 否则页面永久等待）。
     */
    @Deprecated("startActivityForResult is the established contract for the WebView file chooser")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (fileChooser.onActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
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
        webViewHealth.stop()
        if (::webViewGate.isInitialized) webViewGate.dismiss()
        runCatching { splashController.cancelWatchdog() }
        // 释放未完成的文件选择请求，否则 WebView 侧回调悬空
        if (::webView.isInitialized) fileChooser.release()
        // v2.3.1（用户反馈「App 退出后悬浮窗还在」）：Activity 真正结束（不是旋转/recreate）
        // 时收起悬浮窗；引擎/服务仍活着时下次打开会重新显示。
        if (isFinishing && !isChangingConfigurations) {
            runCatching { StatusOverlay.hideAuto(this) }
        }
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    /** WebView 诊断日志（写 logcat + engine.log，用户可导出）；实现见 [WebViewHealth.log]。 */
    private fun logWebView(message: String) {
        webViewHealth.log(message)
    }

    companion object {
        // 分享落盘上限（安全评估 H1）：导出 SEND 是外部可触发的入口，必须有硬上限。
        /** 页面缩放持久化：SharedPreferences 名 + key（设置页与引导共用）。
         *  屏幕朝向偏好（v1.2.120 起三态 portrait/landscape/auto）集中在
         *  OrientationPrefs，此处不再持有布尔键。 */

        /** 竖屏页面缩放范围/步长/默认值（等价浏览器 Ctrl- 缩小一点，用户可再调） */
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
