package app.dsh.mobile

import android.content.Context
import android.content.pm.PackageManager
import app.dsh.mobile.engine.CloudAsr

/**
 * v1.3.78：悬浮窗麦克风是否真的可用。
 *
 * 麦克风显示规则（v1.3.80 修正）：**只以无障碍服务是否连接为准**。
 * 录音权限/识别通道缺失时按钮仍然显示 —— 点击后由语音流程给出「去授权 / 去配语音服务」的提示，
 * 否则用户永远没有入口去发现问题（v1.3.78 把这个也纳入显隐条件，导致开了无障碍却看不到按钮）。
 */
object VoiceReady {
    fun canUse(ctx: Context): Boolean = DshAccessibilityService.isEnabled()

    /** 语音是否「万事俱备」（无障碍 + 录音权限 + 识别通道）。仅用于诊断，不决定按钮显隐。 */
    fun fullyReady(ctx: Context): Boolean {
        if (!canUse(ctx)) return false
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching { AsrManager.isAvailable(ctx) || CloudAsr.isConfigured(ctx) }
            .getOrDefault(false)
    }
}
