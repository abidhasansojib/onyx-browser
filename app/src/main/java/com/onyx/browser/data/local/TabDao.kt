package com.onyx.browser.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.onyx.browser.data.model.TabItem
import kotlinx.coroutines.flow.Flow

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs WHERE isIncognito = 0 ORDER BY position ASC")
    fun getAllNormalTabsFlow(): Flow<List<TabItem>>

    @Query("SELECT * FROM tabs WHERE isIncognito = 0 ORDER BY position ASC")
    suspend fun getAllNormalTabs(): List<TabItem>

    @Query("SELECT * FROM tabs WHERE id = :tabId LIMIT 1")
    suspend fun getTabById(tabId: String): TabItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTab(tab: TabItem)

    @Update
    suspend fun updateTab(tab: TabItem)

    @Delete
    suspend fun deleteTab(tab: TabItem)

    @Query("DELETE FROM tabs WHERE id = :tabId")
    suspend fun deleteTabById(tabId: String)

    @Query("DELETE FROM tabs WHERE isIncognito = 0")
    suspend fun clearNormalTabs()

    @Query("SELECT COUNT(*) FROM tabs WHERE isIncognito = 0")
    suspend fun getNormalTabsCount(): Int
}
