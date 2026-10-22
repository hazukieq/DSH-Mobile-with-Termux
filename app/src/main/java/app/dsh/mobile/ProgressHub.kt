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

    private data class Snapshot(val kind: String, val title: String, val detail: String, val session: String, val elapsed: Long, val at: Long)

    @Volatile private var last: Snapshot? = null
    @Volatile private var appVisible = false
    @Volatile private var lastPublishAt = 0L
    /** sessionId -> 会话标题缓存（插件只带 id，标题由原生读引擎投影缓存解析）。 */
    private val sessionTitles = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var doneClearRunnable: Runnable? = null

    /** 后台有任务在跑时抓一个 PARTIAL_WAKE_LOCK，减少 Doze/省电策略把引擎冻死的概率。 */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    fun mode(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // v1.4.5：默认改成「悬浮窗」——用户明确要求「总是显示悬浮窗最好」。
        // 旧安装若已显式选择过，保留原值；从未选过（缺少 key）时才落到新的默认。
        if (!prefs.contains(KEY_MODE)) {
            prefs.edit().putString(KEY_MODE, MODE_OVERLAY).apply()
            return MODE_OVERLAY
        }
        return prefs.getString(KEY_MODE, MODE_OVERLAY) ?: MODE_OVERLAY
    }

    fun setMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()
        when (mode) {
            MODE_OFF -> { clearAll(ctx); runCatching { StatusOverlay.hideAuto(ctx) } }
            MODE_OVERLAY -> {
                // v1.4.7：切到悬浮窗必须**立刻出现**，不能等下一次进度事件
                //（用户实测：设置里切完返回，什么都没有）。
                ensureOverlayVisible(ctx, placeholder = true)
            }
            else -> {
                runCatching { StatusOverlay.hideAuto(ctx) }
                maybePublish(ctx, force = true)
            }
        }
    }

    /**
     * 悬浮窗模式下确保窗口已经显示；没有进度快照时放一个「等待任务」占位，
     * 让用户切完设置/回到 App 就能看到它。
     */
    fun ensureOverlayVisible(ctx: Context, placeholder: Boolean = false) {
        if (mode(ctx) != MODE_OVERLAY) return
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        // 窗口随 Activity 销毁而消失时，root 字段仍是旧引用 → 先丢掉再重建
        if (!StatusOverlay.isAttached()) StatusOverlay.dropStaleRoot()
        val shown = runCatching { StatusOverlay.show(ctx) }.getOrDefault(false)
        if (!shown) return
        val snap = last
        if (snap != null) {
            maybePublish(ctx, force = true)
            return
        }
        if (placeholder) {
            runCatching {
                StatusOverlay.resetHidden()
                StatusOverlay.setStatus(ctx.getString(R.string.overlay_idle))
                StatusOverlay.setAction(ctx.getString(R.string.progress_overlay_ready))
            }
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
            update(ctx, o.optString("kind"), o.optString("title"), o.optString("detail"), o.optString("session"), o.optLong("elapsed"))
        }.onFailure { Log.w(TAG, "bad progress payload: ${it.message}") }
    }

    /** Agent 的 POST /overlay 兼容入口。 */
    fun onAgentProgress(ctx: Context, status: String?, action: String?, complete: Boolean) {
        // v1.5.7：无障碍/自动化的 POST（status/action）走悬浮窗底部独立 notice，
        // 不再写进思考链（之前会把链路覆盖成「点击/滑动」）。
        if (!complete) {
            val notice = listOfNotNull(status, action).filter { it.isNotBlank() }.joinToString(" · ")
            if (notice.isNotBlank() && mode(ctx) == MODE_OVERLAY) {
                runCatching { StatusOverlay.setNotice(notice) }
                return
            }
        }
        if (complete) {
            update(ctx, "done", "", status ?: ctx.getString(R.string.progress_state_done), "", 0)
            return
        }
        val detail = listOfNotNull(status, action).filter { it.isNotBlank() }.joinToString(" · ")
        if (detail.isBlank()) return
        update(ctx, "working", "", detail, "", 0)
    }

    fun update(ctx: Context, kind: String, title: String, detail: String, session: String, elapsed: Long = 0) {
        val k = when (kind) {
            "thinking", "tool", "done", "error", "idle", "working" -> kind
            else -> "working"
        }
        val sess = session.trim().ifBlank { title.trim() }
        val s = Snapshot(k, title.trim().take(80), detail.trim().take(16000), sess, elapsed, System.currentTimeMillis())
        val prev = last
        // done/error 永远发布：会话结束时内容可能与上一帧相同，但状态必须切到「完成」
        if (k != "done" && k != "error" && prev != null && prev.kind == s.kind &&
            prev.detail == s.detail && prev.title == s.title &&
            prev.elapsed / 1000L == s.elapsed / 1000L
        ) {
            return
        }
        val cached = sessionTitles[s.session]
        if (cached != null && cached != s.title) {
            last = s.copy(title = cached)
        } else {
            last = s
            if (s.session.isNotBlank() && cached == null) resolveSessionTitle(ctx, s.session)
        }
        maybePublish(ctx, force = (k == "done" || k == "error"))
    }

    /** 从引擎会话投影缓存解析会话标题（保持原有后台线程习惯，失败静默）。 */
    private fun resolveSessionTitle(ctx: Context, session: String) {
        if (sessionTitles.containsKey(session)) return
        Thread({
            runCatching {
                val info = app.dsh.mobile.engine.SessionReader.list(ctx).firstOrNull { it.id == session }
                if (info != null) {
                    val t = app.dsh.mobile.engine.SessionReader.titleOf(ctx, info)
                    if (t.isNotBlank()) {
                        sessionTitles[session] = t
                        last?.let { cur -> if (cur.session == session) update(ctx, cur.kind, t, cur.detail, session, cur.elapsed) }
                    }
                }
            }
        }, "progress-title").apply { isDaemon = true }.start()
    }

    private fun maybePublish(ctx: Context, force: Boolean) {
        val m = mode(ctx)
        if (m == MODE_OFF) { clearAll(ctx); return }
        val snap = last
        if (snap == null) return
        val gap = if (m == MODE_OVERLAY) 120L else 600L
        if (!force && System.currentTimeMillis() - lastPublishAt < gap) return
        lastPublishAt = System.currentTimeMillis()

        // 后台有任务在跑：保持一个短暂的 partial wakelock（屏幕熄灭/Doze 时引擎仍能继续跑）
        updateWakeLock(ctx, snap)

        // App 在前台时：通知不打扰；但悬浮窗按用户要求**始终显示**（不再隐藏）。
        if (appVisible && snap.kind != "done" && snap.kind != "error") {
            cancelNotification(ctx)
            if (m != MODE_OVERLAY) {
                runCatching { StatusOverlay.hideAuto(ctx) }
                return
            }
        }

        if (m == MODE_OVERLAY) {
            // 后台时重新挂一次窗口：部分 ROM 会把应用悬浮窗 surface 置 hidden
            if (!appVisible) runCatching { StatusOverlay.reassertVisible() }
            val shown = runCatching { StatusOverlay.show(ctx) }.getOrDefault(false)
            if (shown) {
                runCatching {
                    StatusOverlay.resetHidden()
                    StatusOverlay.setStatus(snap.detail.ifBlank { stateLabel(ctx, snap.kind) })
                    val secs = snap.elapsed / 1000L
                    val label = when (snap.kind) {
                        "done" -> ctx.getString(R.string.progress_state_done)
                        "error" -> ctx.getString(R.string.progress_state_error)
                        else -> ""
                    }
                    val action = snap.title.take(60).ifBlank { stateLabel(ctx, snap.kind) }
                    val tail = when {
                        label.isNotBlank() && secs > 0 -> "$label · ${secs}s"
                        label.isNotBlank() -> label
                        secs > 0 -> "${secs}s"
                        else -> ""
                    }
                    StatusOverlay.setAction(listOf(action, tail).filter { it.isNotBlank() }.joinToString(" · "))
                    if ((snap.kind == "done" || snap.kind == "error") && snap.detail.isBlank()) {
                        StatusOverlay.flashCompleteDefault(15_000L)
                    }
                }
                cancelNotification(ctx)
                return
            }
            // 没有悬浮窗权限且没有无障碍 → 回退通知
        }
        if (m == MODE_OVERLAY && !android.provider.Settings.canDrawOverlays(ctx)) {
            // 选的是悬浮窗但没权限：明确告知并引导授权，而不是静默退回普通通知
            publishNotification(
                ctx,
                snap.copy(title = ctx.getString(R.string.progress_overlay_denied_title)),
            )
            return
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
        Log.i(TAG, "progress notification: kind=${snap.kind} session=${snap.session.take(30)} len=${text.length}")
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
        runCatching { StatusOverlay.hideAuto(ctx) }
        last = null
    }

    /** 设置页「预览一次」。 */
    fun preview(ctx: Context) {
        if (mode(ctx) == MODE_OVERLAY && android.provider.Settings.canDrawOverlays(ctx)) {
            // 悬浮窗模式下预览必须真的显示悬浮窗（旧实现只发通知，用户以为浮窗坏了）
            val shown = runCatching { StatusOverlay.show(ctx) }.getOrDefault(false)
            if (shown) {
                runCatching {
                    StatusOverlay.resetHidden()
                    StatusOverlay.setStatus(ctx.getString(R.string.progress_preview_text))
                    StatusOverlay.setAction(ctx.getString(R.string.progress_overlay_ready))
                }
                clearAll(ctx)
                return
            }
        }
        val s = Snapshot("thinking", ctx.getString(R.string.progress_channel), ctx.getString(R.string.progress_preview_text), "preview", 0, System.currentTimeMillis())
        last = s
        publishNotification(ctx, s)
    }

    /** 通知权限是否可用（13+ 需要 POST_NOTIFICATIONS）。 */
    fun notificationsEnabled(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    }.getOrDefault(true)
}
