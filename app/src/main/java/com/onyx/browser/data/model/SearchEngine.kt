package com.onyx.browser.data.model

import androidx.annotation.DrawableRes
import com.onyx.browser.R
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class SearchEngine(
    val id: String,
    val displayName: String,
    val queryUrl: String,
    val homeUrl: String,
    @DrawableRes val iconResId: Int
) {
    BRAVE(
        id = "brave",
        displayName = "Brave Search",
        queryUrl = "https://search.brave.com/search?q=%s",
        homeUrl = "https://search.brave.com",
        iconResId = R.drawable.ic_engine_brave
    ),
    GOOGLE(
        id = "google",
        displayName = "Google",
        queryUrl = "https://www.google.com/search?q=%s",
        homeUrl = "https://www.google.com",
        iconResId = R.drawable.ic_engine_google
    ),
    DUCKDUCKGO(
        id = "duckduckgo",
        displayName = "DuckDuckGo",
        queryUrl = "https://duckduckgo.com/?q=%s",
        homeUrl = "https://duckduckgo.com",
        iconResId = R.drawable.ic_engine_duckduckgo
    ),
    BING(
        id = "bing",
        displayName = "Bing",
        queryUrl = "https://www.bing.com/search?q=%s",
        homeUrl = "https://www.bing.com",
        iconResId = R.drawable.ic_engine_bing
    ),
    STARTPAGE(
        id = "startpage",
        displayName = "Startpage",
        queryUrl = "https://www.startpage.com/sp/search?query=%s",
        homeUrl = "https://www.startpage.com",
        iconResId = R.drawable.ic_engine_startpage
    ),
    YAHOO(
        id = "yahoo",
        displayName = "Yahoo",
        queryUrl = "https://search.yahoo.com/search?p=%s",
        homeUrl = "https://www.yahoo.com",
        iconResId = R.drawable.ic_engine_yahoo
    );

    fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        return String.format(queryUrl, encoded)
    }

    companion object {
        fun fromId(id: String?): SearchEngine {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BRAVE
        }
    }
}
