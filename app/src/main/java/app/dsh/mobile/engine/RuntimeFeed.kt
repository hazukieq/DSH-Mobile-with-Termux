package app.dsh.mobile.engine

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * dsh 运行时更新源（feed）。
 *
 * ## 为什么单独一个文件（也是这次改动的核心）
 *
 * 三个「版本」必须分清，混用是之前 `dsh-update` 语义跑偏的根因：
 *
 * | 名字 | 例子 | 谁决定 | 怎么升级 |
 * |---|---|---|---|
 * | **APK 版本** | `v1.3.1` | 本仓库 release | 重装 APK |
 * | **运行时资产** | `v1.3.1-app-prefix-arm64` | APK 内置 `assets/runtime.zip` | 随 APK |
 * | **dsh 版本** | `0.2.0-rc.2` | 上游 `@deepseek-ai/dsh` | **本文件负责：独立拉取** |
 *
 * 用户要的是第三种：拉「最新 dsh 运行时」，**不是**最新 APK，也不是「最新 APK 里
 * 那份 runtime.zip」。所以 feed 清单以 **dsh 版本**为键，每个 dsh 版本发布一份
 * 按本仓库 Android 补丁构建好的运行时包（补丁在 CI 里跑，见
 * `scripts/collect-termux-runtime.sh` 的 `DSH_VERSION` 与 `.github/workflows/dsh-runtime.yml`）。
 *
 * ## 为什么不在设备上直接 `npm install @deepseek-ai/dsh@latest`
 *
 * 因为 Android 兼容补丁是在**构建期**打到 dsh 依赖树里的（openPath 内联读文件、
 * 旧 WebView polyfill、koffi/node-pty 打桩、`@deepseek-ai/node-addon-system` 的
 * flock 兜底——最后这条缺了会直接让引擎陷入重启循环）。设备上没有 python3，
 * 也没有能力重跑这套补丁；盲装上游包 = 装一个起不来的引擎。所以「拉最新 dsh」
 * 的实现是**拉一份按该 dsh 版本预先构建并打过补丁的运行时包**。
 *
 * ## feed 清单格式（`runtime-manifest.json`）
 *
 * ```json
 * {
 *   "schema": 1,
 *   "dsh_version": "0.2.1",
 *   "built_at": "2026-10-18T02:00:00Z",
 *   "assets": {
 *     "arm64-v8a": {"url": "https://…/runtime-0.2.1-arm64-v8a.zip",
 *                   "sha256": "…64 hex…", "size": 172961543},
 *     "x86_64":    {"url": "…", "sha256": "…", "size": 176352302}
 *   }
 * }
 * ```
 *
 * 清单地址默认是本仓库 release 的滚动 tag `dsh-runtime-latest`；可用环境变量
 * `DSH_RUNTIME_MANIFEST` 或 CLI 的 `--manifest` 覆盖（自建镜像 / 本地测试）。
 */
object RuntimeFeed {

    /** 滚动发布 tag 上的清单；构建流水线每发布一个 dsh 版本就更新它 */
    const val DEFAULT_MANIFEST_URL =
        "https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/download/dsh-runtime-latest/runtime-manifest.json"

    /** npm 的 dsh 包名（registry 查询只用于回答「上游有没有更新」，不直接安装） */
    private const val NPM_PACKAGE = "@deepseek-ai/dsh"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val MANIFEST_TIMEOUT_MS = 30_000
    private const val NPM_TIMEOUT_MS = 8_000

    /** dsh 引擎在运行时里的 package.json（版本号的权威来源） */
    private const val DSH_PACKAGE_JSON = "lib/node_modules/@deepseek-ai/dsh/package.json"

    data class Asset(val url: String, val sha256: String, val size: Long)

    data class Manifest(val dshVersion: String, val builtAt: String, val assets: Map<String, Asset>) {
        fun assetFor(abi: String): Asset? = assets[abi] ?: assets[abi.replace('_', '-')]
    }

    /** `check` 的结果；同时提供 JSON（桥）与 `key: value`（门脚本）两种序列化 */
    data class Check(
        val ok: Boolean,
        val liveDsh: String,
        val liveRuntime: String,
        val abi: String,
        val feedUrl: String,
        val feedDsh: String,
        val feedBuiltAt: String,
        val npmLatest: String,
        val assetSha: String,
        val assetSize: Long,
        val updateAvailable: Boolean,
        val error: String,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("ok", ok)
            put("live_dsh", liveDsh)
            put("live_runtime", liveRuntime)
            put("abi", abi)
            put("feed", feedUrl)
            put("feed_dsh", feedDsh)
            put("feed_built_at", feedBuiltAt)
            put("npm_latest", npmLatest)
            put("asset_sha", assetSha)
            put("asset_size", assetSize)
            put("update_available", updateAvailable)
            put("error", error)
        }.toString()

