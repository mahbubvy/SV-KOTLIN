package com.secretvault.app.core.database

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Base64
import com.secretvault.app.core.crypto.KeyStoreManager
import com.secretvault.app.core.crypto.SecureMemory
import net.sqlcipher.database.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Stores a random SQLCipher passphrase encrypted by the vault's Android Keystore key. */
class DatabaseKeyManager(
    context: Context,
    private val keyStoreManager: KeyStoreManager,
    private val databaseName: String = VaultDatabase.DB_NAME
) {
    private val appContext = context.applicationContext
    private val databaseFile: File = appContext.getDatabasePath(databaseName)
    private val preferences = appContext.getSharedPreferences("${databaseName}_key", Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreatePassphrase(): ByteArray {
        val encoded = preferences.getString(WRAPPED_KEY, null)
        val passphrase = if (encoded == null) createPassphrase() else unwrap(Base64.decode(encoded, Base64.NO_WRAP))

        if (encoded == null) {
            val migrationRequired = databaseFile.exists()
            val wrapped = Base64.encodeToString(wrap(passphrase), Base64.NO_WRAP)
            commitPreferences(wrapped, !migrationRequired)
            if (migrationRequired) migrateExistingDatabase(passphrase)
        } else if (!preferences.getBoolean(MIGRATION_COMPLETE, false)) {
            migrateExistingDatabase(passphrase)
        }
        return passphrase
    }

    private fun createPassphrase(): ByteArray {
        val random = ByteArray(PASSPHRASE_BYTES)
        SecureRandom().nextBytes(random)
        return try {
            Base64.encode(random, Base64.NO_WRAP)
        } finally {
            SecureMemory.wipe(random)
        }
    }

    private fun commitPreferences(wrapped: String, migrationComplete: Boolean) {
        if (!preferences.edit()
                .putString(WRAPPED_KEY, wrapped)
                .putBoolean(MIGRATION_COMPLETE, migrationComplete)
                .commit()
        ) {
            throw IOException("Could not persist the encrypted database key")
        }
    }

    private fun migrateExistingDatabase(passphrase: ByteArray) {
        if (!databaseFile.exists()) {
            throw IOException("Database key migration is pending, but the vault database is missing")
        }
        val legacyPassphrase = MessageDigest.getInstance("SHA-256")
            .digest(keyStoreManager.getOrCreateMasterKey().encoded ?: LEGACY_FALLBACK.toByteArray())
        try {
            val legacyDatabase = try {
                openAndVerify(legacyPassphrase)
            } catch (legacyFailure: SQLiteException) {
                try {
                    openAndVerify(passphrase).close()
                } catch (newKeyFailure: SQLiteException) {
                    legacyFailure.addSuppressed(newKeyFailure)
                    throw legacyFailure
                }
                markMigrationComplete()
                return
            }
            val newPassword = passphrase.toString(StandardCharsets.US_ASCII).toCharArray()
            try {
                legacyDatabase.changePassword(newPassword)
            } finally {
                SecureMemory.wipe(newPassword)
                legacyDatabase.close()
            }
            openAndVerify(passphrase).close()
            markMigrationComplete()
        } finally {
            SecureMemory.wipe(legacyPassphrase)
        }
    }

    private fun openAndVerify(passphrase: ByteArray): SQLiteDatabase {
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
            null
        )
        try {
            database.rawQuery("SELECT count(*) FROM sqlite_master", null).use { cursor ->
                if (!cursor.moveToFirst()) throw SQLiteException("Could not verify encrypted database")
            }
            return database
        } catch (failure: Exception) {
            database.close()
            throw failure
        }
    }

    private fun markMigrationComplete() {
        if (!preferences.edit().putBoolean(MIGRATION_COMPLETE, true).commit()) {
            throw IOException("Could not finish database key migration")
        }
    }

    private fun wrap(passphrase: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyStoreManager.getOrCreateMasterKey())
        cipher.updateAAD(databaseName.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(passphrase)
        return cipher.iv + encrypted
    }

    private fun unwrap(envelope: ByteArray): ByteArray {
        if (envelope.size < GCM_IV_BYTES + GCM_TAG_BITS / 8) {
            throw IOException("Encrypted database key is incomplete")
        }
        val iv = envelope.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = envelope.copyOfRange(GCM_IV_BYTES, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyStoreManager.getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(databaseName.toByteArray(StandardCharsets.UTF_8))
        return cipher.doFinal(encrypted)
    }

    private companion object {
        const val WRAPPED_KEY = "wrapped_database_passphrase"
        const val MIGRATION_COMPLETE = "database_passphrase_migration_complete"
        const val PASSPHRASE_BYTES = 32
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val LEGACY_FALLBACK = "vault_db_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
