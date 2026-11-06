package app.dsh.mobile

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 离线屏幕 OCR（v1.2.123）—— 无障碍节点树缺失场景的文本兜底。
 *
 * ## 为什么需要它（真机实测发现）
 * 无障碍节点树并非总能拿到文字：OPPO/ColorOS 自带浏览器、部分 WebView 应用、
 * 自绘（Canvas/SurfaceView）界面**完全不暴露文本节点**——`dumpScreenCompact`
 * 对这些界面只能输出空树，云端与本地快速层都"看不见"屏幕内容。
 * 唯一可靠的信息源是**画面本身**，因此从截图做 OCR。
 *
 * ## 为什么用 ML Kit（bundled）
 * - **离线**：模型（Hani 汉字 + Latn 拉丁 + 版面分析）随 APK 分发，
 *   `libmlkit_google_ocr_pipeline.so` 本地推理，**不依赖 Google Play 服务**
 *   （国内 ROM 无 GMS 也能跑）、不联网、无隐私外泄；
 * - **中文**：ChineseTextRecognizerOptions 是中英混合识别（实测中文 UI 可用）；
 * - **够快**：1080p 一帧约 200-600ms（首次初始化 ~1s），配合缓存可接受。
 *
 * ## 调用约束（重要）
 * 1. **截图有系统节流**：`takeScreenshot` 高频调用会被
 *    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT 拒绝（见 screenshotArgb 注释），
 *    所以本类**只在调用方判定必要时**才采帧，且带 800ms 结果缓存防重复；
 * 2. **请在 IO 线程调用**（截图 IPC + 推理都是重活）。
 */
object ScreenOcr {

    private const val TAG = "ScreenOcr"

