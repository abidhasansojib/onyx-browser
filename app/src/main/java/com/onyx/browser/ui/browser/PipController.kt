package com.onyx.browser.ui.browser

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityMainBinding
import com.onyx.browser.media.MediaPlaybackBridge
import com.onyx.browser.web.MediaPlaybackManager
import com.onyx.browser.web.OnyxWebView
import org.json.JSONObject

/**
 * Controller responsible for HTML5 fullscreen video hosting and Picture-in-Picture (PiP) transitions,
 * video aspect ratio calculation, PiP remote action notifications, and DOM video isolation.
 */
class PipController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val preferences: BrowserPreferences,
    private val getActiveWebView: () -> OnyxWebView?,
    private val getMediaTargetWebView: () -> OnyxWebView?,
    private val onHideUiForPip: () -> Unit,
    private val onRestoreUiFromPip: (isCustomVideo: Boolean) -> Unit
) {

    companion object {
        const val ACTION_PIP_PLAY_PAUSE = "com.onyx.browser.action.PIP_PLAY_PAUSE"
        const val ACTION_PIP_REWIND = "com.onyx.browser.action.PIP_REWIND"
        const val ACTION_PIP_FORWARD = "com.onyx.browser.action.PIP_FORWARD"
    }

    var customVideoView: View? = null
        private set
    var customViewCallback: WebChromeClient.CustomViewCallback? = null
        private set

    val isFullscreenVideoActive: Boolean
        get() = customVideoView != null

    var justExitedPip: Boolean = false

    var isCurrentlyInPip: Boolean
        get() = MediaPlaybackBridge.isCurrentlyInPip
        set(value) {
            MediaPlaybackBridge.isCurrentlyInPip = value
        }

    private var isReceiverRegistered = false

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_PLAY_PAUSE -> {
                    val targetWv = getMediaTargetWebView()
                    val wasPlaying = MediaPlaybackBridge.isMediaPlaying
                    val newPlayingState = !wasPlaying

                    MediaPlaybackBridge.isExplicitUserPause = !newPlayingState
                    MediaPlaybackBridge.isMediaPlaying = newPlayingState
                    MediaPlaybackBridge.isVideoPlaying = newPlayingState
                    MediaPlaybackBridge.isAudioOrVideoPlaying = newPlayingState

                    if (newPlayingState) {
                        targetWv?.evaluateJavascript(MediaPlaybackManager.playAllMediaScript, null)
                    } else {
                        targetWv?.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
                    }
                    updatePipParams(isVideoPlaying = newPlayingState)
                }
                ACTION_PIP_REWIND -> {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.getSeekMediaScript(-10), null)
                }
                ACTION_PIP_FORWARD -> {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.getSeekMediaScript(10), null)
                }
            }
        }
    }

    fun registerReceiver() {
        if (isReceiverRegistered) return
        val pipFilter = IntentFilter().apply {
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_REWIND)
            addAction(ACTION_PIP_FORWARD)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(pipReceiver, pipFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(pipReceiver, pipFilter)
        }
        isReceiverRegistered = true
    }

    fun unregisterReceiver() {
        if (!isReceiverRegistered) return
        try {
            activity.unregisterReceiver(pipReceiver)
        } catch (_: Exception) {}
        isReceiverRegistered = false
    }

    fun showCustomFullscreenVideo(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customVideoView != null) {
            callback.onCustomViewHidden()
            return
        }

        customVideoView = view
        customViewCallback = callback

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Insert at index 0 so fullscreenControlsOverlay stays on top
        binding.fullscreenCustomViewContainer.addView(
            view,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        binding.fullscreenCustomViewContainer.visibility = View.VISIBLE
        binding.fullscreenControlsOverlay.visibility = View.VISIBLE

        binding.btnFullscreenPip.setOnClickListener {
            enterPipMode()
        }
        binding.btnFullscreenClose.setOnClickListener {
            hideCustomFullscreenVideo()
        }

        updatePipParams(isVideoPlaying = true, shouldAutoEnter = false)
    }

    fun hideCustomFullscreenVideo() {
        binding.fullscreenControlsOverlay.visibility = View.GONE
        if (customVideoView != null) {
            binding.fullscreenCustomViewContainer.removeView(customVideoView)
        }
        binding.fullscreenCustomViewContainer.removeAllViews()
        binding.fullscreenCustomViewContainer.visibility = View.GONE
        customVideoView = null
        try {
            customViewCallback?.onCustomViewHidden()
        } catch (_: Exception) {}
        customViewCallback = null

        // Guarantee browser chrome is visible
        binding.topBar.visibility = View.VISIBLE
        binding.topBarDivider.visibility = View.VISIBLE
        getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)

        updatePipParams(isVideoPlaying = false, shouldAutoEnter = false)

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        binding.topBar.requestLayout()
        binding.root.requestLayout()
    }

    private fun Rational.coerceIn(min: Rational, max: Rational): Rational {
        val currentVal = toFloat()
        val minVal = min.toFloat()
        val maxVal = max.toFloat()
        return when {
            currentVal < minVal -> min
            currentVal > maxVal -> max
            else -> this
        }
    }

    fun buildPipActions(isPlaying: Boolean = MediaPlaybackBridge.isMediaPlaying): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()

        val actions = mutableListOf<RemoteAction>()

        // 1. Rewind 10s
        val rewindIntent = PendingIntent.getBroadcast(
            activity,
            101,
            Intent(ACTION_PIP_REWIND).setPackage(activity.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(activity, R.drawable.ic_fast_rewind),
                "Rewind 10s",
                "Rewind 10 seconds",
                rewindIntent
            )
        )

        // 2. Play / Pause
        val playPauseIntent = PendingIntent.getBroadcast(
            activity,
            102,
            Intent(ACTION_PIP_PLAY_PAUSE).setPackage(activity.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(activity, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                if (isPlaying) "Pause" else "Play",
                if (isPlaying) "Pause video" else "Play video",
                playPauseIntent
            )
        )

        // 3. Fast Forward 10s
        val forwardIntent = PendingIntent.getBroadcast(
            activity,
            103,
            Intent(ACTION_PIP_FORWARD).setPackage(activity.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(activity, R.drawable.ic_fast_forward),
                "Forward 10s",
                "Fast forward 10 seconds",
                forwardIntent
            )
        )

        return actions
    }

    fun updatePipParams(
        isVideoPlaying: Boolean = MediaPlaybackBridge.isMediaPlaying,
        width: Int = MediaPlaybackBridge.lastVideoWidth,
        height: Int = MediaPlaybackBridge.lastVideoHeight,
        shouldAutoEnter: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val rational = Rational(width.coerceAtLeast(1), height.coerceAtLeast(1))
                    .coerceIn(Rational(1, 2), Rational(2, 1))
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                    .setActions(buildPipActions(isVideoPlaying))

                // Video-only PiP: crop strictly to the video viewport using sourceRectHint
                if (customVideoView != null) {
                    val rect = Rect()
                    customVideoView?.getGlobalVisibleRect(rect)
                    if (!rect.isEmpty) {
                        builder.setSourceRectHint(rect)
                    }
                } else {
                    val bounds = MediaPlaybackBridge.lastVideoBounds
                    val activeWv = getActiveWebView()
                    if (bounds != null && activeWv != null && isVideoPlaying) {
                        val location = IntArray(2)
                        activeWv.getLocationInWindow(location)
                        val density = activity.resources.displayMetrics.density
                        val left = (location[0] + bounds.left * density).toInt()
                        val top = (location[1] + bounds.top * density).toInt()
                        val right = (location[0] + bounds.right * density).toInt()
                        val bottom = (location[1] + bounds.bottom * density).toInt()
                        val rect = Rect(
                            left.coerceAtLeast(0),
                            top.coerceAtLeast(0),
                            right.coerceAtMost(activity.resources.displayMetrics.widthPixels),
                            bottom.coerceAtMost(activity.resources.displayMetrics.heightPixels)
                        )
                        if (rect.width() > 20 && rect.height() > 20) {
                            builder.setSourceRectHint(rect)
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(false)
                }
                activity.setPictureInPictureParams(builder.build())
            } catch (_: Exception) {}
        }
    }

    fun requestInPageVideoPip() {
        if (!preferences.isPipEnabled) return
        val inPip = isCurrentlyInPip || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode)
        if (inPip || justExitedPip) return
        if (customVideoView != null) {
            enterPipMode()
            return
        }
        val activeWv = getActiveWebView()
        if (activeWv == null) {
            Toast.makeText(activity, "No active webpage to extract video", Toast.LENGTH_SHORT).show()
            return
        }
        isolateAndEnterPip(activeWv)
    }

    private fun isolateAndEnterPip(activeWv: OnyxWebView) {
        val inPip = isCurrentlyInPip || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode)
        if (inPip || justExitedPip) return
        activeWv.evaluateJavascript(MediaPlaybackManager.isolateVideoForPipScript) { res ->
            val inPipNow = isCurrentlyInPip || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode)
            if (inPipNow || justExitedPip) return@evaluateJavascript
            val hasVideo = res?.contains("\"found\":true") == true || res?.contains("\"found\": true") == true
            if (hasVideo) {
                try {
                    val unescaped = res?.trim('"', '\'')?.replace("\\\"", "\"") ?: "{}"
                    val jsonObj = JSONObject(unescaped)
                    val vw = jsonObj.optInt("width", 0)
                    val vh = jsonObj.optInt("height", 0)
                    if (vw > 0 && vh > 0) {
                        MediaPlaybackBridge.lastVideoWidth = vw
                        MediaPlaybackBridge.lastVideoHeight = vh
                    }
                } catch (_: Exception) {}
                activeWv.postDelayed({
                    val inPipLater = isCurrentlyInPip || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode)
                    if (!justExitedPip && !inPipLater) {
                        enterPipMode()
                    }
                }, 100)
            } else if (MediaPlaybackBridge.isVideoAvailable || MediaPlaybackBridge.isVideoPlaying || MediaPlaybackBridge.isVideoPresent) {
                activeWv.postDelayed({
                    val inPipLater = isCurrentlyInPip || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode)
                    if (!justExitedPip && !inPipLater) {
                        enterPipMode()
                    }
                }, 50)
            } else {
                Toast.makeText(activity, "No active video found to enter Picture-in-Picture", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun enterPipMode() {
        if (!preferences.isPipEnabled) return
        val inPip = isCurrentlyInPip || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode)
        if (inPip || justExitedPip) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (activity is LifecycleOwner && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    return
                }
                val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                val isPipAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps?.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), activity.packageName) == AppOpsManager.MODE_ALLOWED
                } else {
                    @Suppress("DEPRECATION")
                    appOps?.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), activity.packageName) == AppOpsManager.MODE_ALLOWED
                }
                if (isPipAllowed == false) {
                    Toast.makeText(activity, "Please enable Picture-in-Picture permission in Android Settings", Toast.LENGTH_LONG).show()
                    val intent = Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", Uri.parse("package:${activity.packageName}"))
                    try {
                        activity.startActivity(intent)
                    } catch (_: Exception) {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                    }
                    binding.topBar.visibility = View.VISIBLE
                    binding.topBarDivider.visibility = View.VISIBLE
                    if (customVideoView == null) {
                        getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
                    }
                    return
                }

                val w = MediaPlaybackBridge.lastVideoWidth.coerceAtLeast(1)
                val h = MediaPlaybackBridge.lastVideoHeight.coerceAtLeast(1)
                val rational = Rational(w, h).coerceIn(Rational(1, 2), Rational(2, 1))

                val paramsBuilder = PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                    .setActions(buildPipActions())

                if (customVideoView != null) {
                    val rect = Rect()
                    customVideoView?.getGlobalVisibleRect(rect)
                    if (!rect.isEmpty) {
                        paramsBuilder.setSourceRectHint(rect)
                    }
                } else {
                    onHideUiForPip()

                    val screenW = activity.resources.displayMetrics.widthPixels
                    val screenH = activity.resources.displayMetrics.heightPixels
                    val videoRect: Rect
                    if (screenW.toFloat() / screenH.toFloat() > w.toFloat() / h.toFloat()) {
                        val videoH = screenH
                        val videoW = ((screenH.toFloat() * w.toFloat()) / h.toFloat()).toInt().coerceAtMost(screenW)
                        val left = (screenW - videoW) / 2
                        videoRect = Rect(left, 0, left + videoW, screenH)
                    } else {
                        val videoW = screenW
                        val videoH = ((screenW.toFloat() * h.toFloat()) / w.toFloat()).toInt().coerceAtMost(screenH)
                        val top = (screenH - videoH) / 2
                        videoRect = Rect(0, top, screenW, top + videoH)
                    }
                    if (videoRect.width() > 20 && videoRect.height() > 20) {
                        paramsBuilder.setSourceRectHint(videoRect)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    paramsBuilder.setAutoEnterEnabled(false)
                }

                isCurrentlyInPip = true
                val entered = activity.enterPictureInPictureMode(paramsBuilder.build())
                if (!entered) {
                    isCurrentlyInPip = false
                    binding.topBar.visibility = View.VISIBLE
                    binding.topBarDivider.visibility = View.VISIBLE
                    if (customVideoView == null) {
                        getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
                    }
                }
            } catch (e: Exception) {
                isCurrentlyInPip = false
                if (customVideoView == null) {
                    getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
                }
            }
        } else {
            if (customVideoView == null) {
                getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
            }
            Toast.makeText(activity, "Picture-in-Picture requires Android 8.0+", Toast.LENGTH_SHORT).show()
        }
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        val activeWv = getActiveWebView()
        isCurrentlyInPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            onHideUiForPip()

            if (customVideoView != null) {
                binding.fullscreenCustomViewContainer.visibility = View.VISIBLE
                binding.webViewContainer.visibility = View.GONE
            } else {
                binding.fullscreenCustomViewContainer.visibility = View.GONE
                binding.webViewContainer.visibility = View.VISIBLE
                binding.webViewContainer.setBackgroundColor(android.graphics.Color.BLACK)
                activeWv?.evaluateJavascript(MediaPlaybackManager.isolateVideoForPipScript, null)
            }
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            isCurrentlyInPip = false
            MediaPlaybackBridge.isExplicitUserPause = false
            justExitedPip = true
            binding.root.postDelayed({ justExitedPip = false }, 2500)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    activity.setPictureInPictureParams(
                        PictureInPictureParams.Builder()
                            .setAutoEnterEnabled(false)
                            .build()
                    )
                } catch (_: Exception) {}
            }

            if (!MediaPlaybackBridge.isVideoPlaying) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            binding.topBar.visibility = View.VISIBLE
            binding.topBarDivider.visibility = View.VISIBLE
            binding.webViewContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)

            onRestoreUiFromPip(customVideoView != null)

            if (customVideoView != null) {
                binding.fullscreenControlsOverlay.visibility = View.VISIBLE
                binding.fullscreenCustomViewContainer.visibility = View.VISIBLE
                binding.webViewContainer.visibility = View.GONE
            } else {
                binding.fullscreenCustomViewContainer.visibility = View.GONE
                binding.fullscreenControlsOverlay.visibility = View.GONE
                activeWv?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
            }
            updatePipParams(shouldAutoEnter = false)
        }
    }
}
