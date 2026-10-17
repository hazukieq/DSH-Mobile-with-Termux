/*
 * dsh_pty.c —— PTY 进程派生层（2026-10-18 终端改造：单例 → 会话表）
 *
 * 职责：在 app 进程内 fork 出挂载伪终端的子进程，把 master fd 交给 Java 层；
 * 提供 resize / signal / wait / close 生命周期原语。
 *
 * ## 两套 API 共存
 *  1) **引擎 legacy API**（`app.dsh.mobile.engine.Pty`）：整套单子进程语义原样保留，
 *     实现改为「薄薄地委托给会话表槽 0」，fork/exec 逻辑只有一份。
 *     引擎靠它启动 node，`close_inherited_fds()` / PR_SET_PDEATHSIG 等语义不变。
 *  2) **终端会话 API**（`app.dsh.mobile.term.PtyNative`）：固定 8 槽会话表，
 *     槽 0 保留给引擎，终端会话从槽 1 起分配 —— 所以终端永远拿不到 0，
 *     引擎重启也永远不需要和终端抢槽。
 *
 * ## 为什么 master fd 的所有权在 Java 侧（重要）
 * legacy 路径的 master fd 由 Java 的 `FileInputStream` 负责关闭（引擎日志泵）。
 * 因此 native 侧在清理槽位时**绝不 close 旧 fd** —— fd 号可能已被系统复用，
 * 误关会关掉无关的 socket（3080/3083 那类事故的另一种写法）。
 * 终端会话则相反：fd 归 native 所有，`closeSession()` 统一 close + 杀进程组 + 回收。
 */
#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include <signal.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <pthread.h>
#include <android/log.h>

#define LOG_TAG "dshpty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/* 会话表容量：1 个引擎槽 + 7 个终端会话（终端 tab 上限远小于此） */
#define MAX_SESSIONS 8

/* 槽 0 是引擎 legacy 路径专用；终端会话从 1 起分配 */
#define LEGACY_SESSION 0

/* Android/Linux 的信号编号上限（bionic 未稳定导出 NSIG，自己定一个） */
#define DSH_NSIG 65

typedef struct {
    pid_t pid;           /* 子进程（会话首进程 = 进程组首进程） */
    int master_fd;       /* PTY master；-1 = 无 */
    int in_use;          /* 槽位占用 */
    int reaped;          /* waitpid 已回收 */
    int status;          /* 原始 wait status */
    pthread_mutex_t lock; /* 逐槽锁：waitExit 阻塞时不挡住其它会话 */
} pty_slot;

static pty_slot g_slots[MAX_SESSIONS];

/* 分配/释放槽位用；只保护「哪一槽空闲」这一件事，不跨阻塞调用持有 */
static pthread_mutex_t g_table_lock = PTHREAD_MUTEX_INITIALIZER;

/* 只读空 envp 兜底（execve 允许 envp[0] == NULL） */
static char *g_empty_envp[] = { NULL };

__attribute__((constructor))
static void dsh_pty_init(void) {
    for (int i = 0; i < MAX_SESSIONS; i++) {
        pthread_mutex_init(&g_slots[i].lock, NULL);
        g_slots[i].pid = -1;
        g_slots[i].master_fd = -1;
        g_slots[i].in_use = 0;
        g_slots[i].reaped = 0;
        g_slots[i].status = 0;
    }
}

/* ------------------------------------------------------------------ */
/* JNI 字符串数组辅助                                                    */
/* ------------------------------------------------------------------ */

/* 从 jobjectArray 读出 char**（UTF-8），失败返回 NULL。
 * 返回的数组保证以 NULL 结尾（calloc），可用 free_string_array 统一释放。 */
