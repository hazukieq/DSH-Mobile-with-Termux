package app.dsh.mobile.engine

import android.util.Log
import java.io.File

/**
 * 引擎子进程环境变量构建（从 EngineConfig 拆出，Phase 3）。
 *
 * buildEnv 是引擎与终端共用的唯一环境来源：PATH / LD_LIBRARY_PATH / DSH_HOME /
 * 权限模式闸门（su / shz）都在这里拼装。EngineConfig.buildEnv 保留薄委托。
 */
internal object EngineEnvBuilder {

    /**
     * 引擎默认 shell 路径（v1.3.1，issue #18）。
     *
     * 首选自带 bash（`<EngineConfig.engineRoot>/bin/bash`，门脚本与 /dev/tcp 都依赖 bash 特性）；
     * 若因解压不完整等原因缺失，退回 runtime 里的 `bin/sh`，再退回系统 toybox sh。
     * 绝不返回 Termux 前缀——那条路径在本机永远不存在。
     */
    private fun shellPathFor(root: File): String {
        val bash = File(root, "bin/bash")
        if (bash.canExecute()) return bash.absolutePath
        val sh = File(root, "bin/sh")
        if (sh.exists()) return sh.absolutePath
        Log.w(EngineConfig.TAG, "engine bash missing at ${bash.absolutePath}; falling back to /system/bin/sh")
        return "/system/bin/sh"
    }

