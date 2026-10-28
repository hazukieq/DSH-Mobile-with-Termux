package app.dsh.mobile.engine

/**
 * 一条可执行命令的描述（终端会话与门脚本共用）。
 *
 * 设计动机（2026-10-18 新增终端时引入）：
 * - 终端会话要「以某个 shell 启动、给定 cwd 与 env」，执行层（PTY / ProcessBuilder）
 *   只消费这份纯数据，不必知道上层怎么挑 shell。
 * - 本类刻意不依赖 Android API：便于单测与跨包（term / engine）复用。
 *
 * 注：proot / Linux 发行版子系统已于 2026-10-28 移除（原 `Kind` 枚举与
 * `distroId` 字段一并删除）；现在只有 Android 侧终端一种会话。
 */
data class ShellSpec(
    /** 可执行文件绝对路径；PTY 层直接 execve 它，不走 PATH 解析 */
    val cmd: String,
    /** argv[1..]，不含程序名（PTY 层自动补 argv[0] = cmd） */
    val args: List<String> = emptyList(),
    /** 工作目录；必须真实存在，否则子进程 chdir 失败退出 125 */
    val cwd: String,
    /** 子进程环境变量，"KEY=VALUE" 形态；由调用方给出完整清单（不继承父进程） */
    val env: List<String> = emptyList(),
    /** 会话标题（终端 tab / 通知里显示） */
    val title: String = "",
)
