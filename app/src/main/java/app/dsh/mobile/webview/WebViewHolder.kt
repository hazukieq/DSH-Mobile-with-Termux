package app.dsh.mobile.webview

import android.app.Activity
import android.content.MutableContextWrapper
import android.webkit.WebView

/**
 * 全局唯一的 WebView 宿主（Application 级生命周期）。
 *
 * 为什么不能 `WebView(activity)`：Activity 重建（旋转、主题切换、返回栈回收）时 WebView 会跟着被销毁，
 * 任何「外部要拿当前 WebView」的能力（例如 `/agent/prompt` 往自己的会话里投 prompt）就会拿到 null。
 *
 * 做法（参照 MutableContextWrapper 的常规用法）：
 *  - WebView 只创建一次，初始 context 是 `MutableContextWrapper(applicationContext)`；
 *  - Activity 进来时把 baseContext 指向该 Activity（弹窗/输入法/主题才会用对）；
 *  - Activity 销毁时把 baseContext 换回 applicationContext，**不销毁 WebView**；
 *  - 只有 App 真正退出（Activity finishing 且非配置变更）才 [destroy]。
 */
object WebViewHolder {

    private var view: WebView? = null

    /** 取（必要时创建）全局 WebView，并把它的 baseContext 指向 [activity]。必须在主线程调用。 */
    fun obtain(activity: Activity): WebView {
        val existing = view
        if (existing != null) {
            (existing.context as? MutableContextWrapper)?.baseContext = activity
            return existing
        }
        val created = WebView(MutableContextWrapper(activity.applicationContext))
        (created.context as? MutableContextWrapper)?.baseContext = activity
        view = created
        return created
    }

    /** Activity 销毁：把 baseContext 换回 Application，WebView 本体保留给下一次重建。 */
    fun detach() {
        val v = view ?: return
        runCatching {
            val app = v.context.applicationContext
            (v.context as? MutableContextWrapper)?.baseContext = app
        }
    }

    /** 当前全局 WebView（可能为 null：还没创建过，或已 destroy）。 */
    fun current(): WebView? = view

    /** App 真正结束时销毁。 */
    fun destroy() {
        val v = view ?: return
        view = null
        runCatching {
            v.stopLoading()
            v.webChromeClient = null
            v.webViewClient = android.webkit.WebViewClient()
            v.clearHistory()
            v.removeAllViews()
            v.destroy()
        }
    }
}
