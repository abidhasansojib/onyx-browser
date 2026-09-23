package com.onyx.browser.web

import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences

data class UserAgentTemplate(
    val key: String,
    val title: String,
    val category: String, // "Mobile", "Desktop", "Tablet", "Bot", "Custom"
    val userAgentString: String,
    val iconResId: Int,
    val isCustom: Boolean = false
)

object UserAgentManager {
    const val KEY_DEFAULT = "default"
    const val KEY_WINDOWS_CHROME = "windows"
    const val KEY_WINDOWS_FIREFOX = "windows_firefox"
    const val KEY_WINDOWS_EDGE = "windows_edge"
    const val KEY_MACOS_SAFARI = "macos_safari"
    const val KEY_MACOS_CHROME = "macos_chrome"
    const val KEY_LINUX_FIREFOX = "linux_firefox"
    const val KEY_CHROME_OS = "cros"
    const val KEY_IPHONE = "iphone"
    const val KEY_IPAD = "ipad"
    const val KEY_ANDROID_FIREFOX = "android_firefox"
    const val KEY_GOOGLEBOT = "googlebot"
    const val KEY_CUSTOM = "custom"

    // Default Mobile Android (Pixel 8 / Chrome 131)
    const val DEFAULT_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36"

    // Windows Chrome Desktop
    const val DESKTOP_CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Safari/537.36"

    // Windows Firefox Desktop
    const val DESKTOP_FIREFOX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"

    // Windows Edge Desktop
    const val DESKTOP_EDGE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"

    // macOS Safari Desktop
    const val MACOS_SAFARI_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15"

    // macOS Chrome Desktop
    const val MACOS_CHROME_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Safari/537.36"

    // Linux Firefox Desktop
    const val LINUX_FIREFOX_UA =
        "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0"

    // Chrome OS / Chromebook
    const val CHROME_OS_UA =
        "Mozilla/5.0 (X11; CrOS x86_64 15662.76.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Safari/537.36"

    // iPhone Safari Mobile
    const val IPHONE_SAFARI_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"

    // iPad Safari Tablet
    const val IPAD_SAFARI_UA =
        "Mozilla/5.0 (iPad; CPU OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"

    // Android Firefox Mobile
    const val ANDROID_FIREFOX_UA =
        "Mozilla/5.0 (Android 14; Mobile; rv:133.0) Gecko/133.0 Firefox/133.0"

    // Googlebot Crawler
    const val GOOGLEBOT_UA =
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

    val DESKTOP_KEYS = setOf(
        KEY_WINDOWS_CHROME,
        KEY_WINDOWS_FIREFOX,
        KEY_WINDOWS_EDGE,
        KEY_MACOS_SAFARI,
        KEY_MACOS_CHROME,
        KEY_LINUX_FIREFOX,
        KEY_CHROME_OS
    )

    fun getTemplates(): List<UserAgentTemplate> {
        return listOf(
            UserAgentTemplate(
                key = KEY_DEFAULT,
                title = "Default (Android Chrome)",
                category = "Mobile",
                userAgentString = DEFAULT_MOBILE_UA,
                iconResId = R.drawable.ic_android
            ),
            UserAgentTemplate(
                key = KEY_WINDOWS_CHROME,
                title = "Windows PC (Google Chrome)",
                category = "Desktop",
                userAgentString = DESKTOP_CHROME_UA,
                iconResId = R.drawable.ic_desktop
            ),
            UserAgentTemplate(
                key = KEY_WINDOWS_FIREFOX,
                title = "Windows PC (Mozilla Firefox)",
                category = "Desktop",
                userAgentString = DESKTOP_FIREFOX_UA,
                iconResId = R.drawable.ic_desktop
            ),
            UserAgentTemplate(
                key = KEY_WINDOWS_EDGE,
                title = "Windows PC (Microsoft Edge)",
                category = "Desktop",
                userAgentString = DESKTOP_EDGE_UA,
                iconResId = R.drawable.ic_desktop
            ),
            UserAgentTemplate(
                key = KEY_MACOS_SAFARI,
                title = "macOS (Apple Safari)",
                category = "Desktop",
                userAgentString = MACOS_SAFARI_UA,
                iconResId = R.drawable.ic_desktop
            ),
            UserAgentTemplate(
                key = KEY_MACOS_CHROME,
                title = "macOS (Google Chrome)",
                category = "Desktop",
                userAgentString = MACOS_CHROME_UA,
                iconResId = R.drawable.ic_desktop
            ),
            UserAgentTemplate(
                key = KEY_LINUX_FIREFOX,
                title = "Linux PC (Mozilla Firefox)",
                category = "Desktop",
                userAgentString = LINUX_FIREFOX_UA,
                iconResId = R.drawable.ic_desktop
            ),
            UserAgentTemplate(
                key = KEY_CHROME_OS,
                title = "Chrome OS (Chromebook)",
                category = "Desktop",
                userAgentString = CHROME_OS_UA,
                iconResId = R.drawable.ic_desktop
            ),
            UserAgentTemplate(
                key = KEY_IPHONE,
                title = "iPhone (Apple Safari)",
                category = "Mobile",
                userAgentString = IPHONE_SAFARI_UA,
                iconResId = R.drawable.ic_phone
            ),
            UserAgentTemplate(
                key = KEY_IPAD,
                title = "iPad (iPadOS Safari)",
                category = "Tablet",
                userAgentString = IPAD_SAFARI_UA,
                iconResId = R.drawable.ic_tablet
            ),
            UserAgentTemplate(
                key = KEY_ANDROID_FIREFOX,
                title = "Android (Mozilla Firefox)",
                category = "Mobile",
                userAgentString = ANDROID_FIREFOX_UA,
                iconResId = R.drawable.ic_android
            ),
            UserAgentTemplate(
                key = KEY_GOOGLEBOT,
                title = "Googlebot (Search Crawler)",
                category = "Bot",
                userAgentString = GOOGLEBOT_UA,
                iconResId = R.drawable.ic_bot
            ),
            UserAgentTemplate(
                key = KEY_CUSTOM,
                title = "Custom User-Agent",
                category = "Custom",
                userAgentString = "",
                iconResId = R.drawable.ic_edit,
                isCustom = true
            )
        )
    }

    fun getUserAgentForTemplate(key: String, prefs: BrowserPreferences): String {
        if (key == KEY_CUSTOM) {
            val custom = prefs.customUserAgent.trim()
            return if (custom.isNotBlank()) custom else DEFAULT_MOBILE_UA
        }
        return getTemplates().firstOrNull { it.key == key }?.userAgentString ?: DEFAULT_MOBILE_UA
    }

    fun getDisplayName(key: String, prefs: BrowserPreferences): String {
        if (key == KEY_CUSTOM) {
            val custom = prefs.customUserAgent.trim()
            return if (custom.isNotBlank()) {
                val preview = if (custom.length > 28) custom.substring(0, 28) + "..." else custom
                "Custom ($preview)"
            } else {
                "Custom User-Agent"
            }
        }
        return getTemplates().firstOrNull { it.key == key }?.title ?: "Default (Android Chrome)"
    }
}
