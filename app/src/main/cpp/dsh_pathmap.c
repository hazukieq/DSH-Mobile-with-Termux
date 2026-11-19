/*
 * dsh_pathmap.c —— 把「Linux 习惯路径」透明映射到 Android 可写目录
 *
 * ## 为什么需要
 * Android 的 `/tmp` 是 shell:shell 的 tmpfs（mode 0771），App（普通 UID）
 * 只能进入、不能写：agent 每写一次 `/tmp/xxx.log` 就 EACCES，然后换个路径重试。
 * `/var/tmp`、`/dev/shm`、`/run` 在多数设备上干脆不存在。本 .so 通过
 * LD_PRELOAD 拦截 libc 路径入口，把这几类前缀整体改写到 `$DSH_TMPDIR`，
 * 让「写 /tmp」在 App 内变成「写 $TMPDIR」，无需 root、无需改内核。
 *
 * ## 边界（重要）
 * - 只处理**绝对路径 + 固定前缀**，其余一律原样透传（零成本）。
 * - 只覆盖 libc 入口；静态链接（Go/rust static）与直接 syscall 不受影响 ——
 *   这是刻意的取舍：前者无法拦截，后者很少见。
 * - 解决不了「内核自己解析 shebang」：`#!` 解释器路径由内核处理，靠 Termux
 *   自带的 libtermux-exec.so（本模块会把它串在 LD_PRELOAD 链里）修复。
 * - 映射目标不存在时**不创建**（Java 侧负责建目录），避免在错误时机 mkdir。
 *
 * ## 线程安全
 * 映射缓冲区是 __thread，重入安全；真实符号在首次调用时缓存，不做加锁。
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/types.h>

#define DSH_PATH_MAX (PATH_MAX > 0 ? PATH_MAX : 4096)

/* 映射后的路径缓冲：每条路径最多变长一点点，双倍冗余足够 */
static __thread char g_map[DSH_PATH_MAX * 2];

static const char *dsh_tmp_root(void) {
    static char root[DSH_PATH_MAX];
    static int ready;
    if (!ready) {
        const char *v = getenv("DSH_TMPDIR");
        if (!v || !*v) v = getenv("TMPDIR");
        if (!v || !*v) v = "/data/data/com.termux/files/usr/tmp";
        snprintf(root, sizeof(root), "%s", v);
        /* 去掉末尾斜杠，拼路径时不会出现 // */
        size_t n = strlen(root);
        while (n > 1 && root[n - 1] == '/') root[--n] = '\0';
        ready = 1;
    }
    return root;
}

/*
 * 前缀表：`from` 整体替换成 `<tmp_root><sub>`。
 * 只做「整段前缀」匹配（/tmp 不会误伤 /tmpfoo），子路径原样接在后面。
 */
static const char *dsh_map(const char *path) {
    if (!path || path[0] != '/') return path;

    static const struct {
        const char *from;
        const char *sub;
    } table[] = {
        {"/tmp", ""},       /* 主目标：/tmp/a -> $TMPDIR/a */
        {"/var/tmp", ""},   /* 传统第二临时目录 */
        {"/dev/shm", "/shm"}, /* 共享内存文件 -> $TMPDIR/shm */
        {"/run", "/run"},   /* 运行时状态 -> $TMPDIR/run */
        {"/var/run", "/run"},
    };

    for (size_t i = 0; i < sizeof(table) / sizeof(table[0]); i++) {
        const char *from = table[i].from;
        size_t n = strlen(from);
        if (strncmp(path, from, n) != 0) continue;
        char next = path[n];
        if (next != '\0' && next != '/') continue; /* /tmpfoo 不匹配 */

        const char *root = dsh_tmp_root();
        int written = snprintf(g_map, sizeof(g_map), "%s%s%s", root, table[i].sub, path + n);
        if (written < 0 || written >= (int)sizeof(g_map)) return path; /* 过长：放弃映射 */
        return g_map;
    }
    return path;
}

