package app.dsh.mobile.engine

import android.util.Log
import java.io.File

/**
 * Android 适配层：overlay YAML、客户端插件落地、对上游 JS 的定点 patch、
 * 门脚本注入（从 EngineConfig 拆出，Phase 3）。
 *
 * 这些操作都是幂等的「缺什么补什么」，每次引擎启动都会跑一遍；
 * 上游升级后 patch 锚点若变化会安静跳过并在日志里留痕，不会让引擎起不来。
 */
internal object AndroidOverlay {

    fun ensureAndroidOverlay(ctx: android.content.Context): File {
        val f = File(EngineConfig.engineRoot(ctx), "android-overlay.yml")
        // NOTE: keep this file free of CJK text — the i18n gate scans Kotlin string
        // literals and would flag it (explanations live in the Kotlin comments below).
        //
        // Why all three rows are pinned here instead of relying on DSH_PERMISSION_MODE
        // alone: permission-presets derives its default from the *composed* pair
        // (sandbox mode + approval policy) and throws "composed sandbox and approval
        // defaults match no preset" when the pair names no table entry. A partially
        // applied permission stack (e.g. sandbox resolved but approval left at the
        // schema default 'ask') yields exactly {danger-full-access, ask} — which is
        // not in the table — and the entry then fails to activate on every boot.
        // Pinning the trio makes the composed pair match by construction.
        //
        // ⚠️ A patch REPLACES the targeted row's whole `config` (applyEntryPatches
        // does `target[key] = value`), so each row below restates every key it owns:
        //   · permission needs the full presets table — the plugin's own schema
        //     default only carries workspace-write + danger-full-access, and losing
        //     `read-only` would remove a preset the WebUI offers.
        //   · sandbox-policy needs workspaceRoot (schema marks it required).
        val body = """
            |# [dsh-android] Android compatibility overlay (auto-generated, do not edit)
            |# Sandbox backends (landlock/seatbelt) do not exist on Android, and any
            |# confined mode would make the AI's shell tool refuse to run every command
            |# ("no sandbox backend usable on host"). The security boundary is the app's
            |# permission mode (Normal/Shizuku/Root) plus the su gate, not an engine-side
            |# sandbox. The user can still pick a stricter per-session preset in the WebUI.
            |#
            |# DSH_PERMISSION_MODE=danger-full-access is also exported (see buildEnv) so the
            |# upstream expressions agree with this layer; these rows are the authority.
            |- id: sandbox-policy
            |  config:
            |    mode: danger-full-access
            |    workspaceRoot: !!js process.cwd()
            |
            |- id: approval
            |  config:
            |    policy: never
            |
            |- id: permission
            |  config:
            |    defaultPreset: danger-full-access
            |    presets:
            |      read-only:
            |        sandbox: read-only
            |        approval: ask
            |        name: read-only
            |        description: Read-only file access; every write requires approval.
            |      workspace-write:
            |        sandbox: workspace-write
            |        approval: ask
            |        name: workspace-write
            |        description: Write inside the workspace and permitted temporary directories; wider retries require approval.
            |      danger-full-access:
            |        sandbox: danger-full-access
            |        approval: never
            |        name: danger-full-access
            |        description: Full file access without approval prompts.
            |
            |# Critical host services are repeated in this last overlay deliberately.
            |# Profile bundle patches and the user's profile patch are applied before
            |# --patch, and a later row with the same id replaces the complete row.
            |# This prevents an installed plugin from disabling the directory picker,
            |# workspace controller, loader, or web server by accident. The complete
            |# config is repeated because patching replaces, rather than merges, config.
            |- id: typert
            |  name: '@deepseek-ai/dsh-typert-registry'
            |  disabled: false
            |
            |- id: typert-loader
            |  name: '@deepseek-ai/dsh-typert-loader'
            |  disabled: false
            |
            |- id: typert-gateway
            |  name: '@deepseek-ai/dsh-api-gateway'
            |  disabled: false
            |
            |- id: directory-picker
            |  name: '@deepseek-ai/dsh-host-directory-picker-auto'
            |  inject: [webServer, loader]
            |  disabled: false
            |
            |- id: session-persistence-jsonl
            |  name: '@deepseek-ai/dsh-session-persistence-jsonl'
            |  config:
            |    root: !!js dshHomePath('sessions')
            |  disabled: false
            |
            |- id: storage-domain
            |  name: '@deepseek-ai/dsh-storage-domain'
            |  config:
            |    backend: json
            |  disabled: false
            |
            |- id: workspace-controller
            |  name: '@deepseek-ai/dsh-api-workspace-controller'
            |  disabled: false
            |
            |- id: workspace
            |  name: '@deepseek-ai/dsh-workspace'
            |  disabled: false
            |
            |- id: webserver
            |  name: '@deepseek-ai/dsh-host-webserver'
            |  inject: [webStartup]
            |  disabled: false
            |  config:
            |    host: !!js ctx.webStartup.host ?? '127.0.0.1'
            |    port: !!js ctx.webStartup.port ?? 3080
            |    compression: gzip
            |    compressionLevel: 1
            |    compressionThresholdBytes: 1024
            |
            |# Web providers: leave this row unpatched on purpose. Patching `web`'s
            |# searchProvider/fetchProvider makes the shipped web-search settings page
            |# reject every save ("this deployment did not accept these values") on this engine build, because
            |# the form round-trips the row config against the values it served.
            |#
            |# v2.5.66: the bundled `android-fetch-auto` composite provider is gone. It
            |# existed to route fake-IP (198.18.0.0/15) hostnames — which the engine's
            |# own fetch provider rejected with "not a public range" — through the
            |# official deepseek fetch API. On dsh 0.2.2 the default provider resolves
            |# and fetches such hosts by itself (measured on device: example.com
            |# 2s, github.com 9s, web_search 3s with the plugin absent), so shipping a
            |# second provider only made provider selection ambiguous.
            |- id: web
            |  name: '@deepseek-ai/dsh-web'
            |
            |# This probe is intentionally last: Cordis keeps failed imports as
            |# entries without a fiber, while the HTTP port can still be healthy.
            |# The app consumes its marker and can quarantine only a user bundle.
            |- insert:
            |    - id: android-plugin-health
            |      name: ./android-plugins/plugin-health.mjs
            |
            |- insert:
            |    - id: android-priv-mode
            |      name: ./android-plugins/priv-mode.mjs
            |    # DSH_BRIDGE_TOKEN likewise: the bridges require it, but
            |    # dsh-subprocess.scrubbedParentEnv() drops every DSH_* variable, so
            |    # without registering it the gate scripts (scr/notify/say/shz) get an
            |    # empty token, the bridge answers 403, and the agent is locked out of
            |    # the device capabilities it is meant to have (found in review).
            |    - id: android-bridge-token
            |      name: ./android-plugins/bridge-token.mjs
            |
            |# Accessibility-time thinking switch (v1.2.165): the agent runs `fast on`
            |# before an accessibility task, so later steps stop paying the reasoning
            |# cost; `fast off` restores normal conversation. The plugin reads the
            |# ${'$'}DSH_HOME/fast-mode marker file and only affects reasoningEffort.
            |- insert:
            |    - id: android-fast-mode
            |      name: ./android-plugins/fast-mode.mjs
            |
            |# Narrow-screen UI fixes (v1.2.175 - v1.2.181). Each is a *client plugin
            |# package* (directory form, e.g. mobile-ui/package.json): the package
            |# declares dsh.client + exports["./client"], and the engine's client-modules
            |# scanner discovers it from Loader entries and injects the browser bundle.
            |# Why plugins instead of patching the engine source: the user asked for a
            |# plugin form so it never conflicts with other UI plugins and survives
            |# engine upgrades.
            |#
            |# dsh-mobile-ui (v0.1.17, by the user's earlier mobile-ui work): the Android
            |# app-style layer over the shipped Web UI. One client plugin that turns the
            |# sidebar into a popup drawer, collapses the conversation header behind an
            |# info button, and adapts the composer. There is no in-page rollback any more:
            |# if the skin breaks the UI, recover from the native settings screen
            |# ("Stock UI"), which flips dsh_ui/stock_ui, skips this block and deletes the
            |# deployed mobile-ui folder. Everything is gated on html[data-dsh-mobile].
            |- insert:
            |    - id: mobile-ui
            |      name: ./android-plugins/mobile-ui/index.js
            |
            |# v1.3.68 background progress: watch the current session DOM (thinking / step)
            |# and report it to native through DshNative for the notification / floating
            |# window. No-op in a plain browser where DshNative is absent. (ASCII only.)
            |- insert:
            |    - id: mobile-progress
            |      name: ./android-plugins/mobile-progress/index.js
            |
            |""".trimMargin()
        runCatching {
            if (!f.isFile || f.readText() != body) f.writeText(body)
        }
        // The overlay references the plugins by relative path, so the files must
        // sit beside it. Deploy from assets (kept in sync by the same idempotent check).
        runCatching {
            // 单文件插件（.mjs）：overlay 直接指向文件
            listOf("priv-mode.mjs", "bridge-token.mjs", "fast-mode.mjs", "plugin-health.mjs").forEach { fileName ->
                val src = ctx.assets.open("android-plugins/$fileName").use { it.readBytes() }
                val dst = File(f.parentFile, "android-plugins/$fileName")
                deployOne(dst, src.toString(Charsets.UTF_8), fileName)
            }
            // 目录形式的客户端插件包（v1.2.175+）：引擎按 package.json 的 name 索引
            // 浏览器端 bundle，所以 index.js / client.js / package.json 三个文件
            // 必须同目录且完整。缺任何一个 → client-modules 抛
            // "declares dsh.client but exports no ./client bundle" 或 bundle 加载后
            // 未注册（"loaded without registering"）。
            // 新增插件的步骤：① 在 assets 建目录（含这三个文件）
            //               ② 在此列表追加目录名
            //               ③ 在 overlay 的 insert 段追加一条 entry（指向 index.js）
            listOf("mobile-progress", "mobile-ui").forEach { pkg ->
                // prefixes.json 是 mobile-ui 的语义族表（可缺省，缺了插件用内置表）
                listOf("package.json", "index.js", "client.js", "prefixes.json").forEach { fileName ->
                    val bytes = runCatching {
                        ctx.assets.open("android-plugins/$pkg/$fileName").use { it.readBytes() }
                    }.getOrNull() ?: return@forEach
                    val dst = File(f.parentFile, "android-plugins/$pkg/$fileName")
                    deployOne(dst, bytes.toString(Charsets.UTF_8), "$pkg/$fileName")
                }
            }
        }.onFailure { Log.w(EngineConfig.TAG, "android plugin deploy failed: ${it::class.simpleName}: ${it.message}", it) }
        return f
    }

