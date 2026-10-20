package app.dsh.mobile.engine

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * App 自身的原生截图（v1.3.24）。
 *
 * 为什么不用 WebView/CDP 截图：WebView 的合成层在部分 ROM/驱动上会抓到空白或旧帧，
 * CDP 的 Page.captureScreenshot 又要开调试端口，作为「证明界面长什么样」的证据不可靠。
 * 这里用 [PixelCopy] 直接抓当前 App 窗口的 surface（API 26+，含 WebView 的合成结果），
 * 失败再退回把 decorView 画进 Bitmap（软件绘制，部分 WebView 内容可能缺失）。
 *
 * 窗口引用由 MainActivity 在 onResume/onPause 注册/注销 —— 只有 App 在前台时才能截图，
 * 这也符合「截 App 内部界面」的语义。
 */
object AppShot {
    private const val TAG = "AppShot"
    private const val KEEP = 12

    @Volatile private var activityRef: WeakReference<Activity>? = null

    fun register(activity: Activity) { activityRef = WeakReference(activity) }

    fun unregister(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    fun hasWindow(): Boolean = activityRef?.get() != null

    /**
     * 抓取当前 App 窗口并保存 PNG。
     * @return (file, width, height) 或 null（没有前台窗口 / 抓取失败）
     */
    fun capture(ctx: Context, maxDim: Int? = null): Triple<File, Int, Int>? {
        val act = activityRef?.get() ?: return null
        val window = act.window ?: return null
        val decor = window.decorView ?: return null
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) return null

        var bmp: Bitmap? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val latch = CountDownLatch(1)
            var ok = false
            val handler = Handler(Looper.getMainLooper())
            runCatching {
                handler.post {
                    runCatching {
                        PixelCopy.request(window, tmp, { result ->
                            ok = result == PixelCopy.SUCCESS
                            latch.countDown()
                        }, handler)
                    }.onFailure { latch.countDown() }
                }
                latch.await(3, TimeUnit.SECONDS)
            }
            if (ok) bmp = tmp else tmp.recycle()
        }
        if (bmp == null) {
            // 兜底：把 View 树软绘到 Bitmap（WebView 可能只有背景，但聊胜于无）
            bmp = runCatching {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                    val canvas = android.graphics.Canvas(b)
                    decor.draw(canvas)
                }
            }.getOrNull() ?: return null
        }

        val scaled = if (maxDim != null && maxOf(bmp.width, bmp.height) > maxDim) {
            val scale = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
            val nw = (bmp.width * scale).toInt().coerceAtLeast(1)
            val nh = (bmp.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, nw, nh, true).also { bmp.recycle() }
        } else bmp

        return runCatching {
            val dir = File(ctx.filesDir, "screens").apply { mkdirs() }
            val f = File(dir, "app-${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(f).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val rw = scaled.width
            val rh = scaled.height
            scaled.recycle()
            dir.listFiles { file -> file.name.startsWith("app-") }
                ?.sortedBy { it.lastModified() }?.dropLast(KEEP)?.forEach { it.delete() }
            Log.i(TAG, "app screenshot saved: ${f.absolutePath} (${rw}x${rh})")
            Triple(f, rw, rh)
        }.getOrElse {
            Log.w(TAG, "save app screenshot failed: ${it.message}")
            scaled.recycle()
            null
        }
    }
}
