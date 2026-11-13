package app.dsh.mobile

import android.content.Context

/** 页面缩放、工具栏、退出、文件管理器和朝向偏好；其他 store 由所属模块管理。 */
object DshPrefs {

    const val PREFS_UI = "dsh_ui"
    const val PREFS_EXIT = "dsh_exit"

    const val KEY_PAGE_SCALE = "page_scale"
    const val KEY_USER_EXITED = "user_exited"

    const val PAGE_SCALE_MIN = 50
    const val PAGE_SCALE_MAX = 120
    const val PAGE_SCALE_DEFAULT = 90

    // ---- 文件管理器偏好（用户选的默认；null = 每次让系统询问） ----

    const val KEY_FILE_MANAGER = "file_manager_pkg"

    fun fileManager(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getString(KEY_FILE_MANAGER, null)?.takeIf { it.isNotBlank() }

    /** pkg 传 null/空串 = 清除偏好，回到「每次让系统询问」。 */
    fun setFileManager(ctx: Context, pkg: String?) {
        ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE).edit().apply {
            if (pkg.isNullOrBlank()) remove(KEY_FILE_MANAGER) else putString(KEY_FILE_MANAGER, pkg)
        }.apply()
    }

    // ---- 页面缩放 ----

    fun pageScale(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getInt(KEY_PAGE_SCALE, PAGE_SCALE_DEFAULT)

    fun setPageScale(ctx: Context, value: Int) {
        val clamped = value.coerceIn(PAGE_SCALE_MIN, PAGE_SCALE_MAX)
        ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PAGE_SCALE, clamped).apply()
    }

    // ---- 顶部工具栏是否收起（v2.9.111） ----
    // 默认收起：顶部那条「引擎已就绪 · url / 重启 / 隐藏 / 齿轮」会一直占着 40dp，
    // 用户每次进 App 都得点一次「隐藏」才能全屏看网页 → 改成默认收起，
    // 想用按钮时点顶部小把手唤回；用户的显式选择会被记住。

    const val KEY_TOOLBAR_HIDDEN = "toolbar_hidden"

    fun toolbarHidden(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOOLBAR_HIDDEN, true)

    fun setToolbarHidden(ctx: Context, hidden: Boolean) {
        ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TOOLBAR_HIDDEN, hidden).apply()
    }

    // ---- 用户主动退出 ----

    fun userExited(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_EXIT, Context.MODE_PRIVATE)
            .getBoolean(KEY_USER_EXITED, false)

    fun setUserExited(ctx: Context, exited: Boolean) {
        ctx.getSharedPreferences(PREFS_EXIT, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USER_EXITED, exited).apply()
    }

    // ---- 屏幕朝向 ----

    fun orientationMode(ctx: Context): OrientationMode = OrientationPrefs.get(ctx)

    fun setOrientationMode(ctx: Context, mode: OrientationMode) =
        OrientationPrefs.set(ctx, mode)
}
