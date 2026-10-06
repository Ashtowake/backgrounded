package dev.backgrounded.data.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ManagedFolderStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val preferences = context.getSharedPreferences("managed_folders", Context.MODE_PRIVATE)
        private val mutableFolders = MutableStateFlow(preferences.getStringSet("uris", emptySet()).orEmpty())
        val folders: StateFlow<Set<String>> = mutableFolders.asStateFlow()
        val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        fun grant(uri: Uri): Boolean {
            val granted =
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.isSuccess
            if (!granted) return false
            mutableFolders.value = mutableFolders.value + uri.toString()
            persist()
            changes.tryEmit(Unit)
            return true
        }

        fun revoke(uri: Uri) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            mutableFolders.value = mutableFolders.value - uri.toString()
            persist()
            changes.tryEmit(Unit)
        }

        fun hasWrite(uri: Uri): Boolean =
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

        private fun persist() {
            preferences.edit().putStringSet("uris", mutableFolders.value).apply()
        }
    }
