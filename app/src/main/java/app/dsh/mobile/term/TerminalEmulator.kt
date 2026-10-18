package app.dsh.mobile.term

/**
 * 一个终端单元格的**不可变快照**（View 跨线程读取用）。
 *
 * 注意：内部用可变 `Cell` 存储（避免每写一个字符就分配对象），
 * 只有 [TerminalEmulator.cellAt] 才转成这个不可变类型给渲染层。
 */
data class TerminalCell(
    /** 一个码点（宽字占 2 列，首格是字本身）；"" = 宽字的续格 */
    val text: String,
    /** 0xRRGGBB，或 [DEFAULT]（= 用配色方案的默认色） */
    val fg: Int,
    /** 0xRRGGBB，或 [DEFAULT] */
    val bg: Int,
    /** 位掩码，见下方常量 */
    val style: Int,
    /** true = 宽字首格，占 2 列 */
    val wide: Boolean,
) {
    companion object {
        const val DEFAULT = -1

        const val BOLD = 1
        const val DIM = 1 shl 1
        const val ITALIC = 1 shl 2
        const val UNDERLINE = 1 shl 3
        const val INVERSE = 1 shl 4
        const val STRIKE = 1 shl 5

        /** 全默认空白格（共享单例，渲染层可直接比较引用） */
        val EMPTY = TerminalCell(" ", DEFAULT, DEFAULT, 0, false)
    }
}

/**
 * VT100 / xterm 兼容的终端模拟器：字节流 → 屏幕单元格。
 *
 * ## 结构
 *  - **主屏 + 备用屏**（DECSET 47/1047/1049），各自独立的 `rows × cols` 网格；
 *  - **滚动历史**（主屏，上限 [SCROLLBACK_LIMIT] 行）用 `ArrayDeque` 保存**已滚出
 *    屏幕的整行**；
 *  - 绝对行号模型：`0 .. scrollback.size-1` 是历史行，其后是当前屏幕行；
 *    视口 `viewportTopRow` 也用绝对行号，`scrollbackRows == scrollback.size + rows`。
 *    这样 View 只需要「从 viewportTopRow 画 rows 行」，回看/贴底都用同一套坐标。
 *
 * ## 线程模型（重要）
 *  - 解析（[append] / [resize] / [reset]）在 reader 线程；
 *  - 渲染在 UI 线程逐格读 [cellAt]。
 *  这里选择**单个 `synchronized` 锁**而不是双缓冲：一次 append 只持锁几百微秒，
 *  而重建一份 5000 行快照的代价远大于等锁。渲染侧建议每帧先取一次
 *  [snapshot] 固定 rows/topRow，再逐格 [cellAt]（允许极小的撕裂，换取不长时间
 *  阻塞解析线程）。回调（onChanged/onTitle/onBell）都在**锁外**触发，可以在回调里
 *  直接调用本类而不会死锁。
 *
 * ## 颜色约定（重要）
 * 单元格里存的是 **0xRRGGBB**（也就是 alpha 位为 0），只有 [TerminalCell.DEFAULT]
 * 是 -1。渲染侧必须自己补 `or 0xFF000000` 再交给 Paint —— 不能在这里统一补 alpha：
 * 纯白 0xFFFFFF 补完就是 0xFFFFFFFF == -1，会和 DEFAULT 哨兵撞值。
 * （2026-10-18 模拟器实测：16 色来自配色方案、自带 alpha，256 色/真彩不带，
 *   渲染侧不归一化时整段文字不可见。）
 *
 * ## 已知取舍
 *  - resize 不做 reflow（按契约允许）：行从**顶部**裁剪、底部补空行；列从右侧裁剪、
 *    右侧补空；
 *  - 鼠标上报（1000/1002/1003/1006）安全忽略；bracketed paste（2004）只记录状态；
 *  - DSR / DA 不回复（契约允许），依赖严格回复的 TUI 可能等待；
 *  - 备用屏没有滚动历史。
 */
class TerminalEmulator(initialRows: Int, initialCols: Int) {

    // ------------------------------------------------------------------
    // 内部可变单元格 / 网格
    // ------------------------------------------------------------------

    private class Cell {
        var text: String = " "
        var fg: Int = TerminalCell.DEFAULT
        var bg: Int = TerminalCell.DEFAULT
        var style: Int = 0
        var wide: Boolean = false

        fun copyFrom(o: Cell) {
            text = o.text
            fg = o.fg
            bg = o.bg
            style = o.style
            wide = o.wide
        }
    }

    private class Grid(rows: Int, cols: Int) {
        var rows: Int = rows
            private set
        var cols: Int = cols
            private set
        var cells: Array<Array<Cell>> = Array(rows) { Array(cols) { Cell() } }

        /**
         * 调整尺寸：行从顶部裁剪（终端是底部锚定的，提示符不能被切掉），
         * 列从右侧裁剪；新增区域补空白。不做 reflow。
         */
        fun resize(newRows: Int, newCols: Int) {
            if (newRows == rows && newCols == cols) return
            val next = Array(newRows) { Array(newCols) { Cell() } }
            val rowOffset = if (newRows < rows) rows - newRows else 0
            val copyRows = minOf(rows, newRows)
            val copyCols = minOf(cols, newCols)
            for (r in 0 until copyRows) {
                val src = cells[r + rowOffset]
                val dst = next[r]
                for (c in 0 until copyCols) dst[c].copyFrom(src[c])
            }
            cells = next
            rows = newRows
            cols = newCols
        }
    }

    // ------------------------------------------------------------------
    // 屏幕 / 光标 / 模式
    // ------------------------------------------------------------------

    private val lock = Any()

    @Volatile
    private var _rows: Int = initialRows.coerceIn(1, MAX_ROWS)

    @Volatile
    private var _cols: Int = initialCols.coerceIn(1, MAX_COLS)

    private var main = Grid(_rows, _cols)
    private var alt = Grid(_rows, _cols)
    @Volatile
    private var altActive = false

    private val screen: Grid
        get() = if (altActive) alt else main

    /** 已滚出屏幕的历史行（仅主屏产生） */
    private val scrollback = ArrayDeque<Array<Cell>>()

    private var curRow = 0
    private var curCol = 0
    private var wrapPendingFlag = false

    @Volatile
    private var cursorShown = true

    @Volatile
    private var cursorShapeCode = 0 // DECSCUSR：0/1 闪烁块 2 稳定块 3 闪烁下划线 4 稳定下划线 5/6 竖线

    private var scrollTop = 0
    private var scrollBottom = _rows - 1

    /**
     * 鼠标上报（v1.3.15）：TUI（dsh-tui / vim / less）靠它把滚轮事件转成
     * 「滚动自己的内容」。模式值：0 关闭、1=1000 点击、2=1002 拖动、3=1003 移动。
     * 编码：1006 SGR（`ESC [ < b ; x ; y M`），否则回退 X10（`ESC [ M` + 3 字节）。
     */
    var mouseTrackingMode = 0
        private set
    var mouseSgrEncoding = false
        private set
    var mouseUtf8Encoding = false
        private set

    /** 应用是否请求了鼠标上报（任一模式） */
    val mouseReporting: Boolean get() = mouseTrackingMode != 0

    private var originModeOn = false
    private var autoWrapOn = true
    private var insertModeOn = false
    private var newlineModeOn = false

    @Volatile
    private var cursorKeysAppOn = false

    @Volatile
    private var keypadAppOn = false

    @Volatile
    private var bracketedPasteOn = false

    private var curFg = TerminalCell.DEFAULT
    private var curBg = TerminalCell.DEFAULT
    private var curStyle = 0

    private var tabStops = BooleanArray(_cols) { it % TAB_WIDTH == 0 }

    /** 视口顶部绝对行（0 = 最早的历史行） */
    @Volatile
    private var viewTop = 0

    // ---- 保存的光标（DECSC/DECRC，主/备屏各一份） ----
    private class SavedCursor {
        var row = 0
        var col = 0
        var fg = TerminalCell.DEFAULT
        var bg = TerminalCell.DEFAULT
        var style = 0
        var origin = false
    }

