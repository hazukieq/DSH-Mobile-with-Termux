package app.dsh.mobile.term

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * 终端配色方案。`ansi` 是 16 色 ANSI 调色板（0-7 常规，8-15 亮色），
 * 交由 [TerminalEmulator.setAnsiPalette] 解析 SGR 30-37/40-47/90-97/100-107。
 *
 * 颜色全部按 0xAARRGGBB 存（View 直接交给 Paint）。
 */
data class TerminalColorScheme(
    /** 默认背景色 */
    val bg: Int,
    /** 默认前景色 */
    val fg: Int,
    /** 光标色（块状光标时也作为光标下的文字色） */
    val cursor: Int,
    /** 16 色 ANSI 调色板 */
    val ansi: IntArray,
) {
    companion object {
        /** 深色（默认）：对齐 App 的深色设计语言 #101418 + 强调色 #7DD3FC */
        val DARK: TerminalColorScheme = TerminalColorScheme(
            bg = 0xFF101418.toInt(),
            fg = 0xFFE8ECF2.toInt(),
            cursor = 0xFF7DD3FC.toInt(),
            ansi = intArrayOf(
                0xFF1B222B.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
                0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFABB2BF.toInt(),
                0xFF5C6370.toInt(), 0xFFFF7B86.toInt(), 0xFFB5E890.toInt(), 0xFFFFD68A.toInt(),
                0xFF82CFFF.toInt(), 0xFFE39BE8.toInt(), 0xFF6FD3DE.toInt(), 0xFFF2F4F8.toInt(),
            ),
        )

        /** 浅色（户外/强光） */
        val LIGHT: TerminalColorScheme = TerminalColorScheme(
            bg = 0xFFF7F9FC.toInt(),
            fg = 0xFF1B2430.toInt(),
            cursor = 0xFF0B63A8.toInt(),
            ansi = intArrayOf(
                0xFF24292F.toInt(), 0xFFCF222E.toInt(), 0xFF116329.toInt(), 0xFF7A5D00.toInt(),
                0xFF0550AE.toInt(), 0xFF8250DF.toInt(), 0xFF0E7C86.toInt(), 0xFF6E7781.toInt(),
                0xFF57606A.toInt(), 0xFFA40E26.toInt(), 0xFF1A7F37.toInt(), 0xFF9A6700.toInt(),
                0xFF0969DA.toInt(), 0xFFA475F9.toInt(), 0xFF1B7C83.toInt(), 0xFF8C959F.toInt(),
            ),
        )
    }
}

