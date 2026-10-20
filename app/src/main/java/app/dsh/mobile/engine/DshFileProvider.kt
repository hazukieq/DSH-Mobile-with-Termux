package app.dsh.mobile.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.OpenableColumns
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * 最小文件 ContentProvider（v1.3.41）。
 *
 * WebUI 侧「用外部程序打开 / 在文件夹中显示」需要把本机路径交给别的 App。
 * Android 7+ 禁止 file:// URI，又不想为此引入 androidx FileProvider，这里实现一个
 * 只读、exported=false、按需授权的 provider：content://<pkg>.dshfiles/<encoded absolute path>。
 */
class DshFileProvider : ContentProvider() {
    companion object {
        const val AUTHORITY_SUFFIX = ".dshfiles"
        fun authority(pkg: String) = pkg + AUTHORITY_SUFFIX
    }

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File? {
        val raw = uri.encodedPath ?: return null
        // encodedPath = "/" + "%2F..." → decode 后可能得到 "//storage/..."；
        // 归一化掉重复前导斜杠，避免个别 ROM 的 File 判定异常。
        val decoded = Uri.decode(raw).replace(Regex("^/+"), "/")
        return File(decoded)
    }

    override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor {
        val f = fileFor(uri) ?: throw FileNotFoundException("bad uri: $uri")
        if (!f.isFile) throw FileNotFoundException("not a file: ${f.absolutePath}")
        return android.content.res.AssetFileDescriptor(
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), 0, -1
        )
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri)
        android.util.Log.i("DshFileProvider", "openFile uri=$uri mode=$mode -> ${f?.absolutePath}")
        if (f == null) throw FileNotFoundException("bad uri: $uri")
        if (!f.isFile) throw FileNotFoundException("not a file: ${f.absolutePath}")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * 目标 App 常先 query DISPLAY_NAME/SIZE 再决定怎么读；返回 null 会被当成
     * 「文件不存在」（微信/QQ 分享出去的 mp3 就踩了这个）。这里返回标准 OpenableColumns。
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val f = fileFor(uri) ?: return null
        if (!f.exists()) return null
        val cols = projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols.toTypedArray(), 1)
        val row = cursor.newRow()
        cols.forEach { c ->
            when (c) {
                OpenableColumns.DISPLAY_NAME -> row.add(f.name)
                OpenableColumns.SIZE -> row.add(f.length())
                "_display_name" -> row.add(f.name)
                "_size" -> row.add(f.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String? {
        val f = fileFor(uri) ?: return null
        if (f.isDirectory) return "vnd.android.document/directory"
        val ext = f.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
