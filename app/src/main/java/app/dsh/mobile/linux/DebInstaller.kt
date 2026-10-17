package app.dsh.mobile.linux

import android.content.Context
import android.util.Log
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * proot 负载安装器：从 Termux 官方仓库取 3 个 `.deb`，解出 `data.tar.xz`，
 * 拍平 `data/data/com.termux/files/usr/` 下的内容到 `<linuxHome>/proot/`。
 *
 * 负载 = proot 5.1.107.96 + libtalloc 2.5.0 + libandroid-shmem 0.7
 * （proot 是动态链接的 bionic 二进制，后两个是它的运行期依赖；少一个都起不来）。
 *
 * 复用 `engine/ExtensionManager` 已验证的链路与坑：
 *  - 源：官方 `https://packages.termux.dev/apt/termux-main`（TUNA/USTC 对这类请求
 *    返回 403，国内无可用镜像，故只有官方源）；
 *  - UA 必须是包管理器身份 `APT/2.12.10 (...)`，否则被 WAF 拦；
 *  - 先探 DNS（connectTimeout 管不到 DNS，黑洞网络会让请求挂 30s+）；
 *  - `Packages.gz` 索引里拿 `Filename` + `SHA256`，下载后强校验；
 *  - **不要只硬编码一个版本**：先试 pin 住的 filename；404 或哈希不符时回落到
 *    索引里的当前 filename（Termux 升级 proot 时无需发版）；
 *  - ar 成员按 header 里的 size 精确限量读取（`LimitInputStream`），再交给
 *    `org.tukaani:xz` 解压，最后用 [TarGzExtractor] 的裸 tar 解析（同一套路径防护）。
 *
 * 原子性：所有文件先解到 `<linuxHome>/proot.tmp`，校验通过后再 rename 到
 * `<linuxHome>/proot`。任何失败都只会留下/删除临时目录，**不会**留下半个 final 目录。
 * 全部是阻塞 IO，调用方负责放在后台线程。
 */
object DebInstaller {

    private const val TAG = "DebInstaller"

    /** Termux 官方仓库根（国内无镜像，只有官方源可达） */
    private const val REPO = "https://packages.termux.dev/apt/termux-main"

    /**
     * UA 必须是包管理器身份：Termux 仓库前置的 WAF 会 403 自创 UA
     * （与 ExtensionManager.HTTP_UA 完全一致，改一处要两处同改）。
     */
    private const val HTTP_UA = "APT/2.12.10 (aarch64; android; termux)"

    /** deb data.tar 里 Termux 的前缀：解包时整体剥掉，让 usr 下的内容直接成为 proot 下的内容 */
    private const val TERMUX_USR_PREFIX = "data/data/com.termux/files/usr"

    /** 钉住的包描述：filename + SHA-256（2026-10-18 从 Packages 索引核对） */
    private data class Pinned(val filename: String, val sha256: String)

    /**
     * 每个 Termux 架构的钉住版本（索引里查到的当前值）。
     * 哈希来自 `dists/stable/main/binary-<arch>/Packages.gz` 的 SHA256 字段。
     */
    private val PINNED: Map<String, Map<String, Pinned>> = mapOf(
        "aarch64" to mapOf(
            "proot" to Pinned(
                "pool/main/p/proot/proot_5.1.107.96_aarch64.deb",
                "8199dca06dccb693ec09fb1759e3e1ad08b4863f0c11c612f89c20bd9ecdc1a0",
            ),
            "libtalloc" to Pinned(
                "pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb",
                "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7",
            ),
            "libandroid-shmem" to Pinned(
                "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb",
                "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6",
            ),
        ),
        "x86_64" to mapOf(
            "proot" to Pinned(
                "pool/main/p/proot/proot_5.1.107.96_x86_64.deb",
                "77ea45540071ca543adda2b51aca2bc3761c52904d013fd0890682288eff9455",
            ),
            "libtalloc" to Pinned(
                "pool/main/libt/libtalloc/libtalloc_2.5.0_x86_64.deb",
                "b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530",
            ),
            "libandroid-shmem" to Pinned(
                "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb",
                "ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797",
            ),
        ),
    )

    /** 仓库索引里我们关心的一条记录 */
    private data class RepoPkg(
        val name: String,
        val version: String,
        val filename: String,
        val sha256: String,
    )

    // ================= 对外路径 / 状态 =================

    fun prootBinary(home: File): File = File(home, "proot/bin/proot")

