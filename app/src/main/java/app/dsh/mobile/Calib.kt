package app.dsh.mobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * OCR 坐标自校准（v2.5.28）。
 *
 * 为什么需要：OCR 的坐标是在**截图像素空间**里算出来的，而无障碍的 bounds 是系统给的
 * **显示坐标**。两者大多数时候一致（实测竖屏 1080x2376、横屏 2376x1080 都与显示一致），
 * 但一旦出现缩放、裁剪、letterbox 或厂商截图缩放等差异，整屏的点击坐标就会系统性偏移——
 * 这种偏差靠读代码很难发现，只能实测。
 *
 * 做法：把「无障碍节点里带文字的项」当作真值，把「OCR 里同名文本块」当作观测，
 * 逐轴做最小二乘拟合 x' = sx*x + dx、y' = sy*y + dy；n>=3 且拟合后误差下降才落库。
 * 参数按**截图像素尺寸**分桶（`1080x2376` / `2376x1080` 天然分开，不需要额外判断旋屏）。
 *
 * 只在存在对应桶时生效：没有校准参数时 map 恒等，老行为完全不变。
 */
object Calib {

    /** 拟合结果（不含分桶尺寸）。 */
    data class Fit(
        val sx: Float, val sy: Float, val dx: Float, val dy: Float,
        val n: Int, val maeBefore: Float, val maeAfter: Float,
    )

    /** 落库参数：拟合结果 + 它适用的截图像素尺寸。 */
    data class Params(
        val w: Int, val h: Int,
        val sx: Float, val sy: Float, val dx: Float, val dy: Float,
        val n: Int, val maeBefore: Float, val maeAfter: Float, val at: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("w", w).put("h", h)
            .put("sx", sx.toDouble()).put("sy", sy.toDouble())
            .put("dx", dx.toDouble()).put("dy", dy.toDouble())
            .put("n", n)
            .put("maeBefore", maeBefore.toDouble()).put("maeAfter", maeAfter.toDouble())
            .put("at", at)

        companion object {
            fun from(o: JSONObject): Params? = runCatching {
                Params(
                    o.optInt("w"), o.optInt("h"),
                    o.optDouble("sx", 1.0).toFloat(), o.optDouble("sy", 1.0).toFloat(),
                    o.optDouble("dx", 0.0).toFloat(), o.optDouble("dy", 0.0).toFloat(),
                    o.optInt("n"),
                    o.optDouble("maeBefore", 0.0).toFloat(), o.optDouble("maeAfter", 0.0).toFloat(),
                    o.optLong("at"),
                )
            }.getOrNull()
        }
    }

    private const val FILE = "coord-calib.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun key(w: Int, h: Int) = "${w}x$h"

    @Synchronized
    fun load(ctx: Context): MutableMap<String, Params> {
        val out = LinkedHashMap<String, Params>()
        val f = file(ctx)
        if (!f.isFile) return out
        runCatching {
            val root = JSONObject(f.readText())
            val it = root.keys()
            while (it.hasNext()) {
                val k = it.next()
                Params.from(root.optJSONObject(k) ?: continue)?.let { p -> out[k] = p }
            }
        }
        return out
    }

    @Synchronized
    private fun save(ctx: Context, map: Map<String, Params>) {
        val root = JSONObject()
        map.forEach { (k, p) -> root.put(k, p.toJson()) }
        runCatching { file(ctx).writeText(root.toString()) }
    }

    fun get(ctx: Context, w: Int, h: Int): Params? = load(ctx)[key(w, h)]

    fun put(ctx: Context, p: Params) {
        val map = load(ctx)
        map[key(p.w, p.h)] = p
        save(ctx, map)
    }

