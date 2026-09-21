package com.onyx.browser.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.data.model.HistoryItem
import com.onyx.browser.data.model.TabItem
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [TabItem::class, HistoryItem::class, BookmarkItem::class, DownloadItem::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Load SQLCipher native libraries
                SQLiteDatabase.loadLibs(context.applicationContext)

                // Retrieve or generate the KeyStore-backed AES-256 passphrase
                val passphrase = SecureDatabaseKeyProvider.getOrCreatePassphrase(
                    context.applicationContext
                )
                val factory = SupportFactory(passphrase)

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "onyx_browser_secure.db"
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration()
                    .build()

                // Zero out passphrase from memory immediately after DB is opened
                passphrase.fill(0)

                INSTANCE = instance
                instance
            }
        }
    }
}