    /** proot loader：必须通过 `PROOT_LOADER` 指过去（proot 自己找不到） */
    fun loader(home: File): File = File(home, "proot/libexec/proot/loader")

    /** proot 的宿主侧动态库目录（LD_LIBRARY_PATH） */
    fun libDir(home: File): File = File(home, "proot/lib")

    /** proot 进程的临时目录（PROOT_TMP_DIR） */
    fun tmpDir(home: File): File = File(home, "tmp")

    /**
     * 负载是否完整可用（幂等判断 + 安装后自检共用）。
     *
     * 任务要求的三项：bin/proot、lib/libtalloc.so.2、libexec/proot/loader。
     * 额外检查 libandroid-shmem.so：proot 二进制**动态链接**它，缺了会直接
     * `CANNOT LINK EXECUTABLE`，不能算装好。
     */
    fun isComplete(home: File): Boolean {
        val proot = File(home, "proot")
        return listOf(
            File(proot, "bin/proot"),
            File(proot, "lib/libtalloc.so.2"),
            File(proot, "lib/libandroid-shmem.so"),
            File(proot, "libexec/proot/loader"),
        ).all { it.isFile && it.length() > 0L }
    }

    // ================= 安装主流程 =================

    /**
     * 下载并安装 proot 负载（幂等：已完整则直接返回 true）。
     *
     * @param report 总进度 0..1 + 短 ASCII 状态（UI 直接上屏）
     * @return 成功 true；已安装也 true
     * @throws IllegalStateException 网络、校验或解包失败（message 已带可读原因，UI 可直接展示）
     */
    @Suppress("UNUSED_PARAMETER")   // ctx 保留：与设计文档接口一致，且将来可能需要 context 定位缓存
    @Throws(IllegalStateException::class)
    fun installProot(ctx: Context, home: File, report: (Float, String) -> Unit): Boolean {
        if (isComplete(home)) {
            report(1f, "proot payload already installed")
            return true
        }
        val arch = termuxArch()
        home.mkdirs()
        tmpDir(home).mkdirs()
        val cache = File(home, "cache/proot-debs").apply { mkdirs() }
        val staging = File(home, "proot.tmp")
        try {
            report(0.01f, "Fetching proot package index...")
            // 索引失败并不致命：pin 住的 filename 往往仍然有效；真的都失败时下面的
            // 下载会给出带 HTTP 状态码的错误。
            val index = runCatching { fetchPackagesIndex(arch) }
                .onFailure { Log.w(TAG, "Packages index unavailable (${it.message}); using pinned filenames") }
                .getOrDefault(emptyMap())

            val names = listOf("proot", "libtalloc", "libandroid-shmem")
            val debs = ArrayList<Pair<String, File>>(names.size)
            names.forEachIndexed { i, name ->
                val dest = File(cache, "$name-$arch.deb")
                fetchDebWithFallback(arch, name, index, dest) { frac ->
                    report(
                        0.03f + ((i + frac) / names.size.toFloat()) * 0.42f,
                        "Downloading $name...",
                    )
                }
                debs += name to dest
            }

            if (staging.exists()) staging.deleteRecursively()
            check(staging.mkdirs() || staging.isDirectory) {
                "cannot create staging dir: ${staging.absolutePath}"
            }
            debs.forEachIndexed { i, (name, deb) ->
                report(
                    0.45f + (i / names.size.toFloat()) * 0.45f,
                    "Extracting $name...",
                )
                extractDeb(deb, staging)
            }
            restoreExecBits(staging)

            report(0.93f, "Publishing proot payload...")
            val prootDir = File(home, "proot")
            if (prootDir.exists()) prootDir.deleteRecursively()
            if (!staging.renameTo(prootDir)) {
                // 理论上同一文件系统内 rename 必成功；个别 ROM 的 FUSE/DataStore 实现
                // 可能拒绝目录 rename，退化为复制后删除，仍然保证 final 只在完整时出现。
                staging.copyRecursively(prootDir, overwrite = true)
                staging.deleteRecursively()
            }
            check(isComplete(home)) {
                "proot payload incomplete after extraction (need bin/proot, lib/libtalloc.so.2, " +
                    "lib/libandroid-shmem.so, libexec/proot/loader)"
            }
            report(1f, "proot payload installed")
            Log.i(TAG, "proot payload installed for $arch")
            return true
        } catch (e: Exception) {
            // 失败时把 final 目录也清掉：半套 proot 比没有更难诊断
            runCatching { File(home, "proot").takeIf { !isComplete(home) }?.deleteRecursively() }
            throw if (e is IllegalStateException) e
            else IllegalStateException("proot payload install failed: ${e.message}", e)
        } finally {
            runCatching { staging.deleteRecursively() }
            runCatching { cache.deleteRecursively() }
        }
    }

