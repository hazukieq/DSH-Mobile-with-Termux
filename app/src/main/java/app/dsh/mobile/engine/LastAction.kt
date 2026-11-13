package app.dsh.mobile.engine

import android.content.Context

/** 持久记录最近设备动作与时间，供状态诊断读取；浮窗动作行另由 StatusOverlay 维护。 */
object LastAction {

    private const val PREFS = "last_action"
    private const val KEY_ACTION = "action"
    private const val KEY_AT = "at"

    data class Entry(val action: String, val at: Long)

    /** 记一次动作（每次 AI 派发动作时调用） */
    fun record(ctx: Context, action: String) {
        val a = action.trim()
        if (a.isEmpty()) return
        runCatching {
            prefs(ctx).edit()
                .putString(KEY_ACTION, a)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
        }
    }

    /** 读上次动作；没有则 null */
    fun read(ctx: Context): Entry? = runCatching {
        val p = prefs(ctx)
        val a = p.getString(KEY_ACTION, "").orEmpty()
        val t = p.getLong(KEY_AT, 0L)
        if (a.isBlank() || t <= 0L) null else Entry(a, t)
    }.getOrNull()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
