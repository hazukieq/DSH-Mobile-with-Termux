package app.dsh.mobile

import android.content.Context
import android.graphics.Bitmap
import app.dsh.mobile.engine.EngineConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v1.8.2：PP-OCRv6（ONNX Runtime）本地 OCR 兜底（点击链路提案 P1-4）。
 *
 * 路线（用户选定的首批）：tiny 6MB 模型随 APK 部署到 `$PREFIX/share/ppocr6`，
 * 脚本 `$PREFIX/bin/ppocr6` 用 Termux 里的 python3 + onnxruntime 跑推理。
 * App 自己不做 ONNX 推理，避免内置 onnxruntime 的 APK 体积与 ABI 维护。
 *
 * 依赖不在时 `/ocr?engine=ppocr6` 返回 unavailable + 安装提示；需要时调用
 * [ensureDeps] 执行 `pkg install -y python onnxruntime`（网络、体积都较大，显式触发）。
 */
object PpOcr {

    data class Block(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val conf: Double,
    ) {
        val cx: Int get() = (left + right) / 2
        val cy: Int get() = (top + bottom) / 2
    }

    data class Result(
        val ok: Boolean,
        val blocks: List<Block> = emptyList(),
        val unavailable: Boolean = false,
        val reason: String = "",
        val costMs: Long = 0L,
    )

    fun script(ctx: Context): File = File(EngineConfig.engineRoot(ctx), "bin/ppocr6")
    fun modelsDir(ctx: Context): File = File(EngineConfig.engineRoot(ctx), "share/ppocr6")
    fun python(ctx: Context): File = File(EngineConfig.engineRoot(ctx), "bin/python3")

    /** 脚本、模型、python 是否存在（不触发 import 检查，快）。 */
    fun deployed(ctx: Context): Boolean =
        script(ctx).isFile && modelsDir(ctx).isDirectory &&
            File(modelsDir(ctx), "det_tiny.onnx").isFile &&
            File(modelsDir(ctx), "rec_tiny.onnx").isFile

    @Volatile private var depsOk: Boolean? = null

    /** python3 + onnxruntime + numpy/PIL/scipy 是否可用（缓存结果）。 */
    fun ready(ctx: Context): Boolean {
        depsOk?.let { return it }
        if (!deployed(ctx) || !python(ctx).isFile) { depsOk = false; return false }
        val ok = runCatching {
            val p = ProcessBuilder(
                python(ctx).absolutePath, "-c",
                "import onnxruntime,numpy,PIL,scipy",
            ).redirectErrorStream(true).start()
            p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
        depsOk = ok
        return ok
    }

    /** 显式安装依赖（耗时/占空间；由 `/ocr?engine=ppocr6&ensure=1` 触发）。 */
    fun ensureDeps(ctx: Context): String? {
        val bash = File(EngineConfig.engineRoot(ctx), "bin/bash")
        if (!bash.isFile) return "engine bash missing"
        return runCatching {
            // onnxruntime 的 Python 绑定在 Termux 主仓库不一定存在；numpy/Pillow/scipy 是
            // ppocr6 预处理的硬依赖。分步安装，缺哪个就如实报告（不假装成功）。
            val pb = ProcessBuilder(
                bash.absolutePath, "-lc",
                "pkg install -y python onnxruntime; " +
                    "pkg install -y python-numpy python-pillow python-scipy || true",
            )
            pb.redirectErrorStream(true)
            pb.environment()["PREFIX"] = EngineConfig.engineRoot(ctx).absolutePath
            pb.environment()["PATH"] = "${EngineConfig.engineRoot(ctx).absolutePath}/bin:/system/bin"
            val p = pb.start()
            val out = p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (!p.waitFor(15, TimeUnit.MINUTES) || p.exitValue() != 0) {
                "pkg install failed: ${out.takeLast(600)}"
            } else {
                depsOk = null
                if (ready(ctx)) null else "pkg install finished but import check still fails: ${out.takeLast(400)}"
            }
        }.getOrElse { "ensure failed: ${it.message}" }
    }

    /** 识别一张位图（写入 cache PNG 后调用脚本，解析 --json 输出）。 */
    fun recognize(ctx: Context, bitmap: Bitmap): Result {
        if (!deployed(ctx)) return Result(false, unavailable = true, reason = "ppocr6 not deployed")
        if (!ready(ctx)) return Result(false, unavailable = true, reason = "python/onnxruntime not installed (call /ocr?engine=ppocr6&ensure=1)")
        val t0 = System.currentTimeMillis()
        val png = File(ctx.cacheDir, "ppocr6-${System.currentTimeMillis()}.png")
        return runCatching {
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val pb = ProcessBuilder(script(ctx).absolutePath, png.absolutePath, "--tier", "tiny", "--json")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                return Result(false, reason = "ppocr6 timeout (>90s)")
            }
            if (p.exitValue() != 0) {
                return Result(false, reason = "ppocr6 exit=${p.exitValue()}: ${out.takeLast(500)}")
            }
            val blocks = parseBlocks(out)
            Result(true, blocks = blocks, costMs = System.currentTimeMillis() - t0)
        }.getOrElse { Result(false, reason = "ppocr6 run failed: ${it.message}", costMs = System.currentTimeMillis() - t0) }
            .also { png.delete() }
    }

    /** 解析 `--json`：兼容 {"blocks":[...]} 与逐行 JSON。 */
    private fun parseBlocks(out: String): List<Block> {
        val blocks = ArrayList<Block>()
        fun add(o: JSONObject) {
            val text = o.optString("text").trim()
            if (text.isEmpty()) return
            val bbox = o.optJSONArray("bbox")
            val left: Int; val top: Int; val right: Int; val bottom: Int
            if (bbox != null && bbox.length() >= 4) {
                left = bbox.optInt(0); top = bbox.optInt(1); right = bbox.optInt(2); bottom = bbox.optInt(3)
            } else {
                left = o.optInt("left"); top = o.optInt("top")
                right = o.optInt("right", left + o.optInt("w"))
                bottom = o.optInt("bottom", top + o.optInt("h"))
            }
            blocks.add(Block(text, left, top, right, bottom, o.optDouble("conf", 0.0)))
        }
        runCatching {
            val root = JSONObject(out)
            val arr = root.optJSONArray("blocks") ?: root.optJSONArray("lines")
            if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::add)
            if (blocks.isNotEmpty()) return blocks
        }
        out.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith("{")) runCatching { add(JSONObject(t)) }
        }
        return blocks
    }
}
