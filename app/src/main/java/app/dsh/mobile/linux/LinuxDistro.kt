package app.dsh.mobile.linux

import android.os.Build

/**
 * 可安装的 Linux 发行版（proot rootfs）目录项。
 *
 * 设计要点：
 *  - **纯数据**：UI（LinuxEnvActivity）只读 id/name/version/shell/pkgManager/sizeMb；
 *    下载器读 urls/sha256。不依赖 Context，也不做任何 IO。
 *  - **按 ABI 分别记录**：Alpine 与 Ubuntu 对 arm64 的命名不同（aarch64 / arm64），
 *    所以 URL 与哈希都按 app 侧 ABI key（arm64-v8a / x86_64）建索引，避免调用方
 *    自己拼 arch 字符串（拼错就是 404）。
 *  - **每个 (发行版, ABI) 都有一个真实 SHA-256**：rootfs 是**可执行代码的集合**
 *    （rootfs 里的 /bin/sh、apt、apk 全会被 proot 执行），只校验 HTTPS 不够 ——
 *    镜像站被劫持/缓存了错误文件时，我们必须在解包前拒绝。
 *
 * 哈希来源（2026-10-18 实测，TODO 换版本时必须重算）：
 *  - 四个 tar.gz 均从下面的**阿里云主镜像**实际下载后 `sha256sum` 计算；
 *  - 并与官方清单逐字节对上：
 *    Alpine: https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/<arch>/ 下的 .sha256
 *    Ubuntu: https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS
 *    因此主镜像与官方 CDN 的内容一致；若将来官方与镜像短暂不同步，**以官方清单为准**
 *    （官方升级补丁版本时请同步替换 URL 里的 3.20.3 / 24.04.3）。
 */
data class LinuxDistro(
    /** 稳定的机器 id（进目录名与 DSH_LINUX_DEFAULT，只用小写字母/数字/连字符） */
    val id: String,
    /** 展示名（ASCII，UI 标题用） */
    val name: String,
    /** 发行版版本号（如 3.20.3 / 24.04.3） */
    val version: String,
    /** 客户机内登录 shell 的绝对路径（proot 里执行） */
    val shell: String,
    /** 包管理器可执行名（apk / apt），UI 副标题展示用 */
    val pkgManager: String,
    /** 压缩包的大致下载体积（MB，UI 提示用） */
    val sizeMb: Int,
    /** app ABI key -> rootfs tar.gz 的 SHA-256（小写 hex） */
    val sha256: Map<String, String>,
    /** app ABI key -> rootfs 下载 URL 列表（主镜像在前，失败逐级回落） */
    val urls: Map<String, List<String>>,
    /**
     * app ABI key -> 该 ABI 上的已知问题（UI 会以警告色显示，空 = 无问题）。
     *
     * 目前只有一条：x86_64 上 Alpine 不可用（见 ALPINE 定义处注释）。
     */
    val abiNote: Map<String, String> = emptyMap(),
) {
    /** 该 ABI 的候选下载地址（空 = 本设备 ABI 不被支持） */
    fun urlFor(abi: String): List<String> = urls[abi].orEmpty()

    /** 该 ABI 的期望哈希（空 = 目录未登记，调用方应拒绝安装） */
    fun sha256For(abi: String): String = sha256[abi].orEmpty()
}

/**
 * 发行版目录（唯一可信源）。
 *
 * 只收录两个**最小可用**的 rootfs：Alpine（4MB，ash/busybox）与 Ubuntu Base
 * （30MB，glibc/bash/apt）。刻意不收录 Debian/Fedora 等：手机存储与下载失败率
 * 都不适合「一次装一堆」，需要别的发行版时按本文件的形状加一条即可。
 */
object LinuxDistros {

    /** app 侧 ABI key：与 Build.SUPPORTED_ABIS 对齐 */
    const val ABI_ARM64 = "arm64-v8a"
    const val ABI_X86_64 = "x86_64"

    /** 阿里云 Alpine 镜像（v3.20 系列） */
    private const val ALPINE_MIRROR = "https://mirrors.aliyun.com/alpine"
    /** Alpine 官方 CDN 兜底 */
    private const val ALPINE_CDN = "https://dl-cdn.alpinelinux.org/alpine"
    private const val ALPINE_VERSION = "3.20.3"

    /** 阿里云 Ubuntu CD 镜像（ubuntu-base 官方产物的镜像） */
    private const val UBUNTU_MIRROR = "https://mirrors.aliyun.com/ubuntu-cdimage"
    /** Ubuntu 官方 cdimage 兜底 */
    private const val UBUNTU_CDN = "https://cdimage.ubuntu.com"
    private const val UBUNTU_VERSION = "24.04.3"

