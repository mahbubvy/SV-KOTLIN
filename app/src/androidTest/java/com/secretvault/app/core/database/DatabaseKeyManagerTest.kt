package com.secretvault.app.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.core.crypto.KeyStoreManager
import net.sqlcipher.database.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class DatabaseKeyManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "database-key-migration-test.db"
    private val keyStoreManager = KeyStoreManager()

    @Before
    fun setUp() {
        SQLiteDatabase.loadLibs(context)
        context.deleteDatabase(databaseName)
        context.getSharedPreferences("${databaseName}_key", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
        context.getSharedPreferences("${databaseName}_key", 0).edit().clear().commit()
    }

    @Test
    fun existingDatabaseIsRekeyedAndNewPassphraseSurvivesRestart() {
        val masterKey = keyStoreManager.getOrCreateMasterKey()
        val legacyPassphrase = MessageDigest.getInstance("SHA-256")
            .digest(masterKey.encoded ?: "vault_db_key".toByteArray())
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            legacyPassphrase,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            null,
            null
        )
        database.execSQL("CREATE TABLE migration_test(value TEXT NOT NULL)")
        database.execSQL("INSERT INTO migration_test(value) VALUES ('preserved')")
        database.close()

        val passphrase = DatabaseKeyManager(context, keyStoreManager, databaseName).getOrCreatePassphrase()
        assertNotEquals(legacyPassphrase.toList(), passphrase.toList())
        val reopened = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
            null
        )
        reopened.rawQuery("SELECT value FROM migration_test", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("preserved", cursor.getString(0))
        }
        reopened.close()

        val afterRestart = DatabaseKeyManager(context, keyStoreManager, databaseName).getOrCreatePassphrase()
        assertArrayEquals(passphrase, afterRestart)

        context.getSharedPreferences("${databaseName}_key", 0).edit()
            .putBoolean("database_passphrase_migration_complete", false).commit()
        val afterInterruptedCommit = DatabaseKeyManager(context, keyStoreManager, databaseName).getOrCreatePassphrase()
        assertArrayEquals(passphrase, afterInterruptedCommit)
    }
}
