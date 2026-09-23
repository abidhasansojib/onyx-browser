package com.onyx.browser.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "tabs")
data class TabItem(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    var url: String = "",
    var title: String = "New Tab",
    var isIncognito: Boolean = false,
    var position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    var lastAccessedAt: Long = System.currentTimeMillis(),
    var isHibernated: Boolean = false
)
