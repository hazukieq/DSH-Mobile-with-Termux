package app.dsh.mobile.term

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.R
import app.dsh.mobile.linux.LinuxEnvActivity

/**
 * 终端页：一台手机上的「真终端」。
 *
 * 它是 App 内唯一一个**非 WebView** 的功能页 —— 引擎仍跑在 WebView 里，
 * 但终端要的是原生绘制 + PTY 流式 IO，塞进 WebView 只会更慢更脆。
 *
 * 生命周期约定（2026-10-18）：
 *  - 会话由 [TerminalSessions] 单例托管，**旋转/改字号不重建进程**（configChanges 已声明）；
 *  - 用户按返回退出页面时，若还有滚动历史没到底先回到底部（一次），再退出；
 *  - 真正退出（finish）时关闭本页创建的全部会话，避免后台残留 shell。
 *
 * 入口：
 *  - 设置页 →「终端」；
 *  - 桌面图标（manifest 里 TerminalActivity 也注册了 LAUNCHER，作为独立终端 App）；
 *  - 意图带 `profile`（如 `alpine`）或 `distro` 直接进某个 Linux 发行版；
 *  - Linux 环境页的「打开终端」。
 */
class TerminalActivity : Activity() {

    private lateinit var termView: TerminalView
    private lateinit var extraKeys: TerminalExtraKeysView
    private lateinit var tabStrip: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tvTitle: TextView

    /** 本页创建的会话（不引用 TerminalSessions.all —— 别的页面可能也建过） */
    private val sessions = mutableListOf<TerminalSession>()
    private var active: TerminalSession? = null

