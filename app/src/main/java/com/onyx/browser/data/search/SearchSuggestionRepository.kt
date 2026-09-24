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

    companion object {
        /**
         * Checks whether an input query represents a navigable domain or URL rather than a search phrase.
         */
        fun isLikelyDomainOrUrl(input: String): Boolean {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.contains(" ")) return false
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("file://", ignoreCase = true) ||
                trimmed.startsWith("about:", ignoreCase = true) ||
                trimmed.startsWith("data:", ignoreCase = true) ||
                trimmed.startsWith("www.", ignoreCase = true) ||
                trimmed.startsWith("localhost", ignoreCase = true)
            ) {
                return true
            }

            // IPv4 address check (e.g. 192.168.1.1 or 127.0.0.1:8080/path)
            if (trimmed.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?(/.*)?$"))) {
                return true
            }

            // Domain with valid TLD (at least 2 letters after the last dot before port/path/query)
            // Example: "google.com", "madebyevan.com/webgl-water/", "sub.domain.co.uk:8080/path"
            val hostPart = trimmed.substringBefore('/').substringBefore(':').substringBefore('?')
            val lastDot = hostPart.lastIndexOf('.')
            if (lastDot > 0 && lastDot < hostPart.length - 1) {
                val tld = hostPart.substring(lastDot + 1)
                if (tld.length >= 2 && tld.all { it.isLetter() }) {
                    return true
                }
            }
            return false
        }
    }

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

        // 0. Direct Domain / URL Suggestion: If the typed input is a domain or URL, display it first!
        val looksLikeDirectUrl = isLikelyDomainOrUrl(trimmed)
        if (looksLikeDirectUrl) {
            val norm = normalizeUrl(trimmed)
            seenKeys.add(norm)
            val isCompleteUrl = trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("file://", ignoreCase = true)
            val fullNavUrl = if (isCompleteUrl) trimmed else "https://$trimmed"
            results.add(
                SearchSuggestion(
                    title = trimmed,
                    queryOrUrl = fullNavUrl,
                    isDomain = !isCompleteUrl,
                    isUrl = true
                )
            )
        }

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
                    val isCompleteUrl = item.startsWith("http://", ignoreCase = true) ||
                        item.startsWith("https://", ignoreCase = true)
                    val fullNavUrl = when {
                        isCompleteUrl -> item
                        looksLikeDomain -> "https://$item"
                        else -> item
                    }
                    results.add(
                        SearchSuggestion(
                            title = item,
                            queryOrUrl = fullNavUrl,
                            isHistory = false,
                            isDomain = looksLikeDomain,
                            isUrl = isCompleteUrl
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
        val trimmed = url.trim()
        val withScheme = if (!trimmed.contains("://")) "https://$trimmed" else trimmed
        val u = java.net.URI(withScheme)
        val host = (u.host ?: "").lowercase().removePrefix("www.")
        val path = u.rawPath ?: ""
        "$host$path".trimEnd('/')
    } catch (_: Exception) { url.lowercase().trimEnd('/') }

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