static char **read_string_array(JNIEnv *env, jobjectArray arr, jsize *out_len) {
    *out_len = 0;
    if (arr == NULL) return NULL;
    jsize len = (*env)->GetArrayLength(env, arr);
    char **vec = (char **) calloc((size_t) len + 1, sizeof(char *));
    if (!vec) return NULL;
    for (jsize i = 0; i < len; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        if (js == NULL) {
            vec[i] = strdup("");
        } else {
            const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
            vec[i] = strdup(utf ? utf : "");
            if (utf) (*env)->ReleaseStringUTFChars(env, js, utf);
            (*env)->DeleteLocalRef(env, js);
        }
        if (!vec[i]) {
            for (jsize k = 0; k < i; k++) free(vec[k]);
            free(vec);
            return NULL;
        }
    }
    *out_len = len;
    return vec;
}

static void free_string_array(char **vec) {
    if (!vec) return;
    for (int i = 0; vec[i]; i++) free(vec[i]);
    free(vec);
}

/* argv = [cmd, args...]，末尾 NULL。argv[0] 单独 strdup，其余指针借用 args 数组。 */
static char **build_full_argv(const char *cmd, char **args, jsize argc) {
    char **full = (char **) calloc((size_t) argc + 2, sizeof(char *));
    if (!full) return NULL;
    full[0] = strdup(cmd);
    if (!full[0]) { free(full); return NULL; }
    for (jsize i = 0; i < argc; i++) full[i + 1] = args[i];
    return full;
}

/* ------------------------------------------------------------------ */
/* 子进程启动（两套 API 共用的唯一一份 fork/exec 实现）                     */
/* ------------------------------------------------------------------ */

/*
 * 关掉所有 > stderr 的继承 fd。
 *
 * 为什么必须在 execve 前做（2026-10-09 实测事故）：app 进程里的
 * `java.net.ServerSocket`（AgentBridge 的 127.0.0.1:3083）**不带 O_CLOEXEC**
 * —— 实测该 listen socket 的 fdinfo `flags: 04002`（O_RDWR|O_NONBLOCK，没有
 * O_CLOEXEC）。fork 之后引擎把这块 socket 一起带过去了，于是：
 *   AI 随便起的子进程只要还有一个活着，3083 就**永不释放**；
 *   引擎重启时新桥 bind 失败（EADDRINUSE），notify / scr / say 全部哑掉。
 * 关在 execve 前，等于把「引擎及其全部后代」一次性摘干净 —— 后代从引擎继承，
 * 引擎干净了它们自然干净。
 *
 * 终端会话同样受益：每个 shell 都拿到一个干净的 fd 空间。
 *
 * 用 /proc/self/fd 而不是 close_range(2)：后者要 Linux 5.9+，Android 上跨度太大。
 */
static void close_inherited_fds(void) {
    DIR *d = opendir("/proc/self/fd");
    if (!d) return;
    int dfd = dirfd(d);
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        int fd = atoi(e->d_name);
        if (fd > STDERR_FILENO && fd != dfd) close(fd);
    }
    closedir(d);
}

/*
 * fork 之后、execve 之前复位信号状态。
 *
 * 为什么需要：fork 只复制内存，**信号处理器与屏蔽字也会被继承**。父进程是
 * ART/JVM，它为 SIGQUIT/SIGTERM 之类装了自己的处理器；子进程在 execve 前的那
 * 一小段窗口里如果收到 SIGTERM，会跑父进程的处理器（JVM 状态在子进程里根本
 * 不成立）→ 信号被「吃掉」，close()/signal() 派出的 SIGKILL 之前的 TERM 序列
 * 就静默失效。execve 才会把「被捕获的信号」重置为默认，所以必须自己提前做。
 * 这与 posix_spawn 的行为一致；SIG_IGN（我们对 SIGPIPE 的设置）保持不动，
 * 因为「忽略」按 POSIX 语义应当跨 exec 继承。
 */
