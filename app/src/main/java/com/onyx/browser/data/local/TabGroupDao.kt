package com.onyx.browser.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.onyx.browser.data.model.TabGroupItem
import kotlinx.coroutines.flow.Flow

@Dao
interface TabGroupDao {
    @Query("SELECT * FROM tab_groups ORDER BY position ASC, createdAt ASC")
    fun getAllGroupsFlow(): Flow<List<TabGroupItem>>

    @Query("SELECT * FROM tab_groups ORDER BY position ASC, createdAt ASC")
    suspend fun getAllGroups(): List<TabGroupItem>

    @Query("SELECT * FROM tab_groups WHERE id = :groupId LIMIT 1")
    suspend fun getGroupById(groupId: String): TabGroupItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: TabGroupItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<TabGroupItem>)

    @Update
    suspend fun updateGroup(group: TabGroupItem)

    @Delete
    suspend fun deleteGroup(group: TabGroupItem)

    @Query("DELETE FROM tab_groups WHERE id = :groupId")
    suspend fun deleteGroupById(groupId: String)

    @Query("DELETE FROM tab_groups")
    suspend fun clearAllGroups()
}
