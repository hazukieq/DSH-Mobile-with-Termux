package app.dsh.mobile

import android.content.Context
import android.content.pm.PackageManager
import app.dsh.mobile.engine.CloudAsr

/**
 * v1.3.78：悬浮窗麦克风是否真的可用。
 *
 * 语音输入的三件套：无障碍服务（录音面板/无障碍窗口）、录音权限、可用的识别通道。
 * 任一缺失时**不显示麦克风**（用户要求「没有就不显示」），避免点不动/点了没反应的死控件。
 */
object VoiceReady {
    fun canUse(ctx: Context): Boolean {
        if (!DshAccessibilityService.isEnabled()) return false
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching { AsrManager.isAvailable(ctx) || CloudAsr.isConfigured(ctx) }
            .getOrDefault(false)
    }
}
