package dev.backgrounded.data.importer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.core.di.ApplicationScope
import dev.backgrounded.core.image.ThumbnailCache
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.db.BackgroundedDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Provider observers and scan work exist only while the app or a real wallpaper is visible. */
@Singleton
class FolderDiscoveryController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: BackgroundedDatabase,
        private val scanner: LinkedFolderScanner,
        private val thumbnails: ThumbnailCache,
        private val settings: SettingsStore,
        private val grants: ManagedFolderStore,
        @ApplicationScope scope: CoroutineScope,
    ) {
        private val owners = mutableSetOf<Any>()
        private val visible = MutableStateFlow(false)
        private val changes = Channel<Unit>(Channel.CONFLATED)

        init {
            scope.launch {
                grants.changes.collect {
                    thumbnails.evictAll()
                    changes.trySend(Unit)
                }
            }
            scope.launch {
                combine(
                    visible,
                    settings.settings.map { it.folderScanSeconds }.distinctUntilChanged(),
                ) { active, seconds ->
                    active to seconds
                }.collectLatest { (active, seconds) ->
                    if (!active) return@collectLatest
                    val observer =
                        object : ContentObserver(Handler(Looper.getMainLooper())) {
                            override fun onChange(selfChange: Boolean) {
                                changes.trySend(Unit)
                            }
                        }
                    val registered = mutableSetOf<String>()
                    try {
                        while (true) {
                            val folders = db.linkedFolderDao().all()
                            folders.map { it.treeUri }.distinct().filter { it !in registered }.forEach { ref ->
                                runCatching {
                                    val tree = Uri.parse(ref)
                                    val children =
                                        DocumentsContract.buildChildDocumentsUriUsingTree(
                                            tree,
                                            DocumentsContract.getTreeDocumentId(tree),
                                        )
                                    context.contentResolver.registerContentObserver(children, true, observer)
                                    registered.add(ref)
                                }
                            }
                            val notified = changes.tryReceive().isSuccess
                            if (seconds > 0 || notified) {
                                folders.map { it.albumId }.distinct().forEach { scanner.scan(it, force = notified) }
                            }
                            if (notified) thumbnails.evictAll()
                            withTimeoutOrNull((seconds.takeIf { it > 0 } ?: 60) * 1000L) { changes.receive() }
                                ?.let { changes.trySend(Unit) }
                        }
                    } finally {
                        context.contentResolver.unregisterContentObserver(observer)
                    }
                }
            }
        }

        @Synchronized fun visible(
            owner: Any,
            active: Boolean,
        ) {
            if (active) owners.add(owner) else owners.remove(owner)
            visible.value = owners.isNotEmpty()
        }
    }
