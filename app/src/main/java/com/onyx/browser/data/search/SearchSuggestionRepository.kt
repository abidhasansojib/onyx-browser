package com.onyx.browser.data.search

import com.onyx.browser.data.local.HistoryDao
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.data.model.SearchSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SearchSuggestionRepository(private val historyDao: HistoryDao) {

    suspend fun getSuggestions(
        query: String,
        engine: SearchEngine
    ): List<SearchSuggestion> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<SearchSuggestion>()

        // 1. Fetch matching local history items (max 2)
        try {
            val historyMatches = historyDao.searchHistory(trimmed, limit = 2)
            for (item in historyMatches) {
                results.add(
                    SearchSuggestion(
                        title = item.title.ifBlank { item.url },
                        queryOrUrl = item.url,
                        isHistory = true
                    )
                )
            }
        } catch (_: Exception) {
        }

        // 2. Fetch remote search suggestions from active search engine
        val remoteSuggestions = fetchRemoteSuggestions(trimmed, engine)
        for (item in remoteSuggestions) {
            // Avoid duplicate entries
            if (results.none { it.queryOrUrl.equals(item, ignoreCase = true) }) {
                results.add(
                    SearchSuggestion(
                        title = item,
                        queryOrUrl = item,
                        isHistory = false
                    )
                )
            }
        }

        results.take(10)
    }

    private fun fetchRemoteSuggestions(query: String, engine: SearchEngine): List<String> {
        return try {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            when (engine) {
                SearchEngine.BRAVE -> fetchOpenSearchSuggestions(
                    "https://search.brave.com/api/suggest?q=$encodedQuery"
                )
                SearchEngine.GOOGLE -> fetchOpenSearchSuggestions(
                    "https://suggestqueries.google.com/complete/search?client=firefox&hl=en&q=$encodedQuery"
                )
                SearchEngine.DUCKDUCKGO -> fetchDuckDuckGoSuggestions(
                    "https://duckduckgo.com/ac/?q=$encodedQuery"
                )
                SearchEngine.BING -> fetchOpenSearchSuggestions(
                    "https://api.bing.com/osjson.aspx?query=$encodedQuery"
                )
                SearchEngine.STARTPAGE -> {
                    val startpage = fetchOpenSearchSuggestions(
                        "https://www.startpage.com/do/suggest?query=$encodedQuery&format=json"
                    )
                    if (startpage.isNotEmpty()) startpage else fetchDuckDuckGoSuggestions(
                        "https://duckduckgo.com/ac/?q=$encodedQuery"
                    )
                }
                SearchEngine.YAHOO -> fetchOpenSearchSuggestions(
                    "https://search.yahoo.com/sugg/ff?output=fxjson&command=$encodedQuery"
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Standard OpenSearch format: ["query", ["sugg1", "sugg2", ...]]
     * Used by Google, Brave, Bing, Yahoo, Startpage.
     */
    private fun fetchOpenSearchSuggestions(urlString: String): List<String> {
        val jsonString = httpGet(urlString) ?: return emptyList()
        val suggestions = mutableListOf<String>()
        try {
            val root = JSONArray(jsonString)
            if (root.length() > 1) {
                val array = root.optJSONArray(1)
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val text = array.optString(i)
                        if (!text.isNullOrBlank()) {
                            suggestions.add(text)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return suggestions
    }

    /**
     * DuckDuckGo format: [{"phrase": "suggestion1"}, {"phrase": "suggestion2"}]
     */
    private fun fetchDuckDuckGoSuggestions(urlString: String): List<String> {
        val jsonString = httpGet(urlString) ?: return emptyList()
        val suggestions = mutableListOf<String>()
        try {
            val root = JSONArray(jsonString)
            for (i in 0 until root.length()) {
                val obj = root.optJSONObject(i)
                val phrase = obj?.optString("phrase")
                if (!phrase.isNullOrBlank()) {
                    suggestions.add(phrase)
                }
            }
        } catch (_: Exception) {
        }
        return suggestions
    }

    private fun httpGet(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Android 14; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0"
                )
                setRequestProperty("Accept", "application/json, text/javascript, */*")
            }

            if (connection.responseCode in 200..299) {
                BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
