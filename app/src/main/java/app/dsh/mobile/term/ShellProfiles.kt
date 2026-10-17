package app.dsh.mobile.term

import android.content.Context
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.ShellSpec
import app.dsh.mobile.linux.LinuxEnv
import java.io.File

/**
 * 会话「配方」工厂：把用户选择的 profile 翻译成一条 [ShellSpec]。
 *
 * 两类 profile（2026-10-18 终端功能）：
 *  - **Android 侧**：直接跑引擎自带的 bash（`engine/bin/bash`）——与 AI 的 shell
 *    工具**同一个环境**（PATH/LD_LIBRARY_PATH/PREFIX 全部来自 [EngineConfig.buildEnv]），
 *    所以终端里能直接 `node`、`git`、`python3`（装了扩展的话），所见即 AI 所得。
 *    引擎运行时尚未安装时回落到 `/system/bin/sh`（toybox），至少能用。
 *  - **Linux 侧**：proot 起的发行版 rootfs（Alpine/Ubuntu），见 [LinuxEnv]。
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
        val kind: ShellSpec.Kind,
        val distroId: String? = null,
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
        val spec = if (bash.isFile && bash.canExecute()) {
            ShellSpec(
                cmd = bash.absolutePath,
                // `--norc`：Termux 版 bash 把系统级 bashrc 路径**编译期写死**成
                // /data/data/com.termux/files/usr/etc/bash.bashrc。设备装过 Termux 时
                // 该路径存在但本 App 无权穿越 → 每个交互 shell 都多打一行
                // "Permission denied"（真机实测，模拟器因路径不存在而静默）。
                // 我们已经在环境里注入 PS1，rc 里没有我们需要的配置，故直接跳过。
                args = listOf("--norc", "-i"),
                cwd = cwd,
                env = engineEnv + extra,
                title = "Android",
                kind = ShellSpec.Kind.ANDROID,
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
                kind = ShellSpec.Kind.ANDROID,
            )
        }
        return spec
    }

    /** Linux 发行版会话；未安装或 proot 缺失时返回 null */
    fun linuxSpec(ctx: Context, distroId: String): ShellSpec? =
        LinuxEnv.shellSpec(ctx, distroId, interactive = true)

    /** 当前可用的 profile 列表（Android 恒有；Linux 仅列已安装的发行版） */
    fun profiles(ctx: Context): List<Profile> {
        val list = mutableListOf(Profile(ID_ANDROID, "Android", ShellSpec.Kind.ANDROID))
        runCatching {
            LinuxEnv.installedIds(ctx).forEach { id ->
                val d = LinuxEnv.distros().firstOrNull { it.id == id }
                if (d != null) {
                    list += Profile(id, d.name, ShellSpec.Kind.LINUX, id)
                }
            }
        }
        return list
    }

    /** 按 profile id 取 spec；找不到返回 null */
    fun specFor(ctx: Context, profileId: String): ShellSpec? = when (profileId) {
        ID_ANDROID -> androidSpec(ctx)
        else -> linuxSpec(ctx, profileId)
    }
}
