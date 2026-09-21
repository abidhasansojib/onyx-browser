package com.onyx.browser.data.model

import org.json.JSONObject
import java.util.UUID

data class ShortcutItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val iconType: String = DEFAULT_ICON
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("title", title)
        obj.put("url", url)
        obj.put("iconType", iconType)
        return obj
    }

    companion object {
        const val DEFAULT_ICON = "custom"
        const val ICON_GOOGLE = "google"
        const val ICON_YOUTUBE = "youtube"
        const val ICON_GITHUB = "github"
        const val ICON_WIKIPEDIA = "wikipedia"
        const val ICON_FACEBOOK = "facebook"
        const val ICON_REDDIT = "reddit"
        
        const val ICON_BOOKMARKS = "bookmarks"
        const val ICON_HISTORY = "history"
        const val ICON_DOWNLOADS = "downloads"
        const val ICON_QR_SCAN = "qr_scan"

        fun fromJson(json: JSONObject): ShortcutItem {
            val url = json.optString("url", "")
            val title = json.optString("title", "")
            val icon = json.optString("iconType", inferIconType(url))
            return ShortcutItem(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = title,
                url = url,
                iconType = icon
            )
        }

        fun inferIconType(url: String): String {
            val lower = url.lowercase()
            return when {
                lower.startsWith("onyx://bookmarks") -> ICON_BOOKMARKS
                lower.startsWith("onyx://history") -> ICON_HISTORY
                lower.startsWith("onyx://downloads") -> ICON_DOWNLOADS
                lower.startsWith("onyx://qr") -> ICON_QR_SCAN
                lower.contains("youtube.com") || lower.contains("youtu.be") -> ICON_YOUTUBE
                lower.contains("github.com") -> ICON_GITHUB
                lower.contains("wikipedia.org") -> ICON_WIKIPEDIA
                lower.contains("facebook.com") || lower.contains("fb.com") -> ICON_FACEBOOK
                lower.contains("reddit.com") -> ICON_REDDIT
                lower.contains("google.com") -> ICON_GOOGLE
                else -> DEFAULT_ICON
            }
        }

        fun getDefaultShortcuts(): List<ShortcutItem> {
            return listOf(
                ShortcutItem(
                    id = "sys_bookmarks",
                    title = "Bookmarks",
                    url = "onyx://bookmarks",
                    iconType = ICON_BOOKMARKS
                ),
                ShortcutItem(
                    id = "sys_history",
                    title = "History",
                    url = "onyx://history",
                    iconType = ICON_HISTORY
                ),
                ShortcutItem(
                    id = "sys_downloads",
                    title = "Downloads",
                    url = "onyx://downloads",
                    iconType = ICON_DOWNLOADS
                ),
                ShortcutItem(
                    id = "sys_qr",
                    title = "QR Scan",
                    url = "onyx://qr",
                    iconType = ICON_QR_SCAN
                ),
                ShortcutItem(
                    id = "default_google",
                    title = "Google",
                    url = "https://www.google.com",
                    iconType = ICON_GOOGLE
                ),
                ShortcutItem(
                    id = "default_youtube",
                    title = "YouTube",
                    url = "https://www.youtube.com",
                    iconType = ICON_YOUTUBE
                ),
                ShortcutItem(
                    id = "default_github",
                    title = "GitHub",
                    url = "https://github.com",
                    iconType = ICON_GITHUB
                ),
                ShortcutItem(
                    id = "default_wiki",
                    title = "Wikipedia",
                    url = "https://www.wikipedia.org",
                    iconType = ICON_WIKIPEDIA
                )
            )
        }
    }
}
