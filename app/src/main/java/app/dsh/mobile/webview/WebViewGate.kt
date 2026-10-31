package app.dsh.mobile.webview

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import app.dsh.mobile.AppGate
import app.dsh.mobile.R

/**
 * Web 内核版本门禁（Phase 1.4 从 MainActivity 提取，v1.2.100 逻辑原样）。
 *
 * ## 为什么需要
 * 引擎前端用了 `??=` / 类字段等**解析期**语法（Chrome 85+），polyfill 只能补
 * **API 层**（13 个现代 API 注入），补不了语法 —— WebView 过旧时页面必白屏。
 * 之前的处理是「照常启动引擎 + 日志里记录 SyntaxError」：用户看到的是
 * 白屏/无限重启，不知道原因（酷安用户反馈的正是这种体验）。
 *
 * ## 做法
 * 直接查 WebView Provider 的包版本（不走 JS，无需加载页面）：
 *   · ≥ [AppGate.MIN_WEBVIEW_CHROME]：放行（本会话内不再重复检查）
 *   · < 门槛：弹对话框，给出当前版本、升级路径（应用商店/Play），并提供
 *     「仍然继续」—— 用户明确坚持才启动引擎（尊重知情选择）；对话框
 *     不会因 AlertDialog 取消而反复弹出，选择「继续」后本会话记入记忆。
 *
 * @param webViewProvider WebView 懒引用（三级取版本的第 ③ 号路径用；未初始化时为 null）
 * @param onGateOverridden 用户点「仍然继续」后回调，让宿主重走一遍启动流程
 * @param log 诊断日志（写 logcat + engine.log，由宿主提供）
 */
class WebViewGate(
    private val activity: Activity,
    private val webViewProvider: () -> WebView?,
    private val onGateOverridden: () -> Unit,
    private val log: (String) -> Unit,
) {

    /**
     * 当前显示的版本门禁对话框（v1.2.100）。
     * ⚠️ 必须持有引用并判重：`onResume` 会被反复触发（切回前台、息屏亮屏、点按钮后
     * 重入），旧实现每次都 `show()` 一个新对话框 → **无限叠堆**（且 setCancelable(false)
     * 无法取消）。独立审查指出后补上这道守卫。
     */
    private var dialog: AlertDialog? = null

    /**
     * @return true = 放行（版本达标或用户坚持）
     */
    fun check(): Boolean {
        val ver = currentWebViewVersion()
        if (ver >= AppGate.MIN_WEBVIEW_CHROME) {
            AppGate.markWebViewOk()
            return true
        }
        AppGate.markWebViewBlocked()
        // 升级指引：优先指向 Play 商店的 WebView 页（绝大多数设备），失败则给系统设置
        val updateIntent = runCatching {
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"))
        }.getOrElse {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:com.google.android.webview"))
        }
        val msg = activity.getString(
            R.string.webview_gate_body,
            if (ver > 0) ver.toString() else "??",
            AppGate.MIN_WEBVIEW_CHROME,
        )
        if (dialog?.isShowing == true) {
            log("webview gate: dialog already showing, skip")
            return false
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.webview_gate_title))
            .setMessage(msg)
            .setPositiveButton(R.string.webview_gate_update) { _, _ ->
                runCatching { activity.startActivity(updateIntent) }
                // 升级完回来 onResume 会重新检测（AppGate 仍是 BLOCKED，会再判一次）
            }
            .setNegativeButton(R.string.webview_gate_continue) { _, _ ->
                AppGate.overrideWebViewGate()
                onGateOverridden()   // 重新走一遍（这次会启动引擎）
            }
            .setCancelable(false)
            .create()
            .also { dialog = it }
            .show()
        log("webview gate: chrome=$ver < ${AppGate.MIN_WEBVIEW_CHROME}, prompting user")
        return false
    }

    /** Activity 销毁时收起对话框，避免泄漏。 */
    fun dismiss() {
        runCatching { dialog?.takeIf { it.isShowing }?.dismiss() }
        dialog = null
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
    fun currentWebViewVersion(): Int {
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
                val info = runCatching { activity.packageManager.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
                val vn = info.versionName ?: continue
                Regex("Chrome/(\\d+)").find(vn)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
                vn.substringBefore('.')?.toIntOrNull()?.let { if (it > 0) return it }
            }
        }
        // ③ 现有实例的 UA
        webViewProvider()?.let { wv ->
            runCatching {
                Regex("Chrome/(\\d+)").find(wv.settings.userAgentString ?: "")
                    ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

    companion object {
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
    }
}
