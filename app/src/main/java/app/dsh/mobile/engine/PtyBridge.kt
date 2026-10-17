package app.dsh.mobile.engine

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import app.dsh.mobile.term.PtyNative
import java.io.FileDescriptor

/**
 * 引擎侧 PTY 服务：把 App 的 PTY（`libdshpty.so`）借给引擎用。
 *
 * ## 为什么需要它
 *
 * dsh WebUI 侧边栏的终端走 `dsh-terminal-bash` → `dsh-subprocess-local` →
 * `node-pty`。Android 上 node-pty 没有可用预编译（它的 `.node` 是 glibc/x64 的），
 * 运行时里那份 `node-pty` 是构建期打的**空壳**（`spawn()` 直接 throw），所以侧边栏
 * 一开终端就报错。`libdshpty.so` 早就有真 PTY 实现（fork/exec + /dev/ptmx +
 * PR_SET_PDEATHSIG），缺的只是「引擎进程怎么用它」。
 *
 * 本类就是那座桥：引擎侧的 `node-pty/lib/index.js`（assets/runtime-overlay 里部署）
 * 通过 AgentBridge 的 pty 系列端点申请/读写/缩放/关闭 PTY，真实 PTY 由 App 进程
 * 用 [PtyNative] 打开。于是侧边栏终端与 App 内终端是**同一套 PTY 语义**
 * （真 tty、作业控制、Ctrl-C、resize），而不是管道冒充的假终端。
 *
 * ## 数据通道
 *
 * 每会话一个 reader 线程：阻塞 `Os.read(master)` → 写进本会话的**字节环形缓冲**
 * （保留最近 [MAX_BUFFER] 字节，绝对序号 `seq` 单调递增）。HTTP 侧用
 * `GET /pty/read?id&seq&timeout` 长轮询取增量：客户端记住自己的 `seq`，
 * 服务端只回它之后的新字节；若客户端落后超过缓冲容量，回 `lost: true` 让它重画。
 *
 * ## 与 App 内终端的关系
 *
 * 共用 C 层的 16 槽会话表（槽 1..15），App 内终端仍只允许 7 个 tab（见
 * [PtyNative.MAX_TERMINAL_SESSIONS]），引擎侧另限 [MAX_ENGINE_SESSIONS] 个。
 * 子进程带 `PR_SET_PDEATHSIG`，App 进程一死它们随之退出，不会留孤儿。
 */
object PtyBridge {

    private const val TAG = "dsh-pty-bridge"

    /** 引擎侧（网页侧边栏 / AI 终端工具）并发上限 */
    private const val MAX_ENGINE_SESSIONS = 6

    /** 单次 `Os.read` 的缓冲 */
    private const val READ_BUF = 16 * 1024

    /** 每会话最多缓存多少**未读**字节；超出后丢最旧（客户端会收到 lost 并重画） */
    private const val MAX_BUFFER = 512 * 1024

    private const val DEFAULT_READ_TIMEOUT_MS = 20_000

    private const val DEFAULT_ROWS = 24
    private const val DEFAULT_COLS = 80

    private class Session(val id: Int, val pid: Int, val fd: FileDescriptor) {
        val lock = Object()
        var buf = ByteArray(64 * 1024)
        /** buf 里有效字节数 */
        var valid = 0
        /** 历史累计写入字节数（= 缓冲首字节绝对序号 + valid） */
        var written = 0L
        var exited = false
        var exitCode = -1
        var signalNum = 0
        var closed = false

        fun baseSeq(): Long = written - valid

        fun append(src: ByteArray, n: Int) {
            if (n <= 0) return
            if (valid + n > MAX_BUFFER) {
                // 只保留最近一半，给后续追加留空间（丢最旧的前提：客户端能从 lost 恢复）
                val keep = MAX_BUFFER / 2
                val drop = valid + n - keep
                if (drop >= valid) {
                    valid = 0
                } else {
                    System.arraycopy(buf, drop, buf, 0, valid - drop)
                    valid -= drop
                }
            }
            if (valid + n > buf.size) {
                var cap = buf.size
                while (cap < valid + n) cap = cap shl 1
                buf = buf.copyOf(cap)
            }
            System.arraycopy(src, 0, buf, valid, n)
            valid += n
            written += n
        }

        fun chunkFrom(fromSeq: Long, max: Int): Pair<ByteArray, Boolean> {
            val base = baseSeq()
            val lost = fromSeq < base
            val start = if (lost) base else fromSeq
            val off = (start - base).toInt().coerceIn(0, valid)
            val len = (valid - off).coerceAtMost(max)
            val out = ByteArray(len)
            if (len > 0) System.arraycopy(buf, off, out, 0, len)
            return out to lost
        }
    }

    /** open 的结果（会话句柄对外只暴露 id/pid，内部对象保持私有） */
    data class Opened(val id: Int, val pid: Int)

    /** `/pty/read` 的返回：数据 + 下一个序号 + 退出信息 */
    data class Chunk(
        val data: ByteArray,
        val nextSeq: Long,
        val exited: Boolean,
        val exitCode: Int,
        val signal: Int,
        val lost: Boolean,
    )