    /**
     * 把一个资产文本部署到引擎目录，带「重装后自愈」语义。
     *
     * ⚠️ v1.2.173 实测背景：每次重装 APK，Android 分配**新 uid**，而旧插件文件
     * 属主仍是旧 uid + 权限 600 → app 自己读不了（EACCES → 引擎 "failed to import"）。
     * 提权修复走不通（Shizuku 的 adb 身份无权访问 app 私有目录）。
     * 但 app 对自己的目录有写权限：删掉目录里的文件只需目录写权限，不需要文件本身的
     * 权限 → 正解 = 读不了就**删掉重建**。
     *
     * ⚠️ v1.2.174：判空必须写成 `!dst.isFile || …`。写成 `dst.isFile && …` 时
     * 文件不存在会让整式为 false（stale=false），于是文件**永不创建**。
     *
     * @param dst 目标文件（父目录会自动创建）。
     * @param text 期望内容。
     * @param label 日志用标签。
     */
    private fun deployOne(dst: File, text: String, label: String) {
        dst.parentFile?.mkdirs()
        val stale = !dst.isFile ||
            runCatching { dst.readText() != text }.getOrDefault(true)
        if (stale) {
            runCatching { dst.delete() }   // 旧属主也能删（目录归 app）
            if (!dst.isFile || runCatching { dst.readText() }.getOrNull() != text) {
                dst.writeText(text)
            }
        }
        dst.setReadable(true, false)
        dst.setReadable(true, true)
        Log.i(EngineConfig.TAG, "plugin deployed: $label (${dst.length()} bytes, readable=${dst.canRead()})")
    }