    /**
     * 组装子进程环境变量。
     * PATH/LD_LIBRARY_PATH/PREFIX 对齐 Termux 布局，保证 bionic 二进制能找到依赖库。
     *
     * OPENSSL_CONF/SSL_CERT_FILE：Termux 编译的 node 将 OpenSSL 目录硬编码为
     * /data/data/com.termux/files/usr/etc/tls；设备共存真实 Termux 时 fopen 命中
     * EACCES → "OpenSSL configuration error" → node 启动即退（m1.1 真机事故根因，
     * 模拟器因无 com.termux 目录呈 ENOENT 静默通过故未暴露）。显式指回自带 etc/tls
     * 与宿主 Termux 彻底隔离。
     */
    fun buildEnv(ctx: android.content.Context, port: Int, bootId: String? = null): Array<String> {
        val root = EngineConfig.engineRoot(ctx)
        // 运行权限模式（m1.24）：注入给 AI 侧感知，令其按模式调整执行行为
        val privMode = Privilege.getMode(ctx)
        // m1.29：按模式控制 AI 子进程能否用 su。engine/bin 在 PATH 首位，
        // 非 Root 模式往 engine/bin 放一个「拒绝执行」的 su 遮罩（覆盖 /system/bin/su），
        // Root 模式移除遮罩放行真 su。这样只有切到 Root 模式 AI 才提权。
        applySuGate(root, privMode)
        // m1.30：Shizuku 模式注入 shz 包装器（AI 显式 `shz <adb命令>` 走 ADB 级执行）；
        // 其他模式删除，AI 调 shz 将无命令。
        applyShzGate(root, privMode, port)
        // v1.1.0：notify/scr 包装器（所有模式可用——通知与无障碍是 App 自身能力，
        // 经 AgentBridge 127.0.0.1:3083 转发）。
        AndroidOverlay.applyAgentGates(ctx, root)
        // 运行时 overlay（2026-10-18）：把 assets/runtime-overlay 覆盖进引擎根。
        // 当前只有一项 —— node-pty 的**真**实现（转发到 App 的 libdshpty.so），
        // 替换构建期那个「spawn() 直接 throw」的空壳，WebUI 侧边栏终端才能用。
        AndroidOverlay.applyRuntimeOverlays(ctx, root)
        // v2.5.45：补齐 npm/pnpm/corepack 入口。运行时只带 node + corepack 本体
        // （lib/node_modules/corepack），bin/ 里没有 npm/pnpm/npx，导致 dsh-market
        // 的自动配置与 `dsh plugin add` 全部报「找不到 npm/corepack」。
        AndroidOverlay.ensureNodeTooling(ctx, root)
        // 引擎侧 Android 补丁（2026-10-18）：dsh-subprocess-local 把「终端进程检查」
        // 限定在 linux/darwin/win32，android 直接抛 "terminal inspection is unsupported
        // on platform android" → WebUI 侧边栏终端打不开。Android 有 /proc，检查器可用。
        AndroidOverlay.patchEngineForAndroid(root)
        val env = mutableListOf(
            "PATH=" + (listOf(File(root, "bin"), File(EnginePaths.dshHome(ctx), "local/bin"),
                File(EnginePaths.dshHome(ctx), ".local/share/pnpm")) +
                listOf("/system/bin", "/system/xbin")).joinToString(":"),
            "DSH_SHZ_PORT=${ShizukuHttpBridge.port(port)}",
            // 桥 token（v1.2.99）：只交给引擎子进程，供 scr/notify 等闸门脚本鉴权。
            // 第三方 App 拿不到 → 无法调用读屏/点击/通知等设备能力。
            "DSH_BRIDGE_TOKEN=${AgentBridge.currentToken().orEmpty()}",
            "LD_LIBRARY_PATH=" + listOf(File(root, "lib")).joinToString(":"),
            "PREFIX=$root",
            "COREPACK_HOME=${File(EnginePaths.dshHome(ctx), ".cache/corepack").absolutePath}",
            // SHELL（v1.3.1，issue #18「终端错误」）：必须显式注入。
            // 引擎的 dsh-subprocess-local 按 `process.env.SHELL || os.userInfo().shell`
            // 推导默认 shell（terminalEnvironment），而 runtime 里的 node 是 Termux 官方
            // 构建——它在二进制内把 passwd 的 shell 硬编码成
            // /data/data/com.termux/files/usr/bin/bash（本机不存在，且与用户是否装过
            // Termux 无关）。不设 SHELL 时引擎就会拿这个坏路径去 resolveExecutable →
            // `subprocess-local: command "..." is not an executable file`（持久 bash
            // 工具与 Web 终端一起报错）。注入后 SHELL 优先级最高，且指向自带 bash。
            "SHELL=${shellPathFor(root)}",
            "HOME=${EnginePaths.dshHome(ctx)}",
            "DSH_HOME=${EnginePaths.dshHome(ctx)}",
            "TMPDIR=${EnginePaths.tmpDir(ctx)}",
            "PORT=$port",
            "NODE_ENV=production",
            // 取值统一小写（normal/shizuku/root），与 AGENTS 种子里 AI 读到的文档一致。
            // 此前注入 .name（ROOT）、种子写 lowercase（root）→ AI 按文档写
            // `[ "$DSH_ANDROID_PRIV_MODE" = "root" ]` 永远不成立，静默走错分支。
            "DSH_ANDROID_PRIV_MODE=${privMode.name.lowercase()}",
            // Android 标准环境（init 对普通进程的设定）。aapt / apksigner / zipalign 靠它
            // 判断「是否运行在 Android 上」，缺失时直接报 "ANDROID_DATA not set" 并退出
            // （扩展中心主推 android-buildtools，AI 又被种子引导使用这些工具 → 开箱即坏）。
            // 引擎进程环境会被所有子 shell 与扩展二进制继承，一处修复全局生效。
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "ANDROID_STORAGE=/storage",
            // 社区插件安装（dsh plugin add → pnpm）：pnpm 10+ 的 strictDepBuilds 默认
            // 为 true，只要依赖里带 build script（mnemon、puppeteer 等）就被记成
            // "ignored builds" 并**直接让整次安装失败**：
            //   ERR_PNPM_IGNORED_BUILDS / Ignored build scripts: mnemon@1.3.0, puppeteer@…
            // 手机上没有交互式 `pnpm approve-builds`，所以一律降级为警告继续装。
            "npm_config_strict_dep_builds=false",
            "NPM_CONFIG_STRICT_DEP_BUILDS=false",
            // 让 pnpm 别把「被忽略的构建脚本」当致命错误（pnpm v12 新 strict 模式）
            "npm_config_allow_unused=false",
            // 权限三件套的单一驱动源（v1.2.52）：上游 dsh-base 用这一个变量同时决定
            // sandbox-policy.mode、approval.policy 与默认预设，保证三者组合一致。
            // Android 没有 landlock/seatbelt，任何受限模式都会让 shell 工具拒绝执行
            // （"no sandbox backend usable on host"），故固定 danger-full-access；
            // 真实安全边界是 App 的权限模式（Normal/Shizuku/Root）与 su 闸门。
            "DSH_PERMISSION_MODE=danger-full-access",
            *(bootId?.let { arrayOf("DSH_ANDROID_BOOT_ID=$it") } ?: emptyArray()),
        )
        // 包管理器镜像（2026-10-05，Issue 反馈「pnpm 连不上 GitHub」）：
        // runtime 内没有任何 .npmrc，corepack/npm 默认走 registry.npmjs.org —— 国内直连常
        // 超时或被重置。三个变量覆盖三条路径，缺一不可：
        //   · COREPACK_NPM_REGISTRY —— corepack 拉取 pnpm/yarn 本体（corepack.cjs 内读取）
        //   · npm_config_registry    —— npm / pnpm 自身的包解析（小写环境变量是 npm 的规范形式）
        //   · NPM_CONFIG_REGISTRY    —— 大写别名，部分工具只认大写
        // 用户可通过设置里已导出的同名环境变量覆盖（种子文档也据此告诉 AI 如何换源）。
        listOf("COREPACK_NPM_REGISTRY", "npm_config_registry", "NPM_CONFIG_REGISTRY")
            .filterNot { System.getenv(it)?.isNotBlank() == true }
            .forEach { env += "$it=$NPM_REGISTRY_MIRROR" }
        // 编译工具链支持（AI 交叉编译清单实测）：
        // - GOTMPDIR：Termux go 的临时目录回退硬编码 /data/data/com.termux（不存在）→ 显式指到引擎 tmp
        // - LIBRARY_PATH：链接期库搜索（rust-lld/clang 的 -lunwind 等命中扩展 lib）
        // - CPATH：头文件搜索——ndk-sysroot 的 asm/types.h 在 include/<triple>/ 子目录，
        //   clang 内置的 $PREFIX 头路径是编译期硬编码、指向已不存在的 Termux 前缀
        // - RUSTFLAGS：rustc 传给 rust-lld 的额外 -L（LIBRARY_PATH 对 lld 不生效）
        EnginePaths.tmpDir(ctx).takeIf { it.isDirectory }?.let { env += "GOTMPDIR=$it" }
        // 扩展中心已移除（Termux 原生 pkg/apt 取代）：只保留前缀自身的库路径。
        val libPaths = listOf(File(root, "lib")).filter { it.isDirectory }.joinToString(":")
        if (libPaths.isNotEmpty()) env += "LIBRARY_PATH=$libPaths"
        File(root, "etc/tls/openssl.cnf").takeIf { it.isFile }?.let { env += "OPENSSL_CONF=$it" }
        File(root, "etc/tls/cert.pem").takeIf { it.isFile }?.let { env += "SSL_CERT_FILE=$it" }
        return env.toTypedArray()
    }

