package app.dsh.mobile.webview

/**
 * 分享落盘的纯逻辑（无 Android 依赖，便于 JVM 单测）。
 *
 * 抽出来的原因：上限判断与文件名清洗是最容易出错、也最需要回归的部分
 * （历史上曾因无上限导致大文件分享把主线程卡死并灌满磁盘）。
 */
internal object ShareLogic {
    const val MAX_SHARE_FILES = 20
    const val MAX_SHARE_FILE_BYTES = 512L * 1024 * 1024      // 单文件 512 MB
    const val MAX_SHARE_TOTAL_BYTES = 1024L * 1024 * 1024    // 单次合计 1 GB

    /** 文件名清洗：路径分隔符换成下划线，空名给兜底名。 */
    fun sanitizeName(raw: String?, fallback: String): String {
        val name = raw?.trim().orEmpty()
        val base = if (name.isEmpty()) fallback else name
        return base.replace('/', '_').replace('\\', '_')
    }

    /**
     * 单个文件是否超限。
     * @param declared content Uri 声明的大小，-1 表示未知
     */
    fun fileDeclaredOverflow(declared: Long, total: Long): Boolean =
        declared >= 0 && (declared > MAX_SHARE_FILE_BYTES || total + declared > MAX_SHARE_TOTAL_BYTES)

    /** 写入过程中（大小未知的 Uri）是否该中断。 */
    fun streamOverflow(written: Long, total: Long): Boolean =
        written > MAX_SHARE_FILE_BYTES || total + written > MAX_SHARE_TOTAL_BYTES
}
