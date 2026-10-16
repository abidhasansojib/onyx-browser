package com.onyx.browser.ui.history

import com.onyx.browser.data.model.HistoryItem

sealed class HistoryListItem {
    abstract val id: String

    data class Header(
        val dayKey: String,
        val title: String,
        val isExpanded: Boolean,
        val count: Int
    ) : HistoryListItem() {
        override val id: String = "header_$dayKey"
    }

    data class Item(
        val historyItem: HistoryItem,
        val dayKey: String,
        val isLastInGroup: Boolean
    ) : HistoryListItem() {
        override val id: String = "item_${historyItem.id}"
    }
}
