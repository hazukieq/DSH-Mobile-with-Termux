package app.dsh.mobile.term

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 终端「额外按键条」——手机软键盘上没有 Esc / Ctrl / Tab / 方向键，
 * 不加这一条，终端基本没法用（vim 退不出、补全用不了、历史翻不了）。
 *
 * 形态：横向可滚动的按键条，常驻屏幕底部；按键全部走 [TerminalView] 的
 * `sendKey` / `sendControlChar` / `sendText` 三条通道，不碰 PTY。
 *
 * 设计取舍：
 *  - **不做** Ctrl/Alt 粘滞态去改软键盘输入（那需要 TerminalView 的
 *    InputConnection 配合读取修饰符状态，耦合大且容易与 IME 抢事件）；
 *    改为「^C / ^D / ^L / ^Z 直给」+ Alt 前缀发送，覆盖绝大多数场景。
 *  - 按键文本保持 ASCII：仓库有 i18n 门禁（Kotlin 字面量禁 CJK），
 *    且终端按键本身是符号语言，不需要翻译。
 */
class TerminalExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

    /** 目标终端视图；由 Activity 在 attach 时设置 */
    var target: TerminalView? = null
        set(value) {
            field = value
            value?.onModifiersChanged = { updateModifierVisuals() }
            updateModifierVisuals()
            // 每次 attach/重建都把按键条复位到最左（CTRL/ALT/SHIFT 紧跟在 TAB 后面）
            post { scrollTo(0, 0) }
        }

    /**
     * 软键盘开/关回调（2026-10-18 加入）。
     *
     * 为什么需要：横屏 + 软键盘时，顶栏/标签条/按键条已占约 130px，键盘再吃掉
     * 大半屏，终端可视区几乎为 0（模拟器实测）。给一个显式的「收起键盘」键，
     * 用户才能在同一页里看输出；竖屏下同样是常用操作。
     */
    var onToggleKeyboard: (() -> Unit)? = null

    /** 粘滞修饰键的视图（用于高亮） */
    private var ctrlView: TextView? = null
    private var altView: TextView? = null
    private var shiftView: TextView? = null

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        isHorizontalScrollBarEnabled = false
        // 按键条本身不抢焦点、也不让子键抢焦点：否则焦点落到右侧某个键时
        // HorizontalScrollView 会自动滚到最右（实测一进终端就看到 PGDN/箭头，CTRL 被滚出屏幕）。
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setBackgroundColor(BG)
        addView(
            row,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
        buildKeys()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildKeys() {
        // 顺序按使用频率：先打断/补全，再编辑，最后移动
        key("ESC") { target?.sendKey(android.view.KeyEvent.KEYCODE_ESCAPE) }
        key("TAB") { target?.sendKey(android.view.KeyEvent.KEYCODE_TAB) }
        // 粘滞修饰键（v1.3.14）：点亮后作用于下一次输入，然后自动熄灭
        ctrlView = key("CTRL") { target?.toggleCtrl() }
        altView = key("ALT") { target?.toggleAlt() }
        shiftView = key("SHIFT") { target?.toggleShift() }
        key("^C") { target?.sendControlChar('c') }
        key("^D") { target?.sendControlChar('d') }
        key("^L") { target?.sendControlChar('l') }
        key("^Z") { target?.sendControlChar('z') }
        key("HOME") { target?.sendKey(android.view.KeyEvent.KEYCODE_MOVE_HOME) }
        key("END") { target?.sendKey(android.view.KeyEvent.KEYCODE_MOVE_END) }
        key("PGUP") { target?.sendKey(android.view.KeyEvent.KEYCODE_PAGE_UP) }
        key("PGDN") { target?.sendKey(android.view.KeyEvent.KEYCODE_PAGE_DOWN) }
        key("\u2191") { target?.sendKey(android.view.KeyEvent.KEYCODE_DPAD_UP) }
        key("\u2193") { target?.sendKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        key("\u2190") { target?.sendKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }
        key("\u2192") { target?.sendKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
        key("/") { target?.sendText("/") }
        key("|") { target?.sendText("|") }
        key("-") { target?.sendText("-") }
        key("_") { target?.sendText("_") }
        key("~") { target?.sendText("~") }
        key("KBD") { onToggleKeyboard?.invoke() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun key(label: String, action: () -> Unit): TextView {
        val tv = TextView(context).apply {
            text = label
            setTextColor(FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            background = pill()
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { action() }
            isClickable = true
            isFocusable = false
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(30),
        )
        lp.marginStart = dp(4)
        lp.marginEnd = dp(4)
        lp.topMargin = dp(5)
        lp.bottomMargin = dp(5)
        row.addView(tv, lp)
        return tv
    }

    /** 修饰键高亮：点亮时用强调色底 + 深色字，熄灭恢复默认胶囊 */
    private fun updateModifierVisuals() {
        val t = target
        fun apply(v: TextView?, on: Boolean) {
            v ?: return
            if (on) {
                v.background = GradientDrawable().apply {
                    cornerRadius = dp(7).toFloat()
                    setColor(ACTIVE_BG)
                    setStroke(dp(1), ACTIVE_BG)
                }
                v.setTextColor(0xFF0B0F13.toInt())
            } else {
                v.background = pill()
                v.setTextColor(FG)
            }
        }
        apply(ctrlView, t?.ctrlSticky == true)
        apply(altView, t?.altSticky == true)
        apply(shiftView, t?.shiftSticky == true)
    }

    private fun pill(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(7).toFloat()
        setColor(KEY_BG)
        setStroke(dp(1), KEY_STROKE)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: android.view.MotionEvent?): Boolean {
        // 左右滑动由 HorizontalScrollView 处理；纵向滑动交还给终端（避免误吞滚动手势）
        parent?.requestDisallowInterceptTouchEvent(true)
        return super.onTouchEvent(ev)
    }

    private val ACTIVE_BG = 0xFF7DD3FC.toInt()

    private companion object {
        const val BG = 0xFF141A21.toInt()
        const val KEY_BG = 0xFF1E2731.toInt()
        const val KEY_STROKE = 0xFF2C3844.toInt()
        const val FG = 0xFFCFD8E3.toInt()
    }
}