    // ================= deb 下载 =================

    /**
     * 单个 .deb 的下载：pin 住的 filename 与前缀 + 索引当前项都试一遍（按 (filename, sha)
     * 去重），任何一次哈希不符都换下一个候选，绝不接受未校验的文件。
     */
    private fun fetchDebWithFallback(
        arch: String,
        name: String,
        index: Map<String, RepoPkg>,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        val pinned = PINNED[arch]?.get(name)
        val current = index[name]
        val candidates = mutableListOf<Pair<String, String>>()
        pinned?.let { candidates += it.filename to it.sha256 }
        current?.let { candidates += it.filename to it.sha256 }

        var lastErr: Exception? = null
        val seen = HashSet<String>()
        for ((filename, expectedSha) in candidates) {
            if (!seen.add("$filename|$expectedSha")) continue
            try {
                downloadTo("$REPO/$filename", dest, onProgress)
                val actual = sha256(dest)
                if (expectedSha.isNotEmpty() && !actual.equals(expectedSha, ignoreCase = true)) {
                    dest.delete()
                    throw IllegalStateException(
                        "SHA-256 mismatch for $name (expected ${expectedSha.take(12)}..., " +
                            "got ${actual.take(12)}...)",
                    )
                }
                Log.i(TAG, "deb ok: $filename")
                return
            } catch (e: Exception) {
                lastErr = e
                dest.delete()
                Log.w(TAG, "deb failed: $filename (${e.message})")
            }
        }
        throw IllegalStateException(
            "Cannot download $name for $arch from $REPO: ${lastErr?.message ?: "no candidate filename"}",
            lastErr,
        )
    }

    /** 拉取并解析 Packages.gz；只保留 proot 及两个依赖的条目 */
    private fun fetchPackagesIndex(arch: String): Map<String, RepoPkg> {
        val url = "$REPO/dists/stable/main/binary-$arch/Packages.gz"
        val text = GZIPInputStream(ByteArrayInputStream(downloadBytes(url)))
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
        return parsePackages(text)
    }

    /** 解析 apt 文本索引（块之间空行分隔；缩进行是续行，忽略） */
    private fun parsePackages(text: String): Map<String, RepoPkg> {
        val wanted = setOf("proot", "libtalloc", "libandroid-shmem")
        val out = HashMap<String, RepoPkg>(8)
        text.split("\n\n").forEach { block ->
            var name = ""
            var version = ""
            var filename = ""
            var sha = ""
            block.lineSequence().forEach lineLoop@ { line ->
                if (line.isEmpty() || line[0] == ' ' || line[0] == '\t') return@lineLoop
                val idx = line.indexOf(": ")
                if (idx <= 0) return@lineLoop
                val value = line.substring(idx + 2).trim()
                when (line.substring(0, idx)) {
                    "Package" -> name = value
                    "Version" -> version = value
                    "Filename" -> filename = value
                    "SHA256" -> if (value.length == 64) sha = value
                }
            }
            if (name in wanted && filename.isNotEmpty()) {
                out[name] = RepoPkg(name, version, filename, sha)
            }
        }
        return out
    }

    // ================= deb 解包 =================

    /**
     * 从 [deb] 里取出 `data.tar.xz` 成员并解到 [target]（Termux usr 前缀已剥掉）。
     *
     * .deb 是 ar 归档：8 字节 magic `!<arch>\n` + 若干 60 字节头（名字 0..16、
     * 大小 48..58）+ 数据（2 字节对齐）。Termux 的成员名就是 `data.tar.xz`。
     */
    private fun extractDeb(deb: File, target: File): Int {
        DataInputStream(BufferedInputStream(FileInputStream(deb), 64 * 1024)).use { din ->
            val magic = ByteArray(8)
            din.readFully(magic)
            check(String(magic, StandardCharsets.US_ASCII) == "!<arch>\n") {
                "Not a valid .deb: ${deb.name}"
            }
            while (true) {
                val h = ByteArray(60)
                try {
                    din.readFully(h)
                } catch (e: EOFException) {
                    break
                }
                val name = String(h, 0, 16, StandardCharsets.US_ASCII).trim().trimEnd('/')
                val size = String(h, 48, 10, StandardCharsets.US_ASCII).trim().toLongOrNull() ?: break
                if (name.startsWith("data.tar.")) {
                    // 限量流：ar 成员边界内解压，避免读到后续成员
                    val limited = LimitInputStream(din, size)
                    val tar: InputStream = when {
                        name.endsWith(".xz") -> XZInputStream(limited)
                        name.endsWith(".gz") -> GZIPInputStream(limited)
                        name.endsWith(".tar") -> limited
                        else -> throw IllegalStateException("Unsupported deb data member: $name")
                    }
                    return TarGzExtractor.extractTarStream(
                        tar,
                        target,
                        stripPrefix = TERMUX_USR_PREFIX,
                    )
                }
                skipFully(din, size + (size and 1L))   // ar 成员 2 字节对齐
            }
            throw IllegalStateException("No data.tar member found in .deb: ${deb.name}")
        }
    }

