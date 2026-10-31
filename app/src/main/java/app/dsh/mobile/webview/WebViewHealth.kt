package app.dsh.mobile.webview

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * WebView 卡死自愈 + 诊断日志（Phase 1.5 从 MainActivity 提取，v1.2.100 逻辑原样）。
 *
 * ## 问题
 * 用户反馈：「运行到一半会话内容卡住，计时器还在正常计数，模型仍在运行，
 * 需要关闭进程重启应用，偶发」。症状 = **后端正常、前端停止更新**。
 * 两种成因：
 *  ① WebView 渲染进程崩溃/被系统回收 → 页面残留但不再刷新
 *     （未实现 onRenderProcessGone 时 Android 甚至会直接杀掉整个 App）
 *  ② 前端 JS 事件循环被长任务卡住（渲染进程还活着，但不再处理消息）
 *
 * ## 做法：两级看门狗
 *  ① 渲染进程崩溃 → onRenderProcessGone 里 [recover]（重建 WebView + 重载）
 *  ② 页面活着但不响应 → 定时注入轻量探针，连续失败则同样走 [recover]
 *
 * ⚠️ 引擎**不重启**（它由前台服务持有，会话与进行中的任务都在），
 * 只重建渲染层，用户视角是「页面自己恢复了」。
 *
 * 所有需要 Activity 状态的动作用 [Host] 回调上抛（新 WebView 的安装、URL 读取、
 * Activity 重建），避免本类反向依赖 Activity 的字段。
 */
class WebViewHealth(
    private val activity: Activity,
    private val host: Host,
) {

    /** 需要 Activity 侧配合的部分。 */
    interface Host {
        /** 当前 WebView（未初始化时 null）。 */
        fun currentWebView(): WebView?

        /** 当前页面 URL（尽力而为，可能为 null）。 */
        fun currentUrl(): String?

        /** 安装重建后的 WebView：宿主要换字段引用并重新装配（settings/clients）。 */
        fun installFreshWebView(fresh: WebView)

        /** 引擎当前应加载的 URL（没有则 null，等 Healthy 回调重载）。 */
        fun engineUrlOrNull(): String?

        fun loadUrl(url: String)

        /** 交给宿主的加载流程统一处理（对应 MainActivity.urlLoaded = false）。 */
        fun markUrlNotLoaded()

        /** 自愈彻底失败 → 只能重建 Activity。 */
        fun recreateActivity()
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 连续探针失败次数（成功即清零；达阈值判定卡死） */
    private var probeFails = 0

    /** 页面探针：更新一个时间戳；若 eval 回调迟迟不回来，说明主线程/JS 卡住 */
    private val HANG_PROBE_JS =
        "(function(){window.__dshProbe=Date.now();return String(window.__dshProbe);})()"

    fun start() {
        stop()
        handler.postDelayed(hangTick, HANG_CHECK_INTERVAL_MS)
    }

    /** 看门狗的一次心跳：探针 → 判定 → 排下一次 */
    private val hangTick = object : Runnable {
        override fun run() {
            if (host.currentWebView() == null) return
            probePage { alive ->
                if (alive) {
                    probeFails = 0
                } else {
                    probeFails++
                    log("hang probe failed ($probeFails/$HANG_FAIL_LIMIT)")
                    if (probeFails >= HANG_FAIL_LIMIT) {
                        log("page unresponsive — recovering webview (engine untouched)")
                        recover()
                        probeFails = 0
                        return@probePage   // recover 内部会重启看门狗
                    }
                }
            }
            handler.postDelayed(this, HANG_CHECK_INTERVAL_MS)
        }
    }

    fun stop() {
        handler.removeCallbacks(hangTick)
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
        handler.postDelayed(timeout, HANG_PROBE_TIMEOUT_MS)
        runCatching {
            host.currentWebView()?.evaluateJavascript(HANG_PROBE_JS) { raw ->
                if (!done) {
                    done = true
                    handler.removeCallbacks(timeout)
                    cb(raw != null && raw.contains("1"))   // 时间戳必然含数字
                }
            }
        }.onFailure {
            if (!done) {
                done = true
                handler.removeCallbacks(timeout)
                cb(false)
            }
        }
    }

    /**
     * 重建渲染层：销毁旧 WebView、新建并重新加载当前 URL。
     * 引擎与会话完全不受影响（它们不在渲染进程里）。
     */
    fun recover() {
        runCatching {
            stop()
            val parent = host.currentWebView()?.parent as? android.view.ViewGroup
            val oldUrl = host.currentUrl()
            host.currentWebView()?.let { old ->
                parent?.removeView(old)
                runCatching { old.destroy() }
            }
            val fresh = WebView(activity)
            fresh.id = app.dsh.mobile.R.id.webView
            parent?.addView(fresh, android.view.ViewGroup.LayoutParams(-1, -1))
            host.installFreshWebView(fresh)
            // 引擎仍在跑 → 直接回到它给的地址（没有则等 Healthy 回调重载）
            val target = oldUrl ?: host.engineUrlOrNull()
            if (!target.isNullOrBlank()) {
                host.markUrlNotLoaded()   // 交给 Healthy/加载流程统一处理
                host.loadUrl(target)
            }
            start()   // 新页面继续受监控
            log("webview recovered (reloaded ${target ?: "engine url pending"})")
        }.onFailure {
            log("webview recovery failed: ${it.message} — recreating activity")
            runCatching { host.recreateActivity() }
        }
    }

    /** WebView 诊断日志（写 logcat + engine.log，用户可导出） */
    fun log(message: String) {
        android.util.Log.w("DshWebView", message)
        runCatching {
            val f = app.dsh.mobile.engine.EngineConfig.engineRoot(activity).let {
                java.io.File(it, "engine.log")
            }
            f.appendText("[webview] $message\n")
        }
    }

    companion object {
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
    }
}