/* ---- 真实符号（惰性解析） ---- */
#define REAL(name)                                            \
    static __typeof__(name) *real_##name;                     \
    if (!real_##name) real_##name = (__typeof__(name) *)dlsym(RTLD_NEXT, #name)

int open(const char *path, int flags, ...) {
    REAL(open);
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return real_open(dsh_map(path), flags, mode);
}

int open64(const char *path, int flags, ...) {
    REAL(open64);
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return real_open64(dsh_map(path), flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...) {
    REAL(openat);
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return real_openat(dirfd, dsh_map(path), flags, mode);
}

int openat64(int dirfd, const char *path, int flags, ...) {
    REAL(openat64);
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return real_openat64(dirfd, dsh_map(path), flags, mode);
}

/*
 * NDK r24+/Termux 的多数包默认开 _FORTIFY_SOURCE，`open(path, flags)` 会被
 * <bits/fortify/fcntl.h> 宏展开成 `__open_2` / `__openat_2`（libc 导出的内部符号），
 * 直接调用 libc 内部实现、**绕过**上面的 open 拦截 —— 实测 Termux coreutils 的
 * `cat /tmp/x` 就因此 ENOENT（写成功、读失败）。这里把 fortify 入口也接上。
 */
int __open_2(const char *path, int flags) {
    REAL(open);
    return real_open(dsh_map(path), flags);
}

int __openat_2(int dirfd, const char *path, int flags) {
    REAL(openat);
    return real_openat(dirfd, dsh_map(path), flags);
}

int __openat64_2(int dirfd, const char *path, int flags) {
    REAL(openat64);
    return real_openat64(dirfd, dsh_map(path), flags);
}

int __open64_2(const char *path, int flags) {
    REAL(open64);
    return real_open64(dsh_map(path), flags);
}

int creat(const char *path, mode_t mode) {
    REAL(creat);
    return real_creat(dsh_map(path), mode);
}

int access(const char *path, int mode) {
    REAL(access);
    return real_access(dsh_map(path), mode);
}

int faccessat(int dirfd, const char *path, int mode, int flags) {
    REAL(faccessat);
    return real_faccessat(dirfd, dsh_map(path), mode, flags);
}

int stat(const char *path, struct stat *buf) {
    REAL(stat);
    return real_stat(dsh_map(path), buf);
}

int lstat(const char *path, struct stat *buf) {
    REAL(lstat);
    return real_lstat(dsh_map(path), buf);
}

int fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    REAL(fstatat);
    return real_fstatat(dirfd, dsh_map(path), buf, flags);
}

int chmod(const char *path, mode_t mode) {
    REAL(chmod);
    return real_chmod(dsh_map(path), mode);
}

int fchmodat(int dirfd, const char *path, mode_t mode, int flags) {
    REAL(fchmodat);
    return real_fchmodat(dirfd, dsh_map(path), mode, flags);
}

int mkdir(const char *path, mode_t mode) {
    REAL(mkdir);
    return real_mkdir(dsh_map(path), mode);
}

int mkdirat(int dirfd, const char *path, mode_t mode) {
    REAL(mkdirat);
    return real_mkdirat(dirfd, dsh_map(path), mode);
}

int unlink(const char *path) {
    REAL(unlink);
    return real_unlink(dsh_map(path));
}

int unlinkat(int dirfd, const char *path, int flags) {
    REAL(unlinkat);
    return real_unlinkat(dirfd, dsh_map(path), flags);
}

int remove(const char *path) {
    REAL(remove);
    return real_remove(dsh_map(path));
}

int rename(const char *oldpath, const char *newpath) {
    REAL(rename);
    return real_rename(dsh_map(oldpath), dsh_map(newpath));
}

int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    REAL(renameat);
    return real_renameat(olddirfd, dsh_map(oldpath), newdirfd, dsh_map(newpath));
}

int truncate(const char *path, off_t length) {
    REAL(truncate);
    return real_truncate(dsh_map(path), length);
}

int chdir(const char *path) {
    REAL(chdir);
    return real_chdir(dsh_map(path));
}

DIR *opendir(const char *path) {
    REAL(opendir);
    return real_opendir(dsh_map(path));
}

char *realpath(const char *path, char *resolved) {
    REAL(realpath);
    return real_realpath(dsh_map(path), resolved);
}

FILE *fopen(const char *path, const char *mode) {
    REAL(fopen);
    return real_fopen(dsh_map(path), mode);
}

FILE *fopen64(const char *path, const char *mode) {
    REAL(fopen64);
    return real_fopen64(dsh_map(path), mode);
}

FILE *freopen(const char *path, const char *mode, FILE *stream) {
    REAL(freopen);
    return real_freopen(path ? dsh_map(path) : NULL, mode, stream);
}

int statfs(const char *path, struct statfs *buf) {
    REAL(statfs);
    return real_statfs(dsh_map(path), buf);
}

int statvfs(const char *path, struct statvfs *buf) {
    REAL(statvfs);
    return real_statvfs(dsh_map(path), buf);
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    REAL(execve);
    return real_execve(dsh_map(path), argv, envp);
}
