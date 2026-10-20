package app.dsh.mobile.engine

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import java.lang.ref.WeakReference

/**
 * App 前台/当前 Activity 状态（v1.3.30）。
 *
 * Agent 需要知道「现在 App 在前台吗、用户看到的是哪个页面」才能判断：
 *  - 原生设置/终端是否已经打开（而不是只有 startActivity 回执）；
 *  - 截图/点击类操作是否有意义；
 *  - WebView 是否处于前台（CDP 页面可见）。
 *
 * 各 Activity 在 onResume/onPause 里登记；这里必须是弱引用，避免泄漏。
 */
object AppState {
    @Volatile private var resumed: WeakReference<Activity>? = null

    fun onResume(activity: Activity) {
        resumed = WeakReference(activity)
    }

    fun onPause(activity: Activity) {
        val cur = resumed?.get()
        if (cur === activity) resumed = null
    }

    /** 当前前台 Activity 的类名（没有则为 null）。 */
    fun resumedActivityName(): String? = resumed?.get()?.javaClass?.name

    fun currentActivity(): Activity? = resumed?.get()

    /** 进程重要性是否为前台（App 可见）。 */
    fun isForeground(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val me = am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }
        me?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)
}
