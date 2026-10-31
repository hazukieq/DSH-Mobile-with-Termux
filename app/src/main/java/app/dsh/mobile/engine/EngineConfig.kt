package app.dsh.mobile.engine

import java.io.File

/**
 * 引擎的「平台适配配置」总入口：目录、环境变量、overlay、门脚本、自愈。
 *
 * Phase 3 拆分后本类只保留公开 API 与常量；实现分散在同包的 4 个 internal 协作者里，
 * 这里全部是**一行委托**，签名与行为保持不变：
 *  - [EnginePaths]        目录拓扑、用户数据外置
 *  - [EngineEnvBuilder]   子进程环境变量、su/shz 闸门
 *  - [AndroidOverlay]     overlay / 客户端插件 / 上游 JS patch / 门脚本部署
 *  - [EngineSelfHeal]     启动期自愈（shebang、pnpm、内置插件、UI 重置）
 */
object EngineConfig {

    const val DEFAULT_PORT = 3080

    const val HEALTH_TIMEOUT_MS = 45_000L

    const val HEALTH_INTERVAL_MS = 500L

    /** web listen 后的稳定观察窗：挺过此窗才判定 Healthy 并快照 last-good */
    const val STABLE_WINDOW_MS = 3_000L

    /** 崩溃退避序列（毫秒），连续健康后重置 */
    val BACKOFF_STEPS = longArrayOf(2_000, 4_000, 8_000, 16_000, 32_000)

    // 必须 > guardian 两阶段所需（5 回滚 + 5 归档 = 10 次连续真死），
    // 否则第 8 次就进 Failed 停机、安全模式在数学上永远无法触发（实测事故）
    const val MAX_RESTART = 14

    /** 运行时前缀目录名（Termux 标准是 `usr`；改包名后必须与 Termux 对齐） */
    const val PREFIX_DIR = "usr"

    /**
     * 端口冲突自动清场的最大连续次数（v1.3.1）。
     *
     * 端口被**我们的**孤儿引擎占用时，清场后即可恢复，通常一次足够；但若端口被
     * 完全无关的进程（用户自己跑的、别的 app 的）永久占用，清场永远不会成功 ——
     * 那时必须放行走正常退避与 State.Failed，让用户看到明确错误，
     * 而不是绕过 MAX_RESTART 无声地无限重试。
     */
    const val MAX_PORT_RECLAIMS = 8

    fun engineRoot(ctx: android.content.Context): File =
        File(ctx.filesDir, EngineConfig.PREFIX_DIR).apply { mkdirs() }

    fun nodeBin(ctx: android.content.Context): File =
        File(engineRoot(ctx), "bin/node")

    fun dshEntry(ctx: android.content.Context): File =
        File(engineRoot(ctx), "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")

    /** 日志标签（拆分后各协作者共用） */
    internal const val TAG = "EngineConfig"

    // ── 以下为薄委托（Phase 3 拆分）：实现见同包协作者，签名保持不变 ──

    // EnginePaths
    fun ensureUserDataExternal(ctx: android.content.Context) { EnginePaths.ensureUserDataExternal(ctx) }
    fun dshHome(ctx: android.content.Context): File = EnginePaths.dshHome(ctx)
    fun workspaces(ctx: android.content.Context): File = EnginePaths.workspaces(ctx)
    fun userDataLocation(ctx: android.content.Context): String? = EnginePaths.userDataLocation(ctx)
    fun tmpDir(ctx: android.content.Context): File = EnginePaths.tmpDir(ctx)

    // EngineEnvBuilder
    fun buildEnv(ctx: android.content.Context, port: Int, bootId: String? = null): Array<String> = EngineEnvBuilder.buildEnv(ctx, port, bootId)

    // AndroidOverlay
    fun ensureAndroidOverlay(ctx: android.content.Context): File = AndroidOverlay.ensureAndroidOverlay(ctx)

    // EngineSelfHeal
    fun healTermuxShebangs(ctx: android.content.Context) { EngineSelfHeal.healTermuxShebangs(ctx) }
    fun ensureAgentArtifacts(ctx: android.content.Context) { EngineSelfHeal.ensureAgentArtifacts(ctx) }
    fun ensureBundledPlugins(ctx: android.content.Context) { EngineSelfHeal.ensureBundledPlugins(ctx) }
    fun repairMobileUi(ctx: android.content.Context): Boolean = EngineSelfHeal.repairMobileUi(ctx)
}
