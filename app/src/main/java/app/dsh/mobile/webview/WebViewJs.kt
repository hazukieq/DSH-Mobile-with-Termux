package app.dsh.mobile.webview

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * WebView 注入脚本与相关工具（Phase 1.3 从 MainActivity 的 companion object 提取）。
 *
 * 为什么单独成文件：这些是**一屏一屏的 JS 字符串**（环境自检、语音输入写入、
 * 输入框修复），放在 Activity 里会把 1800 行的类撑得更难读；
 * 它们与 Android 生命周期无关，只与「页面里该跑什么脚本」有关。
 *
 * TODO(Phase 4)：外置成 `assets/js` 下的独立 .js 文件，便于单独 lint / 复用。
 */
internal object WebViewJs {

        /**
         * 环境自检脚本：报告 WebView 版本与引擎所需关键 API 是否存在。
         * 引擎实际需要 Chrome 126（pdf.js 用 URL.parse），polyfill 可补 API 但补不了语法
         * （`??=` 需 Chrome 85，语法错误会导致白屏 —— 那种情况日志里会看到 SyntaxError）。
         */
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

        /**
         * 把识别出的文本写入 WebUI 的输入框并提交（v1.2.58）。
         *
         * 为什么用 JS 注入而不是直接调引擎 API：WebUI 的输入框是前端状态的一部分，
         * 只有走它的 DOM 才能触发正确的发送流程（含附件、@引用等既有逻辑）。
         *
         * 选择器策略（按可靠性排序，逐个尝试）：
         *  ① textarea（当前前端用的是 textarea）
         *  ② [contenteditable]（富文本输入框）
         *  ③ input[type=text]
         * 写入用原生 setter + 派发 input 事件，让 React 的受控组件感知变化
         * （直接改 value 而不派发事件，React 会忽略）。
         */
        fun buildVoiceInputJs(text: String): String =
            JsAssets.read("share-voice-input.js").replace("__DSH_TEXT__", org.json.JSONObject.quote(text))
        fun buildVoiceRepairJs(text: String): String =
            JsAssets.read("share-voice-repair.js").replace("__DSH_TEXT__", org.json.JSONObject.quote(text))



}
