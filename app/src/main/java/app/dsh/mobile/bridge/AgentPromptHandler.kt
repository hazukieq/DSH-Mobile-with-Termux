package app.dsh.mobile.bridge

import android.content.Context
import android.content.Intent
import app.dsh.mobile.DshAccessibilityService
import app.dsh.mobile.engine.AgentBridge
import app.dsh.mobile.engine.AppState
import app.dsh.mobile.webview.WebViewHolder
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `/agent/prompt`：把一段 prompt 投进**本机 App 自己的 WebUI 会话**，交给手机里的 agent 代跑。
 *
 * 关键前提是「等就绪再动手」：App 冷启动要等 Activity resumed + WebView 页面渲染出 composer，
 * 否则任何注入都会落空（早前多次失败都是没等就绪）。
 *
 * 注入顺序：
 *  ① 无障碍 `ACTION_SET_TEXT`（`DshAccessibilityService.inputText`，已支持多窗口查找）；
 *  ② 回退：页面内 `execCommand('insertText')`；
 * 发送：无障碍按「发送消息」点按钮，失败再退回页面内 Enter。
 */
internal class AgentPromptHandler(private val bridge: AgentBridge) {

    private val sendLabel = "\u53d1\u9001\u6d88\u606f"

    fun prompt(ctx: Context, body: String): Pair<Int, String> {
        val text = runCatching { JSONObject(body).optString("text") }.getOrDefault("")
        if (text.isBlank()) return 400 to """{"ok":false,"error":"missing text"}"""

        runCatching {
            ctx.startActivity(Intent().apply {
                setClassName(ctx.packageName, "app.dsh.mobile.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }

        // ① 等 Activity 到前台
        if (!waitUntil(8000) { AppState.currentActivity() != null }) {
            return 503 to """{"ok":false,"error":"app did not come to foreground"}"""
        }
        // ② 等 WebView 就绪 + composer 渲染出来
        val webView = waitForWebView(8000)
            ?: return 503 to """{"ok":false,"error":"webview not ready"}"""
        if (!waitUntil(12000) { composerExists(webView) }) {
            return 503 to """{"ok":false,"error":"composer not rendered (ui still loading)"}"""
        }

        // ③ 写文本：先点一下 composer 让它拿到焦点（无焦点时 SET_TEXT/insertText 都会落空），再逐级尝试写入
        val dm = ctx.resources.displayMetrics
        repeat(2) { attempt ->
            runCatching { DshAccessibilityService.instance?.tapAtExact(dm.widthPixels / 2f, dm.heightPixels * 0.92f) }
            Thread.sleep(if (attempt == 0) 500 else 700)
            runCatching { DshAccessibilityService.instance?.let { it.inputText(text, false, null) } }
            if (waitUntil(1500) { composerLength(webView) >= text.take(6).length }) return@repeat
            injectViaJs(webView, text)
        }
        var wrote = false
        // 不要求逐字匹配（不同输入路径 innerText 可能带换行/占位符差异），只要 composer 里有了内容就继续
        val hasText = waitUntil(4000) { composerLength(webView) >= text.take(8).length }
        if (!hasText) {
            return 500 to """{"ok":false,"error":"cannot write into composer","jswrite":$wrote,"len":${composerLength(webView)}}"""
        }

        // ④ 发送：先聚焦再按 Enter（Enter 只有在 composer 有焦点时才是「发送」）
        evalString(webView, "(function(){var e=document.querySelector('[contenteditable=\"true\"]');if(e)e.focus();return 'f'})()")
        Thread.sleep(150)
        var sent = clickSendByLabel(webView)
        runCatching { DshAccessibilityService.instance?.let { sent = it.tapText(sendLabel) } }
        if (!sent) sent = pressEnter(webView)
        // 判据：composer 被清空，或内容明显变短（WebUI 发送后有时会保留一份草稿状态，二者都算已发送）
        val before = composerLength(webView)
        val left = if (waitUntil(6000) { composerIsEmpty(webView) }) 0 else composerLength(webView)
        if (left > 0 && left >= before) {
            return 500 to """{"ok":false,"error":"written but not sent","left":$left,"before":$before}"""
        }
        return 200 to """{"ok":true,"result":"sent"}"""
    }

    private fun waitForWebView(timeoutMs: Long): android.webkit.WebView? {
        waitUntil(timeoutMs) { WebViewHolder.current() != null }
        return WebViewHolder.current()
    }

    private fun composerExists(view: android.webkit.WebView): Boolean =
        !evalString(view, "(function(){return document.querySelector('[contenteditable=\"true\"]')?'yes':'no'})()").isNullOrBlank()
            && evalString(view, "(function(){return document.querySelector('[contenteditable=\"true\"]')?'yes':'no'})()")!!.contains("yes")

    private fun injectViaJs(view: android.webkit.WebView, text: String): Boolean {
        val js = "(function(){var e=document.querySelector('[contenteditable=\"true\"]');if(!e)return 0;" +
            "e.focus();e.innerText=" + JSONObject.quote(text) + ";" +
            "e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:" +
            JSONObject.quote(text) + "}));return 1})()"
        return evalString(view, js)?.contains("1") == true
    }

    /** 在页面里找 aria-label 含「发送」的元素并 click（WebUI 的发送键不一定是 <button>）。 */
    private fun clickSendByLabel(view: android.webkit.WebView): Boolean {
        val js = "(function(){var els=Array.prototype.slice.call(document.querySelectorAll('[aria-label]'));" +
            "var b=els.filter(function(e){return (e.getAttribute('aria-label')||'').indexOf('" + sendLabel + "')>=0})[0];" +
            "if(!b)return 'none';b.click();return 'clicked'})()"
        return evalString(view, js)?.contains("clicked") == true
    }

    private fun pressEnter(view: android.webkit.WebView): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        view.post {
            runCatching {
                ok.set(
                    view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)) &&
                        view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
                )
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return ok.get()
    }

    private fun composerLength(view: android.webkit.WebView): Int {
        val got = evalString(view,
            "(function(){var e=document.querySelector('[contenteditable=\"true\"]');return e?String((e.innerText||'').trim().length):'0'})()")
        return got?.trim('"')?.toIntOrNull() ?: 0
    }

    private fun composerTextContains(view: android.webkit.WebView, text: String): Boolean {
        val got = evalString(view,
            "(function(){var e=document.querySelector('[contenteditable=\"true\"]');return e?(e.innerText||''):''})()")
            ?: return false
        return got.replace("\\n", "\n").trim().contains(text.take(16))
    }

    private fun composerIsEmpty(view: android.webkit.WebView): Boolean {
        val got = evalString(view,
            "(function(){var e=document.querySelector('[contenteditable=\"true\"]');return e?((e.innerText||'').trim()):'?'})()")
            ?: return false
        return got.trim() == "" || got.trim() == "\\n"
    }

    /** 在主线程跑一段 JS 并返回结果（失败返回 null）。 */
    private fun evalString(view: android.webkit.WebView, js: String): String? {
        val result = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val posted = view.post {
            runCatching { view.evaluateJavascript(js) { raw -> result.set(raw) } }
            latch.countDown()
        }
        if (!posted) return null
        latch.await(2500, TimeUnit.MILLISECONDS)
        // evaluateJavascript 的回调是异步的，再给一点时间
        if (result.get() == null) Thread.sleep(120)
        return result.get()
    }

    private fun waitUntil(timeoutMs: Long, block: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { block() }.getOrDefault(false)) return true
            Thread.sleep(150)
        }
        return false
    }
}
