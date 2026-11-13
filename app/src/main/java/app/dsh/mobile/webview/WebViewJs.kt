package app.dsh.mobile.webview

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** 从 assets/js 加载 WebView 自检、分享写入及修复脚本。 */
internal object WebViewJs {
    /** 自检版本与关键 API；polyfill 能补 API，不能修复旧内核不支持的 JS 语法。 */
    fun selfCheckJs(): String = JsAssets.read("webview-selfcheck.js")
    fun maybeRequestNotificationPermission(activity: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = activity.checkSelfPermission(
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted && activity is Activity) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    /** 经页面输入事件写入/提交，保持 WebUI 的受控输入与附件流程。 */
    fun buildVoiceInputJs(text: String): String =
        JsAssets.read("share-voice-input.js").replace("__DSH_TEXT__", org.json.JSONObject.quote(text))
    fun buildVoiceRepairJs(text: String): String =
        JsAssets.read("share-voice-repair.js").replace("__DSH_TEXT__", org.json.JSONObject.quote(text))
}
