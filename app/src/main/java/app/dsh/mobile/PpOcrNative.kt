package app.dsh.mobile

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v2.9.78：PP-OCRv6 **原生**推理（onnxruntime-android，不再依赖 Termux 的 python/onnxruntime）。
 *
 * 背景：模型（`share/ppocr6/det_tiny.onnx` + `rec_tiny.onnx` + `rec_tiny.yml`）一直随 APK 部署，
 * 但历史实现把推理交给 `$PREFIX/bin/ppocr6`（Termux python3 + onnxruntime）。真机上 python 运行时
 * 默认不存在，于是 `PpOcr.ready()` 恒 false、`/ocr?engine=ppocr6` 直接 unavailable；即便
 * `/ocr?engine=ppocr6&ensure=1` 把运行时装上，每次调用还要重起进程 + 重载两个模型，实测
 * 2.28–2.39s（prefetch 下 2.86–4.12s）。
 *
 * 本类把同一套预处理/后处理搬到 Kotlin，推理走包里已引入的 `onnxruntime-android`：
 * - 检测：DB 模型，BGR + ImageNet 均值方差，输出概率图 → 阈值 0.2 → 4 连通域 → 框均值 > 0.45 →
 *   矩形近似 unclip(1.4)（与 `ppocr6` 脚本逐参数对齐，见 `docs/qa/ppocr-native-report.md`）。
 * - 识别：48 高、宽按长宽比并 8 对齐（16..1600），BGR、(x/255-0.5)/0.5，CTC argmax 去重去 blank，
 *   字符集来自 `rec_<tier>.yml` 的 `character_dict`（`<blank>` + 字符 + 空格）。
 * - 批处理：最多 8 个框 pad 到同宽一次推理；模型不接受 batch 时自动回落单条。
 *
 * 会话只创建一次（懒加载 + 缓存），因此不再有「每次调用重载模型」的开销。
 */
internal object PpOcrNative {

    // ---- DB 检测后处理（与 ppocr6 脚本一致）----
    private const val DET_THRESH = 0.2f
    private const val BOX_THRESH = 0.45f
    private const val UNCLIP_RATIO = 1.4f
    private const val MAX_CANDIDATES = 3000
    private const val MIN_BOX_SIDE = 3
    /**
     * 检测输入的最长边上限。DB 检测是整条链路最贵的一步（1080×2400 全分辨率 ≈ 0.65s），
     * 缩到 1600 后像素少 ~2.2×、框坐标由 [resizeFloat] 映射回原图；真机对 26 行网页文字的
     * 召回/坐标与全分辨率一致（见 docs/qa/ppocr-native-report.md §4）。0 = 不缩。
     */
    @Volatile
    var detMaxSide: Int = 0

    // ---- 识别 ----
    private const val REC_H = 48
    private const val REC_MAX_W = 1600
    private const val REC_MIN_W = 16
    private const val REC_BATCH = 8
    private const val MIN_CONF = 0.35f
    private const val CROP_PAD = 2

    private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    private class Loaded(val session: OrtSession, val inputName: String)

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val lock = Any()
    private val sessions = HashMap<String, Loaded>()
    private val charsets = HashMap<String, List<String>>()

    /** 模型文件是否齐（det/rec/yml 三件套）。 */
    fun modelsReady(ctx: Context, tier: String): Boolean {
        val d = PpOcr.modelsDir(ctx)
        return File(d, "det_$tier.onnx").isFile &&
            File(d, "rec_$tier.onnx").isFile &&
            File(d, "rec_$tier.yml").isFile
    }

    /**
     * 原生推理是否可用：模型齐 + onnxruntime-android 的类能加载。
     * 刻意**不**创建 session —— `ready()` 会被 prefetch/点击链路高频调用，不能在这里载模型。
     */
    fun available(ctx: Context, tier: String = "tiny"): Boolean {
        if (!modelsReady(ctx, tier)) return false
        return runCatching {
            Class.forName("ai.onnxruntime.OrtEnvironment", false, PpOcrNative::class.java.classLoader)
        }.isSuccess
    }

