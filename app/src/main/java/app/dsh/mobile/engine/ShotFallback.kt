package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v1.9.6（提案 P1-4 收尾）：无障碍服务不可用时的截图回退。
 *
 * 走 `Privilege.runPrivileged`（Shizuku 优先 → su 兜底）执行系统 `screencap`，
 * 这样 OCR 不再被「无障碍没开」完全挡住；没有 root/Shizuku 时返回 null，
 * 上层仍然回 `a11y_off` 并引导用户去开服务。
 */
object ShotFallback {

    private const val TAG = "ShotFallback"
    private const val DIR = "/data/local/tmp"

    /** 截一张 PNG 到 app 可读位置；失败返回 null（不抛异常）。 */
    fun capture(ctx: Context): File? {
        val path = "$DIR/dsh-shot-${System.currentTimeMillis()}.png"
        val out = runCatching {
            Privilege.runPrivileged(ctx, "screencap -p $path && chmod 644 $path")
        }.getOrNull() ?: return null
        val f = File(path)
        if (f.isFile && f.length() > 0) {
            Log.i(TAG, "captured via privileged shell: ${f.length()}B")
            return f
        }
        Log.w(TAG, "screencap produced no file; output=${out.trim().take(120)}")
        return null
    }

    fun available(ctx: Context): Boolean = runCatching {
        Privilege.shizukuUsable() || Privilege.probe(ctx).hasRoot
    }.getOrDefault(false)
}