static void reset_child_signal_state(void) {
    struct sigaction sa;
    for (int sig = 1; sig < DSH_NSIG; sig++) {
        if (sig == SIGKILL || sig == SIGSTOP) continue;
        if (sigaction(sig, NULL, &sa) != 0) continue;
        if (sa.sa_handler != SIG_DFL && sa.sa_handler != SIG_IGN) {
            struct sigaction dfl;
            memset(&dfl, 0, sizeof(dfl));
            dfl.sa_handler = SIG_DFL;
            sigemptyset(&dfl.sa_mask);
            sigaction(sig, &dfl, NULL);
        }
    }
    /* 信号屏蔽字同样会跨 exec 继承：fork 线程可能屏蔽了某些信号，
     * 不清掉的话 shell 会带着继承的屏蔽字启动。 */
    sigset_t empty;
    sigemptyset(&empty);
    sigprocmask(SIG_SETMASK, &empty, NULL);
}

/*
 * 派生 PTY 子进程。
 * cmd 可执行文件绝对路径；full_argv 已含 argv[0]；envp 可为 NULL；
 * cwd 工作目录；rows/cols 终端尺寸。
 * 成功返回 master fd 并写 *out_pid；失败返回负 errno。
 */
static int spawn_pty(const char *cmd, char **full_argv, char **envp,
                     const char *cwd, int rows, int cols, pid_t *out_pid) {
    if (!cmd || !full_argv || !cwd || !out_pid) return -EINVAL;
    if (!envp) envp = g_empty_envp;

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) return -errno;
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        int e = errno;
        close(master);
        return -e;
    }

    char slave_path[128];
    if (ptsname_r(master, slave_path, sizeof(slave_path)) != 0) {
        int e = errno;
        close(master);
        return -e;
    }

    /* 预设窗口尺寸，避免子进程首发 ioctl 前读到 0x0（node-pty / readline 都怕） */
    struct winsize ws = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) cols,
        .ws_xpixel = 0,
        .ws_ypixel = 0,
    };
    ioctl(master, TIOCSWINSZ, &ws); /* 失败不致命 */

    /*
     * 同步管道：父进程等子进程完成 setsid + 信号复位后再返回 openSession()。
     * 否则「刚 spawn 就 signal/close」会撞上进程组还不存在的窗口（kill(-pid)
     * 得到 ESRCH），虽然下方有 kill(pid) 兜底，但让 open 返回时状态已就绪更可靠。
     * 子进程在写一个字节后自己关掉写端；若子进程提前死掉，父进程 read 得到 EOF
     * （= 0），同样能继续（后续 waitExit 会拿到真实退出码）。
     */
    int sync_pipe[2] = {-1, -1};
    if (pipe(sync_pipe) != 0) {
        int e = errno;
        close(master);
        return -e;
    }
    fcntl(sync_pipe[0], F_SETFD, FD_CLOEXEC);
    fcntl(sync_pipe[1], F_SETFD, FD_CLOEXEC);

    pid_t pid = fork();
    if (pid < 0) {
        int e = errno;
        close(sync_pipe[0]);
        close(sync_pipe[1]);
        close(master);
        return -e;
    }

    if (pid == 0) {
        /* ---- 子进程 ----
         * 顺序讲究：setsid → 复位信号 → 通知父进程 → 清继承 fd → 才开 slave。
         * 若先开 slave，close_inherited_fds() 会把它一起关掉。
         * 含父进程传下来的 master：这里一并关掉，下面重开 slave（会拿到最小的
         * 可用 fd，通常是 3），再 dup2 到 0/1/2。 */
        close(sync_pipe[0]);
        setsid();
        reset_child_signal_state();
        ssize_t sync_written = write(sync_pipe[1], "1", 1);
        (void) sync_written;
        close(sync_pipe[1]);
        close_inherited_fds();
        int slave = open(slave_path, O_RDWR);
        if (slave < 0) _exit(126);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        ioctl(STDIN_FILENO, TIOCSCTTY, 0);   /* 设为控制终端 */
        /* 父进程死了要跟着死。setsid 之后子进程已脱离会话，没人回收就是孤儿
         * —— 而孤儿会一直攥着继承来的 3080/3083 监听 socket 不放，端口再也
         * 起不来。SIGTERM 而非 SIGKILL：给引擎机会收尾（落盘、断连）。 */
        prctl(PR_SET_PDEATHSIG, SIGTERM);
        /* 竞态：prctl 生效前父进程可能已经没了，那时 PDEATHSIG 不会补发信号 */
        if (getppid() == 1) _exit(0);
        if (chdir(cwd) != 0) _exit(125);
        execve(cmd, full_argv, envp);
        _exit(127);                          /* execve 失败 */
    }

    /* 父进程：等子进程 setsid 完成（或确认它已提前退出）后再把会话交出去 */
    close(sync_pipe[1]);
    {
        char sync_byte = 0;
        ssize_t r;
        do {
            r = read(sync_pipe[0], &sync_byte, 1);
        } while (r < 0 && errno == EINTR);
    }
    close(sync_pipe[0]);

    *out_pid = pid;
    return master;
}

