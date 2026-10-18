package app.dsh.mobile.linux

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * 纯 Java 实现的流式 `.tar.gz` 解包器（不引入任何第三方依赖）。
 *
 * 为什么自己写：rootfs 是 **3~30MB 的 tar.gz**，而项目已有的 tar 解析在
 * 之前的 deb 解包器里是 private、且绑定「Termux deb + usr 拍平」语义。
 * 这里按同一套经过实战验证的规则重写一份，供 Linux 发行版安装复用：
 *  - 支持 ustar / GNU tar（`L` 长文件名、`K` 长链接名）/ PAX（`x`/`g` 扩展头）；
 *  - regular('0'/'\0'/'7')、dir('5')、symlink('2')、hardlink('1')；
 *  - 设备文件/管道（'3'/'4'/'6'）一律**安全跳过**（rootfs 里不应出现，且 App 也创
 *    不了真正的设备节点）；
 *  - 按 tar 头里的 mode 还原 owner rwx 位（不还原则全部 0600/0664，guest 里
 *    /bin/sh、apk、apt 全部失去 +x —— 这正是历史事故的根因）；
 *  - **路径逃逸/绝对路径直接拒绝**：rootfs 是下载来的不可信输入，绝不允许写出
 *    目标目录之外（也不能信任其中的符号链接）。
 *
 * 语义说明（与 GNU tar 对齐）：
 *  - symlink 立即创建，target 原样保留（rootfs 里大量绝对链接与悬空链接是正常的，
 *    例如 Ubuntu 的 `bin -> usr/bin`、`etc/alternatives/awk -> /usr/bin/mawk`）；
 *  - 写文件时先解析**父目录**的符号链接、最后一层保持字面路径：这样「同名条目
 *    覆盖旧链接」与 tar 一致，又不会把内容写到链接目标之外；
 *  - hardlink 的目标若尚未出现则延后到全部条目读完再建（对 tar 顺序做一点兜底）。
 *
 * 线程模型：纯阻塞 IO，调用方负责放到后台线程。
 */
object TarGzExtractor {

    private const val BLOCK = 512
    private const val COPY_BUF = 64 * 1024
    private const val PROGRESS_STEP = 1L shl 20

    /**
     * 单条目大小上限（16GiB）。tar 头的 size 可以是 base-256 的任意 64 位值，
     * 恶意/损坏头传 Long.MAX_VALUE 会让 `padded(size)` 溢出成负数、skip 逻辑静默失步；
     * 真实 rootfs/deb 里没有超过 16GiB 的单文件，直接拒绝更安全。
     */
    private const val MAX_ENTRY_BYTES = 16L shl 30

