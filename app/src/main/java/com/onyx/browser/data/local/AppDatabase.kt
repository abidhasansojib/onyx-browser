package com.onyx.browser.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.data.model.HistoryItem
import com.onyx.browser.data.model.TabItem
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TabItem::class, HistoryItem::class, BookmarkItem::class, DownloadItem::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        private const val TAG = "AppDatabase"

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN downloadId INTEGER NOT NULL DEFAULT -1")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            val appContext = context.applicationContext
            return try {
                buildEncryptedDatabase(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize encrypted database, attempting recovery reset", e)
                try {
                    appContext.deleteDatabase("onyx_browser_secure.db")
                    buildEncryptedDatabase(appContext)
                } catch (fallbackEx: Exception) {
                    Log.e(TAG, "Encrypted database recovery failed, falling back to clean fallback database", fallbackEx)
                    Room.databaseBuilder(
                        appContext,
                        AppDatabase::class.java,
                        "onyx_browser_fallback.db"
                    )
                        .addMigrations(MIGRATION_2_3)
                        .fallbackToDestructiveMigration()
                        .build()
                }
            }
        }

        private fun buildEncryptedDatabase(context: Context): AppDatabase {
            // Load SQLCipher native libraries safely
            SQLiteDatabase.loadLibs(context)

            // Retrieve or generate KeyStore-backed AES-256 passphrase
            val passphrase = SecureDatabaseKeyProvider.getOrCreatePassphrase(context)
            val factory = SupportFactory(passphrase)

            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "onyx_browser_secure.db"
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
