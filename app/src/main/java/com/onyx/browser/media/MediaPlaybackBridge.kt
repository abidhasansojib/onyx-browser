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
            get() = isAudioOrVideoPlaying
            set(value) {
                isAudioOrVideoPlaying = value
            }
        @Volatile var lastVideoWidth: Int = 16
        @Volatile var lastVideoHeight: Int = 9
        @Volatile var currentPositionMs: Long = 0L
        @Volatile var currentDurationMs: Long = 0L
        @Volatile var currentTitle: String = "Web Media"
        @Volatile var currentArtist: String = "Onyx Browser"
        @Volatile var currentArtworkUrl: String? = null
        @Volatile var currentVideoSrc: String? = null

        @Volatile var lastVideoBounds: android.graphics.RectF? = null

        @Volatile var currentPlayingTabId: String? = null
        @Volatile var currentPlayingWebView: java.lang.ref.WeakReference<android.webkit.WebView>? = null

        var onMediaStateListener: ((isPlaying: Boolean, isVideo: Boolean, width: Int, height: Int) -> Unit)? = null
        var onMediaPlaybackStartedListener: ((playingWebView: android.webkit.WebView) -> Unit)? = null
        var onVideoBoundsListener: ((left: Float, top: Float, right: Float, bottom: Float) -> Unit)? = null
        var onVideoSourceListener: ((src: String) -> Unit)? = null
        var onPipRequestedListener: (() -> Unit)? = null
        var onPipExitListener: (() -> Unit)? = null

        fun onTabClosed(tabId: String, context: Context) {
            val playingId = currentPlayingTabId
            val playingWv = currentPlayingWebView?.get()
            val playingWvTabId = (playingWv as? com.onyx.browser.web.OnyxWebView)?.tabId

            if (playingId == tabId || playingWvTabId == tabId || (!isAudioOrVideoPlaying && !isVideoPlaying && playingId == null)) {
                resetMediaPlayback(context)
            }
        }

        fun resetMediaPlayback(context: Context) {
            isVideoPlaying = false
            isAudioOrVideoPlaying = false
            currentPlayingTabId = null
            currentPlayingWebView = null
            currentVideoSrc = null
            lastVideoBounds = null
            currentPositionMs = 0L
            currentDurationMs = 0L
            currentTitle = "Web Media"
            currentArtist = "Onyx Browser"
            currentArtworkUrl = null

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onMediaStateListener?.invoke(false, false, lastVideoWidth, lastVideoHeight)
                MediaPlaybackService.stop(context)
            }
        }
    }

    @JavascriptInterface
    fun onVideoSourceDetected(src: String?) {
        if (!src.isNullOrBlank()) {
            currentVideoSrc = src
            mainHandler.post {
                onVideoSourceListener?.invoke(src)
            }
        }
    }

    @JavascriptInterface
    fun requestVideoPip() {
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
        isVideoPlaying = isVideo && isPlaying
        isAudioOrVideoPlaying = isPlaying

        if (videoWidth > 0 && videoHeight > 0) {
            lastVideoWidth = videoWidth
            lastVideoHeight = videoHeight
        }

        currentPositionMs = (positionSec * 1000).toLong().coerceAtLeast(0L)
        currentDurationMs = (durationSec * 1000).toLong().coerceAtLeast(0L)

        val cleanTitle = title?.takeIf { it.isNotBlank() } ?: "Web Media"
        val cleanArtist = artist?.takeIf { it.isNotBlank() } ?: "Onyx Browser"
        currentTitle = cleanTitle
        currentArtist = cleanArtist
        currentArtworkUrl = artworkUrl?.takeIf { it.isNotBlank() }

        if (isPlaying) {
            val myTabId = (webView as? com.onyx.browser.web.OnyxWebView)?.tabId?.takeIf { it.isNotBlank() }
            if (myTabId != null) {
                currentPlayingTabId = myTabId
            }
            if (webView != null) {
                currentPlayingWebView = java.lang.ref.WeakReference(webView)
            }
        }

        mainHandler.post {
            onMediaStateListener?.invoke(isPlaying, isVideo, lastVideoWidth, lastVideoHeight)
            if (isPlaying && webView != null) {
                onMediaPlaybackStartedListener?.invoke(webView)
            }
            if (preferences.isBackgroundPlayEnabled && isPlaying) {
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
            if (preferences.isBackgroundPlayEnabled && isAudioOrVideoPlaying) {
                MediaPlaybackService.updateProgress(context, currentPositionMs, currentDurationMs)
            }
        }
    }

    @JavascriptInterface
    fun onMediaPlaying(title: String?, artist: String?, isVideo: Boolean) {
        isVideoPlaying = isVideo
        isAudioOrVideoPlaying = true

        val cleanTitle = title?.takeIf { it.isNotBlank() } ?: "Web Media"
        val cleanArtist = artist?.takeIf { it.isNotBlank() } ?: "Onyx Browser"
        currentTitle = cleanTitle
        currentArtist = cleanArtist

        val myTabId = (webView as? com.onyx.browser.web.OnyxWebView)?.tabId?.takeIf { it.isNotBlank() }
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
            if (preferences.isBackgroundPlayEnabled) {
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
        isAudioOrVideoPlaying = false
        isVideoPlaying = false

        mainHandler.post {
            onMediaStateListener?.invoke(false, false, lastVideoWidth, lastVideoHeight)
            if (preferences.isBackgroundPlayEnabled) {
                MediaPlaybackService.updateState(context, false, currentPositionMs, currentDurationMs)
            }
        }
    }

    @JavascriptInterface
    fun onMediaEnded() {
        isAudioOrVideoPlaying = false
        isVideoPlaying = false

        mainHandler.post {
            onMediaStateListener?.invoke(false, false, lastVideoWidth, lastVideoHeight)
            if (preferences.isBackgroundPlayEnabled) {
                MediaPlaybackService.updateState(context, false, currentPositionMs, currentDurationMs)
            }
        }
    }

    @JavascriptInterface
    fun onMediaMetadata(title: String?, artist: String?, artworkUrl: String?) {
        val cleanTitle = title?.takeIf { it.isNotBlank() }
        val cleanArtist = artist?.takeIf { it.isNotBlank() }
        val cleanArtwork = artworkUrl?.takeIf { it.isNotBlank() }

        if (cleanTitle != null) currentTitle = cleanTitle
        if (cleanArtist != null) currentArtist = cleanArtist
        if (cleanArtwork != null) currentArtworkUrl = cleanArtwork

        mainHandler.post {
            if (preferences.isBackgroundPlayEnabled && isAudioOrVideoPlaying) {
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
