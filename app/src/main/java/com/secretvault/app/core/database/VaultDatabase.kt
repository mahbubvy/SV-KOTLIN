package com.secretvault.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.secretvault.app.core.database.dao.AlbumDao
import com.secretvault.app.core.database.dao.MediaDao
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.database.entity.MediaEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        MediaEntity::class,
        AlbumEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun albumDao(): AlbumDao

    companion object {
        const val DB_NAME = "secret_vault_encrypted.db"

        @Volatile
        private var INSTANCE: VaultDatabase? = null

        fun getInstance(context: Context, passphrase: ByteArray): VaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext, passphrase).also { db -> 
                    INSTANCE = db 
                    CoroutineScope(Dispatchers.IO).launch {
                        val systemAlbums = listOf(
                            AlbumEntity(id = AlbumEntity.ALBUM_CAMERA_ID, name = "Camera", isSystem = true),
                            AlbumEntity(id = AlbumEntity.ALBUM_IMPORTS_ID, name = "Imports", isSystem = true),
                            AlbumEntity(id = AlbumEntity.ALBUM_UNSORTED_ID, name = "Unsorted", isSystem = true)
                        )
                        db.albumDao().insertAll(systemAlbums)
                    }
                }
            }
        }

        private fun buildDatabase(context: Context, passphrase: ByteArray): VaultDatabase {
            val factory = SupportFactory(passphrase)
            return Room.databaseBuilder(
                context,
                VaultDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * For in-memory testing without SQLCipher encryption overhead.
         */
        fun buildInMemory(context: Context): VaultDatabase {
            return Room.inMemoryDatabaseBuilder(
                context,
                VaultDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}