/* ------------------------------------------------------------------ */
/* 会话表                                                              */
/* ------------------------------------------------------------------ */

static int session_valid(jint sid) {
    return sid > LEGACY_SESSION && sid < MAX_SESSIONS;
}

/* 找一个空闲槽（跳过 0）。成功返回槽号并预占；失败返回 -ENOSPC。 */
static int session_alloc(void) {
    pthread_mutex_lock(&g_table_lock);
    for (int i = LEGACY_SESSION + 1; i < MAX_SESSIONS; i++) {
        pty_slot *s = &g_slots[i];
        pthread_mutex_lock(&s->lock);
        if (!s->in_use) {
            s->in_use = 1;
            s->pid = -1;
            s->master_fd = -1;
            s->reaped = 0;
            s->status = 0;
            pthread_mutex_unlock(&s->lock);
            pthread_mutex_unlock(&g_table_lock);
            return i;
        }
        pthread_mutex_unlock(&s->lock);
    }
    pthread_mutex_unlock(&g_table_lock);
    return -ENOSPC;
}

/* 启动失败时归还预占的槽（不做任何 I/O） */
static void session_release(int sid) {
    pty_slot *s = &g_slots[sid];
    pthread_mutex_lock(&s->lock);
    s->in_use = 0;
    s->pid = -1;
    s->master_fd = -1;
    s->reaped = 0;
    s->status = 0;
    pthread_mutex_unlock(&s->lock);
}

/*
 * SIGKILL 之后最多等 500ms 回收；等不到就交给人手/进程退出（不阻塞 UI 线程）。
 * @param out_status 非 NULL 时写入原始 wait status
 * @return 1 = 已回收，0 = 没等到（其它线程回收 / 进程僵死）
 */
static int reap_with_timeout(pid_t pid, int *out_status) {
    if (pid <= 0) return 0;
    for (int i = 0; i < 100; i++) {
        int status = 0;
        pid_t r = waitpid(pid, &status, WNOHANG);
        if (r == pid) {
            if (out_status) *out_status = status;
            return 1;
        }
        if (r < 0 && errno != EINTR) return 0; /* ECHILD：已被别的线程回收 */
        usleep(5000);
    }
    LOGW("reap timeout for pid %d", (int) pid);
    return 0;
}

/* ------------------------------------------------------------------ */
/* 终 端 会 话 API（app.dsh.mobile.term.PtyNative）                      */
/* ------------------------------------------------------------------ */

/*
 * 打开一个新的终端会话。
 * 返回 sessionId（1..7，>=0）或 -errno。
 */
