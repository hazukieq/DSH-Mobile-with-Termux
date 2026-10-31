package app.dsh.mobile.engine

import android.util.Log
import java.io.File

/**
 * 启动期自愈：Termux shebang 修复、内置 pnpm、门脚本补装、移动端 UI 重置
 * （从 EngineConfig 拆出，Phase 3）。全部幂等，失败只记日志不阻断启动。
 */
internal object EngineSelfHeal {

    /**
     * Android 适配覆盖层（v1.2.36）：写成 YAML patch 文件，由启动参数 `--patch` 注入。
     *
     * 为什么必须覆盖沙箱模式：dsh 的沙箱后端是 Linux landlock / macOS seatbelt，
     * **Android 上两者都不存在** → sandbox-policy 默认 `read-only`：
     *   - read-only/workspace-write 下 bash 工具直接拒绝执行（模拟器实测原文：
     *     `sandbox mode "workspace-write" ... refusing to run the command unconfined;
     *      no sandbox backend usable on host`）→ AI 一条命令都跑不了；
     * 本项目的安全边界是 App 自身的权限模式（Normal 沙箱 / Shizuku ADB 级 / Root）
     * 与 su 闸门（applySuGate），而非引擎内部沙箱，故置 danger-full-access。
     * 用户在 WebUI 仍可逐会话选择更严格模式。
     *
     * 只覆盖 id 精确匹配的插件；上游升级若改 id，未知条目会被忽略（不阻断启动）。
     */
    /**
     * 运行期修复 engine/bin 里指向 Termux 的 wrapper shebang（v1.2.159）。
     *
     * 背景（真机 AI 反馈实测）：`getprop` 一跑就崩 `bad interpreter` —— 设备上 **37 个**
     * wrapper 的 shebang 仍是 `#!/data/data/com.termux/files/usr/bin/sh`，该路径在本机不存在。
     * 打包脚本 `collect-termux-runtime.sh` 里早有"归一为 /system/bin/sh"的一段，但它只
     * **构建期**生效；老 runtime（或扩展解出的 runtime）里仍带着旧 shebang，运行期无人兜底。
     * 这里做幂等自愈：只改第一行 shebang，其余内容一字不动（那些 shim 遮蔽系统命令是
     * **有意的**，见打包脚本注释，不能删）。
     */
    fun healTermuxShebangs(ctx: android.content.Context) {
        runCatching {
            val bin = File(EngineConfig.engineRoot(ctx), "bin")
            if (!bin.isDirectory) return
            var fixed = 0
            bin.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val head = f.inputStream().use { ins ->
                    val buf = ByteArray(2); if (ins.read(buf) == 2) String(buf) else ""
                }
                if (head != "#!") return@forEach
                val first = f.bufferedReader().use { it.readLine() } ?: return@forEach
                if (!first.startsWith("#!")) return@forEach
                // 2026-10-18 包名切换为 com.termux 后，Termux 前缀**真实存在**：
                // 这些 shebang 是合法的（pkg/apt/codex 等脚本还要靠 bash 的 `=~`）。
                // 只有当解释器路径确实不存在时（旧的 app.dsh.mobile 前缀等）才降级到
                // /system/bin/sh —— 否则会把 bash 脚本改成 toybox sh，语法直接报错。
                val interp = first.removePrefix("#!").trim().substringBefore(' ')
                if (interp.startsWith("/") && File(interp).exists()) return@forEach
                runCatching {
                    val rest = f.readLines().drop(1)
                    f.writeText("#!/system/bin/sh\n" + rest.joinToString("\n") + "\n")
                    f.setExecutable(true, false)
                    fixed++
                }
            }
            if (fixed > 0) Log.i(EngineConfig.TAG, "healed $fixed Termux shebang(s) in engine/bin")
        }.onFailure { Log.w(EngineConfig.TAG, "shebang heal failed: ${it.message}") }
    }

    /**
     * 每次 App 启动都确认一次 Agent 侧产物（门脚本 / shz / su 闸门 / runtime overlay）。
     *
     * 为什么需要：这些文件以前只在「装运行时」那一步部署；用户升级 APK 后如果运行时
     * 目录已存在，启动流程会跳过安装步骤，新版本新增的门脚本（比如 appshot/record/
     * record）就永远不落地 —— Agent 调 `appshot` 会 command not found。这里做幂等复查，
     * 缺什么补什么（AndroidOverlay.applyAgentGates 内部按文件写，成本毫秒级）。
     */
    fun ensureAgentArtifacts(ctx: android.content.Context) {
        runCatching { AndroidOverlay.applyAgentGates(ctx, EngineConfig.engineRoot(ctx)) }
            .onFailure { Log.w(EngineConfig.TAG, "ensureAgentArtifacts failed: ${it.message}") }
        // 只补 pnpm 工具链；不会再改动用户已装的插件或 profile 配置。
        runCatching { ensureBundledPlugins(ctx) }
            .onFailure { Log.w(EngineConfig.TAG, "ensureBundledPlugins failed: ${it.message}") }
    }

    /** 关掉内置插件自动落地的标记文件（agent 可 touch 它）。 */
    private fun bundledPluginsMarker(home: File) = File(home, ".dsh-bundled-off")

    /**
     * 只补 pnpm（dsh-market 安装时需要它，离线可用）。
     *
     * 只做「缺了才补」这一件事：不删除、不改写用户 node_modules 里的任何东西
     * （社区插件是用户自己装的，App 无权替他清理），也不再动 profiles/web/package.json。
     */
    fun ensureBundledPlugins(ctx: android.content.Context) {
        val home = File(ctx.filesDir, "dsh-home")
        val profile = File(home, "profiles/web")
        val modules = File(profile, "node_modules")
        if (bundledPluginsMarker(home).exists()) {
            Log.i(EngineConfig.TAG, "bundled plugins disabled by marker, skip")
            return
        }
        if (!modules.isDirectory) modules.mkdirs()
        val pnpmDir = File(modules, "pnpm")
        // 载荷完整性：pnpm 10 起 add/install 走 worker 线程，缺 dist/worker.js 就会
        // 「Worker pnpm#1 exited with code 1」；旧版打的是裁过的包（12 文件），必须重建。
        val payloadOk = File(pnpmDir, "dist/worker.js").isFile
        if (pnpmDir.isDirectory && payloadOk) return
        if (pnpmDir.isDirectory) {
            Log.w(EngineConfig.TAG, "pnpm payload incomplete (missing dist/worker.js), re-extracting")
            pnpmDir.deleteRecursively()
        }
        runCatching {
            ctx.assets.open("bundled-plugins.zip").use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val rel = e.name.removePrefix("node_modules/")
                        if (rel.isEmpty() || rel == e.name) continue
                        val out = File(modules, rel)
                        if (e.isDirectory) { out.mkdirs(); continue }
                        if (out.exists()) continue
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        zip.closeEntry()
                    }
                }
            }
        }.onSuccess {
            val ok = File(pnpmDir, "dist/worker.js").isFile
            Log.i(EngineConfig.TAG, "pnpm toolchain installed into node_modules (worker.js=$ok)")
        }
            .onFailure { Log.w(EngineConfig.TAG, "pnpm extract failed: ${it.message}") }
    }

    /**
     * 修复移动端界面（设置 → 一键还原 UI）。
     *
     * 语义：**不是**退回桌面版 WebUI，而是把被改坏的 mobile-ui 恢复成 App 内置的那一份。
     * 做法：删掉 dsh-home/android-plugins/mobile-ui/ 下已部署的文件，重启引擎时按
     * assets 重新部署；WebUI 侧的面板/抽屉状态由插件自己按默认值重建。
     *
     * 返回是否真的删到了东西（用于给用户一个诚实的回执）。
     */
    fun repairMobileUi(ctx: android.content.Context): Boolean = runCatching {
        val dir = java.io.File(java.io.File(EngineConfig.engineRoot(ctx), "dsh-home"), "android-plugins/mobile-ui")
        val existed = dir.exists()
        if (existed) dir.deleteRecursively()
        Log.i(EngineConfig.TAG, "repairMobileUi: existed=$existed deleted=${!dir.exists()} path=${dir.path}")
        existed && !dir.exists()
    }.onFailure { Log.w(EngineConfig.TAG, "repairMobileUi failed: ${it.message}") }.getOrDefault(false)
}
