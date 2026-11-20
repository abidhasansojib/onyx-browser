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

    @Query("SELECT * FROM tabs WHERE isIncognito = 0 AND isArchived = 1 ORDER BY archivedAt DESC")
    suspend fun getArchivedNormalTabs(): List<TabItem>

    @Query("SELECT * FROM tabs WHERE id = :tabId LIMIT 1")
    suspend fun getTabById(tabId: String): TabItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTab(tab: TabItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTabs(tabs: List<TabItem>)

    @Update
    suspend fun updateTab(tab: TabItem)

    @Delete
    suspend fun deleteTab(tab: TabItem)

    @Query("DELETE FROM tabs WHERE id = :tabId")
    suspend fun deleteTabById(tabId: String)

    @Query("DELETE FROM tabs WHERE isIncognito = 0")
    suspend fun clearNormalTabs()

    @Query("DELETE FROM tabs WHERE isIncognito = 0 AND isArchived = 1")
    suspend fun clearArchivedTabs()

    @Query("SELECT COUNT(*) FROM tabs WHERE isIncognito = 0")
    suspend fun getNormalTabsCount(): Int

    @Query("UPDATE tabs SET parentId = :newParentId WHERE parentId = :oldParentId")
    suspend fun updateParentIdForChildren(oldParentId: String, newParentId: String?)

    @Query("UPDATE tabs SET groupId = NULL WHERE groupId = :groupId")
    suspend fun ungroupTabsByGroupId(groupId: String)

    @Query("DELETE FROM tabs WHERE groupId = :groupId")
    suspend fun deleteTabsByGroupId(groupId: String)

    @Query("UPDATE tabs SET isArchived = :isArchived, archivedAt = :archivedAt, isHibernated = :isHibernated WHERE id = :tabId")
    suspend fun setTabArchived(tabId: String, isArchived: Boolean, archivedAt: Long, isHibernated: Boolean)
}
