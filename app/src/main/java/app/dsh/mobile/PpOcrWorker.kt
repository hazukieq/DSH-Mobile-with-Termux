package app.dsh.mobile

import android.content.Context
import android.util.Log
import app.dsh.mobile.engine.EngineConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * v1.9.8（提案 P2-8）：常驻 PP-OCRv6 worker。
 *
 * 每次 `/ocr` 冷启 python 要 ~1s（import numpy/scipy + 加载模型），连续操作时纯浪费。
 * 这里保持一个 `ppocr6_worker.py` 子进程：模型只加载一次，之后按 JSON 行喂图片路径，
 * 单次只付检测/识别成本。进程崩溃/超时自动销毁，上层回落一次性 CLI。
 */
object PpOcrWorker {

    private const val TAG = "PpOcrWorker"
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var lines: LinkedBlockingQueue<String>? = null
    @Volatile private var tierWarm: String? = null

    @Synchronized
    private fun start(ctx: Context, tier: String): Boolean {
        val existing = process
        if (existing != null && existing.isAlive && tierWarm == tier) return true
        stop()
        val py = File(EngineConfig.engineRoot(ctx), "bin/python3")
        val worker = File(EngineConfig.engineRoot(ctx), "share/ppocr6/py/ppocr6_worker.py")
        val script = File(EngineConfig.engineRoot(ctx), "bin/ppocr6")
        if (!py.isFile || !worker.isFile || !script.isFile) return false
        return runCatching {
            val pb = ProcessBuilder(py.absolutePath, worker.absolutePath, script.absolutePath)
            pb.redirectErrorStream(false)
            pb.environment()["LD_LIBRARY_PATH"] = EngineConfig.engineRoot(ctx).absolutePath + "/lib"
            val p = pb.start()
            val q = LinkedBlockingQueue<String>()
            val out = BufferedWriter(p.outputStream.writer(Charsets.UTF_8))
            // stderr 丢弃到日志，避免管道写满阻塞
            Thread {
                runCatching {
                    p.errorStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) Log.i(TAG, "worker: ${line.take(200)}")
                    }
                }
            }.apply { isDaemon = true; name = "ppocr6-worker-err" }.start()
            Thread {
                runCatching {
                    p.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            q.offer(line)
                        }
                    }
                }
            }.apply { isDaemon = true; name = "ppocr6-worker-out" }.start()
            process = p
            writer = out
            lines = q
            tierWarm = tier
            Log.i(TAG, "worker started tier=$tier")
            true
        }.getOrElse {
            Log.w(TAG, "worker start failed: ${it.message}")
            stop()
            false
        }
    }

    /** 返回 ppocr6 `--json` 的结构（JSONObject），失败返回 null。 */
    @Synchronized
    fun recognize(ctx: Context, png: File, tier: String, timeoutMs: Long = 90_000L): JSONObject? {
        if (!start(ctx, tier)) return null
        val req = JSONObject()
            .put("image", png.absolutePath)
            .put("tier", tier)
        return runCatching {
            writer?.write(req.toString() + "\n")
            writer?.flush()
            val line = lines?.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if (line == null) {
                Log.w(TAG, "worker timeout -> restart")
                stop()
                return null
            }
            val obj = JSONObject(line)
            if (!obj.optBoolean("ok")) {
                Log.w(TAG, "worker error: ${obj.optString("error")}")
                return null
            }
            obj.optJSONObject("result")
        }.getOrElse {
            Log.w(TAG, "worker call failed: ${it.message}")
            stop()
            null
        }
    }

    @Synchronized
    fun stop() {
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        process = null
        writer = null
        lines = null
        tierWarm = null
    }

    @Synchronized
    fun status(): JSONObject = JSONObject()
        .put("running", process?.isAlive == true)
        .put("tier", tierWarm ?: "")
}
