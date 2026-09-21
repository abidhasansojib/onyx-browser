package com.onyx.browser.data.model

data class SearchSuggestion(
    val title: String,
    val queryOrUrl: String,
    val isHistory: Boolean = false,
    val isBookmark: Boolean = false
)
