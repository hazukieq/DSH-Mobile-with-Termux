package app.dsh.mobile.term

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import app.dsh.mobile.engine.ShellSpec
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个终端会话：**PTY 子进程 + 模拟器 + reader 线程**。
 *
 * 数据流：`master fd --Os.read--> ByteArray --emulator.append--> TerminalCell`
 *  → View 下一帧渲染。输入方向：`View --write()--> Os.write --> master fd`。
 *
 * ## 线程
 *  - **reader 线程**（本类创建，daemon）：阻塞 `Os.read`；EOF/EIO 后 `waitExit`
 *    收尸、置 `isRunning=false`、依次触发 `onExited` / 内部清理。
 *  - 写/信号/resize 可以从任意线程调用；native 侧按会话加锁。
 *
 * ## fd 所有权
 * `masterFd` 由 native 持有并负责关闭（[PtyNative.close]），本类**只借用**
 * 一个 `java.io.FileDescriptor` 包装来读写。这里刻意不用
 * `ParcelFileDescriptor.adoptFd`：PFD 的 finalize/close 会关掉 fd，
 * 与 native 的 closeSession 叠加就是二次关闭（fd 号可能已被复用 → 关错对象）。
 * 反射注入 descriptor 是这个项目已有的做法（见 `EngineProcess.pumpLoop`）。
 *
 * ## 契约
 * 见 `docs/DEV_TERMINAL_LINUX.md` §4.A。对外承诺的成员都保持原签名；
 * 额外成员只增不改（`onTerminated` 是 internal，给 [TerminalSessions] 维护列表用）。
 */