    /**
     * Agent 能力包装器（v1.1.0）：notify / scr，全模式注入。
     * 二者都是 node fetch 到 AgentBridge (127.0.0.1:3083) 的薄包装：
     *  - notify [message]        → POST /notify（任务完成系统通知；AGENTS.md 约定任务完成必调）
     *  - scr dump                → GET  /screen（读屏：可见文本+坐标 JSON）
     *  - scr tap <x> <y>         → POST /tap 坐标点击
     *  - scr tap-text <文本>     → POST /tap 按文本点击（无障碍服务开启才可用）
     */
    /**
     * 引擎侧 Android 兼容补丁（幂等、按内容锚点，不绑死文件名/版本）。
     *
     * 目前只有一条：`dsh-subprocess-local` 的进程检查器把平台限定成
     * linux/darwin/win32，遇到 android 直接抛
     * `subprocess-local: terminal inspection is unsupported on platform android`
     * → WebUI 侧边栏终端（以及一切用 spawnTerminal 的功能）在 Android 上完全不可用。
     *
     * Android 就是 Linux 的一个变体，检查器用的全是 `/proc/<pid>/` 下的 stat/task/fd/syscall，这些在 Android 都存在；`/bin/ps` 那两处只在 darwin 分支和
     * `foregroundPgid` 里用，且调用点已 try/catch（取不到就返回 undefined），
     * 不会把终端判死。因此把 android 归到 Linux 分支是正确且最小的改动。
     *
     * 为什么在 App 启动时打而不是构建期：dsh-subprocess-local 的文件名带内容哈希
     * （runner-launch-XXXX.js），版本一升就变；按**文件内容锚点**扫描整个包目录，
     * 既能跨版本生效，也不会在锚点消失时误改别的代码。
     */
    /** 单个 overlay 文件部署（内容一致则跳过；失败只记日志不阻断启动） */
    private fun deployOverlayFile(ctx: android.content.Context, root: File, rel: String) {
        runCatching {
            val bytes = ctx.assets.open("runtime-overlay/$rel").use { it.readBytes() }
            val dst = File(root, rel)
            if (dst.isFile && dst.readBytes().contentEquals(bytes)) return@runCatching
            dst.parentFile?.mkdirs()
            dst.writeBytes(bytes)
            // overlay 里有需要直接执行的（bin/tar 的 GNU 选项过滤器）；统一给 +x。
            dst.setExecutable(true, false)
            Log.i(EngineConfig.TAG, "runtime overlay deployed: $rel")
        }.onFailure { Log.w(EngineConfig.TAG, "runtime overlay $rel failed: ${it.message}") }
    }

    internal fun patchEngineForAndroid(root: File) {
        val dir = File(root, "lib/node_modules/@deepseek-ai/dsh-subprocess-local/lib")
        if (!dir.isDirectory) {
            Log.w(EngineConfig.TAG, "engine patch: subprocess-local not found, skip")
            return
        }
        val anchor = "if (platform === \"linux\") return new LinuxProcessInspector(arch, internals);"
        val replacement = "/* [dsh-android] Android has /proc: reuse the Linux inspector */\n" +
            "\tif (platform === \"linux\" || platform === \"android\") return new LinuxProcessInspector(arch, internals);"
        var patched = 0
        dir.listFiles { f -> f.isFile && f.name.endsWith(".js") }?.forEach { f ->
            val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
            if (!text.contains(anchor)) return@forEach
            runCatching {
                f.writeText(text.replace(anchor, replacement))
                patched++
                Log.i(EngineConfig.TAG, "engine patch: android terminal inspection enabled in ${f.name}")
            }.onFailure { Log.w(EngineConfig.TAG, "engine patch failed on ${f.name}: ${it.message}") }
        }
        if (patched == 0) Log.i(EngineConfig.TAG, "engine patch: android terminal inspection already patched")
        patchInspectorWorkerForAndroid(root)
        patchFrontendSpaFallback(root)
        patchNativeCommandForAndroid(root)
    }

