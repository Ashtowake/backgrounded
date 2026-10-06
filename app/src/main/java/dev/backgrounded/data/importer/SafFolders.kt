package dev.backgrounded.data.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

object SafFolders {
    data class ImageDocument(val uri: Uri, val id: String, val size: Long, val modifiedAt: Long)

    /** Enumerate without retaining an album-sized list. Provider failures remain failures. */
    suspend fun forEachImage(
        context: Context,
        treeUri: Uri,
        consume: suspend (ImageDocument) -> Unit,
    ) {
        val parent = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
        val columns =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
        val cursor =
            context.contentResolver.query(children, columns, null, null, null)
                ?: error("Folder provider did not return documents")
        cursor.use {
            var batch = 0
            while (it.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (it.getString(1)?.startsWith("image/") == true &&
                    !it.getString(4).orEmpty().startsWith(".backgrounded-restore-")
                ) {
                    val id = it.getString(0)
                    consume(
                        ImageDocument(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                            id,
                            if (it.isNull(2)) -1 else it.getLong(2),
                            if (it.isNull(3)) -1 else it.getLong(3),
                        ),
                    )
                }
                if (++batch == 32) {
                    batch = 0
                    yield()
                }
            }
        }
    }

    fun listImages(
        context: Context,
        treeUri: Uri,
        recursive: Boolean = false,
    ): List<Uri> {
        val documentId =
            runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return emptyList()
        val pending = java.util.ArrayDeque<String>().apply { add(documentId) }
        val visited = mutableSetOf<String>()
        val images = mutableListOf<Uri>()
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            if (!visited.add(parent)) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
            runCatching {
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val document = cursor.getString(0)
                        val mimeType = cursor.getString(1)
                        if (recursive && mimeType == DocumentsContract.Document.MIME_TYPE_DIR) pending.add(document)
                        if (mimeType?.startsWith("image/") == true) {
                            images += DocumentsContract.buildDocumentUriUsingTree(treeUri, document)
                        }
                    }
                }
            }
        }
        return images
    }
}
