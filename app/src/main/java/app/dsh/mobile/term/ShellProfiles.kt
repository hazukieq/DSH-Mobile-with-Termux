package app.dsh.mobile.term

import android.content.Context
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.ShellSpec
import java.io.File

/**
 * 会话「配方」工厂：把用户选择的 profile 翻译成一条 [ShellSpec]。
 *
 * 会话直接跑引擎自带的 bash（`engine/bin/bash`）——与 AI 的 shell 工具**同一个环境**
 * （PATH/LD_LIBRARY_PATH/PREFIX 全部来自 [EngineConfig.buildEnv]），所以终端里能直接
 * `node`、`git`、`python3`（装了扩展的话），所见即 AI 所得。引擎运行时尚未安装时回落
 * 到 `/system/bin/sh`（toybox），至少能用。
 *
 * 注：proot / Linux 发行版 profile 已于 2026-10-28 移除。
 *
 * 为什么终端环境要完全复用 buildEnv 而不是自己拼 PATH：
 * 项目已经在 EngineConfig 里踩平了十几个硬编码路径坑（GIT_EXEC_PATH、PYTHONHOME、
 * Perl/Ruby @INC、GOTMPDIR、su/shz 闸门……）。终端若另拼一套，用户会看到
 * 「AI 能跑的 git，终端里报错」这类分裂现象。
 */
object ShellProfiles {

    /** 终端会话可选的一项（标题 + 生成 spec 的闭包） */
    data class Profile(
        val id: String,
        val title: String,
    )

    const val ID_ANDROID = "android"

    /** Android 侧会话（引擎环境 / toybox 回落） */
    fun androidSpec(ctx: Context): ShellSpec {
        val root = EngineConfig.engineRoot(ctx)
        val bash = File(root, "bin/bash")
        val home = EngineConfig.dshHome(ctx).absolutePath
        val cwd = home.takeIf { File(it).isDirectory } ?: ctx.filesDir.absolutePath
        // 端口沿用引擎固定端口：buildEnv 只为 DSH_SHZ_PORT/PORT 等注入值，
        // 终端里跑 gate 脚本（notify/scr）时正好与引擎一致。
        val engineEnv = runCatching {
            EngineConfig.buildEnv(ctx, EngineConfig.DEFAULT_PORT).toList()
        }.getOrElse { emptyList() }
        val extra = mutableListOf(
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            // 会话里给个明确提示符，避免 bash 用默认 `bash-5.2$`（无路径、无颜色）
            "PS1=\\[\\e[38;5;114m\\]\\u@dsh\\[\\e[0m\\]:\\[\\e[38;5;111m\\]\\w\\[\\e[0m\\]\\$ ",
            "HISTFILE=$home/.bash_history",
            // 引擎 bash 是 bionic 动态链接：LD_LIBRARY_PATH 由 buildEnv 给；
            // 手动补一份兜底，防止 buildEnv 在极端情况下返回空表。
            "DSH_TERMINAL=1",
        )
        if (engineEnv.none { it.startsWith("LD_LIBRARY_PATH=") }) {
            extra += "LD_LIBRARY_PATH=" + File(root, "lib").absolutePath
        }
        // 默认 shell（v1.3.10）：装了 zsh 就用 zsh（自带 zshrc：联想/纠错/语法高亮），
        // 没装则照旧回落 bash —— 不强制用户安装。
        val zsh = File(EngineConfig.engineRoot(ctx), "bin/zsh")
        if (zsh.isFile && zsh.canExecute()) {
            return ShellSpec(
                cmd = zsh.absolutePath,
                args = listOf("-i"),
                cwd = cwd,
                env = engineEnv + extra,
                title = "Android (zsh)",
            )
        }
        val spec = if (bash.isFile && bash.canExecute()) {
            ShellSpec(
                cmd = bash.absolutePath,
                // 2026-10-18：运行时的 bash 已改为**按本 App 包名重编译**
                // （SYS_BASHRC = /data/data/app.dsh.mobile/files/engine/etc/bash.bashrc），
                // 所以直接 `bash -i` 就能读到自己那份 rc，不再需要 `--norc` 兜底。
                // rc 里只有在 PS1 仍是 bash 默认值时才覆盖的守卫，我们注入的 PS1 会保留。
                args = listOf("-i"),
                cwd = cwd,
                env = engineEnv + extra,
                title = "Android",
            )
        } else {
            ShellSpec(
                cmd = "/system/bin/sh",
                args = listOf("-i"),
                cwd = cwd,
                env = listOf(
                    "HOME=$home",
                    "TMPDIR=" + EngineConfig.tmpDir(ctx).absolutePath,
                    "PATH=/system/bin:/system/xbin",
                    "ANDROID_DATA=/data",
                    "ANDROID_ROOT=/system",
                    // /system/bin/sh 是 mksh/toybox，不认 bash 的 \[ \] \u \w 转义：
                    // 用它会打印出 ":<cwd> $" 这种半截提示符（模拟器实测）。回落分支
                    // 用纯文本 PS1，并把别处的 bash 版覆盖掉。
                    "PS1=\$ ",
                ) + extra.filterNot { it.startsWith("PS1=" ) },
                title = "Android (toybox)",
            )
        }
        return spec
    }

    /** 当前可用的 profile 列表（只剩 Android 侧一种） */
    fun profiles(ctx: Context): List<Profile> = listOf(Profile(ID_ANDROID, "Android"))

    /**
     * 按 profile id 取 spec。
     *
     * 旧版本可能把 `alpine`/`ubuntu` 之类的 id 存进 SharedPreferences，
     * 现在一律回落到 Android 会话，避免升级后终端打不开。
     */
    fun specFor(ctx: Context, profileId: String): ShellSpec = androidSpec(ctx)
}
