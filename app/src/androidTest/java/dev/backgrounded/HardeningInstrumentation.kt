package dev.backgrounded

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import dagger.hilt.android.EntryPointAccessors
import dev.backgrounded.core.diagnostics.DeniedFolderProvider
import dev.backgrounded.core.diagnostics.HardeningTestAccess
import dev.backgrounded.core.security.EncryptedFileCodec
import dev.backgrounded.data.db.LinkedFolderEntity
import dev.backgrounded.data.db.ManagedSourceEntity
import dev.backgrounded.data.db.OperationJournalEntity
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.SourceType
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Run with -PqaApplicationId=dev.backgrounded.hardeningprobe. Never runs against a user's installation. */
class HardeningInstrumentation : Instrumentation() {
    private var revokedOnly = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        revokedOnly = arguments?.getString("mode") == "revoked"
        start()
    }

    @Suppress("TooGenericExceptionCaught")
    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName == "dev.backgrounded.hardeningprobe") {
                "Requires an isolated hardeningprobe application ID"
            }
            val access =
                EntryPointAccessors.fromApplication(
                    targetContext.applicationContext,
                    HardeningTestAccess::class.java,
                )
            runBlocking {
                if (!revokedOnly) setFullAccess(true)
                val authenticated =
                    if (access.vault().configured()) {
                        access.vault().unlockAsync("908172")
                    } else {
                        access.vault().setupAsync("908172", false)
                    }
                check(authenticated)
                val cases =
                    if (revokedOnly) {
                        check(!Environment.isExternalStorageManager())
                        listOf<Pair<String, suspend () -> Unit>>(
                            "restore-revoked-access" to { restoreConflict(access, revoked = true) },
                        )
                    } else {
                        listOf<Pair<String, suspend () -> Unit>>(
                            "encrypt-before-publication" to { encryptionRecovery(access, "COPYING") },
                            "encrypt-verified" to { encryptionRecovery(access, "VERIFIED") },
                            "encrypt-after-publication" to { publishedRecovery(access) },
                            "decrypt-verified" to { decryptionRecovery(access) },
                            "incomplete-copy" to { incompleteRecovery(access) },
                            "pin-restart-lock" to { restartLock(access) },
                            "corrupt-key-failure-cache" to { corruptedKey(access) },
                            "failed-folder-backoff" to { failedFolderBackoff(access) },
                            "restore-copying" to { restoreRecovery(access, "COPYING") },
                            "restore-verified" to { restoreRecovery(access, "VERIFIED") },
                            "restore-published" to { restoreRecovery(access, "PUBLISHED") },
                            "restore-conflict" to { restoreConflict(access, revoked = false) },
                        )
                    }
                cases.forEachIndexed { index, (name, test) ->
                    test()
                    sendStatus(
                        0,
                        Bundle().apply {
                            putString("stream", "PASS $name\n")
                            putInt("current", index + 1)
                            putInt("numtests", cases.size)
                        },
                    )
                }
                result.putString("stream", "OK (${cases.size} native hardening checks)\n")
            }
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private data class Fixture(val albumId: Long, val assetId: Long, val source: File, val hash: String)

    private suspend fun fixture(access: HardeningTestAccess): Fixture {
        val albumId = access.albums().createAlbum("Probe ${UUID.randomUUID()}")
        val source = File(access.images().imagesDir, "probe-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(kotlin.random.Random.nextInt())
        try {
            source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val hash = hash(source)
        val pairId =
            access.albums().addPair(
                albumId,
                Background(
                    0, albumId, SourceType.IMPORT, source.absolutePath, source.name, hash,
                    16, 16, false, 0, System.currentTimeMillis(), Background.defaultFramings(),
                ),
            )
        return Fixture(albumId, requireNotNull(access.albums().getPair(pairId)).home.id, source, hash)
    }

    private suspend fun encryptionRecovery(
        access: HardeningTestAccess,
        stage: String,
    ) {
        val fixture = fixture(access)
        val destination = File(access.images().imagesDir, "enc-${UUID.randomUUID()}.bge")
        val fileKey = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(java.security.SecureRandom()::nextBytes)
        val vaultKey = requireNotNull(access.vault().key())
        val wrapped =
            try {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(vaultKey, "AES"), GCMParameterSpec(128, nonce))
                    doFinal(fileKey)
                }
            } finally {
                vaultKey.fill(0)
            }
        try {
            EncryptedFileCodec.write(fixture.source, File("${destination.path}.tmp"), fileKey)
        } finally {
            fileKey.fill(0)
        }
        access.database().hardeningDao().recordOperation(
            OperationJournalEntity(
                destination.name, "ENCRYPT", stage, fixture.assetId, fixture.source.path,
                destination.path, fixture.hash, wrapped, nonce, System.currentTimeMillis(),
            ),
        )
        check(fixture.source.isFile)
        access.encrypted().recoverOperations()
        val asset = requireNotNull(access.database().backgroundDao().get(fixture.assetId))
        check(asset.storageRef == destination.path && asset.sourceType == SourceType.ENCRYPTED_IMPORT.name)
        check(!fixture.source.exists())
        check(access.database().encryptedAssetDao().get(fixture.assetId)?.formatVersion == 2)
        access.encrypted().openAsset(fixture.assetId)!!.use { check(hash(it) == fixture.hash) }
        check(access.database().hardeningDao().operationsForAsset(fixture.assetId) == 0)
    }

    private suspend fun publishedRecovery(access: HardeningTestAccess) {
        val fixture = fixture(access)
        check(access.encrypted().encryptAsset(fixture.assetId))
        val asset = requireNotNull(access.database().backgroundDao().get(fixture.assetId))
        val old = File(access.images().imagesDir, "old-${UUID.randomUUID()}.png").apply { writeText("old copy") }
        access.database().hardeningDao().recordOperation(
            OperationJournalEntity(
                "published-${UUID.randomUUID()}", "ENCRYPT", "VERIFIED", fixture.assetId,
                old.path, asset.storageRef, fixture.hash, null, null, System.currentTimeMillis(),
            ),
        )
        access.encrypted().recoverOperations()
        check(!old.exists())
        check(File(asset.storageRef).isFile)
        check(access.database().hardeningDao().operationsForAsset(fixture.assetId) == 0)
    }

    private suspend fun decryptionRecovery(access: HardeningTestAccess) {
        val fixture = fixture(access)
        check(access.encrypted().encryptAsset(fixture.assetId))
        val asset = requireNotNull(access.database().backgroundDao().get(fixture.assetId))
        val destination = File(access.images().imagesDir, "plain-${UUID.randomUUID()}.png")
        access.encrypted().openAsset(fixture.assetId)!!.use { input ->
            File("${destination.path}.tmp").outputStream().use(input::copyTo)
        }
        access.database().hardeningDao().recordOperation(
            OperationJournalEntity(
                destination.name, "DECRYPT", "VERIFIED", fixture.assetId, asset.storageRef,
                destination.path, fixture.hash, null, null, System.currentTimeMillis(),
            ),
        )
        check(File(asset.storageRef).isFile)
        access.encrypted().recoverOperations()
        check(hash(destination) == fixture.hash)
        check(access.database().backgroundDao().get(fixture.assetId)?.storageRef == destination.path)
        check(access.database().encryptedAssetDao().get(fixture.assetId) == null)
        check(!File(asset.storageRef).exists())
    }

    private suspend fun incompleteRecovery(access: HardeningTestAccess) {
        val fixture = fixture(access)
        val destination = File(access.images().imagesDir, "enc-${UUID.randomUUID()}.bge")
        val temporary = File("${destination.path}.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        access.database().hardeningDao().recordOperation(
            OperationJournalEntity(
                destination.name, "ENCRYPT", "COPYING", fixture.assetId, fixture.source.path,
                destination.path, fixture.hash, ByteArray(32), ByteArray(12), System.currentTimeMillis(),
            ),
        )
        access.encrypted().recoverOperations()
        check(hash(fixture.source) == fixture.hash)
        check(!temporary.exists())
        check(access.database().backgroundDao().get(fixture.assetId)?.storageRef == fixture.source.path)
        check(access.database().hardeningDao().operationsForAsset(fixture.assetId) == 0)
    }

    private suspend fun restartLock(access: HardeningTestAccess) {
        val fixture = fixture(access)
        check(access.encrypted().encryptAsset(fixture.assetId))
        access.vault().lock()
        check(!access.encrypted().available(fixture.assetId))
        check(access.encrypted().openAsset(fixture.assetId) == null)
        check(!access.vault().unlockAsync("000000"))
        check(access.vault().unlockAsync("908172"))
        check(access.encrypted().available(fixture.assetId))
        check(access.encrypted().decryptAsset(fixture.assetId))
        val asset = requireNotNull(access.database().backgroundDao().get(fixture.assetId))
        check(hash(File(asset.storageRef)) == fixture.hash)
    }

    private suspend fun corruptedKey(access: HardeningTestAccess) {
        val fixture = fixture(access)
        check(access.encrypted().encryptAsset(fixture.assetId))
        val metadata = requireNotNull(access.database().encryptedAssetDao().get(fixture.assetId))
        val altered = metadata.wrappedKey.copyOf()
        altered[0] = (altered[0].toInt() xor 1).toByte()
        access.database().encryptedAssetDao().upsert(metadata.copy(wrappedKey = altered))
        val background = access.albums().pairsFor(fixture.albumId).single().home
        check(access.bitmaps().exists(background))
        check(access.bitmaps().decode(background, 16, 16) == null)
        check(!access.bitmaps().exists(background))
        check(access.bitmaps().decode(background, 16, 16) == null)
        access.database().encryptedAssetDao().upsert(metadata)
        access.bitmaps().clearFailures()
        val recovered = requireNotNull(access.bitmaps().decode(background, 16, 16))
        recovered.recycle()
    }

    private suspend fun failedFolderBackoff(access: HardeningTestAccess) {
        val fixture = fixture(access)
        val uri = "content://dev.backgrounded.hardeningprobe.denied/tree/folder"
        access.database().linkedFolderDao().insert(LinkedFolderEntity(albumId = fixture.albumId, treeUri = uri))
        DeniedFolderProvider.queries.set(0)
        repeat(100) { access.scanner().scan(fixture.albumId) }
        check(DeniedFolderProvider.queries.get() == 1)
        check(access.database().linkedFolderDao().forAlbum(fixture.albumId).single().lastScanAt == 0L)
        access.scanner().scan(fixture.albumId, force = true)
        check(DeniedFolderProvider.queries.get() == 2)
        check(hash(fixture.source) == fixture.hash)
    }

    private fun setFullAccess(enabled: Boolean) {
        val mode = if (enabled) "allow" else "ignore"
        uiAutomation.executeShellCommand("appops set dev.backgrounded.hardeningprobe MANAGE_EXTERNAL_STORAGE $mode")
            .use {
                    descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
    }

    private fun sharedTarget(): File {
        val folder = File(Environment.getExternalStorageDirectory(), "Download/backgrounded-hardening-probe")
        check(folder.isDirectory || folder.mkdirs())
        return File(folder, "probe-${UUID.randomUUID()}.png")
    }

    private suspend fun restorationRecord(
        access: HardeningTestAccess,
        fixture: Fixture,
        target: File,
    ) {
        access.database().managedSourceDao().upsert(
            ManagedSourceEntity(fixture.assetId, "FULL_ACCESS", target.path, target.name, fixture.hash, "MOVED_FILE"),
        )
    }

    private suspend fun restoreRecovery(
        access: HardeningTestAccess,
        stage: String,
    ) {
        val fixture = fixture(access)
        val target = sharedTarget()
        restorationRecord(access, fixture, target)
        val temporary = File(target.parentFile, ".backgrounded-restore-${UUID.randomUUID()}.tmp")
        if (stage == "PUBLISHED") {
            fixture.source.copyTo(target)
        } else {
            temporary.writeBytes(if (stage == "COPYING") byteArrayOf(1, 2) else fixture.source.readBytes())
        }
        access.database().hardeningDao().recordOperation(
            OperationJournalEntity(
                "restore-file-${fixture.assetId}", "RESTORE_FILE", stage, fixture.assetId,
                fixture.source.path, temporary.path, fixture.hash, null, null, System.currentTimeMillis(),
            ),
        )
        try {
            check(access.sourceMover().restoreAlbum(fixture.albumId).isEmpty())
            check(hash(target) == fixture.hash)
            check(hash(fixture.source) == fixture.hash)
            check(access.database().managedSourceDao().get(fixture.assetId) == null)
            check(access.database().hardeningDao().operationsForAsset(fixture.assetId) == 0)
        } finally {
            target.delete()
            temporary.delete()
        }
    }

    private suspend fun restoreConflict(
        access: HardeningTestAccess,
        revoked: Boolean,
    ) {
        val fixture = fixture(access)
        val target =
            if (revoked) {
                File(Environment.getExternalStorageDirectory(), "Download/backgrounded-hardening-probe/absent.png")
            } else {
                sharedTarget()
            }
        restorationRecord(access, fixture, target)
        if (!revoked) target.writeText("new original")
        try {
            check(access.sourceMover().restoreAlbum(fixture.albumId) == listOf(target.name))
            check(hash(fixture.source) == fixture.hash)
            check(access.database().managedSourceDao().get(fixture.assetId) != null)
            if (!revoked) check(target.readText() == "new original")
        } finally {
            if (!revoked) target.delete()
        }
    }

    private fun hash(file: File): String = file.inputStream().use(::hash)

    private fun hash(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
