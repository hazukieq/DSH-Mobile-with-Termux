package app.dsh.mobile.engine

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * 把 App 私有目录（$DSH_HOME）暴露成 SAF DocumentsProvider（v1.3.49）。
 *
 * 为什么需要它：「显示文件位置」对 `/data/user/0/com.termux/...` 这类路径，
 * 系统文件管理器根本无权浏览（不在任何 storage root 里）；v79 只能返回错误。
 * 这里按 Android 官方 DocumentsProvider 规范提供自己的 root：
 *  - 系统「文件」App 侧边栏会出现 "DSH Mobile"；
 *  - DocumentsUI 可以通过 document URI 定位到 App 私有目录里的具体文件夹/文件；
 *  - 其它 App 通过 DocumentsUI 打开时，由系统代持 MANAGE_DOCUMENTS 权限，我们看到的就是
 *    「文件管理器能定位 App 私有目录」。
 *
 * 只暴露 dsh-home（工作区/会话/页面产物都在这里），不暴露整个 /data。
 * documentId = 相对 dsh-home 的路径（root 本身用 "home"），路径越界/含 .. 一律拒绝。
 */
class DshDocumentsProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY_SUFFIX = ".docs"
        const val ROOT_ID = "home"
        fun authority(pkg: String) = pkg + AUTHORITY_SUFFIX

        private val DEFAULT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Root.COLUMN_ROOT_ID,
        )
    }

    private fun homeDir(): File = EngineConfig.dshHome(context ?: throw IllegalStateException("no context")).apply { mkdirs() }

    private fun fileFor(documentId: String): File? {
        if (documentId == ROOT_ID) return homeDir()
        if (documentId.isBlank() || documentId.contains("..")) return null
        val root = homeDir().canonicalFile
        val f = File(root, documentId).canonicalFile
        return if (f.path == root.path || f.path.startsWith(root.path + File.separator)) f else null
    }

    private fun docIdFor(file: File): String {
        val root = homeDir().canonicalFile
        val canonical = file.canonicalFile
        if (canonical == root) return ROOT_ID
        return canonical.path.removePrefix(root.path + File.separator)
    }

    private fun mimeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    }

    /**
     * 按 cursor 的 projection 逐列填值（v1.3.49 修复）。
     *
     * DocumentsProvider 内部（findDocumentPath 等）会用自己指定的 projection 查询，
     * 如果按固定顺序 addRow，列与值就会错位 → 抛异常 → DocumentsUI 退回默认“下载”。
     */
    private fun row(cursor: MatrixCursor, file: File, docId: String) {
        val flags = if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        val r = cursor.newRow()
        cursor.columnNames.forEach { col ->
            when (col) {
                Document.COLUMN_DOCUMENT_ID -> r.add(docId)
                Document.COLUMN_DISPLAY_NAME -> r.add(file.name)
                Document.COLUMN_MIME_TYPE -> r.add(mimeOf(file))
                Document.COLUMN_SIZE -> r.add(if (file.isFile) file.length() else 0L)
                Document.COLUMN_LAST_MODIFIED -> r.add(file.lastModified())
                Document.COLUMN_FLAGS -> r.add(flags)
                Document.COLUMN_ICON -> r.add(android.R.drawable.ic_menu_agenda)
                Root.COLUMN_ROOT_ID -> r.add(ROOT_ID)
                else -> r.add(null)
            }
        }
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES,
        )
        val cursor = MatrixCursor(cols, 1)
        val row = cursor.newRow()
        cols.forEach { c ->
            when (c) {
                Root.COLUMN_ROOT_ID -> row.add(ROOT_ID)
                Root.COLUMN_DOCUMENT_ID -> row.add(ROOT_ID)
                Root.COLUMN_TITLE -> row.add("DSH Mobile")
                Root.COLUMN_FLAGS -> row.add(Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
                Root.COLUMN_MIME_TYPES -> row.add("*/*")
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_PROJECTION
        val cursor = MatrixCursor(cols, 1)
        val f = fileFor(documentId) ?: throw FileNotFoundException("bad document id: $documentId")
        if (!f.exists()) throw FileNotFoundException("missing: $documentId")
        row(cursor, f, docIdFor(f))
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection ?: DEFAULT_PROJECTION
        val cursor = MatrixCursor(cols, 8)
        val parent = fileFor(parentDocumentId) ?: throw FileNotFoundException("bad parent: $parentDocumentId")
        if (!parent.isDirectory) throw FileNotFoundException("not a directory: $parentDocumentId")
        parent.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { f ->
            row(cursor, f, docIdFor(f))
        }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = fileFor(documentId) ?: throw FileNotFoundException("bad document id: $documentId")
        android.util.Log.i("DshDocumentsProvider", "openDocument $documentId mode=$mode -> ${f.absolutePath}")
        if (!f.isFile) throw FileNotFoundException("not a file: ${f.absolutePath}")
        val access = if (mode == "w") ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY
        return ParcelFileDescriptor.open(f, access)
    }

    override fun getDocumentType(documentId: String): String {
        val f = fileFor(documentId) ?: return "application/octet-stream"
        return mimeOf(f)
    }

    /**
     * v1.3.49 修复：DocumentsUI 打开 document URI 时会调 findDocumentPath 生成面包屑，
     * 基类默认抛 UnsupportedOperationException，导致界面退回默认“下载”页。
     * 这里按 docId 的路径前缀构造从 root（或指定 parent）到目标的 Path。
     */
    override fun findDocumentPath(
        parentDocumentId: String?,
        childDocumentId: String,
    ): android.provider.DocumentsContract.Path {
        val child = fileFor(childDocumentId) ?: throw FileNotFoundException("bad child: $childDocumentId")
        if (!child.exists()) throw FileNotFoundException("missing: $childDocumentId")
        val rel = docIdFor(child)
        val chain = mutableListOf(ROOT_ID)
        if (rel != ROOT_ID) {
            val parts = rel.split('/').filter { it.isNotEmpty() }
            for (i in parts.indices) chain.add(parts.take(i + 1).joinToString("/"))
        }
        val fromIndex = if (parentDocumentId != null) {
            val idx = chain.indexOf(parentDocumentId)
            if (idx >= 0) idx else 0
        } else 0
        return android.provider.DocumentsContract.Path(ROOT_ID, chain.subList(fromIndex, chain.size))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = fileFor(parentDocumentId) ?: return false
        val child = fileFor(documentId) ?: return false
        return child.path.startsWith(parent.path + File.separator)
    }
}