    /**
     * 让 WebUI 的「open in app / 在文件夹中显示」在 Android 上生效（v1.3.41）。
     *
     * 上游 `dsh-native-command` 把 android 当作「没有桌面」直接拒绝，于是 WebUI 显示
     * 「此主机没有可用的桌面，无法使用外部程序打开文件或文件夹」。这里按内容锚点打补丁：
     *  - `canOpenNativePath` 对 android 返回 true（让按钮出现）；
     *  - `openNativePathWithIntent` 增加 android 分支：POST 到本 App 的 AgentBridge
     *    `/open/file`，由 Kotlin 侧用 DshFileProvider + ACTION_VIEW 交给系统 App 打开。
     * token 优先取进程环境，否则回退读 $PREFIX/.bridge-token。
     */
    private fun patchNativeCommandForAndroid(root: File) {
        val file = File(root, "lib/node_modules/@deepseek-ai/dsh-native-command/lib/index.js")
        if (!file.isFile) { Log.w(EngineConfig.TAG, "native-command patch: module not found, skip"); return }
        var text = runCatching { file.readText() }.getOrNull() ?: return
        val before = text

        // 1) 注入/升级 bridge 调用助手（android 分支共用；v73 的旧助手没有 route 参数，必须替换）
        val helperAnchor = "/** Dispatch one shell-free platform command for the requested open intent. */"
        val helper = """/** [dsh-android] android open v3: hand the path to the app's AgentBridge (v1.3.42). */
async function openAndroidPath(path, signal, env, route = "/open/file") {
	let token = env.DSH_BRIDGE_TOKEN || process.env.DSH_BRIDGE_TOKEN || "";
	if (!token) {
		try {
			token = (await readFile(process.env.PREFIX + "/.bridge-token", "utf8")).trim();
		} catch {}
	}
	console.error("[dsh-android] bridge open " + route + " path=" + path);
	const res = await fetch("http://127.0.0.1:3083" + route, {
		method: "POST",
		headers: { "content-type": "application/json", "x-dsh-token": token },
		body: JSON.stringify({ path }),
		signal
	});
	const body = await res.text();
	console.error("[dsh-android] bridge open result " + res.status + " " + body.slice(0, 400));
	if (!res.ok) throw new Error("android open failed (" + res.status + "): " + body);
	return body;
}
/** Dispatch one shell-free platform command for the requested open intent. */"""
        val oldHelperStart = text.indexOf("/** [dsh-android] android open")
        when {
            oldHelperStart >= 0 -> {
                val oldHelperEnd = text.indexOf(helperAnchor, oldHelperStart)
                if (oldHelperEnd > oldHelperStart) {
                    text = text.substring(0, oldHelperStart) + helper + text.substring(oldHelperEnd)
                    Log.i(EngineConfig.TAG, "native-command patch: bridge helper upgraded to v2")
                }
            }
            text.contains(helperAnchor) -> {
                text = text.replace(helperAnchor, helper)
                Log.i(EngineConfig.TAG, "native-command patch: bridge helper injected")
            }
            else -> Log.w(EngineConfig.TAG, "native-command patch: helper anchor missing")
        }

        // 2) 默认打开（open / association / text-editor 都走这里）
        val throwAnchor = "throw new Error(`native path opener is unsupported on \${platform}`);"
        val branch = "\tif (platform === \"android\") {\n\t\tawait openAndroidPath(path, signal, env);\n\t\treturn;\n\t}\n"
        if (text.contains(throwAnchor) && !text.contains("await openAndroidPath(path, signal, env);")) {
            text = text.replace(throwAnchor, branch + throwAnchor)
        }

        // 3) 「显示文件位置」（reveal）在 android 上打开父目录
        val revealAnchor = "async function revealNativePath(path, signal, internals = {}) {"
        val revealBranch = "async function revealNativePath(path, signal, internals = {}) {\n\t// [dsh-android] android reveal: open the parent folder through the app bridge\n\tif ((internals.platform ?? process.platform) === \"android\") {\n\t\tawait openAndroidPath(dirname(path), signal, internals.env ?? process.env, \"/open/folder\");\n\t\treturn;\n\t}"
        if (text.contains(revealAnchor) && !text.contains("[dsh-android] android reveal")) {
            text = text.replace(revealAnchor, revealBranch)
        }

        // 4) canOpenNativePath 对 android 返回 true（让 UI 显示按钮）
        val canOpenAnchor = "if (platform === \"darwin\" || platform === \"win32\") return true;"
        if (text.contains(canOpenAnchor)) {
            text = text.replace(canOpenAnchor, "if (platform === \"darwin\" || platform === \"win32\" || platform === \"android\") return true;")
        }

        if (text == before) { Log.i(EngineConfig.TAG, "native-command patch: already applied"); return }
        runCatching {
            file.writeText(text)
            Log.i(EngineConfig.TAG, "engine patch: android open-in-app enabled (native-command)")
        }.onFailure { Log.w(EngineConfig.TAG, "native-command patch failed: ${it.message}") }
    }

