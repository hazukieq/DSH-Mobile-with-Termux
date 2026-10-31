package app.dsh.mobile.webview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import app.dsh.mobile.R
import app.dsh.mobile.engine.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分享内容接收器（Phase 1.2 从 MainActivity 提取）。
 *
 * 职责：把其它 App 分享过来的文件复制进 `$DSH_HOME/inbox/`，并把
 * 「文件路径 + 分享文本」组装成正文交给调用方去预填输入框。
 *
 * 刻意**不持有 WebView**：投递由调用方通过 [deliverBody] 完成，保持解耦；
 * 也因此本类可以单独测试（只需一个 Context）。
 *
 * 安全边界（v2.5.64 H1）：MainActivity 的 ACTION_SEND 是 exported=true，
 * 任何第三方 App 都能塞任意 Uri 过来，所以复制必须在 IO 线程做，
 * 并有单项 / 单次总量 / 文件数量三重上限。
 */
class ShareHandler(
    private val ctx: Context,
    private val uiScope: CoroutineScope,
    /** 正文投递回调（主线程调用）。第二个参数是成功落盘的文件数（仅用于日志）。 */
    private val deliverBody: (body: String, fileCount: Int) -> Unit,
) {

    companion object {
        private const val TAG = "DshShare"
        private const val MAX_SHARE_FILES = 20
        private const val MAX_SHARE_FILE_BYTES = 512L * 1024 * 1024      // 单文件 512 MB
        private const val MAX_SHARE_TOTAL_BYTES = 1024L * 1024 * 1024    // 单次合计 1 GB
    }

    /**
     * 接收 ACTION_SEND / ACTION_SEND_MULTIPLE。
     *
     * 文件先复制进 inbox（引擎与终端可直接读的稳定路径），随后把「路径 + 文本」
     * 交给 [deliverBody] 预填输入框（只预填，不自动发送）。
     */
    fun handle(intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "intent action=$action type=${intent.type}")
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        var rawText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (rawText.isNullOrBlank()) rawText = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val uris = mutableListOf<Uri>()
        intent.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) cd.getItemAt(i)?.uri?.let { uris.add(it) }
        }
        @Suppress("DEPRECATION")
        (intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uris.add(it) }

        // v2.5.64（安全评估 H1）：文件复制绝不能跑在主线程上 —— 旧实现在
        // onCreate/onNewIntent 里直接 copyTo，分享大文件时主线程卡住 5s 就是 ANR。
        if (uris.isEmpty()) {
            deliver(rawText, emptyList())
            return
        }
        uiScope.launch {
            val saved = withContext(Dispatchers.IO) { copySharedUris(uris) }
            deliver(rawText, saved)
        }
    }

    /** 分享文件落盘（只允许在 IO 线程调用）：返回成功保存的绝对路径。 */
    private fun copySharedUris(uris: List<Uri>): List<String> {
        val inbox = java.io.File(EngineConfig.dshHome(ctx), "inbox").apply { mkdirs() }
        val saved = mutableListOf<String>()
        var total = 0L
        var skipped = 0
        uris.distinct().take(MAX_SHARE_FILES).forEach { uri ->
            runCatching {
                val declared = sizeOf(uri) ?: -1L
                if (declared > MAX_SHARE_FILE_BYTES || total + declared > MAX_SHARE_TOTAL_BYTES) {
                    skipped++
                    return@forEach
                }
                val name = (displayNameOf(uri) ?: "shared-${System.currentTimeMillis()}").replace('/', '_')
                val dst = java.io.File(inbox, "${System.currentTimeMillis()}-$name")
                var written = 0L
                var overflow = false
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            written += n
                            // 大小未知的 Uri 也要在写入过程中兜住上限
                            if (written > MAX_SHARE_FILE_BYTES || total + written > MAX_SHARE_TOTAL_BYTES) {
                                overflow = true
                                break
                            }
                            output.write(buf, 0, n)
                        }
                    }
                }
                if (overflow) { dst.delete(); skipped++; return@forEach }
                total += written
                if (dst.isFile && dst.length() > 0) saved.add(dst.absolutePath) else dst.delete()
            }.onFailure { Log.w(TAG, "share copy failed: ${it.message}") }
        }
        if (skipped > 0) Log.w(TAG, "skipped $skipped oversize share item(s)")
        return saved
    }

    /** 组装正文并回调（主线程）。空正文时提示而不是静默丢弃。 */
    private fun deliver(rawText: String?, saved: List<String>) {
        val body = buildString {
            if (saved.isNotEmpty()) {
                append(ctx.getString(R.string.share_files_header)).append('\n')
                saved.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            if (!rawText.isNullOrBlank()) append(rawText.trim())
        }.trim()
        if (body.isBlank()) {
            Toast.makeText(ctx, ctx.getString(R.string.share_empty), Toast.LENGTH_SHORT).show()
            return
        }
        deliverBody(body, saved.size)
    }

    /** 读取 content Uri 的声明大小（拿不到返回 null）。 */
    private fun sizeOf(uri: Uri): Long? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun displayNameOf(uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