    /** 识别出的一个文本行 */
    data class Block(
        val text: String,
        val cx: Int,
        val cy: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    data class Result(
        val blocks: List<Block>,
        val costMs: Long,
        /** true = 复用缓存结果（未重新采帧） */
        val cached: Boolean,
        /** 截图不可用（API<30 / 未授权 / 被节流）时为 true */
        val unavailable: Boolean = false,
    )

    // ---- 识别器（懒初始化、复用；ML Kit 客户端是线程安全的） ----
    @Volatile private var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null

    private fun recognizer(): com.google.mlkit.vision.text.TextRecognizer {
        recognizer?.let { return it }
        synchronized(this) {
            recognizer?.let { return it }
            val r = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            recognizer = r
            return r
        }
    }

    /** 识别降采样比例：0.5 = 半分辨率（1080p → 540p），推理快约 3 倍 */
    private const val DOWNSCALE = 0.5f

    // ---- 缓存（时间窗防重复采帧；截图节流是硬约束） ----
    // 实测（模拟器）：推理成本与分辨率基本无关（540p/270p 均 ~1.7s，
    // 固定成本主导），真机 CPU 快数倍。3 秒窗口让同一屏幕的多次 dump
    // （verify 轮询等）复用一次识别结果，避免反复付推理成本。
    private const val CACHE_MS = 3000L

    /** 截图被系统节流/不可用后的冷却：期间不再尝试采帧，避免反复撞节流 */
    private const val COOLDOWN_MS = 3000L
    private val lock = Any()
    private var lastAt = 0L
    private var last: List<Block>? = null
    private var cooldownUntil = 0L

    /** 清缓存（场景切换/需要强制刷新时调用） */
    fun invalidate() {
        synchronized(lock) {
            last = null
            lastAt = 0L
            cooldownUntil = 0L
        }
    }

    /**
     * 识别当前屏幕（带缓存）。
     * @param svc 无障碍服务（截图来源）
     * @param force true = 忽略缓存强制重新识别
     * @param scale 降采样比例（0.2-1.0）；默认 [DOWNSCALE]。识别在缩放图上
     *   进行、坐标自动还原——越小越快，但文字过小会漏识别。
     */
    fun recognize(
        svc: DshAccessibilityService,
        force: Boolean = false,
        scale: Float = DOWNSCALE,
        topSkip: Float = 0f,
    ): Result {
        val t0 = System.currentTimeMillis()
        synchronized(lock) {
            val l = last
            if (!force && l != null && t0 - lastAt < CACHE_MS) {
                return Result(l, System.currentTimeMillis() - t0, cached = true)
            }
            // 冷却期内（截图刚被节流）直接复用旧结果，不再采帧
            if (!force && t0 < cooldownUntil) {
                return Result(l ?: emptyList(), System.currentTimeMillis() - t0,
                    cached = true, unavailable = l == null)
            }
        }
        // v2.9.72：OCR **不避让**悬浮窗（避让 = INVISIBLE = WMS 销毁并重建 surface = 闪），
        // 改为把落在悬浮窗矩形里的识别块剔掉（与 prefetchOcr 同策略）。
        val shot = runCatching { svc.screenshotArgb(avoidOverlay = false) }.getOrNull()
            ?: return unavailable(t0)
        val tShot = System.currentTimeMillis()
        val (pixels, w, h) = shot
        // 降采样（v1.2.123 实测）：全分辨率(1080p+)推理 ~1.5-2.5s（模拟器），
        // 半分辨率显著更快；文字在 540p 下对 OCR 仍清晰。
        // 识别坐标按 scale 还原到屏幕坐标，点击不受影响。
        val sc = scale.coerceIn(0.2f, 1.0f)
        // v1.2.156 实测提速：ML Kit **推理**占 OCR 总耗时的大头（实测
        // infer=791-2595ms，而 scale 只有 8-68ms）——省时间必须**减像素**。
        // topSkip>0 时只识别内容区（跳过状态栏/浏览器顶栏）：那片区域本就不含
        // 我们要找的内容，识别它既费时又往 dump 里灌 `o0 5:06 3G` 这类噪声行。
        val yOff = (h * topSkip.coerceIn(0f, 0.6f)).toInt()
        val ch = (h - yOff).coerceAtLeast(1)
        val bw = (w * sc).toInt().coerceAtLeast(1)
        val bh = (ch * sc).toInt().coerceAtLeast(1)
        val full = runCatching {
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return unavailable(t0)
        val cropped = if (yOff > 0) {
            runCatching { Bitmap.createBitmap(full, 0, yOff, w, ch) }.getOrNull() ?: full
        } else full
        val bmp = if (sc < 1f) {
            runCatching { Bitmap.createScaledBitmap(cropped, bw, bh, true) }.getOrNull() ?: cropped
        } else cropped
        val tScale = System.currentTimeMillis()

        val blocks = runCatching { runBlockingRecognize(bmp, sc, yOff) }.getOrElse {
            Log.w(TAG, "ocr failed: ${it.message}")
            null
        }
        val tInfer = System.currentTimeMillis()
        if (bmp !== cropped) bmp.recycle()
        if (cropped !== full) cropped.recycle()
        full.recycle()
        if (blocks == null) return unavailable(t0)
        Log.i(TAG, "ocr: shot=${tShot - t0}ms scale=${tScale - tShot}ms infer=${tInfer - tScale}ms " +
            "size=${bw}x$bh yOff=$yOff blocks=${blocks.size}")
        val overlayRect = runCatching { StatusOverlay.currentFrameRect() }.getOrNull()
        var dropped = 0
        val visible = if (overlayRect == null) blocks else blocks.filterNot { b ->
            val hit = overlayRect.contains(b.cx, b.cy)
            if (hit) dropped++
            hit
        }
        synchronized(lock) {
            last = visible
            lastAt = System.currentTimeMillis()
            cooldownUntil = 0L
        }
        return Result(visible, System.currentTimeMillis() - t0, cached = false)
    }

    /**
     * 预热（v1.2.123）：后台跑一次识别——ML Kit 模型加载（首次 ~1-2s）与
     * 结果缓存提前完成，用户首次真实使用时不再付 5s 冷启动。
     * 请在服务连接后延迟数秒调用（避开启动高峰；消耗一次截图额度）。
     */
    fun warmUp(svc: DshAccessibilityService) {
        runCatching { recognize(svc, force = true) }
    }

    /** 不可用：启动冷却，避免高频撞节流 */
    private fun unavailable(t0: Long): Result {
        synchronized(lock) { cooldownUntil = t0 + COOLDOWN_MS }
        return Result(emptyList(), System.currentTimeMillis() - t0, cached = false, unavailable = true)
    }

    /** 同步等待 ML Kit 识别完成（CountDownLatch；调用方须在 IO 线程） */
    private fun runBlockingRecognize(bmp: Bitmap, scale: Float, yOff: Int = 0): List<Block>? {
        val latch = CountDownLatch(1)
        var out: List<Block>? = null
        var err: Exception? = null
        recognizer().process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                // 降采样坐标还原：识别在缩放图上，除以 scale 得到屏幕坐标
                fun back(v: Int) = (v / scale).toInt() + yOff   // 裁剪偏移一并还原
                out = text.textBlocks.flatMap { tb ->
                    tb.lines.mapNotNull { line ->
                        val t = line.text.trim()
                        if (t.isEmpty()) return@mapNotNull null
                        val r = line.boundingBox ?: return@mapNotNull null
                        Block(t, back(r.centerX()), back(r.centerY()),
                            back(r.left), back(r.top), back(r.right), back(r.bottom))
                    }
                }
                latch.countDown()
            }
            .addOnFailureListener { e ->
                err = e as? Exception ?: Exception(e)
                latch.countDown()
            }
        if (!latch.await(8, TimeUnit.SECONDS)) {
            Log.w(TAG, "ocr timed out (8s)")
            return null
        }
        err?.let { throw it }
        return out
    }

    /**
     * 把 OCR 行渲染成紧凑 dump 行（与 `dumpScreenCompact` 同格式，
     * 序号前缀 `o` 标记来源 = OCR，供模型与诊断区分）。
     * 坐标即可点击位置（行中心），FastPicker/云端可直接用作 tap 目标。
     *
     * ⚠️ 文本中的 `@` 替换为全角：紧凑格式用 `@x,y` 定界坐标，解析器按
     * **最后一个 @** 切分——识别出的邮箱/账号类文本会破坏定界（实测坑）。
     */
    fun toDumpLines(blocks: List<Block>, startIndex: Int = 0): List<String> =
        blocks.mapIndexed { i, b ->
            "o${startIndex + i} ${b.text.replace('@', '＠')} @${b.cx},${b.cy} c"
        }
}