/**
 * 终端渲染 / 输入控件。
 *
 * ## 职责
 *  - **渲染**：Canvas 逐行画背景色块（同色合并成 run）+ 逐格 drawText；
 *    支持粗体（fake bold）、斜体（skew）、下划线、删除线、反显、暗色；
 *  - **滚动**：触摸拖动 / [scrollBy] 改变 emulator.viewportTopRow，贴底时跟随输出；
 *    回看时右上角画「↓ N」指示；
 *  - **输入**：软键盘（[BaseInputConnection] 把 commitText 直接写 PTY）、硬件键
 *    （ENTER/BACKSPACE/方向键/TAB/ESC/Home/End/PgUp/PgDn）、[sendKey]/
 *    [sendControlChar]/[sendText] 给额外按键条用；
 *  - **尺寸**：View 拥有 PTY 尺寸 —— `onSizeChanged` / attach / 字号变化时按
 *    单元格宽高算 rows/cols 并 `session.resize()`（Activity 先给 24x80 占位即可）。
 *
 * ## 线程
 * 渲染在 UI 线程；`emulator.onChanged` 由 reader 线程触发，这里只
 * `postInvalidateOnAnimation()`（线程安全）。
 *
 * ## 已知限制
 *  - 不做选区/复制粘贴（契约外；后续可加）；
 *  - IME 组合输入（拼音候选）不显示预编辑串，选词提交时才写入 PTY；
 *  - 鼠标上报未实现（emulator 侧安全忽略）。
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ------------------------------------------------------------------
    // 依赖与绘制状态
    // ------------------------------------------------------------------

    private var session: TerminalSession? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        isSubpixelText = true
    }
    private val fillPaint = Paint()

    private var fontMetrics: Paint.FontMetrics = textPaint.fontMetrics
    private var cellWidth = 1
    private var cellHeight = 1

    /** 最近一次应用到 PTY 的行/列（0 = 还没布局） */
    private var appliedRows = 0
    private var appliedCols = 0

    /** 最近一次计算出的几何尺寸（像素视图未布局前为 0） */
    val termRows: Int get() = appliedRows
    val termCols: Int get() = appliedCols

    private val blinkHandler = Handler(Looper.getMainLooper())
    private var cursorOn = true

    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            invalidate()
            blinkHandler.postDelayed(this, BLINK_MS)
        }
    }

    /** 字号（px）；Lead 用 SharedPreferences 持久化 */
    var fontSizePx: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        DEFAULT_FONT_SP,
        resources.displayMetrics,
    )
        set(value) {
            if (field == value) return
            field = value
            updateMetrics()
            applySize(force = true)
            invalidate()
        }

    /** 配色方案；切换时同步 ANSI 调色板（只影响后续输出，历史内容保留写入时的颜色） */
    var colorScheme: TerminalColorScheme = TerminalColorScheme.DARK
        set(value) {
            if (field === value) return
            field = value
            session?.emulator?.setAnsiPalette(value.ansi)
            updateMetrics()
            invalidate()
        }

    // ------------------------------------------------------------------
    // 尺寸
    // ------------------------------------------------------------------

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setWillNotDraw(false)
        updateMetrics()
    }

    private fun updateMetrics() {
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.textSize = fontSizePx
        fontMetrics = textPaint.fontMetrics
        // 等宽字体的「格宽」用字符 M 的步进；取 ceil 避免相邻字挤在一起
        cellWidth = max(1, ceil(textPaint.measureText("M").toDouble()).toInt())
        val lineHeight = fontMetrics.descent - fontMetrics.ascent + fontMetrics.leading
        cellHeight = max(1, ceil(lineHeight.toDouble()).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applySize(force = false)
    }

    /**
     * 按当前像素尺寸算出 rows/cols 并同步给会话。
     * @param force true = 即使行列没变也调一次 `session.resize()`（attach/字号变化用）
     */
    private fun applySize(force: Boolean) {
        if (cellWidth <= 0 || cellHeight <= 0) return
        val contentW = max(1, width - paddingLeft - paddingRight)
        val contentH = max(1, height - paddingTop - paddingBottom)
        if (width <= 0 || height <= 0) return
        val newCols = max(1, contentW / cellWidth)
        val newRows = max(1, contentH / cellHeight)
        val changed = newRows != appliedRows || newCols != appliedCols
        appliedRows = newRows
        appliedCols = newCols
        val s = session ?: return
        // 只在「尺寸真的变了」或调用方要求时 resize，避免 onSizeChanged 里反复触发
        if (!changed && !force) return
        s.resize(newRows, newCols)
        if (changed) invalidate()
    }

    // ------------------------------------------------------------------
    // 会话挂接
    // ------------------------------------------------------------------

    /** 挂接/解挂会话（null 解挂）。Lead 在 Activity 里调用。 */
    fun attach(session: TerminalSession?) {
        if (this.session === session) {
            if (session != null) {
                session.emulator.setAnsiPalette(colorScheme.ansi)
                session.emulator.scrollToBottom()
                applySize(force = true)
                invalidate()
            }
            return
        }
        // 解挂旧会话的刷新回调
        this.session?.emulator?.onChanged = null
        this.session = session
        if (session != null) {
            TerminalSessions.setContext(context)
            session.emulator.onChanged = { postInvalidateOnAnimation() }
            session.emulator.setAnsiPalette(colorScheme.ansi)
            session.emulator.scrollToBottom()
            applySize(force = true) // 用真实尺寸替换 Activity 给的占位 24x80
        }
        updateBlink()
        invalidate()
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colorScheme.bg)
        val s = session ?: return
        val emulator = s.emulator
        val snap = emulator.snapshot()
        if (snap.rows <= 0 || snap.cols <= 0) return

        val baseX = paddingLeft
        val baseY = paddingTop
        val top = snap.topRow

        canvas.save()
        canvas.clipRect(
            paddingLeft,
            paddingTop,
            max(paddingLeft + 1, width - paddingRight),
            max(paddingTop + 1, height - paddingBottom),
        )

        // 1) 背景：把同色相邻格合并成一个 run，减少 drawRect 次数
        fillPaint.style = Paint.Style.FILL
        for (r in 0 until snap.rows) {
            val rowY = (baseY + r * cellHeight).toFloat()
            val rowBottom = rowY + cellHeight
            var c = 0
            while (c < snap.cols) {
                val bg = effectiveBackground(emulator.cellAt(top + r, c))
                var end = c + 1
                while (end < snap.cols &&
                    effectiveBackground(emulator.cellAt(top + r, end)) == bg
                ) {
                    end++
                }
                if (bg != colorScheme.bg) {
                    fillPaint.color = bg
                    canvas.drawRect(
                        (baseX + c * cellWidth).toFloat(),
                        rowY,
                        (baseX + end * cellWidth).toFloat(),
                        rowBottom,
                        fillPaint,
                    )
                }
                c = end
            }
        }

        // 2) 文本：逐格 drawText（等宽字体，列坐标即 x 坐标）
        val baselineOffset = -fontMetrics.ascent
        for (r in 0 until snap.rows) {
            val y = (baseY + r * cellHeight + baselineOffset).toFloat()
            var c = 0
            while (c < snap.cols) {
                val cell = emulator.cellAt(top + r, c)
                val text = cell.text
                if (text.isEmpty() || text == " ") {
                    c += if (cell.wide) 2 else 1
                    continue
                }
                applyTextStyle(cell)
                canvas.drawText(text, (baseX + c * cellWidth).toFloat(), y, textPaint)
                c += if (cell.wide) 2 else 1
            }
        }

        // 3) 光标
        drawCursor(canvas, emulator, snap)

        // 4) 回看指示
        if (snap.scrolledBack) drawScrollIndicator(canvas, snap)

        canvas.restore()
    }

    /**
     * 模拟器存的是 **0xRRGGBB**（契约如此，alpha 位为 0），直接当 ARGB 用会得到
     * 全透明色 —— 2026-10-18 模拟器实测：16 色正常（配色方案的 ansi[] 本身带
     * 0xFF alpha），而 **256 色 / 真彩整段文字不可见**（抄自 xterm 公式的
     * 0xRRGGBB 被 Paint 当成 alpha=0）。
     * 修复放在渲染侧而不是解析侧：DEFAULT 是 -1（0xFFFFFFFF），若解析侧统一补
     * alpha，纯白就会与 DEFAULT 撞值，语义反而坏掉。
     */
    private fun opaque(color: Int): Int =
        if (color == TerminalCell.DEFAULT) color else color or 0xFF000000.toInt()

    private fun effectiveForeground(cell: TerminalCell): Int =
        if (cell.fg == TerminalCell.DEFAULT) colorScheme.fg else opaque(cell.fg)

    private fun effectiveBackground(cell: TerminalCell): Int {
        val fg = effectiveForeground(cell)
        val bg = if (cell.bg == TerminalCell.DEFAULT) colorScheme.bg else opaque(cell.bg)
        return if (cell.style and TerminalCell.INVERSE != 0) fg else bg
    }

    /** 根据单元格属性配置 textPaint（字体/颜色/伪粗斜体/下划线/删除线） */
    private fun applyTextStyle(cell: TerminalCell) {
        var fg = effectiveForeground(cell)
        val bg = if (cell.bg == TerminalCell.DEFAULT) colorScheme.bg else opaque(cell.bg)
        if (cell.style and TerminalCell.INVERSE != 0) fg = bg
        if (cell.style and TerminalCell.DIM != 0) fg = blend(fg, colorScheme.bg, DIM_FACTOR)
        textPaint.color = fg
        textPaint.isFakeBoldText = cell.style and TerminalCell.BOLD != 0
        textPaint.textSkewX = if (cell.style and TerminalCell.ITALIC != 0) ITALIC_SKEW else 0f
        textPaint.isUnderlineText = cell.style and TerminalCell.UNDERLINE != 0
        textPaint.isStrikeThruText = cell.style and TerminalCell.STRIKE != 0
    }

    private fun drawCursor(canvas: Canvas, emulator: TerminalEmulator, snap: TerminalEmulator.Snapshot) {
        if (!snap.cursorVisible || !hasFocus() || !cursorOn || snap.scrolledBack) return
        val col = if (snap.wrapPending) (snap.cols - 1).coerceAtLeast(0) else snap.cursorCol
        val rowOnScreen = snap.cursorAbsRow - snap.topRow
        if (rowOnScreen < 0 || rowOnScreen >= snap.rows) return

        val x = (paddingLeft + col * cellWidth).toFloat()
        val y = (paddingTop + rowOnScreen * cellHeight).toFloat()
        fillPaint.color = colorScheme.cursor
        fillPaint.style = Paint.Style.FILL

        when (snap.cursorShape) {
            3, 4 -> { // 下划线
                val h = max(2, cellHeight / 10).toFloat()
                canvas.drawRect(x, y + cellHeight - h, x + cellWidth, y + cellHeight, fillPaint)
            }
            5, 6 -> { // 竖线
                val w = max(2, cellWidth / 8).toFloat()
                canvas.drawRect(x, y, x + w, y + cellHeight, fillPaint)
            }
            else -> { // 块状：底色填充 + 用背景色重画字符
                canvas.drawRect(x, y, x + cellWidth, y + cellHeight, fillPaint)
                val cell = emulator.cellAt(snap.cursorAbsRow, col)
                if (cell.text.isNotEmpty() && cell.text != " ") {
                    textPaint.color = colorScheme.bg
                    textPaint.isFakeBoldText = cell.style and TerminalCell.BOLD != 0
                    textPaint.textSkewX = if (cell.style and TerminalCell.ITALIC != 0) ITALIC_SKEW else 0f
                    textPaint.isUnderlineText = false
                    textPaint.isStrikeThruText = false
                    val baseline = y - fontMetrics.ascent
                    canvas.drawText(cell.text, x, baseline, textPaint)
                }
            }
        }
    }

    /** 回看时右上角画「↓ N」：N = 距离最新输出还有多少行 */
    private fun drawScrollIndicator(canvas: Canvas, snap: TerminalEmulator.Snapshot) {
        val behind = snap.scrollbackRows - snap.rows - snap.topRow
        if (behind <= 0) return
        val label = "\u2193 $behind"
        val savedSize = textPaint.textSize
        textPaint.textSize = max(8f, fontSizePx * 0.8f)
        textPaint.color = colorScheme.fg
        textPaint.isFakeBoldText = true
        textPaint.textSkewX = 0f
        textPaint.isUnderlineText = false
        textPaint.isStrikeThruText = false

        val pad = 6f
        val textW = textPaint.measureText(label)
        val boxH = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent + pad
        val right = (width - paddingRight - pad).coerceAtLeast(pad + textW)
        val left = right - textW - pad * 2
        val top = paddingTop + pad

        fillPaint.color = Color.argb(
            210,
            Color.red(colorScheme.bg),
            Color.green(colorScheme.bg),
            Color.blue(colorScheme.bg),
        )
        canvas.drawRoundRect(left, top, right, top + boxH + pad, 6f, 6f, fillPaint)
        canvas.drawText(
            label,
            left + pad,
            top + pad - textPaint.fontMetrics.ascent,
            textPaint,
        )
        textPaint.textSize = savedSize
        textPaint.isFakeBoldText = false
    }

    /** 颜色向 toward 混合（用于 DIM） */
    private fun blend(color: Int, toward: Int, factor: Float): Int {
        val r = (Color.red(color) + (Color.red(toward) - Color.red(color)) * factor).toInt()
        val g = (Color.green(color) + (Color.green(toward) - Color.green(color)) * factor).toInt()
        val b = (Color.blue(color) + (Color.blue(toward) - Color.blue(color)) * factor).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    // ------------------------------------------------------------------
    // 触摸滚动
    // ------------------------------------------------------------------

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var lastY = 0f
    private var dragAccum = 0f
    private var dragging = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                lastY = event.y
                dragAccum = 0f
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 手指**下滑** = 内容下移 = 看更早的输出；手指**上滑** = 看更新的输出。
                //
                // ⚠️ 2026-10-18 方向修正：emulator.scrollBy(+n) 是把 viewportTopRow
                // 往**新**的方向挪（越界由它 coerce），所以「手指下滑要显示更早的
                // 输出」必须传负值。旧实现直接累加 dy，结果方向正好相反（上滑看历史、
                // 下滑回最新），与 Android 里所有滚动容器（以及用户习惯）都是反的。
                val dy = lastY - event.y
                lastY = event.y
                if (!dragging && abs(event.y - downY) > touchSlop) dragging = true
                if (dragging) {
                    dragAccum += dy
                    val rows = (dragAccum / cellHeight).toInt()
                    if (rows != 0) {
                        scrollBy(rows)
                        dragAccum -= rows * cellHeight
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    requestTerminalFocus()
                    performClick()
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ------------------------------------------------------------------
    // 公开操作（设计文档 §4.A + Lead 追加）
    // ------------------------------------------------------------------

    /** 滚动 deltaRows 行（正数 = 往更新的方向；越界由 emulator 收敛到 0..maxTop） */
    fun scrollBy(deltaRows: Int) {
        val emulator = session?.emulator ?: return
        emulator.scrollBy(deltaRows)
        invalidate()
    }

    /** 回到最新输出 */
    fun scrollToBottom() {
        session?.emulator?.scrollToBottom()
        invalidate()
    }

    /**
     * 发送一个物理键（KeyEvent.KEYCODE_* 对应的 VT 序列）。
     * 额外按键条（[TerminalExtraKeysView]）也走这里。
     */
    fun sendKey(keyCode: Int) {
        val seq = sequenceFor(keyCode) ?: return
        session?.write(seq)
    }

    /** 发送文本（IME 提交、按键条的 "/" "|" 等） */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        session?.write(text.toByteArray(Charsets.UTF_8))
    }

    /** 发送控制字符：'c' → 0x03（^C），'d' → 0x04，'l' → 0x0C… */
    fun sendControlChar(c: Char) {
        val code = c.code
        val ctrl = when {
            code in 'a'.code..'z'.code -> code - 'a'.code + 1
            code in 'A'.code..'Z'.code -> code - 'A'.code + 1
            code == ' '.code || code == '@'.code -> 0
            code == '['.code -> 27
            code == '\\'.code -> 28
            code == ']'.code -> 29
            code == '^'.code -> 30
            code == '_'.code -> 31
            else -> code and 0x1F
        }
        session?.write(byteArrayOf(ctrl.toByte()))
    }

    /** 抢焦点并弹软键盘 */
    fun requestTerminalFocus() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    // ------------------------------------------------------------------
    // 按键 → VT 序列
    // ------------------------------------------------------------------

    private fun sequenceFor(keyCode: Int): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER ->
            byteArrayOf(0x0D)
        KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F)
        KeyEvent.KEYCODE_FORWARD_DEL -> seq("${CSI}3~")
        KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
        KeyEvent.KEYCODE_TAB -> byteArrayOf(0x09)
        KeyEvent.KEYCODE_DPAD_UP -> cursorKey('A')
        KeyEvent.KEYCODE_DPAD_DOWN -> cursorKey('B')
        KeyEvent.KEYCODE_DPAD_RIGHT -> cursorKey('C')
        KeyEvent.KEYCODE_DPAD_LEFT -> cursorKey('D')
        KeyEvent.KEYCODE_MOVE_HOME -> cursorKey('H')
        KeyEvent.KEYCODE_MOVE_END -> cursorKey('F')
        KeyEvent.KEYCODE_PAGE_UP -> seq("${CSI}5~")
        KeyEvent.KEYCODE_PAGE_DOWN -> seq("${CSI}6~")
        KeyEvent.KEYCODE_INSERT -> seq("${CSI}2~")
        KeyEvent.KEYCODE_F1 -> seq("${CSI}11~")
        KeyEvent.KEYCODE_F2 -> seq("${CSI}12~")
        KeyEvent.KEYCODE_F3 -> seq("${CSI}13~")
        KeyEvent.KEYCODE_F4 -> seq("${CSI}14~")
        KeyEvent.KEYCODE_F5 -> seq("${CSI}15~")
        KeyEvent.KEYCODE_F6 -> seq("${CSI}17~")
        KeyEvent.KEYCODE_F7 -> seq("${CSI}18~")
        KeyEvent.KEYCODE_F8 -> seq("${CSI}19~")
        KeyEvent.KEYCODE_F9 -> seq("${CSI}20~")
        KeyEvent.KEYCODE_F10 -> seq("${CSI}21~")
        KeyEvent.KEYCODE_F11 -> seq("${CSI}23~")
        KeyEvent.KEYCODE_F12 -> seq("${CSI}24~")
        else -> null
    }

    private fun seq(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun isMappedKey(keyCode: Int): Boolean = sequenceFor(keyCode) != null

    /**
     * 方向键/Home/End：normal 模式是 CSI（`ESC [ A`），
     * application 模式（DECSET 1，vim/less 常用）是 SS3（`ESC O A`）。
     */
    private fun cursorKey(letter: Char): ByteArray {
        val app = session?.emulator?.cursorKeysApplication == true
        return if (app) "\u001bO$letter".toByteArray(Charsets.US_ASCII)
        else "\u001b[$letter".toByteArray(Charsets.US_ASCII)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 硬件 Ctrl+字母 → 控制字符
        if (event.metaState and KeyEvent.META_CTRL_ON != 0) {
            if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                val ch = 'a' + (keyCode - KeyEvent.KEYCODE_A)
                sendControlChar(ch)
                return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        if (isMappedKey(keyCode)) {
            sendKey(keyCode)
            return true
        }
        // 硬件键盘 / `adb shell input text` 走的是 KeyEvent 而不是 IME commitText：
        // 可打印字符必须从 unicodeChar 取出直接写 PTY，否则「物理键盘打得进去字、
        // input text 打不进去」这类割裂（2026-10-18 模拟器实测发现）。
        val uc = event.unicodeChar
        if (uc != 0) {
            val ch = uc.toChar()
            if (!ch.isISOControl()) {
                sendText(ch.toString())
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * `input text` 在部分版本会用 ACTION_MULTIPLE 一次性投递整串字符
     * （KeyEvent.getCharacters）。不处理的话这类注入的文本会被静默丢弃。
     */
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val chars = event.characters
        if (!chars.isNullOrEmpty()) {
            sendText(chars)
            return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // DOWN 已发送过序列：UP 只消费，避免系统提示音/重复发送
        if (keyCode != KeyEvent.KEYCODE_BACK && isMappedKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    // ------------------------------------------------------------------
    // 软键盘输入
    // ------------------------------------------------------------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // ⚠️ 这里**绝不能**用 TYPE_TEXT_VARIATION_VISIBLE_PASSWORD：
        // 那是给「显示明文密码」输入框的，vivo/小米等 ROM 会据此弹出**安全键盘**
        // （纯英文数字、无中文输入法）—— 用户实测「终端打不了中文、还弹出密码键盘」。
        // 终端只需要普通文本 + 组合输入（中文候选词）。
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return TerminalInputConnection()
    }

    /**
     * 软键盘 → PTY。终端没有可编辑文本区，所以 commitText 直接写字节；
     * 组合输入（setComposingText）不落地（选词提交时才通过 commitText 写入）。
     */
    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, false) {

        private var lastDeleteAt = 0L
        /**
         * IME 的“可编辑区”。
         *
         * 终端本身没有可编辑文本，但**中文/日文输入法必须看到一个真正的 Editable**
         * 才会进入组合输入（拼音→候选词）。BaseInputConnection(editable=false) 时
         * getEditable() 返回 null，很多 IME 直接退化成「只能打英文」——
         * 这正是 vivo 上中文打不出来的第二个原因（第一个是 VISIBLE_PASSWORD 弹安全键盘）。
         * 这里给 IME 一个进程内的缓冲，组合过程只更新它，选词提交时再写 PTY。
         */
        private val imeBuffer = android.text.SpannableStringBuilder()

        override fun getEditable(): android.text.Editable = imeBuffer

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            imeBuffer.clear()
            // IME 提交空串通常只是清组合态，不能当成输入发送
            val t = text?.toString().orEmpty()
            if (t.isNotEmpty()) sendText(t)
            return true
        }

        /** 中文/日文输入法：拼音阶段反复回调这里，选词后走 commitText */
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            imeBuffer.clear()
            if (!text.isNullOrEmpty()) imeBuffer.append(text)
            return true
        }

        override fun finishComposingText(): Boolean {
            imeBuffer.clear()
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean = true

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = imeBuffer

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

        override fun getSelectedText(flags: Int): CharSequence? = null

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // 组合输入中按退格 = 删候选拼音，不能给 PTY 发退格
            if (imeBuffer.isNotEmpty()) {
                imeBuffer.clear()
                return true
            }
            // 部分 IME 在按下/抬起时各调一次 → 20ms 内去重，避免一次退格删两个字符
            val now = System.currentTimeMillis()
            if (now - lastDeleteAt < DELETE_DEDUPE_MS) return true
            lastDeleteAt = now
            repeat(beforeLength.coerceIn(0, 16)) { sendKey(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength.coerceIn(0, 16)) { sendKey(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            if (event == null) return false
            if (event.action == KeyEvent.ACTION_DOWN) sendKey(event.keyCode)
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            sendKey(KeyEvent.KEYCODE_ENTER)
            return true
        }
    }

    // ------------------------------------------------------------------
    // 焦点 / 生命周期 / 光标闪烁
    // ------------------------------------------------------------------

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        updateBlink()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 从窗口分离时解掉了刷新回调（防止泄漏），重新挂上
        session?.emulator?.onChanged = { postInvalidateOnAnimation() }
        updateBlink()
    }

    override fun onDetachedFromWindow() {
        blinkHandler.removeCallbacks(blinkRunnable)
        session?.emulator?.onChanged = null
        super.onDetachedFromWindow()
    }

    private fun updateBlink() {
        blinkHandler.removeCallbacks(blinkRunnable)
        cursorOn = true
        val shouldBlink = isAttachedToWindow && hasFocus() && session != null
        if (shouldBlink) blinkHandler.postDelayed(blinkRunnable, BLINK_MS)
        invalidate()
    }

    private companion object {
        const val DEFAULT_FONT_SP = 13f
        const val BLINK_MS = 500L
        const val ITALIC_SKEW = -0.25f
        const val DIM_FACTOR = 0.45f
        const val DELETE_DEDUPE_MS = 20L
        const val CSI = "\u001b["
    }
}
