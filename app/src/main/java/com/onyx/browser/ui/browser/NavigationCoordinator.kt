package com.onyx.browser.ui.browser

import android.app.SearchManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.data.search.SearchSuggestionRepository
import com.onyx.browser.web.LocalFileLoader
import java.io.File

/**
 * Coordinates external Intent URL extraction, omnibox input resolution (URLs, keyword search
 * shortcuts, and search queries), feed/reel detection, and pinning webpage shortcuts to the
 * launcher home screen.
 */
class NavigationCoordinator(
    private val activity: MainActivity,
    private val preferences: BrowserPreferences
) {
    fun isLikelyUrl(input: String): Boolean {
        return SearchSuggestionRepository.isLikelyDomainOrUrl(input)
    }

    fun isReelOrFeedUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.contains("facebook.com") && (url.contains("/reel") || url.contains("/watch") || url.contains("/videos"))) {
            return true
        }
        if (url.contains("instagram.com") && (url.contains("/reel") || url.contains("/reels") || url.contains("/stories"))) {
            return true
        }
        if (url.contains("youtube.com/shorts") || url.contains("tiktok.com")) {
            return true
        }
        return false
    }

    /**
     * Resolves user omnibox input into a target URL or search query URL.
     * Returns null if navigation was blocked (e.g. restricted local path or missing file).
     */
    fun resolveOmniboxInputToUrl(trimmed: String): String? {
        return when {
            LocalFileLoader.isSensitiveOrRestrictedPath(activity, trimmed) -> {
                Toast.makeText(activity, "Access to private or restricted files is blocked", Toast.LENGTH_SHORT).show()
                null
            }

            LocalFileLoader.isLocalFile(activity, trimmed) -> trimmed

            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true) -> trimmed

            trimmed.startsWith("file://", ignoreCase = true) -> {
                val parsed = try { Uri.parse(trimmed) } catch (_: Exception) { null }
                val path = parsed?.path
                if (path != null && File(path).exists()) {
                    trimmed
                } else {
                    Toast.makeText(activity, "File not found: $trimmed", Toast.LENGTH_SHORT).show()
                    null
                }
            }

            isLikelyUrl(trimmed) -> "https://$trimmed"

            else -> {
                val allEngines = preferences.getAllSearchEngines()
                val spaceIndex = trimmed.indexOf(' ')
                if (spaceIndex > 0) {
                    val firstToken = trimmed.substring(0, spaceIndex).trim()
                    val queryAfterKeyword = trimmed.substring(spaceIndex + 1).trim()
                    val matchedEngine = allEngines.firstOrNull {
                        it.keyword.isNotBlank() && it.keyword.equals(firstToken, ignoreCase = true)
                    }
                    if (matchedEngine != null && queryAfterKeyword.isNotEmpty()) {
                        matchedEngine.buildSearchUrl(queryAfterKeyword)
                    } else {
                        preferences.searchEngine.buildSearchUrl(trimmed)
                    }
                } else {
                    preferences.searchEngine.buildSearchUrl(trimmed)
                }
            }
        }
    }

    fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null

        // Priority 1: Direct data URI from file manager, link click, or download item
        val dataUri = intent.data ?: intent.clipData?.let { if (it.itemCount > 0) it.getItemAt(0).uri else null }
        if (dataUri != null) {
            val uriStr = dataUri.toString().trim()
            if (uriStr.isNotBlank()) {
                return uriStr
            }
        }
        val dataString = intent.dataString?.trim()
        if (!dataString.isNullOrBlank()) {
            return dataString
        }

        val action = intent.action ?: return null

        when (action) {
            Intent.ACTION_VIEW, Intent.ACTION_SEND -> {
                val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!extraText.isNullOrBlank()) {
                    return parseUrlOrExtract(extraText)
                }
            }
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY)
                    ?: intent.getStringExtra("query")
                if (!query.isNullOrBlank()) {
                    return preferences.searchEngine.buildSearchUrl(query.trim())
                }
            }
        }
        return null
    }

    fun parseUrlOrExtract(text: String): String {
        val trimmed = text.trim()
        if (LocalFileLoader.isSensitiveOrRestrictedPath(activity, trimmed)) {
            return preferences.searchEngine.buildSearchUrl(trimmed)
        }
        if (LocalFileLoader.isLocalFile(activity, trimmed) ||
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true)
        ) {
            return trimmed
        }
        val urlRegex = Regex("(https?://[^\\s]+)")
        val match = urlRegex.find(trimmed)
        if (match != null) {
            return match.value
        }
        if (isLikelyUrl(trimmed)) {
            return "https://$trimmed"
        }
        return preferences.searchEngine.buildSearchUrl(trimmed)
    }

    fun pinWebpageToHomeScreen(activeTab: TabItem?, favicon: Bitmap?) {
        if (activeTab == null) return
        val currentUrl = activeTab.url
        if (currentUrl.isBlank() || currentUrl.startsWith("onyx://") || currentUrl.startsWith("about:")) {
            Toast.makeText(activity, "Cannot add internal page to Home screen", Toast.LENGTH_SHORT).show()
            return
        }
        val title = activeTab.title.ifBlank { currentUrl }

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
            val shortcutIntent = Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(currentUrl)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val iconCompat = if (favicon != null) {
                IconCompat.createWithBitmap(favicon)
            } else {
                IconCompat.createWithResource(activity, R.mipmap.ic_launcher)
            }
            val pinShortcutInfo = ShortcutInfoCompat.Builder(activity, "onyx_web_${currentUrl.hashCode()}")
                .setShortLabel(title.take(20))
                .setLongLabel(title)
                .setIcon(iconCompat)
                .setIntent(shortcutIntent)
                .build()
            ShortcutManagerCompat.requestPinShortcut(activity, pinShortcutInfo, null)
            Toast.makeText(activity, activity.getString(R.string.added_to_home_screen), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(activity, "Pin shortcut not supported by launcher", Toast.LENGTH_SHORT).show()
        }
    }
}
