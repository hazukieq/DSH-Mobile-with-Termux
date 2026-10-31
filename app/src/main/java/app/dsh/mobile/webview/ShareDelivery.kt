package app.dsh.mobile.webview

import android.app.Activity
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import app.dsh.mobile.R

/**
 * 分享正文投递（Phase 1.7 从 MainActivity 提取，v1.3.44 逻辑原样）。
 *
 * 「文件路径 + 分享文本」由 [ShareHandler] 组装好后，交给这里写进当前会话的输入框
 * （只是预填，不自动发送）。不能只看 JS 返回值：Lexical 的 paste 管线是**异步**的，
 * 且 paste 可能完全无效（语音输入踩过的坑）。所以按语音同样的
 * 「写入 → 读回 → 必要时 execCommand 补写」三步走。
 */
class ShareDelivery(
    private val activity: Activity,
    private val webViewProvider: () -> WebView?,
) {

    /** 其它 App 分享进来的待写入内容（页面未就绪时暂存）。 */
    private var pendingSharePrompt: String? = null
    private var deliverAttempts = 0

    /** 首次投递前先读取现有草稿并合并，避免分享覆盖用户正在写的内容。 */
    private var mergeChecked = false

    /** 分享正文投递（由 [ShareHandler] 在主线程回调）：只预填，不自动发送。 */
    fun deliver(body: String, fileCount: Int = 0) {
        pendingSharePrompt = body
        deliverAttempts = 0
        mergeChecked = false
        Log.i("DshShare", "prompt len=${body.length} files=$fileCount webViewReady=${webViewProvider() != null}")
        webViewProvider()?.postDelayed({ deliverPending() }, 400)
    }

    /**
     * 把待写入的分享内容放进 WebUI 输入框（三步走，不发送，只预填给用户确认）。
     */
    private fun deliverPending() {
        val prompt = pendingSharePrompt ?: return
        val wv = webViewProvider() ?: return
        if (!mergeChecked) {
            mergeChecked = true
            readSendText { existing ->
                if (!existing.isNullOrBlank() && existing != "none" && !existing.contains(prompt.take(16))) {
                    pendingSharePrompt = existing.trimEnd() + "\n\n" + prompt
                }
                deliverPending()
            }
            return
        }
        if (deliverAttempts >= MAX_ATTEMPTS) {
            pendingSharePrompt = null
            val inbox = java.io.File(app.dsh.mobile.engine.EngineConfig.dshHome(activity), "inbox").absolutePath
            Toast.makeText(
                activity,
                activity.getString(R.string.share_no_composer, inbox),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        deliverAttempts++
        Log.i("DshShare", "deliver attempt=$deliverAttempts len=${prompt.length}")
        wv.evaluateJavascript(WebViewJs.buildVoiceInputJs(prompt)) { raw ->
            Log.i("DshShare", "write result=${jsString(raw)}")
            when (jsString(raw)) {
                "entering-session", "no-input-found" ->
                    wv.postDelayed({ deliverPending() }, 900)
                else -> wv.postDelayed({ verifyWrite(prompt) }, 700)
            }
        }
    }

    private fun verifyWrite(prompt: String) {
        val probe = prompt.take(16)
        readSendText { inBox ->
            when {
                inBox.contains(probe) -> {
                    pendingSharePrompt = null
                    Toast.makeText(activity, activity.getString(R.string.share_prefilled), Toast.LENGTH_SHORT).show()
                }
                inBox.isEmpty() || inBox == "none" -> {
                    // paste 没生效 → execCommand 补写，再读一次
                    withWebView { wv ->
                        wv.evaluateJavascript(WebViewJs.buildVoiceRepairJs(prompt)) { _ ->
                            wv.postDelayed({
                                readSendText { after ->
                                    if (after.contains(probe)) {
                                        pendingSharePrompt = null
                                        Toast.makeText(activity, activity.getString(R.string.share_prefilled), Toast.LENGTH_SHORT).show()
                                    } else {
                                        deliverPending()
                                    }
                                }
                            }, 350)
                        }
                    }
                }
                else -> {
                    // 用户已有草稿：追加在末尾，不覆盖
                    withWebView { wv ->
                        wv.evaluateJavascript(WebViewJs.buildVoiceRepairJs("\n\n" + prompt)) { _ ->
                            wv.postDelayed({
                                readSendText { after ->
                                    if (after.contains(probe)) {
                                        pendingSharePrompt = null
                                        Toast.makeText(activity, activity.getString(R.string.share_appended), Toast.LENGTH_SHORT).show()
                                    } else {
                                        deliverPending()
                                    }
                                }
                            }, 350)
                        }
                    }
                }
            }
        }
    }

    private inline fun withWebView(block: (WebView) -> Unit) {
        webViewProvider()?.let(block)
    }

    /**
     * 读输入框里的**实际文字**（v1.2.94）。
     *
     * 与前端同步读值钩子的区别：那个只回答"空不空"，这个给出内容 ——
     * 必须靠它区分「我们刚写进去的那段」和「用户自己打的字」，
     * 否则会把用户的输入覆盖掉、或把我们那段提交两遍（主人实测的「两段」）。
     */
    private fun readSendText(cb: (String) -> Unit) {
        withWebView { wv ->
            wv.evaluateJavascript("window.__dshText ? window.__dshText() : ''") { raw ->
                cb(jsString(raw))
            }
        }
    }

    /**
     * 解析 `evaluateJavascript` 返回的**字符串值**（v1.2.94）。
     *
     * 用 JSON 解析，完整保留反斜杠/引号等原字符（供文本比对使用）。
     */
    private fun jsString(raw: String?): String = runCatching {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "null") ""
        else org.json.JSONTokener(s).nextValue() as? String ?: s
    }.getOrDefault(raw?.trim('"').orEmpty())

    companion object {
        /** 投递重试上限（每次约 1s，超过则提示用户手动看 inbox）。 */
        private const val MAX_ATTEMPTS = 8
    }
}
