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

    fun open(ctx: Context, path: String, kind: String = "file", mimeOverride: String? = null): JSONObject {
        val file = File(path)
        if (!file.exists()) {
            return JSONObject().put("ok", false).put("error", "not found: $path")
        }
        val mime = mimeOverride?.takeIf { it.isNotBlank() } ?: mimeOf(file)
        val (action, uri, type) = when (kind) {
            "folder" -> {
                val doc = storageDocumentUri(file.absolutePath)
                if (doc != null) Triple(Intent.ACTION_VIEW, doc, "vnd.android.document/directory")
                else Triple(Intent.ACTION_VIEW, providerUri(ctx, file), "vnd.android.document/directory")
            }
            "share" -> Triple(Intent.ACTION_SEND, providerUri(ctx, file), mime)
            else -> Triple(Intent.ACTION_VIEW, providerUri(ctx, file), mime)
        }
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (kind == "share") {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, file.name)
            } else {
                setDataAndType(uri, type)
            }
            if (kind == "file" || kind == "folder") {
                @Suppress("DEPRECATION")
                clipData = android.content.ClipData.newUri(ctx.contentResolver, file.name, uri)
            }
        }
        val target = ctx.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val before = AppState.resumedActivityName()
        val started = System.currentTimeMillis()
        return runCatching {
            ctx.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // 是否真的离开本 App：离开/切走说明系统界面已接管；本 App 仍在 → 可能被 BAL 拦下
            val deadline = System.currentTimeMillis() + 900
            var left = false
            while (System.currentTimeMillis() < deadline) {
                if (AppState.resumedActivityName() == null) { left = true; break }
                Thread.sleep(50)
            }
            JSONObject()
                .put("ok", true)
                .put("opened", left)
                .put("kind", kind)
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
            JSONObject().put("ok", false).put("error", "no app can open this: ${it.message}")
        }
    }
}