class TerminalSession internal constructor(
    val sessionId: Int,
    val spec: ShellSpec,
    val emulator: TerminalEmulator,
    val masterFd: Int,
    val pid: Int,
) {

    /** 供 TerminalSessions 在会话结束时把自己从 live 列表里摘掉 */
    internal var onTerminated: (() -> Unit)? = null

    /** 内容更新（已按 append 节流；reader 线程回调，UI 侧自行切主线程） */
    @Volatile
    var onUpdate: ((TerminalSession) -> Unit)? = null

    /** 子进程退出（reader 线程回调；`exitStatus` 已就绪） */
    @Volatile
    var onExited: ((TerminalSession) -> Unit)? = null

    @Volatile
    var isRunning: Boolean = true
        private set

    /**
     * 原始 wait status（低 7 位 = 终止信号，0 表示正常退出；高 8 位 = 退出码）。
     * 未退出时为 -1。
     */
    @Volatile
    var exitStatus: Int = -1
        private set

    /** 标题：优先 OSC 0/2 设置的，其次 ShellSpec.title */
    val title: String
        get() = emulator.title.ifEmpty { spec.title }

    private val closed = AtomicBoolean(false)
    private val terminated = AtomicBoolean(false)

    /** native fd 的只读包装（不拥有 fd） */
    private val fd: FileDescriptor = wrapFd(masterFd)

    private val readerThread = Thread({ readerLoop() }, "dsh-term-$sessionId").apply {
        isDaemon = true
        start()
    }

    // ------------------------------------------------------------------
    // 输出方向（reader 线程）
    // ------------------------------------------------------------------

    private fun readerLoop() {
        val buffer = ByteArray(READ_BUFFER)
        try {
            while (!closed.get()) {
                val n = readChunk(buffer)
                if (n > 0) {
                    emulator.append(buffer, 0, n)
                    onUpdate?.invoke(this)
                } else {
                    // n == 0：EOF；n < 0：已消费的 EINTR / 致命错误（EIO 等）
                    break
                }
            }
        } catch (t: Throwable) {
            if (!closed.get()) Log.w(TAG, "reader stopped: ${t.message}")
        }
        finish()
    }

    /** @return 读到的字节数；-1 表示结束或不可恢复错误（EINTR 已内部重试） */
    private fun readChunk(buffer: ByteArray): Int {
        while (!closed.get()) {
            try {
                return Os.read(fd, buffer, 0, buffer.size)
            } catch (e: ErrnoException) {
                when (e.errno) {
                    OsConstants.EINTR -> continue
                    // PTY 从端全部关闭后 master read 返回 EIO —— 正常结束路径
                    OsConstants.EIO -> return -1
                    OsConstants.EBADF -> return -1
                    else -> {
                        Log.w(TAG, "read errno=${e.errno}")
                        return -1
                    }
                }
            }
        }
        return -1
    }

    private fun finish() {
        if (!terminated.compareAndSet(false, true)) return
        // 先收尸：waitExit 可能已经被 close() 抢先回收（返回缓存值/ECHILD）。
        // 原始 wait status 恒 >= 0；负值是 -errno（槽位已被 close() 释放），记 -1 表示未知。
        val raw = runCatching { PtyNative.waitExit(sessionId) }.getOrDefault(-1)
        exitStatus = if (raw >= 0) raw else -1
        isRunning = false
        // 关闭 native 会话：kill 进程组（若还活着）+ close fd + 释放槽位
        runCatching { PtyNative.close(sessionId) }
        Log.i(TAG, "session $sessionId exited, status=$exitStatus")
        runCatching { onExited?.invoke(this) }
        runCatching { onTerminated?.invoke() }
    }

    // ------------------------------------------------------------------
    // 输入方向
    // ------------------------------------------------------------------

    /** 写入原始字节（UTF-8 文本请先用 String.toByteArray） */
    fun write(bytes: ByteArray) {
        if (bytes.isEmpty() || !isRunning) return
        var off = 0
        try {
            while (off < bytes.size) {
                val n = Os.write(fd, bytes, off, bytes.size - off)
                if (n <= 0) break
                off += n
            }
        } catch (e: ErrnoException) {
            // 会话刚关闭 / 子进程已退出：静默（终端输入失败不该打断 UI）
            if (!closed.get()) Log.w(TAG, "write errno=${e.errno}")
        }
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /** 调整尺寸：先改模拟器（渲染立即对齐），再 TIOCSWINSZ 通知子进程（SIGWINCH） */
    fun resize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        emulator.resize(rows, cols)
        if (!isRunning) return
        runCatching { PtyNative.resize(sessionId, rows, cols) }
    }

    /** 发信号（15=TERM / 9=KILL / 2=INT …），打到整个进程组 */
    fun signal(sig: Int) {
        if (sig <= 0 || !isRunning) return
        runCatching { PtyNative.signal(sessionId, sig) }
    }

    /**
     * 主动关闭：SIGKILL 进程组 → 关 fd → 由 reader 线程完成收尾。
     * 绝不抛异常（Activity.onDestroy、进程退出路径都会调它）。
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { PtyNative.close(sessionId) }
        // reader 线程会因 EIO 醒来并走 finish()；不在这里 join（主线程不能阻塞）
    }

    companion object {
        private const val TAG = "TerminalSession"
        private const val READ_BUFFER = 8192

        /**
         * 把 native fd 包成 FileDescriptor（只读视图，不接管生命周期）。
         * 反射注入 descriptor 是 Android 平台惯例（libcore 未提供公开构造）。
         */
        private fun wrapFd(fd: Int): FileDescriptor {
            val desc = FileDescriptor()
            if (fd < 0) return desc
            try {
                val field = FileDescriptor::class.java.getDeclaredField("descriptor")
                field.isAccessible = true
                field.setInt(desc, fd)
            } catch (t: Throwable) {
                // 极端 ROM 上反射被限制：抛给上层，避免 reader 线程读 -1 号 fd
                throw IllegalStateException("cannot wrap pty master fd", t)
            }
            return desc
        }
    }
}

/**
 * 终端会话注册表（对应设计文档 §4.A 的 `TerminalSessions`）。
 *
 * 生命周期：Activity 创建会话 → tab 切换保留 → `onDestroy`/进程退出时 [closeAll]。
 * `all` 是**实时**视图（会话退出会自己摘除），调用方可以放心持有引用。
 */
object TerminalSessions {

    private const val TAG = "TerminalSessions"

    private val sessions = CopyOnWriteArrayList<TerminalSession>()

    /** 当前存活会话（含正在退出的；以 `isRunning` 区分） */
    val all: List<TerminalSession> get() = sessions

    /** 全局 applicationContext（由 TerminalView/Activity 注入，用于 defaultEnv 解析 filesDir） */
    @Volatile
    private var appCtx: android.content.Context? = null

    fun setContext(context: android.content.Context) {
        appCtx = context.applicationContext
    }

