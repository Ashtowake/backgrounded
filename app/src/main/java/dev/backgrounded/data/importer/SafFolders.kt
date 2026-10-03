package dev.backgrounded.data.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

object SafFolders {
    fun listImages(
        context: Context,
        treeUri: Uri,
    ): List<Uri> {
        val documentId =
            runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val images = mutableListOf<Uri>()
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
                    if (mimeType?.startsWith("image/") == true) {
                        images += DocumentsContract.buildDocumentUriUsingTree(treeUri, document)
                    }
                }
            }
        }
        return images
    }
}
