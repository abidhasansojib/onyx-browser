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

    // Throttle progress forwarding to the service: max once per 3 seconds
    @Volatile private var lastProgressDispatchMs = 0L
    private var pendingEndedRunnable: Runnable? = null
    private var resumeWindowStartMs = 0L
    private var resumeCount = 0

    companion object {
        private const val PROGRESS_DISPATCH_THROTTLE_MS = 3000L

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
        @Volatile var lastViewportWidth: Float = 0f
        @Volatile var lastViewportHeight: Float = 0f

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
                lastViewportWidth = 0f
                lastViewportHeight = 0f
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
            // Never reset isCurrentlyInPip here: PiP mode is an Activity window state owned by
            // PipController, and playlist/page navigations can occur while remaining inside PiP.
            isExplicitUserPause = false
            currentPlayingTabId = null
            currentPlayingWebView = null
            lastVideoBounds = null
            lastViewportWidth = 0f
            lastViewportHeight = 0f
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

    /** Never auto-resume during a phone/VoIP call; the system paused us on purpose. */
    private fun isInCall(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return false
        return am.mode == android.media.AudioManager.MODE_IN_CALL ||
                am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
                am.mode == android.media.AudioManager.MODE_RINGTONE
    }

    /**
     * Caps background auto-resumes to 3 per 15s window so a page that keeps pausing itself
     * (e.g. an explicit "paused" state the heuristic missed) cannot loop forever.
     */
    private fun consumeResumeBudget(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - resumeWindowStartMs > 15_000L) {
            resumeWindowStartMs = now
            resumeCount = 0
        }
        if (resumeCount >= 3) return false
        resumeCount++
        return true
    }

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
        onVideoBoundsChangedWithViewport(left, top, right, bottom, 0f, 0f)
    }

    @JavascriptInterface
    fun onVideoBoundsChangedWithViewport(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        viewportWidth: Float,
        viewportHeight: Float
    ) {
        // Ignore PiP-sized viewport bounds so pre-PiP inline video rect stays intact
        if (isCurrentlyInPip) return
        if (right - left < 16f || bottom - top < 16f) return
        val myTabId = (webView as? com.onyx.browser.web.OnyxWebView)?.tabId?.takeIf { it.isNotBlank() }
        if (myTabId != null && currentPlayingTabId != null && currentPlayingTabId != myTabId) {
            return
        }
        lastVideoBounds = android.graphics.RectF(left, top, right, bottom)
        if (viewportWidth > 0f && viewportHeight > 0f) {
            lastViewportWidth = viewportWidth
            lastViewportHeight = viewportHeight
        }
        mainHandler.post {
            onVideoBoundsListener?.invoke(left, top, right, bottom)
        }
    }

    @JavascriptInterface
    fun onVideoDimensionsChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val myTabId = (webView as? com.onyx.browser.web.OnyxWebView)?.tabId?.takeIf { it.isNotBlank() }
        if (myTabId != null && currentPlayingTabId != null && currentPlayingTabId != myTabId) {
            return
        }
        if (lastVideoWidth == width && lastVideoHeight == height) return
        lastVideoWidth = width
        lastVideoHeight = height
        mainHandler.post {
            if (isVideoPlaying || isCurrentlyInPip) {
                onMediaStateListener?.invoke(isMediaPlaying, true, width, height)
            }
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
            if (isBackgroundPlayActive) {
                if (isPlaying) {
                    MediaPlaybackService.start(
                        context,
                        cleanTitle,
                        cleanArtist,
                        currentArtworkUrl,
                        currentPositionMs,
                        currentDurationMs,
                        true
                    )
                } else if (MediaPlaybackService.isServiceRunning) {
                    MediaPlaybackService.updateState(context, false, currentPositionMs, currentDurationMs)
                }
            } else if (!isPlaying && MediaPlaybackService.isServiceRunning) {
                MediaPlaybackService.updateState(context, false, currentPositionMs, currentDurationMs)
            }
        }
    }

    @JavascriptInterface
    fun onMediaProgress(positionSec: Double, durationSec: Double) {
        val newPositionMs = (positionSec * 1000).toLong().coerceAtLeast(0L)
        val newDurationMs = (durationSec * 1000).toLong().coerceAtLeast(0L)
        currentPositionMs = newPositionMs
        currentDurationMs = newDurationMs

        // Throttle: only forward progress to the service at most once every 3 seconds.
        // JS timeupdate fires every ~250ms — dispatching startService() on every tick causes
        // main-thread binder IPC jank. The MediaSession playback state already tracks live
        // position via the elapsed-realtime clock set in updatePlaybackState().
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastProgressDispatchMs < PROGRESS_DISPATCH_THROTTLE_MS) return
        lastProgressDispatchMs = now

        mainHandler.post {
            if (isBackgroundPlayActive && isAudioOrVideoPlaying) {
                MediaPlaybackService.updateProgress(context, currentPositionMs, currentDurationMs)
            }
        }
    }

    @JavascriptInterface
    fun onMediaPlaying(title: String?, artist: String?, isVideo: Boolean) {
        pendingEndedRunnable?.let { mainHandler.removeCallbacks(it) }
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
        handleMediaPaused(isUserIntent = false)
    }

    @JavascriptInterface
    fun onMediaPausedWithIntent(isUserIntent: Boolean) {
        if (isUserIntent) {
            isExplicitUserPause = true
        }
        handleMediaPaused(isUserIntent = isUserIntent)
    }

    private fun handleMediaPaused(isUserIntent: Boolean) {
        val inPip = isCurrentlyInPip
        // Only fight site/engine-initiated pauses while the app is actually backgrounded.
        // In the foreground every pause is respected so site controls (ads, carousels,
        // mute/pause buttons) behave normally.
        val shouldSuppress = !inPip &&
                isAppInBackground &&
                isBackgroundPlayActive &&
                !isUserIntent &&
                !isExplicitUserPause &&
                !isInCall(context) &&
                consumeResumeBudget()

        if (shouldSuppress) {
            // Background pause caused by visibility/occlusion without user intent: resume.
            mainHandler.postDelayed({
                if (isExplicitUserPause || !isAppInBackground) return@postDelayed
                val wv = currentPlayingWebView?.get() ?: webView
                wv?.evaluateJavascript(com.onyx.browser.web.MediaPlaybackManager.playAllMediaScript, null)
            }, 150L)
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
        mainHandler.post {
            isAudioOrVideoPlaying = false
            isVideoPlaying = false
            pendingEndedRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                if (!isAudioOrVideoPlaying && !isVideoPlaying) {
                    if (myTabId != null && currentPlayingTabId == myTabId) {
                        currentPlayingTabId = null
                        currentPlayingWebView = null
                    }
                    onMediaStateListener?.invoke(false, false, lastVideoWidth, lastVideoHeight)
                    if (isBackgroundPlayActive) {
                        if (isAppInBackground && MediaPlaybackService.isServiceRunning) {
                            // Keep the foreground service alive (paused) so the next playlist item
                            // can resume; Android 12+ forbids restarting it from the background.
                            MediaPlaybackService.updateState(context, false, currentPositionMs, currentDurationMs)
                        } else {
                            MediaPlaybackService.stop(context)
                        }
                    }
                }
            }
            pendingEndedRunnable = runnable
            // Brave-parity: 2-second grace gap for playlist autoplay on locked screen
            mainHandler.postDelayed(runnable, 2000L)
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
