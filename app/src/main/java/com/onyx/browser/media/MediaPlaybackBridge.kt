package com.onyx.browser.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.onyx.browser.data.preferences.BrowserPreferences

/**
 * JavaScript interface that bridges HTML5 video/audio events and Web MediaSession
 * metadata to Android's MediaPlaybackService and PiP manager.
 */
class MediaPlaybackBridge(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences = BrowserPreferences.getInstance(context)

    companion object {
        @Volatile var isVideoPlaying: Boolean = false
        @Volatile var isAudioOrVideoPlaying: Boolean = false
    }

    @JavascriptInterface
    fun onMediaPlaying(title: String?, artist: String?, isVideo: Boolean) {
        isVideoPlaying = isVideo
        isAudioOrVideoPlaying = true

        val cleanTitle = title?.takeIf { it.isNotBlank() } ?: "Web Media"
        val cleanArtist = artist?.takeIf { it.isNotBlank() } ?: "Onyx Browser"

        mainHandler.post {
            if (preferences.isBackgroundPlayEnabled) {
                MediaPlaybackService.start(context, cleanTitle, cleanArtist, true)
            }
        }
    }

    @JavascriptInterface
    fun onMediaPaused() {
        isAudioOrVideoPlaying = false
        isVideoPlaying = false

        mainHandler.post {
            if (preferences.isBackgroundPlayEnabled) {
                MediaPlaybackService.updateState(context, false)
            }
        }
    }

    @JavascriptInterface
    fun onMediaEnded() {
        isAudioOrVideoPlaying = false
        isVideoPlaying = false

        mainHandler.post {
            if (preferences.isBackgroundPlayEnabled) {
                MediaPlaybackService.updateState(context, false)
            }
        }
    }

    @JavascriptInterface
    fun onMediaMetadata(title: String?, artist: String?) {
        if (!isAudioOrVideoPlaying) return
        val cleanTitle = title?.takeIf { it.isNotBlank() } ?: return
        val cleanArtist = artist?.takeIf { it.isNotBlank() } ?: "Onyx Browser"

        mainHandler.post {
            if (preferences.isBackgroundPlayEnabled) {
                MediaPlaybackService.updateState(context, true, cleanTitle, cleanArtist)
            }
        }
    }
}
