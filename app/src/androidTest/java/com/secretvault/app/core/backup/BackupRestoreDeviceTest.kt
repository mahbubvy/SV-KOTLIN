package com.secretvault.app.core.backup

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.database.VaultDatabase
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.database.entity.MediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupRestoreDeviceTest {
    @Test fun albumBackupSkipsExistingMediaAndRepeatedRestores(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SecretVaultApp
        val directory = File(app.cacheDir, "backup-restore-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        val sourceDb = VaultDatabase.buildInMemory(context)
        val restoredDb = VaultDatabase.buildInMemory(context)
        try {
            val albumA = AlbumEntity(id = "backup-test-a", name = "Backup test A", isSystem = false)
            val albumB = AlbumEntity(id = "backup-test-b", name = "Backup test B", isSystem = false)
            sourceDb.albumDao().insertAll(listOf(albumA, albumB))
            val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            val random = Random(42)
            bitmap.setPixels(IntArray(640 * 640) { random.nextInt() or 0xff000000.toInt() }, 0, 640, 0, 0, 640, 640)
            val jpeg = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
            bitmap.recycle()
            assertTrue("Fixture must cross backup record boundaries", jpeg.size > SvBackupCrypto.CHUNK_SIZE)
            for ((index, suffix) in listOf(11, 12).withIndex()) {
                val bytes = jpeg + suffix.toByte()
                val encrypted = File(directory, "source-$index.enc")
                encrypted.outputStream().use { app.cryptoEngine.encryptStream(ByteArrayInputStream(bytes), it) }
                sourceDb.mediaDao().insert(MediaEntity(
                    id = "backup-test-$index", filename = "same-name.jpg", originalName = "same-name.jpg",
                    mediaType = "PHOTO", mimeType = "image/jpeg", encryptedPath = encrypted.absolutePath,
                    thumbnailPath = null, sizeBytes = encrypted.length(), width = 640, height = 640,
                    albumId = if (index == 0) albumA.id else albumB.id, isFavorite = index == 0
                ))
            }
            val before = sourceDb.mediaDao().getAllList()
            val archive = Uri.fromFile(File(directory, "albums.svbackup"))
            val password = UUID.randomUUID().toString()
            val export = BackupExportManager(context, app.cryptoEngine, sourceDb)
                .exportBackup(archive, password, BackupScope.SelectedAlbums(setOf(albumA.id, albumB.id)))
            assertTrue(export.errorMessage, export.success)
            assertEquals(2, export.exportedItemsCount)

            val sameVault = BackupImportManager(context, app.cryptoEngine, sourceDb).importBackup(archive, password)
            assertTrue(sameVault.errorMessage, sameVault.success)
            assertEquals(0, sameVault.importedItemsCount)
            assertEquals(0, sameVault.importedAlbumsCount)
            assertEquals(before, sourceDb.mediaDao().getAllList())

            val importer = BackupImportManager(context, app.cryptoEngine, restoredDb)
            val first = importer.importBackup(archive, password)
            assertTrue(first.errorMessage, first.success)
            assertEquals(2, first.importedItemsCount)
            assertEquals(2, first.importedAlbumsCount)
            val restored = restoredDb.mediaDao().getAllList()
            val second = importer.importBackup(archive, password)
            assertTrue(second.errorMessage, second.success)
            assertEquals(0, second.importedItemsCount)
            assertEquals(0, second.importedAlbumsCount)
            assertEquals(restored, restoredDb.mediaDao().getAllList())
            assertEquals(2, restoredDb.albumDao().getAllAlbumsList().size)
        } finally {
            sourceDb.close()
            restoredDb.close()
            directory.walkBottomUp().forEach { it.delete() }
        }
    }
}
