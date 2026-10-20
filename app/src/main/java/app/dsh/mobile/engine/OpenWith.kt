package app.dsh.mobile.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File

/**
 * 用系统其它 App 打开文件/文件夹/分享（v1.3.41）。
 *
 * 引擎跑在 Android 上，桌面版的「xdg-open」不存在；WebUI 的「open in app」在 Android
 * 会显示「此主机没有可用的桌面」。这里统一提供：
 *   - 文件：ACTION_VIEW + 本 App 的 DshFileProvider content:// + 临时读授权；
 *   - 文件夹：/sdcard 用 ExternalStorageProvider 的 document URI（系统文件 App 能处理），
 *     其它目录退回 content:// + vnd.android.document/directory；
 *   - 分享：ACTION_SEND + EXTRA_STREAM。
 * 同时按「本 App 是否离开前台」判断是否真的交出去了（与 /activity 的 BAL 语义一致）。
 */
object OpenWith {

    private fun mimeOf(file: File): String {
        if (file.isDirectory) return "vnd.android.document/directory"
        val ext = file.extension.lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun providerUri(ctx: Context, file: File): Uri =
        Uri.parse("content://" + DshFileProvider.authority(ctx.packageName) + "/" + Uri.encode(file.absolutePath))

    /** /sdcard、/storage/emulated/0 下的目录 → 系统文件 App 的 document URI。 */
    private fun storageDocumentUri(path: String): Uri? {
        val prefixes = listOf("/sdcard/", "/storage/emulated/0/", "/storage/self/primary/")
        val rel = prefixes.firstOrNull { path.startsWith(it) }?.let { path.removePrefix(it) } ?: return null
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
    }

    /**
     * 分享文本/链接（v1.3.44）：ACTION_SEND + text/plain + chooser。
     * 用途：agent 把链接、摘要、生成内容“转发”给微信/QQ/邮件等其它 App。
     */
    fun shareText(ctx: Context, text: String, subject: String? = null): JSONObject {
        android.util.Log.i("OpenWith", "shareText chars=${text.length}")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        return launchChooser(ctx, intent, "text")
    }

    /** 分享文件/文件夹（ACTION_SEND + EXTRA_STREAM），与 open 的 share 分支共用实现。 */
    private fun launchChooser(ctx: Context, intent: Intent, kind: String): JSONObject {
        val before = AppState.resumedActivityName()
        return runCatching {
            val chooser = Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
            val deadline = System.currentTimeMillis() + 900
            var left = false
            while (System.currentTimeMillis() < deadline) {
                if (AppState.resumedActivityName() == null) { left = true; break }
                Thread.sleep(50)
            }
            android.util.Log.i("OpenWith", "share $kind launched leftApp=$left")
            JSONObject()
                .put("ok", true)
                .put("opened", left)
                .put("kind", kind)
                .put("handoff", true)
                .put("verify", "none")
                .put("hint", if (left) "Share sheet opened. Hand-off complete." else "Share sheet did not come up (BAL or no handler); foreground the app and retry.")
        }.getOrElse {
            android.util.Log.w("OpenWith", "share failed: ${it.message}")
            JSONObject().put("ok", false).put("error", "share failed: ${it.message}")
        }
    }

    fun open(ctx: Context, path: String, kind: String = "file", mimeOverride: String? = null): JSONObject {
        android.util.Log.i("OpenWith", "request kind=$kind path=$path")
        val file = File(path)
        if (!file.exists()) {
            return JSONObject().put("ok", false).put("error", "not found: $path")
        }
        // 调用方传 file 但实际是目录（引擎预览/文件夹按钮）时，按文件夹处理
        val effectiveKind = if (kind == "file" && file.isDirectory) "folder" else kind
        val mime = mimeOverride?.takeIf { it.isNotBlank() } ?: mimeOf(file)
        // /sdcard 下的目录优先交给系统的 ExternalStorageProvider（DocumentsUI 能直接显示）。
        // ⚠️ 这个 provider 的文档 URI 不归我们所有：带上 GRANT/ClipData 会让系统尝试
        // 把读权限授予目标 App，而本 App 本身没有该 URI 权限（即使有 MANAGE_EXTERNAL_STORAGE
        // 也不行）→ SecurityException「you could obtain access using ACTION_OPEN_DOCUMENT」。
        // 所以：系统文档 URI 走「不带 grant」的纯 VIEW；我们自己的 provider URI 才带 grant。
        val externalDoc = if (effectiveKind == "folder") storageDocumentUri(file.absolutePath) else null
        val uri = externalDoc ?: providerUri(ctx, file)
        val action = if (effectiveKind == "share") Intent.ACTION_SEND else Intent.ACTION_VIEW
        val type = when (effectiveKind) {
            "folder" -> "vnd.android.document/directory"
            else -> mime
        }
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (externalDoc == null) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (effectiveKind == "share") {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, file.name)
            } else {
                setDataAndType(uri, type)
            }
            if (externalDoc == null) {
                clipData = android.content.ClipData.newRawUri(file.name, uri)
            }
        }
        val target = ctx.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        android.util.Log.i("OpenWith", "intent action=$action uri=$uri type=$type resolved=${target?.activityInfo?.packageName}")
        val before = AppState.resumedActivityName()
        val started = System.currentTimeMillis()
        return runCatching {
            val chooser = Intent.createChooser(intent, null).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            ctx.startActivity(chooser)
            // 是否真的离开本 App：离开/切走说明系统界面已接管；本 App 仍在 → 可能被 BAL 拦下
            val deadline = System.currentTimeMillis() + 900
            var left = false
            while (System.currentTimeMillis() < deadline) {
                if (AppState.resumedActivityName() == null) { left = true; break }
                Thread.sleep(50)
            }
            android.util.Log.i("OpenWith", "started kind=$effectiveKind uri=$uri leftApp=$left")
            JSONObject()
                .put("ok", true)
                .put("opened", left)
                .put("kind", effectiveKind)
                .put("action", action)
                .put("uri", uri.toString())
                .put("mime", type)
                .put("resolved_package", target?.activityInfo?.packageName ?: JSONObject.NULL)
                .put("handoff", true)
                .put("verify", "none")
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put(
                    "hint",
                    if (left) "Opened in another app. Hand-off complete: no verification needed."
                    else "startActivity returned but the app stayed in front; the system chooser may have been blocked (BAL) - foreground the app and retry, or ask the user.",
                )
        }.getOrElse {
            android.util.Log.w("OpenWith", "failed: ${it.message}")
            JSONObject().put("ok", false).put("error", "no app can open this: ${it.message}")
        }
    }
}
