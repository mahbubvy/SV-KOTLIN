package com.secretvault.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.secretvault.app.core.crypto.KeyStoreManager
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.database.DatabaseKeyManager
import com.secretvault.app.core.database.VaultDatabase
import com.secretvault.app.core.image.EncryptedThumbnailFetcher
import com.secretvault.app.core.image.EncryptedThumbnailKeyer
import com.secretvault.app.core.security.PinManager
import com.secretvault.app.core.security.SessionManager
import com.secretvault.app.data.repository.AlbumRepository
import com.secretvault.app.data.repository.MediaRepository
import com.secretvault.app.data.repository.VaultAlbumRepository
import com.secretvault.app.data.repository.VaultMediaRepository
import net.sqlcipher.database.SQLiteDatabase
import java.io.File

class SecretVaultApp : Application(), ImageLoaderFactory {

    lateinit var keyStoreManager: KeyStoreManager private set
    lateinit var cryptoEngine: VaultCryptoEngine private set
    lateinit var pinManager: PinManager private set
    lateinit var sessionManager: SessionManager private set
    lateinit var database: VaultDatabase private set
    lateinit var mediaRepository: MediaRepository private set
    lateinit var albumRepository: AlbumRepository private set
    lateinit var mediaSaveQueue: com.secretvault.app.core.worker.MediaSaveQueue private set
    lateinit var batchImportManager: com.secretvault.app.core.worker.BatchImportManager private set
    lateinit var ephemeralShareManager: com.secretvault.app.core.share.EphemeralShareManager private set
    lateinit var backupImportManager: com.secretvault.app.core.backup.BackupImportManager private set
    lateinit var backupExportManager: com.secretvault.app.core.backup.BackupExportManager private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Initialize SQLCipher native libraries
        SQLiteDatabase.loadLibs(this)

        // 2. Initialize Security & Crypto singletons
        keyStoreManager = KeyStoreManager()
        cryptoEngine = VaultCryptoEngine(keyStoreManager)
        pinManager = PinManager(this)
        sessionManager = SessionManager()

        // 3. Initialize Database with Hardware-derived Passphrase
        val dbPassphrase = DatabaseKeyManager(this, keyStoreManager).getOrCreatePassphrase()
        database = VaultDatabase.getInstance(this, dbPassphrase)

        // 4. Initialize Repositories
        mediaRepository = VaultMediaRepository(database.mediaDao(), cryptoEngine)
        albumRepository = VaultAlbumRepository(database.albumDao(), database.mediaDao())

        // 5. Initialize Media Workers & Managers
        mediaSaveQueue = com.secretvault.app.core.worker.MediaSaveQueue(this, cryptoEngine, mediaRepository)
        batchImportManager = com.secretvault.app.core.worker.BatchImportManager(this, cryptoEngine, mediaRepository)
        ephemeralShareManager = com.secretvault.app.core.share.EphemeralShareManager(this, cryptoEngine)
        backupImportManager = com.secretvault.app.core.backup.BackupImportManager(this, cryptoEngine, database)
        backupExportManager = com.secretvault.app.core.backup.BackupExportManager(this, cryptoEngine, database)

        // Purge any residual decrypted shared files from previous sessions
        ephemeralShareManager.purgeAllSharedFiles()

        // 6. Ensure vault media directories exist in app-private storage
        getVaultMediaDir()
        getVaultThumbsDir()
    }

    fun getVaultMediaDir(): File {
        val dir = File(filesDir, "vault_media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getVaultThumbsDir(): File {
        val dir = File(filesDir, "vault_thumbs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(EncryptedThumbnailKeyer())
                add(EncryptedThumbnailFetcher.Factory(cryptoEngine))
            }
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: SecretVaultApp
            private set
    }
}
