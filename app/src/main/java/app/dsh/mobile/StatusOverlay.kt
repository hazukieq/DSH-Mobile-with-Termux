package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 36dp 折叠球与 240dp 卡片；正文滚动区固定 92dp，形态只由用户操作切换。 */
object StatusOverlay {

    private const val TAG = "StatusOverlay"

    private const val TAP_SLOP_DP = 10

    private const val COLLAPSED_BALL_DP = 36

    @Volatile private var root: View? = null

    private val liveViews = ArrayList<View>()
    @Volatile private var params: WindowManager.LayoutParams? = null
    @Volatile private var statusText: TextView? = null
    @Volatile private var statusScroll: android.widget.ScrollView? = null
    @Volatile private var statusRow: LinearLayout? = null

    @Volatile private var ballDot: View? = null

    @Volatile private var statusDot: View? = null
    @Volatile private var sessionText: TextView? = null
    @Volatile private var elapsedText: TextView? = null
    @Volatile private var footRow: android.view.View? = null

    @Volatile private var a11yText: TextView? = null
    private val a11yLines = ArrayDeque<String>()

    @Volatile private var startedAt = 0L
    @Volatile private var ticking = false

    @Volatile private var expandLevel = 0
    @Volatile private var hidden = false

    @Volatile private var hiddenByExit = false
    @Volatile private var rememberedLevel = 0

    @Volatile private var dockRight = true

    @Volatile private var replyReady = false

    private var lastBgRes = 0

    private var anchorPending = false

    private var statusTextColor: Int = 0xFFE6EAF2.toInt()

    @Volatile private var windowTypeUsed = 0

    @Volatile private var pendingPos: Pair<Int, Int>? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var wm: WindowManager? = null

    private var lastStatusBody: String = ""
    private var sessionTitle = ""
    private var elapsedRunning = false
    private var elapsedSeconds = 0L

    private const val COLOR_IDLE = 0xFF9AA0A6.toInt()
    private const val COLOR_WORKING = 0xFF7DD3FC.toInt()
    private const val COLOR_DONE = 0xFFFF5A5F.toInt()
    private var dotColor = COLOR_IDLE

    fun currentFrameRect(): android.graphics.Rect? {
        val r = root ?: return null
        if (!r.isAttachedToWindow) return null
        val loc = IntArray(2)
        runCatching { r.getLocationOnScreen(loc) }
        val w = r.width
        val h = r.height
        if (w <= 0 || h <= 0) return null
        return android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
    }

    fun debugState(): String {
        val r = root
        val p = params
        return "root=${r?.let { System.identityHashCode(it) } ?: "null"}" +
            " attached=${r?.isAttachedToWindow}" +
            " live=${liveViews.size}" +
            " level=$expandLevel" +
            " pos=${p?.x},${p?.y} size=${p?.width}x${p?.height}" +
            " flags=0x${p?.flags?.toString(16) ?: "?"}" +
            " touchable=${p?.let { (it.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0 } ?: "?"}" +
            " hideRefs=${avoidance.count}" +
            " type=$windowTypeUsed"
    }

    fun isAvailable(): Boolean = root != null || DshAccessibilityService.instance != null ||
        appCtx?.let { android.provider.Settings.canDrawOverlays(it) } == true

    /** 无障碍窗口优先；服务不可用时由 applicationContext 持有应用悬浮窗。 */
    fun show(svc: AccessibilityService) {
        show(svc as Context)
    }

