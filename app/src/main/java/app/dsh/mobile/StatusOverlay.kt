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

/**
 *
 * ## 为什么用 TYPE_ACCESSIBILITY_OVERLAY
 * 由无障碍服务自身持有，**不需要用户再授权「显示在其他应用上层」**。
 *
 * ## 交互（全部实测可用）
 *  · **拖动**：按住任意位置拖动移动（松手吸附到左右边缘，避免长期挡内容）
 *  · **点击**：展开/收起详情行（点与拖由移动阈值区分：<10dp 视为点击）
 *  · **长按**：隐藏（用户明确不要时尊重）
 *
 * 文档承诺了功能却没做，用户实测发现「不能移动」。本次补上。
 *
 * ## 外观
 * 对齐设置页设计语言：深色表面 #1C232C + 16dp 圆角 + 细描边 + 强调色 #7DD3FC；
 * 左侧状态点用颜色区分状态（强调色=执行中 / 绿=完成 / 灰=空闲）。
 */
object StatusOverlay {

    private const val TAG = "StatusOverlay"

    /** 判定为「点击」而非「拖动」的位移阈值（dp） */
    private const val TAP_SLOP_DP = 10

    /**
     * 折叠态悬浮球直径（dp）。
     * 旧版折叠态只有「22dp 圆点 + 4dp 内边距」= 30dp，实测窗口 95x77 像素
     * 是个**椭圆**且触摸只认中间那个点，所以看起来「像个按钮」（主人实测反馈）。
     * 44dp：够大能点准（远超 40dp 的最小触摸目标），又不至于占屏。
     */
    // 改为 26dp 紧凑圆点：展开后是自然的变大，观感平滑；中间状态点大小不变（DOT_DP）。
    // 只有折叠时才强制这个最小正圆，DOT_DP 也不动）。
    private const val COLLAPSED_BALL_DP = 36

    /**
     * 状态点直径（dp）—— **折叠态与展开态用同一个值**。
     * 主人要求「让两个球在变化大小不变」：展开/收起时这颗点不能忽大忽小。
     * （旧代码折叠 22dp / 展开 10dp，切换时明显跳一下。）
     */
    private const val DOT_DP = 14

    /** 松手后吸附边缘的动画时长 */
    private const val SNAP_MS = 180L

    @Volatile private var root: View? = null
    /**
     *
     * 实测（vivo Pad 2.5.23）：屏幕上出现**两个**悬浮窗（110x110 圆球 + wrap 卡片），
     * dumpsys 里是两条独立窗口。原因是 root 引用被清掉后旧 View 仍 attach 着，
     * 此时再走一次 startOverlay 就又多挂一个——单靠 root 判空挡不住。
     * 因此每次建窗口前先把遗留视图全部 removeView，隐藏时也一并清干净。
     */
    private val liveViews = ArrayList<View>()
    @Volatile private var params: WindowManager.LayoutParams? = null
    @Volatile private var statusText: TextView? = null
    @Volatile private var statusScroll: android.widget.ScrollView? = null
    @Volatile private var statusRow: LinearLayout? = null
    /** 球态专用圆点（折叠态唯一可见内容，居中由布局保证）。 */
    @Volatile private var ballDot: View? = null
    /** 展开态固定图标（只在卡片静止区域，不随 ScrollView 滚动）。 */
    @Volatile private var statusDot: View? = null
    @Volatile private var sessionText: TextView? = null
    @Volatile private var elapsedText: TextView? = null
    @Volatile private var footRow: android.view.View? = null

    @Volatile private var a11yText: TextView? = null
    private val a11yLines = ArrayDeque<String>()
    /** 倒计时基准（工作起始墙钟）；<=0 表示没有在跑的任务。 */
    @Volatile private var startedAt = 0L
    @Volatile private var ticking = false
    /** 最近一次动作链文本（折叠/展开切换时用来恢复显隐，不重新排版）。 */
    /** 展开档位：0=折叠(仅圆点) / 1=状态+麦克风 / 2=再加动作+会话列表 */
    @Volatile private var expandLevel = 0
    @Volatile private var hidden = false
    @Volatile private var rememberedLevel = 0
    /** 上一次应用过的档位：档位变化时窗口尺寸必须重建，否则 WMS 保留旧 frame。 */
    @Volatile private var lastAppliedLevel = -1
    /** 贴边方向：true=右边缘，false=左边缘。展开/折叠后始终贴回这一侧。 */
    @Volatile private var dockRight = true

    /** 红色「回复已给出」状态（见 markReplyReady） */
    @Volatile private var replyReady = false

    /** 上一次实际应用过的折叠态（-1=未应用）：只在真正切换时改背景/内边距，避免闪 */
    private var lastCollapsedApplied = -1

    /** 上一次设为的背景资源（避免每次刷新都 setBackgroundResource，重设 drawable 会闪） */
    private var lastBgRes = 0

    /** 待执行的贴边锚定（合并同一帧内的多次请求） */
    private var anchorPending = false

    /** 状态文字初始颜色（清红时还原） */
    private var statusTextColor: Int = 0xFFE6EAF2.toInt()   // 面板底是深色，字必须浅色
    /** 当前窗口由哪种 type 创建：无障碍窗口随服务销毁，应用悬浮窗不随 Activity/服务走。 */
    @Volatile private var windowTypeUsed = 0
    @Volatile private var appCtx: Context? = null
    @Volatile private var wm: WindowManager? = null

    /**
     * 是否「有活干」（执行中 / 录音 / 完成提示）。
     * 动作行（AI 正在干什么）只在有活干 + 展开态时显示 ——
     * 空闲时不该还挂着上一次的动作文案（主人要的是「现在在干什么」）。
     */
    @Volatile private var working = false
    /** 空闲紧凑态：三块固定占位收起，面板只剩状态行 */
    @Volatile private var idleCompactNow = false
    private var lastStatusBody: String = ""
    private var lastStatusAt: Long = 0L

    /**
     * 是否是我们**自己**因为「AI 开始干活」而自动展开的。
     * 只有这种情况才会在回空闲时自动收成球 —— 用户手动展开的档位绝不擅自改动。
     */
    @Volatile private var autoExpanded = false

    /** 状态点配色（与 drawable 默认色一致，运行时切换） */
    private const val COLOR_IDLE = 0xFF9AA0A6.toInt()      // 中性灰
    private const val COLOR_WORKING = 0xFF7DD3FC.toInt()   // 强调蓝
    private const val COLOR_DONE = 0xFFFF5A5F.toInt()      // 完成/结束：红