    /**
     * 解包 [tarGz] 到 [targetDir]（目录会被创建/复用）。
     *
     * @param onProgress (已消费的压缩字节, 压缩文件总字节)；total 恒 > 0，便于 UI 画进度。
     *                   注意这是**压缩流进度**，不是解包条目数。
     * @return 解出的条目数（含目录，便于日志核对）
     * @throws IOException 流损坏/截断；[SecurityException] 路径逃逸
     */
    fun extract(
        tarGz: File,
        targetDir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Int {
        require(tarGz.isFile) { "tar.gz not found: ${tarGz.absolutePath}" }
        val total = tarGz.length().coerceAtLeast(1L)
        targetDir.mkdirs()
        check(targetDir.isDirectory) { "cannot create target dir: ${targetDir.absolutePath}" }
        FileInputStream(tarGz).use { raw ->
            // 计数放在 gzip 之前：进度 = 真实读掉的压缩字节，压缩/解压速度差异不会影响分母。
            val counting = CountingInputStream(raw)
            val gz = GZIPInputStream(BufferedInputStream(counting, COPY_BUF), COPY_BUF)
            // onBytes 给的是**解压后** tar 流字节数；进度必须换算回压缩侧，否则分母
            // （压缩文件大小）与分子（解压后字节）量纲不一致，进度会提前跑满 100%。
            val entries = extractTarStream(gz, targetDir) { onProgress(counting.count, total) }
            // tar 读完后 gzip 尾部尚未被消费，补一次收尾，让 UI 进度确定走到 100%。
            onProgress(total, total)
            return entries
        }
    }

    /**
     * 从**裸 tar 流**解包（供 [DebInstaller] 复用：deb 的 data.tar.xz 已经由 XZ 解成 tar 流）。
     *
     * @param stripPrefix 只解出该前缀之下的条目（如 deb 的
     *        `data/data/com.termux/files/usr/`），前缀之外的条目整体跳过。
     *        前缀必须用 `/` 结尾或不带结尾 `/` 均可。
     * @param onBytes 已消费的 tar 字节数（压缩层由调用方自己计数）
     * @return 条目数
     *
     * 不关闭 [input]（生命周期归调用方）；异常时可能已写入部分文件，调用方必须把
     * 目标目录当临时目录、失败时整个删除（本类不做回滚，保持职责单一）。
     */
    internal fun extractTarStream(
        input: InputStream,
        targetDir: File,
        stripPrefix: String = "",
        onBytes: (Long) -> Unit = {},
    ): Int {
        targetDir.mkdirs()
        val root = targetDir.canonicalFile
        val rootPath = root.path.trimEnd('/')
        val strip = stripPrefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val din = DataInputStream(BufferedInputStream(input, COPY_BUF))
        val header = ByteArray(BLOCK)
        var entries = 0
        var consumed = 0L
        var gnuLongName: String? = null
        var gnuLongLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        // hardlink 目标尚未落地时延后补建（tar 通常先存本体，但顺序不保证）
        val deferredHardlinks = ArrayList<Pair<File, String>>(4)

        while (true) {
            val filled = fillHeader(din, header)
            if (filled == 0) {
                // 流干净结束。tar 规范要求两个全零块收尾，但 rootfs 是 gzip 压缩流：
                // 截断会先被 GZIPInputStream 的 CRC/长度校验抓到并抛 EOFException，
                // 所以这里把「读到来不及一个头就 EOF」视为正常收尾（对 .deb 的调用
                // 同样由 dpkg 产物保障有结束块）。
                break
            }
            if (filled < BLOCK) {
                throw EOFException("tar header truncated ($filled/$BLOCK bytes after $entries entries)")
            }
            consumed += BLOCK
            if (header.allZero()) break

            val size = parseNumeric(header, 124, 12)
            if (size < 0 || size > MAX_ENTRY_BYTES) {
                throw IOException("unreasonable tar entry size: $size (entry #$entries)")
            }
            val mode = parseNumeric(header, 100, 8)
            val type = (header[156].toInt() and 0xFF).toChar()

            // 扩展头：吃掉 body，记录「下一个真实条目」的属性
            when (type) {
                'L' -> { gnuLongName = readBodyString(din, size); consumed += padded(size) }
                'K' -> { gnuLongLink = readBodyString(din, size); consumed += padded(size) }
                'x' -> {
                    val pax = parsePax(readBodyString(din, size))
                    pax.path?.let { paxPath = it }
                    pax.link?.let { paxLink = it }
                    consumed += padded(size)
                }
                'g' -> { skipBody(din, size); consumed += padded(size) }
                else -> {
                    entries++
                    var name = gnuLongName ?: paxPath ?: headerString(header, 0, 100)
                    val link = gnuLongLink ?: paxLink ?: headerString(header, 157, 100)
                    gnuLongName = null; gnuLongLink = null; paxPath = null; paxLink = null
                    // ustar 的 155 字节 prefix 字段：只有名字本身不是绝对路径时才拼接
                    val prefix = headerString(header, 345, 155)
                    if (prefix.isNotEmpty() && !name.startsWith("/")) name = "$prefix/$name"

                    val rel = normalizePath(name, strip)
                    if (rel == null) {
                        // 不在 stripPrefix 之下 / 是根目录本身：安全跳过 body
                        skipBody(din, size)
                        consumed += padded(size)
                    } else {
                        val out = resolveInside(rootPath, rel)
                        when (type) {
                            '5' -> {
                                makeDirectory(out)
                                applyMode(out, mode)
                                skipBody(din, size)
                                consumed += padded(size)
                            }
                            '0', '\u0000', '7' -> {
                                consumed += writeFile(din, out, size, consumed, onBytes)
                                applyMode(out, mode)
                                skipPadAfter(din, size)
                                consumed += padded(size) - size
                            }
                            '2' -> {
                                createSymlink(out, link)
                                skipBody(din, size)
                                consumed += padded(size)
                            }
                            '1' -> {
                                val written = tryCreateHardlink(rootPath, out, link, strip)
                                if (!written) deferredHardlinks += out to link
                                skipBody(din, size)
                                consumed += padded(size)
                            }
                            else -> {
                                // '3' char device / '4' block device / '6' fifo / 其它：
                                // App 没有 mknod 权限，强行创建只会失败 —— 安全跳过。
                                skipBody(din, size)
                                consumed += padded(size)
                            }
                        }
                    }
                }
            }
            onBytes(consumed)
        }

        // 补建延后的硬链接；目标仍不存在就放弃（悬空硬链接比整次安装失败更可接受）
        for ((linkFile, linkTarget) in deferredHardlinks) {
            runCatching { createHardlink(rootPath, linkFile, linkTarget, strip) }
        }
        onBytes(consumed)
        return entries
    }

    // ================= 路径与安全 =================

    /**
     * tar 里的原始名字 -> 相对目标根的安全路径；null = 本条目不落盘。
     *
     * 规则：
     *  - 绝对路径直接拒绝（不可信输入不允许指定写入位置）；
     *  - 循环剥掉前导 `./`（Alpine/Debian 都混用 `./x` 与 `x` 两种写法）；
     *  - 有 stripPrefix 时，前缀之外全部跳过；
     *  - 任何 `..` 段直接拒绝（canonical 检查也会兜住，这里提前给出可诊断的错误）。
     */
    private fun normalizePath(raw: String, strip: String): String? {
        var n = raw
        if (n.startsWith("/")) throw SecurityException("absolute path in tar entry: $raw")
        while (n.startsWith("./")) n = n.substring(2)
        if (n.isEmpty() || n == ".") return null
        if (strip.isNotEmpty()) {
            if (n == strip.trimEnd('/')) return null
            if (!n.startsWith(strip)) return null
            n = n.substring(strip.length)
        }
        n = n.trimEnd('/')
        if (n.isEmpty()) return null
        val parts = n.split('/')
        if (parts.any { it == ".." }) throw SecurityException("tar path escape: $raw")
        return parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }

    /**
     * 解析出目标目录内的字面路径：**父目录走 canonical**（这样 `bin -> usr/bin`
     * 这类链接目录里的文件会落到真实位置，同时父链跑到目标根之外会被拒绝），
     * 最后一层保持字面名（同名条目覆盖旧 symlink 时不会写穿到链接目标）。
     */
    private fun resolveInside(rootPath: String, rel: String): File {
        val slash = rel.lastIndexOf('/')
        val parent = if (slash < 0) File(rootPath) else File(rootPath, rel.substring(0, slash)).canonicalFile
        val out = File(parent, if (slash < 0) rel else rel.substring(slash + 1))
        val p = out.path
        if (p != rootPath && !p.startsWith(rootPath + File.separator)) {
            throw SecurityException("tar path escape: $rel")
        }
        return out
    }

    // ================= 条目落地 =================

    private fun makeDirectory(out: File) {
        if (out.isDirectory) return
        if (out.exists() || Files.isSymbolicLink(out.toPath())) {
            // 文件/链接挡住了目录：覆盖（tar 后出现的条目优先）
            deletePath(out)
        }
        if (!out.mkdirs() && !out.isDirectory) {
            throw IOException("cannot create directory: ${out.absolutePath}")
        }
    }

    /** 普通文件：精确按 size 拷贝，父目录自动创建，覆盖同名文件/链接 */
    private fun writeFile(
        din: DataInputStream,
        out: File,
        size: Long,
        baseConsumed: Long,
        onBytes: (Long) -> Unit,
    ): Long {
        out.parentFile?.mkdirs()
        if (out.isDirectory) deletePath(out)
        if (Files.isSymbolicLink(out.toPath())) deletePath(out)
        var written = 0L
        var lastReport = 0L
        out.outputStream().use { os ->
            val buf = ByteArray(COPY_BUF)
            while (written < size) {
                val want = minOf(size - written, buf.size.toLong()).toInt()
                val n = din.read(buf, 0, want)
                if (n < 0) throw EOFException("tar stream truncated while writing ${out.name} ($written/$size)")
                os.write(buf, 0, n)
                written += n
                if (written - lastReport >= PROGRESS_STEP) {
                    lastReport = written
                    onBytes(baseConsumed + BLOCK + written)
                }
            }
        }
        return written
    }

    private fun createSymlink(out: File, target: String) {
        out.parentFile?.mkdirs()
        if (out.exists() || Files.isSymbolicLink(out.toPath())) deletePath(out)
        // target 原样保留：绝对路径/悬空链接在 rootfs 里都是合法且常见的
        Files.createSymbolicLink(out.toPath(), Paths.get(target))
    }

    /** @return true = 已创建；false = 目标还没出现，交给调用方延后重试 */
    private fun tryCreateHardlink(rootPath: String, out: File, rawTarget: String, strip: String): Boolean {
        return runCatching { createHardlink(rootPath, out, rawTarget, strip) }.getOrDefault(false)
    }

    private fun createHardlink(rootPath: String, out: File, rawTarget: String, strip: String): Boolean {
        val targetRel = normalizePath(rawTarget, strip) ?: return false
        val target = resolveInside(rootPath, targetRel)
        if (!target.isFile) return false
        out.parentFile?.mkdirs()
        if (out.exists() || Files.isSymbolicLink(out.toPath())) deletePath(out)
        Files.createLink(out.toPath(), target.toPath())
        return true
    }

    /** 删除**字面路径**（链接只删链接本身，不跟到目标） */
    private fun deletePath(f: File) {
        if (Files.isSymbolicLink(f.toPath())) {
            Files.deleteIfExists(f.toPath())
        } else if (f.isDirectory) {
            f.deleteRecursively()
        } else {
            Files.deleteIfExists(f.toPath())
        }
    }

    /**
     * 按 tar 头 mode（offset 100，8 字节八进制）还原 owner rwx。
     *
     * 只处理 owner 位：App 沙箱里文件属主恒为自身 uid，group/other 位没有意义
     * （proot -0 的 guest 侧权限检查最终仍落到 bionic 内核态，属主可读写即可）。
     * 特殊位（setuid/sticky）Kotlin 的 File API 表达不了，交给 LinuxEnv 对 /tmp 单独
     * chmod 1777。
     */
    private fun applyMode(f: File, mode: Long) {
        if (mode <= 0) return
        runCatching {
            f.setExecutable((mode and 73L) != 0L, false)   // 0o111 = 73
            f.setReadable((mode and 292L) != 0L, false)    // 0o444 = 292
            f.setWritable((mode and 146L) != 0L, false)    // 0o222 = 146
        }
    }

    // ================= 流小工具 =================

    private fun fillHeader(din: DataInputStream, bh: ByteArray): Int {
        var off = 0
        while (off < BLOCK) {
            val r = din.read(bh, off, BLOCK - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun readBodyString(din: DataInputStream, size: Long): String {
        if (size < 0 || size > 1L shl 20) throw IOException("unreasonable tar extended-header size: $size")
        val buf = ByteArray(size.toInt())
        din.readFully(buf)
        skipPadAfter(din, size)
        // GNU longname 的 body 以 NUL 结尾；不注意会把 \0 带进路径
        return String(buf, StandardCharsets.UTF_8).trimEnd('\u0000')
    }

    private fun parsePax(content: String): Pax {
        var path: String? = null
        var link: String? = null
        // 记录格式 "<len> path=<value>\n"，用第一个空格切掉长度前缀即可
        content.lineSequence().forEach { rec ->
            val sp = rec.indexOf(' ')
            if (sp <= 0) return@forEach
            val kv = rec.substring(sp + 1)
            when {
                kv.startsWith("path=") -> path = kv.substring(5)
                kv.startsWith("linkpath=") -> link = kv.substring(9)
            }
        }
        return Pax(path, link)
    }

    private fun skipBody(din: DataInputStream, size: Long) {
        skipFully(din, size)
        skipPadAfter(din, size)
    }

    /** body 已精确读完时，只跳过其 512 对齐 padding */
    private fun skipPadAfter(din: DataInputStream, size: Long) {
        skipFully(din, padded(size) - size)
    }

    private fun padded(size: Long): Long = (size + BLOCK - 1) / BLOCK * BLOCK

    private fun skipFully(din: DataInputStream, n: Long) {
        var left = n
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val r = din.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) throw EOFException("tar body truncated (still $left bytes to skip)")
            left -= r
        }
    }

    private fun headerString(bh: ByteArray, off: Int, len: Int): String =
        String(bh, off, len, StandardCharsets.UTF_8).substringBefore('\u0000').trim()

    /**
     * tar 数值字段：常规是 ASCII 八进制；GNU 对大数支持 base-256（首字节高位为 1）。
     * rootfs 里文件都小于 8GB，但解析器不该因为一个恶意头就崩掉/读错长度。
     */
    private fun parseNumeric(bh: ByteArray, off: Int, len: Int): Long {
        val first = bh[off].toInt() and 0xFF
        if (first and 0x80 != 0) {
            var v = (first and 0x7F).toLong()
            for (i in 1 until len) v = (v shl 8) or (bh[off + i].toLong() and 0xFF)
            return v
        }
        val s = String(bh, off, len, StandardCharsets.US_ASCII).trim('\u0000', ' ')
        return if (s.isEmpty()) 0L else s.toLongOrNull(8) ?: 0L
    }

    private fun ByteArray.allZero(): Boolean = all { it == 0.toByte() }

    private data class Pax(val path: String?, val link: String?)

    /** 只读计数流（不关闭上游由外层负责） */
    private class CountingInputStream(src: InputStream) : FilterInputStream(src) {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }
    }
}
