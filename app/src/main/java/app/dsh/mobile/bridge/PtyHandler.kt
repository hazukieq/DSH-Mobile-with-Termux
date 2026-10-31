package app.dsh.mobile.bridge

import android.content.Context
import android.util.Base64
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.PtyBridge
import org.json.JSONObject

/**
 * `/pty/…` 端点（引擎侧侧边栏终端用的真实 PTY 桥）。
 *
 * 为什么在 App 侧开 PTY：Android 上 node-pty 没有可用预编译（依赖 glibc/x64 的 .node），
 * 运行时里的 node-pty 是空壳，WebUI 打开终端就 throw。App 有 libdshpty.so
 * （fork/exec + /dev/ptmx + PR_SET_PDEATHSIG），把真 tty 留在 App 进程里，
 * 用这组端点暴露给引擎的 node-pty shim —— 侧边栏因此拿到真 tty
 * （作业控制、Ctrl-C、resize 都成立），不是管道假终端。
 *
 * 鉴权沿用 handle() 的统一 X-DSH-Token；数据用 base64（二进制安全）。
 */
internal object PtyHandler {

    /** POST /pty/open {file,args,cwd,env,rows,cols,name} → {ok,id,pid} */
    fun open(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val file = obj.optString("file").trim()
        if (file.isEmpty()) {
            return 400 to JSONObject().put("ok", false).put("error", "missing 'file'").toString()
        }
        val args = ArrayList<String>()
        obj.optJSONArray("args")?.let { a -> for (i in 0 until a.length()) args.add(a.optString(i)) }
        val env = LinkedHashMap<String, String>()
        obj.optJSONObject("env")?.let { e -> for (k in e.keys()) env[k] = e.optString(k) }
        val cwd = obj.optString("cwd").trim().takeIf { it.isNotEmpty() }
            ?: EngineConfig.dshHome(ctx).absolutePath
        val rows = obj.optInt("rows", 24).let { if (it in 1..512) it else 24 }
        val cols = obj.optInt("cols", 80).let { if (it in 1..512) it else 80 }
        val term = obj.optString("name").trim().ifEmpty { "xterm-256color" }
        return try {
            val s = PtyBridge.open(ctx, file, args, cwd, env, rows, cols, term)
            200 to JSONObject().put("ok", true).put("id", s.id).put("pid", s.pid).toString()
        } catch (e: Exception) {
            409 to JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    /** POST /pty/write {id,data(base64)} */
    fun write(body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val id = obj.optInt("id", -1)
        val b64 = obj.optString("data")
        return try {
            val data = if (b64.isEmpty()) ByteArray(0) else Base64.decode(b64, Base64.NO_WRAP)
            val n = PtyBridge.write(id, data)
            200 to JSONObject().put("ok", true).put("bytes", n).toString()
        } catch (e: Exception) {
            409 to JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    /** POST /pty/resize {id,rows,cols} */
    fun resize(body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        return try {
            PtyBridge.resize(obj.optInt("id", -1), obj.optInt("rows", 24), obj.optInt("cols", 80))
            200 to JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            409 to JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    /** POST /pty/kill {id,sig}（默认 SIGTERM=15；只管信号，不释放会话） */
    fun kill(body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        return try {
            PtyBridge.signal(obj.optInt("id", -1), obj.optInt("sig", 15))
            200 to JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            409 to JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    /** POST /pty/close {id}（杀进程组 + 回收槽位，幂等） */
    fun close(body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        return try {
            PtyBridge.close(obj.optInt("id", -1))
            200 to JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            409 to JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    /** GET /pty/read?id&seq&timeout → {ok,seq,data,exited,exit_code,signal,lost} */
    fun read(query: String): Pair<Int, String> {
        val p = BridgeSupport.parseQuery(query)
        val id = p["id"]?.toIntOrNull() ?: -1
        val seq = p["seq"]?.toLongOrNull() ?: 0L
        val timeout = p["timeout"]?.toIntOrNull() ?: 20_000
        return try {
            val c = PtyBridge.read(id, seq, timeout)
            val b64 = if (c.data.isEmpty()) "" else Base64.encodeToString(c.data, Base64.NO_WRAP)
            200 to JSONObject().put("ok", true)
                .put("seq", c.nextSeq)
                .put("data", b64)
                .put("exited", c.exited)
                .put("exit_code", c.exitCode)
                .put("signal", c.signal)
                .put("lost", c.lost)
                .toString()
        } catch (e: Exception) {
            409 to JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }
}
