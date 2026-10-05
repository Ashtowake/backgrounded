package dev.backgrounded.data.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

object SafFolders {
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
