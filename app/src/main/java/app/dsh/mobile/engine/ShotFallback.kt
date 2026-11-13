package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 无障碍服务不可用时的截图回退。
 *
 * 走 `Privilege.runPrivileged` 按所选 Shizuku 或 Root 模式执行系统 `screencap`，
 * 这样 OCR 可在已选择特权模式时回退；Normal 或对应通道不可用时返回 null，
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

    fun available(ctx: Context): Boolean = when (Privilege.getMode(ctx)) {
        PrivMode.NORMAL -> false
        PrivMode.SHIZUKU -> Privilege.shizukuUsable()
        PrivMode.ROOT -> Privilege.rootAvailableMinimal()
    }
}
