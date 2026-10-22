package app.dsh.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.dsh.mobile.AppGate
import app.dsh.mobile.DshApp
import app.dsh.mobile.MainActivity
import app.dsh.mobile.R
import app.dsh.mobile.engine.EngineSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import app.dsh.mobile.engine.LanWebProxy

/**
 * 引擎前台服务。
 *
 * 类型选择 specialUse（Android 14+ 生效）：dataSync 类型有 6 小时系统限时，
 * 长任务会被强杀；specialUse 无此限制且 sideload 分发无需 Play 审核豁免。
 */
class EngineService : Service() {

    private var stateJob: Job? = null
    private val stateScope by lazy { CoroutineScope(Dispatchers.Main) }

    override fun onCreate() {

        running = this
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏「退出」按钮：用户显式要求彻底停止（Termux 同款交互）
        if (intent?.action == ACTION_EXIT) {
            setUserExited(this, true)
            exitCompletely()
            return START_NOT_STICKY
        }
        // 自动拉起的兜底：正常退出后任何路径都不得再启动引擎
        if (userExited(this)) {
            Log.i(TAG, "skip service start: user exited normally (no auto-start)")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val app = application as DshApp

        startAsForeground()

        // Android 13+ 通知权限需动态请求；无权限时前台服务仍合法，只是通知不可见
        if (Build.VERSION.SDK_INT >= 33) {
            MainActivity.maybeRequestNotificationPermission(this)
        }

        app.supervisor.start(app.appScope)

        // v1.2.160：按偏好恢复「局域网访问 WebUI」代理（默认关闭）。
        // 刻意与引擎解耦：独立线程 + 独立端口 3090，失败只记日志，不抛出、不重启。
        runCatching {
            // ⚠️ 先注入 context：LanWebProxy.start() 需要它推导引擎目录。
            // 原设计靠 AgentBridge 注入，但 AgentBridge 在 EngineSupervisor 启动流程里
            // （比这里晚）→ 实测 start() 拿不到 ctx、静默不做任何事（日志一行都没有）。
            LanWebProxy.setContext(this)
            if (DshApp.lanWebEnabled(this)) {
                val pw = DshApp.lanWebPassword(this)
                Thread({ runCatching { LanWebProxy.start(pw) } }, "lan-boot").apply { isDaemon = true }.start()
            }
        }.onFailure { android.util.Log.w("EngineService", "lan proxy: ${it.message}") }

        // 🔴 v1.2.73 修主人实测「引擎明明在线，小字一直显示正在启动引擎」：
        // startAsForeground() 每次都把通知重置成 status_starting，而 StateFlow 只在
        // 状态**变化**时才发事件 —— MainActivity.onResume() 每次都调 EngineService.start()，
        // 于是「重进 onStartCommand → 通知被重置为启动中 → 已 Healthy 的状态不再重发」
        // → 小字永远卡住。这里立刻按当前状态强制刷一次，闭环。
        updateNotification(app.supervisor.state.value, force = true)

        // 状态回写到常驻通知
        if (stateJob == null) {
            stateJob = stateScope.launch {
                app.supervisor.state.collect { updateNotification(it) }
            }
        }
        return START_STICKY
    }

    /**
     * v1.2.91：用户从「最近任务」划掉 App 时的自愈。
     *
     * 默认行为：划掉任务卡片会**连同前台服务一起被杀**（引擎随之停止，
     * 后台的 AI 任务中断）。主人要求「利用已有的无障碍/悬浮窗机制保活」，
     * 这里先做最基础也最关键的一环 —— **被划掉后立刻把自己拉回来**。
     *
     * 为什么不做更激进的（比如定时 AlarmManager 互相拉起）：
     *  · 本项目 targetSdk 28（sideload），前台服务 + 无障碍服务已经足够稳
     *  · 过度保活会显著增加耗电，且属于灰色手段（易被 ROM 判定为"流氓应用"）
     *  · 无障碍服务本身极难被杀（系统级），它才是真正的保活底牌（见下）
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户显式退出过就不拉起（尊重"退出"语义）
        val app = application as DshApp
        if (app.supervisor.isUserStopped()) {
            Log.i(TAG, "task removed but user explicitly exited, not restarting")
            return
        }
        // v1.3.73 用户硬性要求：从最近任务划掉 = 正常退出，**绝不自动拉起**。
        // v2.3.1（用户反馈「App 退出后悬浮窗还在」）：划掉任务就是彻底退出，
        // 不能只置标记把引擎/悬浮窗留后台——直接走和通知栏「退出」同一条清理路径。
        Log.i(TAG, "task removed: exiting completely (engine + overlay)")
        setUserExited(this, true)
        exitCompletely()
    }

    override fun onDestroy() {
        running = null
        stateJob?.cancel()
        stateJob?.cancel()
        stateJob = null
        stateScope.cancel()
        (application as DshApp).supervisor.stop()
        // v2.3.1：服务销毁时兜底拆掉悬浮窗（进程可能还活着，窗口不会自动消失）。
        runCatching { app.dsh.mobile.StatusOverlay.teardown(app.dsh.mobile.DshAccessibilityService.instance) }
        runCatching { app.dsh.mobile.StatusOverlay.hideAuto(this) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification = buildNotification(getString(R.string.status_starting))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_engine),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notif_channel_engine_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private var lastNotifText: String = ""

    private fun buildNotification(text: String): Notification {
        // SINGLE_TOP：MainActivity 是 singleTask，复用已有实例走 onNewIntent，
        // 杜绝通知点击新建实例压出「双界面」（实测事故）
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // Termux 式「退出」按钮：通知展开后可见（折叠态部分 ROM 精简 action）
        val exitPending = PendingIntent.getService(
            this, 1,
            Intent(this, EngineService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val exitAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(
                this, android.R.drawable.ic_menu_close_clear_cancel,
            ),
            getString(R.string.notif_action_exit),
            exitPending,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pending)
            .addAction(exitAction)
            .build()
    }

    private fun updateNotification(state: EngineSupervisor.State, force: Boolean = false) {
        // ⚠️ v1.2.73：**每个状态都要有文案**。旧版只有 Healthy/Backoff/Failed 三种，
        // 其余（Idle/Installing/Starting/Stopped）直接 `return` 丢弃 —— 表现为
        // 「引擎明明已就绪，小字还停在正在启动引擎」：startAsForeground() 先写了
        // status_starting，而随后的 Starting 事件被丢弃、Healthy 事件又被
        // `if (text == lastNotifText) return` 之类的时序问题挡住时，小字就永远不更新。
        val text = when (state) {
            is EngineSupervisor.State.Healthy -> state.recoveredPlugin?.let {
                getString(R.string.status_plugin_recovered, it)
            } ?: getString(R.string.status_healthy)
            is EngineSupervisor.State.SafeMode -> state.recoveredPlugin?.let {
                getString(R.string.status_plugin_recovered, it)
            } ?: getString(R.string.status_healthy)
            is EngineSupervisor.State.Installing -> getString(R.string.status_installing)
            is EngineSupervisor.State.Starting -> getString(R.string.status_starting)
            is EngineSupervisor.State.Backoff ->
                getString(R.string.status_backoff, state.delayMs / 1000, state.attempt)
            is EngineSupervisor.State.Failed -> state.reason
            is EngineSupervisor.State.Stopped -> getString(R.string.status_exiting)
            else -> getString(R.string.status_starting)
        }
        if (!force && text == lastNotifText) return
        lastNotifText = text
        // ⚠️ v1.2.44：前台服务通知必须用 startForeground 再发一次来更新 ——
        // 用 NotificationManager.notify() 更新 FGS 通知在 Android 13+ 常被静默忽略
        // （用户实测：通知标题正确但小字永远停在「正在启动引擎」），且该路径还依赖
        // POST_NOTIFICATIONS 权限；startForeground 对 FGS 通知始终有效。
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /**
     * 彻底退出：杀引擎 → 移除通知 → 停服务。
     *
     * 🔴 v1.2.73 修主人实测「点退出完全没有反应」：
     * `supervisor.stop()` 内部是 `EngineProcess.stop(graceMs = 10_000)` ——
     * **阻塞式**等引擎退出（最长 10 秒，实测 TERM 后还要 `pumpThread.join(1000)`），
     * 而它跑在 **onStartCommand 的主线程**上。于是点下退出后通知栏十几秒纹丝不动，
     * 用户完全感知不到任何反馈。
     *
     * 现在：① 先立刻把通知文案改成「正在退出…」（有反馈）；
     *      ② 真正耗时的停止动作丢到后台线程，且宽限压到 3 秒（不干等）；
     *      ③ 完成后回主线程移除通知并停服务。
     */
    private fun exitCompletely() {
        stateJob?.cancel()
        stateJob = null
        // ① 立刻反馈
        runCatching { pushStatusText(getString(R.string.status_exiting)) }
        // ② 后台真正停止（阻塞动作不能在主线程）
        Thread({
            // explicit = true：用户主动退出 → 置「显式退出」标记；
            // MainActivity 观察到后会 finishAndRemoveTask 并销毁 WebView，
            // 释放 ~380MB（v1.2.187，用户反馈「退出不彻底、后台仍占内存耗电」）。
            runCatching { (application as DshApp).supervisor.stop(graceMs = 3_000, explicit = true) }
            // v1.2.187：这两个是**独立子进程**，不在引擎进程树里，退出时必须显式停 ——
            //  · 局域网 WebUI 代理（node，端口 3090）：开着时退出引擎它仍在跑
            //  · 本地推理服务（llama-server）：/local/ensure 起过就一直驻留
            // 不停的话用户看到"引擎停了"但后台仍有进程吃内存/电。
            runCatching { app.dsh.mobile.engine.LanWebProxy.stop() }
            runCatching { app.dsh.mobile.engine.LocalModel.stop() }
            // ③ 回主线程收尾
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                }
                // v1.2.157 用户实测反馈：退出引擎后悬浮窗仍在后台（球 + 3s 周期刷新 +
                // 回空闲定时器）→ 进程持续活跃、耗电。退出时一并彻底拆掉。
                runCatching {
                    app.dsh.mobile.StatusOverlay.teardown(app.dsh.mobile.DshAccessibilityService.instance)
                }
                stopSelf()
            }
        }, "engine-exit").apply { isDaemon = true; start() }
    }

    /** 直接把通知小字改成指定文案（不走状态机，用于退出这类即时反馈） */
    private fun pushStatusText(text: String) {
        lastNotifText = text
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val TAG = "EngineService"
        private const val CHANNEL_ID = "engine"
        private const val NOTIF_ID = 42

        /** 运行中的服务实例（供能力桥在通知权限缺失时降级顶替前台通知文案） */
        @Volatile private var running: EngineService? = null

        /** 前台服务是否存活（供无障碍服务的保活看门狗判断，v1.2.91） */
        fun isRunning(): Boolean = running != null

        /**
         * 把 AI 的消息临时顶到前台服务通知的小字上（POST_NOTIFICATIONS 未授予时的降级通道：
         * FGS 通知豁免该权限，始终可见）。返回 false = 服务未在运行。
         * 下一次引擎状态变化会覆盖回常规文案。
         */
        fun pushAgentNotice(text: String): Boolean = running?.let { svc ->
            runCatching {
                val n = svc.buildNotification(text)
                if (Build.VERSION.SDK_INT >= 34) {
                    svc.startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    svc.startForeground(NOTIF_ID, n)
                }
                true
            }.getOrDefault(false)
        } ?: false

        private const val PREFS_EXIT = "dsh_exit"
        private const val KEY_USER_EXITED = "user_exited"

        /**
         * v1.3.73（用户硬性要求）：**正常退出后绝不自动启动**。
         *
         * 置位时机：通知栏「退出」、从最近任务划掉 App（onTaskRemoved）。
         * 清除时机：用户主动打开 MainActivity（onCreate 且非重建）。
         * 读取时机：start() 与无障碍保活看门狗 —— 置位期间一律跳过。
         */
        fun userExited(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_EXIT, Context.MODE_PRIVATE)
                .getBoolean(KEY_USER_EXITED, false)

        fun setUserExited(ctx: Context, exited: Boolean) {
            ctx.getSharedPreferences(PREFS_EXIT, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USER_EXITED, exited).apply()
        }

        /** 通知「退出」按钮触发动作 */
        const val ACTION_EXIT = "app.dsh.mobile.service.action.EXIT"

        /**
         * 便捷启动入口（供 Activity **与保活看门狗**调用）。
         *
         * ⚠️ v1.2.100：WebView 版本门禁判断放在这里 —— 这是**所有自动启动路径的唯一汇聚点**
         * （MainActivity.onResume、无障碍服务保活看门狗、通知点击）。旧实现只在
         * MainActivity 里判版本，看门狗会绕过它照常拉起引擎 → 低版本 WebView 设备
         * 仍然「引擎白跑 + 前端白屏」（独立审查指出）。
         *
         * 判定规则：
         *  · 用户已在本会话里看到并选择「仍然继续」（[AppGate.userOverride]）→ 放行
         *  · 版本达标 → 放行（并记住，后续零开销）
         *  · 版本不足且用户未知情 → 不启动（让 MainActivity 去弹说明对话框）
         */
        fun start(context: Context) {
            // v1.3.73：正常退出后（含划掉任务）任何自动路径都不得启动
            if (userExited(context)) {
                android.util.Log.i("EngineService", "skip start: user exited normally")
                return
            }
            val app = context.applicationContext as? app.dsh.mobile.DshApp
            // 用户刚从通知栏显式退出 → 跳过生命周期自动拉起（否则「退出不了」，
            // MainActivity.onResume 会立刻把服务拉回来 ✗）。想再开：主界面点「启动」。
            if (app?.supervisor?.isUserStopped() == true) {
                android.util.Log.i("EngineService", "skip auto-start: user explicitly exited")
                return
            }
            // WebView 版本门禁（见上）
            if (!AppGate.webViewAllowed(context)) {
                android.util.Log.i("EngineService", "skip auto-start: webview below minimum (gate not overridden)")
                return
            }
            context.startForegroundService(Intent(context, EngineService::class.java))
        }
    }
}
