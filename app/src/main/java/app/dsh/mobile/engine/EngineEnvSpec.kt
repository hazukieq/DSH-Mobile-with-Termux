package app.dsh.mobile.engine

/**
 * 引擎子进程环境变量的纯计算部分（无 Android 依赖，便于 JVM 单测）。
 *
 * 这里的拼法一旦错位（顺序、分隔符、PREFIX 不一致），bionic 的 node 就找不到
 * 依赖库或包装器，表现为「引擎起不来 / npm 找不到」。所以单独抽出来做回归。
 */
internal object EngineEnvSpec {

    /** PATH 前缀顺序：引擎 bin → dsh-home 本地 bin → pnpm → 系统。 */
    fun pathValue(root: String, home: String): String = listOf(
        "$root/bin",
        "$home/local/bin",
        "$home/.local/share/pnpm",
        "/system/bin",
        "/system/xbin",
    ).joinToString(":")

    /** 引擎私有库目录（bionic .so 都在这里）。 */
    fun ldValue(root: String): String = "$root/lib"

    fun corepackHome(home: String): String = "$home/.cache/corepack"
}
