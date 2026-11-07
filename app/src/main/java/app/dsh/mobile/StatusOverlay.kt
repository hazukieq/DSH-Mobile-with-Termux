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
    /**
     * 「窗口是被用户退出流程抑制掉的」而不是用户长按隐藏的。
     * 2.9.104：退出后用户再打开 App，必须把球恢复回来；而长按隐藏是显式选择，不该被覆盖。
     */
    @Volatile private var hiddenByExit = false
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
    /** 换窗口类型（2038→2032）时暂存旧位置，供 startOverlay 复用。 */
    @Volatile private var pendingPos: Pair<Int, Int>? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var wm: WindowManager? = null

    /**
     * 是否「有活干」（执行中 / 录音 / 完成提示）。
     * 动作行（AI 正在干什么）只在有活干 + 展开态时显示 ——
     * 空闲时不该还挂着上一次的动作文案（主人要的是「现在在干什么」）。
     */
    @Volatile private var working = false
    // v2.9.83：无障碍回来了但当时正在显示进度（working）→ 类型切换推迟到回空闲，
    // 否则会丢掉正在滚动的进度文本。
    @Volatile private var pendingA11ySwitch = false
    // v2.9.87：类型切换因 working 被推迟的起始时刻（0 = 未推迟）；超时后强制切换
    @Volatile private var a11ySwitchDeferredAt = 0L
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

    /** 诊断用：一句话描述当前窗口状态（给截图链路打日志，便于定位「到底摘掉的是不是它」）。 */
    /** 当前悬浮窗在屏幕上的矩形（无窗口时 null）。prefetch 用它在 OCR 结果里排除悬浮窗自身文字。 */
    fun currentFrameRect(): android.graphics.Rect? {
        val ctx = appCtx ?: return null
        if (!liveViews.contains(root ?: return null)) return null
        val r = root ?: return null
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
            " hideRefs=$hideRefs" +
            " type=$windowTypeUsed"
    }

    fun isAvailable(): Boolean = DshAccessibilityService.instance != null

    /**
     * 显示悬浮窗。
     * @param svc 无障碍服务实例（提供 overlay 窗口类型）
     */
    /** 无障碍服务路径（历史行为）：用 TYPE_ACCESSIBILITY_OVERLAY，不需要悬浮窗权限。 */
    fun show(svc: AccessibilityService) {
        val ctx = svc.applicationContext
        val w = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 窗口类型纠正（2.9.75）：App 先起、无障碍后连时，窗口是按下面 show(ctx) 的
        // 「应用悬浮窗」(2038 + Activity token) 建的；这种窗口 App 一退后台就被 WMS 置
        // hidden（见 show(ctx) 注释里的实测，Xiaomi 上还叠加 mIsForceHiddenNonSystemOverlayWindow），
        // 表现就是「切个 App 悬浮窗就没了 / 一会儿又出来」。无障碍已就绪 → 换成 2032 并
        // 保留位置与展开档位，一次性纠正（只在 idle 时换，避免丢正在显示的进度文本）。
        if (root != null) {
            if (windowTypeUsed == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                a11ySwitchDeferredAt = 0L
                return
            }
            // v2.9.87：working 可能长期卡住（进度文本还在但本轮早已结束，实测 vivo 上
            // 60s 一次 "skipped: working" 无限循环），于是 2038 永远换不回 2032——
            // 而 2038+Activity token 一退后台就被 WMS 隐藏（「切个 App 球就没了」）。
            // 折中：只在 working 的**前 20 秒**推迟，超时强制切换（位置与档位照旧保留）。
            val deferFor = 20_000L
            val nowUptime = android.os.SystemClock.uptimeMillis()
            if (working && a11ySwitchDeferredAt != 0L && nowUptime - a11ySwitchDeferredAt < deferFor) {
                pendingA11ySwitch = true
                Log.i(TAG, "overlay type switch skipped: working (type=$windowTypeUsed, deferred=${nowUptime - a11ySwitchDeferredAt}ms)")
            } else {
                if (working) {
                    Log.w(TAG, "overlay type switch forced: working too long (type=$windowTypeUsed)")
                }
                a11ySwitchDeferredAt = 0L
                Log.i(TAG, "overlay type switch: $windowTypeUsed → TYPE_ACCESSIBILITY_OVERLAY (a11y ready)")
                pendingPos = (params?.x ?: 0) to (params?.y ?: 0)
                hideInternal(false)
            }
        }
        startOverlay(ctx, w, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
    }

    /**
     *
     * 优先用 **应用悬浮窗**（TYPE_APPLICATION_OVERLAY + SYSTEM_ALERT_WINDOW 权限），
     * 这样没开无障碍也能显示；没有悬浮窗权限但无障碍在跑时回退到无障碍窗口。
     * @return true 表示窗口已创建（或已存在）。
     */
    fun show(ctx: Context): Boolean {
        if (root != null) {
            // v2.9.83：无障碍回来了但窗口还是应用悬浮窗(2038) → 立刻换回 2032。
            // 调用点很密（ProgressHub 每次 publish），比等服务重连/看门狗更快收敛。
            val live = DshAccessibilityService.instance
            val stuck = working && a11ySwitchDeferredAt != 0L &&
                android.os.SystemClock.uptimeMillis() - a11ySwitchDeferredAt > 20_000L
            if (live != null && windowTypeUsed != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY && (!working || stuck)) {
                show(live)
            }
            return true
        }
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
        // v2.9.104：用户已退出（通知栏「退出」/划掉任务）就别再把窗口挂回去。
        // 退出后无障碍服务仍被系统绑着、进程还活着，各种保活/进度通路都会走到这里，
        // 结果就是"App 已经销毁，悬浮球却还在"（用户反馈）。
        if (runCatching { app.dsh.mobile.service.EngineService.userExited(ctx) }.getOrDefault(false)) {
            Log.i(TAG, "overlay suppressed: user exited")
            return
        }
        // v2.9.107：用户把「显示方式」设为关闭（ProgressHub.MODE_OFF）时，任何通路都不能把
        // 窗口再挂回来——读屏/点击/OCR/截图/分享这些能力与悬浮窗无关，必须照常工作；
        // 之前 hideAuto() 把 hidden 复位成 false，于是保活/进度/回到前台几条路各自 show() 一次，
        // 关掉的球过十几秒又冒出来（用户实测）。
        if (runCatching { app.dsh.mobile.ProgressHub.mode(ctx) } .getOrDefault("") !=
            app.dsh.mobile.ProgressHub.MODE_OVERLAY) {
            Log.i(TAG, "overlay suppressed: progress mode off")
            return
        }
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

                // 根据当前记忆档位初始化尺寸（折叠态 36dp，展开态 240dp），初始即用最终尺寸避免闪白跳动
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
                    // 默认放右侧；换窗口类型时沿用旧坐标（否则球会跳回默认位）
                    val keep = pendingPos
                    pendingPos = null
                    x = keep?.first ?: dp(ctx, 8)
                    y = keep?.second ?: dp(ctx, 120)
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
                    val vw = if (p.width > 0) p.width else if (view.width > 0) view.width else dp(ctx, 240)
                    p.x = (screenRect(ctx).width() - vw - margin).coerceAtLeast(margin)
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
            // 展开态：只有点击状态圆点（statusDot）才收起回折叠球
            val collapse = android.view.View.OnClickListener { collapseToBall() }
            sDot?.setOnClickListener(collapse)
            sDot?.isClickable = true

            // 扩展 statusDot 的触摸热区，方便点击收起
            val parent = sDot?.parent as? View
            parent?.post {
                val rect = android.graphics.Rect()
                sDot?.getHitRect(rect)
                val pad = dp(ctx, 12)
                rect.top -= pad
                rect.bottom += pad
                rect.left -= pad
                rect.right += pad
                parent.touchDelegate = android.view.TouchDelegate(rect, sDot)
            }

            // 正文区域纯滚动，不响应收起，不拦截卡片手势
            sScroll?.isClickable = false
            statusTv?.isClickable = false

            // 卡片主体支持拖动与长按隐藏；单击主体空白处不收起，避免误触。
            // 但「档位说展开、实际渲染是球」（重建后脱钩）时，这一下点击必须自愈成展开，
            // 否则用户点了半天没反应 —— 这正是「悬浮窗点不开」的另一半成因。
            attachGesture(
                view, p, ctx,
                onClick = { if (statusRow?.visibility != View.VISIBLE) expandPanel() },
                scrollChild = sScroll,
            )
        } else {
            // 折叠态：整颗球都能点/拖。
            // v2.9.106（用户要求）：长按隐藏已彻底移除——它只会让人误触把球弄丢，
            // 想隐藏请在设置里关掉「显示悬浮窗」。
            attachGesture(
                view, p, ctx,
                onClick = { expandPanel() },
            )
        }
    }

    /** 收起回折叠球（展开态点圆点的语义） */
    private fun collapseToBall() {
        autoExpanded = false
        if (expandLevel == 0) return
        expandLevel = 0
        rememberedLevel = 0
        lastAppliedLevel = -1   // 收起后同样要按球宽重新锚定
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
        /**
         * 展开态传 statusScroll：手指落在「可滚动的正文区」里时**不吃事件**，
         * 交给 ScrollView 自己滚。不传的话根容器会把 DOWN 吞掉 → 正文滚不动。
         */
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
            p.height > 0 -> p.height
            v.height > 0 -> v.height
            else -> if (expandLevel == 0) dp(ctx, COLLAPSED_BALL_DP) else dp(ctx, 120)
        }
        return p.y.coerceIn(dp(ctx, 10), (screenH - vh - dp(ctx, 10)).coerceAtLeast(dp(ctx, 10)))
    }

    /** 计算贴边后的 x（按当前窗口宽度与 dockRight 侧）。 */
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

    /**
     * 按「当前尺寸」重新贴到所在一侧。
     *
     * 仅用于档位切换（球 ↔ 卡片）后的锚定：保持贴边的一侧不移动。
     */
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
            // 兜底夹取：任何情况下窗口右边都不能超出屏幕。
            // 早先「展开卡片 x 仍是球坐标（948 + 720 > 1080）」时，用户只看到屏幕上一条
            // 竖边、点哪里都没反应 —— 与「点不开」是同一个体验故障。
            prm.x = dockX(v, prm, ctx).coerceIn(margin, (screenW - vw - margin).coerceAtLeast(margin))
            prm.y = clampY(v, prm, ctx)
            safeUpdateLayout(wm, v, prm)
        }
    }

    /** 松手后吸附到最近的水平边缘（避免长期悬在屏幕中央挡内容） */
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

    /**
     * 展开悬浮窗卡片：
     *  0 折叠：只有圆形状态点（36dp）
     *  1 展开：固定 240dp 定宽卡片，三段式展示
     */
    private fun expandPanel() {
        autoExpanded = false
        // ⚠️ 早退判据必须是「渲染态」，不能只看 expandLevel。
        // 窗口重建 / 2032↔2038 类型切换 / hide→show 之后，expandLevel 可能被 rememberedLevel
        // 留在 1，而视图已经按折叠态重建 —— 此时旧代码 `if (expandLevel == 1) return`
        // 会把点击直接吞掉，真机表现就是「悬浮窗点不开」（v2.9.95 修复）。
        val renderedExpanded = statusRow?.visibility == View.VISIBLE
        if (renderedExpanded && expandLevel == 1) return
        expandLevel = 1
        rememberedLevel = 1
        lastAppliedLevel = -1   // 强制重算锚定（卡在屏幕外的宽条就是漏锚定导致的）
        applyExpandLevel()
        // 再补一次：刚摘过窗口/刚由 GONE 恢复时，首帧测量可能还停在折叠态，
        // 补一次保证圆点、滚动区、文字三者的可见性与卡片尺寸一致。
        root?.postDelayed({ runCatching { applyExpandLevel() } }, 120L)
    }
    /** 按档位应用布局。窗口尺寸只由 [applyWindowSize] 决定，其它任何地方都不写宽高。 */
    private fun applyExpandLevel(r: View? = root) {
        val ctx = appCtx ?: return
        val view = r ?: return
        val collapsed = expandLevel == 0

        // 内边距：球态必须为 0（保证圆点居中）；卡片态 10/8/12/8。
        if (collapsed) view.setPadding(0, 0, 0, 0)
        else view.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))

        val bgRes = if (collapsed) R.drawable.bg_overlay_ball else R.drawable.bg_overlay_panel
        if (bgRes != lastBgRes) {
            lastBgRes = bgRes
            runCatching { view.setBackgroundResource(bgRes) }
        }

        // 球态：窗口本身是正方形，圆点居中
        (view as? LinearLayout)?.gravity =
            if (collapsed) Gravity.CENTER else (Gravity.TOP or Gravity.START)

        statusRow?.visibility = if (collapsed) View.GONE else View.VISIBLE
        ballDot?.visibility = if (collapsed) View.VISIBLE else View.GONE
        statusText?.visibility = if (collapsed) View.GONE else View.VISIBLE
        statusScroll?.visibility = if (collapsed) View.GONE else View.VISIBLE

        // 展开态必须**永远**有字：曾经出现过「卡片在、文字空了」——档位切换/截图避让
        // 的 GONE-VISIBLE 往返中途被打断，文字停留空串，卡片就成了一张空框。
        if (!collapsed) {
            statusText?.setTextColor(statusTextColor)
            if (statusText?.text.isNullOrBlank()) {
                statusText?.text = ctx.getString(R.string.overlay_idle)
            }
        }

        statusScroll?.let { sc ->
            // 折叠态不参与测量；展开态**空闲与工作用同一套宽高**（用户定调：
            // 「空闲态就是和工作态一样的宽高」）——滚动区固定 92dp，卡片高度恒定不跳动。
            val targetH = if (collapsed) android.view.ViewGroup.LayoutParams.WRAP_CONTENT else dp(ctx, 92)
            val lp = sc.layoutParams
            if (lp != null) {
                var changed = false
                if (lp.height != targetH) { lp.height = targetH; changed = true }
                // ⚠️ 绝对不要动 weight：overlayScroll 的 layout_width 是 0dp + weight=1
                // （横向 LinearLayout 里靠权重撑开）。把 weight 清零会让宽度变成 0，
                // 卡片照样画出来、圆点也在，但**文字全被裁成 0 宽**——
                // 用户看到的就是「展开态文本不显示」。这里只固定高度。
                if (changed) sc.layoutParams = lp
            }
            sc.visibility = if (collapsed) View.GONE else View.VISIBLE
        }

        refreshActionVisibility()
        applyWindowSize(ctx, view, collapsed)

        // 每次应用档位都要重新锚定：宽度会在球 108 ↔ 卡片 720 之间变，
        // 只在「档位值变了」时锚定，会让重建后的窗口停在旧坐标上
        // （展开卡片 x=948 + 宽 720 → 588px 在屏幕外 = 用户眼里的「点不开」）。
        lastAppliedLevel = expandLevel
        if (!anchorPending) {
            anchorPending = true
            view.postOnAnimation {
                anchorPending = false
                anchorToEdge()
            }
        }

        bindHandle(view, params ?: return, ctx)
        view.requestLayout()
    }

    /**
     * 窗口尺寸的唯一真源：
     *  - 折叠球：COLLAPSED_BALL_DP (36dp) 正方球
     *  - 展开态：无论空闲还是工作，一律保持固定 240dp 宽，高度稳定（内部滚动区 92dp）
     */
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
        // 我们从不设置 NOT_TOUCHABLE；若历史版本残留过，这里顺手清掉（悬浮窗「看得见点不动」的真凶之一）
        if ((p.flags and notTouch) != 0) {
            p.flags = p.flags and notTouch.inv()
            needUpdate = true
            Log.w(TAG, "cleared stale NOT_TOUCHABLE flag")
        }
        if (needUpdate) {
            runCatching { safeUpdateLayout(wm, view, p) }
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
        // v2.9.72（真机取证修复）：ProgressHub 每 120ms 无脑 reassert 一次，
        // 避让期间会把 INVISIBLE 硬捞回 VISIBLE —— 截图带上悬浮窗 + VISIBLE/INVISIBLE
        // 交替 = 加倍闪。避让是引用计数的语义，reassert 必须让位于它。
        if (hideRefs > 0) return
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
        //
        // v2.9.81（vivo / OriginOS 17 真机实测）：系统会在后台**静默撤销**无障碍
        // （`enabled_accessibility_services` 变 null，隔夜就发生），于是 2032 窗口随服务
        // 一起销毁 —— 用户看到的是「球突然没了 / 再点也没反应」，只能重开 App。
        // 这里在服务断开时**立刻用应用悬浮窗(2038)顶上**，保住球与位置；
        // 服务重连时 show(svc) 会走 2.9.75 的类型纠正把它换回 2032（不需要额外代码）。
        val ctx = appCtx
        val keep = root != null && !hidden && ctx != null &&
            !app.dsh.mobile.service.EngineService.userExited(ctx)
        hide(null)
        if (!keep || ctx == null) return
        // hide() 是 post 到主线程执行的：必须等它真的把窗口摘掉、并把 hidden 复位之后
        // 再 show —— 否则 show(ctx) 里 `if (root != null) return true` 会提前返回，
        // 紧接着排队执行的 hide 又把窗口删掉（实测踩过：日志是「kept alive」紧跟「hidden by user」）。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching {
                resetHidden()
                if (show(ctx)) Log.i(TAG, "overlay kept alive as app overlay (a11y lost)")
                else Log.w(TAG, "overlay fallback: show(ctx) returned false")
            }.onFailure { Log.w(TAG, "overlay fallback failed: ${it.message}") }
        }, 300L)
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
            // v2.9.72：窗口被摘掉后引用计数必须归零——否则此后每个 showNow() 都只会
            // 把 refs 减到 1 就 return，悬浮窗永久不再出现（用户报「有时候再也点不到」）。
            synchronized(refLock) { hideRefs = 0 }
            hidden = userHidden
            Log.i(TAG, if (userHidden) "overlay hidden (sticky)" else "overlay hidden (auto)")
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
            hiddenByExit = true
            Log.i(TAG, "overlay torn down (engine exited)")
        }
    }

    /** 用户隐藏后重新允许显示（引擎重启等场景调用） */
    fun resetHidden() { hidden = false }

    /**
     * 用户重新打开 App（MainActivity 已清掉 userExited）时调用：
     * 把「退出流程抑制掉的窗口」恢复回来。
     * 设置里关掉悬浮窗属于另一条路径（`setOverlayEnabled(false)`），不走这里。
     * @return true 表示确实恢复了一次（调用方可以顺手 show）
     */
    fun resumeAfterExit(): Boolean {
        if (!hiddenByExit) return false
        hiddenByExit = false
        hidden = false
        Log.i(TAG, "overlay resumed after user exited")
        return true
    }

    // ── 显示/隐藏：唯一机制就是 view 级 visibility ─────────────────────────────
    // 铁律（用户定调 + 实测，别再动）：
    //  * `INVISIBLE` 的视图不绘制、不参与命中测试，**也不会挡住自动化注入的手势** ——
    //    所以「截图避让」与「自动化操作避让」用同一套隐藏机制。
    //  * 绝不 `removeView`/`addView`：重建窗口会丢触摸状态（点不动/拖不动/滚不动）还会闪。
    //  * 绝不改 `params.flags`：`FLAG_NOT_TOUCHABLE` 一旦残留，悬浮窗就永久点不动。
    //  * 恢复时不做布局重放：重放会改布局导致「UI 变形」；这里只把 visibility 改回来。
    @Volatile private var hideRefs = 0
    /** 保护 [hideRefs]：bridge 线程、主线程、watchdog 都会加减它（原先 `+=` 是竞态） */
    private val refLock = Any()

    private fun hideNow(reason: String) {
        synchronized(refLock) {
            hideRefs += 1
            if (hideRefs > 1) {
                Log.i(TAG, "hide($reason): already hidden refs=$hideRefs")
                return
            }
        }
        var applied = false
        val ran = mainSync {
            root?.let {
                it.visibility = View.INVISIBLE
                applied = true
                Log.i(TAG, "hide($reason): visibility=${it.visibility} (${debugState()})")
            } ?: Log.w(TAG, "hide($reason): no root (overlay not attached)")
        }
        if (!ran || !applied) {
            // 没真隐藏就没资格占用引用计数：否则 endCaptureAvoid 时 refs 减到 0
            // 之外的路径会让计数永久偏移 → 要么一直接不到、要么再也不显示。
            synchronized(refLock) { hideRefs = (hideRefs - 1).coerceAtLeast(0) }
            Log.w(TAG, "hide($reason): FAILED (ran=$ran applied=$applied) refs rolled back to $hideRefs")
        }
    }

    private fun showNow(reason: String) {
        var stillHidden = false
        synchronized(refLock) {
            if (hideRefs <= 0) {
                hideRefs = 0
                return
            }
            hideRefs -= 1
            stillHidden = hideRefs > 0
            if (stillHidden) Log.i(TAG, "show($reason): still hidden refs=$hideRefs")
        }
        if (stillHidden) return
        var applied = false
        val ran = mainSync {
            root?.let {
                it.visibility = View.VISIBLE
                it.requestLayout()
                applied = true
                Log.i(TAG, "show($reason): visibility=${it.visibility} (${debugState()})")
            } ?: Log.w(TAG, "show($reason): no root (overlay not attached)")
        }
        if (!ran || !applied) {
            synchronized(refLock) { hideRefs += 1 }
            Log.w(TAG, "show($reason): FAILED (ran=$ran applied=$applied) refs restored to $hideRefs")
        }
    }

    /**
     * 截图前隐藏悬浮窗（view 级 INVISIBLE，不摘窗口、不改 flags）。
     * 配合 [DshAccessibilityService.screenshotBase64] 里的「≥333ms 节流 + 先丢两帧」，
     * 实测截图里不会出现悬浮窗。
     */
    fun beginCaptureAvoid(svc: AccessibilityService?) {
        Log.i(TAG, "capture avoid: begin (${debugState()})")
        hideNow("capture")
        Thread.sleep(120L)   // 让合成器吃掉这一帧（双缓冲陈旧帧由截图侧 warmup 处理）
    }

    fun endCaptureAvoid(svc: AccessibilityService?) {
        showNow("capture")
        Log.i(TAG, "capture restore: done (${debugState()})")
    }

    /** 自动化操作期间的避让：同样只是隐藏（INVISIBLE 不吃命中测试），不改 flags、不摘窗口。 */
    private val opsAvoidWatchdog = Runnable {
        val stuck = synchronized(refLock) {
            if (hideRefs > 0) { hideRefs = 0; true } else false
        }
        if (stuck) {
            Log.w(TAG, "opsAvoid watchdog fired (endOpsAvoid missed) — force show")
            mainSync {
                root?.let {
                    it.visibility = View.VISIBLE
                    it.requestLayout()
                }
            }
        }
    }

    /**
     * v2.9.86：**只有注入手势真的压到悬浮窗时才避让**。
     * @param gestureBounds 一次注入的所有 stroke 的并集（屏幕坐标，已外扩）
     * @return true 表示已隐藏，调用方必须在手势结束后调用 [endOpsAvoid]
     */
    fun beginOpsAvoidIfHit(gestureBounds: android.graphics.Rect): Boolean {
        val r = currentFrameRect()
        if (r == null) {
            Log.d(TAG, "ops avoid skip: overlay rect unknown (gesture=$gestureBounds refs=$hideRefs)")
            return false
        }
        if (!android.graphics.Rect.intersects(r, gestureBounds)) {
            Log.d(TAG, "ops avoid skip: overlay=$r gesture=$gestureBounds")
            return false
        }
        beginOpsAvoid()
        return true
    }

    fun beginOpsAvoid() {
        hideNow("ops")
        mainHandler.removeCallbacks(opsAvoidWatchdog)
        mainHandler.postDelayed(opsAvoidWatchdog, 90_000L)
        Log.i(TAG, "opsAvoid on (${debugState()})")
    }

    fun endOpsAvoid() {
        mainHandler.removeCallbacks(opsAvoidWatchdog)
        showNow("ops")
        Log.i(TAG, "opsAvoid off (${debugState()})")
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
        a11yText?.visibility = if (expanded && a11yLines.isNotEmpty()) View.VISIBLE else View.GONE
        footRow?.visibility = if (expanded) View.VISIBLE else View.GONE
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
            working = true

            val full = text.trim()
            val body = if (full.length > 600) full.takeLast(600) else full
            val nowMs = android.os.SystemClock.uptimeMillis()
            if (body == lastStatusBody || nowMs - lastStatusAt < 250L) {
                return@main
            }
            lastStatusBody = body
            lastStatusAt = nowMs

            statusText?.text = body
            statusText?.visibility = if (expandLevel > 0) View.VISIBLE else View.GONE
            ensureExpandedContent()
            clearReplyReady()
            tintDots(COLOR_WORKING)

            // 流式输出时：仅当展开态且在 View 层平滑滚动到底部，绝不调用 applyExpandLevel 触发 WMS 重排与跳动！
            if (expandLevel > 0) {
                statusScroll?.post {
                    statusScroll?.fullScroll(View.FOCUS_DOWN)
                }
            }
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
            ensureExpandedContent()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    /**
     * 展开态自愈：卡片窗口在、但圆点/滚动区/文字被中途的 GONE→VISIBLE、摘窗口重挂、
     * 活动切后台等操作打断时会停在「空框」状态（用户看到的「展开态文本不显示」）。
     * 这里做一次廉价的可见性+文案校验，不一致就整套重放，成本只有几个 getter。
     */
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

    /**
     * 回到空闲态（灰色点 + 空闲文案）。
     * ⚠️ 展开态回到空闲时，保持展开卡片展示「空闲」，绝不自动收起成球或改变尺寸！
     */
    fun setIdle() {
        main {
            cancelIdleReset()
            statusText?.text = appCtx?.getString(R.string.overlay_idle) ?: "Idle"
            tintDots(COLOR_IDLE)
            working = false
            if (pendingA11ySwitch) {
                pendingA11ySwitch = false
                DshAccessibilityService.instance?.let { svc ->
                    if (windowTypeUsed != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                        Log.i(TAG, "overlay type switch: deferred until idle")
                        show(svc)
                    }
                }
            }
            startedAt = 0L
            stopTicker()
            elapsedText?.text = ""
            refreshActionVisibility()
            ensureExpandedContent()
            autoExpanded = false
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
     * 在主线程执行并**等待完成**（最多 [timeoutMs]）。
     *
     * 截图避让必须确认「窗口已被摘掉」才能拍照，而 [main] 只是 post，调用方拿不到时序；
     * 之前的版本在 post 出去的块里等栅栏 → 把主线程堵死 → 截图超时、窗口也没挂回去。
     * 已经在主线程时直接执行（否则会自锁）。
     */
    private fun mainSync(timeoutMs: Long = 800L, block: () -> Unit): Boolean {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return runCatching { block(); true }.getOrDefault(false)
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val posted = runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching(block)
                latch.countDown()
            }
        }.isSuccess
        if (!posted) return false
        return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
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
