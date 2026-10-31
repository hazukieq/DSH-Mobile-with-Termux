package app.dsh.mobile.webview

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/**
 * WebView 文件选择器控制器（Phase 1.7 从 MainActivity 提取）。
 *
 * `<input type=file>` 请求交给系统 SAF 选择器，结果经 `onActivityResult` 回传。
 *
 * ⚠️ 必须调用 onReceiveValue（哪怕是 null）：WebView 在回调前会**挂起页面的文件选择
 * 请求**，不调用则页面永久等待（表现为「点了上传没反应」）。用户取消时传 null 即取消。
 */
class FileChooser(
    private val activity: Activity,
    private val log: (String) -> Unit,
) {

    /** 待回传的文件选择结果（onShowFileChooser → onActivityResult 之间持有；null 表示无进行中请求） */
    private var pending: ValueCallback<Array<Uri>>? = null

    /** 文件选择器请求（<input type=file>）：交给系统 SAF 选择器并回传结果。 */
    fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?,
    ): Boolean {
        callback ?: return false
        // 上一个请求未结束（用户连点/页面切换）→ 先回传空值释放，否则 WebView 卡死
        pending?.onReceiveValue(null)
        pending = callback
        val intent = runCatching { params?.createIntent() }.getOrNull()
            ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        return runCatching {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ_FILE_CHOOSER)
            true
        }.getOrElse {
            log("file chooser failed to launch: ${it.message}")
            pending = null
            callback.onReceiveValue(null)
            false
        }
    }

    /**
     * 结果回传。
     * @return true = 本类已处理（宿主不要再走 super）；false = 不是文件选择请求
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_FILE_CHOOSER) return false
        val cb = pending
        pending = null
        if (cb == null) return true
        val uris: Array<Uri>? = when {
            resultCode != Activity.RESULT_OK -> null
            else -> {
                val fromData = WebChromeClient.FileChooserParams
                    .parseResult(resultCode, data)
                // 部分 ROM 的选择器把结果放在 clipData（多选/分享式选择器），parseResult 拿不到
                if (fromData != null && fromData.isNotEmpty()) fromData
                else data?.clipData?.let { cd -> Array(cd.itemCount) { cd.getItemAt(it).uri } }
                    ?: data?.data?.let { arrayOf(it) }
            }
        }
        runCatching { cb.onReceiveValue(uris) }
            .onFailure { log("file chooser result delivery failed: ${it.message}") }
        return true
    }

    /** Activity 销毁时释放未完成的请求，否则 WebView 侧回调悬空。 */
    fun release() {
        pending?.let { runCatching { it.onReceiveValue(null) } }
        pending = null
    }

    companion object {
        /** WebView 文件选择器的请求码（onShowFileChooser → onActivityResult） */
        private const val REQ_FILE_CHOOSER = 1001
    }
}
