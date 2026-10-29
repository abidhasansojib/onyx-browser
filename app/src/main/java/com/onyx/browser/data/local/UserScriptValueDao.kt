package com.onyx.browser.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.onyx.browser.data.model.UserScriptValueItem

@Dao
interface UserScriptValueDao {
    @Query("SELECT value FROM user_script_values WHERE scriptId = :scriptId AND key = :key")
    fun getValue(scriptId: Long, key: String): String?

    @Query("SELECT key FROM user_script_values WHERE scriptId = :scriptId")
    fun listKeys(scriptId: Long): List<String>

    @Query("SELECT * FROM user_script_values WHERE scriptId = :scriptId")
    fun getAllForScript(scriptId: Long): List<UserScriptValueItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setValue(value: UserScriptValueItem)

    @Query("DELETE FROM user_script_values WHERE scriptId = :scriptId AND key = :key")
    fun deleteValue(scriptId: Long, key: String)

    @Query("DELETE FROM user_script_values WHERE scriptId = :scriptId")
    fun deleteAllForScript(scriptId: Long)
}
