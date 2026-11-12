package com.onyx.browser.web.navigation

import android.webkit.WebView
import com.onyx.browser.web.TabActionCallback
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalNavigationHandlerTest {

    @Test
    fun `checkAndBreakRedirectLoop triggers on 4th identical normalized navigation within burst window`() {
        val tabCallback = mockk<TabActionCallback>(relaxed = true)
        val handler = ExternalNavigationHandler(tabCallback)
        val mockView = mockk<WebView>(relaxed = true)
        val baseTime = 100_000L

        assertFalse(handler.checkAndBreakRedirectLoop(mockView, "https://example.com/login?step=1", baseTime))
        assertFalse(handler.checkAndBreakRedirectLoop(mockView, "https://example.com/login?step=2", baseTime + 500))
        assertFalse(handler.checkAndBreakRedirectLoop(mockView, "https://example.com/login?step=3", baseTime + 1000))
        assertTrue(handler.checkAndBreakRedirectLoop(mockView, "https://example.com/login?step=4", baseTime + 1500))

        verify(exactly = 1) { mockView.stopLoading() }

        // State should be cleared after breaking the loop
        assertFalse(handler.checkAndBreakRedirectLoop(mockView, "https://example.com/login?step=5", baseTime + 1600))
    }

    @Test
    fun `checkAndBreakRedirectLoop resets count when navigations occur outside burst window`() {
        val handler = ExternalNavigationHandler()
        val mockView = mockk<WebView>(relaxed = true)
        val baseTime = 200_000L

        assertFalse(handler.checkAndBreakRedirectLoop(mockView, "https://example.com/page", baseTime))
        assertFalse(handler.checkAndBreakRedirectLoop(mockView, "https://example.com/page", baseTime + 1000))
        assertFalse(handler.checkAndBreakRedirectLoop(mockView, "https://example.com/page", baseTime + 2000))

        // Advance past BURST_WINDOW_MS (4000ms) from the first navigation
        val afterWindow = baseTime + ExternalNavigationHandler.BURST_WINDOW_MS + 500
        assertFalse(handler.checkAndBreakRedirectLoop(mockView, "https://example.com/page", afterWindow))

        verify(exactly = 0) { mockView.stopLoading() }
    }

    @Test
    fun `checkAndBreakRedirectLoop detects alternating ping-pong redirect loop`() {
        val handler = ExternalNavigationHandler()
        val mockView = mockk<WebView>(relaxed = true)
        val t0 = 500_000L
        val urlA = "https://auth.example.com/callback"
        val urlB = "https://app.example.com/dashboard"

        assertFalse(handler.checkAndBreakRedirectLoop(mockView, urlA, t0))
        assertFalse(handler.checkAndBreakRedirectLoop(mockView, urlB, t0 + 200))
        assertFalse(handler.checkAndBreakRedirectLoop(mockView, urlA, t0 + 400))
        assertFalse(handler.checkAndBreakRedirectLoop(mockView, urlB, t0 + 600))
        assertFalse(handler.checkAndBreakRedirectLoop(mockView, urlA, t0 + 800))
        assertTrue(handler.checkAndBreakRedirectLoop(mockView, urlB, t0 + 1000))

        verify(exactly = 1) { mockView.stopLoading() }
    }

    @Test
    fun `isSafeFallbackScheme allows only http and https and rejects dangerous schemes`() {
        assertTrue(ExternalNavigationHandler.isSafeFallbackScheme("http"))
        assertTrue(ExternalNavigationHandler.isSafeFallbackScheme("https"))
        assertTrue(ExternalNavigationHandler.isSafeFallbackScheme("HTTPS"))
        assertTrue(ExternalNavigationHandler.isSafeFallbackScheme(" http "))

        assertFalse(ExternalNavigationHandler.isSafeFallbackScheme("javascript"))
        assertFalse(ExternalNavigationHandler.isSafeFallbackScheme("file"))
        assertFalse(ExternalNavigationHandler.isSafeFallbackScheme("intent"))
        assertFalse(ExternalNavigationHandler.isSafeFallbackScheme("data"))
        assertFalse(ExternalNavigationHandler.isSafeFallbackScheme("content"))
        assertFalse(ExternalNavigationHandler.isSafeFallbackScheme("about"))
        assertFalse(ExternalNavigationHandler.isSafeFallbackScheme(""))
        assertFalse(ExternalNavigationHandler.isSafeFallbackScheme(null))
    }

    @Test
    fun `isSafeFallbackUrl validates full fallback URLs and rejects script or file URIs`() {
        assertTrue(ExternalNavigationHandler.isSafeFallbackUrl("https://example.com/fallback"))
        assertTrue(ExternalNavigationHandler.isSafeFallbackUrl("http://sub.example.org:8080/path?q=1"))

        assertFalse(ExternalNavigationHandler.isSafeFallbackUrl("javascript:alert(1)"))
        assertFalse(ExternalNavigationHandler.isSafeFallbackUrl("file:///data/data/com.onyx.browser/shared_prefs/prefs.xml"))
        assertFalse(ExternalNavigationHandler.isSafeFallbackUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(ExternalNavigationHandler.isSafeFallbackUrl("https:///missing-host"))
        assertFalse(ExternalNavigationHandler.isSafeFallbackUrl(""))
        assertFalse(ExternalNavigationHandler.isSafeFallbackUrl(null))
    }

    @Test
    fun `isCustomExternalScheme distinguishes external app schemes from internal web schemes`() {
        // External schemes
        assertTrue(ExternalNavigationHandler.isCustomExternalScheme("mailto"))
        assertTrue(ExternalNavigationHandler.isCustomExternalScheme("tel"))
        assertTrue(ExternalNavigationHandler.isCustomExternalScheme("sms"))
        assertTrue(ExternalNavigationHandler.isCustomExternalScheme("smsto"))
        assertTrue(ExternalNavigationHandler.isCustomExternalScheme("mms"))
        assertTrue(ExternalNavigationHandler.isCustomExternalScheme("geo"))
        assertTrue(ExternalNavigationHandler.isCustomExternalScheme("market"))
        assertTrue(ExternalNavigationHandler.isCustomExternalScheme("intent"))
        assertTrue(ExternalNavigationHandler.isCustomExternalScheme("tg"))
        assertTrue(ExternalNavigationHandler.isCustomExternalScheme("whatsapp"))
        assertTrue(ExternalNavigationHandler.isCustomExternalScheme("discord"))
        assertTrue(ExternalNavigationHandler.isCustomExternalScheme("spotify"))

        // Internal web schemes
        assertFalse(ExternalNavigationHandler.isCustomExternalScheme("http"))
        assertFalse(ExternalNavigationHandler.isCustomExternalScheme("https"))
        assertFalse(ExternalNavigationHandler.isCustomExternalScheme("about"))
        assertFalse(ExternalNavigationHandler.isCustomExternalScheme("data"))
        assertFalse(ExternalNavigationHandler.isCustomExternalScheme("blob"))
        assertFalse(ExternalNavigationHandler.isCustomExternalScheme("javascript"))
        assertFalse(ExternalNavigationHandler.isCustomExternalScheme("file"))
        assertFalse(ExternalNavigationHandler.isCustomExternalScheme("content"))
        assertFalse(ExternalNavigationHandler.isCustomExternalScheme(""))
    }

    @Test
    fun `resolveKnownAppName and resolveFallbackStorePackage map known external schemes`() {
        assertEquals("Telegram", ExternalNavigationHandler.resolveKnownAppName("tg"))
        assertEquals("WhatsApp", ExternalNavigationHandler.resolveKnownAppName("whatsapp"))
        assertEquals("Google Play", ExternalNavigationHandler.resolveKnownAppName("market"))
        assertNull(ExternalNavigationHandler.resolveKnownAppName("unknownscheme"))

        assertEquals("org.telegram.messenger", ExternalNavigationHandler.resolveFallbackStorePackage("telegram"))
        assertEquals("com.spotify.music", ExternalNavigationHandler.resolveFallbackStorePackage("spotify"))
        assertNull(ExternalNavigationHandler.resolveFallbackStorePackage("mailto"))
    }

    @Test
    fun `bypassAppInterceptUrls adds and consumes URLs once`() {
        val handler = ExternalNavigationHandler()
        val url = "https://www.reddit.com/r/android/"

        assertTrue(handler.addBypassUrl(url))
        assertTrue(handler.consumeBypassUrl(url))
        assertFalse(handler.consumeBypassUrl(url))
    }
}
