package app.dsh.mobile.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
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
        return File(Uri.decode(raw))
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri) ?: throw FileNotFoundException("bad uri: $uri")
        if (!f.isFile) throw FileNotFoundException("not a file: ${f.absolutePath}")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? {
        val f = fileFor(uri) ?: return null
        if (f.isDirectory) return "vnd.android.document/directory"
        val ext = f.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
