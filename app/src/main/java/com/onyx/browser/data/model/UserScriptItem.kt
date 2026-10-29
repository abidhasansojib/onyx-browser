package com.onyx.browser.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_scripts")
data class UserScriptItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val sourceUrl: String? = null,
    val order: Int = 0,
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val matchSummary: String = "",
    val runAt: String = "document-end"
)
