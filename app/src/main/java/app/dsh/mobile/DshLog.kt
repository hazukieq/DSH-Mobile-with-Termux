package app.dsh.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 发布包日志（v2.5.30 重做，2026-10-27）：
 *  ① 每条同时 Log.i("DSHLOG", …) → 电脑端 `adb logcat -s DSHLOG:*` 随时可读（release 也可）；
 *  ② 追加写 filesDir/log/dsh.log，超过 1MB 轮转成 dsh.log.1 → 重装/崩溃后仍在；
 *  ③ 内存环只当快路径（桥 /log 读文件，不靠环）。
 * 注：App 自身读 logcat 不可行（READ_LOGS 自 API16 仅特权系统应用）。
 */
object DshLog {
    private const val TAG = "DSHLOG"
    private const val MAX = 400
    private const val ROTATE = 1024L * 1024L

    private val ring = ArrayDeque<String>(MAX)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-log").apply { isDaemon = true } }
    private val written = AtomicLong(0)
    @Volatile private var file: File? = null

    @Volatile var lastError: String? = null
        private set

    fun init(ctx: Context) {
        val dir = File(ctx.filesDir, "log").apply { mkdirs() }
        file = File(dir, "dsh.log")
        installCrashHandler(ctx)
        d("life", "logger started, log=${file!!.absolutePath}")
    }

    private fun line(tag: String, msg: String, level: Char): String {
        val s = "${fmt.format(Date())} $level/$tag $msg"
        Log.println(if (level == 'E') Log.ERROR else Log.INFO, TAG, "$level/$tag $msg")
        synchronized(ring) { if (ring.size >= MAX) ring.removeFirst(); ring.addLast(s) }
        val f = file
        if (f != null) io.execute {
            runCatching {
                if (f.length() > ROTATE) f.renameTo(File(f.parentFile, "dsh.log.1"))
                f.appendText(s + "\n")
                written.incrementAndGet()
            }
        }
        return s
    }

    fun d(tag: String, msg: String) { line(tag, msg, 'I') }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val head = line(tag, msg, 'E')
        tr?.let {
            lastError = head
            it.stackTraceToString().lineSequence().take(60).forEach { l -> line(tag, "  $l", 'E') }
        }
    }

    fun tail(n: Int): List<String> = synchronized(ring) { ring.toList().takeLast(n) }

    fun tailJson(n: Int): String {
        val arr = StringBuilder("[")
        tail(n).forEachIndexed { i, l -> if (i > 0) arr.append(','); arr.append(org.json.JSONObject.quote(l)) }
        return arr.append("]").toString()
    }

    /** 从文件读尾部（重启后仍有效）：优先 dsh.log，不足再补 dsh.log.1。 */
    fun fileTail(n: Int): List<String> {
        val f = file ?: return tail(n)
        val cur = runCatching { f.readLines().takeLast(n) }.getOrDefault(emptyList())
        if (cur.size >= n) return cur
        val old = runCatching { File(f.parentFile, "dsh.log.1").readLines() }.getOrDefault(emptyList())
        return (old + cur).takeLast(n)
    }

    fun fileTailJson(n: Int): String {
        val arr = StringBuilder("[")
        fileTail(n).forEachIndexed { i, l -> if (i > 0) arr.append(','); arr.append(org.json.JSONObject.quote(l)) }
        return arr.append("]").toString()
    }

    fun crashFile(ctx: Context) = File(ctx.filesDir, "crash.log")

    fun writeCrash(ctx: Context, tr: Throwable) {
        runCatching {
            val f = crashFile(ctx); if (f.length() > 512 * 1024) f.delete()
            f.appendText("\n===== ${Date()} =====\n" + tr.stackTraceToString())
        }
    }

    fun installCrashHandler(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            e("crash", "uncaught in ${t.name}: ${e.message}", e)
            writeCrash(ctx, e)
            prev?.uncaughtException(t, e)
        }
    }
}