    private val savedMain = SavedCursor()
    private val savedAlt = SavedCursor()

    private fun currentSaved(): SavedCursor = if (altActive) savedAlt else savedMain

    // ------------------------------------------------------------------
    // 公开回调
    // ------------------------------------------------------------------

    /** 内容变化（每次 [append] 最多一次、每次 [resize]/[reset] 一次） */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /** OSC 0/2 改标题 */
    @Volatile
    var onTitle: ((String) -> Unit)? = null

    /** BEL（0x07） */
    @Volatile
    var onBell: (() -> Unit)? = null

    @Volatile
    private var _title: String = ""

    var title: String
        get() = _title
        set(value) {
            if (value == _title) return
            _title = value
            onTitle?.invoke(value)
        }

    // ------------------------------------------------------------------
    // 公开只读状态（契约 §4.A）
    // ------------------------------------------------------------------

    val rows: Int get() = _rows
    val cols: Int get() = _cols

    /** 总绝对行数 = 历史行 + 屏幕行（备用屏时等于屏幕行） */
    val scrollbackRows: Int get() = synchronized(lock) { scrollbackRowsLocked() }

    /** 视口顶部绝对行 */
    val viewportTopRow: Int get() = viewTop

    /** 光标绝对行（主屏 = 历史行数 + 屏内行；备用屏 = 屏内行） */
    val cursorAbsRow: Int get() = synchronized(lock) { cursorAbsRowLocked() }

    val cursorCol: Int get() = synchronized(lock) { curCol }

    val cursorVisible: Boolean get() = cursorShown

    /** 自动换行挂起（写满最后一列后，下一个字符才真正换行） */
    val wrapPending: Boolean get() = synchronized(lock) { wrapPendingFlag }

    val cursorKeysApplication: Boolean get() = cursorKeysAppOn
    val keypadApplication: Boolean get() = keypadAppOn
    val bracketedPaste: Boolean get() = bracketedPasteOn
    val cursorShape: Int get() = cursorShapeCode
    val alternateScreen: Boolean get() = altActive

    val atBottom: Boolean get() = synchronized(lock) { viewTop >= maxTopLocked() }
    val maxViewportTopRow: Int get() = synchronized(lock) { maxTopLocked() }

