package com.onyx.browser.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.onyx.browser.data.model.UserScriptItem

@Dao
interface UserScriptDao {
    @Query("SELECT * FROM user_scripts ORDER BY `order` ASC, id ASC")
    suspend fun getAll(): List<UserScriptItem>

    @Query("SELECT * FROM user_scripts WHERE id = :id")
    suspend fun getById(id: Long): UserScriptItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(script: UserScriptItem): Long

    @Update
    suspend fun update(script: UserScriptItem)

    @Delete
    suspend fun delete(script: UserScriptItem)

    @Query("DELETE FROM user_scripts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE user_scripts SET enabled = :enabled WHERE id = :id")
    suspend fun updateEnabled(id: Long, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM user_scripts WHERE enabled = 1")
    suspend fun countEnabled(): Int
}
