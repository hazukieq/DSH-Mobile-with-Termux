package app.dsh.mobile.webview

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.R
import app.dsh.mobile.engine.EngineSupervisor

/**
 * 顶部原生 chrome 控制器（Phase 1.6 从 MainActivity 提取）。
 *
 * 负责「引擎已就绪 · url / 重启 / 隐藏 / 齿轮」这一条状态栏、启动进度条、
 * 预览模式返回按钮、工具栏收起/唤回把手，以及引擎生命周期 → 状态栏文案的映射。
 *
 * 只做**视图层**：引擎重启、URL 加载、遮罩收放都通过 [Host] 回调交给宿主，
 * 保持「引擎由前台服务持有、Activity 只做外壳」的原架构。
 */
class NativeChrome(
    private val activity: Activity,
    private val supervisor: EngineSupervisor,
    private val splash: SplashController,
    private val host: Host,
) {

    /** 需要 Activity 侧配合的动作。 */
    interface Host {
        /** 重置「已加载」标记（引擎重启后要重载页面）。 */
        fun resetUrlLoaded()

        /** 导航到指定 URL（统一入口，保持缩放/viewport 行为不变）。 */
        fun loadLocalUrl(url: String)

        /** 引擎 Healthy/SafeMode 时按需加载引擎页（token 变化才重载）。 */
        fun loadEngineUrlIfNeeded()

        /** 引擎进入 Stopped：作废已加载 URL 与历史栈。 */
        fun onEngineStopped()
    }

    private lateinit var statusBar: TextView

    /** 启动阶段秒表：引擎启动本身耗时（真机可达十几秒），显示已用秒数避免被误判为卡死 */
    private var startTicker: Runnable? = null

    /** 绑定布局控件与按钮回调；在 setContentView 之后、首次 render 之前调用一次。 */
    fun bind() {
        statusBar = activity.findViewById(R.id.statusBar)
        // 热重启：用户显式动作，完整 stop→start 链路；resetUrlLoaded 让 Healthy 后重载 3080。
        // restart() 自身立即返回（内部串行 + 先置「启动中」），无需再套线程；重复点击幂等。
        // Toast 给即时反馈：引擎优雅退出最长等 10s，期间状态栏可能来不及刷新（实测观感
        // 是「点了没反应」于是连点三下 → 触发并发 stop/start 踩踏）。
        activity.findViewById<TextView>(R.id.btnRestart).setOnClickListener {
            host.resetUrlLoaded()
            supervisor.restart()
            Toast.makeText(activity, activity.getString(R.string.engine_restarting), Toast.LENGTH_SHORT).show()
        }
        // 隐藏工具栏：一键收起让网页全屏（点顶部小把手唤回）
        activity.findViewById<TextView>(R.id.btnHide).setOnClickListener { toggleToolbar() }
        // 齿轮按钮：跳转独立设置页
        activity.findViewById<View>(R.id.btnMore).setOnClickListener {
            activity.startActivity(Intent(activity, app.dsh.mobile.SettingsActivity::class.java))
        }
        // 预览模式返回：一键从 AI 起的服务页回引擎主界面
        activity.findViewById<TextView>(R.id.btnBack).setOnClickListener {
            host.loadLocalUrl(supervisor.webUrl())
        }
        // 工具栏收起/唤回：点横栏文字空白区收起（网页全屏）
        statusBar.setOnClickListener { toggleToolbar() }
        // 把手：可拖到屏幕边缘任意位置（避让遮挡）；移动距离小于阈值视为点击唤回工具栏
        setupHandleBar()
    }

    /**
     * 预览 chrome：WebView 导航到非引擎端口的回环页面（用户点击 AI 在对话里给的
     * http://127.0.0.1:PORT 链接）时，状态栏切预览模式并亮出返回按钮；
     * 回到引擎主界面自动恢复。AI 无需任何特殊协议，输出普通链接即可。
     */
    fun onVisitedHistory(url: String?) {
        val uri = url?.let { Uri.parse(it) } ?: return
        val loopback = uri.host == "127.0.0.1" || uri.host == "localhost"
        val enginePort = supervisor.healthyPort
        val preview = loopback && uri.port != enginePort
        activity.findViewById<View>(R.id.btnBack).visibility = if (preview) View.VISIBLE else View.GONE
        if (preview) statusBar.text = activity.getString(R.string.status_preview, uri.port)
    }

    /** 解压进度：null=不在解压（不确定转圈），有值=真实百分比确定性进度 */
    fun renderProgress(frac: Float?) {
        val bar = activity.findViewById<ProgressBar>(R.id.installProgress)
        if (frac == null) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.progress = (frac * 10000).toInt()
        }
        // 启动遮罩里的进度条同步真实解压百分比（就绪后转为不确定动画）
        runCatching { splash.updateProgress(frac) }
    }

    /** 引擎生命周期 → 状态栏/进度条/遮罩文案（与旧 MainActivity.render 逐行等价）。 */
    fun render(state: EngineSupervisor.State) {
        // 显式退出（Stopped）后按钮文案变「启动」，提示用户点这里重新拉起引擎
        activity.findViewById<TextView>(R.id.btnRestart).text =
            if (state is EngineSupervisor.State.Stopped) activity.getString(R.string.btn_start_engine)
            else activity.getString(R.string.btn_restart)
        val bar = activity.findViewById<ProgressBar>(R.id.installProgress)
        bar.visibility =
            if (state is EngineSupervisor.State.Installing || state is EngineSupervisor.State.Starting)
                View.VISIBLE else View.GONE
        if (state !is EngineSupervisor.State.Starting) stopStartTicker()
        // 启动遮罩：阶段文案 + 失败/停止时兜底（不能让用户卡在遮罩里看不到错误）
        when (state) {
            is EngineSupervisor.State.Installing -> splash.setStatus(R.string.splash_preparing)
            is EngineSupervisor.State.Starting -> splash.setStatus(R.string.splash_starting)
            is EngineSupervisor.State.Healthy, is EngineSupervisor.State.SafeMode ->
                splash.setStatus(R.string.splash_loading)
            is EngineSupervisor.State.Failed, is EngineSupervisor.State.Backoff,
            is EngineSupervisor.State.Stopped -> splash.dismiss()
            else -> Unit
        }
        statusBar.text = when (state) {
            is EngineSupervisor.State.Idle -> activity.getString(R.string.status_idle)
            is EngineSupervisor.State.Installing -> activity.getString(R.string.status_installing)
            is EngineSupervisor.State.Starting -> activity.getString(R.string.status_starting).also { startStartTicker() }
            is EngineSupervisor.State.Healthy -> {
                host.loadEngineUrlIfNeeded()
                activity.getString(R.string.status_healthy)
            }
            is EngineSupervisor.State.SafeMode -> {
                host.loadEngineUrlIfNeeded()
                activity.getString(R.string.status_safe_mode)
            }
            is EngineSupervisor.State.Backoff ->
                activity.getString(R.string.status_backoff, state.delayMs / 1000, state.attempt)
            is EngineSupervisor.State.Failed -> activity.getString(R.string.status_failed, state.reason)
            is EngineSupervisor.State.Stopped -> {
                host.onEngineStopped()
                activity.getString(R.string.status_idle)
            }
        }
    }

    /** 工具栏收起/唤回：收起时网页全屏，仅留顶部小把手提示可恢复 */
    fun toggleToolbar() {
        val row = activity.findViewById<View>(R.id.statusBarRow)
        val handle = activity.findViewById<View>(R.id.handleBar)
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
        val handle = activity.findViewById<View>(R.id.handleBar)
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
                statusBar.text = activity.getString(R.string.status_starting) + " " + sec + "s"
                statusBar.postDelayed(this, 1000)
            }
        }
        startTicker = r
        statusBar.postDelayed(r, 1000)
    }
}