    /** 渲染用一致快照（rows/cols/topRow/光标一次取齐） */
    class Snapshot internal constructor(
        val rows: Int,
        val cols: Int,
        val topRow: Int,
        val cursorAbsRow: Int,
        val cursorCol: Int,
        val cursorVisible: Boolean,
        val wrapPending: Boolean,
        val altScreen: Boolean,
        val scrolledBack: Boolean,
        val scrollbackRows: Int,
        val cursorShape: Int,
    )

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            rows = _rows,
            cols = _cols,
            topRow = viewTop,
            cursorAbsRow = cursorAbsRowLocked(),
            cursorCol = curCol,
            cursorVisible = cursorShown,
            wrapPending = wrapPendingFlag,
            altScreen = altActive,
            scrolledBack = viewTop < maxTopLocked(),
            scrollbackRows = scrollbackRowsLocked(),
            cursorShape = cursorShapeCode,
        )
    }

    // ------------------------------------------------------------------
    // 公开操作
    // ------------------------------------------------------------------

    /**
     * 喂入一段 PTY 输出。
     * UTF-8 多字节序列可以跨多次调用（解码状态保存在实例里）。
     */
    fun append(data: ByteArray, off: Int, count: Int) {
        if (count <= 0 || off < 0 || off + count > data.size) return
        var processed = false
        // 锁内收集、锁外回调（回调里再调用本类也不会死锁）
        var bell: Boolean
        var titleDirty: Boolean

        synchronized(lock) {
            var i = off
            val end = off + count
            while (i < end) {
                feedByte(data[i].toInt() and 0xFF)
                i++
                processed = true
            }
            if (processed) {
                // 解析过程中长度/历史可能变化：保证视口仍在合法区间
                viewTop = viewTop.coerceIn(0, maxTopLocked())
            }
            bell = bellPending
            bellPending = false
            titleDirty = titlePending
            titlePending = false
        }

        if (!processed) return
        onChanged?.invoke()
        if (titleDirty) onTitle?.invoke(_title)
        if (bell) onBell?.invoke()
    }

    fun append(data: ByteArray) = append(data, 0, data.size)

    /**
     * 调整终端尺寸。行从顶部裁剪、底部补空；列右侧裁剪、右侧补空（不做 reflow）。
     */
    fun resize(newRows: Int, newCols: Int) {
        val r = newRows.coerceIn(1, MAX_ROWS)
        val c = newCols.coerceIn(1, MAX_COLS)
        if (r == _rows && c == _cols) return

        synchronized(lock) {
            val oldRows = _rows
            val oldCols = _cols
            val rowOffset = if (r < oldRows) oldRows - r else 0

            main.resize(r, c)
            alt.resize(r, c)
            _rows = r
            _cols = c

            curRow = (curRow - rowOffset).coerceIn(0, r - 1)
            curCol = curCol.coerceIn(0, c - 1)
            wrapPendingFlag = false

            // 滚动区域：贴底设置（覆盖全屏）跟随新尺寸；显式区域保留并夹紧
            scrollTop = scrollTop.coerceIn(0, r - 1)
            val wasFullScreen = scrollBottom >= oldRows - 1
            scrollBottom = if (wasFullScreen) r - 1 else scrollBottom.coerceIn(0, r - 1)
            if (scrollBottom <= scrollTop) {
                scrollTop = 0
                scrollBottom = r - 1
            }

            // 制表位：保留重叠区间，新增列恢复默认 8 列
            val nextStops = BooleanArray(c) { it % TAB_WIDTH == 0 }
            val keep = minOf(oldCols, c)
            for (i in 0 until keep) nextStops[i] = tabStops[i]
            tabStops = nextStops

            viewTop = viewTop.coerceIn(0, maxTopLocked())
        }
        onChanged?.invoke()
    }

    /** RIS（ESC c）：清屏、清历史、复位所有模式（标题保留，符合 xterm 行为） */
    fun reset() {
        synchronized(lock) {
            hardResetLocked()
        }
        onChanged?.invoke()
    }

    /** 滚动视口；贴底时停止跟随输出 */
    fun scrollBy(deltaRows: Int) {
        if (deltaRows == 0) return
        synchronized(lock) {
            viewTop = (viewTop + deltaRows).coerceIn(0, maxTopLocked())
        }
    }

    /** 回到最新输出（贴底） */
    fun scrollToBottom() {
        synchronized(lock) { viewTop = maxTopLocked() }
    }

    /**
     * 更新 ANSI 调色板（0..15 项，来自 View 的配色方案）。
     *
     * 只影响**之后**写入的 SGR 30-37/40-47/90-97/100-107 颜色：
     * 已写入单元格里存的是解析时的 RGB 值（契约要求 0xRRGGBB），
     * 换主题不会重刷历史内容 —— 这是刻意的取舍，避免为了换肤保存索引。
     */
    fun setAnsiPalette(colors: IntArray) {
        synchronized(lock) {
            val n = minOf(16, colors.size)
            for (i in 0 until n) ansiPalette[i] = colors[i]
        }
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /**
     * 读取绝对行 [absRow]、列 [col] 的单元格快照。
     * 越界/空白返回 [TerminalCell.EMPTY]。
     */
    fun cellAt(absRow: Int, col: Int): TerminalCell = synchronized(lock) {
        if (col < 0 || col >= _cols) return@synchronized TerminalCell.EMPTY
        val line = lineAt(absRow) ?: return@synchronized TerminalCell.EMPTY
        if (col >= line.size) return@synchronized TerminalCell.EMPTY
        snapshotCell(line[col])
    }

    private fun lineAt(absRow: Int): Array<Cell>? {
        if (altActive) {
            return if (absRow in 0 until _rows) alt.cells[absRow] else null
        }
        if (absRow < 0) return null
        val sb = scrollback.size
        if (absRow < sb) return scrollback[absRow]
        val r = absRow - sb
        return if (r in 0 until _rows) main.cells[r] else null
    }

    private fun snapshotCell(c: Cell): TerminalCell {
        if (!c.wide && c.text == " " && c.fg == TerminalCell.DEFAULT &&
            c.bg == TerminalCell.DEFAULT && c.style == 0
        ) {
            return TerminalCell.EMPTY
        }
        return TerminalCell(c.text, c.fg, c.bg, c.style, c.wide)
    }

    // ------------------------------------------------------------------
    // 绝对行 / 视口工具（调用方必须已持锁；*Locked 后缀即此约定）
    // ------------------------------------------------------------------

    private fun scrollbackRowsLocked(): Int =
        if (altActive) _rows else scrollback.size + _rows

    private fun maxTopLocked(): Int = (scrollbackRowsLocked() - _rows).coerceAtLeast(0)

    private fun cursorAbsRowLocked(): Int =
        if (altActive) curRow else scrollback.size + curRow

    // ------------------------------------------------------------------
    // UTF-8 增量解码
    // ------------------------------------------------------------------

    private var u8Need = 0
    private var u8Cp = 0
    private var u8Min = 0

    /**
     * 逐字节喂入 UTF-8 解码器；完整码点直接交给 [processCp]。
     * 序列被截断时先吐 U+FFFD，再把当前字节当新起始字节重新处理（最多递归一层）。
     */
    private fun feedByte(b: Int) {
        if (u8Need == 0) {
            when {
                b < 0x80 -> processCp(b)
                b in 0xC2..0xDF -> {
                    u8Cp = b and 0x1F
                    u8Need = 1
                    u8Min = 0x80
                }
                b in 0xE0..0xEF -> {
                    u8Cp = b and 0x0F
                    u8Need = 2
                    u8Min = 0x800
                }
                b in 0xF0..0xF4 -> {
                    u8Cp = b and 0x07
                    u8Need = 3
                    u8Min = 0x10000
                }
                // 0x80..0xC1 / 0xF5..0xFF：非法起始字节
                else -> processCp(0xFFFD)
            }
            return
        }
        if (b and 0xC0 != 0x80) {
            u8Need = 0
            u8Cp = 0
            u8Min = 0
            processCp(0xFFFD)
            feedByte(b)
            return
        }
        u8Cp = (u8Cp shl 6) or (b and 0x3F)
        u8Need--
        if (u8Need == 0) {
            val cp = u8Cp
            val valid = cp in u8Min..0x10FFFF && cp !in 0xD800..0xDFFF
            u8Cp = 0
            u8Min = 0
            processCp(if (valid) cp else 0xFFFD)
        }
    }

    // ------------------------------------------------------------------
    // VT 状态机
    // ------------------------------------------------------------------

    private var state = S_GROUND
    private var escInter = 0

    /** G0..G3 是否被指定为 DEC 特殊图形（线框）字符集。 */
    private var g0DecSpecial = false
    private var g1DecSpecial = false
    private var g2DecSpecial = false
    private var g3DecSpecial = false

    /** GL 当前取自 G1（SO），或下一次单字符用 G2/G3（SS2/SS3，SS2=2 / SS3=3）。 */
    private var glFromG1 = false
    private var singleShift = 0

    private val csiParams = IntArray(MAX_CSI_PARAMS)
    private var csiCount = 0
    private var csiCur = -1
    private var csiPrivate = 0
    private var csiInter = 0
    private var csiTrailingSep = false

    private val osc = StringBuilder()
    private var bellPending = false
    private var titlePending = false

    private var lastRow = 0
    private var lastCol = 0
    private var lastCp = ' '.code

    private fun processCp(cp: Int) {
        when (state) {
            S_GROUND -> when {
                cp == 0x1B -> {
                    state = S_ESC
                    escInter = 0
                }
                cp < 0x20 -> control(cp)
                cp == 0x7F -> Unit // DEL：忽略
                else -> printCp(cp)
            }
            S_ESC -> escDispatch(cp)
            S_ESC_INT -> {
                // ESC ( B / ESC # 8 之类的两字节序列
                when (escInter) {
                    '('.code -> g0DecSpecial = cp == '0'.code
                    ')'.code -> g1DecSpecial = cp == '0'.code
                    '*'.code -> g2DecSpecial = cp == '0'.code
                    '+'.code -> g3DecSpecial = cp == '0'.code
                    '#'.code -> if (cp == '8'.code) decaln()
                }
                state = S_GROUND
            }
            S_CSI -> csiByte(cp)
            S_OSC -> when (cp) {
                0x1B -> state = S_OSC_ESC
                0x07 -> finishOsc()
                0x18, 0x1A -> { // CAN / SUB 取消
                    osc.setLength(0)
                    state = S_GROUND
                }
                else -> if (osc.length < OSC_MAX) osc.append(String(Character.toChars(cp)))
            }
            S_OSC_ESC -> {
                if (cp == '\\'.code) finishOsc() else state = S_GROUND
            }
            S_DCS -> when (cp) {
                0x1B -> state = S_DCS_ESC
                0x07 -> state = S_GROUND // 容错：BEL 也终止
            }
            S_DCS_ESC -> state = if (cp == '\\'.code) S_GROUND else S_DCS
        }
    }

    private fun control(cp: Int) {
        when (cp) {
            C0_BEL -> bellPending = true
            C0_BS -> {
                wrapPendingFlag = false
                if (curCol > 0) curCol--
            }
            C0_HT -> tabForward(1)
            C0_LF, C0_VT, C0_FF -> {
                wrapPendingFlag = false
                lineFeed()
                if (newlineModeOn) curCol = 0
            }
            C0_CR -> {
                curCol = 0
                wrapPendingFlag = false
            }
            // SO/SI：GL 在 G1 / G0 之间切换（DEC 线框字符集靠这条路径生效）。
            C0_SO -> glFromG1 = true
            C0_SI -> glFromG1 = false
        }
    }

    // ------------------------------------------------------------------
    // ESC 分派
    // ------------------------------------------------------------------

    private fun escDispatch(cp: Int) {
        state = S_GROUND
        when (cp) {
            '['.code -> {
                resetCsi()
                state = S_CSI
            }
            ']'.code -> {
                osc.setLength(0)
                state = S_OSC
            }
            // DCS / SOS / PM / APC：整段跳过（xterm 也不处理内容）
            'P'.code, 'X'.code, '^'.code, '_'.code -> state = S_DCS
            '7'.code -> saveCursor()
            '8'.code -> restoreCursor()
            'D'.code -> {
                wrapPendingFlag = false
                lineFeed()
            }
            'E'.code -> {
                wrapPendingFlag = false
                curCol = 0
                lineFeed()
            }
            'M'.code -> {
                wrapPendingFlag = false
                reverseIndex()
            }
            'H'.code -> if (curCol in 0 until _cols) tabStops[curCol] = true
            'c'.code -> hardResetLocked()
            '='.code -> keypadAppOn = true
            '>'.code -> keypadAppOn = false
            // SS2 / SS3：下一个可打印字符临时用 G2 / G3
            'N'.code -> singleShift = 2
            'O'.code -> singleShift = 3
            // 字符集指定 / DECALN 等两字节 ESC 序列：吃掉下一个字节
            '('.code, ')'.code, '*'.code, '+'.code, '-'.code, '.'.code, '/'.code, '#'.code -> {
                escInter = cp
                state = S_ESC_INT
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------
    // CSI 字节累积
    // ------------------------------------------------------------------

    private fun resetCsi() {
        csiCount = 0
        csiCur = -1
        csiPrivate = 0
        csiInter = 0
        csiTrailingSep = false
    }

    private fun pushCsiParam(v: Int) {
        if (csiCount < MAX_CSI_PARAMS) {
            csiParams[csiCount] = v
        }
        csiCount++
    }

    private fun csiByte(cp: Int) {
        when {
            cp in 0x30..0x39 -> {
                val d = cp - 0x30
                csiCur = if (csiCur < 0) d else minOf(csiCur * 10 + d, 999_999)
                csiTrailingSep = false
            }
            cp == ';'.code || cp == ':'.code -> {
                // ':' 是子参数分隔（SGR 38:5:n 等），统一按 ';' 展开 —— 对终端颜色
                // 语义无损；4:3（花线下划线）会退化成 4;3，属可接受的取舍。
                pushCsiParam(csiCur)
                csiCur = -1
                csiTrailingSep = true
            }
            cp == '?'.code || cp == '>'.code || cp == '<'.code || cp == '='.code -> {
                if (csiCount == 0 && csiCur < 0 && csiPrivate == 0) csiPrivate = cp
            }
            cp in 0x20..0x2F -> csiInter = cp
            cp in 0x40..0x7E -> {
                if (csiCur >= 0 || csiTrailingSep) pushCsiParam(csiCur)
                csiCur = -1
                csiDispatch(cp)
                resetCsi()
                state = S_GROUND
            }
            // CSI 内的 C0 立即执行（ECMA-48）
            cp < 0x20 -> control(cp)
        }
    }

    private fun csiParam(i: Int): Int =
        if (i in 0 until csiCount) csiParams[i] else -1

    /** 光标位移类参数：缺省/0 视为 1 */
    private fun n1(i: Int): Int {
        val v = csiParam(i)
        return if (v <= 0) 1 else v
    }

    /** 模式类参数：缺省视为 0 */
    private fun n0(i: Int): Int {
        val v = csiParam(i)
        return if (v < 0) 0 else v
    }

    // ------------------------------------------------------------------
    // CSI 分派
    // ------------------------------------------------------------------

    private fun csiDispatch(final: Int) {
        when (final) {
            'A'.code -> moveCursor(-n1(0), 0)
            'B'.code -> moveCursor(n1(0), 0)
            'C'.code -> moveCursor(0, n1(0))
            'D'.code -> moveCursor(0, -n1(0))
            'E'.code -> {
                moveCursor(n1(0), 0)
                curCol = 0
            }
            'F'.code -> {
                moveCursor(-n1(0), 0)
                curCol = 0
            }
            'G'.code -> { // CHA
                wrapPendingFlag = false
                curCol = (n1(0) - 1).coerceIn(0, _cols - 1)
            }
            'H'.code, 'f'.code -> cursorPosition(n1(0), n1(1))
            'J'.code -> eraseInDisplay(n0(0))
            'K'.code -> eraseInLine(n0(0))
            'L'.code -> insertLines(n1(0))
            'M'.code -> deleteLines(n1(0))
            'P'.code -> deleteChars(n1(0))
            '@'.code -> insertChars(n1(0))
            'S'.code -> scrollUp(scrollTop, scrollBottom, n1(0), pushHistory = false)
            'T'.code -> scrollDown(scrollTop, scrollBottom, n1(0))
            'X'.code -> eraseChars(n1(0))
            'Z'.code -> tabBackward(n1(0))
            'I'.code -> tabForward(n1(0))
            'b'.code -> repeatLastChar(n1(0))
            'c'.code -> Unit // DA：不回复
            'd'.code -> { // VPA
                wrapPendingFlag = false
                val base = if (originModeOn) scrollTop else 0
                val max = if (originModeOn) scrollBottom else _rows - 1
                curRow = (base + n1(0) - 1).coerceIn(base, max)
            }
            'e'.code -> moveCursor(n1(0), 0)
            'g'.code -> { // TBC
                when (n0(0)) {
                    0 -> if (curCol in 0 until _cols) tabStops[curCol] = false
                    3 -> for (i in tabStops.indices) tabStops[i] = false
                }
            }
            'h'.code -> setModes(true)
            'l'.code -> setModes(false)
            'm'.code -> sgr()
            'n'.code -> Unit // DSR：不回复（契约允许）
            'q'.code -> if (csiInter == ' '.code) cursorShapeCode = n0(0).coerceIn(0, 6)
            'r'.code -> setScrollRegion()
            's'.code -> saveCursor()
            't'.code -> Unit // 窗口操作：忽略
            'u'.code -> restoreCursor()
            '`'.code -> {
                wrapPendingFlag = false
                curCol = (n1(0) - 1).coerceIn(0, _cols - 1)
            }
            'a'.code -> moveCursor(0, n1(0))
            'p'.code -> if (csiInter == '!'.code) softResetLocked()
            else -> Unit
        }
    }

    private fun moveCursor(dRow: Int, dCol: Int) {
        wrapPendingFlag = false
        curRow = (curRow + dRow).coerceIn(0, _rows - 1)
        curCol = (curCol + dCol).coerceIn(0, _cols - 1)
    }

    private fun cursorPosition(row1: Int, col1: Int) {
        wrapPendingFlag = false
        if (originModeOn) {
            curRow = (scrollTop + row1 - 1).coerceIn(scrollTop, scrollBottom)
        } else {
            curRow = (row1 - 1).coerceIn(0, _rows - 1)
        }
        curCol = (col1 - 1).coerceIn(0, _cols - 1)
    }

    private fun setScrollRegion() {
        val top = (n1(0) - 1).coerceIn(0, _rows - 1)
        val bottomParam = csiParam(1)
        val bottom = (if (bottomParam <= 0) _rows else bottomParam).let {
            (it - 1).coerceIn(0, _rows - 1)
        }
        if (top >= bottom) return
        scrollTop = top
        scrollBottom = bottom
        curRow = if (originModeOn) scrollTop else 0
        curCol = 0
        wrapPendingFlag = false
    }

    private fun setModes(enabled: Boolean) {
        if (csiPrivate == '?'.code) {
            for (i in 0 until csiCount) setPrivateMode(csiParams[i], enabled)
        } else {
            for (i in 0 until csiCount) {
                when (csiParams[i]) {
                    4 -> insertModeOn = enabled
                    20 -> newlineModeOn = enabled
                    // 12 (SRM 本地回显)：终端自己已回显，忽略
                    else -> Unit
                }
            }
        }
    }

    private fun setPrivateMode(mode: Int, enabled: Boolean) {
        when (mode) {
            1 -> cursorKeysAppOn = enabled
            4 -> insertModeOn = enabled
            6 -> {
                originModeOn = enabled
                curRow = if (enabled) scrollTop else 0
                curCol = 0
                wrapPendingFlag = false
            }
            7 -> autoWrapOn = enabled
            12 -> Unit // 本地回显：终端天然开启
            20 -> newlineModeOn = enabled
            25 -> cursorShown = enabled
            47 -> switchAlternate(enabled, clearAlt = false)
            1047 -> switchAlternate(enabled, clearAlt = true)
            1048 -> if (enabled) saveCursor() else restoreCursor()
            1049 -> {
                if (enabled) {
                    if (!altActive) {
                        saveCursor()
                        switchAlternate(true, clearAlt = true)
                        curRow = 0
                        curCol = 0
                        wrapPendingFlag = false
                    }
                } else if (altActive) {
                    switchAlternate(false, clearAlt = false)
                    restoreCursor()
                }
            }
            // 鼠标上报（v1.3.15）：TUI 的滚轮滚动依赖它。
            // 1000/1002/1003 只记录模式，真正的上报由 TerminalView 在触摸滚动时发出；
            // 1005（UTF-8 坐标）/1015（urxvt）本实现不编码，仅记录以免误报支持。
            1000 -> mouseTrackingMode = if (enabled) 1 else 0
            1002 -> mouseTrackingMode = if (enabled) 2 else 0
            1003 -> mouseTrackingMode = if (enabled) 3 else 0
            1005 -> mouseUtf8Encoding = enabled
            1006 -> mouseSgrEncoding = enabled
            1015 -> Unit
            2004 -> bracketedPasteOn = enabled
            else -> Unit
        }
    }

    private fun switchAlternate(enable: Boolean, clearAlt: Boolean) {
        if (enable) {
            if (!altActive) {
                altActive = true
                if (clearAlt) clearGrid(alt)
                viewTop = 0
                scrollTop = 0
                scrollBottom = _rows - 1
            } else if (clearAlt) {
                clearGrid(alt)
            }
        } else if (altActive) {
            altActive = false
            if (clearAlt) clearGrid(alt)
            viewTop = 0
            scrollTop = 0
            scrollBottom = _rows - 1
        }
    }

    // ------------------------------------------------------------------
    // SGR
    // ------------------------------------------------------------------

    private fun sgr() {
        if (csiCount == 0) {
            resetAttrs()
            return
        }
        var i = 0
        while (i < csiCount) {
            val p = if (csiParams[i] < 0) 0 else csiParams[i]
            when {
                p == 0 -> resetAttrs()
                p == 1 -> curStyle = curStyle or TerminalCell.BOLD
                p == 2 -> curStyle = curStyle or TerminalCell.DIM
                p == 3 -> curStyle = curStyle or TerminalCell.ITALIC
                p == 4 -> curStyle = curStyle or TerminalCell.UNDERLINE
                p == 5 || p == 6 -> Unit // 闪烁：不实现（部分 TUI 会开，画面上忽略）
                p == 7 -> curStyle = curStyle or TerminalCell.INVERSE
                p == 9 -> curStyle = curStyle or TerminalCell.STRIKE
                p == 21 -> curStyle = curStyle or TerminalCell.UNDERLINE // 双下划线视作下划线
                p == 22 -> curStyle = curStyle and (TerminalCell.BOLD or TerminalCell.DIM).inv()
                p == 23 -> curStyle = curStyle and TerminalCell.ITALIC.inv()
                p == 24 -> curStyle = curStyle and TerminalCell.UNDERLINE.inv()
                p == 27 -> curStyle = curStyle and TerminalCell.INVERSE.inv()
                p == 29 -> curStyle = curStyle and TerminalCell.STRIKE.inv()
                p in 30..37 -> curFg = ansiPalette[p - 30]
                p == 38 -> i = applyExtendedColor(i, foreground = true)
                p == 39 -> curFg = TerminalCell.DEFAULT
                p in 40..47 -> curBg = ansiPalette[p - 40]
                p == 48 -> i = applyExtendedColor(i, foreground = false)
                p == 49 -> curBg = TerminalCell.DEFAULT
                p in 90..97 -> curFg = ansiPalette[p - 90 + 8]
                p in 100..107 -> curBg = ansiPalette[p - 100 + 8]
            }
            i++
        }
    }

    /**
     * SGR 38/48 扩展色：`;5;n`（256 色）或 `;2;r;g;b`（真彩）。
     * 兼容 `38:2:<colorspace>:r:g:b`（颜色空间参数为空时会被解码成 -1）。
     * @return 已消费的最后一个参数下标
     */
    private fun applyExtendedColor(index: Int, foreground: Boolean): Int {
        val mode = csiParam(index + 1)
        if (mode == 5) {
            val n = csiParam(index + 2)
            if (n in 0..255) {
                if (foreground) curFg = ansiPalette[n] else curBg = ansiPalette[n]
            }
            return index + 2
        }
        if (mode == 2) {
            var base = index + 2
            if (csiParam(base) < 0) base++ // 跳过 38:2::r:g:b 的空颜色空间参数
            val r = csiParam(base)
            val g = csiParam(base + 1)
            val b = csiParam(base + 2)
            if (r in 0..255 && g in 0..255 && b in 0..255) {
                val rgb = (r shl 16) or (g shl 8) or b
                if (foreground) curFg = rgb else curBg = rgb
            }
            return base + 2
        }
        return index
    }

    private fun resetAttrs() {
        curFg = TerminalCell.DEFAULT
        curBg = TerminalCell.DEFAULT
        curStyle = 0
    }

    // ------------------------------------------------------------------
    // 打印
    // ------------------------------------------------------------------

    /** 当前字符集是否为 DEC 特殊图形（curses/dialog 的线框字符）。 */
    private fun decSpecialActive(): Boolean {
        if (singleShift == 2) return g2DecSpecial
        if (singleShift == 3) return g3DecSpecial
        return if (glFromG1) g1DecSpecial else g0DecSpecial
    }

    /**
     * DEC Special Graphics（VT100 ACS）映射表：把 0x60..0x7E 的 ASCII 换成线框/符号。
     * dialog、htop、menuconfig 这类 ncurses 程序在非 UTF-8 locale 下会走这条路径，
     * 不映射就会看到 `lqqqk / x / mqqqj` 这种“字母版线框”。
     */
    private fun decSpecialChar(cp: Int): Int {
        if (cp < 0x5F || cp > 0x7E) return cp
        return when (cp) {
            0x5F -> ' '.code          // blank
            0x60 -> 0x25C6            // ◆
            0x61 -> 0x2592            // ▒
            0x62 -> 0x2409            // ␉
            0x63 -> 0x240C            // ␌
            0x64 -> 0x240D            // ␍
            0x65 -> 0x240A            // ␊
            0x66 -> 0x00B0            // °
            0x67 -> 0x00B1            // ±
            0x68 -> 0x2424            // ␤
            0x69 -> 0x240B            // ␋
            0x6A -> 0x2518            // ┘
            0x6B -> 0x2510            // ┐
            0x6C -> 0x250C            // ┌
            0x6D -> 0x2514            // └
            0x6E -> 0x253C            // ┼
            0x6F -> 0x23BA            // ⎺
            0x70 -> 0x23BB            // ⎻
            0x71 -> 0x2500            // ─
            0x72 -> 0x23BC            // ⎼
            0x73 -> 0x23BD            // ⎽
            0x74 -> 0x251C            // ├
            0x75 -> 0x2524            // ┤
            0x76 -> 0x2534            // ┴
            0x77 -> 0x252C            // ┬
            0x78 -> 0x2502            // │
            0x79 -> 0x2264            // ≤
            0x7A -> 0x2265            // ≥
            0x7B -> 0x03C0            // π
            0x7C -> 0x2260            // ≠
            0x7D -> 0x00A3            // £
            0x7E -> 0x00B7            // ·
            else -> cp
        }
    }

    private fun printCp(cp: Int) {
        val special = decSpecialActive()
        val ch = if (special) decSpecialChar(cp) else cp
        singleShift = 0
        val w = wcwidth(ch)
        if (w == 0) {
            appendCombining(ch)
            return
        }
        if (wrapPendingFlag) {
            wrapPendingFlag = false
            curCol = 0
            lineFeed()
        }
        if (w == 2) printWide(ch) else printNarrow(ch)
        lastCp = ch
    }

    private fun printNarrow(cp: Int) {
        if (curCol > _cols - 1) curCol = _cols - 1
        if (insertModeOn) insertChars(1)
        val row = screen.cells[curRow]
        val cell = row[curCol]
        cell.text = String(Character.toChars(cp))
        cell.fg = curFg
        cell.bg = curBg
        cell.style = curStyle
        cell.wide = false
        lastRow = curRow
        lastCol = curCol
        if (curCol >= _cols - 1) {
            curCol = _cols - 1
            if (autoWrapOn) wrapPendingFlag = true
        } else {
            curCol++
        }
    }

    private fun printWide(cp: Int) {
        if (_cols < 2) {
            printNarrow(cp)
            return
        }
        // 宽字不能只占最后一列：先擦掉残影，再换行（或退到 cols-2）
        if (curCol >= _cols - 1) {
            val row = screen.cells[curRow]
            clearCell(row[_cols - 1])
            if (autoWrapOn) {
                wrapPendingFlag = false
                curCol = 0
                lineFeed()
            } else {
                curCol = _cols - 2
            }
        }
        if (curCol < 0) curCol = 0
        if (insertModeOn) insertChars(2)
        val row = screen.cells[curRow]
        val text = String(Character.toChars(cp))
        row[curCol].apply {
            this.text = text
            fg = curFg
            bg = curBg
            style = curStyle
            wide = true
        }
        row[curCol + 1].apply {
            this.text = ""
            fg = curFg
            bg = curBg
            style = curStyle
            wide = false
        }
        lastRow = curRow
        lastCol = curCol
        curCol += 2
        if (curCol >= _cols) {
            curCol = _cols - 1
            if (autoWrapOn) wrapPendingFlag = true
        }
    }

    private fun appendCombining(cp: Int) {
        // 组合符/零宽字符：附加到上一个被打印的单元格（宽字则归到首格）
        val row = screen.cells.getOrNull(lastRow) ?: return
        val cell = row.getOrNull(lastCol) ?: return
        cell.text += String(Character.toChars(cp))
    }

    private fun repeatLastChar(n: Int) {
        repeat(n.coerceIn(1, 4096)) { printCp(lastCp) }
    }

    // ------------------------------------------------------------------
    // 行操作
    // ------------------------------------------------------------------

    /** 换行：在滚动区底部触发上滚，否则光标下移一行 */
    private fun lineFeed() {
        if (curRow == scrollBottom) {
            val full = scrollTop == 0 && scrollBottom == _rows - 1
            scrollUp(scrollTop, scrollBottom, 1, pushHistory = full)
        } else if (curRow < _rows - 1) {
            curRow++
        }
    }

    /** 反向换行（RI）：顶部触发下滚 */
    private fun reverseIndex() {
        if (curRow == scrollTop) {
            scrollDown(scrollTop, scrollBottom, 1)
        } else if (curRow > 0) {
            curRow--
        }
    }

    /**
     * 区域内上滚 n 行。
     * @param pushHistory 只有**整屏**滚动（LF/IND 在底边）才把顶部行存入回滚历史；
     *                    IL/SU 等区域内滚动不产生历史行（与 xterm 一致）。
     */
    private fun scrollUp(top: Int, bottom: Int, n0: Int, pushHistory: Boolean) {
        val height = bottom - top + 1
        if (height <= 0) return
        val n = minOf(n0, height)
        if (n <= 0) return
        val grid = screen
        val wasAtBottom = viewTop >= maxTopLocked()

        if (pushHistory && !altActive && top == 0 && bottom == _rows - 1) {
            repeat(n) {
                scrollback.addLast(copyRow(grid.cells[0]))
            }
            if (scrollback.size > SCROLLBACK_LIMIT) {
                val removed = scrollback.size - SCROLLBACK_LIMIT
                repeat(removed) { scrollback.removeFirst() }
                viewTop = (viewTop - removed).coerceAtLeast(0)
            }
        }

        // 交换行引用（O(1)），最后把腾出来的行清空
        for (r in top..bottom - n) {
            val tmp = grid.cells[r]
            grid.cells[r] = grid.cells[r + n]
            grid.cells[r + n] = tmp
        }
        for (r in bottom - n + 1..bottom) blankRow(grid, r)

        if (pushHistory && wasAtBottom) viewTop = maxTopLocked()
    }

    /** 区域内下滚 n 行（RI / IL 用），从不产生历史行 */
    private fun scrollDown(top: Int, bottom: Int, n0: Int) {
        val height = bottom - top + 1
        if (height <= 0) return
        val n = minOf(n0, height)
        if (n <= 0) return
        val grid = screen
        for (r in bottom downTo top + n) {
            val tmp = grid.cells[r]
            grid.cells[r] = grid.cells[r - n]
            grid.cells[r - n] = tmp
        }
        for (r in top until top + n) blankRow(grid, r)
    }

    private fun insertLines(n: Int) {
        if (curRow < scrollTop || curRow > scrollBottom) return
        scrollDown(curRow, scrollBottom, n)
    }

    private fun deleteLines(n: Int) {
        if (curRow < scrollTop || curRow > scrollBottom) return
        scrollUp(curRow, scrollBottom, n, pushHistory = false)
    }

    private fun insertChars(n: Int) {
        val row = screen.cells[curRow]
        val shift = minOf(n, _cols - curCol)
        if (shift <= 0) return
        var c = _cols - 1
        while (c >= curCol + shift) {
            row[c].copyFrom(row[c - shift])
            c--
        }
        for (k in curCol until curCol + shift) clearCell(row[k])
    }

    private fun deleteChars(n: Int) {
        val row = screen.cells[curRow]
        val shift = minOf(n, _cols - curCol)
        if (shift <= 0) return
        for (c in curCol until _cols - shift) row[c].copyFrom(row[c + shift])
        for (c in _cols - shift until _cols) clearCell(row[c])
    }

    private fun eraseChars(n: Int) {
        val row = screen.cells[curRow]
        val to = minOf(curCol + n - 1, _cols - 1)
        eraseRange(row, curCol, to)
    }

    // ------------------------------------------------------------------
    // 擦除
    // ------------------------------------------------------------------

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseRange(screen.cells[curRow], curCol, _cols - 1)
                for (r in curRow + 1 until _rows) blankRow(screen, r)
            }
            1 -> {
                for (r in 0 until curRow) blankRow(screen, r)
                eraseRange(screen.cells[curRow], 0, curCol)
            }
            2 -> for (r in 0 until _rows) blankRow(screen, r)
            3 -> {
                for (r in 0 until _rows) blankRow(screen, r)
                if (!altActive) {
                    scrollback.clear()
                    viewTop = 0
                }
            }
        }
    }

    private fun eraseInLine(mode: Int) {
        val row = screen.cells[curRow]
        when (mode) {
            0 -> eraseRange(row, curCol, _cols - 1)
            1 -> eraseRange(row, 0, curCol)
            2 -> eraseRange(row, 0, _cols - 1)
        }
    }

    private fun eraseRange(row: Array<Cell>, from: Int, to: Int) {
        var a = from.coerceIn(0, _cols - 1)
        var b = to.coerceIn(0, _cols - 1)
        if (a > b) return
        // 擦掉宽字的半边会把另一半变成孤儿续格：把范围扩展到完整字宽
        if (a > 0 && row[a].text.isEmpty()) a--
        if (b < _cols - 1 && row[b].wide) b++
        for (c in a..b) clearCell(row[c])
    }

    private fun blankRow(grid: Grid, row: Int) {
        val line = grid.cells.getOrNull(row) ?: return
        for (c in line.indices) clearCell(line[c])
    }

    private fun clearGrid(grid: Grid) {
        for (r in 0 until grid.rows) {
            val line = grid.cells[r]
            for (c in line.indices) {
                val cell = line[c]
                cell.text = " "
                cell.fg = TerminalCell.DEFAULT
                cell.bg = TerminalCell.DEFAULT
                cell.style = 0
                cell.wide = false
            }
        }
    }

    /** 擦除用：空白格保留当前背景色（xterm 语义） */
    private fun clearCell(cell: Cell) {
        cell.text = " "
        cell.fg = TerminalCell.DEFAULT
        cell.bg = curBg
        cell.style = 0
        cell.wide = false
    }

    private fun copyRow(line: Array<Cell>): Array<Cell> =
        Array(line.size) { i -> Cell().also { c -> c.copyFrom(line[i]) } }

    // ------------------------------------------------------------------
    // 光标保存 / 恢复 / 复位
    // ------------------------------------------------------------------

    private fun saveCursor() {
        val s = currentSaved()
        s.row = curRow
        s.col = curCol
        s.fg = curFg
        s.bg = curBg
        s.style = curStyle
        s.origin = originModeOn
    }

    private fun restoreCursor() {
        val s = currentSaved()
        curRow = s.row.coerceIn(0, _rows - 1)
        curCol = s.col.coerceIn(0, _cols - 1)
        curFg = s.fg
        curBg = s.bg
        curStyle = s.style
        originModeOn = s.origin
        wrapPendingFlag = false
    }

    private fun softResetLocked() {
        resetAttrs()
        insertModeOn = false
        originModeOn = false
        autoWrapOn = true
        cursorShown = true
        cursorKeysAppOn = false
        scrollTop = 0
        scrollBottom = _rows - 1
        curRow = curRow.coerceIn(0, _rows - 1)
        curCol = curCol.coerceIn(0, _cols - 1)
        wrapPendingFlag = false
    }

    private fun hardResetLocked() {
        clearGrid(main)
        clearGrid(alt)
        scrollback.clear()
        altActive = false
        curRow = 0
        curCol = 0
        wrapPendingFlag = false
        cursorShown = true
        cursorShapeCode = 0
        scrollTop = 0
        scrollBottom = _rows - 1
        originModeOn = false
        autoWrapOn = true
        insertModeOn = false
        newlineModeOn = false
        cursorKeysAppOn = false
        keypadAppOn = false
        bracketedPasteOn = false
        resetAttrs()
        tabStops = BooleanArray(_cols) { it % TAB_WIDTH == 0 }
        savedMain.row = 0; savedMain.col = 0; savedMain.origin = false
        savedAlt.row = 0; savedAlt.col = 0; savedAlt.origin = false
        viewTop = 0
        state = S_GROUND
        resetCsi()
        osc.setLength(0)
        u8Need = 0
        u8Cp = 0
        u8Min = 0
        bellPending = false
        titlePending = false
    }

    // ------------------------------------------------------------------
    // 制表 / OSC / DECALN
    // ------------------------------------------------------------------

    private fun tabForward(n: Int) {
        wrapPendingFlag = false
        repeat(n) {
            var c = curCol + 1
            while (c < _cols && !tabStops[c]) c++
            curCol = if (c >= _cols) _cols - 1 else c
        }
    }

    private fun tabBackward(n: Int) {
        wrapPendingFlag = false
        repeat(n) {
            var c = curCol - 1
            while (c > 0 && !tabStops[c]) c--
            curCol = c.coerceAtLeast(0)
        }
    }

    private fun finishOsc() {
        val s = osc.toString()
        osc.setLength(0)
        state = S_GROUND
        if (s.isEmpty()) return
        val sep = s.indexOf(';')
        // OSC 0/2 都是「设置窗口标题」；1（图标名）以及 52（剪贴板）等一律忽略
        if (sep > 0) {
            val code = s.substring(0, sep).trim()
            if (code == "0" || code == "2") {
                val value = s.substring(sep + 1)
                val clean = buildString(value.length) {
                    for (ch in value) {
                        if (ch.code >= 0x20 && ch.code != 0x7F) append(ch)
                    }
                }
                if (clean != _title) {
                    _title = clean
                    titlePending = true
                }
            }
        }
    }

    /** DECALN（ESC # 8）：整屏填 'E'，xterm 的对齐自检画面 */
    private fun decaln() {
        val grid = screen
        for (r in 0 until _rows) {
            val line = grid.cells[r]
            for (c in 0 until _cols) {
                val cell = line[c]
                cell.text = "E"
                cell.fg = TerminalCell.DEFAULT
                cell.bg = TerminalCell.DEFAULT
                cell.style = 0
                cell.wide = false
            }
        }
        curRow = 0
        curCol = 0
        wrapPendingFlag = false
    }

    // ------------------------------------------------------------------
    // 宽度判定（wcwidth 风格，覆盖 CJK / emoji / 组合符）
    // ------------------------------------------------------------------

    private fun wcwidth(cp: Int): Int {
        if (cp == 0) return 0
        if (cp < 0x20 || (cp in 0x7F..0x9F)) return 0
        if (isZeroWidth(cp)) return 0
        if (isWide(cp)) return 2
        return 1
    }

    private fun isWide(cp: Int): Boolean = when (cp) {
        in 0x1100..0x115F -> true                 // Hangul Jamo 初声
        0x2329, 0x232A -> true                    // 〈 〉
        in 0x2E80..0x303E -> true                 // CJK 部首 / 符号 / 标点
        in 0x3041..0x33FF -> true                 // 假名 / 注音 / CJK 兼容
        in 0x3400..0x4DBF -> true                 // CJK 扩展 A
        in 0x4E00..0x9FFF -> true                 // CJK 统一表意
        in 0xA000..0xA4CF -> true                 // 彝文
        in 0xA960..0xA97F -> true                 // Hangul Jamo 扩展 A
        in 0xAC00..0xD7A3 -> true                 // Hangul 音节
        in 0xF900..0xFAFF -> true                 // CJK 兼容表意
        in 0xFE10..0xFE19 -> true                 // 竖排标点
        in 0xFE30..0xFE6F -> true                 // CJK 兼容形式
        in 0xFF00..0xFF60 -> true                 // 全角 ASCII / 半角片假名
        in 0xFFE0..0xFFE6 -> true                 // 全角符号
        in 0x1B000..0x1B001 -> true
        0x1F004 -> true
        0x1F0CF -> true
        0x1F18E -> true
        in 0x1F191..0x1F19A -> true
        in 0x1F200..0x1F202 -> true
        in 0x1F210..0x1F23B -> true
        in 0x1F240..0x1F248 -> true
        in 0x1F250..0x1F251 -> true
        in 0x1F300..0x1F64F -> true               // 杂项符号 / 表情（主区）
        in 0x1F680..0x1F6FF -> true               // 交通 / 地图符号
        in 0x1F900..0x1F9FF -> true               // 补充符号 / 表情
        in 0x1FA70..0x1FAFF -> true               // 符号扩展 A
        in 0x20000..0x3FFFD -> true               // CJK 扩展 B+
        else -> false
    }

    private fun isZeroWidth(cp: Int): Boolean = when (cp) {
        in 0x0300..0x036F -> true                 // 组合变音符
        in 0x0483..0x0489 -> true
        in 0x0591..0x05BD -> true
        0x05BF -> true
        in 0x05C1..0x05C2 -> true
        in 0x05C4..0x05C5 -> true
        0x05C7 -> true
        in 0x0610..0x061A -> true
        in 0x064B..0x065F -> true
        0x0670 -> true
        in 0x06D6..0x06DC -> true
        in 0x06DF..0x06E4 -> true
        in 0x06E7..0x06E8 -> true
        in 0x06EA..0x06ED -> true
        in 0x0730..0x074A -> true
        in 0x07A6..0x07B0 -> true
        in 0x07EB..0x07F3 -> true
        in 0x0816..0x0819 -> true
        in 0x081B..0x0823 -> true
        in 0x0825..0x0827 -> true
        in 0x0829..0x082D -> true
        in 0x0859..0x085B -> true
        in 0x08D3..0x08E1 -> true
        in 0x08E3..0x0902 -> true
        0x093A -> true
        0x093C -> true
        in 0x0941..0x0948 -> true
        0x094D -> true
        in 0x0951..0x0957 -> true
        in 0x0962..0x0963 -> true
        0x0981 -> true
        0x09BC -> true
        in 0x09C1..0x09C4 -> true
        0x09CD -> true
        in 0x09E2..0x09E3 -> true
        in 0x0A01..0x0A02 -> true
        0x0A3C -> true
        in 0x0A41..0x0A42 -> true
        in 0x0A47..0x0A48 -> true
        in 0x0A4B..0x0A4D -> true
        0x0A51 -> true
        in 0x0A70..0x0A71 -> true
        0x0A75 -> true
        in 0x0A81..0x0A82 -> true
        0x0ABC -> true
        in 0x0AC1..0x0AC5 -> true
        in 0x0AC7..0x0AC8 -> true
        0x0ACD -> true
        in 0x0AE2..0x0AE3 -> true
        0x0B01 -> true
        0x0B3C -> true
        0x0B3F -> true
        in 0x0B41..0x0B44 -> true
        0x0B4D -> true
        0x0B56 -> true
        in 0x0B62..0x0B63 -> true
        0x0B82 -> true
        0x0BC0 -> true
        0x0BCD -> true
        0x0C00 -> true
        in 0x0C3E..0x0C40 -> true
        in 0x0C46..0x0C48 -> true
        in 0x0C4A..0x0C4D -> true
        in 0x0C55..0x0C56 -> true
        in 0x0C62..0x0C63 -> true
        0x0C81 -> true
        0x0CBC -> true
        0x0CBF -> true
        0x0CC6 -> true
        in 0x0CCC..0x0CCD -> true
        in 0x0CE2..0x0CE3 -> true
        in 0x0D00..0x0D01 -> true
        in 0x0D3B..0x0D3C -> true
        in 0x0D41..0x0D44 -> true
        0x0D4D -> true
        in 0x0D62..0x0D63 -> true
        0x0DCA -> true
        in 0x0DD2..0x0DD4 -> true
        0x0DD6 -> true
        0x0E31 -> true
        in 0x0E34..0x0E3A -> true
        in 0x0E47..0x0E4E -> true
        0x0EB1 -> true
        in 0x0EB4..0x0EBC -> true
        in 0x0EC8..0x0ECD -> true
        in 0x0F18..0x0F19 -> true
        0x0F35 -> true
        0x0F37 -> true
        0x0F39 -> true
        in 0x0F71..0x0F7E -> true
        in 0x0F80..0x0F84 -> true
        in 0x0F86..0x0F87 -> true
        in 0x0F8D..0x0F97 -> true
        in 0x0F99..0x0FBC -> true
        0x0FC6 -> true
        in 0x102D..0x1030 -> true
        in 0x1032..0x1037 -> true
        in 0x1039..0x103A -> true
        in 0x103D..0x103E -> true
        in 0x1058..0x1059 -> true
        in 0x105E..0x1060 -> true
        in 0x1071..0x1074 -> true
        0x1082 -> true
        in 0x1085..0x1086 -> true
        0x108D -> true
        0x109D -> true
        in 0x135D..0x135F -> true
        in 0x1712..0x1714 -> true
        in 0x1732..0x1734 -> true
        in 0x1752..0x1753 -> true
        in 0x1772..0x1773 -> true
        in 0x17B4..0x17B5 -> true
        in 0x17B7..0x17BD -> true
        0x17C6 -> true
        in 0x17C9..0x17D3 -> true
        0x17DD -> true
        in 0x180B..0x180D -> true
        in 0x1885..0x1886 -> true
        0x18A9 -> true
        in 0x1920..0x1922 -> true
        in 0x1927..0x1928 -> true
        0x1932 -> true
        in 0x1939..0x193B -> true
        in 0x1A17..0x1A18 -> true
        0x1A1B -> true
        0x1A56 -> true
        in 0x1A58..0x1A5E -> true
        0x1A60 -> true
        0x1A62 -> true
        in 0x1A65..0x1A6C -> true
        in 0x1A73..0x1A7C -> true
        0x1A7F -> true
        in 0x1AB0..0x1AFF -> true                 // 组合用变音扩展
        in 0x1B00..0x1B03 -> true
        0x1B34 -> true
        in 0x1B36..0x1B3A -> true
        0x1B3C -> true
        0x1B42 -> true
        in 0x1B6B..0x1B73 -> true
        in 0x1B80..0x1B81 -> true
        in 0x1BA2..0x1BA5 -> true
        in 0x1BA8..0x1BA9 -> true
        in 0x1BAB..0x1BAD -> true
        0x1BE6 -> true
        in 0x1BE8..0x1BE9 -> true
        0x1BED -> true
        in 0x1BEF..0x1BF1 -> true
        in 0x1C2C..0x1C33 -> true
        in 0x1C36..0x1C37 -> true
        in 0x1CD0..0x1CD2 -> true
        in 0x1CD4..0x1CE0 -> true
        in 0x1CE2..0x1CE8 -> true
        0x1CED -> true
        0x1CF4 -> true
        in 0x1CF8..0x1CF9 -> true
        in 0x1DC0..0x1DFF -> true                 // 组合用变音补充
        in 0x200B..0x200F -> true                 // 零宽空格 / 方向标记 / ZWJ
        in 0x2028..0x202E -> true
        in 0x2060..0x2064 -> true
        in 0x20D0..0x20F0 -> true                 // 组合用符号
        in 0x2CEF..0x2CF1 -> true
        0x2D7F -> true
        in 0x2DE0..0x2DFF -> true
        in 0x302A..0x302D -> true
        in 0x3099..0x309A -> true                 // 日文浊点
        in 0xA66F..0xA672 -> true
        in 0xA674..0xA67D -> true
        in 0xA69E..0xA69F -> true
        in 0xA6F0..0xA6F1 -> true
        0xA802 -> true
        0xA806 -> true
        0xA80B -> true
        in 0xA825..0xA826 -> true
        0xA8C4 -> true
        in 0xA8E0..0xA8F1 -> true
        in 0xA926..0xA92D -> true
        in 0xA947..0xA951 -> true
        in 0xA980..0xA982 -> true
        0xA9B3 -> true
        in 0xA9B6..0xA9B9 -> true
        0xA9BC -> true
        0xA9E5 -> true
        in 0xAA29..0xAA2E -> true
        in 0xAA31..0xAA32 -> true
        in 0xAA35..0xAA36 -> true
        0xAA43 -> true
        0xAA4C -> true
        0xAA7C -> true
        0xAAB0 -> true
        in 0xAAB2..0xAAB4 -> true
        in 0xAAB7..0xAAB8 -> true
        in 0xAABE..0xAABF -> true
        0xAAC1 -> true
        in 0xAAEC..0xAAED -> true
        0xAAF6 -> true
        0xABE5 -> true
        0xABE8 -> true
        0xABED -> true
        0xFB1E -> true
        in 0xFE00..0xFE0F -> true                 // 变体选择符 VS1-16
        in 0xFE20..0xFE2F -> true                 // 组合用半角符号
        0xFEFF -> true                            // BOM / 零宽不换行空格
        in 0xE0100..0xE01EF -> true               // 变体选择符补充
        else -> false
    }

    // ------------------------------------------------------------------
    // ANSI 调色板（16 色可被 View 的配色方案覆盖；16-255 用 xterm 标准公式）
    // ------------------------------------------------------------------

    private val ansiPalette = IntArray(256).also { p ->
        for (i in 0 until 16) p[i] = XTERM_16[i]
        // 6x6x6 色立方
        val steps = intArrayOf(0x00, 0x5F, 0x87, 0xAF, 0xD7, 0xFF)
        for (i in 0 until 216) {
            val r = steps[i / 36]
            val g = steps[(i / 6) % 6]
            val b = steps[i % 6]
            p[16 + i] = (r shl 16) or (g shl 8) or b
        }
        // 24 级灰阶
        for (i in 0 until 24) {
            val v = 8 + i * 10
            p[232 + i] = (v shl 16) or (v shl 8) or v
        }
    }

    private companion object {
        const val MAX_ROWS = 512
        const val MAX_COLS = 1024
        const val TAB_WIDTH = 8

        /** 主屏回滚历史上限（契约要求 >= 5000） */
        const val SCROLLBACK_LIMIT = 5000

        const val OSC_MAX = 4096
        const val MAX_CSI_PARAMS = 32

        // 解析状态
        const val S_GROUND = 0
        const val S_ESC = 1
        const val S_ESC_INT = 2
        const val S_CSI = 3
        const val S_OSC = 4
        const val S_OSC_ESC = 5
        const val S_DCS = 6
        const val S_DCS_ESC = 7

        // C0 控制符
        const val C0_BEL = 0x07
        const val C0_BS = 0x08
        const val C0_HT = 0x09
        const val C0_LF = 0x0A
        const val C0_VT = 0x0B
        const val C0_FF = 0x0C
        const val C0_CR = 0x0D
        const val C0_SO = 0x0E
        const val C0_SI = 0x0F

        /** xterm 默认 16 色（View 的配色方案会通过 setAnsiPalette 覆盖） */
        val XTERM_16 = intArrayOf(
            0x000000, 0xCD0000, 0x00CD00, 0xCDCD00,
            0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
            0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00,
            0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
        )
    }
}
