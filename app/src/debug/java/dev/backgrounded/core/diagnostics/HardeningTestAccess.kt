package dev.backgrounded.core.diagnostics

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.backgrounded.core.security.EncryptedImageStore
import dev.backgrounded.core.security.PinVault
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.importer.ImageStore
import dev.backgrounded.data.importer.LinkedFolderScanner
import dev.backgrounded.data.importer.ManagedSourceMover
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.render.BitmapLoader

/** Available only to the isolated instrumentation target; excluded from release builds. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HardeningTestAccess {
    fun database(): BackgroundedDatabase

    fun images(): ImageStore

    fun albums(): AlbumRepository

    fun encrypted(): EncryptedImageStore

    fun vault(): PinVault

    fun sourceMover(): ManagedSourceMover

    fun bitmaps(): BitmapLoader

    fun scanner(): LinkedFolderScanner
}