    private val sessions = HashMap<Int, Session>()
    private val tableLock = Object()

    /**
     * 所有 fork 都必须在这个**常驻线程**上做。
     *
     * 原因（2026-10-18 实测踩坑）：C 层给子进程设了 `PR_SET_PDEATHSIG(SIGTERM)`，
     * 而 PDEATHSIG 的触发条件是「**发起 fork 的那个线程**结束」，不是父进程结束。
     * AgentBridge 是每个请求一个线程 —— 直接在请求线程里 fork，HTTP 响应一返回
     * 线程就退出，PTY 子进程立刻收到 SIGTERM 被秒杀（现象：open 成功、read 立刻
     * 返回 exited=true / signal=15，终端一行输出都没有）。
     * 放到常驻线程上 fork，子进程的生命周期就只跟「App 进程」绑定。
     */
    private val spawnQueue = java.util.concurrent.LinkedBlockingQueue<() -> Unit>()

    @Volatile private var spawner: Thread? = null

    private fun spawnerThread(): Thread {
        spawner?.let { return it }
        synchronized(tableLock) {
            spawner?.let { return it }
            val t = Thread({
                while (true) {
                    val task = try {
                        spawnQueue.take()
                    } catch (e: InterruptedException) {
                        continue
                    }
                    try {
                        task()
                    } catch (t: Throwable) {
                        Log.w(TAG, "pty spawner task failed: ${t.message}")
                    }
                }
            }, "pty-spawner").apply { isDaemon = true; start() }
            spawner = t
            return t
        }
    }

    /** 在 spawner 线程上同步执行（fork 系调用专用），最多等 [timeoutMs] */
    private fun <T> onSpawner(timeoutMs: Long = 20_000, block: () -> T): T {
        spawnerThread()
        val box = java.util.concurrent.CompletableFuture<T>()
        spawnQueue.put {
            try {
                box.complete(block())
            } catch (t: Throwable) {
                box.completeExceptionally(t)
            }
        }
        try {
            return box.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            throw if (cause is IllegalStateException) cause else IllegalStateException(
                cause?.message ?: "pty spawn failed",
            )
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IllegalStateException("pty spawner timed out")
        }
    }

    /** 引擎侧当前会话数（诊断用） */
    fun count(): Int = synchronized(tableLock) { sessions.size }

    /**
     * 打开一个真 PTY 会话。
     *
     * @param env 引擎侧传来的完整环境（`KEY=VALUE` 对）；这里补 TERM/PATH 兜底
     * @throws IllegalStateException 失败原因（ASCII，直接回给引擎）
     */
    fun open(
        ctx: android.content.Context,
        file: String,
        args: List<String>,
        cwd: String,
        env: Map<String, String>,
        rows: Int,
        cols: Int,
        term: String,
    ): Opened {
        require(file.isNotBlank()) { "empty file" }
        synchronized(tableLock) {
            if (sessions.size >= MAX_ENGINE_SESSIONS) {
                throw IllegalStateException("too many engine terminals (max $MAX_ENGINE_SESSIONS)")
            }
        }
        val merged = LinkedHashMap<String, String>()
        for ((k, v) in env) {
            if (k.isNotEmpty() && !k.contains('=') && !k.contains('\u0000')) merged[k] = v
        }
        if (merged["TERM"].isNullOrBlank()) merged["TERM"] = term.ifBlank { "xterm-256color" }
        if (merged["PATH"].isNullOrBlank()) {
            merged["PATH"] = EngineConfig.engineRoot(ctx).absolutePath + "/bin:/system/bin:/system/xbin"
        }
        val envArr = merged.map { (k, v) -> "$k=$v" }
            .filter { !it.contains('\u0000') }
            .toTypedArray()
        // cwd 兜底：引擎给的 spec.cwd 可能在本机不存在（如会话工作区没建、或
        // 上游按桌面习惯给了 /root、/home/xxx）。C 层 chdir 失败会 _exit(125)，
        // 表现是「终端一打开就退出、无输出」——这里先落到存在的目录，并记日志。
        val cwdFile = java.io.File(cwd)
        val realCwd = if (cwdFile.isDirectory) cwdFile.absolutePath
        else EngineConfig.dshHome(ctx).absolutePath
        if (realCwd != cwd) Log.w(TAG, "open: cwd '$cwd' unavailable, fallback to $realCwd")
        val r = rows.coerceIn(1, 512)
        val c = cols.coerceIn(1, 512)
        // fork/exec 必须在常驻 spawner 线程上做（PDEATHSIG 绑线程，见 spawnerThread 注释）
        val opened = onSpawner {
            val sid = PtyNative.open(file, args.toTypedArray(), realCwd, envArr, r, c)
            if (sid < 0) throw IllegalStateException("pty open failed (errno ${PtyNative.errnoOf(sid)})")
            val mfd = PtyNative.masterFd(sid)
            if (mfd < 0) {
                runCatching { PtyNative.close(sid) }
                throw IllegalStateException("pty master fd unavailable (errno ${-mfd})")
            }
            val pid = PtyNative.childPid(sid).let { if (it > 0) it else -1 }
            Triple(sid, pid, mfd)
        }
        val sid = opened.first
        val pid = opened.second
        val session = Session(sid, pid, wrapFd(opened.third))
        synchronized(tableLock) { sessions[sid] = session }
        startReader(session)
        Log.i(TAG, "engine terminal opened: id=$sid pid=$pid ${file.substringAfterLast('/')} ${r}x$c")
        return Opened(sid, pid)
    }

