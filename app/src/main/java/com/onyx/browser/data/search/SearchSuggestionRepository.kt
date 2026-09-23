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

import com.onyx.browser.data.local.BookmarkDao

class SearchSuggestionRepository(
    private val historyDao: HistoryDao,
    private val bookmarkDao: BookmarkDao
) {

    // In-memory result cache keyed by (trimmed_query|engine_id).
    // Prevents flicker when the user repositions the cursor without changing text.
    @Volatile private var cacheKey: String = ""
    @Volatile private var cacheResult: List<SearchSuggestion> = emptyList()

    suspend fun getSuggestions(
        query: String,
        engine: SearchEngine
    ): List<SearchSuggestion> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()

        // Require at least 2 characters before any network traffic
        if (trimmed.length < 2) return@withContext emptyList()

        val key = "${trimmed}|${engine.id}"
        if (key == cacheKey) return@withContext cacheResult

        val results = mutableListOf<SearchSuggestion>()
        val seenKeys = mutableSetOf<String>()

        // 1. Local bookmarks first (max 2 items)
        try {
            val bookmarkMatches = bookmarkDao.searchBookmarks(trimmed, limit = 2)
            for (item in bookmarkMatches) {
                val norm = normalizeUrl(item.url)
                if (seenKeys.add(norm)) {
                    val isHttpUrl = item.url.startsWith("http://") || item.url.startsWith("https://")
                    results.add(
                        SearchSuggestion(
                            title = item.title.ifBlank { item.url },
                            queryOrUrl = item.url,
                            isBookmark = true,
                            isUrl = isHttpUrl
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 2. Local history (max 3 items total with bookmarks)

        try {
            val historyMatches = historyDao.searchHistory(trimmed, limit = 5)
            for (item in historyMatches) {
                val norm = normalizeUrl(item.url)
                if (seenKeys.add(norm)) {
                    val isHttpUrl = item.url.startsWith("http://") || item.url.startsWith("https://")
                    results.add(
                        SearchSuggestion(
                            title = item.title.ifBlank { item.url },
                            queryOrUrl = item.url,
                            isHistory = true,
                            isUrl = isHttpUrl
                        )
                    )
                    if (results.size >= 3) break
                }
            }
        } catch (_: Exception) {}

        // 3. Remote suggestions to fill up to 10 total
        val remoteNeeded = 10 - results.size
        if (remoteNeeded > 0) {
            val remote = fetchRemoteSuggestions(trimmed, engine)
            for (item in remote) {
                val norm = item.lowercase().trim()
                if (seenKeys.add(norm)) {
                    val looksLikeDomain = !item.contains(" ") && item.contains(".") &&
                        !item.startsWith("http://") && !item.startsWith("https://")
                    results.add(
                        SearchSuggestion(
                            title = item,
                            queryOrUrl = item,
                            isHistory = false,
                            isDomain = looksLikeDomain
                        )
                    )
                    if (results.size >= 10) break
                }
            }
        }

        val final = results.take(10)
        cacheKey = key
        cacheResult = final
        final
    }

    private fun normalizeUrl(url: String): String = try {
        val u = java.net.URI(url)
        (u.host ?: url).lowercase().removePrefix("www.")
    } catch (_: Exception) { url.lowercase() }

    private fun fetchRemoteSuggestions(query: String, engine: SearchEngine): List<String> {
        return try {
            val q = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            when (engine.id) {
                SearchEngine.BRAVE.id -> fetchOpenSearch("https://search.brave.com/api/suggest?q=$q")
                SearchEngine.GOOGLE.id -> fetchOpenSearch("https://suggestqueries.google.com/complete/search?client=firefox&hl=en&q=$q")
                SearchEngine.DUCKDUCKGO.id -> fetchDuckDuckGo("https://duckduckgo.com/ac/?q=$q")
                SearchEngine.BING.id -> fetchOpenSearch("https://api.bing.com/osjson.aspx?query=$q")
                SearchEngine.STARTPAGE.id -> fetchOpenSearch("https://www.startpage.com/do/suggest?query=$q&format=json").ifEmpty {
                    fetchDuckDuckGo("https://duckduckgo.com/ac/?q=$q")
                }
                SearchEngine.YAHOO.id -> fetchOpenSearch("https://search.yahoo.com/sugg/ff?output=fxjson&command=$q")
                else -> fetchOpenSearch("https://suggestqueries.google.com/complete/search?client=firefox&hl=en&q=$q")
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchOpenSearch(urlString: String): List<String> {
        val json = httpGet(urlString) ?: return emptyList()
        return try {
            val root = JSONArray(json)
            if (root.length() > 1) {
                val arr = root.optJSONArray(1) ?: return emptyList()
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchDuckDuckGo(urlString: String): List<String> {
        val json = httpGet(urlString) ?: return emptyList()
        return try {
            val root = JSONArray(json)
            (0 until root.length()).mapNotNull { root.optJSONObject(it)?.optString("phrase")?.takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) { emptyList() }
    }

    private fun httpGet(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                setRequestProperty("Accept", "application/json, text/javascript, */*")
            }
            if (conn.responseCode in 200..299)
                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
            else null
        } catch (_: Exception) { null }
        finally { conn?.disconnect() }
    }
}
