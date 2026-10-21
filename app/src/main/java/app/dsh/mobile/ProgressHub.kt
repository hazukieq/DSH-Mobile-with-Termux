package app.dsh.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * 后台进度提示（v1.3.68）。
 *
 * 目标：用户离开 App 后仍能知道「当前在思考什么 / 做到哪一步」，且**不依赖无障碍**。
 *
 * 数据源：
 *  - WebUI 注入的 mobile-progress 插件监听会话 DOM，通过 `DshNative.progress(json)`
 *    JS Bridge 上报快照（主要来源，无需无障碍）；
 *  - Agent 仍可走 `POST /overlay`（兼容旧行为），也会汇入这里。
 *
 * 呈现：
 *  - `notify`（默认）：低打扰持续通知，锁屏只显示状态、隐藏正文（VISIBILITY_PRIVATE）；
 *  - `overlay`：优先应用悬浮窗（SYSTEM_ALERT_WINDOW）；无权限时回退无障碍窗口；
 *    两者都不可用则回退通知；
 *  - `off`：全部关闭。
 */
object ProgressHub {
    private const val TAG = "ProgressHub"
    private const val PREFS = "dsh_progress"
    private const val KEY_MODE = "mode"
    const val MODE_OFF = "off"
    const val MODE_NOTIFY = "notify"
    const val MODE_OVERLAY = "overlay"

    private const val CHANNEL_ID = "dsh_progress"
    private const val NOTIF_ID = 4301

    private val main = Handler(Looper.getMainLooper())

    private data class Snapshot(val kind: String, val title: String, val detail: String, val at: Long)

    @Volatile private var last: Snapshot? = null
    @Volatile private var appVisible = false
    @Volatile private var lastPublishAt = 0L
    @Volatile private var doneClearRunnable: Runnable? = null
    /** 后台有任务在跑时抓一个 PARTIAL_WAKE_LOCK，减少 Doze/省电策略把引擎冻死的概率。 */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    fun mode(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, MODE_NOTIFY)
            ?: MODE_NOTIFY