    /**
     * su 闸门（m1.29）：engine/bin 在 PATH 首位，控制 AI 子进程能否提权。
     * - 非 Root（普通/Shizuku）：写入一个拒绝执行的 su 遮罩 —— AI 调 su 立即报错退出，
     *   覆盖系统 /system/bin/su。已 root 且投过权也不放行（符合「只有切 Root 才允许」）。
     * - Root：删除遮罩，让 AI 走系统真 su（引擎整体已以 root 启动）。
     */
    private fun applySuGate(root: File, mode: PrivMode) {
        val bindir = File(root, "bin").apply { mkdirs() }
        val suShim = File(bindir, "su")
        if (mode == PrivMode.ROOT) {
            if (suShim.exists()) {
                suShim.delete()
                Log.i(EngineConfig.TAG, "su gate: ROOT mode, removed su shim (AI can su)")
            }
            return
        }
        // 非 Root：写拒绝遮罩（幂等，总是覆盖成正确内容）
        try {
            suShim.writeText("#!/system/bin/sh\n" +
                "# [dsh-android] su gate: priv mode != ROOT, deny su.\n" +
                "echo 'su: Permission denied (dsh-android: run as Root mode to gain su)' >&2\n" +
                "exit 1\n")
            suShim.setExecutable(true, false)
            if (!suShim.canExecute()) {
                // 某些 ROM 需显式 chmod；setExecutable 失败罕见，写日志即可
                Log.w(EngineConfig.TAG, "su gate: chmod failed on su shim")
            }
            Log.i(EngineConfig.TAG, "su gate: mode=$mode, su denied via shim in engine/bin")
        } catch (e: Exception) {
            Log.w(EngineConfig.TAG, "su gate: write su shim failed: ${e.message}")
        }
    }