    /** 释放会话（tier 切换/模型更新时用；正常运行常驻）。 */
    fun closeAll() {
        synchronized(lock) {
            sessions.values.forEach { runCatching { it.session.close() } }
            sessions.clear()
        }
    }

    // ------------------------------------------------------------------ 会话 / 字典

    private fun session(ctx: Context, tier: String, kind: String): Loaded? = synchronized(lock) {
        sessions["$tier:$kind"]?.let { return it }
        val f = File(PpOcr.modelsDir(ctx), "${kind}_$tier.onnx")
        if (!f.isFile) return null
        val loaded = runCatching {
            val opts = OrtSession.SessionOptions()
            try {
                opts.setIntraOpNumThreads(min(4, Runtime.getRuntime().availableProcessors()))
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                val s = env.createSession(f.absolutePath, opts)
                Loaded(s, s.inputNames.first())
            } finally {
                runCatching { opts.close() }
            }
        }.getOrNull() ?: return null
        sessions["$tier:$kind"] = loaded
        loaded
    }

    /** `rec_<tier>.yml` 的 `character_dict` → ["<blank>"] + chars + [" "]。 */
    private fun charset(ctx: Context, tier: String): List<String>? = synchronized(lock) {
        charsets[tier]?.let { return it }
        val f = File(PpOcr.modelsDir(ctx), "rec_$tier.yml")
        if (!f.isFile) return null
        val txt = runCatching { f.readText() }.getOrNull() ?: return null
        val m = Regex("character_dict:\n((?:  - .*\n)+)").find(txt) ?: return null
        val chars = ArrayList<String>(7000)
        for (line in m.groupValues[1].lineSequence()) {
            if (line.length < 4) continue
            var v = line.substring(4)
            if (v.length >= 2 && v.first() == '\'' && v.last() == '\'') {
                v = v.substring(1, v.length - 1).replace("''", "'")
            }
            chars.add(v)
        }
        if (chars.isEmpty()) return null
        val full = ArrayList<String>(chars.size + 2)
        full.add("<blank>")
        full.addAll(chars)
        full.add(" ")
        charsets[tier] = full
        full
    }

    // ------------------------------------------------------------------ 主流程

    fun recognize(ctx: Context, bitmap: Bitmap, tier: String = "tiny"): PpOcr.Result {
        val t0 = System.currentTimeMillis()
        val det = session(ctx, tier, "det")
            ?: return PpOcr.Result(false, unavailable = true, reason = "native: det_$tier.onnx missing or failed to load")
        val rec = session(ctx, tier, "rec")
            ?: return PpOcr.Result(false, unavailable = true, reason = "native: rec_$tier.onnx missing or failed to load")
        val cs = charset(ctx, tier)
            ?: return PpOcr.Result(false, unavailable = true, reason = "native: rec_$tier.yml has no character_dict")

        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8) return PpOcr.Result(false, reason = "native: bitmap too small ${w}x$h")