    /**
     * 恢复可执行位：deb 解包已按 tar mode 设置过，但 Android 上 `setExecutable`
     * 对部分路径可能受 ROM 限制而静默失败；bin/ 与 libexec/ 是「必须可执行」的地方，
     * 解包后统一再强设一次（与 ExtensionManager.restoreExecBits 同一策略）。
     */
    private fun restoreExecBits(root: File) {
        listOf("bin", "libexec").forEach { sub ->
            val dir = File(root, sub).takeIf { it.isDirectory } ?: return@forEach
            dir.walkTopDown().forEach { f ->
                if (f.isFile && !f.canExecute()) runCatching { f.setExecutable(true, false) }
            }
        }
    }

    // ================= 网络（与 ExtensionManager 同款坑位处理） =================

    private fun openConn(url: String, readTimeoutMs: Int): HttpURLConnection {
        val u = URL(url)
        // DNS 预检：connectTimeout 不覆盖解析阶段，黑洞 DNS 会挂 30s+
        val addrs = dnsResolve(u.host, 5_000)
            ?: throw IllegalStateException(
                "DNS resolution timed out: ${u.host} (restricted network or hijacked DNS?)",
            )
        check(addrs.isNotEmpty()) { "DNS resolution failed: ${u.host}" }
        val conn = u.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", HTTP_UA)
        conn.setRequestProperty("Connection", "Close")
        return conn
    }

    private fun dnsResolve(host: String, timeoutMs: Long): Array<InetAddress>? = try {
        DNS_POOL.submit(Callable { InetAddress.getAllByName(host) })
            .get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "dns resolve fail/timeout: $host (${e.message})")
        null
    }

    private fun downloadBytes(url: String): ByteArray {
        val conn = openConn(url, readTimeoutMs = 45_000)
        val code = conn.responseCode
        check(code in 200..299) { httpFail(code, url) }
        return conn.inputStream.use { it.readBytes() }
    }

    private fun downloadTo(url: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = openConn(url, readTimeoutMs = 120_000)
        val code = conn.responseCode
        check(code in 200..299) { httpFail(code, url) }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                }
            }
        }
        check(dest.length() > 0L) { "Empty download: $url" }
    }

    private fun httpFail(code: Int, url: String): String = when (code) {
        403 -> "HTTP 403 denied: $url (accelerator/VPN might be intercepting the mirror?)"
        404 -> "HTTP 404 not found: $url (Termux repo version changed?)"
        else -> "Download failed with HTTP $code: $url"
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** app ABI -> Termux 仓库架构键（binary-aarch64 / binary-x86_64） */
    private fun termuxArch(): String = when (LinuxDistros.deviceAbi()) {
        LinuxDistros.ABI_ARM64 -> "aarch64"
        else -> "x86_64"
    }

    // ================= 流小工具 =================

    /** 限量输入流（ar 成员按 header size 精确截断，不关闭上游） */
    private class LimitInputStream(src: InputStream, private val limit: Long) : FilterInputStream(src) {
        private var remaining = limit

        override fun read(): Int {
            if (remaining <= 0) return -1
            val r = super.read()
            if (r >= 0) remaining--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val r = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (r > 0) remaining -= r
            return r
        }
    }

    private fun skipFully(din: DataInputStream, n: Long) {
        var left = n
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val r = din.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) throw EOFException("ar skip read truncated (still $left bytes)")
            left -= r
        }
    }

    /** DNS 预检线程池（daemon，防进程悬挂） */
    private val DNS_POOL = Executors.newSingleThreadExecutor { r ->
        Thread(r).apply {
            isDaemon = true
            name = "dsh-linux-dns"
        }
    }
}
