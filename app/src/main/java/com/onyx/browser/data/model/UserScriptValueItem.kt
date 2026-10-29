package com.onyx.browser.data.model

import androidx.room.Entity

@Entity(tableName = "user_script_values", primaryKeys = ["scriptId", "key"])
data class UserScriptValueItem(
    val scriptId: Long,
    val key: String,
    val value: String
)
