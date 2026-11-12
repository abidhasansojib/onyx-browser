package com.onyx.browser.web.interceptor

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.onyx.browser.data.preferences.BrowserPreferences
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InterceptorPipelineTest {

    private fun createContext(
        requestUrl: String = "https://ads.example.net/banner.js",
        requestHost: String = "ads.example.net",
        pageUrl: String = "https://publisher.example.com/article",
        pageHost: String = "publisher.example.com",
        isMainFrame: Boolean = false,
        resourceType: String = "script",
        isThirdParty: Boolean = true
    ): InterceptRequestContext {
        return InterceptRequestContext(
            view = null,
            onyxWebView = null,
            request = mockk<WebResourceRequest>(relaxed = true),
            requestUrl = requestUrl,
            requestHost = requestHost,
            pageUrl = pageUrl,
            pageHost = pageHost,
            isMainFrame = isMainFrame,
            resourceType = resourceType,
            isThirdParty = isThirdParty
        )
    }

    @Test
    fun `RealInterceptorChain executes interceptors in order and returns null when none intercept`() {
        val callOrder = mutableListOf<String>()
        val i1 = object : RequestInterceptor {
            override fun intercept(chain: InterceptorChain): WebResourceResponse? {
                callOrder.add("i1")
                return chain.proceed()
            }
        }
        val i2 = object : RequestInterceptor {
            override fun intercept(chain: InterceptorChain): WebResourceResponse? {
                callOrder.add("i2")
                return chain.proceed()
            }
        }
        val i3 = object : RequestInterceptor {
            override fun intercept(chain: InterceptorChain): WebResourceResponse? {
                callOrder.add("i3")
                return chain.proceed()
            }
        }

        val ctx = createContext()
        val result = RealInterceptorChain(listOf(i1, i2, i3), 0, ctx).proceed(ctx)

        assertNull(result)
        assertEquals(listOf("i1", "i2", "i3"), callOrder)
    }

    @Test
    fun `RealInterceptorChain short-circuits when an interceptor returns a response`() {
        val expectedResponse = mockk<WebResourceResponse>(relaxed = true)
        val callOrder = mutableListOf<String>()

        val i1 = object : RequestInterceptor {
            override fun intercept(chain: InterceptorChain): WebResourceResponse? {
                callOrder.add("i1")
                return chain.proceed()
            }
        }
        val i2 = object : RequestInterceptor {
            override fun intercept(chain: InterceptorChain): WebResourceResponse? {
                callOrder.add("i2")
                return expectedResponse
            }
        }
        val i3 = object : RequestInterceptor {
            override fun intercept(chain: InterceptorChain): WebResourceResponse? {
                callOrder.add("i3")
                return chain.proceed()
            }
        }

        val ctx = createContext()
        val result = RealInterceptorChain(listOf(i1, i2, i3), 0, ctx).proceed(ctx)

        assertSame(expectedResponse, result)
        assertEquals(listOf("i1", "i2"), callOrder)
    }

    @Test
    fun `AuthBypassInterceptor short-circuits for CAPTCHA, OAuth, and media segment URLs`() {
        var downstreamCalled = false
        val downstream = object : RequestInterceptor {
            override fun intercept(chain: InterceptorChain): WebResourceResponse? {
                downstreamCalled = true
                return mockk<WebResourceResponse>(relaxed = true)
            }
        }
        val authBypass = AuthBypassInterceptor()

        // 1. Cloudflare Turnstile CAPTCHA challenge
        val captchaCtx = createContext(
            requestUrl = "https://challenges.cloudflare.com/turnstile/v0/api.js",
            requestHost = "challenges.cloudflare.com"
        )
        val captchaResult = RealInterceptorChain(listOf(authBypass, downstream), 0, captchaCtx).proceed(captchaCtx)
        assertNull(captchaResult)
        assertFalse(downstreamCalled)

        // 2. OAuth identity provider
        val oauthCtx = createContext(
            requestUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=123",
            requestHost = "accounts.google.com"
        )
        val oauthResult = RealInterceptorChain(listOf(authBypass, downstream), 0, oauthCtx).proceed(oauthCtx)
        assertNull(oauthResult)
        assertFalse(downstreamCalled)

        // 3. HLS media stream segment
        val mediaCtx = createContext(
            requestUrl = "https://cdn.stream.example.com/hls/segment_001.ts",
            requestHost = "cdn.stream.example.com",
            resourceType = "media"
        )
        val mediaResult = RealInterceptorChain(listOf(authBypass, downstream), 0, mediaCtx).proceed(mediaCtx)
        assertNull(mediaResult)
        assertFalse(downstreamCalled)

        // 4. Regular third-party ad script proceeds to downstream
        val adCtx = createContext(
            requestUrl = "https://doubleclick.net/instream/ad.js",
            requestHost = "doubleclick.net",
            resourceType = "script"
        )
        val adResult = RealInterceptorChain(listOf(authBypass, downstream), 0, adCtx).proceed(adCtx)
        assertNotNull(adResult)
        assertTrue(downstreamCalled)
    }

    @Test
    fun `HttpsUpgradeInterceptor blocks cleartext HTTP subresources in strict mode while preserving media and localhost`() {
        val blockedStub = mockk<WebResourceResponse>(relaxed = true)
        val strictHttps = HttpsUpgradeInterceptor(
            httpsUpgradeModeProvider = { BrowserPreferences.HTTPS_MODE_STRICT },
            isDomainWhitelisted = { false },
            onBlockedSubresource = { blockedStub }
        )

        // 1. Cleartext HTTP script on public host is blocked in strict mode
        val httpScriptCtx = createContext(
            requestUrl = "http://insecure.example.com/app.js",
            requestHost = "insecure.example.com",
            resourceType = "script"
        )
        val blockedResult = RealInterceptorChain(listOf(strictHttps), 0, httpScriptCtx).proceed(httpScriptCtx)
        assertSame(blockedStub, blockedResult)

        // 2. Passive mixed-content media stream is allowed
        val httpMediaCtx = createContext(
            requestUrl = "http://stream.example.com/live/index.m3u8",
            requestHost = "stream.example.com",
            resourceType = "media"
        )
        assertNull(RealInterceptorChain(listOf(strictHttps), 0, httpMediaCtx).proceed(httpMediaCtx))

        // 3. Localhost / RFC1918 private network is allowed
        val localhostCtx = createContext(
            requestUrl = "http://192.168.1.10:8080/api.js",
            requestHost = "192.168.1.10",
            resourceType = "script"
        )
        assertNull(RealInterceptorChain(listOf(strictHttps), 0, localhostCtx).proceed(localhostCtx))
    }

    @Test
    fun `PrivacyUrlSanitizer strips tracking parameters and preserves legitimate query and fragment`() {
        val dirtyUrl = "https://example.com/path?id=42&utm_source=newsletter&fbclid=IwAR123&sort=asc#top"
        val cleaned = PrivacyUrlSanitizer.stripTrackingParams(dirtyUrl)
        assertEquals("https://example.com/path?id=42&sort=asc#top", cleaned)

        val onlyTrackingUrl = "https://example.com/landing?gclid=abc123&utm_campaign=spring#hero"
        assertEquals("https://example.com/landing#hero", PrivacyUrlSanitizer.stripTrackingParams(onlyTrackingUrl))

        val cleanUrl = "https://example.com/search?q=kotlin&page=2"
        assertEquals(cleanUrl, PrivacyUrlSanitizer.stripTrackingParams(cleanUrl))
    }

    @Test
    fun `PrivacyUrlSanitizer resolves Google AMP cache and query AMP URLs`() {
        val googleAmp = "https://www.google.com/amp/s/example.com/news/story.html"
        assertEquals("https://example.com/news/story.html", PrivacyUrlSanitizer.resolveAmpUrl(googleAmp))

        val queryAmp = "https://example.com/article?amp=1&ref=home"
        assertEquals("https://example.com/article?ref=home", PrivacyUrlSanitizer.resolveAmpUrl(queryAmp))

        val normalUrl = "https://example.com/article?id=10"
        assertNull(PrivacyUrlSanitizer.resolveAmpUrl(normalUrl))
    }

    @Test
    fun `PrivacyUrlSanitizer accurately classifies Meta, OAuth, CAPTCHA, media, and third-party domains`() {
        assertTrue(PrivacyUrlSanitizer.isMetaDomain("m.facebook.com"))
        assertTrue(PrivacyUrlSanitizer.isMetaDomain("cdninstagram.com"))
        assertFalse(PrivacyUrlSanitizer.isMetaDomain("evil-facebook.com"))

        assertTrue(PrivacyUrlSanitizer.isOAuthOrLoginProvider("accounts.google.com"))
        assertTrue(PrivacyUrlSanitizer.isOAuthOrLoginProvider("login.microsoftonline.com"))
        assertTrue(PrivacyUrlSanitizer.isOAuthOrLoginUrl("https://site.example.com/oauth2/callback?code=xyz"))

        assertTrue(
            PrivacyUrlSanitizer.isCaptchaOrAuthUrl(
                "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/g",
                "challenges.cloudflare.com"
            )
        )

        assertTrue(PrivacyUrlSanitizer.isMediaUrl("https://video.example.com/master.m3u8?token=1"))
        assertTrue(PrivacyUrlSanitizer.isMediaUrl("https://video.example.com/chunk.m4s"))
        assertFalse(PrivacyUrlSanitizer.isMediaUrl("https://video.example.com/player.js"))

        assertTrue(PrivacyUrlSanitizer.isThirdPartyDomain("ads.doubleclick.net", "nytimes.com"))
        assertFalse(PrivacyUrlSanitizer.isThirdPartyDomain("img.nytimes.com", "nytimes.com"))
    }
}