JNIEXPORT jint JNICALL
Java_app_dsh_mobile_term_PtyNative_openSession(
        JNIEnv *env, jobject thiz,
        jstring jCmd, jobjectArray jArgs, jstring jCwd,
        jobjectArray jEnv, jint rows, jint cols) {
    (void) thiz;
    if (jCmd == NULL || jCwd == NULL || rows <= 0 || cols <= 0) return -EINVAL;

    const char *cmd = (*env)->GetStringUTFChars(env, jCmd, NULL);
    const char *cwd = (*env)->GetStringUTFChars(env, jCwd, NULL);
    jsize argc = 0, envc = 0;
    char **args = read_string_array(env, jArgs, &argc);
    char **envp = read_string_array(env, jEnv, &envc);

    int result = -ENOMEM;
    int slot = -1;
    char **full_argv = NULL;

    if (cmd == NULL || cwd == NULL) { result = -EINVAL; goto done; }
    /* 传了非 NULL 数组却读失败 → 内存不足；NULL 数组按「无」处理 */
    if (jArgs != NULL && args == NULL) goto done;
    if (jEnv != NULL && envp == NULL) goto done;

    full_argv = build_full_argv(cmd, args, argc);
    if (!full_argv) goto done;

    slot = session_alloc();
    if (slot < 0) { result = slot; slot = -1; goto done; }

    signal(SIGPIPE, SIG_IGN);

    pid_t pid = -1;
    int master = spawn_pty(cmd, full_argv, envp, cwd, rows, cols, &pid);
    if (master < 0) {
        session_release(slot);
        slot = -1;
        result = master;
        goto done;
    }

    pty_slot *s = &g_slots[slot];
    pthread_mutex_lock(&s->lock);
    s->pid = pid;
    s->master_fd = master;
    s->in_use = 1;
    s->reaped = 0;
    s->status = 0;
    pthread_mutex_unlock(&s->lock);
    result = slot;

done:
    if (full_argv) { free(full_argv[0]); free(full_argv); }
    free_string_array(args);
    free_string_array(envp);
    if (cmd) (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
    return result;
}

/* master fd；无效会话返回 -EINVAL，fd 不存在返回 -EBADF */
JNIEXPORT jint JNICALL
Java_app_dsh_mobile_term_PtyNative_masterFd(JNIEnv *env, jobject thiz, jint sid) {
    (void) env; (void) thiz;
    if (!session_valid(sid)) return -EINVAL;
    pty_slot *s = &g_slots[sid];
    pthread_mutex_lock(&s->lock);
    int fd = s->in_use ? s->master_fd : -1;
    pthread_mutex_unlock(&s->lock);
    return fd >= 0 ? (jint) fd : -EBADF;
}

/* TIOCSWINSZ；返回 0 或 -errno */
JNIEXPORT jint JNICALL
Java_app_dsh_mobile_term_PtyNative_resize(JNIEnv *env, jobject thiz,
                                          jint sid, jint rows, jint cols) {
    (void) env; (void) thiz;
    if (!session_valid(sid)) return -EINVAL;
    if (rows <= 0 || cols <= 0) return -EINVAL;
    pty_slot *s = &g_slots[sid];
    pthread_mutex_lock(&s->lock);
    int fd = s->in_use ? s->master_fd : -1;
    pthread_mutex_unlock(&s->lock);
    if (fd < 0) return -EBADF;

    struct winsize ws = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) cols,
        .ws_xpixel = 0,
        .ws_ypixel = 0,
    };
    if (ioctl(fd, TIOCSWINSZ, &ws) < 0) return -errno;
    return 0;
}

