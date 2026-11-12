package com.onyx.browser.data

import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.data.preferences.AppearancePreferences
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.data.preferences.MediaAndDownloadPreferences
import com.onyx.browser.data.preferences.SearchPreferences
import com.onyx.browser.data.preferences.ShieldPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainPreferencesTest {

    @Test
    fun `AppearancePreferences reads and writes through BrowserPreferences`() {
        val browserPrefs = mockk<BrowserPreferences>(relaxed = true)
        val appearance = AppearancePreferences(browserPrefs)

        every { browserPrefs.themeMode } returns BrowserPreferences.THEME_DARK
        every { browserPrefs.accentColorPreset } returns BrowserPreferences.ACCENT_PRESET_BLUE
        every { browserPrefs.accentColorHex } returns "#2563EB"
        every { browserPrefs.isAddressBarAtBottom } returns true
        every { browserPrefs.isAddressBarScrollHideEnabled } returns true
        every { browserPrefs.isHomeReplacedWithRefresh } returns true
        every { browserPrefs.isShowShortcutsOnHome } returns false
        every { browserPrefs.isShowQuickActionsOnHome } returns true
        every { browserPrefs.pageZoomPercent } returns 125

        assertEquals(BrowserPreferences.THEME_DARK, appearance.themeMode)
        assertEquals(BrowserPreferences.ACCENT_PRESET_BLUE, appearance.accentColorPreset)
        assertEquals("#2563EB", appearance.accentColorHex)
        assertTrue(appearance.isAddressBarAtBottom)
        assertTrue(appearance.isAddressBarScrollHideEnabled)
        assertTrue(appearance.isHomeReplacedWithRefresh)
        assertFalse(appearance.isShowShortcutsOnHome)
        assertTrue(appearance.isShowQuickActionsOnHome)
        assertEquals(125, appearance.pageZoomPercent)

        appearance.themeMode = BrowserPreferences.THEME_LIGHT
        appearance.accentColorPreset = BrowserPreferences.ACCENT_PRESET_GREEN
        appearance.accentColorHex = "#10B981"
        appearance.isAddressBarAtBottom = false
        appearance.isAddressBarScrollHideEnabled = false
        appearance.isHomeReplacedWithRefresh = false
        appearance.isShowShortcutsOnHome = true
        appearance.isShowQuickActionsOnHome = false
        appearance.pageZoomPercent = 110
        appearance.applyTheme()

        verify { browserPrefs.themeMode = BrowserPreferences.THEME_LIGHT }
        verify { browserPrefs.accentColorPreset = BrowserPreferences.ACCENT_PRESET_GREEN }
        verify { browserPrefs.accentColorHex = "#10B981" }
        verify { browserPrefs.isAddressBarAtBottom = false }
        verify { browserPrefs.isAddressBarScrollHideEnabled = false }
        verify { browserPrefs.isHomeReplacedWithRefresh = false }
        verify { browserPrefs.isShowShortcutsOnHome = true }
        verify { browserPrefs.isShowQuickActionsOnHome = false }
        verify { browserPrefs.pageZoomPercent = 110 }
        verify { browserPrefs.applyTheme() }
    }

    @Test
    fun `ShieldPreferences reads and writes through BrowserPreferences`() {
        val browserPrefs = mockk<BrowserPreferences>(relaxed = true)
        val shields = ShieldPreferences(browserPrefs)
        val blockedFlow = MutableStateFlow(42L)

        every { browserPrefs.isAdBlockEnabled } returns true
        every { browserPrefs.blockingLevel } returns BrowserPreferences.BLOCKING_AGGRESSIVE
        every { browserPrefs.isHttpsUpgradeEnabled } returns true
        every { browserPrefs.httpsMode } returns BrowserPreferences.HTTPS_MODE_STRICT
        every { browserPrefs.cookieMode } returns BrowserPreferences.COOKIE_BLOCK_THIRD_PARTY
        every { browserPrefs.dnsProvider } returns "https://dns.quad9.net/dns-query"
        every { browserPrefs.customDnsUrl } returns "https://dns.example.com/dns-query"
        every { browserPrefs.isWebRtcProtectedGlobal } returns true
        every { browserPrefs.isBlockScriptsGlobal } returns false
        every { browserPrefs.isClearOnExitEnabled } returns true
        every { browserPrefs.isClearOnExitTabsEnabled } returns true
        every { browserPrefs.isClearOnExitHistoryEnabled } returns true
        every { browserPrefs.isClearOnExitCookiesEnabled } returns false
        every { browserPrefs.isClearOnExitCacheEnabled } returns true
        every { browserPrefs.isElementBlockerEnabled } returns true
        every { browserPrefs.blockedRequestsFlow } returns blockedFlow
        every { browserPrefs.incrementBlockedRequests() } returns 43L

        assertTrue(shields.isAdBlockEnabled)
        assertEquals(BrowserPreferences.BLOCKING_AGGRESSIVE, shields.blockingLevel)
        assertTrue(shields.isHttpsUpgradeEnabled)
        assertEquals(BrowserPreferences.HTTPS_MODE_STRICT, shields.httpsMode)
        assertEquals(BrowserPreferences.COOKIE_BLOCK_THIRD_PARTY, shields.cookieMode)
        assertEquals("https://dns.quad9.net/dns-query", shields.dnsProvider)
        assertEquals("https://dns.example.com/dns-query", shields.customDnsUrl)
        assertTrue(shields.isWebRtcProtectedGlobal)
        assertFalse(shields.isBlockScriptsGlobal)
        assertTrue(shields.isClearOnExitEnabled)
        assertTrue(shields.isClearOnExitTabsEnabled)
        assertTrue(shields.isClearOnExitHistoryEnabled)
        assertFalse(shields.isClearOnExitCookiesEnabled)
        assertTrue(shields.isClearOnExitCacheEnabled)
        assertTrue(shields.isElementBlockerEnabled)
        assertSame(blockedFlow, shields.blockedRequestsFlow)
        assertEquals(43L, shields.incrementBlockedRequests())

        shields.isAdBlockEnabled = false
        shields.blockingLevel = BrowserPreferences.BLOCKING_STANDARD
        shields.isHttpsUpgradeEnabled = false
        shields.httpsMode = BrowserPreferences.HTTPS_MODE_WHEN_POSSIBLE
        shields.cookieMode = BrowserPreferences.COOKIE_BLOCK_ALL
        shields.dnsProvider = "https://cloudflare-dns.com/dns-query"
        shields.customDnsUrl = ""
        shields.isWebRtcProtectedGlobal = false
        shields.isBlockScriptsGlobal = true
        shields.isClearOnExitEnabled = false
        shields.isClearOnExitTabsEnabled = false
        shields.isClearOnExitHistoryEnabled = false
        shields.isClearOnExitCookiesEnabled = true
        shields.isClearOnExitCacheEnabled = false
        shields.isElementBlockerEnabled = false

        verify { browserPrefs.isAdBlockEnabled = false }
        verify { browserPrefs.blockingLevel = BrowserPreferences.BLOCKING_STANDARD }
        verify { browserPrefs.isHttpsUpgradeEnabled = false }
        verify { browserPrefs.httpsMode = BrowserPreferences.HTTPS_MODE_WHEN_POSSIBLE }
        verify { browserPrefs.cookieMode = BrowserPreferences.COOKIE_BLOCK_ALL }
        verify { browserPrefs.dnsProvider = "https://cloudflare-dns.com/dns-query" }
        verify { browserPrefs.customDnsUrl = "" }
        verify { browserPrefs.isWebRtcProtectedGlobal = false }
        verify { browserPrefs.isBlockScriptsGlobal = true }
        verify { browserPrefs.isClearOnExitEnabled = false }
        verify { browserPrefs.isClearOnExitTabsEnabled = false }
        verify { browserPrefs.isClearOnExitHistoryEnabled = false }
        verify { browserPrefs.isClearOnExitCookiesEnabled = true }
        verify { browserPrefs.isClearOnExitCacheEnabled = false }
        verify { browserPrefs.isElementBlockerEnabled = false }
        verify { browserPrefs.incrementBlockedRequests() }
    }

    @Test
    fun `MediaAndDownloadPreferences reads and writes through BrowserPreferences`() {
        val browserPrefs = mockk<BrowserPreferences>(relaxed = true)
        val mediaAndDownloads = MediaAndDownloadPreferences(browserPrefs)

        every { browserPrefs.isBackgroundPlayEnabled } returns true
        every { browserPrefs.isAutoPipEnabled } returns true
        every { browserPrefs.isSwipeGestureVideoEnabled } returns true
        every { browserPrefs.isDoubleTapSeekEnabled } returns false
        every { browserPrefs.selectedDownloaderPackage } returns "com.dv.adm"
        every { browserPrefs.isAskBeforeDownload } returns true
        every { browserPrefs.downloadThreadsCount } returns 8
        every { browserPrefs.downloadSpeedLimitKbps } returns 2048
        every { browserPrefs.isWifiOnlyDownload } returns true

        assertTrue(mediaAndDownloads.isBackgroundPlayEnabled)
        assertTrue(mediaAndDownloads.isAutoPipEnabled)
        assertTrue(mediaAndDownloads.isSwipeGestureVideoEnabled)
        assertFalse(mediaAndDownloads.isDoubleTapSeekEnabled)
        assertEquals("com.dv.adm", mediaAndDownloads.selectedDownloaderPackage)
        assertTrue(mediaAndDownloads.isAskBeforeDownload)
        assertEquals(8, mediaAndDownloads.downloadThreadsCount)
        assertEquals(2048, mediaAndDownloads.downloadSpeedLimitKbps)
        assertTrue(mediaAndDownloads.isWifiOnlyDownload)

        mediaAndDownloads.isBackgroundPlayEnabled = false
        mediaAndDownloads.isAutoPipEnabled = false
        mediaAndDownloads.isSwipeGestureVideoEnabled = false
        mediaAndDownloads.isDoubleTapSeekEnabled = true
        mediaAndDownloads.selectedDownloaderPackage = ""
        mediaAndDownloads.isAskBeforeDownload = false
        mediaAndDownloads.downloadThreadsCount = 4
        mediaAndDownloads.downloadSpeedLimitKbps = 0
        mediaAndDownloads.isWifiOnlyDownload = false

        verify { browserPrefs.isBackgroundPlayEnabled = false }
        verify { browserPrefs.isAutoPipEnabled = false }
        verify { browserPrefs.isSwipeGestureVideoEnabled = false }
        verify { browserPrefs.isDoubleTapSeekEnabled = true }
        verify { browserPrefs.selectedDownloaderPackage = "" }
        verify { browserPrefs.isAskBeforeDownload = false }
        verify { browserPrefs.downloadThreadsCount = 4 }
        verify { browserPrefs.downloadSpeedLimitKbps = 0 }
        verify { browserPrefs.isWifiOnlyDownload = false }
    }

    @Test
    fun `SearchPreferences reads and writes through BrowserPreferences`() {
        val browserPrefs = mockk<BrowserPreferences>(relaxed = true)
        val search = SearchPreferences(browserPrefs)
        val customEngine = SearchEngine(
            id = "custom_kagi",
            displayName = "Kagi",
            queryUrl = "https://kagi.com/search?q=%s",
            homeUrl = "https://kagi.com",
            isCustom = true
        )

        every { browserPrefs.searchEngine } returns SearchEngine.DUCKDUCKGO
        every { browserPrefs.isSearchSuggestionsEnabled } returns true
        every { browserPrefs.isShowClipboardSuggestions } returns false
        every { browserPrefs.isShowHistorySuggestions } returns true
        every { browserPrefs.getCustomSearchEngines() } returns listOf(customEngine)
        every { browserPrefs.getAllSearchEngines() } returns SearchEngine.BUILT_IN + customEngine

        assertEquals(SearchEngine.DUCKDUCKGO, search.searchEngine)
        assertTrue(search.isSearchSuggestionsEnabled)
        assertFalse(search.isShowClipboardSuggestions)
        assertTrue(search.isShowHistorySuggestions)
        assertEquals(listOf(customEngine), search.getCustomSearchEngines())
        assertTrue(search.getAllSearchEngines().contains(customEngine))

        search.searchEngine = SearchEngine.BRAVE
        search.isSearchSuggestionsEnabled = false
        search.isShowClipboardSuggestions = true
        search.isShowHistorySuggestions = false
        search.saveCustomSearchEngines(listOf(customEngine))

        verify { browserPrefs.searchEngine = SearchEngine.BRAVE }
        verify { browserPrefs.isSearchSuggestionsEnabled = false }
        verify { browserPrefs.isShowClipboardSuggestions = true }
        verify { browserPrefs.isShowHistorySuggestions = false }
        verify { browserPrefs.saveCustomSearchEngines(listOf(customEngine)) }
    }
}
