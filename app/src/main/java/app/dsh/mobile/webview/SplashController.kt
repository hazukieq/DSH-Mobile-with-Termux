package app.dsh.mobile.webview

import android.app.Activity
import android.view.View
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.TextView
import app.dsh.mobile.R

/**
 * 启动遮罩控制器（Phase 1.1 从 MainActivity 提取）。
 *
 * 逻辑与 v1.3.1 完全一致，只改变物理位置：遮罩不是纯装饰——它同时承载
 * **真实解压进度**与引擎阶段文案，让冷启动那段时间「有东西看、也知道在干什么」。
 *
 * 调用方约定：所有方法都在主线程调用（MainActivity 目前就是这么用的）。
 */
class SplashController(
    private val activity: Activity,
    /** WebView 懒引用：遮罩淡出时要把底色刷成主题色，避免闪白底。 */
    private val webViewProvider: () -> WebView?,
) {

    /** 全屏启动遮罩；null = 已淡出/未初始化 */
    private var splash: View? = null
    private var splashLogo: View? = null
    private var splashStatus: TextView? = null
    private var splashProgress: ProgressBar? = null

    /** 遮罩出现时间：用于保证最短展示时长（避免一闪而过） */
    private var splashShownAt = 0L

    /** 是否已经淡出（幂等） */
    private var splashDismissed = false
    private val splashMinMs = 700L

    /** 兜底看门狗：任何加载路径异常都不能让遮罩永久吞掉触摸（issue #17） */
    private var splashWatchdog: Runnable? = null

    /**
     * 初始化启动遮罩：logo 淡入 + 缩放入场，随后进入缓慢呼吸。
     *
     * 设计取舍：动画只用 ViewPropertyAnimator（零依赖、不引第三方库）。
     */
    fun setup() {
        val v = activity.findViewById<View>(R.id.splash) ?: return
        splash = v
        splashLogo = activity.findViewById(R.id.splashLogo)
        splashStatus = activity.findViewById(R.id.splashStatus)
        splashProgress = activity.findViewById(R.id.splashProgress)
        splashShownAt = System.currentTimeMillis()
        splashDismissed = false
        // 兜底：无论引擎/WebView 走哪条路径，45s 后强制收遮罩。宁可露出工具栏，
        // 也不能让一张不透明的全屏 View 永久拦截触摸（issue #17 的教训）。
        splashWatchdog?.let { v.removeCallbacks(it) }
        val watchdog = Runnable { dismiss() }
        splashWatchdog = watchdog
        v.postDelayed(watchdog, 45_000L)
        // WebView 底色与主题一致：遮罩淡出时不会闪出白底
        runCatching { webViewProvider()?.setBackgroundColor(0xFF101418.toInt()) }
        val logo = splashLogo ?: return
        logo.alpha = 0f
        logo.scaleX = 0.82f
        logo.scaleY = 0.82f
        logo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(420L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                // 引擎还没好时，用极缓的呼吸动画表示"在动"
                logo.animate()
                    .scaleX(1.05f).scaleY(1.05f)
                    .setDuration(1_400L)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction {
                        logo.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(1_400L)
                            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                            .withEndAction { if (splash != null) setupPulseAgain() }
                            .start()
                    }
                    .start()
            }
            .start()
    }

    /** 呼吸动画循环（单独方法，便于在遮罩淡出时停止）。 */
    private fun setupPulseAgain() {
        val logo = splashLogo ?: return
        if (splash == null || splashDismissed) return
        logo.animate()
            .scaleX(1.05f).scaleY(1.05f)
            .setDuration(1_400L)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                if (splash == null || splashDismissed) return@withEndAction
                logo.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(1_400L)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction { setupPulseAgain() }
                    .start()
            }
            .start()
    }

    /** 更新遮罩上的阶段文案。 */
    fun setStatus(resId: Int) {
        splashStatus?.setText(resId)
    }

    /** 真实进度（0f..1f）；null = 不确定进度（转圈）。 */
    fun updateProgress(fraction: Float?) {
        val p = splashProgress ?: return
        if (fraction == null) {
            p.isIndeterminate = true
        } else {
            p.isIndeterminate = false
            p.progress = (fraction.coerceIn(0f, 1f) * 10_000).toInt()
        }
    }

    /** 遮罩是否还在（供调用方决定要不要重复设文案）。 */
    fun isShowing(): Boolean = splash != null

    /**
     * 只取消兜底看门狗（Activity 销毁时调用）。
     *
     * 与 [dismiss] 的区别：销毁时不需要再演淡出动画，也不能让 45 s 的 Runnable
     * 继续持有已销毁的 View —— 直接摘掉即可。
     */
    fun cancelWatchdog() {
        splash?.let { v -> splashWatchdog?.let { v.removeCallbacks(it) } }
        splashWatchdog = null
    }

    /**
     * 淡出启动遮罩（幂等）。
     *
     * 触发点：WebView 首屏 onPageFinished（页面真的可用了），或引擎进入失败/停止态
     * （不能让用户卡在遮罩里看不到工具栏与错误）。保证最短展示 700ms，避免一闪而过。
     */
    fun dismiss() {
        splashWatchdog?.let { wd -> splash?.removeCallbacks(wd) }
        splashWatchdog = null
        val v = splash ?: return
        if (splashDismissed) return
        splashDismissed = true
        val wait = (splashMinMs - (System.currentTimeMillis() - splashShownAt)).coerceAtLeast(0L)
        v.postDelayed({
            // ⚠️ v1.3.0 补丁（用户反馈「直接硬切有点生硬」）：onPageFinished 时页面往往
            // 还没画出第一帧，立刻淡出会先露出一块空 WebView = 看起来像硬切。
            // 这里再等 ~180ms 让首帧落地，然后做「淡出 + 轻微放大」的过渡，
            // 视觉上是从启动图"化开"进入页面，而不是被替换。
            v.postDelayed({
                splashLogo?.animate()?.cancel()
                v.animate()
                    .alpha(0f)
                    .scaleX(1.04f)
                    .scaleY(1.04f)
                    .setDuration(460L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction {
                        v.visibility = View.GONE
                        v.scaleX = 1f
                        v.scaleY = 1f
                        splash = null
                        splashLogo = null
                        splashStatus = null
                        splashProgress = null
                    }
                    .start()
            }, 180L)
        }, wait)
    }
}
