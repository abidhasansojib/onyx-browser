package com.onyx.browser.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.onyx.browser.data.preferences.BrowserPreferences

/**
 * JavaScript interface that bridges HTML5 video/audio events, playback positions,
 * and Web MediaSession metadata to Android's MediaPlaybackService and PiP manager.
 */
class MediaPlaybackBridge(private val context: Context, private val webView: android.webkit.WebView? = null) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences = BrowserPreferences.getInstance(context)

    companion object {
        @Volatile var isVideoPlaying: Boolean = false
        @Volatile var isAudioOrVideoPlaying: Boolean = false
        var isMediaPlaying: Boolean
            get() = isAudioOrVideoPlaying || isVideoPlaying
            set(value) {
                isAudioOrVideoPlaying = value
                isVideoPlaying = value
            }
        @Volatile var lastVideoWidth: Int = 16
        @Volatile var lastVideoHeight: Int = 9
        @Volatile var currentPositionMs: Long = 0L
        @Volatile var currentDurationMs: Long = 0L
        @Volatile var currentTitle: String = "Web Media"
        @Volatile var currentArtist: String = "Onyx Browser"
        @Volatile var currentArtworkUrl: String? = null

        @Volatile var isAppInBackground: Boolean = false
        @Volatile var isCurrentlyInPip: Boolean = false
        @Volatile var isExplicitUserPause: Boolean = false

        fun isScreenOffOrLocked(ctx: Context): Boolean {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            val isScreenOff = pm?.isInteractive == false
            val isLocked = km?.isKeyguardLocked == true
            return isScreenOff || isLocked
        }

        @Volatile var isVideoPresent: Boolean = false
        @Volatile var currentVideoPresentTabId: String? = null
        val isVideoAvailable: Boolean
            get() = isVideoPresent || isVideoPlaying

        fun isVideoAvailableForTab(tabId: String?): Boolean {
            if (tabId.isNullOrBlank()) return false
            val isPresent = (currentVideoPresentTabId == tabId && isVideoPresent)
            val isPlaying = (currentPlayingTabId == tabId && isVideoPlaying)
            return isPresent || isPlaying
        }

        @Volatile var lastVideoBounds: android.graphics.RectF? = null

        @Volatile var currentPlayingTabId: String? = null
        @Volatile var currentPlayingWebView: java.lang.ref.WeakReference<android.webkit.WebView>? = null
        @Volatile var lastPipRequestTimestamp = 0L

        var onMediaStateListener: ((isPlaying: Boolean, isVideo: Boolean, width: Int, height: Int) -> Unit)? = null
        var onMediaPlaybackStartedListener: ((playingWebView: android.webkit.WebView) -> Unit)? = null
        var onVideoBoundsListener: ((left: Float, top: Float, right: Float, bottom: Float) -> Unit)? = null
        var onPipRequestedListener: (() -> Unit)? = null
        var onPipExitListener: (() -> Unit)? = null

        fun isBackgroundPlayActive(preferences: BrowserPreferences): Boolean {
            return preferences.isBackgroundPlayEnabled
        }

        fun onTabClosed(tabId: String, context: Context) {
            if (currentVideoPresentTabId == tabId) {
                currentVideoPresentTabId = null
                isVideoPresent = false
                lastVideoBounds = null
            }
            val playingId = currentPlayingTabId
            val playingWv = currentPlayingWebView?.get()
            val playingWvTabId = (playingWv as? com.onyx.browser.web.OnyxWebView)?.tabId

            if (playingId == tabId || playingWvTabId == tabId || (!isAudioOrVideoPlaying && !isVideoPlaying && playingId == null)) {
                resetMediaPlayback(context)
            }
        }

        fun resetMediaPlayback(context: Context) {
            currentVideoPresentTabId = null
            isVideoPresent = false
            isVideoPlaying = false
            isAudioOrVideoPlaying = false
            isCurrentlyInPip = false
            isExplicitUserPause = false
            currentPlayingTabId = null
            currentPlayingWebView = null
            lastVideoBounds = null
            currentPositionMs = 0L
            currentDurationMs = 0L
            currentTitle = "Web Media"
            currentArtist = "Onyx Browser"
            currentArtworkUrl = null

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onMediaStateListener?.invoke(false, false, lastVideoWidth, lastVideoHeight)
                MediaPlaybackService.stopNotificationOnly(context)
            }
        }
    }

    private val isBackgroundPlayActive: Boolean
        get() = isBackgroundPlayActive(preferences)

    @JavascriptInterface
    fun onVideoPresenceChanged(hasVideo: Boolean, src: String?, width: Int, height: Int) {
        val myTabId = (webView as? com.onyx.browser.web.OnyxWebView)?.tabId?.takeIf { it.isNotBlank() }
        if (hasVideo) {
            if (myTabId != null) {
                currentVideoPresentTabId = myTabId
            }
            isVideoPresent = true
            if (width > 0 && height > 0) {
                lastVideoWidth = width
                lastVideoHeight = height
            }
        } else {
            if (myTabId != null && currentVideoPresentTabId == myTabId) {
                currentVideoPresentTabId = null
                isVideoPresent = false
            }
        }
    }

    @JavascriptInterface
    fun requestVideoPip() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPipRequestTimestamp < 1500L) {
            return
        }
        val myTabId = (webView as? com.onyx.browser.web.OnyxWebView)?.tabId
        if (!isVideoPresent && !isVideoAvailableForTab(myTabId)) {
            return
        }
        lastPipRequestTimestamp = now
        mainHandler.post {
            onPipRequestedListener?.invoke()
        }
    }

    @JavascriptInterface
    fun exitVideoPip() {
        mainHandler.post {
            onPipExitListener?.invoke()
        }
    }

    @JavascriptInterface
    fun onVideoBoundsChanged(left: Float, top: Float, right: Float, bottom: Float) {
        lastVideoBounds = android.graphics.RectF(left, top, right, bottom)
        mainHandler.post {
            onVideoBoundsListener?.invoke(left, top, right, bottom)
        }
    }

    private fun sanitizeMediaText(raw: String?, fallback: String? = null): String? {
        val trimmed = raw?.take(100)?.replace(Regex("[\\r\\n\\t]"), " ")?.trim()
        return trimmed?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun sanitizeArtworkUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.take(500)?.trim() ?: return null
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("data:image/", ignoreCase = true)
        ) trimmed else null
    }

    @JavascriptInterface
    fun onMediaStateChanged(
        title: String?,
        artist: String?,
        artworkUrl: String?,
        isPlaying: Boolean,
        positionSec: Double,
        durationSec: Double,
        isVideo: Boolean,
        videoWidth: Int,
        videoHeight: Int
    ) {
        val myTabId = (webView as? com.onyx.browser.web.OnyxWebView)?.tabId?.takeIf { it.isNotBlank() }
        if (isVideo) {
            if (myTabId != null) {
                currentVideoPresentTabId = myTabId
            }
            isVideoPresent = true
        }

        if (isPlaying) {
            isVideoPlaying = isVideo
            isAudioOrVideoPlaying = true
            isExplicitUserPause = false
            if (myTabId != null) {
                currentPlayingTabId = myTabId
            }
            if (webView != null) {
                currentPlayingWebView = java.lang.ref.WeakReference(webView)
            }
        } else {
            if (myTabId != null && currentPlayingTabId == myTabId) {
                isVideoPlaying = false
                isAudioOrVideoPlaying = false
            }
        }

        if (videoWidth > 0 && videoHeight > 0) {
            lastVideoWidth = videoWidth
            lastVideoHeight = videoHeight
        }

        currentPositionMs = (positionSec * 1000).toLong().coerceAtLeast(0L)
        currentDurationMs = (durationSec * 1000).toLong().coerceAtLeast(0L)

        val cleanTitle = sanitizeMediaText(title, "Web Media") ?: "Web Media"
        val cleanArtist = sanitizeMediaText(artist, "Onyx Browser") ?: "Onyx Browser"
        currentTitle = cleanTitle
        currentArtist = cleanArtist
        currentArtworkUrl = sanitizeArtworkUrl(artworkUrl)

        mainHandler.post {
            onMediaStateListener?.invoke(isPlaying, isVideo, lastVideoWidth, lastVideoHeight)
            if (isPlaying && webView != null) {
                onMediaPlaybackStartedListener?.invoke(webView)
            }
            if (isBackgroundPlayActive && isPlaying) {
                MediaPlaybackService.start(
                    context,
                    cleanTitle,
                    cleanArtist,
                    currentArtworkUrl,
                    currentPositionMs,
                    currentDurationMs,
                    true
                )
            } else if (!isPlaying) {
                MediaPlaybackService.updateState(context, false, currentPositionMs, currentDurationMs)
            }
        }
    }

    @JavascriptInterface
    fun onMediaProgress(positionSec: Double, durationSec: Double) {
        currentPositionMs = (positionSec * 1000).toLong().coerceAtLeast(0L)
        currentDurationMs = (durationSec * 1000).toLong().coerceAtLeast(0L)

        mainHandler.post {
            if (isBackgroundPlayActive && isAudioOrVideoPlaying) {
                MediaPlaybackService.updateProgress(context, currentPositionMs, currentDurationMs)
            }
        }
    }

    @JavascriptInterface
    fun onMediaPlaying(title: String?, artist: String?, isVideo: Boolean) {
        isExplicitUserPause = false
        val myTabId = (webView as? com.onyx.browser.web.OnyxWebView)?.tabId?.takeIf { it.isNotBlank() }
        if (isVideo) {
            if (myTabId != null) {
                currentVideoPresentTabId = myTabId
            }
            isVideoPresent = true
        }
        isVideoPlaying = isVideo
        isAudioOrVideoPlaying = true

        val cleanTitle = sanitizeMediaText(title, "Web Media") ?: "Web Media"
        val cleanArtist = sanitizeMediaText(artist, "Onyx Browser") ?: "Onyx Browser"
        currentTitle = cleanTitle
        currentArtist = cleanArtist

        if (myTabId != null) {
            currentPlayingTabId = myTabId
        }
        if (webView != null) {
            currentPlayingWebView = java.lang.ref.WeakReference(webView)
        }

        mainHandler.post {
            onMediaStateListener?.invoke(true, isVideo, lastVideoWidth, lastVideoHeight)
            if (webView != null) {
                onMediaPlaybackStartedListener?.invoke(webView)
            }
            if (isBackgroundPlayActive) {
                MediaPlaybackService.start(
                    context,
                    cleanTitle,
                    cleanArtist,
                    currentArtworkUrl,
                    currentPositionMs,
                    currentDurationMs,
                    true
                )
            }
        }
    }

    @JavascriptInterface
    fun onMediaPaused() {
        val inPip = isCurrentlyInPip
        val shouldSuppress = !inPip &&
                isBackgroundPlayActive &&
                (isAppInBackground || isScreenOffOrLocked(context)) &&
                !isExplicitUserPause

        if (shouldSuppress) {
            // Brave pattern: Transient pause caused by screen lock or background transition.
            // Suppress the pause, preserve playing state so audio keeps decoding in background!
            mainHandler.post {
                val wv = currentPlayingWebView?.get() ?: webView
                wv?.evaluateJavascript(com.onyx.browser.web.MediaPlaybackManager.playAllMediaScript, null)
            }
            return
        }

        val myTabId = (webView as? com.onyx.browser.web.OnyxWebView)?.tabId?.takeIf { it.isNotBlank() }
        if (myTabId != null && currentPlayingTabId == myTabId) {
            isAudioOrVideoPlaying = false
            isVideoPlaying = false
        } else if (currentPlayingTabId == null || inPip) {
            isAudioOrVideoPlaying = false
            isVideoPlaying = false
        }

        mainHandler.post {
            onMediaStateListener?.invoke(false, false, lastVideoWidth, lastVideoHeight)
            if (isBackgroundPlayActive) {
                MediaPlaybackService.updateState(context, false, currentPositionMs, currentDurationMs)
            }
        }
    }

    @JavascriptInterface
    fun onMediaEnded() {
        val myTabId = (webView as? com.onyx.browser.web.OnyxWebView)?.tabId?.takeIf { it.isNotBlank() }
        if (myTabId != null && currentPlayingTabId == myTabId) {
            isAudioOrVideoPlaying = false
            isVideoPlaying = false
            currentPlayingTabId = null
            currentPlayingWebView = null
        }

        mainHandler.post {
            onMediaStateListener?.invoke(false, false, lastVideoWidth, lastVideoHeight)
            if (isBackgroundPlayActive) {
                MediaPlaybackService.stop(context)
            }
        }
    }

    @JavascriptInterface
    fun onMediaMetadata(title: String?, artist: String?, artworkUrl: String?) {
        val cleanTitle = sanitizeMediaText(title)
        val cleanArtist = sanitizeMediaText(artist)
        val cleanArtwork = sanitizeArtworkUrl(artworkUrl)

        if (cleanTitle != null) currentTitle = cleanTitle
        if (cleanArtist != null) currentArtist = cleanArtist
        if (cleanArtwork != null) currentArtworkUrl = cleanArtwork

        mainHandler.post {
            if (isBackgroundPlayActive && isAudioOrVideoPlaying) {
                MediaPlaybackService.updateMetadata(
                    context,
                    currentTitle,
                    currentArtist,
                    currentArtworkUrl,
                    currentPositionMs,
                    currentDurationMs
                )
            }
        }
    }
}
