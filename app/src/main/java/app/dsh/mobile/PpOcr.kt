package app.dsh.mobile

import android.content.Context
import android.graphics.Bitmap
import app.dsh.mobile.engine.EngineConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

    fun isTierDeployed(ctx: Context, tier: String): Boolean {
        val d = modelsDir(ctx)
        if (!script(ctx).isFile) return false
        return File(d, "det_$tier.onnx").isFile && File(d, "rec_$tier.onnx").isFile &&
            File(d, "rec_$tier.yml").isFile
    }

    fun tierSizes(ctx: Context, tier: String): Map<String, Long> = runCatching {
        val d = modelsDir(ctx)
        listOf("det_$tier.onnx", "rec_$tier.onnx", "rec_$tier.yml").associateWith {
            File(d, it).takeIf { f -> f.isFile }?.length() ?: 0L
        }
    }.getOrDefault(emptyMap())

    /** v1.9.6：medium 档按需下载（约 139MB；HuggingFace 或 hf-mirror 镜像）。 */
    fun ensureModels(ctx: Context, tier: String): String? {
        if (tier == "tiny") return null // 随 APK 部署
        if (tier != "medium") return "unknown tier '$tier' (tiny|medium)"
        if (isTierDeployed(ctx, tier)) return null
        val d = modelsDir(ctx).apply { mkdirs() }
        val files = listOf(
            Triple("det_$tier.onnx", "PP-OCRv6_medium_det_onnx", "inference.onnx"),
            Triple("rec_$tier.onnx", "PP-OCRv6_medium_rec_onnx", "inference.onnx"),
            Triple("rec_$tier.yml", "PP-OCRv6_medium_rec_onnx", "inference.yml"),
        )
        // 国内优先：ModelScope（实测 ~9MB/s）→ hf-mirror → 官方 HF。
        val hostTemplates = listOf(
            "https://modelscope.cn/models/PaddlePaddle/%s/resolve/master",
            "https://hf-mirror.com/PaddlePaddle/%s/resolve/main",
            "https://huggingface.co/PaddlePaddle/%s/resolve/main",
        )
        for ((destName, repo, srcName) in files) {
            val dst = File(d, destName)
            if (dst.isFile && dst.length() > 0) continue
            var lastErr = "download failed"
            var done = false
            for (hostTemplate in hostTemplates) {
                val host = hostTemplate.format(repo)
                runCatching {
                    val tmp = File(d, "$destName.part")
                    val have = if (tmp.isFile) tmp.length() else 0L
                    val conn = (URL("$host/$srcName").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 300_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "DSH-Mobile/1.9")
                        if (have > 0) setRequestProperty("Range", "bytes=$have-")
                    }
                    if (conn.responseCode !in 200..299) {
                        lastErr = "HTTP ${conn.responseCode} from $host"
                        conn.disconnect(); return@runCatching
                    }
                    val append = conn.responseCode == 206 && have > 0
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(tmp, append).use { out -> input.copyTo(out) }
                    }
                    conn.disconnect()
                    if (tmp.length() <= 0) { lastErr = "empty body from $host"; tmp.delete(); return@runCatching }
                    if (append && tmp.length() < have) { lastErr = "short append"; return@runCatching }
                    tmp.renameTo(dst)
                    done = true
                }.onFailure { lastErr = it.message ?: it.javaClass.simpleName }
                if (done) break
            }
            if (!done) return "cannot download $destName: $lastErr"
        }
        return if (isTierDeployed(ctx, tier)) null else "models downloaded but files missing"
    }

    @Volatile private var depsOk: Boolean? = null

    /** python3 + onnxruntime + numpy/PIL/scipy 是否可用（缓存结果）。 */
    fun ready(ctx: Context): Boolean {
        depsOk?.let { return it }
        if (!deployed(ctx) || !python(ctx).isFile) { depsOk = false; return false }
        fun probe(extraPythonPath: String?): Boolean = runCatching {
            val pb = ProcessBuilder(
                python(ctx).absolutePath, "-c",
                "import onnxruntime,numpy,PIL,scipy;print('ok')",
            )
            pb.redirectErrorStream(true)
            if (extraPythonPath != null) pb.environment()["PYTHONPATH"] = extraPythonPath
            pb.environment()["LD_LIBRARY_PATH"] = EngineConfig.engineRoot(ctx).absolutePath + "/lib"
            val p = pb.start()
            val out = p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0 && out.contains("ok")
        }.getOrDefault(false)
        // 优先真实包（pkg install python-onnxruntime）；没有才用随包 ctypes shim。
        var ok = probe(null)
        if (!ok) ok = probe(File(modelsDir(ctx), "py").absolutePath)
        depsOk = ok
        return ok
    }

    /** python 包 `python-onnxruntime` 是否真的装进了 site-packages（区别于 shim 兜底）。 */
    fun realBindingReady(ctx: Context): Boolean = runCatching {
        val pb = ProcessBuilder(python(ctx).absolutePath, "-c", "import onnxruntime;print('real')")
        pb.redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = EngineConfig.engineRoot(ctx).absolutePath + "/lib"
        val p = pb.start()
        val out = p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0 && out.contains("real")
    }.getOrDefault(false)

    /**
     * 显式安装依赖（耗时/占空间；由 `/ocr?engine=ppocr6&ensure=1` 触发）。
     *
     * Termux 的 Python 绑定是**独立包 `python-onnxruntime`**（Depends: python, python-numpy,
     * python-pip, onnxruntime），必须走 `pkg install`；pip 在 bionic 上没有可用 wheel。
     */
    fun ensureDeps(ctx: Context): String? {
        val bash = File(EngineConfig.engineRoot(ctx), "bin/bash")
        if (!bash.isFile) return "engine bash missing"
        return runCatching {
            val pb = ProcessBuilder(
                bash.absolutePath, "-lc",
                "pkg install -y python python-numpy python-pillow python-scipy python-onnxruntime",
            )
            pb.redirectErrorStream(true)
            pb.environment()["PREFIX"] = EngineConfig.engineRoot(ctx).absolutePath
            pb.environment()["PATH"] = "${EngineConfig.engineRoot(ctx).absolutePath}/bin:/system/bin"
            val p = pb.start()
            val out = p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            runCatching {
                File(EngineConfig.engineRoot(ctx), "tmp/ppocr6-ensure.log").writeText(out.takeLast(6000))
            }
            val ok = p.waitFor(15, TimeUnit.MINUTES) && p.exitValue() == 0
            depsOk = null
            when {
                ok && realBindingReady(ctx) -> null
                ok -> "pkg finished but python binding is still missing (log: \$PREFIX/tmp/ppocr6-ensure.log)"
                else -> "pkg install failed (mirror/deps?): ${out.takeLast(700)}"
            }
        }.getOrElse { "ensure failed: ${it.message}" }
    }

    /** 识别一张位图（写入 cache PNG 后调用脚本，解析 --json 输出）。 */
    fun recognize(ctx: Context, bitmap: Bitmap, tier: String = "tiny"): Result {
        if (!deployed(ctx)) return Result(false, unavailable = true, reason = "ppocr6 not deployed")
        val png = File(ctx.cacheDir, "ppocr6-${System.currentTimeMillis()}.png")
        runCatching { png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            .onFailure { return Result(false, reason = "write png failed: ${it.message}") }
        return recognizeFile(ctx, png, tier).also { png.delete() }
    }

    /** 识别一个已存在的 PNG（供 root/Shizuku 截图回退复用）。 */
    fun recognizeFile(ctx: Context, png: File, tier: String = "tiny"): Result {
        if (!deployed(ctx)) return Result(false, unavailable = true, reason = "ppocr6 not deployed")
        if (!ready(ctx)) return Result(false, unavailable = true, reason = "python/onnxruntime not installed (call /ocr?engine=ppocr6&ensure=1)")
        if (!isTierDeployed(ctx, tier)) {
            return Result(false, unavailable = true, reason = "models for tier '$tier' missing (call /ocr?engine=ppocr6&download=$tier)")
        }
        val t0 = System.currentTimeMillis()
        return runCatching {
            val pb = ProcessBuilder(script(ctx).absolutePath, png.absolutePath, "--tier", tier, "--json")
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
            // ppocr6 --json: {"items":[{text,conf,x,y,bbox:[l,t,r,b]}]}
            val arr = root.optJSONArray("items") ?: root.optJSONArray("blocks") ?: root.optJSONArray("lines")
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
