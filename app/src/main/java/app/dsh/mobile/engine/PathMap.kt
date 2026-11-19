package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * LD_PRELOAD 路径映射层（2026-11-20）。
 *
 * ## 为什么需要
 * Android 的 `/tmp` 属 shell:shell、mode 0771：App 进程只能进入不能写，
 * agent 每次写 `/tmp` 下的日志都 EACCES 后换路径重试。`/var/tmp`、`/dev/shm`、
 * `/run` 在多数设备上不存在。`dsh_pathmap.c` 拦截 libc 路径入口，把这几类
 * 前缀改写到 `$DSH_TMPDIR`，让「写 /tmp」在 App 内真实可用。
 *
 * ## 为什么从 APK 里解出来
 * 本 App `extractNativeLibs=false`：`.so` 只存在于 base.apk 的 `lib/<abi>/` 里，
 * 而 `LD_PRELOAD` 需要**文件系统路径**（linker 不会从 zip 预载）。所以启动时
 * 把对应条目解到 `$PREFIX/lib/`（与 Termux 自己的 lib 同目录，linker 允许）。
 *
 * ## 失败策略
 * 任何一步失败都返回 null —— 调用方不注入 `LD_PRELOAD`，行为退回「/tmp 不可写」，
 * 不影响 App 启动；日志留一行原因。
 */
internal object PathMap {

    private const val TAG = "PathMap"
    private const val ABI = "arm64-v8a"
    private const val ENTRY = "lib/$ABI/libdshpathmap.so"
    private const val FILE_NAME = "libdsh-pathmap.so"

    @Volatile
    private var cached: String? = null

    /** 返回可用的 .so 绝对路径；已缓存，重复调用几乎零成本。 */
    fun ensure(ctx: Context): String? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val path = runCatching { extract(ctx) }
                .onFailure { Log.w(TAG, "pathmap unavailable: ${it.message}") }
                .getOrNull()
            cached = path
            return path
        }
    }

    private fun extract(ctx: Context): String? {
        val out = File(EngineConfig.engineRoot(ctx), "lib/$FILE_NAME")
        val apk = File(ctx.applicationInfo.sourceDir)
        if (!apk.isFile) return null

        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(ENTRY) ?: run {
                Log.w(TAG, "APK has no $ENTRY (abi=${ctx.applicationInfo.nativeLibraryDir})")
                return null
            }
            // 已解出且大小一致（同一次安装）→ 直接复用，避免每次启动写盘
            if (out.isFile && out.length() == entry.size) {
                out.setExecutable(true, false)
                return out.absolutePath
            }
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, "$FILE_NAME.tmp")
            zip.getInputStream(entry).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            // 先写临时文件再 rename：中途杀掉进程不会留下半截 .so 被 linker 预载
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            out.setReadable(true, false)
            out.setExecutable(true, false)
            Log.i(TAG, "pathmap extracted: ${out.absolutePath} (${out.length()} bytes)")
            return out.absolutePath
        }
    }
}