    /**
     * shz 闸门（m1.30）：engine/bin 在 PATH 首位，控制 AI 能否用 ADB 级能力。
     * 仅 Shizuku 模式注入 `shz` 包装器：
     *   - shz <cmd>：把 <cmd> 经 HTTP POST 到 ShizukuHttpBridge（127.0.0.1:DSH_SHZ_PORT），
     *     由 Privilege.shizukuExec 以 adb 身份执行并打印输出。
     *   - 其他模式删除 shz，AI 调 shz 报 command not found（无 ADB 能力）。
     */
    private fun applyShzGate(root: File, mode: PrivMode, port: Int) {
        val bindir = File(root, "bin").apply { mkdirs() }
        val shz = File(bindir, "shz")
        if (mode != PrivMode.SHIZUKU) {
            if (shz.exists()) shz.delete()
            return
        }
        try {
            val bridgePort = ShizukuHttpBridge.port(port)
            // 关键：node -e CODE -- "$@" 时 `--` 被 node 消费掉，process.argv=[node, arg1..]，
            // 因此必须 slice(1)。旧版 slice(2) 会丢弃第一个参数（如 shz id 变 shz ），导致
            // bridge 收到空/残缺命令 → empty command / socket hang up。改用 fetch 简化并更稳。
            shz.writeText("#!/system/bin/sh\n" +
                "# [dsh-android] shz: run a command via Shizuku (adb uid) — Shizuku mode only.\n" +
                "# Posts the command to the in-process Android bridge, which executes it with\n" +
                "# IShizukuService.newProcess. Usage: shz <any shell command>\n" +
                "if [ \"$#\" -eq 0 ]; then echo 'usage: shz <command>' >&2; exit 2; fi\n" +
                // node 是动态链接的 bionic 二进制：补 LD_LIBRARY_PATH（v1.2.100，
                // 与 curl 包装器同一坑：独立调用时缺库路径会 CANNOT LINK）
                "PREFIX=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n" +
                // Kotlin 里 $ 是字符串模板起始符：shell 变量必须写成 ${'$'}{VAR}
                "export LD_LIBRARY_PATH=\"${'$'}{PREFIX}/lib:${'$'}{PREFIX}/usr/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}\"\n" +
                "exec \"$(dirname \"$0\")/node\" -e '\n" +
                "  const port = Number(process.env.DSH_SHZ_PORT || " + bridgePort + ");\n" +
                "  const cmd = process.argv.slice(1).join(\" \");\n" +
                // 桥鉴权（v1.2.100）：ShizukuHttpBridge 现在要求 X-DSH-Token。
                // AI 不知道 token，只能从环境变量读 —— 引擎子进程继承得到，第三方 App 没有。
                "  const headers = { \"Content-Type\": \"text/plain; charset=utf-8\" };\n" +
                "  if (process.env.DSH_BRIDGE_TOKEN) headers[\"X-DSH-Token\"] = process.env.DSH_BRIDGE_TOKEN;\n" +
                "  fetch(\"http://127.0.0.1:\" + port + \"/shizuku_exec\", { method: \"POST\", headers: headers, body: cmd })\n" +
                "    .then(async (res) => { const t = await res.text(); process.stdout.write(t); process.exit(res.status === 200 ? 0 : 1); })\n" +
                "    .catch((e) => { console.error(\"shz: \" + e.message); process.exit(2); });\n" +
                "' -- \"$@\"\n")
            shz.setExecutable(true, false)
            Log.i(EngineConfig.TAG, "shz gate: mode=SHIZUKU, shz wrapper injected -> :$bridgePort")
        } catch (e: Exception) {
            Log.w(EngineConfig.TAG, "shz gate: write shz failed: ${e.message}")
        }
    }

    /**
     * npm 生态默认镜像。corepack / npm / pnpm 在 runtime 内无任何 .npmrc，
     * 默认 registry.npmjs.org 在国内网络下经常超时（实测反馈「pnpm 连不上」）。
     * 淘宝源为国内通用镜像，可被进程环境变量覆盖（见 buildEnv 的 filterNot 守卫）。
     */
    private const val NPM_REGISTRY_MIRROR = "https://registry.npmmirror.com"
}