/* 向整个进程组发信号（子进程 setsid 后 pgid == pid）；0 或 -errno */
JNIEXPORT jint JNICALL
Java_app_dsh_mobile_term_PtyNative_signal(JNIEnv *env, jobject thiz,
                                          jint sid, jint sig) {
    (void) env; (void) thiz;
    if (!session_valid(sid) || sig <= 0) return -EINVAL;
    pty_slot *s = &g_slots[sid];
    pthread_mutex_lock(&s->lock);
    pid_t pid = s->in_use ? s->pid : -1;
    pthread_mutex_unlock(&s->lock);
    if (pid <= 0) return -ESRCH;

    if (kill(-pid, sig) == 0) return 0;   /* 进程组：shell + 全部前台子进程 */
    int group_err = errno;
    /* 兜底：极端时序下（刚 fork 完子进程还没来得及 setsid）进程组还不存在，
     * 此时 -pid 会 ESRCH —— 必须直接打 pid，不能提前返回，
     * 否则「启动后立刻取消」这类调用会静默丢信号。 */
    if (kill(pid, sig) == 0) return 0;
    int pid_err = errno;
    if (group_err == ESRCH && pid_err == ESRCH) return -ESRCH;
    return -(pid_err != 0 ? pid_err : group_err);
}

/*
 * 阻塞 waitpid；返回原始 wait status 或 -errno。
 * 可以在 reader 线程收到 EOF 后调用；重复调用返回缓存值。
 */
