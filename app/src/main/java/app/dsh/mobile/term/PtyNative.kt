package app.dsh.mobile.term

/**
 * PTY 会话原语绑定（终端专用）。
 *
 * 与引擎侧的 [app.dsh.mobile.engine.Pty] 是**两套独立的 JNI 入口**：
 *  - `engine.Pty`：单子进程（node 引擎），历史包袱重，行为不允许变；
 *  - `PtyNative`：固定 8 槽会话表（槽 0 留给引擎），支持多终端 tab。
 *
 * 两者共用 `libdshpty.so` 里同一份 fork/exec 实现（见 `app/src/main/cpp/dsh_pty.c`），
 * 所以 16KB 页对齐、`close_inherited_fds`、`PR_SET_PDEATHSIG` 这些坑只需要踩一次。
 *
 * ## fd 所有权约定（重要）
 * 本类的 master fd **归 native 所有**：Java 侧只允许 `Os.read` / `Os.write`，
 * 绝对不要 `ParcelFileDescriptor.adoptFd` 或 `FileInputStream.close()` ——
 * 那会关掉 native 仍记账的 fd，随后 [close] 再关一次就可能关到被系统复用的
 * 无关 fd（引擎侧曾经踩过同款事故）。会话结束统一走 [close]，由 native
 * 负责 close fd + 杀进程组 + 回收。
 */
object PtyNative {

    init {
        System.loadLibrary("dshpty")
    }

    /** 会话表容量（含引擎槽 0），必须与 dsh_pty.c 的 MAX_SESSIONS 一致 */
    const val MAX_SESSIONS = 16

    /**
     * App 内终端 tab 并发上限（槽 1..7）。
     *
     * 表本身扩到 16 是为了给「引擎侧终端」留位置（dsh WebUI 侧边栏经
     * [app.dsh.mobile.engine.PtyBridge] 申请 PTY），但 App 自己仍只允许 7 个 tab ——
     * 这是刻意的产品限制，不随表容量放宽。
     */
    const val MAX_TERMINAL_SESSIONS = 7

    // ------------------------------------------------------------------
    // native 入口（命名约定绑定，见 dsh_pty.c）
    // ------------------------------------------------------------------

    /**
     * 打开一个挂载伪终端的新会话（openSession 是 JNI 名，Kotlin 侧用 [open] 包装）。
     * @return sessionId（1..7）或 -errno
     */
    external fun openSession(
        cmd: String,
        args: Array<out String>,
        cwd: String,
        env: Array<out String>,
        rows: Int,
        cols: Int,
    ): Int

    /** master fd；无效会话 -errno */
    external fun masterFd(session: Int): Int

    /** TIOCSWINSZ；返回 0 或 -errno */
    external fun resize(session: Int, rows: Int, cols: Int): Int

    /** 向会话进程组发信号；返回 0 或 -errno */
    external fun signal(session: Int, sig: Int): Int

    /** 阻塞 waitpid；返回原始 wait status 或 -errno（可在 reader 线程重复调用） */
    external fun waitExit(session: Int): Int

    /** 子进程 pid；无效 -EINVAL，已退出 -ESRCH */
    external fun childPid(session: Int): Int

    /** 关闭会话：close master fd + SIGKILL 进程组 + 回收 + 释放槽；幂等 */
    external fun close(session: Int): Int

    // ------------------------------------------------------------------
    // 契约包装（design doc §4.A 的公开名字）
    // ------------------------------------------------------------------

    /**
     * 打开终端会话（设计文档中的契约名）。
     * @return sessionId（>=0）或 -errno
     */
    fun open(
        cmd: String,
        args: Array<String>,
        cwd: String,
        env: Array<String>,
        rows: Int,
        cols: Int,
    ): Int = openSession(cmd, args, cwd, env, rows, cols)

    /** -errno → errno（非负返回 0），错误日志/异常文案用 */
    fun errnoOf(rc: Int): Int = if (rc < 0) -rc else 0
}
