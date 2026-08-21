package app.cleanaf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

const val MIME_DIR: String = DocumentsContract.Document.MIME_TYPE_DIR

data class TreeFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String,
    val modified: Long,
    val path: String,
)

/**
 * Recursively walk a Storage-Access-Framework tree (from ACTION_OPEN_DOCUMENT_TREE),
 * calling [onFile] for every non-directory document and [onEmptyDir] for directories
 * that contain nothing. Directories that can't be read are skipped.
 */
fun walkTree(
    context: Context,
    treeUri: Uri,
    onFile: (TreeFile) -> Unit,
    onEmptyDir: ((Uri, String) -> Unit)? = null,
    isCancelled: () -> Boolean = { false },
) {
    val resolver = context.contentResolver
    val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
    val stack = ArrayDeque<Pair<String, String>>()
    stack.addLast(rootDocId to "")
    val proj = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    while (stack.isNotEmpty()) {
        if (isCancelled()) return
        val (docId, path) = stack.removeLast()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        var childCount = 0
        try {
            resolver.query(childrenUri, proj, null, null, null)?.use { c ->
                val idI = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameI = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeI = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeI = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modI = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (c.moveToNext()) {
                    childCount++
                    val cid = c.getString(idI)
                    val name = c.getString(nameI) ?: "file"
                    val mime = c.getString(mimeI) ?: ""
                    val childPath = if (path.isEmpty()) name else "$path/$name"
                    if (mime == MIME_DIR) {
                        stack.addLast(cid to childPath)
                    } else {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cid)
                        onFile(TreeFile(docUri, name, c.getLong(sizeI), mime, c.getLong(modI), childPath))
                    }
                }
            }
        } catch (e: Exception) {
            continue
        }
        if (childCount == 0 && path.isNotEmpty() && onEmptyDir != null) {
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            onEmptyDir(dirUri, path)
        }
    }
}

/** Delete a single SAF document. Returns true on success. */
fun deleteDocument(context: Context, uri: Uri): Boolean =
    try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (e: Exception) {
        false
    }

/** A friendly, human-readable name for the picked tree, e.g. "Download". */
fun treeDisplayName(treeUri: Uri): String {
    val id = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (e: Exception) {
        return "this folder"
    }
    val afterColon = id.substringAfter(':', id)
    val leaf = afterColon.trimEnd('/').substringAfterLast('/')
    return when {
        leaf.isNotBlank() -> leaf
        afterColon.isBlank() -> "Internal storage"
        else -> afterColon
    }
}

private fun hashRegion(ch: FileChannel, start: Long, len: Long, md: MessageDigest) {
    ch.position(start)
    val bb = ByteBuffer.allocate(65536)
    var remaining = len
    while (remaining > 0) {
        bb.clear()
        if (bb.limit() > remaining) bb.limit(remaining.toInt())
        val n = ch.read(bb)
        if (n <= 0) break
        bb.flip()
        md.update(bb)
        remaining -= n
    }
}

/**
 * Content fingerprint for a file behind a content Uri: its size plus a SHA-256 of the
 * first and last megabyte. Two files with the same key are duplicates for our purposes.
 */
fun contentFileKey(context: Context, uri: Uri, size: Long): String {
    val md = MessageDigest.getInstance("SHA-256")
    val head = 1_048_576L
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        FileInputStream(pfd.fileDescriptor).use { fis ->
            val ch = fis.channel
            hashRegion(ch, 0, minOf(head, size), md)
            if (size > 2 * head) hashRegion(ch, size - head, head, md)
        }
    }
    return size.toString() + "-" + md.digest().joinToString("") { "%02x".format(it) }
}
