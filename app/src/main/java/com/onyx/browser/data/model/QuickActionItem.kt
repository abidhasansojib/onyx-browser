package com.onyx.browser.data.model

import com.onyx.browser.R

data class QuickActionItem(
    val id: String,
    val titleRes: Int,
    val iconRes: Int
) {
    companion object {
        const val ID_BOOKMARKS = "bookmarks"
        const val ID_HISTORY = "history"
        const val ID_DOWNLOADS = "downloads"
        const val ID_ADD = "add"

        val ALL_ACTIONS: Map<String, QuickActionItem> = mapOf(
            ID_BOOKMARKS to QuickActionItem(ID_BOOKMARKS, R.string.bookmarks, R.drawable.ic_bookmark),
            ID_HISTORY to QuickActionItem(ID_HISTORY, R.string.history, R.drawable.ic_history),
            ID_DOWNLOADS to QuickActionItem(ID_DOWNLOADS, R.string.downloads, R.drawable.ic_download),
            ID_ADD to QuickActionItem(ID_ADD, R.string.add, R.drawable.ic_add)
        )

        // Default order puts Add button in 4th position: Bookmarks, History, Downloads, Add
        val DEFAULT_ORDER: List<String> = listOf(ID_BOOKMARKS, ID_HISTORY, ID_DOWNLOADS, ID_ADD)

        fun getOrderedItems(order: List<String>): List<QuickActionItem> {
            val valid = order.filter { ALL_ACTIONS.containsKey(it) }.distinct().toMutableList()
            // Add any missing default actions to the end
            for (id in DEFAULT_ORDER) {
                if (!valid.contains(id)) {
                    valid.add(id)
                }
            }
            return valid.mapNotNull { ALL_ACTIONS[it] }
        }
    }
}