    /**
     * WebUI 客户端路由 fallback（v1.3.27，issue「设置页歧义」）。
     *
     * 上游 `dsh-host-frontend-static` 对 dist 里不存在的路径一律 404，导致任何
     * 客户端路由（/settings、/settings/general…）一刷新/直链就白屏；WebUI 的
     * 设置其实是「账号菜单 → 设置」的全屏浮层（URL 不变），不是路由。这里按
     * 内容锚点把「扩展名缺失的未知路径」改回 index.html，让深链/刷新得到 SPA
     * 入口而不是错误页（API/静态资源路径有扩展名或是具名路由，不受影响）。
     */
    private fun patchFrontendSpaFallback(root: File) {
        val file = File(root, "lib/node_modules/@deepseek-ai/dsh-host-frontend-static/lib/index.js")
        if (!file.isFile) { Log.w(EngineConfig.TAG, "spa fallback: frontend-static not found, skip"); return }
        val text = runCatching { file.readText() }.getOrNull() ?: return
        if (text.contains("[dsh-android] SPA fallback")) return
        val anchor = """	} catch (error) {
		if (!STATIC_MISS_CODES.has(error.code)) throw error;
		res.writeHead(404);
		res.end();
		return;
	}"""
        val replacement = """	} catch (error) {
		if (!STATIC_MISS_CODES.has(error.code)) throw error;
		// [dsh-android] SPA fallback (v1.3.27): extensionless client routes such as
		// /settings must return index.html instead of 404 (refresh/deep links otherwise
		// show chrome-error and look like the settings page is broken).
		const lastSegment = target.slice(distRoot.length).split(sep).pop() ?? "";
		if (lastSegment.includes(".")) {
			res.writeHead(404);
			res.end();
			return;
		}
		if (!authorizeIndex()) return;
		const spaBody = await renderIndex();
		res.writeHead(200, { "content-type": HTML_MIME });
		res.end(spaBody);
		return;
	}"""
        if (!text.contains(anchor)) { Log.w(EngineConfig.TAG, "spa fallback anchor missing (upstream changed?)"); return }
        runCatching {
            file.writeText(text.replace(anchor, replacement))
            Log.i(EngineConfig.TAG, "engine patch: SPA fallback enabled in frontend-static")
        }.onFailure { Log.w(EngineConfig.TAG, "spa fallback patch failed: ${it.message}") }
    }

    /**
     * 开发者工具（experimental-inspector）的 Worker 需要 `--expose-internals`。
     *
     * 上游 `dsh-experimental-inspector/lib/index.js` 里 `spawnWorker()` 写死
     * `execArgv: []` —— 桌面端没问题，因为它用**预编译原生 addon** 访问 Node 内部模块；
     * Android 上我们只能用 JS 兜底（`node-addon-require-builtin` 改成 createRequire），
     * 而 createRequire('internal/...') 只有在进程启动带 `--expose-internals` 时可用。
     * Worker 的 execArgv 被显式清空 → 插件激活时报：
     *   Error: Cannot find module 'internal/modules/esm/loader'
     * 这里把空的 execArgv 补成 `["--expose-internals"]`（幂等；热更新后重跑）。
     */
    private fun patchInspectorWorkerForAndroid(root: File) {
        val f = File(root, "lib/node_modules/@deepseek-ai/dsh-experimental-inspector/lib/index.js")
        if (!f.isFile) {
            Log.i(EngineConfig.TAG, "inspector patch: experimental-inspector not present, skip")
            return
        }
        val text = runCatching { f.readText() }.getOrNull() ?: return
        if (text.contains("execArgv: [\"--expose-internals\"]")) {
            Log.i(EngineConfig.TAG, "inspector patch: worker execArgv already patched")
            return
        }
        if (!text.contains("execArgv: []")) {
            Log.w(EngineConfig.TAG, "inspector patch: anchor 'execArgv: []' not found (engine layout changed?)")
            return
        }
        runCatching {
            f.writeText(text.replace("execArgv: []", "execArgv: [\"--expose-internals\"]"))
            Log.i(EngineConfig.TAG, "inspector patch: worker execArgv += --expose-internals")
        }.onFailure { Log.w(EngineConfig.TAG, "inspector patch failed: ${it.message}") }
    }

