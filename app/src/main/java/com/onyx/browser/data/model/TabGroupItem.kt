package com.onyx.browser.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "tab_groups")
data class TabGroupItem(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Tab Group",
    var colorHex: String = DEFAULT_COLOR_ORANGE,
    var position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    var isCollapsed: Boolean = false
) {
    companion object {
        const val DEFAULT_COLOR_ORANGE = "#FF5C38"
        const val COLOR_BLUE = "#2563EB"
        const val COLOR_GREEN = "#10B981"
        const val COLOR_VIOLET = "#8B5CF6"
        const val COLOR_AMBER = "#F59E0B"
        const val COLOR_PINK = "#EC4899"
        const val COLOR_CYAN = "#06B6D4"
        const val COLOR_CRIMSON = "#EF4444"

        val GROUP_COLORS = listOf(
            DEFAULT_COLOR_ORANGE,
            COLOR_BLUE,
            COLOR_GREEN,
            COLOR_VIOLET,
            COLOR_AMBER,
            COLOR_PINK,
            COLOR_CYAN,
            COLOR_CRIMSON
        )
    }
}