    /**
     * Alpine Linux 3.20.3 minirootfs。
     *
     * 官方命名 aarch64 / x86_64（注意 x86_64 与 app 的 ABI key 同名，但不是必然）。
     * 哈希（实测，官方 .sha256 与阿里云下载一致）：
     *   aarch64: 041fa34a81788242df9e78fa69b97ab45b8ec47ddbf88864755610414a7bf3de
     *   x86_64 : d4e6fd67dcf75e40c451560ac7265166c2b72a0f38ddc9aae756a7de3d1efa0c
     */
    val ALPINE: LinuxDistro = run {
        fun url(base: String, arch: String) =
            "$base/v3.20/releases/$arch/alpine-minirootfs-$ALPINE_VERSION-$arch.tar.gz"
        LinuxDistro(
            id = "alpine",
            name = "Alpine Linux",
            version = ALPINE_VERSION,
            shell = "/bin/sh",
            pkgManager = "apk",
            sizeMb = 4,
            sha256 = mapOf(
                ABI_ARM64 to "041fa34a81788242df9e78fa69b97ab45b8ec47ddbf88864755610414a7bf3de",
                ABI_X86_64 to "d4e6fd67dcf75e40c451560ac7265166c2b72a0f38ddc9aae756a7de3d1efa0c",
            ),
            urls = mapOf(
                ABI_ARM64 to listOf(url(ALPINE_MIRROR, "aarch64"), url(ALPINE_CDN, "aarch64")),
                ABI_X86_64 to listOf(url(ALPINE_MIRROR, "x86_64"), url(ALPINE_CDN, "x86_64")),
            ),
            // 2026-10-18 模拟器实测（Android 14 x86_64）：Alpine 能起 shell，但
            // 任何外部命令都报 "can't fork: Function not implemented" —— musl 在
            // x86_64 上用 SYS_fork 实现 fork()，而 Android 的 app seccomp 策略把
            // fork(2) 判为 SIGSYS 拦截（proot 日志：seccomp SIGSYS: fork(...)）。
            // arm64 上根本没有 fork 系统调用（musl 一律走 clone），所以真机不受影响。
            // x86_64 用 glibc 的 Ubuntu Base 正常（glibc 走 clone）。
            abiNote = mapOf(
                ABI_X86_64 to "Emulator x86_64: Alpine's musl uses the fork() syscall, " +
                    "which Android blocks for apps — install Ubuntu Base instead. " +
                    "arm64 phones are unaffected.",
            ),
        )
    }

    /**
     * Ubuntu Base 24.04.3（noble）。
     *
     * 官方命名 arm64 / amd64（与 app 的 ABI key 不同，必须映射）。
     * 哈希（实测，官方 SHA256SUMS 与阿里云下载一致）：
     *   arm64: 7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048
     *   amd64: 6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9
     */
    val UBUNTU: LinuxDistro = run {
        fun url(base: String, arch: String) =
            "$base/ubuntu-base/releases/24.04/release/ubuntu-base-$UBUNTU_VERSION-base-$arch.tar.gz"
        LinuxDistro(
            id = "ubuntu",
            name = "Ubuntu Base",
            version = UBUNTU_VERSION,
            shell = "/bin/bash",
            pkgManager = "apt",
            sizeMb = 30,
            sha256 = mapOf(
                ABI_ARM64 to "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048",
                ABI_X86_64 to "6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9",
            ),
            urls = mapOf(
                ABI_ARM64 to listOf(url(UBUNTU_MIRROR, "arm64"), url(UBUNTU_CDN, "arm64")),
                ABI_X86_64 to listOf(url(UBUNTU_MIRROR, "amd64"), url(UBUNTU_CDN, "amd64")),
            ),
        )
    }

    /** 展示顺序即 UI 顺序：小的在前，先装成功率高 */
    fun all(): List<LinuxDistro> = listOf(ALPINE, UBUNTU)

    fun byId(id: String): LinuxDistro? = all().firstOrNull { it.id == id }

    /**
     * 设备 ABI -> 目录里使用的 ABI key。
     *
     * 取 SUPPORTED_ABIS 中第一个我们支持的（真机通常 arm64-v8a 优先；模拟器 x86_64）；
     * 都不支持时返回首选 ABI（让上层拿到空 URL 列表并给出明确错误，而不是猜一个
     * 装不上的架构）。32 位设备（armeabi-v7a / x86）不在支持范围内。
     */
    fun deviceAbi(): String {
        val supported = Build.SUPPORTED_ABIS
        return supported.firstOrNull { it == ABI_ARM64 || it == ABI_X86_64 }
            ?: supported.firstOrNull()
            ?: ABI_ARM64
    }
}
