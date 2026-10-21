package app.dsh.mobile

import android.app.Application
import app.dsh.mobile.engine.EngineSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DshApp : Application() {

    /** 全局协程域：监督器生命周期独立于任何 Activity/Service */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 全局唯一监督器单例，Activity 与 Service 共享状态 */
    lateinit var supervisor: EngineSupervisor
        private set

    /** 全局唯一扩展管理器单例：扩展中心 UI 与 AI 通道（AgentBridge）共享下载任务状态 */

    override fun onCreate() {
        super.onCreate()
        // ⚠️ v1.2.110：全局崩溃捕获 —— 用户反馈「进程异常退出」（1.2.97 升级后 ×2 人，
        // Android 16 未复现，疑为 Android 17 兼容问题）。没有现场日志无法定位，
        // 现在任何 Java 崩溃都会落盘 `filesDir/crash-last.txt`（时间、线程、堆栈），
        // 用户或支持把该文件发回来即可定位。若该文件为空，说明是 native 崩溃
        // （tombstone 在系统日志里，需 logcat 提供）。
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val f = java.io.File(filesDir, "crash-last.txt")
                f.writeText(
                    buildString {
                        appendLine("time: ${System.currentTimeMillis()}")
                        appendLine("thread: ${thread.name}")
                        appendLine(android.util.Log.getStackTraceString(throwable))
                    },
                )
                android.util.Log.e("DshApp", "crash captured -> ${f.absolutePath}")
            }
            prev?.uncaughtException(thread, throwable)
        }
        // WebView 远程调试（v1.3.61 起**默认开启**，用户要求方便 agent 调试）：
        // adb forward tcp:9222 localabstract:webview_devtools_remote_<pid> 后，
        // Chrome/Playwright 连 127.0.0.1:9222 就能量 DOM 与注入 CSS。
        // 需要关闭时在外部文件目录放 `webview-debug-off`：
        //   adb shell touch /sdcard/Android/data/com.termux/files/webview-debug-off
        // （旧的 `webview-debug` 开关文件仍被识别，只是不再必需。）
        runCatching {
            val optOut = getExternalFilesDir(null)?.let { java.io.File(it, "webview-debug-off") }
            if (optOut?.isFile != true) {
                android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                android.util.Log.i("DshApp", "WebView remote debugging enabled (default; opt out: webview-debug-off)")
            }
        }
        // 每次启动都复查 Agent 侧产物（v1.3.24）：不是首次安装也要补齐缺失的门脚本、
        // 自述文档、dsh-mobile 助手与 dotfiles。后台线程做，避免阻塞首帧。
        Thread({
            runCatching { app.dsh.mobile.engine.TermuxDotfiles.ensure(this) }
            runCatching { app.dsh.mobile.engine.EngineConfig.ensureAgentArtifacts(this) }
        }, "dsh-asset-sync").start()
        supervisor = EngineSupervisor(this)
    }

    companion object {
        /** 设置页 UI 偏好（页面缩放/横竖屏），与 SettingsActivity 共用 */
        const val PREFS_UI = "dsh_ui"

        /**
         * max+ 的模型来源（v1.2.127）：选中的凭证记录键名（如 DEEPSEEK_API_KEY）。
         * 空串 = 未显式选择（按 CredentialProbe.defaultRef 兜底：优先 DeepSeek 官方）。
         */
        const val KEY_A11Y_MAX_PROVIDER = "a11y_max_provider"

        fun a11yMaxProvider(context: android.content.Context): String =
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .getString(KEY_A11Y_MAX_PROVIDER, "") ?: ""

        /** 局域网访问 WebUI（v1.2.160）：让同网段设备用原生界面连上本机引擎。默认关闭。 */
        const val KEY_LAN_WEB = "lan_web_access"
        private const val KEY_LAN_WEB_PW = "lan_web_password"
        /** 验证码是否处于"待展示"状态（只显示一次，再进设置不再显示） */
        private const val KEY_LAN_WEB_PW_PENDING = "lan_web_pw_pending"

        fun lanWebEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LAN_WEB, false)

        fun setLanWebEnabled(context: android.content.Context, on: Boolean) {
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LAN_WEB, on).apply()
        }

        /** 访问密码：首次生成后固定（8 位十六进制） */
        fun lanWebPassword(context: android.content.Context): String {
            val prefs = context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
            val cur = prefs.getString(KEY_LAN_WEB_PW, "") ?: ""
            if (cur.length >= 6) return cur
            return regenerateLanWebPassword(context)
        }

        /**
         * 生成新的 6 位数字验证码（v1.2.161，用户要求）。
         * 每次「开启局域网访问」都重新生成 —— 不复用旧码，避免长期有效的固定口令。
         */
        fun regenerateLanWebPassword(context: android.content.Context): String {
            val prefs = context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
            val pw = (1..6).map { "0123456789".random() }.joinToString("")
            // 同时标记"待展示"：新码生成后应在设置页显示一次（并自动复制）
            prefs.edit().putString(KEY_LAN_WEB_PW, pw)
                .putBoolean(KEY_LAN_WEB_PW_PENDING, true).apply()
            return pw
        }

        /** 是否还需要在设置页显示验证码：只在"刚生成、还没展示过"时为真（只显示一次） */
        fun lanWebPwPending(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LAN_WEB_PW_PENDING, false)

        fun markLanWebPwShown(context: android.content.Context) {
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LAN_WEB_PW_PENDING, false).apply()
        }


    }
}
