package app.dsh.mobile

import android.content.Context

/**
 * 应用所有 SharedPreferences 的统一入口（Phase 5）。
 *
 * 目前集中管理三块最常被跨文件读写的偏好：
 *  - 页面缩放 `dsh_ui/page_scale`（WebView 竖屏 viewport 缩放，50..120）
 *  - 用户主动退出 `dsh_exit/user_exited`（置位后引擎保活全部跳过）
 *  - 屏幕朝向：委托 [OrientationPrefs]（三态 + 旧布尔迁移逻辑保留在原处）
 *
 * 其它 store（dsh_a11y / dsh_priv / dsh_progress / app_locale）键名仍各自持有，
 * 后续可逐步并入这里；迁移时保持「旧键名不变、写入点收敛到本对象」。
 */
object DshPrefs {

    const val PREFS_UI = "dsh_ui"
    const val PREFS_EXIT = "dsh_exit"

    const val KEY_PAGE_SCALE = "page_scale"
    const val KEY_USER_EXITED = "user_exited"

    const val PAGE_SCALE_MIN = 50
    const val PAGE_SCALE_MAX = 120
    const val PAGE_SCALE_DEFAULT = 90

    // ---- 页面缩放 ----

    fun pageScale(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getInt(KEY_PAGE_SCALE, PAGE_SCALE_DEFAULT)

    fun setPageScale(ctx: Context, value: Int) {
        val clamped = value.coerceIn(PAGE_SCALE_MIN, PAGE_SCALE_MAX)
        ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PAGE_SCALE, clamped).apply()
    }

    // ---- 用户主动退出 ----

    fun userExited(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_EXIT, Context.MODE_PRIVATE)
            .getBoolean(KEY_USER_EXITED, false)

    fun setUserExited(ctx: Context, exited: Boolean) {
        ctx.getSharedPreferences(PREFS_EXIT, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USER_EXITED, exited).apply()
    }

    // ---- 屏幕朝向（过渡期委托原实现）----

    fun orientationMode(ctx: Context): OrientationMode = OrientationPrefs.get(ctx)

    fun setOrientationMode(ctx: Context, mode: OrientationMode) =
        OrientationPrefs.set(ctx, mode)
}
