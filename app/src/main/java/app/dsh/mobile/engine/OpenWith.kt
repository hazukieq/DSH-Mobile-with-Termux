package app.dsh.mobile.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
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
     * 系统「文件」App（DocumentsUI）能不能显式打开某个目录。
     *
     * 用户要求「打开文件/显示位置 = 打开文件管理器去定位即可」，所以优先显式指定
     * DocumentsUI 的 FilesActivity（避免弹「用哪个应用打开」选择器）；机型没有
     * DocumentsUI 时再退回不带 component 的 VIEW。
     */
    private fun documentsUiComponent(ctx: Context): android.content.ComponentName? {
        val candidates = listOf(
            "com.android.documentsui" to "com.android.documentsui.files.FilesActivity",
            "com.google.android.documentsui" to "com.google.android.documentsui.files.FilesActivity",
        )
        for ((pkg, cls) in candidates) {
            val cn = android.content.ComponentName(pkg, cls)
            val ok = runCatching { ctx.packageManager.getActivityInfo(cn, 0) }.isSuccess
            if (ok) return cn
        }
        return null
    }

    /**
     * 分享文本/链接（v1.3.44）：ACTION_SEND + text/plain + chooser。
     * 用途：agent 把链接、摘要、生成内容“转发”给微信/QQ/邮件等其它 App。
     */
    fun shareText(ctx: Context, text: String, subject: String? = null, appQuery: String? = null): JSONObject {
        android.util.Log.i("OpenWith", "shareText chars=${text.length} app=$appQuery")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        if (!appQuery.isNullOrBlank()) {
            when (val pick = pickHandler(ctx, intent, appQuery)) {
                is Pick.One -> {
                    intent.setPackage(pick.handler.packageName)
                    return launchAndReport(ctx, intent, "text", direct = true, target = pick.handler, action = Intent.ACTION_SEND, uri = null, mime = "text/plain")
                }
                is Pick.Many -> return JSONObject().put("ok", false).put("error", "multiple apps match '$appQuery'").put("candidates", org.json.JSONArray().apply {
                    pick.list.forEach { put(JSONObject().put("package", it.packageName).put("label", it.label)) }
                })
                is Pick.None -> return JSONObject().put("ok", false).put("error", "no app handles text sharing (query='$appQuery')").put("available", org.json.JSONArray().apply {
                    pick.available.forEach { put(JSONObject().put("package", it.packageName).put("label", it.label)) }
                })
            }
        }
        return launchAndReport(ctx, intent, "text", direct = false, target = null, action = Intent.ACTION_SEND, uri = null, mime = "text/plain")
    }

    /** 目标 App 解析结果（label / package）。 */
    data class Handler(val packageName: String, val label: String)

    private sealed class Pick {
        data class One(val handler: Handler) : Pick()
        data class Many(val list: List<Handler>) : Pick()
        data class None(val available: List<Handler>) : Pick()
    }

    /** 查询能处理该 intent 的 App（按包去重，带 label）。 */
    private fun queryHandlers(ctx: Context, intent: Intent): List<Handler> = runCatching {
        ctx.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                Handler(pkg, ri.loadLabel(ctx.packageManager).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    /**
     * 按用户说法（中文名/英文名/包名片段）挑一个处理 App。
     * 多个匹配返回 Many（候选交用户选），0 个返回 None（附可用列表）。
     */
    private fun pickHandler(ctx: Context, intent: Intent, query: String): Pick {
        val all = queryHandlers(ctx, intent)
        val q = query.trim().lowercase()
        if (q.isEmpty()) return Pick.None(all)
        val exactPkg = all.filter { it.packageName.equals(q, true) }
        if (exactPkg.size == 1) return Pick.One(exactPkg.first())
        val matches = all.filter {
            it.packageName.lowercase().contains(q) || it.label.lowercase().contains(q)
        }
        return when {
            matches.size == 1 -> Pick.One(matches.first())
            matches.isEmpty() -> Pick.None(all)
            else -> Pick.Many(matches)
        }
    }

    /**
     * GET /apps?action=send|view&mime=audio/mpeg —— 列出能接这个意图的 App。
     * Agent 说「分享给微信 / 用某播放器打开」前可以先查确切名称，避免猜。
     */
    fun listHandlers(ctx: Context, action: String, mime: String?): JSONObject {
        val act = if (action.lowercase() == "send") Intent.ACTION_SEND else Intent.ACTION_VIEW
        val type = mime?.takeIf { it.isNotBlank() } ?: "*/*"
        val intent = Intent(act).setType(type)
        val all = queryHandlers(ctx, intent)
        val def = ctx.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        val arr = org.json.JSONArray()
        all.forEach { h ->
            arr.put(
                JSONObject()
                    .put("package", h.packageName)
                    .put("label", h.label)
                    .put("default", h.packageName == def),
            )
        }
        return JSONObject().put("ok", true).put("action", act).put("mime", type).put("apps", arr)
    }

    private fun launchAndReport(
        ctx: Context,
        intent: Intent,
        kind: String,
        direct: Boolean,
        target: Handler?,
        action: String,
        uri: android.net.Uri?,
        mime: String,
    ): JSONObject {
        val started = System.currentTimeMillis()
        return runCatching {
            if (direct) {
                ctx.startActivity(intent)
            } else {
                val chooser = Intent.createChooser(intent, null).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                ctx.startActivity(chooser)
            }
            val deadline = System.currentTimeMillis() + 900
            var left = false
            while (System.currentTimeMillis() < deadline) {
                if (AppState.resumedActivityName() == null) { left = true; break }
                Thread.sleep(50)
            }
            android.util.Log.i("OpenWith", "launched kind=$kind direct=$direct target=${target?.packageName} leftApp=$left")
            JSONObject()
                .put("ok", true)
                .put("opened", left)
                .put("kind", kind)
                .put("action", action)
                .put("uri", uri?.toString() ?: JSONObject.NULL)
                .put("mime", mime)
                .put("direct", direct)
                .put("target_package", target?.packageName ?: JSONObject.NULL)
                .put("target_label", target?.label ?: JSONObject.NULL)
                .put("handoff", true)
                .put("verify", "none")
                .put("stop", true)
                .put("next", "none")
                .put("do_not", JSONArray().put("screenshot").put("poll").put("retry"))
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put(
                    "hint",
                    when {
                        !direct -> "Chooser opened. Hand-off complete: no verification needed."
                        left -> "Opened directly in the requested app. Hand-off complete."
                        else -> "startActivity returned but the app stayed in front; the start may have been blocked (BAL) - foreground the app and retry, or ask the user."
                    },
                )
        }.getOrElse {
            android.util.Log.w("OpenWith", "launch failed: ${it.message}")
            JSONObject().put("ok", false).put("error", "no app can open this: ${it.message}")
        }
    }

    fun open(
        ctx: Context,
        path: String,
        kind: String = "file",
        mimeOverride: String? = null,
        appQuery: String? = null,
    ): JSONObject {
        android.util.Log.i("OpenWith", "request kind=$kind path=$path app=$appQuery")
        val file = File(path)
        if (!file.exists()) {
            return JSONObject().put("ok", false).put("error", "not found: $path")
        }
        val effectiveKind = if (kind == "file" && file.isDirectory) "folder" else kind
        val mime = mimeOverride?.takeIf { it.isNotBlank() } ?: mimeOf(file)
        // /sdcard 下的目录优先交给系统的 ExternalStorageProvider（DocumentsUI 能直接显示）。
        // ⚠️ 这个 provider 的文档 URI 不归我们所有：带上 GRANT/ClipData 会让系统尝试
        // 把读权限授予目标 App，而本 App 本身没有该 URI 权限（即使有 MANAGE_EXTERNAL_STORAGE
        // 也不行）→ SecurityException「you could obtain access using ACTION_OPEN_DOCUMENT」。
        // 所以：系统文档 URI 走「不带 grant」的纯 VIEW；我们自己的 provider URI 才带 grant。
        val externalDoc = if (effectiveKind == "folder") storageDocumentUri(file.absolutePath) else null
        // App 私有目录（dsh-home）：v1.3.49 起有自己的 DocumentsProvider，
        // 系统文件管理器可以浏览/定位（document URI 由我们的 provider 提供）。
        val homeDir = EngineConfig.dshHome(ctx).canonicalFile
        val canonical = file.canonicalFile
        val inHome = canonical.path == homeDir.path ||
            canonical.path.startsWith(homeDir.path + File.separator)
        val homeDocId = if (inHome) {
            if (canonical.path == homeDir.path) DshDocumentsProvider.ROOT_ID
            else canonical.path.removePrefix(homeDir.path + File.separator)
        } else null
        val homeDoc = homeDocId?.let {
            DocumentsContract.buildDocumentUri(DshDocumentsProvider.authority(ctx.packageName), it)
        }
        if (effectiveKind == "folder" && externalDoc == null && homeDoc == null) {
            return JSONObject().put("ok", false)
                .put("error", "folder is outside shared storage and outside DSH_HOME; cannot be shown by the file manager: ${file.absolutePath}")
        }
        // 目录定位用 document URI（App 私有目录走我们自己的 DocumentsProvider）；
        // 文件打开/分享仍用 .dshfiles FileProvider（微信/QQ 等对该形态兼容性最好）。
        val uri = externalDoc ?: (if (effectiveKind == "folder") homeDoc else null) ?: providerUri(ctx, file)
        // ⚠️ 目录定位（show in folder）不能带 GRANT/ClipData：DocumentsUI 收到带授权的
        // document URI 会按「打开文档」处理并退回 provider 根目录（平板实测），
        // 纯 data+type+direction 才会按路径定位。文件分享/打开才需要授权。
        val documentFolder = effectiveKind == "folder" && (externalDoc != null || homeDoc != null)
        val action = if (effectiveKind == "share") Intent.ACTION_SEND else Intent.ACTION_VIEW
        val type = when (effectiveKind) {
            "folder" -> "vnd.android.document/directory"
            else -> mime
        }
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!documentFolder) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (effectiveKind == "folder" && (externalDoc != null || homeDoc != null)) {
                documentsUiComponent(ctx)?.let { component = it }
            }
            if (effectiveKind == "share") {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, file.name)
                // ClipData 显式挂上：部分 ROM 的分享目标（微信/QQ）从 ClipData 取 grant，
                // 只设 EXTRA_STREAM 时 Android 自动迁移在个别实现里不生效 → 目标读不到。
                clipData = android.content.ClipData.newRawUri(file.name, uri)
            } else {
                setDataAndType(uri, type)
            }
            if (!documentFolder && effectiveKind != "share") {
                clipData = android.content.ClipData.newRawUri(file.name, uri)
            }
        }

        // 指定了「分享给某 App / 用某播放器打开」→ 直接派给那个 App，不弹选择器
        if (appQuery != null && appQuery.isNotBlank()) {
            when (val pick = pickHandler(ctx, intent, appQuery)) {
                is Pick.One -> {
                    intent.setPackage(pick.handler.packageName)
                    return launchAndReport(ctx, intent, effectiveKind, direct = true, target = pick.handler, action = action, uri = uri, mime = type)
                }
                is Pick.Many -> return JSONObject().put("ok", false).put("error", "multiple apps match '$appQuery'").put("candidates", org.json.JSONArray().apply {
                    pick.list.forEach { put(JSONObject().put("package", it.packageName).put("label", it.label)) }
                })
                is Pick.None -> return JSONObject().put("ok", false).put("error", "no app handles this (query='$appQuery')").put("available", org.json.JSONArray().apply {
                    pick.available.forEach { put(JSONObject().put("package", it.packageName).put("label", it.label)) }
                })
            }
        }

        val target = ctx.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        android.util.Log.i("OpenWith", "intent action=$action uri=$uri type=$type")
        // 文件夹（显示位置）已经有显式 DocumentsUI component，直接打开，不再套 chooser
        val direct = effectiveKind == "folder" && intent.component != null
        return launchAndReport(ctx, intent, effectiveKind, direct = direct, target = target?.activityInfo?.packageName?.let { Handler(it, "") }, action = action, uri = uri, mime = type)
    }
}
