package app.dsh.mobile.engine

/**
 * 一条可执行命令的描述（终端会话、Linux 环境启动、门脚本共用）。
 *
 * 设计动机（2026-10-18 新增终端 + Linux 环境）：
 * - 终端会话与 `linux run` 门脚本都要「以某个 shell 启动、给定 cwd 与 env」，
 *   而具体是 `/system/bin/sh`（Android 侧）还是 `proot -0 -r rootfs /bin/sh`（Linux 侧）
 *   属于上层策略。把「怎么起」抽象成纯数据，执行层（PTY / ProcessBuilder）只消费它，
 *   两侧都不用互相 import。
 * - 本类刻意不依赖 Android API：便于单测与跨包（term / linux / engine）复用。
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
    /** 会话类型：决定 UI 角标与「关闭后是否回落到普通 shell」等行为 */
    val kind: Kind = Kind.ANDROID,
    /** 关联的 Linux 发行版 id（kind == LINUX 时有效，如 "alpine"） */
    val distroId: String? = null,
) {
    enum class Kind {
        /** 直接跑 Android 侧 shell（app 沙箱 / bionic 环境，PATH 由 EngineConfig 组装） */
        ANDROID,

        /** 跑在 proot 里的 Linux 用户空间（发行版 rootfs） */
        LINUX,
    }
}
