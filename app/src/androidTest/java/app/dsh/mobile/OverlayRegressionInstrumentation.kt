package app.dsh.mobile

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/** 无第三方测试依赖的窗口回归，目标设备需预先授予 SYSTEM_ALERT_WINDOW。 */
class OverlayRegressionInstrumentation : Instrumentation() {
    private var holdMs = 0L
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        check(android.os.Process.myUid() != 0) { "instrumentation must run as app uid" }
        app.dsh.mobile.engine.Privilege.setMode(targetContext, app.dsh.mobile.engine.PrivMode.NORMAL)
        holdMs = arguments?.getString("holdMs")?.toLongOrNull()?.coerceIn(0L, 30_000L) ?: 0L
        start()
    }

    override fun onStart() {
        val ctx = targetContext
        val previousMode = ProgressHub.mode(ctx)
        val previousExit = DshPrefs.userExited(ctx)
        val results = Bundle()
        var resultCode = android.app.Activity.RESULT_OK
        try {
            check(app.dsh.mobile.engine.Privilege.getMode(ctx) == app.dsh.mobile.engine.PrivMode.NORMAL)
            results.putInt("appUid", android.os.Process.myUid())
            results.putString("privMode", "normal")
            onMain {
                DshPrefs.setUserExited(ctx, false)
                StatusOverlay.resetHidden()
                ProgressHub.setMode(ctx, ProgressHub.MODE_OVERLAY)
            }
            waitForIdleSync()
            check(root().isAttachedToWindow) { "window did not attach" }
            tapBall()
            waitForIdleSync()
            onMain {
                val view = root()
                check(view.findViewById<View>(R.id.overlayRow).visibility == View.VISIBLE)
                StatusOverlay.setStatus("first frame")
                StatusOverlay.setStatus("final frame")
                check(view.findViewById<TextView>(R.id.overlayStatus).text.toString() == "final frame")
                StatusOverlay.setSession("regression session")
                StatusOverlay.setA11yAction("regression action")
            }
            // Capture's compositor wait belongs on the instrumentation worker, not the UI thread.
            val capture = StatusOverlay.beginCaptureAvoid()
            onMain {
                val ops = StatusOverlay.beginOpsAvoid()
                StatusOverlay.endOpsAvoid(ops)
                StatusOverlay.endOpsAvoid(ops)
                StatusOverlay.reassertVisible()
                check(root().visibility == View.INVISIBLE) { "gesture ended capture avoidance" }
            }
            StatusOverlay.endCaptureAvoid(capture)
            onMain {
                check(root().visibility == View.VISIBLE)
                StatusOverlay.markReplyReady()
                StatusOverlay.hideAuto()
                check(field("root") == null)
                check(field("statusRow") == null && field("ballDot") == null && field("footRow") == null)
                check(field("displayListener") == null && field("componentCallbacks") == null)
                check(StatusOverlay.show(ctx))
            }
            waitForIdleSync()
            onMain {
                val view = root()
                check(view.findViewById<TextView>(R.id.overlayStatus).text.toString() == "final frame")
                check(view.findViewById<TextView>(R.id.overlaySession).text.toString() == "regression session")
                check(view.findViewById<TextView>(R.id.overlayA11y).text.contains("regression action"))
                ProgressHub.update(ctx, "thinking", "regression", "stream first", "regression")
                ProgressHub.update(ctx, "thinking", "regression", "stream final", "regression")
            }
            SystemClock.sleep(250L)
            onMain {
                check(root().findViewById<TextView>(R.id.overlayStatus).text.toString() == "stream final") {
                    "progress throttle dropped the trailing frame"
                }
                ProgressHub.preview(ctx)
            }
            waitForIdleSync()
            onMain {
                check(root().isAttachedToWindow) { "preview removed its own window" }
                ProgressHub.setMode(ctx, ProgressHub.MODE_OFF)
                check(!StatusOverlay.show(ctx)) { "off mode recreated window" }
                DshPrefs.setUserExited(ctx, true)
                StatusOverlay.teardown()
                ProgressHub.setMode(ctx, ProgressHub.MODE_OVERLAY)
                check(!StatusOverlay.show(ctx)) { "user exit recreated window" }
                DshPrefs.setUserExited(ctx, false)
                check(StatusOverlay.resumeAfterExit())
                check(StatusOverlay.show(ctx))
            }
            if (holdMs > 0) {
                sendStatus(1, Bundle().apply { putString("stream", "window checks passed; holding for visual inspection\n") })
                Thread.sleep(holdMs)
            }
            results.putString("stream", "PASS: attach, expand, final frame, overlapping avoidance, callback cleanup, content restore, preview, off, exit/resume\n")

        } catch (e: Throwable) {
            results.putString("stream", android.util.Log.getStackTraceString(e))
            resultCode = android.app.Activity.RESULT_CANCELED
        } finally {
            onMain {
                DshPrefs.setUserExited(ctx, previousExit)
                ProgressHub.setMode(ctx, previousMode)
            }
        }
        finish(resultCode, results)
    }

    private fun onMain(block: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { block() } catch (e: Throwable) { error = e } }
        error?.let { throw it }
    }

    private fun field(name: String): Any? = StatusOverlay::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(StatusOverlay)
    }

    private fun root(): View = field("root") as? View ?: error("overlay root missing")

    private fun tapBall() = onMain {
        val view = root()
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, view.width / 2f, view.height / 2f, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
}