        // 1) 检测：先按最长边上限缩放（可选），再按 32 对齐（DB 模型要求 32 的倍数）
        val cap = detMaxSide
        val detScale = if (cap > 0 && max(w, h) > cap) cap.toFloat() / max(w, h) else 1f
        val tw = (w * detScale).roundToInt().coerceAtLeast(32)
        val th = (h * detScale).roundToInt().coerceAtLeast(32)
        val nw = ceil(tw / 32.0).toInt() * 32
        val nh = ceil(th / 32.0).toInt() * 32
        val detBmp = if (nw != w || nh != h) Bitmap.createScaledBitmap(bitmap, nw, nh, true) else bitmap
        val prob: FloatArray
        val pw: Int
        val ph: Int
        try {
            val detIn = fillChwBgr(detBmp, DET_MEAN, DET_STD)
            val tensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(detIn), longArrayOf(1, 3, nh.toLong(), nw.toLong()),
            )
            try {
                val res = det.session.run(mapOf(det.inputName to tensor))
                try {
                    val out = res.get(0) as OnnxTensor
                    val shape = (out.info as TensorInfo).shape
                    ph = shape[2].toInt()
                    pw = shape[3].toInt()
                    val fb = out.floatBuffer
                    prob = FloatArray(fb.remaining())
                    fb.get(prob)
                } finally {
                    runCatching { res.close() }
                }
            } finally {
                runCatching { tensor.close() }
            }
        } catch (t: Throwable) {
            return PpOcr.Result(false, reason = "native det failed: ${t.message}", costMs = System.currentTimeMillis() - t0)
        } finally {
            if (detBmp !== bitmap) runCatching { detBmp.recycle() }
        }

        // 2) 概率图缩回原尺寸（脚本用 PIL 双线性）
        val mask = if (pw == w && ph == h) prob else resizeFloat(prob, pw, ph, w, h)
        val boxes = detectBoxes(mask, w, h)
        if (boxes.isEmpty()) {
            return PpOcr.Result(true, blocks = emptyList(), costMs = System.currentTimeMillis() - t0, changed = true)
        }

        // 3) 识别：逐框裁切 → 批量推理
        val blanks = ArrayList<PpOcr.Block>()
        var i = 0
        while (i < boxes.size) {
            val chunk = ArrayList<Rec>(REC_BATCH)
            while (i < boxes.size && chunk.size < REC_BATCH) {
                val b = boxes[i]
                i++
                recInput(bitmap, b)?.let { chunk.add(it) }
            }
            if (chunk.isEmpty()) continue
            val decoded = runRecBatch(rec, chunk, cs)
                ?: runRecOneByOne(rec, chunk, cs)
            for ((k, item) in chunk.withIndex()) {
                val d = decoded.getOrNull(k) ?: continue
                val text = d.first
                if (text.isEmpty() || d.second < MIN_CONF) continue
                val x0 = max(0, item.box.x0.toInt())
                val y0 = max(0, item.box.y0.toInt())
                val x1 = min(w, ceil(item.box.x1).toInt())
                val y1 = min(h, ceil(item.box.y1).toInt())
                if (x1 <= x0 || y1 <= y0) continue
                blanks.add(PpOcr.Block(text, x0, y0, x1, y1, d.second.toDouble()))
            }
        }
        blanks.sortWith(compareBy({ it.cy }, { it.cx }))
        return PpOcr.Result(
            true, blocks = blanks, costMs = System.currentTimeMillis() - t0, changed = true,
            reason = "native boxes=${boxes.size}",
        )
    }

    /** 截图 PNG 直接识别（替代 python CLI 的文件路径）。 */
    fun recognizeFile(ctx: Context, png: File, tier: String): PpOcr.Result {
        val bmp = runCatching {
            android.graphics.BitmapFactory.decodeFile(png.absolutePath)
        }.getOrNull() ?: return PpOcr.Result(false, reason = "native: decode $png failed")
        return try {
            recognize(ctx, bmp, tier)
        } finally {
            runCatching { bmp.recycle() }
        }
    }

    // ------------------------------------------------------------------ 预处理

    /** ARGB_8888 位图 → NCHW FloatArray，通道顺序 BGR（Paddle 历史约定）。 */
    private fun fillChwBgr(src: Bitmap, mean: FloatArray?, std: FloatArray?): FloatArray {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(3 * n)
        if (mean != null && std != null) {
            val mb = mean[0]; val mg = mean[1]; val mr = mean[2]
            val sb = std[0]; val sg = std[1]; val sr = std[2]
            for (idx in 0 until n) {
                val p = px[idx]
                out[idx] = (((p and 0xFF).toFloat() / 255f) - mb) / sb
                out[n + idx] = ((((p shr 8) and 0xFF).toFloat() / 255f) - mg) / sg
                out[2 * n + idx] = ((((p shr 16) and 0xFF).toFloat() / 255f) - mr) / sr
            }
        } else {
            for (idx in 0 until n) {
                val p = px[idx]
                out[idx] = ((p and 0xFF).toFloat() / 255f - 0.5f) / 0.5f
                out[n + idx] = ((((p shr 8) and 0xFF).toFloat() / 255f) - 0.5f) / 0.5f
                out[2 * n + idx] = ((((p shr 16) and 0xFF).toFloat() / 255f) - 0.5f) / 0.5f
            }
        }
        return out
    }

    /** 双线性缩放单通道浮点图（概率图回原尺寸）。 */
    private fun resizeFloat(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        val xr = if (dw > 1) (sw - 1).toFloat() / (dw - 1) else 0f
        val yr = if (dh > 1) (sh - 1).toFloat() / (dh - 1) else 0f
        var oi = 0
        for (y in 0 until dh) {
            val sy = y * yr
            val y0 = sy.toInt().coerceIn(0, sh - 1)
            val y1 = (y0 + 1).coerceAtMost(sh - 1)
            val fy = sy - y0
            val r0 = y0 * sw
            val r1 = y1 * sw
            for (x in 0 until dw) {
                val sx = x * xr
                val x0 = sx.toInt().coerceIn(0, sw - 1)
                val x1 = (x0 + 1).coerceAtMost(sw - 1)
                val fx = sx - x0
                val a = src[r0 + x0]
                val b = src[r0 + x1]
                val c = src[r1 + x0]
                val d = src[r1 + x1]
                out[oi++] = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
            }
        }
        return out
    }

    // ------------------------------------------------------------------ DB 后处理

    private class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val score: Float)

    /** 阈值 → 4 连通域 → 框内均值 → 矩形近似 unclip（对应脚本里的 scipy.ndimage.label）。 */
    private fun detectBoxes(prob: FloatArray, w: Int, h: Int): List<Box> {
        val n = w * h
        val visited = java.util.BitSet(n)
        val stack = IntArray(n)
        val boxes = ArrayList<Box>(64)
        for (start in 0 until n) {
            if (visited.get(start) || prob[start] <= DET_THRESH) continue
            var sp = 0
            stack[sp++] = start
            visited.set(start)
            var minX = w; var maxX = -1; var minY = h; var maxY = -1
            var sum = 0f; var cnt = 0
            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % w
                val y = idx / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                sum += prob[idx]
                cnt++
                if (x > 0) {
                    val j = idx - 1
                    if (!visited.get(j) && prob[j] > DET_THRESH) { visited.set(j); stack[sp++] = j }
                }
                if (x + 1 < w) {
                    val j = idx + 1
                    if (!visited.get(j) && prob[j] > DET_THRESH) { visited.set(j); stack[sp++] = j }
                }
                if (y > 0) {
                    val j = idx - w
                    if (!visited.get(j) && prob[j] > DET_THRESH) { visited.set(j); stack[sp++] = j }
                }
                if (y + 1 < h) {
                    val j = idx + w
                    if (!visited.get(j) && prob[j] > DET_THRESH) { visited.set(j); stack[sp++] = j }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < MIN_BOX_SIDE || bh < MIN_BOX_SIDE) continue
            val score = if (cnt > 0) sum / cnt else 0f
            if (score < BOX_THRESH) continue
            val peri = 2f * (bw + bh)
            val dist = if (peri > 0f) bw * bh * UNCLIP_RATIO / peri else 0f
            boxes.add(Box(minX - dist, minY - dist, maxX + 1 + dist, maxY + 1 + dist, score))
            if (boxes.size >= MAX_CANDIDATES) break
        }
        return boxes
    }

    // ------------------------------------------------------------------ 识别批处理

    private class Rec(val input: FloatArray, val width: Int, val box: Box)

    private fun recInput(bitmap: Bitmap, box: Box): Rec? {
        val x0 = (box.x0.toInt() - CROP_PAD).coerceAtLeast(0)
        val y0 = (box.y0.toInt() - CROP_PAD).coerceAtLeast(0)
        val x1 = (ceil(box.x1).toInt() + CROP_PAD).coerceAtMost(bitmap.width)
        val y1 = (ceil(box.y1).toInt() + CROP_PAD).coerceAtMost(bitmap.height)
        val cw = x1 - x0
        val ch = y1 - y0
        if (cw < 2 || ch < 2) return null
        val crop = runCatching { Bitmap.createBitmap(bitmap, x0, y0, cw, ch) }.getOrNull() ?: return null
        var nw = (REC_H * (cw.toFloat() / ch)).roundToInt().coerceIn(REC_MIN_W, REC_MAX_W)
        nw = ceil(nw / 8.0).toInt() * 8
        var scaled: Bitmap? = null
        return try {
            scaled = Bitmap.createScaledBitmap(crop, nw, REC_H, true)
            Rec(fillChwBgr(scaled, null, null), nw, box)
        } catch (t: Throwable) {
            null
        } finally {
            if (scaled != null && scaled !== crop) runCatching { scaled!!.recycle() }
            runCatching { crop.recycle() }
        }
    }

    /** 批量识别：pad 到同宽一次跑。返回每条的 (text, conf)；batch 失败返回 null。 */
    private fun runRecBatch(rec: Loaded, chunk: List<Rec>, cs: List<String>): List<Pair<String, Float>>? {
        val maxW = chunk.maxOf { it.width }
        val n = chunk.size
        val batch = FloatArray(n * 3 * REC_H * maxW)
        for ((k, item) in chunk.withIndex()) {
            for (c in 0 until 3) {
                val srcBase = c * REC_H * item.width
                val dstBase = k * 3 * REC_H * maxW + c * REC_H * maxW
                for (y in 0 until REC_H) {
                    System.arraycopy(item.input, srcBase + y * item.width, batch, dstBase + y * maxW, item.width)
                }
            }
        }
        return runCatching {
            val tensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(batch),
                longArrayOf(n.toLong(), 3, REC_H.toLong(), maxW.toLong()),
            )
            try {
                val res = rec.session.run(mapOf(rec.inputName to tensor))
                try {
                    val out = res.get(0) as OnnxTensor
                    val shape = (out.info as TensorInfo).shape
                    if (shape.size != 3) return null
                    val bn = shape[0].toInt()
                    val t = shape[1].toInt()
                    val c = shape[2].toInt()
                    if (bn != n) return null
                    val fb = out.floatBuffer
                    val logits = FloatArray(fb.remaining())
                    fb.get(logits)
                    (0 until n).map { k -> ctcDecode(logits, k * t * c, t, c, cs) }
                } finally {
                    runCatching { res.close() }
                }
            } finally {
                runCatching { tensor.close() }
            }
        }.getOrNull()
    }

    /** 模型不接受 batch（或单条失败）时逐条跑。 */
    private fun runRecOneByOne(rec: Loaded, chunk: List<Rec>, cs: List<String>): List<Pair<String, Float>> {
        val out = ArrayList<Pair<String, Float>>(chunk.size)
        for (item in chunk) {
            val r = runCatching {
                val tensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(item.input),
                    longArrayOf(1, 3, REC_H.toLong(), item.width.toLong()),
                )
                try {
                    val res = rec.session.run(mapOf(rec.inputName to tensor))
                    try {
                        val o = res.get(0) as OnnxTensor
                        val shape = (o.info as TensorInfo).shape
                        val t = shape[1].toInt()
                        val c = shape[2].toInt()
                        val fb = o.floatBuffer
                        val logits = FloatArray(fb.remaining())
                        fb.get(logits)
                        ctcDecode(logits, 0, t, c, cs)
                    } finally {
                        runCatching { res.close() }
                    }
                } finally {
                    runCatching { tensor.close() }
                }
            }.getOrNull()
            out.add(r ?: ("" to 0f))
        }
        return out
    }

    /** CTC 解码：[offset, offset + t*c) 的 [T,C] logits → 文本 + 平均置信度。 */
    private fun ctcDecode(logits: FloatArray, offset: Int, t: Int, c: Int, cs: List<String>): Pair<String, Float> {
        val sb = StringBuilder(32)
        var prev = -1
        var sum = 0f
        var cnt = 0
        for (ti in 0 until t) {
            val base = offset + ti * c
            if (base + c > logits.size) break
            var best = 0
            var bestV = logits[base]
            for (k in 1 until c) {
                val v = logits[base + k]
                if (v > bestV) { bestV = v; best = k }
            }
            if (best == prev) continue
            prev = best
            if (best == 0) continue
            if (best < cs.size) {
                val ch = cs[best]
                if (ch != "<blank>") {
                    sb.append(ch)
                    sum += bestV
                    cnt++
                }
            }
        }
        return sb.toString().trim() to if (cnt > 0) sum / cnt else 0f
    }
}
