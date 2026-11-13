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

/** 引擎进度分发到悬浮窗或通知；显示关闭不影响无障碍与 OCR。 */
object ProgressHub {
    private const val TAG = "ProgressHub"
    private const val PREFS = "dsh_progress"
    private const val KEY_MODE = "mode"
    const val MODE_OFF = "off"
    const val MODE_NOTIFY = "notify"
    const val MODE_OVERLAY = "overlay"

    private const val CHANNEL_ID = "dsh_progress"
    private const val NOTIF_ID = 4301
    private val ACTIVE_KINDS = setOf("thinking", "tool", "working")

    private var overlayDeniedHintShown = false

    private val main = Handler(Looper.getMainLooper())

    private data class Snapshot(
        val kind: String,
        val title: String,
        val detail: String,
        val session: String,
        val elapsed: Long,
        val at: Long,

        val think: String = "",
    )

    @Volatile private var last: Snapshot? = null
    @Volatile private var appVisible = false
    @Volatile private var lastPublishAt = 0L
    private var pendingPublish: Runnable? = null

    private val sessionTitles = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var doneClearRunnable: Runnable? = null

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    fun mode(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (!prefs.contains(KEY_MODE)) {
            prefs.edit().putString(KEY_MODE, MODE_OVERLAY).apply()
            return MODE_OVERLAY
        }
        return prefs.getString(KEY_MODE, MODE_OVERLAY) ?: MODE_OVERLAY
    }