    fun isAvailable(): Boolean = DshAccessibilityService.instance != null

    /**
     * 显示悬浮窗。
     * @param svc 无障碍服务实例（提供 overlay 窗口类型）
     */
    /** 无障碍服务路径（历史行为）：用 TYPE_ACCESSIBILITY_OVERLAY，不需要悬浮窗权限。 */
    fun show(svc: AccessibilityService) {
        val ctx = svc.applicationContext
        val w = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startOverlay(ctx, w, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
    }

    /**
     *
     * 优先用 **应用悬浮窗**（TYPE_APPLICATION_OVERLAY + SYSTEM_ALERT_WINDOW 权限），
     * 这样没开无障碍也能显示；没有悬浮窗权限但无障碍在跑时回退到无障碍窗口。
     * @return true 表示窗口已创建（或已存在）。
     */
    fun show(ctx: Context): Boolean {
        if (root != null) return true
        // 实测：applicationContext 建的 TYPE_APPLICATION_OVERLAY 在 App 退到后台后
        // WMS 会把它标记为 hidden（mLastHidden=true / isVisible=false），切到别的 App 就看不见；
        // Activity token 建的窗口反而能跨 App 持续显示。
        val app = ctx
        val appOverlay = runCatching { android.provider.Settings.canDrawOverlays(app) }.getOrDefault(false)
        val svc = DshAccessibilityService.instance
        // 无障碍窗口由服务持有、不依赖 Activity 生命周期，切 App/Activity 重建都不会
        // 被 WMS 置 hidden（用户实测：开了无障碍后思考链路会「断」，就是窗口被隐藏后
        // 内容其实还在推、只是看不见）。没有无障碍时再用活动 Activity 的应用悬浮窗。
        if (svc != null) {
            val w = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            startOverlay(svc.applicationContext, w, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            return root != null
        }
        if (appOverlay) {
            val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            startOverlay(app, w, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            return root != null
        }
        return false
    }

    private fun startOverlay(ctx: Context, w: WindowManager, windowType: Int) {
        main {
            if (root != null || hidden) return@main
            try {
                appCtx = ctx
                wm = w
                windowTypeUsed = windowType

                val view = LayoutInflater.from(ctx).inflate(R.layout.overlay_status, null)
                // 悬浮窗是我们自己的 UI：不能进无障碍树，否则会混进节点树、挡住读屏与点击
                view.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                val status = view.findViewById<TextView>(R.id.overlayStatus)

                status.text = ctx.getString(R.string.overlay_idle)
                statusTextColor = 0xFFE6EAF2.toInt()   // 固定浅色：XML 里的是给浅底准备的深色字

                // 初值就用**折叠球尺寸**（不是 WRAP）：WRAP 时首帧会按 XML 自然尺寸
                // （实测 178x79 的横条）画出来，再被 applyExpandLevel 改成 99x99 →
                // 每次显示都闪一下「横条→圆球」。直接用最终尺寸即可无跳变。
                val initSide = dp(ctx, COLLAPSED_BALL_DP)
                val p = WindowManager.LayoutParams(
                    initSide,
                    initSide,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    // 默认放**右侧**：多数 App 的左侧是导航栏/图标栏，悬浮窗压在上面会挡住操作。
                    // ⚠️ 初值必须保守 —— 见下方 post 里的修正。
                    x = dp(ctx, 8)
                    y = dp(ctx, 120)
                }

                // ⚠️ 顺序有讲究（实测踩坑）：
                //  ① 先赋字段（applyExpandLevel / bindHandle 依赖它们）
                //  ② addView（先挂到 window，布局才有意义）
                //  ③ 在 post 里应用初始档位 —— 此时已完成首次测量，
                //     子 View 才有真实边界可绑手柄（否则绑到 [0,0,0,0] 的控件上）
                statusText = status
                statusDot = view.findViewById(R.id.overlayDot)
                statusRow = view.findViewById(R.id.overlayRow)
                ballDot = view.findViewById(R.id.overlayBallDot)
                ballDot?.background?.setTint(COLOR_IDLE)
                statusScroll = view.findViewById<android.widget.ScrollView>(R.id.overlayScroll)
                sessionText = view.findViewById(R.id.overlaySession)
                elapsedText = view.findViewById(R.id.overlayElapsed)
                footRow = view.findViewById(R.id.overlayFoot)
                a11yText = view.findViewById(R.id.overlayA11y)
                root = view; params = p

                // 先清掉可能遗留的旧视图（见 liveViews 注释），再加新的
                sweepLiveViews(w)
                liveViews.add(view)
                w.addView(view, p)

                view.post {
                    expandLevel = rememberedLevel
                    applyExpandLevel(view)
                }

                // 显示尺寸真变了（旋转 / 视频全屏 / 分屏）就重新贴边。
                // ComponentCallbacks 只在本进程收到 configuration change 时触发，前台是别的
                // App（如视频全屏）时收不到；DisplayListener 由系统直接回调，覆盖该场景。
                runCatching {
                    val dm = ctx.getSystemService(android.content.Context.DISPLAY_SERVICE)
                        as? android.hardware.display.DisplayManager
                    dm?.registerDisplayListener(object : android.hardware.display.DisplayManager.DisplayListener {
                        override fun onDisplayAdded(displayId: Int) {}
                        override fun onDisplayRemoved(displayId: Int) {}
                        override fun onDisplayChanged(displayId: Int) {
                            main { params?.let { prm -> runCatching { snapToEdge(prm, appCtx ?: ctx) } } }
                        }
                    }, null)
                }

                // 旋转（横屏看视频等）后重新贴边：宽高变了而 p.x/p.y 还是旧值。
                runCatching {
                    (ctx.applicationContext as? android.app.Application)?.registerComponentCallbacks(
                        object : android.content.ComponentCallbacks {
                            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                                main { root?.let { v -> params?.let { prm -> runCatching { snapToEdge(prm, appCtx ?: ctx) }; v.requestLayout() } } }
                            }
                            override fun onLowMemory() {}
                        },
                    )
                }

                // ⚠️ 右对齐必须在**测量完成后**做。
                // 旧实现 addView 之后立刻读 view.width —— 此时尚未 measure/layout，
                // 宽度恒为 0，于是 x 被算成 screenW - 0 - margin = 1059（1080 宽的屏），
                // 整个悬浮窗被推到屏幕外，用户完全看不见（模拟器实测日志：
                // "overlay shown at 1059,315 (0x0)"）。用 post 等布局完成再回算。
                view.post {
                    val margin = dp(ctx, 8)
                    val vw = if (view.width > 0) view.width else dp(ctx, 120)
                    p.x = (ctx.resources.displayMetrics.widthPixels - vw - margin)
                        .coerceAtLeast(margin)
                    p.y = clampY(view, p, ctx)
                    runCatching { safeUpdateLayout(w, view, p) }
                    Log.i(TAG, "overlay shown at ${p.x},${p.y} (${view.width}x${view.height})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "show failed: ${e.message}")
            }
        }
    }

    /**
     * 绑定手势目标（每次档位变化后都要重绑）。
     *
     *  · 折叠态：**整颗球**点一下 → 展开
     *  · 展开态：**状态文字**点一下 → 继续展开（详情档），到顶再点循环回球
     *  · 展开态：**面板其余部分**（包括那颗圆点、内边距）点一下 → **收起回球**
     *
     * 为什么补第三条：此前展开态只有那一小块状态文字绑了手势，面板其余处**完全没反应**
     * （根容器的手势在折叠态被摘掉了）—— 主人实测「第二阶段点不回去，必须点到第三阶段
     * 才能点回去」。那颗圆点长得就是折叠态的球，点它 = 收起来，才是符合直觉的预期。
     *
     * 落在子 View 边界内的事件由**子 View 优先**处理，父容器手势只是兜底 ——
     * 所以根容器绑手势不会抢走麦克风（麦克风自己消费自己的），
     * 但绑过手势的 View 会吃掉事件：换档位时必须把旧目标的监听摘干净，
     * 否则会出现「点了没反应」或「麦克风永久失效」。
     */
    private fun bindHandle(view: View, p: WindowManager.LayoutParams, ctx: Context) {
        val statusTv = view.findViewById<TextView>(R.id.overlayStatus)
        // 先摘掉全部旧监听 + **复位 clickable**（档位切换会换目标）
        //
        // 🔴 修主人实测的「球心点不动、点旁边才行」：
        // 状态点在某些档位会当手柄（attachGesture 会给它 isClickable = true）。
        // 只清监听、不复位 isClickable 的话，那颗点会**自己吃掉触摸**
        // （clickable 的 View 在 onTouchEvent 里消费 DOWN，父容器再也收不到），
        // 表现就是「点球心（= 圆点所在位置）毫无反应，点球的其他位置正常」。
        listOfNotNull(view, statusTv).forEach { v ->
            v.setOnTouchListener(null)
            v.setOnLongClickListener(null)
            v.isClickable = false
            v.isLongClickable = false
        }
        val expanded = expandLevel > 0 && statusTv != null && statusTv.visibility == View.VISIBLE
        if (expanded) {
            // 抢去拖窗口，ScrollView 收不到事件，长回复就只能看到前几行（用户实测）。
            // 现在只有「动作行/状态点」可以点收/拖动，正文区域完全交给 ScrollView 滚动。
            // 展开态：整张卡片**任意位置**点击都收起（用户要求「layout 上所有点击都能触发状态变化」）
            val collapse = android.view.View.OnClickListener { collapseToBall() }
            listOfNotNull(view, statusTv, statusScroll, footRow, a11yText, statusDot, elapsedText)
                .forEach { v ->
                    v.setOnClickListener(collapse)
                    v.isClickable = true
                }
            (statusText ?: view).let { d ->
                attachGesture(d, p, ctx, onClick = { collapseToBall() })
            }
            // 是残留交互，展开态点正文只会让人以为有第四段；正文现在纯滚动。
            // 收起：点圆点或动作行；隐藏：长按动作行。
        } else {
            // 折叠态：整颗球都能点/拖；长按 = 隐藏
            attachGesture(
                view, p, ctx,
                onClick = { expandPanel() },
                onLongPress = { hide(DshAccessibilityService.instance) },
            )
        }
    }

    /** 收起回折叠球（展开态点面板空白处的语义） */
    private fun collapseToBall() {
        autoExpanded = false
        if (expandLevel == 0) return
        statusScroll?.post { statusScroll?.fullScroll(View.FOCUS_DOWN) }
        expandLevel = 0
        applyExpandLevel()
    }

    /**
     * 给一个目标 View 装上「拖动 / 点击 / 长按」手势。
     * 位移小于 [TAP_SLOP_DP] 视为点击（调 onClick），超过则拖动，松手吸附到最近边缘。
     */
    private fun attachGesture(
        target: View,
        p: WindowManager.LayoutParams,
        ctx: Context,
        onClick: () -> Unit,
        onLongPress: (() -> Unit)? = null,
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
                        // 用被触摸的 view：root 字段可能指向已被替换/摘除的旧实例，
                        // 那样 safeUpdateLayout 会以「detached」跳过 → 表现为拖不动（实测回归）。
                        val live = if (target.isAttachedToWindow) target else (root ?: target)
                        runCatching { safeUpdateLayout(wm, live, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
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
        // 长按隐藏（View 自带的长按不会与拖动冲突：拖动会先触发 MOVE 取消长按）
        if (onLongPress != null) {
            target.setOnLongClickListener { onLongPress(); true }
        }
    }

    /** 当前屏幕可用区域（旋转后实时取；缓存的 displayMetrics 会导致「不贴边、左右拖不动」）。 */
    private fun screenRect(ctx: Context): android.graphics.Rect {
        val w = wm
        if (android.os.Build.VERSION.SDK_INT >= 30 && w != null) {
            runCatching { return w.currentWindowMetrics.bounds }
        }
        val dm = ctx.resources.displayMetrics
        return android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    /** 垂直位置只做屏幕内约束（默认 y 由调用方给）。 */
    private fun clampY(v: View, p: WindowManager.LayoutParams, ctx: Context): Int {
        val screenH = screenRect(ctx).height()
        val vh = when {
            v.height > 0 -> v.height
            p.height > 0 -> p.height
            else -> dp(ctx, 100)
        }
        return p.y.coerceIn(dp(ctx, 10), (screenH - vh - dp(ctx, 10)).coerceAtLeast(dp(ctx, 10)))
    }

    /** 计算贴边后的 x（按当前窗口宽度与 dockRight 侧）。 */
    private fun dockX(v: View, p: WindowManager.LayoutParams, ctx: Context): Int {
        val screenW = screenRect(ctx).width()
        val margin = dp(ctx, 8)
        val vw = when {
            v.width > 0 -> v.width
            p.width > 0 -> p.width
            else -> dp(ctx, 120)
        }
        return if (dockRight) (screenW - vw - margin).coerceAtLeast(margin) else margin
    }

    /**
     * 按「当前尺寸」重新贴到所在一侧。
     *
     * 用于档位切换（球 ↔ 卡片）后的锚定：窗口 x 是左上角坐标，球变宽成 240dp 卡片时
     * 左上角不动、右边缘却往右长出去 —— 观感就是「一开始说话悬浮窗跳了一下」。
     * 这里在布局完成后再按新宽度重新算 x，保持贴边的一侧不移动。
     */
    private fun anchorToEdge() {
        val v = root ?: return
        val prm = params ?: return
        val ctx = appCtx ?: return
        runCatching {
            prm.x = dockX(v, prm, ctx)
            prm.y = clampY(v, prm, ctx)
            safeUpdateLayout(wm, v, prm)
        }
    }

    /** 松手后吸附到最近的水平边缘（避免长期悬在屏幕中央挡内容） */
    private fun snapToEdge(p: WindowManager.LayoutParams, ctx: Context) {
        val view = root ?: return
        // ⚠️ 不能读 resources.displayMetrics：视频全屏/横屏切换时它还是旧值，
        // 于是 x 按竖屏宽度算，观感就是「横屏了还按竖屏左右贴边」。
        val screenW = screenRect(ctx).width()
        val margin = dp(ctx, 8)
        // 宽度兜底：测量未完成时 view.width 会是 0，直接用会把窗口推到屏外
        val vw = if (view.width > 0) view.width else dp(ctx, 120)
        dockRight = !(p.x + vw / 2 < screenW / 2)
        val target = if (dockRight) screenW - vw - margin else margin
        p.y = clampY(view, p, ctx)
        p.x = target
        runCatching { safeUpdateLayout(wm, view, p) }
    }

    /**
     *
     *  0 折叠：只有圆形状态点（最省空间，半透明）
     *  1 展开：状态点 + 状态文字 + 麦克风按钮
     *  2 详情：在展开基础上 + 最近动作 + 活跃会话列表
     */
    private fun expandPanel() {
        autoExpanded = false   // 用户自己动的档位 → 不再自动收回
        expandLevel = 1
        rememberedLevel = 1
        applyExpandLevel()
    }

    /** 按当前档位应用可见性（所有控件都在同一窗口里，只切 visibility） */
    /**
     * 按当前档位应用可见性（所有控件都在同一窗口里，只切 visibility）。
     * @param r 根 View（显式传入：本方法可能在 root 字段赋值前就被调用）
     */
    /** 按档位应用布局。窗口尺寸只由 [applyWindowSize] 决定，其它任何地方都不写宽高。 */
    private fun applyExpandLevel(r: View? = root) {
        val ctx = appCtx ?: return
        val view = r ?: return
        val collapsed = expandLevel == 0
        // 空闲态必须「自适应」：宽度贴着 dot +「空闲」走（WRAP），不占 240dp 空白；
        // 但不能缩成 34dp 细条（那会看起来像自己折叠了），高度仍按内容走。
        val idleText2 = ctx.getString(R.string.overlay_idle)
        val idleCompact = !collapsed && statusText?.text?.toString() == idleText2
        idleCompactNow = idleCompact

        // 内边距：球态必须为 0（否则球心会被 padding 顶偏）；卡片态 10/8/12/8。
        if (collapsed) view.setPadding(0, 0, 0, 0)
        else view.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
        val bgRes = if (collapsed) R.drawable.bg_overlay_ball else R.drawable.bg_overlay_panel
        if (bgRes != lastBgRes) {
            lastBgRes = bgRes
            runCatching { view.setBackgroundResource(bgRes) }
        }

        // 球态：窗口本身就是正方形，圆点靠 gravity 居中——不用 margin 像素补偿。
        (view as? LinearLayout)?.gravity =
            if (collapsed) Gravity.CENTER else (Gravity.TOP or Gravity.START)
        statusRow?.visibility = if (collapsed) View.GONE else View.VISIBLE
        statusRow?.let { row ->
            val lp = row.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                val h = if (collapsed) LinearLayout.LayoutParams.MATCH_PARENT
                else LinearLayout.LayoutParams.WRAP_CONTENT
                val g = if (collapsed) Gravity.CENTER else (Gravity.TOP or Gravity.START)
                if (lp.height != h || lp.gravity != g) {
                    lp.height = h
                    lp.gravity = g
                    row.layoutParams = lp
                }
            }
        }

        // 球态只显示独立圆点（布局居中）；卡片态反过来。
        ballDot?.visibility = if (collapsed) View.VISIBLE else View.GONE

        // 内容可见性：卡片空闲态只留状态行（滚动区/动作行/页脚都收起）。
        statusText?.visibility = if (collapsed) View.GONE else View.VISIBLE
        // 空闲小块里必须能看到「空闲」二字：文字为空时补上（只补空值，不覆盖任何真实内容）
        if (!collapsed && statusText?.text.isNullOrBlank()) {
            statusText?.text = ctx.getString(R.string.overlay_idle)
            statusText?.setTextColor(statusTextColor)
        }
        statusScroll?.let { sc ->
            // 折叠态不参与测量；空闲 = 贴合文字；工作态 = 固定 92dp（文字在框内滚动，窗口不伸缩）。
            val targetH = when {
                collapsed -> android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                idleCompactNow -> android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                else -> dp(ctx, 92)
            }
            val lp = sc.layoutParams
            if (lp != null) {
                var changed = false
                if (lp.height != targetH) { lp.height = targetH; changed = true }
                // 只有「有内容（非空闲）」的展开态才需要固定 92dp 滚动区；
                // 空闲态保持贴合内容（不受这次修复影响）。
                if (!idleCompactNow && lp is LinearLayout.LayoutParams && lp.weight != 0f) {
                    lp.weight = 0f   // XML 的 layout_weight=1 会把滚动区撑成"占满剩余高度"，必须清零
                    changed = true
                }
                if (changed) sc.layoutParams = lp
            }
            // 状态文字永远可见：空闲时也要能看到「空闲」二字（之前把它 GONE 掉 → 面板空白的根源）
            sc.visibility = if (collapsed) View.GONE else View.VISIBLE
        }
        // 状态行宽高一律 wrap_content（尺寸由 applyWindowSize 的窗口大小决定，行不再撑满）
        (statusRow?.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { rlp ->
            if (rlp.width != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
                rlp.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                statusRow?.layoutParams = rlp
            }
        }
        a11yText?.visibility = if (!collapsed && !idleCompactNow) View.VISIBLE else View.GONE
        footRow?.visibility = if (collapsed || idleCompactNow) View.GONE else View.VISIBLE
        refreshActionVisibility()

        applyWindowSize(ctx, view, collapsed)

        if (!anchorPending) {
            anchorPending = true
            view.postOnAnimation {
                anchorPending = false
                anchorToEdge()
            }
        }
        bindHandle(view, params ?: return, ctx)
        view.requestLayout()
        view.post {
            runCatching { bindHandle(view, params ?: return@post, ctx) }
            runCatching {
                val xy = IntArray(2); view.getLocationOnScreen(xy)
                Log.i(TAG, "[geo] level=$expandLevel ${view.width}x${view.height} at ${xy[0]},${xy[1]} idle=$idleCompactNow bg=$lastBgRes text='${statusText?.text}' vis=${statusText?.visibility} tw=${statusText?.width} sw=${statusScroll?.width} rw=${statusRow?.width}")
            }
        }
    }

    /**
     * 窗口尺寸的唯一真源。**三种形态互不相同，禁止为了「统一」把它们合并**（详见 docs/OVERLAY.md §2.0）：
     *
     * | 形态 | 宽 | 高 |
     * | --- | --- | --- |
     * | 折叠球 `collapsed` | `COLLAPSED_BALL_DP`（正方形） | 同宽 |
     * | 展开·空闲 `idleCompactNow` | `WRAP_CONTENT`（贴着 图标+「空闲」，自适应） | `WRAP_CONTENT` |
     * | 展开·工作 | `dp(240)` 固定 | `WRAP_CONTENT`（内部正文区固定 92dp，窗口高度稳定） |
     *
     * 历史踩坑（别重犯）：
     *  - 把空闲也写死 240dp → 右边一大片空白，用户报「空闲又不自适应了」；
     *  - 把空闲压成 34dp 细条 → 看起来像「展开后自己折叠」；
     *  - 用 removeView/addView 换尺寸 → 每次切换/截图都闪一下。
     * 档位切换只走 updateViewLayout + requestLayout；可见性切换只改 visibility。
     */
    private fun applyWindowSize(ctx: Context, view: View, collapsed: Boolean) {
        val side = dp(ctx, COLLAPSED_BALL_DP)
        val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        // 折叠=正方球；展开空闲=贴合内容（只有 dot + 文案）；展开运行=固定 240dp 宽
        val w = when {
            collapsed -> side
            !idleCompactNow -> dp(ctx, 240)   // 工作态：固定 240dp（文字在框内滚动）
            else -> WRAP                      // 空闲：自适应内容宽度
        }
        val h = when {
            collapsed -> side
            else -> WRAP              // 高度由内容决定（空闲一行、运行多行，逐渐增高而非瞬变）
        }
        view.layoutParams?.let { lp ->
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                view.layoutParams = lp
            }
        }
        val p = params ?: return
        p.width = w
        p.height = h
        // 防御：opsAvoid 不在生效期却带着 NOT_TOUCHABLE → 球永远点不动/拖不动。
        // （历史现象：点击无任何 handle touch 日志，窗口成了「只能看」的摆设。）
        val notTouch = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (!opsAvoid && (p.flags and notTouch) != 0) {
            p.flags = p.flags and notTouch.inv()
            Log.w(TAG, "cleared stale NOT_TOUCHABLE flag")
        }
        Log.i(TAG, "[win] level=$expandLevel collapsed=$collapsed ${w}x$h flags=0x${Integer.toHexString(p.flags)} touchable=${(p.flags and notTouch) == 0}")
        runCatching { safeUpdateLayout(wm, view, p) }
        // 档位变化只改 LayoutParams + requestLayout，**不重挂窗口**：
        // removeView/addView 会重建 surface，观感就是切换档位时闪一下（用户反馈「一闪一闪」）。
        if (lastAppliedLevel != expandLevel) {
            lastAppliedLevel = expandLevel
            view.requestLayout()
            view.post {
                runCatching {
                    p.x = dockX(view, p, ctx)
                    safeUpdateLayout(wm, view, p)
                }
            }
        }
    }

    fun hide(svc: AccessibilityService?) {
        hideInternal(userHidden = true)
    }

    fun hide(ctx: Context?) {
        hideInternal(userHidden = true)
    }

    /**
     * 否则下次后台展示会被 `if (root != null || hidden) return` 永久拦住。
     */
    fun hideAuto(ctx: Context?) {
        hideInternal(userHidden = false)
    }

    /**
     * 应用悬浮窗（SYSTEM_ALERT_WINDOW）必须保留，否则切断无障碍/ROM 重启服务会把浮窗弄没。
     */
    /**
     *（dumpsys 里 mLastHidden=true / isVisible=false），此时窗口对象还在但看不见。
     * 重新挂一次窗口即可恢复显示（参数原样复用）。
     */
    /** 窗口是否真的还挂在 WindowManager 上（Activity 销毁后 root 字段可能残留）。 */
    fun isAttached(): Boolean = runCatching { root?.isAttachedToWindow == true }.getOrDefault(false)

    /** 把还挂着的旧视图全部摘掉（幂等；主线程调用）。 */
    private fun sweepLiveViews(w: WindowManager) {
        if (liveViews.isEmpty()) return
        for (v in liveViews) runCatching { w.removeView(v) }
        liveViews.clear()
    }

    /** 丢弃已失效的 root 引用，让下一次 show() 重建窗口。 */
    fun dropStaleRoot() {
        main {
            val v = root ?: return@main
            runCatching { wm?.removeView(v) }
            root = null
            params = null
            lastAppliedLevel = -1
        }
    }

    fun reassertVisible() {
        main {
            val v = root ?: return@main
            val p = params ?: return@main
            runCatching {
                safeUpdateLayout(wm, v, p)
                v.visibility = View.VISIBLE
                v.requestLayout()
            }
        }
    }

    fun onAccessibilityLost() {
        // 都先收起——否则服务没了、app 窗口还挂在进程里（用户反馈「退出后悬浮窗还在」）。
        // 服务重新连接时 DshAccessibilityService.onServiceConnected 会按开关重新 show。
        hide(null)
    }

    private fun hideInternal(userHidden: Boolean) {
        main {
            val r = root ?: return@main
            runCatching {
                wm?.removeView(r)
            }
            wm?.let { sweepLiveViews(it) }
            root = null; params = null; statusText = null; statusDot = null
            statusScroll = null; a11yText = null
            hidden = userHidden
            Log.i(TAG, if (userHidden) "overlay hidden by user" else "overlay hidden (auto)")
        }
    }

    /**
     * 退出引擎后悬浮窗仍在后台——球还挂着、`idleReset` 定时器还在排着
     * → 进程持续活跃耗电。这里一次清干净：取消回空闲、移除窗口视图、状态复位。
     */
    fun teardown(svc: AccessibilityService?) {
        main {
            cancelIdleReset()
            working = false
            expandLevel = 0
            autoExpanded = false
            hide(svc)
            Log.i(TAG, "overlay torn down (engine exited)")
        }
    }

    /** 用户隐藏后重新允许显示（引擎重启等场景调用） */
    fun resetHidden() { hidden = false }

    // 截图/读屏时必须让悬浮窗**真正消失**（不是 alpha=0）：
    //   - alpha=0 的窗口仍然参与命中测试与无障碍树，会挡住自动点击、把「空闲/执行中」混进节点树；
    //   - 所以这里是 removeView / addView（同一份视图对象，不重建、不重新 inflate，状态不丢）。
    @Volatile private var hiddenForCapture = false

    /** 截图前把悬浮窗从窗口移除（真正不可见、不可点、不进无障碍树） */
    fun beginCaptureAvoid(svc: AccessibilityService?) {
        main {
            if (hiddenForCapture) return@main
            val r = root ?: return@main
            val w = wm ?: return@main
            hiddenForCapture = true
            // ⚠️ 不再 removeViewImmediate：每次截图/读屏都摘挂窗口 → 画面必闪一下，
            // 密集自动化时观感就是「一闪一闪」。改成 visibility=GONE：不参与绘制、
            // 不参与命中测试、不进无障碍树，但窗口本身不动 → 视觉连贯。
            r.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            r.visibility = android.view.View.GONE
            runCatching { w.updateViewLayout(r, params ?: return@main) }
        }
        // 等一帧，确保 GONE 已生效再截
        Thread.sleep(35L)
    }

    /** 截图完成后原样挂回窗口（沿用原 params 与档位状态） */
    fun endCaptureAvoid(svc: AccessibilityService?) {
        main {
            if (!hiddenForCapture) return@main
            hiddenForCapture = false
            val r = root ?: return@main
            val w = wm ?: return@main
            val p = params ?: return@main
            runCatching {
                r.visibility = android.view.View.VISIBLE
                if (!r.isAttachedToWindow) {
                    r.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    w.addView(r, p)
                }
                // 档位状态原样保留（不重置 expandLevel）——用户展开后就该一直展开
                applyExpandLevel(r)
            }
        }
    }

    /**
     * 自动化操作期间让悬浮窗**不吃点击**。
     *
     * 为什么需要：窗口是 FLAG_NOT_FOCUSABLE|NOT_TOUCH_MODAL，但**仍然可触摸**——
     * 球/卡片覆盖的那一块，注入的 tap/swipe 会先落到我们自己的窗口上，被当成
     * 「拖动悬浮窗」，于是自动点击在那一块区域永远点不中目标。
     * 操作期间加 FLAG_NOT_TOUCHABLE（可见、但不参与命中测试），操作结束立刻去掉。
     */
    @Volatile private var opsAvoid = false

    /** opsAvoid 的看门狗：异常路径没配对调用 endOpsAvoid 时，90s 后自动恢复可点。 */
    private val opsAvoidWatchdog = Runnable {
        if (opsAvoid) {
            Log.w(TAG, "opsAvoid watchdog fired (endOpsAvoid was missed) — restoring touch")
            opsAvoid = false
            applyOpsAvoid()
        }
    }

    fun beginOpsAvoid() {
        if (opsAvoid) return
        opsAvoid = true
        applyOpsAvoid()
        mainHandler.removeCallbacks(opsAvoidWatchdog)
        mainHandler.postDelayed(opsAvoidWatchdog, 90_000L)
        Log.i(TAG, "opsAvoid on")
    }

    fun endOpsAvoid() {
        mainHandler.removeCallbacks(opsAvoidWatchdog)
        if (!opsAvoid) return
        opsAvoid = false
        applyOpsAvoid()
        Log.i(TAG, "opsAvoid off")
    }

    private fun applyOpsAvoid() {
        main {
            val p = params ?: return@main
            val r = root ?: return@main
            val w = wm ?: return@main
            val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val next = if (opsAvoid) (p.flags or notTouchable) else (p.flags and notTouchable.inv())
            if (next == p.flags) return@main
            p.flags = next
            runCatching { if (r.isAttachedToWindow) safeUpdateLayout(w, r, p) }
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 待执行的「回空闲」任务（必须可取消，见 scheduleIdle 注释） */
    @Volatile private var idleReset: Runnable? = null

    @Volatile private var collapsePending: Runnable? = null

    /**
     * 取消待执行的「回空闲」。
     *
     * 🔴 这里修的是主人实测反馈的回归：「AI 正在做无障碍操作，悬浮窗却一直显示空闲」。
     * 根因：`flashComplete` 等短暂提示用 `postDelayed` 排了一个「N 秒后 setIdle()」，
     * **从不取消**。流程实际是「上一轮结束 → flashComplete 排 6s 定时 → 用户马上又让 AI 干活
     * → setStatus(「执行中…」) 写入新状态 → 那个过期定时器到点触发 → 又把状态覆盖成「空闲」」。
     * 表现就是「AI 明明在动，悬浮窗永远空闲」。
     */
    private fun cancelIdleReset() {
        idleReset?.let { runCatching { mainHandler.removeCallbacks(it) } }
        idleReset = null
        // 否则刚排队的收起会在新任务开始后把悬浮窗缩掉。
        cancelCollapse()
    }

    /**
     * 动作行显隐的**唯一判定点**：
     * 展开态（档位 ≥1）且有活干且有文案才显示。
     *
     * 旧代码在 flashComplete 等状态更新里直接 `visibility = VISIBLE`，
     * 绕过了档位规则 —— 折叠态下球会被那行字撑成椭圆（实测）。
     */
    private fun refreshActionVisibility() {
        val expanded = expandLevel >= 1
        // 第三段：会话标题 + 倒计时
        // 无障碍/打开应用动作链（第④段）：展开态且有内容才显示
        a11yText?.visibility = if (expanded && !idleCompactNow && a11yLines.isNotEmpty()) View.VISIBLE else View.GONE
        val foot = footRow ?: return
        val hasFoot = sessionText?.text?.isNotEmpty() == true || elapsedText?.text?.isNotEmpty() == true
        // 固定占位：展开态始终占这 16dp（无内容时留空），避免出现/消失时窗口高度跳变
        foot.visibility = if (expanded && !idleCompactNow) View.VISIBLE else View.GONE
    }

    /** 排一个「holdMs 后回空闲」，可被后续任何新状态取消 */
    private fun scheduleIdle(holdMs: Long) {
        cancelIdleReset()
        val r = Runnable { idleReset = null; setIdle() }
        idleReset = r
        mainHandler.postDelayed(r, holdMs)
    }

    /** 更新状态行（如「执行中」），并把状态点染成强调色 */
    private fun fitText(text: String, max: Int = 90): String {
        val one = text.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= max) one else one.take(max - 1).trimEnd() + "…"
    }

    /**
     * 这里按内容实测高度夹到 176dp（不够就不动），超出则固定高度并内部滚动。
     */
    private fun capScrollHeight() {
        // 按内容夹高会让每条状态都改一次窗口高度，观感就是持续闪烁。
        if (true) return
        val sc = statusScroll ?: return
        val ctx = appCtx ?: return
        val maxPx = dp(ctx, 132)
        val child = sc.getChildAt(0) ?: return
        // 宽度未知时按卡片正文最大宽度（216dp - 内边距）量一遍，保证拿到真实内容高度。
        var desired = child.measuredHeight
        if (desired <= 0 || child.width == 0) {
            val w = (sc.width - sc.paddingLeft - sc.paddingRight)
                .takeIf { it > 0 } ?: dp(ctx, 200)
            child.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            desired = child.measuredHeight
        }
        if (desired <= 0) return
        val target = if (desired > maxPx) maxPx else desired
        val lp = sc.layoutParams ?: return
        if (lp.height != target) {
            lp.height = target
            sc.layoutParams = lp
            sc.requestLayout()
            root?.requestLayout()
            root?.let { r -> params?.let { safeUpdateLayout(wm, r, it) } }
        }
    }

    /**
     *
     * 与 AI 的工具链分开显示：这里是 App 真的对外做的事——读屏点击/滑动/按键、
     * 启动或跳转别的 App（微信/支付宝/浏览器…）。连续相同行不重复，只留最近 4 条。
     */
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
            root?.let { rv -> params?.let { pr -> safeUpdateLayout(wm, rv, pr) } }
        }
    }

    /** 新一轮开始或引擎重启时清空无障碍动作链 */
    fun clearA11yChain() {
        main {
            a11yLines.clear()
            a11yText?.text = ""
            a11yText?.visibility = View.GONE
        }
    }

    /** 状态文字更新后把滚动区滚到底（最新一行可见）。 */
    private fun scrollStatusToBottom() {
        statusScroll?.let { sc -> sc.post { runCatching { sc.fullScroll(View.FOCUS_DOWN) } } }
    }

    fun setStatus(text: String) {
        if (working) replyReady = false   // 新活动开始 → 清红（红只代表「整轮结束」）
        main {
            cancelIdleReset()   // 新状态来了 → 作废"回空闲"定时器（否则会把这里覆盖掉）
            val wasWorking = working
            working = true
            if (!wasWorking) runCatching { applyExpandLevel() }   // 离开空闲态 → 恢复固定占位
            // 用户要求「点击后再展开」，所以这里**不**自动升档（折叠球由用户点开）。
            // 原来每次都把全文塞进 TextView 再 fullScroll → 每 250ms 一次全量重排。
            // 现在：① 只留末尾 600 字（显示区 92dp≈4 行）；② 文本没变不动；
            // ③ 400ms 内最多更新一次（丢中间帧，末尾状态自然会再到）。
            val full = text.trim()
            val body = if (full.length > 600) full.takeLast(600) else full
            val nowMs = android.os.SystemClock.uptimeMillis()
            if (body == lastStatusBody || nowMs - lastStatusAt < 400L) {
                Log.i(TAG, "setStatus skip len=${full.length}")
                return@main
            }
            lastStatusBody = body
            lastStatusAt = nowMs
            statusText?.text = body
            runCatching { applyExpandLevel() }   // 文案变了要重排：空闲→工作会切到固定 92dp 滚动区
            scrollStatusToBottom()
            Log.i(TAG, "setStatus len=${body.length} full=${full.length}")
            clearReplyReady()
            tintDots(COLOR_WORKING)
            statusScroll?.post {
                capScrollHeight()
                statusScroll?.fullScroll(View.FOCUS_DOWN)
            }
            refreshActionVisibility()
        }
    }

    /**
     * 更新最近动作行（如 `tap-text "WLAN"`）。
     * 之前只有档位 2 才显示，等于要点两下才看得到最近动作。
     */
    /**
     * 由 LocalLoopExecutor 每步调用：状态行显示「执行中」，动作行显示当前那一步在做什么。
     */
    fun setDaemonAction(text: String) {
        setA11yAction(text)
    }

    fun setAction(text: String) {
        setA11yAction(text)
        // 进程重启后仍要能看到，所以每次动作都记一次时间戳
        appCtx?.let { app.dsh.mobile.engine.LastAction.record(it, text) }
    }

    fun setSession(title: String) {
        val t = title.trim().take(80)
        main {
            sessionText?.text = t
            refreshActionVisibility()
        }
    }

    /**
     *
     * 进度事件本身有节流/心跳（最长 2.5s 一次），只靠它刷新会看起来"卡住"；
     * 这里按「工作起始墙钟」在本地每秒 tick，事件只负责校准基准。
     *
     * @param seconds 已运行秒数（来自插件 elapsed）
     * @param running true=还在跑（继续 tick）；false=停止（完成/错误）
     */
    fun setElapsed(seconds: Long, running: Boolean) {
        main {
            if (seconds <= 0L && !running) {
                startedAt = 0L
                stopTicker()
                elapsedText?.text = ""
                refreshActionVisibility()
                return@main
            }
            startedAt = System.currentTimeMillis() - seconds * 1000L
            if (running) startTicker() else stopTicker()
            updateElapsedText()
        }
    }

    private fun updateElapsedText() {
        val base = startedAt
        if (base <= 0L) {
            elapsedText?.text = ""
            return
        }
        val secs = ((System.currentTimeMillis() - base) / 1000L).coerceAtLeast(0L)
        elapsedText?.text = secs.toString() + "s"
        refreshActionVisibility()
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking || startedAt <= 0L) return
            updateElapsedText()
            mainHandler.postDelayed(this, 1000L)
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

    /**
     * 回到空闲态（灰色点 + 空闲文案）。
     *
     * 收起 = **悬浮窗尺寸变化**（展开卡片 → 圆形球：文字/动作行隐藏、padding、
     * minWidth/Height、背景全变），而 View 属性动画管不到窗口尺寸，直接改会在
     * 1~2 帧里看到整体跳变。
     *
     * 处理：把**所有**会改变尺寸的改动（文案、动作行、档位）都挪到 alpha=0 期间，
     * 再淡入。并延迟 60ms 避开语音面板移除那一帧（两个 overlay 窗口同时重排时
     * 闪得更明显）。延迟期间若已有新任务/新状态，[cancelCollapse] 会取消这次收起。
     */
    fun setIdle() {
        main {
            val r = root
            // 下一条状态又展开 → 一轮对话里反复「球↔面板」，观感就是持续闪烁。
            // 收球只由用户点击触发（expandPanel 循环档位）。
            val needFade = false

            /** 真正的空闲布局（调用时必须处于 alpha=0 或本来就不需要淡出）。 */
            fun applyIdleNow() {
                statusText?.text = appCtx?.getString(R.string.overlay_idle) ?: "Idle"
                tintDots(COLOR_IDLE)
                working = false
                startedAt = 0L
                stopTicker()
                elapsedText?.text = ""
                refreshActionVisibility()
                autoExpanded = false
            }

            if (!needFade) {
                applyIdleNow()
                return@main
            }

            // 立刻标记，避免重复排队；真正的布局改动在淡出后执行
            autoExpanded = false
            cancelCollapse()
            val job = Runnable {
                collapsePending = null
                val view = root ?: return@Runnable
                view.animate().cancel()
                view.animate().alpha(0f).setDuration(80L).withEndAction {
                    // 此刻不可见 → 安全地改尺寸
                    statusText?.text = appCtx?.getString(R.string.overlay_idle) ?: "Idle"
                    tintDots(COLOR_IDLE)
                    working = false
                    startedAt = 0L
                    stopTicker()
                    elapsedText?.text = ""
                    refreshActionVisibility()
                    expandLevel = 0
                    applyExpandLevel()
                    view.animate().alpha(1f).setDuration(140L).start()
                }.start()
            }
            collapsePending = job
            mainHandler.postDelayed(job, 60L)
        }
    }

    /** 取消待执行的「平滑收起」（任何新状态都应调用） */
    private fun cancelCollapse() {
        collapsePending?.let { runCatching { mainHandler.removeCallbacks(it) } }
        collapsePending = null
    }

    /**
     * 对话完成提示：绿色高亮 + 短暂显示，到点自动回空闲。
     */
    /**
     * 会话结束 / 被停止：整枚悬浮窗变红并保持，直到用户点它（提示「回复已经给出」）。
     * 旧行为是 15s 自动回灰，用户容易错过。
     */
    /** 一轮结束/停止：圆点变红提示「可以看了」。**不改写状态文字**——文案一旦被覆盖，
     *  正在滚动的思考链就没法回看（用户明确要求只在圆点上表达）。 */
    @Suppress("UNUSED_PARAMETER")
    fun markReplyReady(summary: String? = null) {
        main {
            replyReady = true
            working = false
            tintDots(COLOR_DONE)
        }
    }

    /** 清红（用户点/拖悬浮窗、或新状态到来时） */
    private fun clearReplyReady() {
        if (!replyReady) return
        replyReady = false
        runCatching { statusText?.setTextColor(statusTextColor) }
    }

    /** 完成闪一下：同样**只改圆点颜色**，不覆盖状态文字（否则滚动内容被冲掉、无法回看）。 */
    @Suppress("UNUSED_PARAMETER")
    fun flashComplete(summary: String, holdMs: Long = 15_000L) {
        main {
            working = false
            replyReady = true
            tintDots(COLOR_DONE)
            // ⚠️ 必须可取消：这个 6 秒定时器若不被作废，会在「用户马上又让 AI 干活」时
            // 把新写入的「执行中」覆盖成「空闲」——主人实测反馈的正是这个现象。
        }
    }

    /** 默认完成文案（供无 Context 的调用方，如桥接层 handler） */
    fun flashCompleteDefault(holdMs: Long = 15_000L) {
        flashComplete(appCtx?.getString(R.string.overlay_turn_done) ?: "Done", holdMs)
    }

    /** 「执行中 · 点击」 文案（供无 Context 的调用方） */
    fun labelWorkingTap(): String {
        val c = appCtx ?: return "Working"
        return c.getString(R.string.overlay_working, c.getString(R.string.a11y_action_tap))
    }

    /** 通用版状态文案：动作类型由调用方给（点击/滑动/输入/导航/读屏…） */
    fun labelWorking(action: String): String {
        val c = appCtx ?: return "Working"
        return c.getString(R.string.overlay_working, action)
    }

    private fun main(block: () -> Unit) {
        runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post(block) }
    }


    /**
     * 窗口布局的安全写法：视图可能被临时摘除（截图/读屏期间 beginCaptureAvoid 会
     * removeViewImmediate），此时 updateViewLayout 会抛
     * IllegalArgumentException: View ... not attached to window manager，未捕获就让进程崩。
     * 所以统一走这里：没挂上就跳过（等 endCaptureAvoid 重新 addView 后自然生效）。
     */
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
        // ⚠️ 这里必须是真正的 WindowManager.updateViewLayout —— 早前批量替换把内部调用也
        // 改成了 safeUpdateLayout，导致无限递归（StackOverflowError 被 runCatching 吞掉，
        // 外在表现就是「拖动完全没反应」）。
        runCatching { w.updateViewLayout(v, p) }
            .onFailure { Log.w(TAG, "updateViewLayout failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** 状态色同时作用到两枚圆点（球态那枚 + 卡片态那枚）。 */
    private fun tintDots(color: Int) {
        runCatching { ballDot?.background?.setTint(color) }
        // 展开态图标是 TextView 的 drawableStart：给这份 drawable 也上色，否则圆点永远是默认灰
        runCatching {
            val tv = statusText ?: return@runCatching
            statusDot?.background?.mutate()?.setTint(color)
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