    /**
     * 创建会话。失败抛 [IOException]（含 errno），调用方负责提示。
     *
     * @param rows 初始行数（View attach 后会用真实尺寸再 resize 一次）
     * @param cols 初始列数
     */
    @Throws(IOException::class)
    fun create(spec: ShellSpec, rows: Int, cols: Int): TerminalSession {
        if (rows <= 0 || cols <= 0) throw IOException("invalid terminal size ${rows}x$cols")
        if (sessions.count { it.isRunning } >= PtyNative.MAX_TERMINAL_SESSIONS) {
            throw IOException("too many terminal sessions")
        }
        // spec.env 是「完整清单」；空表时补一份能起 shell 的默认环境
        val env = if (spec.env.isEmpty()) defaultEnv() else spec.env
        val sessionId = PtyNative.open(
            cmd = spec.cmd,
            args = spec.args.toTypedArray(),
            cwd = spec.cwd,
            env = env.toTypedArray(),
            rows = rows,
            cols = cols,
        )
        if (sessionId < 0) {
            val errno = -sessionId
            throw IOException("pty open failed: errno=$errno (${describeErrno(errno)}) cmd=${spec.cmd}")
        }
        val masterFd = PtyNative.masterFd(sessionId)
        val pid = PtyNative.childPid(sessionId)
        if (masterFd < 0 || pid <= 0) {
            runCatching { PtyNative.close(sessionId) }
            throw IOException("pty open returned bad fd/pid (fd=$masterFd pid=$pid)")
        }

        val emulator = TerminalEmulator(rows, cols)
        val session = try {
            TerminalSession(sessionId, spec, emulator, masterFd, pid)
        } catch (t: Throwable) {
            // descriptor 反射失败等极端情况：别把 native 会话/子进程留成孤儿
            runCatching { PtyNative.close(sessionId) }
            throw IOException("terminal session init failed", t)
        }
        session.onTerminated = { sessions.remove(session) }
        sessions += session
        Log.i(TAG, "session $sessionId started pid=$pid cmd=${spec.cmd} ${rows}x$cols")
        return session
    }

    /** 关闭所有会话（App 退出 / Activity 销毁） */
    fun closeAll() {
        val snapshot = sessions.toList()
        sessions.clear()
        for (s in snapshot) {
            runCatching { s.close() }
        }
    }

    /**
     * 默认环境变量（"KEY=VALUE" 完整列表）。
     *
     * 注意这是**兜底**：`ShellProfiles.androidSpec` 会传入 `EngineConfig.buildEnv`
     * 的完整引擎环境（PATH 里有 node/git/python 扩展），Linux 会话由 LinuxEnv 给。
     * 这里只保证「随便传个 /system/bin/sh 也能起一个可用的 shell」。
     */
    fun defaultEnv(): List<String> {
        val filesDir = appCtx?.filesDir ?: guessFilesDir()
        val home = File(filesDir, "dsh-home").apply { mkdirs() }
        val tmp = File(filesDir, "tmp").apply { mkdirs() }
        val engine = File(filesDir, "engine")
        val pathParts = buildList {
            add(File(engine, "bin"))
            add(File(engine, "usr/bin"))
            add(File(filesDir, "bin"))
            add(File("/system/bin"))
            add(File("/system/xbin"))
        }.filter { it.path.startsWith("/system") || it.isDirectory }
        return listOf(
            "HOME=" + home.absolutePath,
            "PWD=" + home.absolutePath,
            "TMPDIR=" + tmp.absolutePath,
            "PATH=" + pathParts.joinToString(":") { it.absolutePath },
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            // Android 标准环境（init 对普通进程的设定）；aapt/apksigner 依赖 ANDROID_DATA
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "ANDROID_STORAGE=/storage",
            "SHELL=/system/bin/sh",
            "DSH_TERMINAL=1",
        )
    }

    /**
     * 未注入 Context 时猜 app 的 filesDir。
     * 进程名就是包名（`/proc/self/cmdline`），据此拼 `/data/user/0/<pkg>/files`；
     * 这是零权限、零反射的做法（不依赖隐藏 API / BuildConfig）。
     */
    private fun guessFilesDir(): File {
        val pkg = runCatching {
            File("/proc/self/cmdline").readText()
                .substringBefore('\u0000')
                .trim()
                .substringBefore(':')
        }.getOrNull()?.takeIf { it.contains('.') && !it.contains(' ') } ?: "app.dsh.mobile"
        for (base in listOf("/data/user/0/$pkg", "/data/data/$pkg")) {
            val dir = File(base)
            if (dir.isDirectory) return File(dir, "files")
        }
        return File("/data/data/$pkg/files")
    }

    private fun describeErrno(errno: Int): String = when (errno) {
        OsConstants.EACCES -> "EACCES"
        OsConstants.ENOENT -> "ENOENT"
        OsConstants.EPERM -> "EPERM"
        OsConstants.ENOMEM -> "ENOMEM"
        OsConstants.ENOSPC -> "ENOSPC"
        OsConstants.EINVAL -> "EINVAL"
        OsConstants.EBUSY -> "EBUSY"
        else -> "errno $errno"
    }
}
