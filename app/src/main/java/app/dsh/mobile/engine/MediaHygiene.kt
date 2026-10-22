package app.dsh.mobile.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v2.3.0（ISSUE-工作区与回收站不应被相册扫描）：
 *
 * - 工作区根与回收站根自动放 `.nomedia`（目录级屏蔽整棵子树的 images/video/audio 索引）；
 * - 提供唯一“导出给用户”出口：默认 `Download/DSH/`，仅对可见目标触发 MediaScanner；
 *   用户指定 `pictures` 时才进相册目录，`custom:<dir>` 不触发扫描。
 */
object MediaHygiene {

    private const val TAG = "MediaHygiene"

    fun workspacesRoot(ctx: Context): File = EngineConfig.workspaces(ctx)
    fun trashRoot(): File = File(Environment.getExternalStorageDirectory(), "dsh-trash")

    private fun writeNomedia(dir: File): Boolean = runCatching {
        dir.mkdirs()
        val f = File(dir, ".nomedia")
        if (!f.isFile || f.length() == 0L) f.writeText("")
        true
    }.getOrDefault(false)

    /** 幂等：工作区根 + 回收站根补 `.nomedia`。 */
    fun ensureAll(ctx: Context) {
        runCatching {
            val ws = workspacesRoot(ctx)
            writeNomedia(ws)
            // 旧版可能在根下已有工作区且根没有 .nomedia：根补上即可覆盖整棵子树；
            // 额外给每个一级子目录也补一份，兼容根是符号链接/权限边缘情况。
            ws.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { writeNomedia(it) }
            writeNomedia(trashRoot())
            Log.i(TAG, "nomedia ensured: ${ws.absolutePath} / ${trashRoot().absolutePath}")
        }.onFailure { Log.w(TAG, "ensure nomedia failed: ${it.message}") }
    }

    fun workspaceMediaVisible(ctx: Context): Boolean = runCatching {
        val ws = workspacesRoot(ctx)
        !File(ws, ".nomedia").isFile
    }.getOrDefault(true)

    fun trashMediaVisible(): Boolean = runCatching { !File(trashRoot(), ".nomedia").isFile }.getOrDefault(true)

    /**
     * 导出给用户。target: download（默认）| pictures | custom。
     * @return {ok, targetDir, copied:[{from,to}], skipped:[{path,reason}], scanned}
     */
    fun export(ctx: Context, body: JSONObject): JSONObject {
        val paths = body.optJSONArray("paths") ?: JSONArray()
        val target = body.optString("target", "download").lowercase()
        val customDir = body.optString("dir").trim()
        val base = when (target) {
            "pictures" -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DSH")
            "custom" -> File(customDir.ifBlank { Environment.getExternalStorageDirectory().absolutePath })
            else -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DSH")
        }
        base.mkdirs()
        val copied = JSONArray()
        val skipped = JSONArray()
        val toScan = ArrayList<String>()
        for (i in 0 until paths.length()) {
            val src = File(paths.optString(i))
            if (!src.isFile) {
                skipped.put(JSONObject().put("path", src.absolutePath).put("reason", "not a file"))
                continue
            }
            var dst = File(base, src.name)
            var n = 1
            while (dst.exists()) {
                val dot = src.name.lastIndexOf('.')
                val stem = if (dot > 0) src.name.substring(0, dot) else src.name
                val ext = if (dot > 0) src.name.substring(dot) else ""
                dst = File(base, "$stem-$n$ext")
                n++
            }
            runCatching { src.copyTo(dst) }
                .onSuccess {
                    copied.put(JSONObject().put("from", src.absolutePath).put("to", dst.absolutePath))
                    if (target == "download" || target == "pictures") toScan.add(dst.absolutePath)
                }
                .onFailure { skipped.put(JSONObject().put("path", src.absolutePath).put("reason", it.message)) }
        }
        if (toScan.isNotEmpty()) {
            runCatching {
                MediaScannerConnection.scanFile(ctx, toScan.toTypedArray(), null) { _, _ -> }
            }.onFailure { Log.w(TAG, "scan failed: ${it.message}") }
        }
        return JSONObject()
            .put("ok", true)
            .put("target", target)
            .put("targetDir", base.absolutePath)
            .put("copied", copied)
            .put("skipped", skipped)
            .put("scanned", toScan.size)
            .put("hint", if (target == "pictures") "pictures is album-visible by design; use target=download unless the user asked for the album" else "review copied[] and tell the user the exact targetDir")
    }
}
