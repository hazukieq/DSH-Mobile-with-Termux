package app.dsh.mobile

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo

/**
 * 屏幕朝向偏好（v1.2.120）：三态 portrait / landscape / auto。
 *
 * 历史：此前只有一个布尔 `landscape`（dsh_ui prefs，默认 false=竖屏）。
 * 本次升级为字符串 `orientation` 三态；旧布尔键保留为迁移源——
 * `get()` 在新键缺失时读一次旧键，之后只写新键（不做破坏性清理）。
 */
enum class OrientationMode(val key: String) {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape"),
    AUTO("auto");

    companion object {
        fun fromKey(key: String?): OrientationMode = when (key) {
            "landscape" -> LANDSCAPE
            "auto" -> AUTO
            else -> PORTRAIT   // 含历史默认：旧实现 landscape=false 即竖屏
        }
    }
}

object OrientationPrefs {
    private const val KEY = "orientation"
    private const val KEY_LEGACY = "landscape"

    fun get(ctx: Context): OrientationMode {
        val p = prefs(ctx)
        val raw = p.getString(KEY, null)
        if (raw != null) return OrientationMode.fromKey(raw)
        // 迁移：老安装没有 orientation 键，回退到旧布尔（false=竖屏）
        return if (p.getBoolean(KEY_LEGACY, false)) OrientationMode.LANDSCAPE
        else OrientationMode.PORTRAIT
    }

    fun set(ctx: Context, mode: OrientationMode) {
        prefs(ctx).edit().putString(KEY, mode.key).apply()
    }

    /**
     * 偏好 → requestedOrientation。
     *
     * ⚠️ 竖屏必须显式 PORTRAIT；UNSPECIFIED 的语义是「交给系统/传感器」，
     * **不是**锁定竖屏（旧实现踩过这个坑，见 MainActivity 历史注释）。
     * auto 用 SENSOR 家族里的 USER：跟随物理传感器，同时尊重系统的
     * 「旋转锁定」总开关（用户锁旋转时保持当前朝向，不强行转）。
     */
    fun toActivityInfo(mode: OrientationMode): Int = when (mode) {
        OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_USER
    }

    /**
     * 把当前偏好落到某个 Activity 的 requestedOrientation。
     *
     * v2.9.110：原生页面（设置/关于/自定义指令）此前各自硬编码
     * `requestedOrientation = SCREEN_ORIENTATION_PORTRAIT`，导致「主界面横屏、
     * 一进设置就跳竖屏」（平板上必现，手机上因默认竖屏看不出来）。
     * 原生页面必须和主界面共用同一套策略，否则同一个 App 里出现两种朝向行为。
     */
    fun applyTo(activity: android.app.Activity) {
        activity.requestedOrientation = toActivityInfo(get(activity))
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
}