    /** 诊断用：把当前会话列成 `id=pid` 串 */
    fun describe(): String = synchronized(tableLock) {
        sessions.entries.joinToString(",") { (sid, s) -> "$sid=${s.pid}${if (s.exited) "x" else ""}" }
    }

    fun write(id: Int, data: ByteArray): Int {
        val s = session(id) ?: throw IllegalStateException("no such pty session: $id")
        if (data.isEmpty()) return 0
        var off = 0
        try {
            while (off < data.size) {
                val n = Os.write(s.fd, data, off, data.size - off)
                if (n <= 0) break
                off += n
            }
        } catch (e: ErrnoException) {
            if (!s.closed) throw IllegalStateException("write failed (errno ${e.errno})")
        }
        return off
    }

    fun resize(id: Int, rows: Int, cols: Int) {
        val s = session(id) ?: throw IllegalStateException("no such pty session: $id")
        val rc = PtyNative.resize(s.id, rows.coerceIn(1, 512), cols.coerceIn(1, 512))
        if (rc < 0) throw IllegalStateException("resize failed (errno ${-rc})")
    }

    fun signal(id: Int, sig: Int) {
        val s = session(id) ?: throw IllegalStateException("no such pty session: $id")
        val rc = PtyNative.signal(s.id, sig)
        if (rc < 0) throw IllegalStateException("signal failed (errno ${-rc})")
    }

    /** 关闭会话（杀进程组 + 关 fd + 释放槽）；幂等 */
    fun close(id: Int) {
        val s = synchronized(tableLock) { sessions.remove(id) } ?: return
        synchronized(s.lock) { s.closed = true; s.lock.notifyAll() }
        runCatching { PtyNative.close(s.id) }
        Log.i(TAG, "engine terminal closed: id=$id")
    }

    /**
     * 长轮询读增量。
     *
     * @param fromSeq 客户端已消费到的绝对序号（0 = 从头）
     * @param timeoutMs 没有新数据时最多等待多久；0 = 立即返回
     */
    fun read(id: Int, fromSeq: Long, timeoutMs: Int): Chunk {
        val s = session(id) ?: throw IllegalStateException("no such pty session: $id")
        val deadline = System.currentTimeMillis() +
            timeoutMs.coerceIn(0, DEFAULT_READ_TIMEOUT_MS * 2).toLong()
        synchronized(s.lock) {
            while (true) {
                val has = s.written > fromSeq || s.exited || s.closed
                if (has) break
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                runCatching { s.lock.wait(left.coerceAtMost(500L)) }
            }
            val (bytes, lost) = s.chunkFrom(fromSeq, READ_BUF)
            return Chunk(bytes, s.written, s.exited, s.exitCode, s.signalNum, lost)
        }
    }

    /** App 进程退出/切换时兜底清理（引擎重启不会触发：会话属于 App 进程） */
    fun closeAll() {
        val ids = synchronized(tableLock) { sessions.keys.toList() }
        ids.forEach { close(it) }
    }

    // ── 内部 ──

    private fun session(id: Int): Session? = synchronized(tableLock) { sessions[id] }

    private fun startReader(s: Session) {
        Thread({
            val buf = ByteArray(READ_BUF)
            while (!s.closed) {
                val n = try {
                    Os.read(s.fd, buf, 0, buf.size)
                } catch (e: ErrnoException) {
                    when (e.errno) {
                        OsConstants.EINTR -> continue
                        else -> -1
                    }
                }
                if (n <= 0) break
                synchronized(s.lock) {
                    s.append(buf, n)
                    s.lock.notifyAll()
                }
            }
            // EOF/EIO：收尸并广播退出（exitCode 用 wait status 解码，signal 单独给）
            val raw = runCatching { PtyNative.waitExit(s.id) }.getOrDefault(-1)
            synchronized(s.lock) {
                s.exited = true
                s.exitCode = if (raw >= 0) (raw shr 8) and 0xFF else -1
                s.signalNum = if (raw >= 0) raw and 0x7F else 0
                s.lock.notifyAll()
            }
            Log.i(TAG, "engine terminal exited: id=${s.id} status=$raw")
        }, "pty-bridge-${s.id}").apply { isDaemon = true; start() }
    }

    /** 把 native fd 包成 FileDescriptor（只读视图；生命周期归 native） */
    private fun wrapFd(fd: Int): FileDescriptor {
        val desc = FileDescriptor()
        if (fd < 0) return desc
        try {
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.setInt(desc, fd)
        } catch (t: Throwable) {
            throw IllegalStateException("cannot wrap pty master fd", t)
        }
        return desc
    }
}