    /**
     * 运行时 overlay：`assets/runtime-overlay/<相对路径>` → `<EngineConfig.engineRoot>/<相对路径>`。
     *
     * 为什么需要：有些「引擎侧」的兼容层无法在运行时打包阶段解决，只能在 App 启动时
     * 覆盖进引擎树。当前唯一的一项是 node-pty 的**真实现**：
     *  - 构建期（collect-termux-runtime.sh）把 node-pty 打成空壳，因为上游预编译是
     *    glibc/x64 的 .node，Android 加载不了 —— 于是 WebUI 侧边栏终端一开就 throw；
     *  - 现在 App 进程有真 PTY（libdshpty.so），overlay 里的 node-pty 通过
     *    AgentBridge 的 pty 系列端点转发，侧边栏拿到的是真 tty。
     *
     * 部署条件（幂等、可升级）：
     *  - 目标不存在 → 写；
     *  - 目标是空壳（package.json 里含 "android-shim"）→ 覆盖；
     *  - 目标已是 overlay 版本（内容一致）→ 跳过；
     *  - 目标是**真** node-pty（有 build/Release 等）→ 不动，避免破坏未来真机可用的预编译。
     */
    /**
     * 生成 bin/{corepack,npm,npx,pnpm} 四个 POSIX 壳脚本。
     *
     * 运行时（engine-dsh-aarch64.zip）只提供 `bin/node` 与 `lib/node_modules/corepack`，
     * 没有 npm/pnpm 入口；而 dsh 的插件安装器与 dsh-market 都按 PATH 找它们。
     * 这里用 corepack 做唯一实现来源，npm/pnpm/npx 都是它的转发壳，避免再塞一份 npm 进 APK。
     */
    internal fun ensureNodeTooling(ctx: android.content.Context, root: File) {
        val bin = File(root, "bin")
        if (!bin.isDirectory) return
        val node = File(bin, "node")
        val corepackJs = File(root, "lib/node_modules/corepack/dist/corepack.js")
        // corepack 的 dist/ 下每个工具都有自己的 JS 入口（npm.js/npx.js/pnpm.js），
        // 只有 corepack.js 本体是 Android shell 包装脚本。所以：
        //   corepack → 直接执行那个 shell 包装脚本
        //   npm/npx  → node corepack/dist/<tool>.js（corepack 自带的转发实现）
        //   pnpm     → 优先用随包附带的 pnpm（离线可用），否则退回 corepack 版
        val shims = mapOf(
            "corepack" to "exec \"\$(dirname \"\$0\")/../lib/node_modules/corepack/dist/corepack.js\" \"\$@\"",
            "npm" to "exec \"\$(dirname \"\$0\")/node\" \"\$(dirname \"\$0\")/../lib/node_modules/corepack/dist/npm.js\" \"\$@\"",
            "npx" to "exec \"\$(dirname \"\$0\")/node\" \"\$(dirname \"\$0\")/../lib/node_modules/corepack/dist/npx.js\" \"\$@\"",
            "pnpm" to "PNPM_JS=\"\${DSH_HOME:-\$(dirname \"\$0\")/../../dsh-home}/profiles/web/node_modules/pnpm/bin/pnpm.cjs\"; [ -f \"\$PNPM_JS\" ] && exec \"\$(dirname \"\$0\")/node\" \"\$PNPM_JS\" \"\$@\"; exec \"\$(dirname \"\$0\")/node\" \"\$(dirname \"\$0\")/../lib/node_modules/corepack/dist/pnpm.js\" \"\$@\"",
        )
        shims.forEach { (name, body) ->
            val f = File(bin, name)
            val want = "#!/system/bin/sh\n# generated by DSH Mobile: forwards to corepack\n$body\n"
            val have = runCatching { if (f.isFile) f.readText() else "" }.getOrDefault("")
            if (have == want && f.canExecute()) return@forEach
            runCatching {
                f.writeText(want)
                f.setExecutable(true, false)
                Log.i(EngineConfig.TAG, "node tooling shim written: bin/$name")
            }.onFailure { Log.w(EngineConfig.TAG, "node tooling shim failed ($name): ${it.message}") }
        }
        if (!corepackJs.isFile) Log.w(EngineConfig.TAG, "corepack.js missing at ${corepackJs.absolutePath}")
        if (!node.isFile) Log.w(EngineConfig.TAG, "node missing at ${node.absolutePath}")
        // pnpm 的全局安装目录（dsh 的 PATH 探测器会查这两处）
        File(EnginePaths.dshHome(ctx), "local/bin").mkdirs()
        File(EnginePaths.dshHome(ctx), ".local/share/pnpm").mkdirs()
        File(EnginePaths.dshHome(ctx), ".cache/corepack").mkdirs()
    }

    internal fun applyRuntimeOverlays(ctx: android.content.Context, root: File) {
        // ① 与 node-pty 无关的 overlay：pkg 的前端（Termux 仓库 + dpkg 的 node 驱动）。
        //    `pkg` 门脚本只负责 exec node bin/pkg.js；这里保证脚本每次启动都是最新版。
        // 包管理器切换成 Termux 原生 pkg/apt 后，pkg.js / tar / sh 垫片全部删除
        // ② node-pty 真实现：仅当运行时里那份不是原生编译产物时才覆盖。
        val pkgDir = File(root, "lib/node_modules/node-pty")
        // 判定「能不能覆盖」而不是「版本号长什么样」：只要这棵树里没有**原生产物**
        // （.node / prebuilds / build/Release），就说明它是空壳或我们自己的 bridge 版，
        // 可以覆盖；一旦将来 runtime 里出现能用的真 node-pty 预编译，就绝不碰它。
        val hasNative = runCatching {
            pkgDir.walkTopDown().any { f ->
                f.isFile && (f.name.endsWith(".node") || f.path.contains("/prebuilds/") ||
                    f.path.contains("/build/Release/"))
            }
        }.getOrDefault(false)
        if (hasNative) {
            Log.i(EngineConfig.TAG, "runtime overlay: node-pty has native artifacts, skipping")
            return
        }
        val files = listOf(
            "lib/node_modules/node-pty/package.json",
            "lib/node_modules/node-pty/lib/index.js",
        )
        for (rel in files) {
            runCatching {
                val bytes = ctx.assets.open("runtime-overlay/$rel").use { it.readBytes() }
                val dst = File(root, rel)
                if (dst.isFile && dst.readBytes().contentEquals(bytes)) return@runCatching
                dst.parentFile?.mkdirs()
                dst.writeBytes(bytes)
                Log.i(EngineConfig.TAG, "runtime overlay deployed: $rel")
            }.onFailure { Log.w(EngineConfig.TAG, "runtime overlay $rel failed: ${it.message}") }
        }
    }

    /**
     * 由 AndroidOverlay.applyAgentGates 部署到 engine/bin 的门脚本清单（v1.3.31 起集中定义）。
     * 新加门脚本只改这里，部署与完整性探针共用同一份，避免探针漏检新脚本。
     */
    private val AGENT_GATES = listOf(
        "_dsh_http.sh", "notify", "scr", "say", "loop", "fast",
        "dsh-update", "shot", "appshot", "native", "media", "docs",
    )

    /**
     * 特权门脚本：只有 ROOT / SHIZUKU 模式下才部署。
     * NORMAL 下它们必然失败（`screenrecord` 要 shell uid），留在 bin 里只会让 agent
     * 撞墙 —— 所以「不部署」而不是「部署后报错」。切换回 NORMAL 时同步删除。
     */
    private val AGENT_GATES_PRIV = listOf("record")