    fun setMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()
        when (mode) {
            MODE_OFF -> clearAll(ctx)
            MODE_OVERLAY -> {

                StatusOverlay.resetHidden()
                ensureOverlayVisible(ctx, placeholder = true)
            }
            else -> {
                runCatching { StatusOverlay.hideAuto() }
                maybePublish(ctx, force = true)
            }
        }
    }

    /** 无障碍窗口不要求 SYSTEM_ALERT_WINDOW，是否能建窗由 StatusOverlay 决定。 */
    fun ensureOverlayVisible(ctx: Context, placeholder: Boolean = false) {
        if (mode(ctx) != MODE_OVERLAY) return

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
                StatusOverlay.setIdle()
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

    fun onAppVisibility(ctx: Context, visible: Boolean) {
        appVisible = visible
        maybePublish(ctx, force = true)
    }

    fun onJsProgress(ctx: Context, payload: String) {
        runCatching {
            val o = JSONObject(payload)
            update(
                ctx, o.optString("kind"), o.optString("title"), o.optString("detail"),
                o.optString("session"), o.optLong("elapsed"),
                think = o.optString("think"),
            )
        }.onFailure { Log.w(TAG, "bad progress payload: ${it.message}") }
    }

    fun onAgentProgress(ctx: Context, status: String?, action: String?, complete: Boolean) {
        if (mode(ctx) == MODE_OVERLAY) {
            val notice = listOfNotNull(status, action).filter { it.isNotBlank() }.joinToString(" · ")
            if (notice.isNotBlank()) StatusOverlay.setA11yAction(notice)
            return
        }
        if (complete) {
            update(ctx, "done", "", status ?: ctx.getString(R.string.progress_state_done), "", 0)
            return
        }
        val detail = listOfNotNull(status, action).filter { it.isNotBlank() }.joinToString(" · ")
        if (detail.isBlank()) return
        update(ctx, "working", "", detail, "", 0)
    }

    fun update(
        ctx: Context,
        kind: String,
        title: String,
        detail: String,
        session: String,
        elapsed: Long = 0,
        think: String = "",
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { update(ctx.applicationContext, kind, title, detail, session, elapsed, think) }
            return
        }
        if (app.dsh.mobile.service.EngineService.userExited(ctx)) return
        val k = when (kind) {
            "thinking", "tool", "done", "error", "idle", "working" -> kind
            else -> "working"
        }
        val sess = session.trim().ifBlank { title.trim() }
        val s = Snapshot(
            k, title.trim().take(80), detail.trim().take(16000), sess, elapsed,
            System.currentTimeMillis(),
            think = think.trim().take(4000),
        )
        val prev = last

        if (k in ACTIVE_KINDS && prev != null && (prev.kind !in ACTIVE_KINDS || prev.session != sess)) {
            runCatching { StatusOverlay.clearA11yChain() }
        }

        if (k != "done" && k != "error" && prev != null && prev.kind == s.kind &&
            prev.session == s.session && prev.detail == s.detail && prev.title == s.title && prev.think == s.think && prev.elapsed / 1000L == s.elapsed / 1000L
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

    private fun resolveSessionTitle(ctx: Context, session: String) {
        if (sessionTitles.containsKey(session)) return
        Thread({
            runCatching {
                val info = app.dsh.mobile.engine.SessionReader.list(ctx).firstOrNull { it.id == session }
                if (info != null) {
                    val t = app.dsh.mobile.engine.SessionReader.titleOf(ctx, info)
                    if (t.isNotBlank()) {
                        sessionTitles[session] = t
                        main.post {
                            last?.let { cur ->
                                if (cur.session == session) {
                                    last = cur.copy(title = t)
                                    maybePublish(ctx.applicationContext, force = true)
                                }
                            }
                        }
                    }
                }
            }
        }, "progress-title").apply { isDaemon = true }.start()
    }

    private fun maybePublish(ctx: Context, force: Boolean) {
        val m = mode(ctx)
        if (m == MODE_OFF || app.dsh.mobile.service.EngineService.userExited(ctx)) { clearAll(ctx); return }
        val snap = last
        if (snap == null) return
        val gap = if (m == MODE_OVERLAY) 120L else 600L
        val now = android.os.SystemClock.uptimeMillis()
        val remaining = gap - (now - lastPublishAt)
        if (!force && remaining > 0) {
            // 节流合并中间帧，但保留一次尾部发布，流结束时仍显示最新快照。
            if (pendingPublish == null) {
                pendingPublish = Runnable {
                    pendingPublish = null
                    maybePublish(ctx.applicationContext, force = false)
                }.also { main.postDelayed(it, remaining) }
            }
            return
        }
        pendingPublish?.let { main.removeCallbacks(it) }
        pendingPublish = null
        lastPublishAt = now

        updateWakeLock(ctx, snap)

        if (appVisible && snap.kind != "done" && snap.kind != "error") {
            cancelNotification(ctx)
            if (m != MODE_OVERLAY) {
                runCatching { StatusOverlay.hideAuto() }
                return
            }
        }

        if (m == MODE_OVERLAY) {

            if (!appVisible) runCatching { StatusOverlay.reassertVisible() }

            val shown = runCatching { StatusOverlay.show(ctx) }.getOrDefault(false)
            if (shown) {
                runCatching {

                    val think = snap.think.ifBlank { snap.detail }
                    val running = snap.kind == "thinking" || snap.kind == "tool" || snap.kind == "working"
                    StatusOverlay.setStatus(think.ifBlank { stateLabel(ctx, snap.kind) }, running)
                    val secs = snap.elapsed / 1000L
                    StatusOverlay.setElapsed(secs, running)
                    val label = when (snap.kind) {
                        "done" -> ctx.getString(R.string.progress_state_done)
                        "error" -> ctx.getString(R.string.progress_state_error)
                        else -> ""
                    }
                    val title = snap.title.take(60).ifBlank { stateLabel(ctx, snap.kind) }
                    StatusOverlay.setSession(if (label.isNotBlank()) "$title · $label" else title)
                }
                cancelNotification(ctx)
                return
            }
        }
        if (m == MODE_OVERLAY && !android.provider.Settings.canDrawOverlays(ctx)) {

            if (!overlayDeniedHintShown) {
                overlayDeniedHintShown = true
                publishNotification(
                    ctx,
                    snap.copy(title = ctx.getString(R.string.progress_overlay_denied_title)),
                    ongoing = false,
                )
            }
            return
        }
        if (m == MODE_OVERLAY) {

            cancelNotification(ctx)
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

    private fun publishNotification(ctx: Context, snap: Snapshot, ongoing: Boolean = true) {

        if (mode(ctx) == MODE_OVERLAY && StatusOverlay.isAttached()) return
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

            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.progress_open), open).build())
        val finished = snap.kind == "done" || snap.kind == "error"
        builder.setOngoing(ongoing && !finished)
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
        pendingPublish?.let { main.removeCallbacks(it) }
        pendingPublish = null
        lastPublishAt = 0L
        runCatching { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
        cancelNotification(ctx)
        doneClearRunnable?.let { main.removeCallbacks(it) }
        doneClearRunnable = null
        runCatching { StatusOverlay.hideAuto() }
        last = null
    }

    /** 预览沿用当前模式，不清空刚显示的窗口。 */
    fun preview(ctx: Context) {
        if (mode(ctx) == MODE_OFF) return
        StatusOverlay.resetHidden()
        update(ctx, "thinking", ctx.getString(R.string.progress_channel),
            ctx.getString(R.string.progress_preview_text), "preview")
        maybePublish(ctx, force = true)
    }

    fun notificationsEnabled(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    }.getOrDefault(true)
}