        fun toLine(): String = buildString {
            appendLine("ok: $ok")
            appendLine("dsh: $liveDsh")
            appendLine("runtime: $liveRuntime")
            appendLine("abi: $abi")
            appendLine("feed: $feedUrl")
            appendLine("feed_dsh: $feedDsh")
            appendLine("feed_built_at: $feedBuiltAt")
            appendLine("npm_latest: $npmLatest")
            appendLine("asset_sha: " + assetSha.take(16))
            appendLine("asset_size: $assetSize")
            appendLine("update_available: $updateAvailable")
            appendLine("error: $error")
        }
    }

    fun manifestUrl(): String =
        System.getenv("DSH_RUNTIME_MANIFEST")?.trim()?.takeIf { it.startsWith("http") }
            ?: DEFAULT_MANIFEST_URL

    /** 设备 ABI，取值与清单的 `assets` 键一致（`arm64-v8a` / `x86_64`） */
    fun currentAbi(): String {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        for (abi in abis) {
            when {
                abi == "arm64-v8a" || abi.startsWith("arm64") -> return "arm64-v8a"
                abi == "x86_64" || abi.startsWith("x86_64") -> return "x86_64"
            }
        }
        return abis.firstOrNull() ?: "unknown"
    }

    /** 运行时里 dsh 的实际版本（`0.2.0-rc.2`）；读不到返回 null */
    fun liveDshVersion(ctx: Context): String? = runCatching {
        val f = File(EngineConfig.engineRoot(ctx), DSH_PACKAGE_JSON)
        if (!f.isFile) return@runCatching null
        JSONObject(f.readText()).optString("version").trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * 上游 npm 上的最新 dsh 版本。只用于回答「上游是否已有更新版本」——
     * 即使 npm 更新了，也要等 feed 出对应版本的运行时包才能安装。
     * 先 npmmirror（国内快）再官方 registry（海外 / 镜像缺包时兜底）。
     */
    fun npmLatest(): String? {
        val urls = listOf(
            "https://registry.npmmirror.com/$NPM_PACKAGE/latest",
            "https://registry.npmjs.org/$NPM_PACKAGE/latest",
        )
        for (u in urls) {
            val v = runCatching {
                JSONObject(getText(u, NPM_TIMEOUT_MS)).optString("version").trim()
            }.getOrNull()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    fun fetchManifest(url: String): Manifest {
        val text = getText(url, MANIFEST_TIMEOUT_MS)
        val obj = JSONObject(text)
        val assets = LinkedHashMap<String, Asset>()
        val json = obj.optJSONObject("assets") ?: JSONObject()
        for (abi in json.keys()) {
            val a = json.optJSONObject(abi) ?: continue
            val u = a.optString("url").trim()
            val sha = a.optString("sha256").trim()
            if (u.isEmpty() || sha.isEmpty()) continue
            assets[abi] = Asset(u, sha.lowercase(), a.optLong("size", 0L))
        }
        val dsh = obj.optString("dsh_version").trim()
        if (dsh.isEmpty()) throw IllegalStateException("manifest has no dsh_version")
        if (assets.isEmpty()) throw IllegalStateException("manifest has no usable assets")
        return Manifest(dsh, obj.optString("built_at").trim(), assets)
    }

    /**
     * 查一次「装的是什么 / 上游有什么 / feed 能装什么」。
     *
     * 失败不抛异常：check 是只读诊断，网络不通时整段带 `ok: false` + `error:` 返回，
     * 让 agent 与设置页都能把原因打出来（而不是卡住或只给一句 "failed"）。
     */
    fun check(ctx: Context, manifestUrl: String? = null): Check {
        val feed = (manifestUrl?.trim()?.takeIf { it.isNotEmpty() }) ?: manifestUrl()
        val abi = currentAbi()
        val liveDsh = liveDshVersion(ctx).orEmpty()
        val liveRuntime = RuntimeUpdater.readVersion(EngineConfig.engineRoot(ctx)).orEmpty()
        val npm = npmLatest().orEmpty()
        val manifest = runCatching { fetchManifest(feed) }
        val err = manifest.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }.orEmpty()
        val m = manifest.getOrNull()
        val asset = m?.assetFor(abi)
        // 只有 feed 版本比装的更新才算「可更新」；同版本不重复推（避免把用户从
        // 一个已打补丁的树换成语义相同的另一棵树）。
        val newer = m != null && asset != null && liveDsh.isNotEmpty() && m.dshVersion != liveDsh
        return Check(
            ok = m != null && asset != null,
            liveDsh = liveDsh,
            liveRuntime = liveRuntime,
            abi = abi,
            feedUrl = feed,
            feedDsh = m?.dshVersion.orEmpty(),
            feedBuiltAt = m?.builtAt.orEmpty(),
            npmLatest = npm,
            assetSha = asset?.sha256.orEmpty(),
            assetSize = asset?.size ?: 0L,
            updateAvailable = newer,
            error = if (m == null) err else if (asset == null) "feed has no $abi asset (dsh ${m.dshVersion})" else "",
        )
    }

    /**
     * 下载 feed 里的一个运行时包到 [dest]，边下边校验 SHA-256（大小也对得上才算成功）。
     *
     * @param onProgress 0f..1f（未知大小时按「读到多少字节 / 声明大小」估算；无 Content-Length 时恒 0）
     */
    fun download(asset: Asset, dest: File, onProgress: (Float) -> Unit = {}): File {
        val conn = open(asset.url, 120_000)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("download failed: http $code")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
            val md = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastPct = -1
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct / 100f)
                            }
                        }
                    }
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(asset.sha256, ignoreCase = true)) {
                dest.delete()
                throw IllegalStateException("sha256 mismatch: expected=${asset.sha256.take(16)}… actual=${actual.take(16)}…")
            }
            if (asset.size > 0 && read != asset.size) {
                dest.delete()
                throw IllegalStateException("size mismatch: expected=${asset.size} actual=$read")
            }
            onProgress(1f)
            return dest
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ── HTTP 小工具（无第三方依赖：HttpURLConnection 足够，且不引入 okhttp） ──

    private fun open(url: String, readTimeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        // GitHub release 资源会 302 到 objects.githubusercontent.com；同协议自动跟随即可
        conn.instanceFollowRedirects = true
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("User-Agent", "dsh-mobile-runtime-feed")
        conn.setRequestProperty("Accept", "*/*")
        return conn
    }

    private fun getText(url: String, timeoutMs: Int): String {
        val conn = open(url, timeoutMs)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("http $code for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