    /** 权限位（用于诊断日志，如 0600/0755）。 */
    private fun octalMode(f: File): String = runCatching {
        val perms = java.nio.file.Files.getPosixFilePermissions(f.toPath())
        val order = listOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.GROUP_READ,
            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
            java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
            java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE,
        )
        order.chunked(3).joinToString("") { (r, w, x) ->
            val v = (if (perms.contains(r)) 4 else 0) +
                (if (perms.contains(w)) 2 else 0) +
                (if (perms.contains(x)) 1 else 0)
            v.toString()
        }
    }.getOrDefault("?")

    internal fun applyAgentGates(ctx: android.content.Context, root: File) {
        val bindir = File(root, "bin").apply { mkdirs() }
        try {
            // ── 门脚本：从 assets/gates 部署（v1.2.50）─────────────────────────
            // 此前 notify/scr/say 内嵌在 Kotlin 字符串里，每次调用 `exec node -e fetch`
            // 都要冷启一个 Node（实测 ~150ms/次 + 数十 MB 峰值内存；内存高水位时
            // scr dump 挂 >60s）。现改为 assets 里的纯 bash 脚本（配 _dsh_http.sh
            // 共享 HTTP 客户端），零 Node 冷启 + 内建超时。
            // 放在 assets 而非 Kotlin 字符串：避免 bash/Kotlin 双层转义（易错且难维护）。
            // 门脚本 shebang 用 #!@DSH_BASH@ 占位符：部署时替换为引擎 bash 的实际路径。
            // 必须用 bash（/dev/tcp 是 bash 特性；/system/bin/sh 是 toybox，不支持）。
            val bashPath = File(root, "bin/bash").absolutePath
            val libDir = File(root, "lib").absolutePath
            val broken = mutableListOf<String>()

            val elevated = Privilege.isElevated(ctx)
            if (!elevated) AGENT_GATES_PRIV.forEach { runCatching { File(bindir, it).delete() } }
            (AGENT_GATES + if (elevated) AGENT_GATES_PRIV else emptyList()).forEach { name ->
                val dst = File(bindir, name)
                runCatching {
                    val text = ctx.assets.open("gates/$name").use { it.readBytes().toString(Charsets.UTF_8) }
                    // ⚠️ CRLF 剥离（v1.2.52 事故）：门脚本曾被以 CRLF 打进 APK，
                    // 首行变成 `#!/system/bin/sh\r` → 内核按字面找解释器，报
                    // "bad interpreter: No such file or directory"（误导性地像文件丢失）。
                    // 三个门脚本 100% 失效，而 notify/scr/say 是种子要求 agent 必用的能力。
                    // 根因是工作区文件在 .gitattributes 生效前就已以 CRLF 检出；此处
                    // 无条件剥离，作为不依赖构建环境行尾的兜底防线。
                    val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
                        .replace("@DSH_BASH@", bashPath).replace("@DSH_LIBDIR@", libDir)
                    // ⚠️ v1.3.31 权限事故：$PREFIX/bin 里 bootstrap 留下的同名条目可能是
                    // 指向 toybox 的符号链接；直接 writeText 会写穿到 toybox 本体（或失败）。
                    // 必须先删除再写。且 Java 在 Android 上新建文件是 0600 —— 不 chmod 的话
                    // native/appshot/shot/record 这些新脚本全都不可执行。
                    if (runCatching { java.nio.file.Files.isSymbolicLink(dst.toPath()) }.getOrDefault(false) || !dst.isFile) dst.delete()
                    dst.writeText(normalized)
                    dst.setReadable(true, false)
                    dst.setExecutable(true, false)
                    if (!dst.canExecute()) Log.w(EngineConfig.TAG, "agent gate $name is NOT executable after chmod")
                }.onFailure { Log.w(EngineConfig.TAG, "agent gate $name deploy failed: ${it.message}") }
            }
            Log.i(EngineConfig.TAG, "agent gates deployed (${AGENT_GATES.size + if (elevated) AGENT_GATES_PRIV.size else 0}, " +
                "elevated=$elevated): ${(AGENT_GATES + if (elevated) AGENT_GATES_PRIV else emptyList()).joinToString(" ")}")
            // v1.8.2（点击链路提案 P1-4）：PP-OCRv6 tiny 兜底 —— 脚本放 bin、模型放 share。
            // 只部署 tiny（6MB）；python/onnxruntime 由 /ocr?engine=ppocr6&ensure=1 显式安装。
            runCatching {
                val share = File(root, "share/ppocr6").apply { mkdirs() }
                val pyDir = File(share, "py").apply { mkdirs() }
                listOf("onnxruntime.py", "libortshim.so", "ppocr6_worker.py").forEach { name ->
                    val bytes = ctx.assets.open("ppocr6/py/$name").use { it.readBytes() }
                    val dst = File(pyDir, name)
                    if (!dst.isFile || dst.length() != bytes.size.toLong()) dst.writeBytes(bytes)
                    dst.setReadable(true, false)
                    if (name.endsWith(".so")) dst.setExecutable(true, false)
                }
                listOf("det_tiny.onnx", "rec_tiny.onnx", "rec_tiny.yml").forEach { name ->
                    val bytes = ctx.assets.open("ppocr6/$name").use { it.readBytes() }
                    val dst = File(share, name)
                    if (!dst.isFile || dst.length() != bytes.size.toLong()) dst.writeBytes(bytes)
                }
                val scriptText = ctx.assets.open("ppocr6/ppocr6").use { it.readBytes().toString(Charsets.UTF_8) }
                    .replace("\r\n", "\n").replace("\r", "\n")
                val dst = File(bindir, "ppocr6")
                if (runCatching { java.nio.file.Files.isSymbolicLink(dst.toPath()) }.getOrDefault(false) || !dst.isFile) dst.delete()
                if (!dst.isFile || dst.readText() != scriptText) dst.writeText(scriptText)
                dst.setReadable(true, false)
                dst.setExecutable(true, false)
                Log.i(EngineConfig.TAG, "ppocr6 deployed: script=${dst.length()}B models=${share.list()?.size ?: 0}")
            }.onFailure { Log.w(EngineConfig.TAG, "ppocr6 deploy failed: ${it.message}") }
            // planner-daemon 已于 v1.2.167 移除（用户要求：AI 用自己的工具做无障碍）

    // LAN 反向代理脚本（v1.2.160）：与 daemon 同一套部署方式（assets → tools）。
    // 为什么用 node 而不是 app 内手写 HTTP 代理：chunked/压缩/Cookie/特殊 URL
    // （plugins/??a.js,b.js）/WebSocket 升级，手写全都要自己处理 —— 实测反复出
    // 问题（白屏 / 0 字节资源 / 满屏乱码）。node 的 http 模块天然处理这些。
    runCatching {
                val toolsDir = File(EnginePaths.dshHome(ctx), "tools").apply { mkdirs() }
                val src = ctx.assets.open("tools/lan-web-proxy.mjs").use { it.readBytes().toString(Charsets.UTF_8) }
                val dst = File(toolsDir, "lan-web-proxy.mjs")
                // 同插件：读不了就删掉重建（重装后 uid 变化导致 EACCES 的解法）
                val stale = !dst.isFile ||
                    runCatching { dst.readText() != src }.getOrDefault(true)
                if (stale) {
                    runCatching { dst.delete() }
                    if (!dst.isFile || runCatching { dst.readText() }.getOrNull() != src) {
                        dst.writeText(src)
                    }
                }
                dst.setReadable(true, false)
                dst.setReadable(true, true)
    }.onFailure { Log.w(EngineConfig.TAG, "lan-web-proxy deploy failed: ${it.message}") }

            listOf("notify", "scr", "say", "loop", "fast", "dsh-update").forEach { File(bindir, it).setExecutable(true, false) }
            // 已下线的门脚本（v1.3.0 USB 桥回退）：清掉设备上的旧副本
            // v2.5.43：proot 子系统移除后，老设备上残留的 linux 门脚本也一并清掉
            listOf("pc", "linux").forEach { runCatching { File(bindir, it).delete() } }
            File(bindir, "_dsh_http.sh").setReadable(true, false)

            // 部署后自检（v1.2.52）：逐个校验首行不含 \r 且指向存在的解释器。
            // 失败不静默——写进 engine.log 并给出明确原因，避免「看起来像文件丢了」的误判。
            (AGENT_GATES + if (elevated) AGENT_GATES_PRIV else emptyList()).forEach { name ->
                val f = File(bindir, name)
                val head = runCatching {
                    f.inputStream().use { ins ->
                        val buf = ByteArray(128)
                        val n = ins.read(buf)
                        String(buf, 0, maxOf(n, 0), Charsets.UTF_8).lineSequence().first()
                    }
                }.getOrNull().orEmpty()
                when {
                    !f.isFile -> broken += "$name (missing)"
                    !f.canExecute() -> broken += "$name (mode ${octalMode(f)} not executable)"
                    head.contains('\r') -> broken += "$name (CRLF in shebang: ${head.take(30)})"
                    head.startsWith("#!") && !File(head.removePrefix("#!").trim()).isFile ->
                        broken += "$name (interpreter missing: ${head.removePrefix("#!").trim()})"
                }
            }
            if (broken.isNotEmpty()) {
                Log.w(EngineConfig.TAG, "agent gates BROKEN: ${broken.joinToString("; ")}")
            } else {
                Log.i(EngineConfig.TAG, "agent gates: ${AGENT_GATES.size} scripts deployed, executable (bridge :3083)")
            }

            // ── Android 编译桩 jar 预置（v1.2.107）────────────────────────────
            // 动机：AGENTS.md 原先教 Agent 自己跑 `extract-android-stub.py` 去提取
            // android.jar，但那是**开发机上的脚本** —— 设备上既没有该脚本、也没有
            // git 仓库可 pull，Agent 照着做只会扑空（主人实测反馈「那个 py 文件似乎
            // 不存在」）。现在改为随包发布、启动即落位，Agent 直接 `-cp ~/android.jar`。
            // 幂等：按字节数比对（体积固定，内容变化必然反映在长度上），命中就跳过。
            runCatching {
                val jar = File(EngineConfig.dshHome(ctx), "android.jar")
                val want = ctx.assets.open("android-stub/android.jar")
                    .use { it.available().toLong() }
                if (!jar.isFile || jar.length() != want) {
                    ctx.assets.open("android-stub/android.jar").use { ins ->
                        jar.outputStream().use { ins.copyTo(it) }
                    }
                    jar.setReadable(true, false)
                    Log.i(EngineConfig.TAG, "android.jar staged: ${jar.length()} bytes (expected $want)")
                }
            }.onFailure { Log.w(EngineConfig.TAG, "android.jar staging failed: ${it.message}") }

        } catch (e: Exception) {
            Log.w(EngineConfig.TAG, "agent gates: ${e.message}")
        }
    }
}
