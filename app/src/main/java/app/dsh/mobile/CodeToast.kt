package app.dsh.mobile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Activity 内的访问码提示条，无需系统悬浮窗或无障碍权限。 */
object CodeToast {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current: View? = null
    private var dismissTask: Runnable? = null

    /**
     * 在 [activity] 顶部显示"验证码已复制"提示条。
     * @param code 6 位验证码
     * @param host 可选的访问地址（显示在验证码下方）
     */
    fun show(activity: Activity, code: String, host: String? = null) {
        // show/dismiss 在主线程同步执行，防止旧的 dismiss 排在新窗口创建之后。
        mainThread {
            if (activity.isFinishing || activity.isDestroyed) return@mainThread
            runCatching {
                dismiss()
                val v = buildView(activity, code, host)
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.TOP
                    val m = dp(activity, 16).toInt()
                    setMargins(m, dp(activity, 64).toInt(), m, 0)
                }
                // 加在 Activity 内容之上（decorView 的 content 层）——无需任何权限
                activity.addContentView(v, lp)
                current = v
                android.util.Log.i("CodeToast", "access code notice shown")
                val task = Runnable { dismiss() }
                dismissTask = task
                main.postDelayed(task, 9_000L)
            }.onFailure {
                android.util.Log.w("CodeToast", "addContentView failed: ${it.message}")
                // 最后兜底：Toast（至少要有反馈）
                runCatching {
                    Toast.makeText(activity, activity.getString(R.string.code_toast_fallback, code), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun dismiss() {
        mainThread {
            dismissTask?.let { main.removeCallbacks(it) }
            dismissTask = null
            runCatching {
                (current?.parent as? ViewGroup)?.removeView(current)
            }
            current = null
        }
    }

    /** 已在主线程 → 直接执行；否则投递（避免调用方与内部双双 post 造成的时序错乱） */
    private inline fun mainThread(crossinline block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else main.post { block() }
    }

    private fun buildView(ctx: Context, code: String, host: String?): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16).toInt()
            setPadding(p, dp(ctx, 14).toInt(), p, dp(ctx, 14).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 18)
                setColor(0xF21F2733.toInt())      // 与悬浮窗/设置页同源深色
                setStroke(dp(ctx, 1).toInt(), 0x33FFFFFF)
            }
            elevation = dp(ctx, 8)
            isClickable = true                     // 吃掉点击，避免穿透到下面的行
            setOnClickListener { dismiss() }
        }

        card.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.code_copied_hint)
            setTextColor(0xFF9AA0A6.toInt())
            textSize = 12f
        })

        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 6).toInt(), 0, 0)

            addView(TextView(ctx).apply {
                text = code
                setTextColor(Color.WHITE)
                textSize = 28f
                typeface = Typeface.MONOSPACE       // 等宽，像系统验证码那样好读
                letterSpacing = 0.2f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.code_copy)
                setTextColor(0xFF7DD3FC.toInt())
                textSize = 15f
                val p = dp(ctx, 12).toInt()
                setPadding(p, dp(ctx, 8).toInt(), p, dp(ctx, 8).toInt())
                isClickable = true
                setOnClickListener {
                    runCatching {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("DSH LAN code", code))
                    }
                    Toast.makeText(ctx, ctx.getString(R.string.code_copied_toast, code), Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            })
        })

        if (!host.isNullOrBlank()) {
            card.addView(TextView(ctx).apply {
                text = host
                setTextColor(0xFF8A94A3.toInt())
                textSize = 11f
                setPadding(0, dp(ctx, 6).toInt(), 0, 0)
            })
        }
        return card
    }

    private fun dp(ctx: Context, v: Int): Float =
        v * ctx.resources.displayMetrics.density
}