    private val prefs by lazy { getSharedPreferences(DshAppPrefs.NAME, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        // 终端要长时间亮屏（编译、日志跟踪），且软键盘必须压缩布局而不是盖住终端
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        termView = findViewById(R.id.termView)
        extraKeys = findViewById(R.id.termExtraKeys)
        tabStrip = findViewById(R.id.termTabStrip)
        tabScroll = findViewById(R.id.termTabScroll)
        tvTitle = findViewById(R.id.tvTermTitle)
        extraKeys.target = termView
        extraKeys.onToggleKeyboard = { toggleKeyboard() }

        applyDisplayPrefs()
        wireTopBar()

        val wanted = intent.getStringExtra(EXTRA_PROFILE)
            ?: intent.getStringExtra(EXTRA_DISTRO)
            ?: prefs.getString(KEY_LAST_PROFILE, ShellProfiles.ID_ANDROID)
        openSession(wanted ?: ShellProfiles.ID_ANDROID)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val wanted = intent?.getStringExtra(EXTRA_PROFILE)
            ?: intent?.getStringExtra(EXTRA_DISTRO)
            ?: return
        openSession(wanted)
    }

    // ── UI 装配 ────────────────────────────────────────────────────────────

    private fun wireTopBar() {
        findViewById<android.widget.ImageView>(R.id.btnTermBack).setOnClickListener { onBack() }
        findViewById<TextView>(R.id.btnTermNew).setOnClickListener { chooseShell() }
        findViewById<TextView>(R.id.btnTermMenu).setOnClickListener { showMenu() }
    }

    private fun applyDisplayPrefs() {
        val sp = prefs.getFloat(KEY_FONT_SP, DEFAULT_FONT_SP)
        termView.fontSizePx = sp * resources.displayMetrics.scaledDensity
        termView.colorScheme = TerminalColorScheme.DARK
    }

    // ── 会话 ───────────────────────────────────────────────────────────────

    private fun openSession(profileId: String) {
        var spec = ShellProfiles.specFor(this, profileId)
        if (spec == null) {
            // Linux 发行版未安装 / proot 缺失：明确告知并回落到 Android 环境，
            // 而不是静默给个空终端（用户会以为程序坏了）
            toast(getString(R.string.terminal_profile_unavailable, profileId))
            spec = ShellProfiles.androidSpec(this)
        }
        val rows = if (termView.termRows > 0) termView.termRows else 24
        val cols = if (termView.termCols > 0) termView.termCols else 80
        val session = runCatching { TerminalSessions.create(spec, rows, cols) }.getOrNull()
        if (session == null) {
            toast(getString(R.string.terminal_create_failed))
            return
        }
        session.onUpdate = { s -> runOnUiThread { if (active === s) syncTitle() } }
        session.onExited = { s ->
            runOnUiThread {
                updateTabs()
                if (active === s) syncTitle()
            }
        }
        session.emulator.onTitle = { t -> runOnUiThread { if (active === session) tvTitle.text = t } }
        sessions += session
        prefs.edit().putString(KEY_LAST_PROFILE, profileId).apply()
        attach(session)
    }

    private fun attach(session: TerminalSession) {
        active = session
        termView.attach(session)
        extraKeys.target = termView
        updateTabs()
        syncTitle()
        // 让 IME 直接可用：用户点进终端通常就是要打字
        termView.post { termView.requestTerminalFocus() }
    }

    private fun syncTitle() {
        val s = active ?: return
        val base = s.spec.title.ifBlank { getString(R.string.terminal_title) }
        val osc = s.emulator.title
        tvTitle.text = if (osc.isNotBlank()) osc else base
    }

    private fun updateTabs() {
        tabStrip.removeAllViews()
        if (sessions.size <= 1) {
            tabScroll.visibility = android.view.View.GONE
            return
        }
        tabScroll.visibility = android.view.View.VISIBLE
        sessions.forEach { s ->
            val tv = TextView(this).apply {
                text = s.spec.title.ifBlank { "sh" }
                textSize = 12f
                setTextColor(if (s === active) 0xFF0B0F13.toInt() else 0xFFB8C2CE.toInt())
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(if (s === active) 0xFF7DD3FC.toInt() else 0xFF1E2731.toInt())
                }
                setOnClickListener { attach(s) }
                setOnLongClickListener {
                    confirmClose(s)
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(6)
            tabStrip.addView(tv, lp)
        }
        // 选中项滚进可见区
        tabStrip.post { tabScroll.smoothScrollTo(maxOf(0, tabStrip.width - tabScroll.width), 0) }
    }

    private fun closeSession(s: TerminalSession) {
        runCatching { s.close() }
        sessions.remove(s)
        if (active === s) {
            active = null
            val next = sessions.lastOrNull()
            if (next != null) attach(next) else finish()
        } else {
            updateTabs()
        }
    }

    private fun confirmClose(s: TerminalSession) {
        AlertDialog.Builder(this)
            .setTitle(R.string.terminal_close_session)
            .setMessage(s.spec.title)
            .setPositiveButton(R.string.terminal_close) { _, _ -> closeSession(s) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── 菜单 ───────────────────────────────────────────────────────────────

    private fun shellItems(): List<Pair<String, () -> Unit>> {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        ShellProfiles.profiles(this).forEach { p ->
            items += p.title to { openSession(p.id) }
        }
        items += getString(R.string.terminal_linux_env) to {
            startActivity(Intent(this, LinuxEnvActivity::class.java))
        }
        return items
    }

    private fun chooseShell() {
        val items = shellItems()
        AlertDialog.Builder(this)
            .setTitle(R.string.terminal_choose_shell)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun showMenu() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun add(label: Int, act: () -> Unit) {
            labels += getString(label); actions += act
        }
        add(R.string.terminal_menu_new) { chooseShell() }
        add(R.string.terminal_menu_paste) { pasteFromClipboard() }
        add(R.string.terminal_menu_copy_all) { copyAll() }
        add(R.string.terminal_menu_font_up) { bumpFont(+1f) }
        add(R.string.terminal_menu_font_down) { bumpFont(-1f) }
        add(R.string.terminal_menu_scroll_bottom) { termView.scrollToBottom() }
        add(R.string.terminal_menu_close) { active?.let { confirmClose(it) } }
        if (sessions.size > 1) add(R.string.terminal_menu_close_all) { closeAllAndFinish() }
        add(R.string.terminal_linux_env) { startActivity(Intent(this, LinuxEnvActivity::class.java)) }
        AlertDialog.Builder(this)
            .setTitle(R.string.terminal_menu)
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .show()
    }

    private fun bumpFont(delta: Float) {
        val cur = prefs.getFloat(KEY_FONT_SP, DEFAULT_FONT_SP)
        val next = (cur + delta).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        if (next == cur) return
        prefs.edit().putFloat(KEY_FONT_SP, next).apply()
        applyDisplayPrefs()
        // 字号变了 → 行列数变 → 让 View 重新结算并 resize PTY（View 内部会做）
        termView.requestLayout()
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            toast(getString(R.string.terminal_clipboard_empty))
            return
        }
        termView.sendText(text)
    }

    /**
     * 复制整个缓冲（回滚 + 当前屏）。
     *
     * 不用 View 的选择态：Android 自绘终端的跨行选择/手柄是另一个量级的工程，
     * 而手机上「整段复制日志」是最常见诉求 —— 直接给全量文本更实用。
     */
    private fun copyAll() {
        val s = active ?: return
        val e = s.emulator
        val sb = StringBuilder()
        for (r in 0 until e.scrollbackRows) {
            val line = StringBuilder()
            for (c in 0 until e.cols) {
                val cell = e.cellAt(r, c)
                if (cell.text.isEmpty()) continue   // 宽字符的第二格
                line.append(cell.text)
            }
            val trimmed = line.toString().trimEnd()
            // 末尾连续空行折叠：否则复制出来的东西后面拖几十个空行
            if (trimmed.isEmpty() && sb.isNotEmpty() && sb.last() == '\n') continue
            sb.append(trimmed).append('\n')
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", sb.toString()))
        toast(getString(R.string.terminal_copied, sb.length))
    }

    private fun closeAllAndFinish() {
        sessions.toList().forEach { runCatching { it.close() } }
        sessions.clear()
        active = null
        finish()
    }

    // ── 返回键 ─────────────────────────────────────────────────────────────

    @Deprecated("Use onBack(); kept because the project has no AndroidX dependency")
    override fun onBackPressed() {
        onBack()
    }

    private fun onBack() {
        // 若正在回看历史，第一次返回先回到底部（与桌面终端一致的直觉）。
        //
        // ⚠️ 2026-10-18 修 bug：这里原先写的是
        //     viewportTopRow < scrollbackRows - 1
        // 但视口顶行最大只到 `scrollbackRows - 屏幕行数`（maxTop），所以只要屏幕
        // 行数 > 1，这个条件**恒为真** —— 每次返回都只是 scrollToBottom()（贴底时
        // 还是空操作），finish() 永远走不到，表现就是「返回键点了没反应」。
        // 判据应改成 emulator 自己给的 scrolledBack（viewTop < maxTop）。
        val e = active?.emulator
        if (e != null && e.snapshot().scrolledBack) {
            termView.scrollToBottom()
            return
        }
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) {
            sessions.toList().forEach { runCatching { it.close() } }
            sessions.clear()
            active = null
        }
        super.onDestroy()
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 软键盘开/关；横屏下这是「看见输出」的唯一入口（见 TerminalExtraKeysView 注释） */
    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        if (imm.isActive(termView)) {
            imm.hideSoftInputFromWindow(termView.windowToken, 0)
            termView.clearFocus()
        } else {
            termView.requestTerminalFocus()
        }
    }

    /** 设置页偏好文件是 DshApp.PREFS_UI，这里复用同一份，避免两套界面偏好 */
    private object DshAppPrefs {
        const val NAME = "dsh_ui"
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_DISTRO = "distro"
        private const val KEY_LAST_PROFILE = "term_last_profile"
        private const val KEY_FONT_SP = "term_font_sp"
        private const val DEFAULT_FONT_SP = 13f
        private const val MIN_FONT_SP = 8f
        private const val MAX_FONT_SP = 26f

        fun intent(ctx: Context, profileId: String? = null): Intent =
            Intent(ctx, TerminalActivity::class.java).apply {
                if (!profileId.isNullOrBlank()) putExtra(EXTRA_PROFILE, profileId)
            }
    }
}
