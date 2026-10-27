package app.dsh.mobile

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import app.dsh.mobile.engine.AgentBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 屏幕旋转后自动校准坐标（v2.5.29）。
 *
 * 为什么需要自动：手动跑 `scr calib` 必须由 agent 在“目标 App 前台”时执行，
 * 而输入命令本身又常常把本 App 切到前台 —— 横屏场景几乎不可能手动测准。
 * 改成监听显示旋转：只要用户在任意 App 里转屏，等动画/布局稳定后自动
 * dump 无障碍节点 + 截屏配对，拟合出该尺寸下的坐标映射并存库（按 w×h 分桶）。
 *
 * 只在无障碍服务在线时执行；拟合不出（文本太少/全是同名）就什么都不写。
 */
object CoordAutoCalibrator {
    private const val TAG = "CoordCalib"
    private var installed = false
    private var lastRotation = -1

    fun install(app: Application) {
        if (installed) return
        installed = true
        val dm = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        fun rotationNow(): Int = runCatching {
            dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        }.getOrDefault(Surface.ROTATION_0)
        lastRotation = rotationNow()
        dm.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val rotation = runCatching {
                    dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                }.getOrNull() ?: return
                if (rotation == lastRotation) return
                lastRotation = rotation
                Handler(Looper.getMainLooper()).postDelayed({
                    (app as? DshApp)?.appScope?.launch {
                        if (DshAccessibilityService.instance == null) {
                            Log.i(TAG, "rotation=$rotation skipped: no accessibility service")
                            return@launch
                        }
                        kotlinx.coroutines.delay(300)
                        runCatching {
                            val (code, body) = AgentBridge.calib(app, "apply=1")
                            Log.i(TAG, "rotation=$rotation code=$code $body")
                        }.onFailure { Log.w(TAG, "auto-calib failed: ${it.message}") }
                    }
                }, 2200)
            }
        }, null)
        Log.i(TAG, "installed (rotation=$lastRotation)")
    }
}