    fun setMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()
        when (mode) {
            MODE_OFF -> clearAll(ctx)
            else -> maybePublish(ctx, force = true)
        }
    }

    fun modeLabel(ctx: Context, mode: String): String = ctx.getString(
        when (mode) {
            MODE_OFF -> R.string.progress_mode_off
            MODE_OVERLAY -> R.string.progress_mode_overlay
            else -> R.string.progress_mode_notify
        },
    )

    /** App 前后台变化：前台时收起提示，回到后台立刻展示当前快照。 */
    fun onAppVisibility(ctx: Context, visible: Boolean) {
        appVisible = visible
        maybePublish(ctx, force = true)
    }

    /** JS Bridge 上报（字符串避免 Kotlin/JS 类型转换成本）。 */
    fun onJsProgress(ctx: Context, payload: String) {
        runCatching {
            val o = JSONObject(payload)
            update(ctx, o.optString("kind"), o.optString("title"), o.optString("detail"))
        }.onFailure { Log.w(TAG, "bad progress payload: ${it.message}") }
    }

    /** Agent 的 POST /overlay 兼容入口。 */
    fun onAgentProgress(ctx: Context, status: String?, action: String?, complete: Boolean) {
        if (complete) {
            update(ctx, "done", "", status ?: ctx.getString(R.string.progress_state_done))
            return
        }
        val detail = listOfNotNull(status, action).filter { it.isNotBlank() }.joinToString(" · ")
        if (detail.isBlank()) return
        update(ctx, "working", "", detail)
    }

    fun update(ctx: Context, kind: String, title: String, detail: String) {
        val k = when (kind) {
            "thinking", "tool", "done", "error", "idle", "working" -> kind
            else -> "working"
        }
        val s = Snapshot(k, title.trim().take(80), detail.trim().take(240), System.currentTimeMillis())
        val prev = last
        if (prev != null && prev.kind == s.kind && prev.detail == s.detail && prev.title == s.title) {
            return
        }
        last = s
        maybePublish(ctx, force = (k == "done" || k == "error"))
    }

    private fun maybePublish(ctx: Context, force: Boolean) {
        val m = mode(ctx)
        if (m == MODE_OFF) { clearAll(ctx); return }
        val snap = last
        if (snap == null) return
        if (!force && System.currentTimeMillis() - lastPublishAt < 600L) return
        lastPublishAt = System.currentTimeMillis()

        // 后台有任务在跑：保持一个短暂的 partial wakelock（屏幕熄灭/Doze 时引擎仍能继续跑）
        updateWakeLock(ctx, snap)

        // App 在前台且不是完成/错误：不打扰（回后台时 onAppVisibility 会补一次 force）
        if (appVisible && snap.kind != "done" && snap.kind != "error") {
            cancelNotification(ctx)
            runCatching { StatusOverlay.hide(ctx) }
            return
        }

        if (m == MODE_OVERLAY) {
            val shown = runCatching { StatusOverlay.show(ctx) }.getOrDefault(false)
            if (shown) {
                runCatching {
                    StatusOverlay.resetHidden()
                    StatusOverlay.setStatus(snap.detail.ifBlank { stateLabel(ctx, snap.kind) })
                    StatusOverlay.setAction(snap.title.ifBlank { stateLabel(ctx, snap.kind) })
                    if (snap.kind == "done" || snap.kind == "error") StatusOverlay.flashCompleteDefault(6000L)
                }
                cancelNotification(ctx)
                return
            }
            // 没有悬浮窗权限且没有无障碍 → 回退通知
        }
        publishNotification(ctx, snap)
    }

    private fun updateWakeLock(ctx: Context, snap: Snapshot) {
        val running = !appVisible && (snap.kind == "thinking" || snap.kind == "tool" || snap.kind == "working")
        runCatching {
            if (running) {
                if (wakeLock?.isHeld != true) {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "dsh:background-task").apply {
                        setReferenceCounted(false)
                        acquire(15 * 60 * 1000L)
                    }
                    Log.i(TAG, "wakelock acquired (background task running)")
                }
            } else {
                wakeLock?.let { if (it.isHeld) { it.release(); Log.i(TAG, "wakelock released") } }
                wakeLock = null
            }
        }
    }

    private fun publishNotification(ctx: Context, snap: Snapshot) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm, ctx)
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val titleText = stateLabel(ctx, snap.kind)
        val text = snap.detail.ifBlank { snap.title }
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(R.string.progress_channel) + " · " + titleText)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // 锁屏不显示正文（只显示状态），解锁后才展示详情
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.progress_open), open).build())
        val finished = snap.kind == "done" || snap.kind == "error"
        builder.setOngoing(!finished)
        nm.notify(NOTIF_ID, builder.build())
        doneClearRunnable?.let { main.removeCallbacks(it); doneClearRunnable = null }
        if (finished) {
            val r = Runnable { cancelNotification(ctx) }
            doneClearRunnable = r
            main.postDelayed(r, 120_000L)
        }
        Log.i(TAG, "progress notification: kind=${snap.kind} len=${text.length}")
    }

    private fun ensureChannel(nm: NotificationManager, ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.progress_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
    }

    private fun stateLabel(ctx: Context, kind: String): String = ctx.getString(
        when (kind) {
            "thinking" -> R.string.progress_state_thinking
            "done" -> R.string.progress_state_done
            "error" -> R.string.progress_state_error
            "idle" -> R.string.progress_state_idle
            else -> R.string.progress_state_working
        },
    )

    private fun cancelNotification(ctx: Context) {
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        }
    }

    fun clearAll(ctx: Context) {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
        cancelNotification(ctx)
        runCatching { StatusOverlay.hide(ctx) }
        last = null
    }

    /** 设置页「预览一次」。 */
    fun preview(ctx: Context) {
        val s = Snapshot("thinking", ctx.getString(R.string.progress_channel), ctx.getString(R.string.progress_preview_text), System.currentTimeMillis())
        last = s
        publishNotification(ctx, s)
    }

    /** 通知权限是否可用（13+ 需要 POST_NOTIFICATIONS）。 */
    fun notificationsEnabled(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    }.getOrDefault(true)
}
