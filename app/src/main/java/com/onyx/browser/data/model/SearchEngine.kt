package com.onyx.browser.data.model

import androidx.annotation.DrawableRes
import com.onyx.browser.R
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class SearchEngine(
    val id: String,
    val displayName: String,
    val queryUrl: String,
    val homeUrl: String,
    @DrawableRes val iconResId: Int = R.drawable.ic_web,
    val keyword: String = "",
    val isCustom: Boolean = false
) {
    fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        return if (queryUrl.contains("%s")) {
            queryUrl.replace("%s", encoded)
        } else if (queryUrl.contains("?")) {
            "$queryUrl&$encoded"
        } else {
            "$queryUrl?q=$encoded"
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", displayName)
            put("queryUrl", queryUrl)
            put("homeUrl", homeUrl)
            put("keyword", keyword)
            put("isCustom", true)
        }
    }

    companion object {
        val BRAVE = SearchEngine(
            id = "brave",
            displayName = "Brave Search",
            queryUrl = "https://search.brave.com/search?q=%s",
            homeUrl = "https://search.brave.com",
            iconResId = R.drawable.ic_engine_brave,
            keyword = ":b",
            isCustom = false
        )

        val GOOGLE = SearchEngine(
            id = "google",
            displayName = "Google",
            queryUrl = "https://www.google.com/search?q=%s",
            homeUrl = "https://www.google.com",
            iconResId = R.drawable.ic_engine_google,
            keyword = ":g",
            isCustom = false
        )

        val DUCKDUCKGO = SearchEngine(
            id = "duckduckgo",
            displayName = "DuckDuckGo",
            queryUrl = "https://duckduckgo.com/?q=%s",
            homeUrl = "https://duckduckgo.com",
            iconResId = R.drawable.ic_engine_duckduckgo,
            keyword = ":d",
            isCustom = false
        )

        val BING = SearchEngine(
            id = "bing",
            displayName = "Bing",
            queryUrl = "https://www.bing.com/search?q=%s",
            homeUrl = "https://www.bing.com",
            iconResId = R.drawable.ic_engine_bing,
            keyword = ":bi",
            isCustom = false
        )

        val STARTPAGE = SearchEngine(
            id = "startpage",
            displayName = "Startpage",
            queryUrl = "https://www.startpage.com/sp/search?query=%s",
            homeUrl = "https://www.startpage.com",
            iconResId = R.drawable.ic_engine_startpage,
            keyword = ":s",
            isCustom = false
        )

        val YAHOO = SearchEngine(
            id = "yahoo",
            displayName = "Yahoo",
            queryUrl = "https://search.yahoo.com/search?p=%s",
            homeUrl = "https://www.yahoo.com",
            iconResId = R.drawable.ic_engine_yahoo,
            keyword = ":y",
            isCustom = false
        )

        val BUILT_IN: List<SearchEngine> = listOf(
            GOOGLE, BRAVE, DUCKDUCKGO, BING, STARTPAGE, YAHOO
        )

        val entries: List<SearchEngine> get() = BUILT_IN

        fun fromId(id: String?, customEngines: List<SearchEngine> = emptyList()): SearchEngine {
            if (id.isNullOrBlank()) return GOOGLE
            val builtIn = BUILT_IN.firstOrNull { it.id.equals(id, ignoreCase = true) }
            if (builtIn != null) return builtIn
            return customEngines.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GOOGLE
        }

        fun fromJson(json: JSONObject): SearchEngine {
            return SearchEngine(
                id = json.optString("id", UUID.randomUUID().toString()),
                displayName = json.optString("name", "Custom Engine"),
                queryUrl = json.optString("queryUrl", ""),
                homeUrl = json.optString("homeUrl", ""),
                iconResId = R.drawable.ic_web,
                keyword = json.optString("keyword", ""),
                isCustom = true
            )
        }
    }
}