    /** 清空全部校准（`/calib?clear=1`）。 */
    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    /**
     * 最小二乘拟合：truth = s*obs + d（每个轴独立）。
     *
     * @param pairs [truthX, truthY, obsX, obsY]
     * @return null = 样本不足 / 退化；样本方差过小时退化为纯平移。
     */
    fun fit(pairs: List<FloatArray>): Fit? {
        if (pairs.size < 3) return null

        fun medianOf(v: FloatArray): Float {
            if (v.isEmpty()) return 0f
            val a = v.copyOf()
            a.sort()
            return a[a.size / 2]
        }

        fun naiveMae(list: List<FloatArray>): Float {
            val d = FloatArray(list.size) { i ->
                val p = list[i]
                kotlin.math.hypot(p[0] - p[2], p[1] - p[3])
            }
            return medianOf(d)
        }

        /** 逐轴最小二乘 truth = s*obs + d；退化/离谱时退回纯平移。 */
        fun ls(list: List<FloatArray>, ti: Int, oi: Int): Pair<Float, Float> {
            val n = list.size.toDouble()
            var so = 0.0; var st = 0.0; var soo = 0.0; var sot = 0.0
            for (p in list) {
                val o = p[oi].toDouble(); val t = p[ti].toDouble()
                so += o; st += t; soo += o * o; sot += o * t
            }
            val den = n * soo - so * so
            if (abs(den) < 1e-3) return 1f to (st / n - so / n).toFloat()
            var sc = (n * sot - so * st) / den
            var d = (st - sc * so) / n
            if (sc.isNaN() || sc.isInfinite() || sc < 0.5 || sc > 2.0) {
                sc = 1.0
                d = st / n - so / n
            }
            return sc.toFloat() to d.toFloat()
        }

        /**
         * 两步稳健拟合：先用「中位偏移」把配错的离群点筛掉，再在剩余点上做最小二乘，
         * 然后按拟合后的残差再筛一次。屏幕上同名文本（「1小时前」重复两次）与动态内容
         * 靠这个兜住，否则一次配错就能把整张图的偏移算歪。
         */
        var inliers = pairs
        var sx = 1f; var sy = 1f; var dx = 0f; var dy = 0f
        repeat(2) {
            val mdx = medianOf(FloatArray(inliers.size) { inliers[it][0] - inliers[it][2] })
            val mdy = medianOf(FloatArray(inliers.size) { inliers[it][1] - inliers[it][3] })
            val mx = FloatArray(inliers.size) { abs(inliers[it][0] - inliers[it][2] - mdx) }
            val my = FloatArray(inliers.size) { abs(inliers[it][1] - inliers[it][3] - mdy) }
            val madX = medianOf(mx)
            val madY = medianOf(my)
            val kept = inliers.filter { p ->
                abs(p[0] - p[2] - mdx) <= 3f * madX + 10f && abs(p[1] - p[3] - mdy) <= 3f * madY + 10f
            }
            if (kept.size < 3) return null
            inliers = kept
            val fx = ls(inliers, 0, 2)
            val fy = ls(inliers, 1, 3)
            sx = fx.first; dx = fx.second
            sy = fy.first; dy = fy.second
        }
        return Fit(sx, sy, dx, dy, inliers.size, naiveMae(inliers), maeAfter(inliers, sx, sy, dx, dy))
    }

    private fun maeAfter(pairs: List<FloatArray>, sx: Float, sy: Float, dx: Float, dy: Float): Float {
        if (pairs.isEmpty()) return 0f
        var sum = 0.0
        for (p in pairs) {
            val mx = sx * p[2] + dx
            val my = sy * p[3] + dy
            sum += kotlin.math.hypot((mx - p[0]).toDouble(), (my - p[1]).toDouble())
        }
        return (sum / pairs.size).toFloat()
    }

    /**
     * OCR 坐标 → 显示坐标。没有对应桶时原样返回（恒等），保证老行为不变。
     */
    fun mapX(ctx: Context, w: Int, h: Int, x: Float): Float {
        val p = get(ctx, w, h) ?: return x
        return p.sx * x + p.dx
    }

    fun mapY(ctx: Context, w: Int, h: Int, y: Float): Float {
        val p = get(ctx, w, h) ?: return y
        return p.sy * y + p.dy
    }

    /**
     * 就地校正一组 OCR 框。`w/h` 是这一组框所在的**截图像素尺寸**（不是显示尺寸）。
     *
     * @return 是否真的做了校正（false = 无参数 / 参数不产生位移）
     */
    fun applyTo(
        ctx: Context,
        w: Int, h: Int,
        boxes: MutableList<FloatArray>,
    ): Boolean {
        val p = get(ctx, w, h) ?: return false
        for (b in boxes) {
            // [left, top, right, bottom]
            b[0] = p.sx * b[0] + p.dx
            b[1] = p.sy * b[1] + p.dy
            b[2] = p.sx * b[2] + p.dx
            b[3] = p.sy * b[3] + p.dy
        }
        return true
    }
}