    fun show(ctx: Context): Boolean {
        var shown = false
        mainSync {
            if (app.dsh.mobile.service.EngineService.userExited(ctx) ||
                ProgressHub.mode(ctx) != ProgressHub.MODE_OVERLAY || hidden) return@mainSync
            val svc = DshAccessibilityService.instance
            val windowCtx = svc ?: ctx.applicationContext
            val type = if (svc != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            if (svc == null && !android.provider.Settings.canDrawOverlays(windowCtx)) return@mainSync
            if (root != null && (root?.parent == null || windowTypeUsed != type)) {
                pendingPos = (params?.x ?: 0) to (params?.y ?: 0)
                hideInternal(false)
            }
            if (root == null) {
                val manager = windowCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                startOverlay(windowCtx.applicationContext, manager, type)
            }
            shown = root != null
        }
        return shown
    }

    private fun startOverlay(ctx: Context, w: WindowManager, windowType: Int) {

        // 入口在主线程检查，避免排队的 show 在关闭模式或退出后重新建窗。
        if (app.dsh.mobile.service.EngineService.userExited(ctx) ||
            ProgressHub.mode(ctx) != ProgressHub.MODE_OVERLAY) return

        main {
            if (root != null || hidden) return@main
            try {
                appCtx = ctx
                wm = w
                windowTypeUsed = windowType

                val view = LayoutInflater.from(ctx).inflate(R.layout.overlay_status, null)

                view.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                val status = view.findViewById<TextView>(R.id.overlayStatus)

                status.text = lastStatusBody.ifBlank { ctx.getString(R.string.overlay_idle) }
                statusTextColor = 0xFFE6EAF2.toInt()

                expandLevel = rememberedLevel
                val isCollapsed = expandLevel == 0
                val initW = if (isCollapsed) dp(ctx, COLLAPSED_BALL_DP) else dp(ctx, 240)
                val initH = if (isCollapsed) dp(ctx, COLLAPSED_BALL_DP) else WindowManager.LayoutParams.WRAP_CONTENT
                val p = WindowManager.LayoutParams(
                    initW,
                    initH,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START

                    val keep = pendingPos
                    pendingPos = null
                    x = keep?.first ?: if (dockRight)
                        (screenRect(ctx).width() - initW - dp(ctx, 8)).coerceAtLeast(dp(ctx, 8))
                        else dp(ctx, 8)
                    y = keep?.second ?: dp(ctx, 120)
                }

                statusText = status
                statusDot = view.findViewById(R.id.overlayDot)
                statusRow = view.findViewById(R.id.overlayRow)
                ballDot = view.findViewById(R.id.overlayBallDot)
                statusScroll = view.findViewById<android.widget.ScrollView>(R.id.overlayScroll)
                sessionText = view.findViewById(R.id.overlaySession)
                elapsedText = view.findViewById(R.id.overlayElapsed)
                footRow = view.findViewById(R.id.overlayFoot)
                a11yText = view.findViewById(R.id.overlayA11y)
                root = view; params = p
                a11yText?.text = a11yLines.joinToString("\n") { "› $it" }
                sessionText?.text = sessionTitle
                updateElapsedText()
                tintDots(dotColor)

                applyExpandLevel(view)
                sweepLiveViews(w)
                liveViews.add(view)
                if (avoidance.hidden) view.visibility = View.INVISIBLE
                w.addView(view, p)

                view.post {
                    if (root !== view) return@post
                    applyExpandLevel(view)
                    anchorToEdge()
                    Log.i(TAG, "overlay shown at ${p.x},${p.y} (${view.width}x${view.height})")
                }
                registerWindowCallbacks(ctx)
                if (elapsedRunning) startTicker()

            } catch (e: Exception) {
                Log.w(TAG, "show failed: ${e.message}")
                hideInternal(false)
            }
        }
    }

    private var displayManager: android.hardware.display.DisplayManager? = null
    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null
    private var componentCallbacks: android.content.ComponentCallbacks? = null

    private fun registerWindowCallbacks(ctx: Context) {
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) { anchorToEdge() }
        }
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
        displayManager = dm
        displayListener = listener
        dm?.registerDisplayListener(listener, mainHandler)
        val callbacks = object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                anchorToEdge()
            }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() {}
        }
        componentCallbacks = callbacks
        ctx.registerComponentCallbacks(callbacks)
    }

    private fun unregisterWindowCallbacks() {
        displayListener?.let { listener -> runCatching { displayManager?.unregisterDisplayListener(listener) } }
        componentCallbacks?.let { callbacks -> runCatching { appCtx?.unregisterComponentCallbacks(callbacks) } }
        displayManager = null
        displayListener = null
        componentCallbacks = null
    }

    /** 正文只滚动；状态点收起；球和卡片空白处支持拖动。 */
    private fun bindHandle(view: View, p: WindowManager.LayoutParams, ctx: Context) {
        val statusTv = view.findViewById<TextView>(R.id.overlayStatus)
        val sDot = view.findViewById<View>(R.id.overlayDot)
        val sScroll = view.findViewById<android.widget.ScrollView>(R.id.overlayScroll)

        listOfNotNull(view, statusTv, sDot, sScroll, footRow, a11yText, elapsedText, sessionText).forEach { v ->
            v.setOnTouchListener(null)
            v.setOnLongClickListener(null)
            v.setOnClickListener(null)
            v.isClickable = false
            v.isLongClickable = false
        }
        val expanded = expandLevel > 0
        if (expanded) {

            val collapse = android.view.View.OnClickListener { collapseToBall() }
            sDot?.setOnClickListener(collapse)
            sDot?.isClickable = true

            val parent = sDot?.parent as? View
            parent?.post {
                if (root !== view || expandLevel == 0) return@post
                val rect = android.graphics.Rect()
                sDot?.getHitRect(rect)
                val pad = dp(ctx, 12)
                rect.top -= pad
                rect.bottom += pad
                rect.left -= pad
                rect.right += pad
                parent.touchDelegate = android.view.TouchDelegate(rect, sDot)
            }

            sScroll?.isClickable = false
            statusTv?.isClickable = false

            attachGesture(
                view, p, ctx,
                onClick = { if (statusRow?.visibility != View.VISIBLE) expandPanel() },
                scrollChild = sScroll,
            )
        } else {

            attachGesture(
                view, p, ctx,
                onClick = { expandPanel() },
            )
        }
    }

    private fun collapseToBall() {
        clearReplyReady()

        if (expandLevel == 0) return
        expandLevel = 0
        rememberedLevel = 0

        applyExpandLevel()
    }

    private fun attachGesture(
        target: View,
        p: WindowManager.LayoutParams,
        ctx: Context,
        onClick: () -> Unit,

        scrollChild: android.widget.ScrollView? = null,
    ) {
        val slop = dp(ctx, TAP_SLOP_DP).toFloat()
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false
        var moved = false
        target.isClickable = true
        target.setOnTouchListener { _, ev ->
            Log.i(TAG, "handle touch: action=${ev.actionMasked} at (${ev.x.toInt()},${ev.y.toInt()})")
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (scrollChild != null && scrollChild.visibility == View.VISIBLE) {
                        val r = android.graphics.Rect()
                        scrollChild.getHitRect(r)
                        val inside = r.contains(ev.x.toInt(), ev.y.toInt())
                        val scrollable = scrollChild.canScrollVertically(1) || scrollChild.canScrollVertically(-1)
                        if (inside && scrollable) {
                            Log.i(TAG, "handle touch: pass through to scroll area at (${ev.x.toInt()},${ev.y.toInt()})")
                            dragging = false
                            return@setOnTouchListener false
                        }
                    }
                    clearReplyReady()
                    downX = ev.rawX; downY = ev.rawY
                    startX = p.x; startY = p.y
                    dragging = true; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        moved = true
                    }
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()

                        val live = if (target.isAttachedToWindow) target else (root ?: target)
                        runCatching { safeUpdateLayout(wm, live, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) return@setOnTouchListener false
                    dragging = false
                    if (moved) {
                        snapToEdge(p, ctx)
                    } else {
                        onClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> false
            }
        }
    }

    private fun screenRect(ctx: Context): android.graphics.Rect {
        val w = wm
        if (android.os.Build.VERSION.SDK_INT >= 30 && w != null) {
            runCatching { return w.currentWindowMetrics.bounds }
        }
        val dm = ctx.resources.displayMetrics
        return android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun clampY(v: View, p: WindowManager.LayoutParams, ctx: Context): Int {
        val screenH = screenRect(ctx).height()
        val vh = when {
            p.height > 0 -> p.height
            v.height > 0 -> v.height
            else -> if (expandLevel == 0) dp(ctx, COLLAPSED_BALL_DP) else dp(ctx, 120)
        }
        return p.y.coerceIn(dp(ctx, 10), (screenH - vh - dp(ctx, 10)).coerceAtLeast(dp(ctx, 10)))
    }

    private fun dockX(v: View, p: WindowManager.LayoutParams, ctx: Context): Int {
        val screenW = screenRect(ctx).width()
        val margin = dp(ctx, 8)
        val vw = when {
            p.width > 0 -> p.width
            v.width > 0 -> v.width
            else -> if (expandLevel == 0) dp(ctx, COLLAPSED_BALL_DP) else dp(ctx, 240)
        }
        return if (dockRight) (screenW - vw - margin).coerceAtLeast(margin) else margin
    }

    private fun anchorToEdge() {
        val v = root ?: return
        val prm = params ?: return
        val ctx = appCtx ?: return
        runCatching {
            val screenW = screenRect(ctx).width()
            val margin = dp(ctx, 8)
            val vw = when {
                prm.width > 0 -> prm.width
                v.width > 0 -> v.width
                else -> if (expandLevel == 0) dp(ctx, COLLAPSED_BALL_DP) else dp(ctx, 240)
            }

            prm.x = dockX(v, prm, ctx).coerceIn(margin, (screenW - vw - margin).coerceAtLeast(margin))
            prm.y = clampY(v, prm, ctx)
            safeUpdateLayout(wm, v, prm)
        }
    }

    private fun snapToEdge(p: WindowManager.LayoutParams, ctx: Context) {
        val view = root ?: return
        val screenW = screenRect(ctx).width()
        val margin = dp(ctx, 8)
        val vw = when {
            p.width > 0 -> p.width
            view.width > 0 -> view.width
            else -> if (expandLevel == 0) dp(ctx, COLLAPSED_BALL_DP) else dp(ctx, 240)
        }
        dockRight = !(p.x + vw / 2 < screenW / 2)
        val target = if (dockRight) screenW - vw - margin else margin
        p.y = clampY(view, p, ctx)
        p.x = target
        runCatching { safeUpdateLayout(wm, view, p) }
    }

    private fun expandPanel() {

        val renderedExpanded = statusRow?.visibility == View.VISIBLE
        if (renderedExpanded && expandLevel == 1) return
        expandLevel = 1
        rememberedLevel = 1

        applyExpandLevel()

    }

    /** 保留正文的横向 weight；这里只调整高度，避免 0dp 宽正文被裁空。 */
    private fun applyExpandLevel(r: View? = root) {
        val ctx = appCtx ?: return
        val view = r ?: return
        if (root !== view) return
        val collapsed = expandLevel == 0

        if (collapsed) view.setPadding(0, 0, 0, 0)
        else view.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))

        val bgRes = if (collapsed) R.drawable.bg_overlay_ball else R.drawable.bg_overlay_panel
        if (bgRes != lastBgRes) {
            lastBgRes = bgRes
            runCatching { view.setBackgroundResource(bgRes) }
        }

        (view as? LinearLayout)?.gravity =
            if (collapsed) Gravity.CENTER else (Gravity.TOP or Gravity.START)

        statusRow?.visibility = if (collapsed) View.GONE else View.VISIBLE
        ballDot?.visibility = if (collapsed) View.VISIBLE else View.GONE
        statusText?.visibility = if (collapsed) View.GONE else View.VISIBLE
        statusScroll?.visibility = if (collapsed) View.GONE else View.VISIBLE

        if (!collapsed) {
            statusText?.setTextColor(statusTextColor)
            if (statusText?.text.isNullOrBlank()) {
                statusText?.text = ctx.getString(R.string.overlay_idle)
            }
        }

        statusScroll?.let { sc ->

            val targetH = if (collapsed) android.view.ViewGroup.LayoutParams.WRAP_CONTENT else dp(ctx, 92)
            val lp = sc.layoutParams
            if (lp != null) {
                var changed = false
                if (lp.height != targetH) { lp.height = targetH; changed = true }

                if (changed) sc.layoutParams = lp
            }
            sc.visibility = if (collapsed) View.GONE else View.VISIBLE
        }

        refreshActionVisibility()
        applyWindowSize(ctx, view, collapsed)

        if (!anchorPending) {
            anchorPending = true
            view.postOnAnimation {
                if (root !== view) return@postOnAnimation
                anchorPending = false
                anchorToEdge()
            }
        }

        bindHandle(view, params ?: return, ctx)
        view.requestLayout()
    }

    private fun applyWindowSize(ctx: Context, view: View, collapsed: Boolean) {
        val side = dp(ctx, COLLAPSED_BALL_DP)
        val w = if (collapsed) side else dp(ctx, 240)
        val h = if (collapsed) side else android.view.ViewGroup.LayoutParams.WRAP_CONTENT

        view.layoutParams?.let { lp ->
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                view.layoutParams = lp
            }
        }
        val p = params ?: return
        var needUpdate = false
        if (p.width != w || p.height != h) {
            p.width = w
            p.height = h
            needUpdate = true
        }
        val notTouch = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        if ((p.flags and notTouch) != 0) {
            p.flags = p.flags and notTouch.inv()
            needUpdate = true
            Log.w(TAG, "cleared stale NOT_TOUCHABLE flag")
        }
        if (needUpdate) {
            runCatching { safeUpdateLayout(wm, view, p) }
        }
    }

    fun hide() { main { hideInternal(userHidden = true) } }

    fun hideAuto() { main { hideInternal(userHidden = false) } }

    fun isAttached(): Boolean = runCatching { root?.isAttachedToWindow == true }.getOrDefault(false)

    private fun sweepLiveViews(w: WindowManager) {
        for (v in liveViews) runCatching { w.removeViewImmediate(v) }
        liveViews.clear()
    }

    fun reassertVisible() {
        main {
            val v = root ?: return@main
            if (avoidance.hidden) return@main
            if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
        }
    }

    fun onAccessibilityLost() {
        main {
            if (windowTypeUsed != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) return@main
            val ctx = appCtx ?: return@main
            pendingPos = (params?.x ?: 0) to (params?.y ?: 0)
            hideInternal(false)
            show(ctx)
        }
    }

    /** 移除窗口时释放所有监听器和 View 引用；内容留在模型字段供重建恢复。 */
    private fun hideInternal(userHidden: Boolean) {
        unregisterWindowCallbacks()
        stopTicker()
        wm?.let { sweepLiveViews(it) }
        root = null; params = null; statusText = null; statusDot = null
        statusRow = null; ballDot = null; statusScroll = null; a11yText = null
        sessionText = null; elapsedText = null; footRow = null
        wm = null; windowTypeUsed = 0; lastBgRes = 0; anchorPending = false
        hidden = userHidden
        Log.i(TAG, if (userHidden) "overlay hidden (sticky)" else "overlay hidden (auto)")
    }

    fun teardown() {
        main {
            hideInternal(true)
            hiddenByExit = true
            avoidTimeouts.values.forEach { mainHandler.removeCallbacks(it) }
            avoidTimeouts.clear()
            avoidance.clear()
            startedAt = 0L
            elapsedRunning = false
            lastStatusBody = ""
            sessionTitle = ""
            a11yLines.clear()
            replyReady = false
            dotColor = COLOR_IDLE
            pendingPos = null
        }
    }

    fun resetHidden() { main { hidden = false } }

    fun resumeAfterExit(): Boolean {
        if (!hiddenByExit) return false
        hiddenByExit = false
        hidden = false
        Log.i(TAG, "overlay resumed after user exited")
        return true
    }

    private val avoidance = OverlayAvoidance()
    private val avoidTimeouts = mutableMapOf<Long, Runnable>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 每次避让有独立凭据；过期回调不能结束另一次避让。所有 View 修改在主线程。 */
    private fun hideNow(reason: String, timeoutMs: Long? = null): Long? {
        var lease: Long? = null
        mainSync {
            lease = avoidance.begin(reason)
            root?.visibility = View.INVISIBLE
            Log.i(TAG, "hide($reason): refs=${avoidance.count}")
            val id = lease ?: return@mainSync
            if (timeoutMs != null) {
                val timeout = Runnable {
                    avoidTimeouts.remove(id)
                    if (avoidance.end(id)) {
                        Log.w(TAG, "$reason avoid expired: lease=$id")
                        restoreVisibility()
                    }
                }
                avoidTimeouts[id] = timeout
                mainHandler.postDelayed(timeout, timeoutMs)
            }
        }
        return lease
    }

    private fun restoreVisibility() {
        root?.visibility = if (avoidance.hidden) View.INVISIBLE else View.VISIBLE
    }

    private fun showNow(lease: Long?) {
        if (lease == null) return
        mainSync {
            avoidTimeouts.remove(lease)?.let { mainHandler.removeCallbacks(it) }
            if (avoidance.end(lease)) restoreVisibility()
        }
    }

    /** 整屏截图的回退路径：临时 INVISIBLE，保留窗口与触摸参数。 */
    fun beginCaptureAvoid(): Long? {
        val lease = hideNow("capture")
        try { if (lease != null) Thread.sleep(120L) }
        catch (e: InterruptedException) {
            showNow(lease)
            Thread.currentThread().interrupt()
            throw e
        }
        return lease
    }

    fun endCaptureAvoid(lease: Long?) { showNow(lease) }

    fun beginOpsAvoidIfHit(gestureBounds: android.graphics.Rect): Long? {
        var lease: Long? = null
        mainSync {
            val frame = currentFrameRect() ?: return@mainSync
            if (android.graphics.Rect.intersects(frame, gestureBounds)) lease = beginOpsAvoid()
        }
        return lease
    }

    fun beginOpsAvoid(): Long? = hideNow("ops", 90_000L)

    fun endOpsAvoid(lease: Long?) { showNow(lease) }

    private fun refreshActionVisibility() {
        val expanded = expandLevel >= 1
        a11yText?.visibility = if (expanded && a11yLines.isNotEmpty()) View.VISIBLE else View.GONE
        footRow?.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    fun setA11yAction(line: String) {
        val t = line.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return
        main {
            if (a11yLines.isNotEmpty() && a11yLines.last() == t) return@main
            a11yLines.addLast(t)
            while (a11yLines.size > 4) a11yLines.removeFirst()
            a11yText?.text = a11yLines.joinToString("\n") { "› " + it }
            refreshActionVisibility()
            root?.requestLayout()
        }
    }

    fun clearA11yChain() {
        main {
            a11yLines.clear()
            a11yText?.text = ""
            a11yText?.visibility = View.GONE
        }
    }

    private fun scrollStatusToBottom() {
        statusScroll?.let { sc -> sc.post { runCatching { sc.fullScroll(View.FOCUS_DOWN) } } }
    }

    /** 上游已经节流；必须接住末帧，不能再按时间丢弃更新。 */
    fun setStatus(text: String, running: Boolean = true) {
        main {
            val body = text.trim().takeLast(600)
            lastStatusBody = body
            if (statusText?.text?.toString() != body) {
                statusText?.text = body
                scrollStatusToBottom()
            }
            if (running) {
                replyReady = false
                tintDots(COLOR_WORKING)
            }
            ensureExpandedContent()
        }
    }

    fun setDaemonAction(text: String) {
        setA11yAction(text)
    }

    fun setAction(text: String) {
        setA11yAction(text)

        appCtx?.let { app.dsh.mobile.engine.LastAction.record(it, text) }
    }

    fun setSession(title: String) {
        val t = title.trim().take(80)
        main {
            sessionTitle = t
            sessionText?.text = t
        }
    }

    fun setElapsed(seconds: Long, running: Boolean) {
        main {
            elapsedSeconds = seconds.coerceAtLeast(0L)
            elapsedRunning = running
            if (seconds <= 0L && !running) {
                startedAt = 0L
                stopTicker()
                elapsedText?.text = ""
                refreshActionVisibility()
                return@main
            }
            startedAt = android.os.SystemClock.elapsedRealtime() - seconds * 1000L
            if (running && root != null) startTicker() else stopTicker()
            updateElapsedText()
        }
    }

    private fun updateElapsedText() {
        val base = startedAt
        if (base <= 0L) {
            elapsedText?.text = ""
            return
        }
        val secs = if (elapsedRunning) ((android.os.SystemClock.elapsedRealtime() - base) / 1000L).coerceAtLeast(0L)
            else elapsedSeconds
        elapsedText?.text = secs.toString() + "s"
        refreshActionVisibility()
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking || startedAt <= 0L) return
            updateElapsedText()
            ensureExpandedContent()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun ensureExpandedContent() {
        if (expandLevel == 0) return
        val r = root ?: return
        if (!r.isAttachedToWindow || r.visibility != View.VISIBLE) return
        val st = statusText ?: return
        val bad = statusRow?.visibility != View.VISIBLE ||
            st.visibility != View.VISIBLE ||
            statusScroll?.visibility != View.VISIBLE ||
            st.text.isNullOrBlank() ||
            st.currentTextColor != statusTextColor
        if (bad) {
            Log.w(TAG, "expanded content out of sync (row=${statusRow?.visibility} st=${st.visibility}" +
                " text='${st.text?.take(10)}'), reapplying")
            applyExpandLevel(r)
        }
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.postDelayed(tickRunnable, 1000L)
    }

    private fun stopTicker() {
        ticking = false
        runCatching { mainHandler.removeCallbacks(tickRunnable) }
    }

    fun setIdle() {
        main {
            lastStatusBody = appCtx?.getString(R.string.overlay_idle) ?: "Idle"
            statusText?.text = lastStatusBody
            replyReady = false
            tintDots(COLOR_IDLE)
            startedAt = 0L
            elapsedRunning = false
            stopTicker()
            elapsedText?.text = ""
            refreshActionVisibility()
            ensureExpandedContent()
        }
    }

    /** 整轮结束只改圆点，保留正文供回看。 */
    fun markReplyReady() {
        main {
            replyReady = true
            if (elapsedRunning && startedAt > 0L) {
                elapsedSeconds = ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000L).coerceAtLeast(0L)
            }
            elapsedRunning = false
            stopTicker()
            updateElapsedText()
            tintDots(COLOR_DONE)
        }
    }

    private fun clearReplyReady() {
        if (!replyReady) return
        replyReady = false
        tintDots(if (elapsedRunning) COLOR_WORKING else COLOR_IDLE)
    }

    private fun main(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    /** 超时后撤销尚未开始的工作，避免延迟的隐藏/建窗在调用方返回后生效。 */
    private fun mainSync(timeoutMs: Long = 800L, block: () -> Unit): Boolean {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
            return true
        }
        val gate = Any()
        var cancelled = false
        var completed = false
        val latch = java.util.concurrent.CountDownLatch(1)
        val action = Runnable {
            synchronized(gate) {
                if (!cancelled) {
                    try { block(); completed = true }
                    catch (e: Exception) { Log.w(TAG, "main action failed", e) }
                }
            }
            latch.countDown()
        }
        if (!mainHandler.post(action)) return false
        try { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        synchronized(gate) {
            cancelled = !completed
            if (cancelled) mainHandler.removeCallbacks(action)
            return completed
        }
    }

    /** View 可能在关闭或服务切换时被摘除，只有仍挂载的窗口才能更新布局。 */

    private fun safeUpdateLayout(
        w: android.view.WindowManager?,
        v: android.view.View?,
        p: android.view.WindowManager.LayoutParams?
    ) {
        if (w == null || v == null || p == null) return
        if (!runCatching { v.isAttachedToWindow }.getOrDefault(false)) {
            Log.i(TAG, "updateViewLayout skipped: overlay detached")
            return
        }

        runCatching { w.updateViewLayout(v, p) }
            .onFailure { Log.w(TAG, "updateViewLayout failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun tintDots(color: Int) {
        dotColor = color
        runCatching { ballDot?.background?.setTint(color) }

        runCatching {
            statusDot?.background?.mutate()?.setTint(color)
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
