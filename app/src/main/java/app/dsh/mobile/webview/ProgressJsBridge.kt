package app.dsh.mobile.webview

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import app.dsh.mobile.ProgressHub
import app.dsh.mobile.SettingsActivity
import app.dsh.mobile.StatusOverlay

/**
 * JS Bridge：只暴露后台进度 / 动作链 / 打开设置这几个能力
 * （页面固定为 127.0.0.1:3080，不面向外部站点）。
 *
 * Phase 1.3 从 MainActivity 的私有嵌套类搬出，保持行为不变。
 */
internal class ProgressJsBridge(private val ctx: Context) {

    @JavascriptInterface
    fun progress(payload: String) {
        runCatching { ProgressHub.onJsProgress(ctx, payload) }
    }

    /**
     * 兼容旧调用名（v1.5.7 的 notice）：v2.5.6 起旧的 notice 小条已删除，
     * 无障碍/自动化文案统一并入工具链下方的动作链。
     */
    @JavascriptInterface
    fun notice(text: String) {
        runCatching { StatusOverlay.setA11yAction(text) }
    }

    /**
     * v2.5.6：把「无障碍/打开应用」动作追加到悬浮窗第④段动作链。
     *
     * 与工具链分开：插件/门禁在做读屏点击、启动或跳转别的 App 时调这里，
     * 用户一眼能分清「AI 在调工具」和「App 真的在点/在跳」。
     */
    @JavascriptInterface
    fun a11y(line: String) {
        runCatching { StatusOverlay.setA11yAction(line) }
    }

    /** 打开原生设置页（v1.3.70）：WebUI 侧可用来直达后台进度等设置。 */
    @JavascriptInterface
    fun openSettings() {
        runCatching {
            ctx.startActivity(
                Intent(ctx, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
