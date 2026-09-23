package com.onyx.browser.data.model

data class SearchSuggestion(
    val title: String,
    val queryOrUrl: String,
    val isHistory: Boolean = false,
    val isBookmark: Boolean = false,
    /** True when the suggestion looks like a navigable domain (e.g. "github.com"). */
    val isDomain: Boolean = false,
    /** True when the suggestion is a complete URL (e.g. starts with http/https). */
    val isUrl: Boolean = false,
    val isClipboard: Boolean = false
)