JNIEXPORT jint JNICALL
Java_app_dsh_mobile_term_PtyNative_waitExit(JNIEnv *env, jobject thiz, jint sid) {
    (void) env; (void) thiz;
    if (!session_valid(sid)) return -EINVAL;

    pty_slot *s = &g_slots[sid];
    pthread_mutex_lock(&s->lock);
    if (!s->in_use && !s->reaped) {
        pthread_mutex_unlock(&s->lock);
        return -EINVAL;
    }
    if (s->reaped) {
        int cached = s->status;
        pthread_mutex_unlock(&s->lock);
        return (jint) cached;
    }
    pid_t pid = s->pid;
    pthread_mutex_unlock(&s->lock);
    if (pid <= 0) return -ESRCH;

    int status = 0;
    pid_t r;
    do {
        r = waitpid(pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    int err = errno;

    pthread_mutex_lock(&s->lock);
    if (r < 0) {
        if (err == ECHILD && s->reaped) {
            /* closeSession() 抢先回收了：把它的结果给调用方 */
            int cached = s->status;
            pthread_mutex_unlock(&s->lock);
            return (jint) cached;
        }
        s->reaped = 1;
        s->status = 0;
        s->pid = -1;
        pthread_mutex_unlock(&s->lock);
        return -err;
    }
    s->status = status;
    s->reaped = 1;
    s->pid = -1;      /* 已回收；槽位仍占用直到 closeSession 释放 fd */
    pthread_mutex_unlock(&s->lock);
    return (jint) status;
}

/* 子进程 pid；无效会话 -EINVAL，已退出 -ESRCH */
JNIEXPORT jint JNICALL
Java_app_dsh_mobile_term_PtyNative_childPid(JNIEnv *env, jobject thiz, jint sid) {
    (void) env; (void) thiz;
    if (!session_valid(sid)) return -EINVAL;
    pty_slot *s = &g_slots[sid];
    pthread_mutex_lock(&s->lock);
    pid_t pid = s->in_use ? s->pid : -1;
    pthread_mutex_unlock(&s->lock);
    return pid > 0 ? (jint) pid : -ESRCH;
}

/*
 * 关闭会话：杀进程组 → 回收 → 关 master fd → 释放槽位。
 * 幂等：已关闭/无效会话返回 -EINVAL，不抛异常。
 */
JNIEXPORT jint JNICALL
Java_app_dsh_mobile_term_PtyNative_close(JNIEnv *env, jobject thiz, jint sid) {
    (void) env; (void) thiz;
    if (!session_valid(sid)) return -EINVAL;

    pty_slot *s = &g_slots[sid];
    pid_t pid;
    int fd;
    int reaped;

    pthread_mutex_lock(&s->lock);
    if (!s->in_use) {
        pthread_mutex_unlock(&s->lock);
        return -EINVAL;
    }
    pid = s->pid;
    fd = s->master_fd;
    reaped = s->reaped;
    s->in_use = 0;
    s->master_fd = -1;
    s->pid = -1;
    pthread_mutex_unlock(&s->lock);

    /* 先杀进程组：setsid 之后子进程是会话/进程组首进程，负号 pid 打到整棵树
     * （vim 里的 !cmd、proot 的孙进程都在这棵树里）。 */
    if (pid > 0) {
        if (kill(-pid, SIGKILL) != 0) kill(pid, SIGKILL);
        if (!reaped) {
            int status = 0;
            /* 抢在 reader 线程的 waitExit 之前回收时，把状态留在槽里：
             * waitExit 稍后调用会命中 ECHILD 分支并回读它，Java 侧 exitStatus 才准确。 */
            if (reap_with_timeout(pid, &status)) {
                pthread_mutex_lock(&s->lock);
                if (!s->reaped) {
                    s->reaped = 1;
                    s->status = status;
                }
                pthread_mutex_unlock(&s->lock);
            }
        }
    }
    /* 再关 fd：子进程已死 → slave 关闭 → 阻塞中的 reader 收到 EIO 醒来 */
    if (fd >= 0) {
        int r;
        do {
            r = close(fd);
        } while (r < 0 && errno == EINTR);
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* 引 擎 legacy API（app.dsh.mobile.engine.Pty）—— 行为保持不变          */
/* ------------------------------------------------------------------ */

/*
 * 引擎重新 fork 前的槽 0 清理。
 * 只回收已退出的旧子进程；**不 close master fd**（fd 归 Java 侧日志泵所有，
 * 旧实现也从不在此关闭；close 一个可能已被系统复用的 fd 是更严重的事故）。
 * 若旧子进程还活着（正常流程不会发生：EngineProcess.stop 会先等退出），
 * 按旧实现的行为「覆盖登记」，不额外击杀 —— 保持与既有语义一致。
 */
static void legacy_prepare_slot0(void) {
    pty_slot *s = &g_slots[LEGACY_SESSION];
    pthread_mutex_lock(&s->lock);
    if (s->in_use) {
        if (s->pid > 0 && !s->reaped) {
            int status = 0;
            pid_t r = waitpid(s->pid, &status, WNOHANG);
            if (r == s->pid) {
                s->reaped = 1;
                s->status = status;
            } else if (r < 0 && errno == ECHILD) {
                s->reaped = 1;
            }
        }
        s->in_use = 0;
        s->master_fd = -1;
        s->pid = -1;
    }
    pthread_mutex_unlock(&s->lock);
}

/*
 * 派生引擎 PTY 子进程（legacy 签名，行为与改造前一致）。
 * cmd: 可执行文件绝对路径; args: argv[1..]; cwd: 工作目录;
 * env: "KEY=VALUE" 数组; rows/cols: 终端尺寸。
 * 成功返回 master fd，失败返回负的 errno 取反（Java 层取 abs 得 errno）。
 */
JNIEXPORT jint JNICALL
Java_app_dsh_mobile_engine_Pty_nativeForkPty(
        JNIEnv *env, jclass clazz,
        jstring jCmd, jobjectArray jArgs, jstring jCwd,
        jobjectArray jEnv, jint rows, jint cols) {
    (void) clazz;

    const char *cmd = (*env)->GetStringUTFChars(env, jCmd, NULL);
    const char *cwd = (*env)->GetStringUTFChars(env, jCwd, NULL);

    jsize argc = 0, envc = 0;
    char **argv = read_string_array(env, jArgs, &argc);
    char **envp = read_string_array(env, jEnv, &envc);
    char **full_argv = NULL;
    int result;

    if (cmd == NULL || cwd == NULL) {
        result = -EINVAL;
        goto done;
    }
    full_argv = build_full_argv(cmd, argv, argc);
    if (!full_argv) {
        result = -ENOMEM;
        goto done;
    }

    signal(SIGPIPE, SIG_IGN);
    legacy_prepare_slot0();

    pid_t pid = -1;
    int master = spawn_pty(cmd, full_argv, envp, cwd, rows, cols, &pid);
    if (master < 0) {
        result = master;
        goto done;
    }

    pty_slot *s = &g_slots[LEGACY_SESSION];
    pthread_mutex_lock(&s->lock);
    s->pid = pid;
    s->master_fd = master;
    s->in_use = 1;
    s->reaped = 0;
    s->status = 0;
    pthread_mutex_unlock(&s->lock);
    result = master;

done:
    if (result < 0) LOGE("nativeForkPty failed: %s", strerror(-result));
    /* full_argv[0] 单独 strdup；full_argv[1..] 直接引用 argv 内指针，
       因此先 free 容器自身 + 首元素，再交 free_string_array(argv) 统一释放剩余 */
    if (full_argv) { free(full_argv[0]); free(full_argv); }
    free_string_array(argv);
    free_string_array(envp);
    if (cmd) (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
    return result;
}

/* 当前跟踪的子进程 PID（供 Java 层进程组/树级清理）；无子进程返回 -1 */
JNIEXPORT jint JNICALL
Java_app_dsh_mobile_engine_Pty_nativeChildPid(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    pty_slot *s = &g_slots[LEGACY_SESSION];
    pthread_mutex_lock(&s->lock);
    jint out = (s->in_use && s->pid > 0) ? (jint) s->pid : -1;
    pthread_mutex_unlock(&s->lock);
    return out;
}

/* 阻塞等待子进程退出，返回原始 waitpid status（Java 层解析） */
JNIEXPORT jint JNICALL
Java_app_dsh_mobile_engine_Pty_nativeWaitChild(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    pty_slot *s = &g_slots[LEGACY_SESSION];

    pthread_mutex_lock(&s->lock);
    if (!s->in_use || s->pid <= 0) {
        pthread_mutex_unlock(&s->lock);
        return -1;                     /* 与改造前一致：无 tracked child → -1 */
    }
    pid_t pid = s->pid;
    pthread_mutex_unlock(&s->lock);

    int status = 0;
    pid_t r;
    do {
        r = waitpid(pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    int err = errno;

    pthread_mutex_lock(&s->lock);
    if (r < 0) {
        s->in_use = 0;
        s->pid = -1;
        s->master_fd = -1;             /* 不 close：fd 归 Java 日志泵 */
        if (err == ECHILD) s->reaped = 1;
        pthread_mutex_unlock(&s->lock);
        return -err;
    }
    s->status = status;
    s->reaped = 1;
    s->in_use = 0;
    s->pid = -1;                       /* 与改造前一致：之后 nativeChildPid() == -1 */
    s->master_fd = -1;
    pthread_mutex_unlock(&s->lock);
    return (jint) status;
}

/* 向子进程发送信号（SIGTERM=15 / SIGKILL=9）；legacy 语义只打直接子进程 */
JNIEXPORT void JNICALL
Java_app_dsh_mobile_engine_Pty_nativeSignalChild(JNIEnv *env, jclass clazz, jint sig) {
    (void) env; (void) clazz;
    pty_slot *s = &g_slots[LEGACY_SESSION];
    pthread_mutex_lock(&s->lock);
    pid_t pid = (s->in_use && s->pid > 0) ? s->pid : -1;
    pthread_mutex_unlock(&s->lock);
    if (pid > 0) kill(pid, sig);
}

/* Java 层关闭 master fd 后子进程收到 SIGHUP 会自然退出，此处兜底强杀 */
JNIEXPORT void JNICALL
Java_app_dsh_mobile_engine_Pty_nativeForceKill(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    pty_slot *s = &g_slots[LEGACY_SESSION];
    pthread_mutex_lock(&s->lock);
    pid_t pid = (s->in_use && s->pid > 0) ? s->pid : -1;
    pthread_mutex_unlock(&s->lock);
    if (pid > 0) kill(pid, SIGKILL);
}
