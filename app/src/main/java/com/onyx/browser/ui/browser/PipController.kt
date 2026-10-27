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
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.View
import android.view.ViewGroup
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
        private const val PIP_EXIT_DEBOUNCE_MS = 600L
        private const val FULLSCREEN_OVERLAY_AUTO_HIDE_MS = 3500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var exitGraceRunnable: Runnable? = null
    private var fullscreenOverlayHideRunnable: Runnable? = null

    var customVideoView: View? = null
        private set
    var customViewCallback: WebChromeClient.CustomViewCallback? = null
        private set

    val isFullscreenVideoActive: Boolean
        get() = customVideoView != null

    /** Short debounce after exiting PiP to ignore bounce re-entry / unsolicited JS PiP calls. */
    var justExitedPip: Boolean = false

    /**
     * Set true when onPictureInPictureModeChanged(false) fires.
     * - Cleared in onActivityResumed() when the user expands PiP back into the browser.
     * - Consumed in MainActivity.onStop() when the user dismisses PiP via the 'X' close button.
     */
    var pendingPipCloseCheck: Boolean = false
        private set

    var isCurrentlyInPip: Boolean
        get() = MediaPlaybackBridge.isCurrentlyInPip
        set(value) {
            MediaPlaybackBridge.isCurrentlyInPip = value
        }

    private var isReceiverRegistered = false

    fun isInPip(): Boolean {
        return isCurrentlyInPip ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode)
    }

    /**
     * Returns true strictly when video (not audio-only) is actively playing on the currently
     * displayed foreground tab, or when a fullscreen custom video view is active.
     */
    fun isActiveTabVideoPlaying(): Boolean {
        if (isFullscreenVideoActive) return true
        if (!MediaPlaybackBridge.isVideoPlaying) return false
        val activeWv = getActiveWebView() ?: return false
        val playingWv = MediaPlaybackBridge.currentPlayingWebView?.get()
        val playingTabId = MediaPlaybackBridge.currentPlayingTabId
        return (playingWv != null && playingWv === activeWv) ||
            (!playingTabId.isNullOrBlank() && playingTabId == activeWv.tabId)
    }

    fun onActivityResumed() {
        pendingPipCloseCheck = false
    }

    fun consumePendingPipClose(): Boolean {
        val wasPending = pendingPipCloseCheck
        pendingPipCloseCheck = false
        return wasPending
    }

    fun disarmAutoEnter() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isAutoEnterArmed && !isInPip()) {
            isAutoEnterArmed = false
            safeSetPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAutoEnterEnabled(false)
                    .build()
            )
        }
    }

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
                    updatePipParams(isVideoPlaying = newPlayingState, shouldAutoEnter = false)
                    if (com.onyx.browser.media.MediaPlaybackService.isServiceRunning) {
                        com.onyx.browser.media.MediaPlaybackService.updateState(activity, newPlayingState)
                    }
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
        exitGraceRunnable?.let { mainHandler.removeCallbacks(it) }
        exitGraceRunnable = null
        cancelFullscreenOverlayAutoHide()
        if (!isReceiverRegistered) return
        try {
            activity.unregisterReceiver(pipReceiver)
        } catch (_: Exception) {}
        isReceiverRegistered = false
    }

    private fun cancelFullscreenOverlayAutoHide() {
        fullscreenOverlayHideRunnable?.let { mainHandler.removeCallbacks(it) }
        fullscreenOverlayHideRunnable = null
        binding.fullscreenControlsOverlay.animate().cancel()
        binding.fullscreenControlsOverlay.alpha = 1f
    }

    private fun scheduleFullscreenOverlayAutoHide() {
        fullscreenOverlayHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (customVideoView != null && !isInPip()) {
                binding.fullscreenControlsOverlay.animate()
                    .alpha(0f)
                    .setDuration(200L)
                    .withEndAction {
                        binding.fullscreenControlsOverlay.visibility = View.GONE
                        binding.fullscreenControlsOverlay.alpha = 1f
                    }
                    .start()
            }
        }
        fullscreenOverlayHideRunnable = runnable
        mainHandler.postDelayed(runnable, FULLSCREEN_OVERLAY_AUTO_HIDE_MS)
    }

    fun onFullscreenUserInteraction() {
        if (customVideoView == null || isInPip()) return
        cancelFullscreenOverlayAutoHide()
        binding.fullscreenControlsOverlay.visibility = View.VISIBLE
        binding.fullscreenControlsOverlay.bringToFront()
        binding.fullscreenCustomViewContainer.bringChildToFront(binding.fullscreenControlsOverlay)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            binding.fullscreenControlsOverlay.translationZ = 100f
        }
        scheduleFullscreenOverlayAutoHide()
    }

    private fun removeCustomVideoViewsOnly() {
        customVideoView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        // Remove any stray dynamic child views while strictly preserving fullscreenControlsOverlay
        for (i in binding.fullscreenCustomViewContainer.childCount - 1 downTo 0) {
            val child = binding.fullscreenCustomViewContainer.getChildAt(i)
            if (child !== binding.fullscreenControlsOverlay) {
                binding.fullscreenCustomViewContainer.removeViewAt(i)
            }
        }
    }

    fun showCustomFullscreenVideo(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customVideoView != null) {
            callback.onCustomViewHidden()
            return
        }

        removeCustomVideoViewsOnly()
        customVideoView = view
        customViewCallback = callback

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Insert at index 0 so fullscreenControlsOverlay stays on top
        (view.parent as? ViewGroup)?.removeView(view)
        binding.fullscreenCustomViewContainer.addView(
            view,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        binding.fullscreenCustomViewContainer.visibility = View.VISIBLE

        binding.btnFullscreenPip.setOnClickListener {
            enterPipMode()
        }
        binding.btnFullscreenClose.setOnClickListener {
            hideCustomFullscreenVideo()
        }

        if (!isInPip()) {
            onFullscreenUserInteraction()
        } else {
            binding.fullscreenControlsOverlay.visibility = View.GONE
        }

        updatePipParams(isVideoPlaying = true, shouldAutoEnter = true)
        view.post {
            if (customVideoView === view && !isInPip()) {
                updatePipParams(isVideoPlaying = true, shouldAutoEnter = true)
            }
        }
    }

    fun hideCustomFullscreenVideo() {
        cancelFullscreenOverlayAutoHide()
        binding.fullscreenControlsOverlay.visibility = View.GONE
        removeCustomVideoViewsOnly()
        binding.fullscreenCustomViewContainer.visibility = View.GONE
        customVideoView = null
        try {
            customViewCallback?.onCustomViewHidden()
        } catch (_: Exception) {}
        customViewCallback = null

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // If Chromium exited element fullscreen while the Activity is still inside PiP,
        // seamlessly hand off the PiP window to the isolated in-page <video> without restoring browser UI.
        if (isInPip()) {
            onHideUiForPip()
            binding.webViewContainer.visibility = View.VISIBLE
            binding.webViewContainer.setBackgroundColor(Color.BLACK)
            getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.isolateVideoForPipScript, null)
            return
        }

        // Guarantee browser chrome and web content container are restored to visible normal state
        binding.topBar.visibility = View.VISIBLE
        binding.topBarDivider.visibility = View.VISIBLE
        binding.webViewContainer.visibility = View.VISIBLE
        binding.webViewContainer.setBackgroundColor(Color.TRANSPARENT)
        onRestoreUiFromPip(false)

        val activeWv = getActiveWebView()
        activeWv?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
        activeWv?.requestLayout()

        val inlineStillPlaying = isActiveTabVideoPlaying()
        updatePipParams(isVideoPlaying = inlineStillPlaying, shouldAutoEnter = inlineStillPlaying)

        WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        binding.topBar.requestLayout()
        binding.webViewContainer.requestLayout()
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

    fun safeSetPictureInPictureParams(params: PictureInPictureParams): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            activity.setPictureInPictureParams(params)
            true
        } catch (_: Exception) {
            // Shield against Android OEM / IPC framework crashes (Brave PictureInPictureActivity pattern)
            false
        }
    }

    fun isPipAllowedBySystem(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), activity.packageName) == AppOpsManager.MODE_ALLOWED
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), activity.packageName) == AppOpsManager.MODE_ALLOWED
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun computeVideoSourceRect(): Rect? {
        if (customVideoView != null) {
            val rect = Rect()
            val visible = customVideoView?.getGlobalVisibleRect(rect) == true
            return if (visible && rect.width() > 24 && rect.height() > 24) rect else null
        }

        val bounds: RectF = MediaPlaybackBridge.lastVideoBounds ?: return null
        val activeWv = getActiveWebView() ?: return null
        if (activeWv.width <= 0 || activeWv.height <= 0) return null

        val location = IntArray(2)
        activeWv.getLocationInWindow(location)
        val density = activity.resources.displayMetrics.density
        val vpW = MediaPlaybackBridge.lastViewportWidth
        val vpH = MediaPlaybackBridge.lastViewportHeight
        val scaleX = if (vpW > 0f) activeWv.width.toFloat() / vpW else density
        val scaleY = if (vpH > 0f) activeWv.height.toFloat() / vpH else density

        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        val wvLeft = location[0].coerceIn(0, screenW)
        val wvTop = location[1].coerceIn(0, screenH)
        val wvRight = (location[0] + activeWv.width).coerceIn(wvLeft, screenW)
        val wvBottom = (location[1] + activeWv.height).coerceIn(wvTop, screenH)

        val left = (location[0] + bounds.left * scaleX).toInt().coerceIn(wvLeft, wvRight)
        val top = (location[1] + bounds.top * scaleY).toInt().coerceIn(wvTop, wvBottom)
        val right = (location[0] + bounds.right * scaleX).toInt().coerceIn(wvLeft, wvRight)
        val bottom = (location[1] + bounds.bottom * scaleY).toInt().coerceIn(wvTop, wvBottom)

        val rect = Rect(left, top, right, bottom)
        return if (rect.width() > 24 && rect.height() > 24) rect else null
    }

    /** True while Android 12+ setAutoEnterEnabled(true) is armed on the activity. */
    var isAutoEnterArmed: Boolean = false
        private set

    fun updatePipParams(
        isVideoPlaying: Boolean = isActiveTabVideoPlaying(),
        width: Int = MediaPlaybackBridge.lastVideoWidth,
        height: Int = MediaPlaybackBridge.lastVideoHeight,
        shouldAutoEnter: Boolean = isVideoPlaying
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val inPip = isInPip()
                val activeTabPlaying = isActiveTabVideoPlaying()
                val isPlayingForActions = if (inPip) MediaPlaybackBridge.isMediaPlaying else (isVideoPlaying && activeTabPlaying)

                val safeW = if (width > 0) width else 16
                val safeH = if (height > 0) height else 9
                val rational = Rational(safeW, safeH)
                    .coerceIn(Rational(1, 2), Rational(2, 1))
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                    .setActions(buildPipActions(isPlayingForActions))

                if (!inPip && activeTabPlaying) {
                    computeVideoSourceRect()?.let { rect ->
                        builder.setSourceRectHint(rect)
                    }
                }

                var canAutoEnter = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val isForeground = if (activity is LifecycleOwner) {
                        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    } else {
                        true
                    }
                    canAutoEnter = !inPip &&
                        shouldAutoEnter &&
                        preferences.isPipEnabled &&
                        activeTabPlaying &&
                        !justExitedPip &&
                        !activity.isFinishing &&
                        isForeground &&
                        isPipAllowedBySystem()
                    builder.setAutoEnterEnabled(canAutoEnter)
                }
                val applied = safeSetPictureInPictureParams(builder.build())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    isAutoEnterArmed = applied && canAutoEnter
                }
            } catch (_: Exception) {}
        }
    }

    fun requestInPageVideoPip() {
        if (!preferences.isPipEnabled) return
        if (isInPip() || justExitedPip) return
        if (customVideoView != null) {
            enterPipMode()
            return
        }
        val activeWv = getActiveWebView()
        if (activeWv == null) {
            Toast.makeText(activity, "No active webpage to extract video", Toast.LENGTH_SHORT).show()
            return
        }

        // Probe active video dimensions & viewport bounds without mutating DOM before PiP transition
        activeWv.evaluateJavascript(MediaPlaybackManager.probeVideoForPipScript) { res ->
            if (isInPip() || justExitedPip) return@evaluateJavascript
            var found = false
            try {
                val raw = res?.trim() ?: ""
                val unescaped = if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
                    org.json.JSONTokener(raw).nextValue().toString()
                } else {
                    raw
                }
                val jsonObj = JSONObject(unescaped)
                found = jsonObj.optBoolean("found", false)
                if (found) {
                    val vw = jsonObj.optInt("width", 0)
                    val vh = jsonObj.optInt("height", 0)
                    if (vw > 0 && vh > 0) {
                        MediaPlaybackBridge.lastVideoWidth = vw
                        MediaPlaybackBridge.lastVideoHeight = vh
                    }
                    val left = jsonObj.optDouble("left", 0.0).toFloat()
                    val top = jsonObj.optDouble("top", 0.0).toFloat()
                    val right = jsonObj.optDouble("right", 0.0).toFloat()
                    val bottom = jsonObj.optDouble("bottom", 0.0).toFloat()
                    val viewportW = jsonObj.optDouble("vw", 0.0).toFloat()
                    val viewportH = jsonObj.optDouble("vh", 0.0).toFloat()
                    if (right - left >= 16f && bottom - top >= 16f) {
                        MediaPlaybackBridge.lastVideoBounds = RectF(left, top, right, bottom)
                        if (viewportW > 0f && viewportH > 0f) {
                            MediaPlaybackBridge.lastViewportWidth = viewportW
                            MediaPlaybackBridge.lastViewportHeight = viewportH
                        }
                    }
                }
            } catch (_: Exception) {
                found = res?.contains("\"found\":true") == true || res?.contains("\"found\": true") == true
            }

            if (found || MediaPlaybackBridge.isVideoAvailableForTab(activeWv.tabId)) {
                enterPipMode()
            } else {
                Toast.makeText(activity, "No active video found to enter Picture-in-Picture", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyPipUiState() {
        cancelFullscreenOverlayAutoHide()
        binding.fullscreenControlsOverlay.visibility = View.GONE
        onHideUiForPip()

        if (customVideoView != null) {
            binding.fullscreenCustomViewContainer.visibility = View.VISIBLE
            binding.webViewContainer.visibility = View.GONE
        } else {
            binding.fullscreenCustomViewContainer.visibility = View.GONE
            binding.webViewContainer.visibility = View.VISIBLE
            binding.webViewContainer.setBackgroundColor(Color.BLACK)
            getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.isolateVideoForPipScript, null)
        }
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun enterPipMode() {
        if (!preferences.isPipEnabled) return
        if (isInPip() || justExitedPip) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (activity is LifecycleOwner && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return
                }
                if (!isPipAllowedBySystem()) {
                    Toast.makeText(activity, "Please enable Picture-in-Picture permission in Android Settings", Toast.LENGTH_LONG).show()
                    val intent = Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", Uri.parse("package:${activity.packageName}"))
                    try {
                        activity.startActivity(intent)
                    } catch (_: Exception) {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                    }
                    return
                }

                exitGraceRunnable?.let { mainHandler.removeCallbacks(it) }
                exitGraceRunnable = null
                pendingPipCloseCheck = false

                // Hide fullscreen overlay pill prior to PiP window snapshot
                cancelFullscreenOverlayAutoHide()
                binding.fullscreenControlsOverlay.visibility = View.GONE

                val w = MediaPlaybackBridge.lastVideoWidth.coerceAtLeast(1)
                val h = MediaPlaybackBridge.lastVideoHeight.coerceAtLeast(1)
                val rational = Rational(w, h).coerceIn(Rational(1, 2), Rational(2, 1))

                val paramsBuilder = PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                    .setActions(buildPipActions())

                computeVideoSourceRect()?.let { rect ->
                    paramsBuilder.setSourceRectHint(rect)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    paramsBuilder.setAutoEnterEnabled(false)
                    isAutoEnterArmed = false
                }

                isCurrentlyInPip = true
                val entered = activity.enterPictureInPictureMode(paramsBuilder.build())
                if (entered) {
                    applyPipUiState()
                } else {
                    isCurrentlyInPip = false
                    if (customVideoView != null) {
                        onFullscreenUserInteraction()
                    }
                }
            } catch (_: Exception) {
                isCurrentlyInPip = false
                if (customVideoView != null) {
                    onFullscreenUserInteraction()
                }
            }
        } else {
            Toast.makeText(activity, "Picture-in-Picture requires Android 8.0+", Toast.LENGTH_SHORT).show()
        }
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        val activeWv = getActiveWebView()
        isCurrentlyInPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            exitGraceRunnable?.let { mainHandler.removeCallbacks(it) }
            exitGraceRunnable = null
            justExitedPip = false
            pendingPipCloseCheck = false
            isAutoEnterArmed = false

            applyPipUiState()
            updatePipParams(isVideoPlaying = MediaPlaybackBridge.isMediaPlaying, shouldAutoEnter = false)
        } else {
            isCurrentlyInPip = false
            pendingPipCloseCheck = true
            if (MediaPlaybackBridge.isMediaPlaying) {
                MediaPlaybackBridge.isExplicitUserPause = false
            }

            justExitedPip = true
            exitGraceRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                justExitedPip = false
                if (!isInPip() && isActiveTabVideoPlaying()) {
                    updatePipParams(isVideoPlaying = true, shouldAutoEnter = true)
                }
            }
            exitGraceRunnable = runnable
            mainHandler.postDelayed(runnable, PIP_EXIT_DEBOUNCE_MS)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                safeSetPictureInPictureParams(
                    PictureInPictureParams.Builder()
                        .setAutoEnterEnabled(false)
                        .build()
                )
                isAutoEnterArmed = false
            }

            if (!MediaPlaybackBridge.isVideoPlaying && customVideoView == null) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            if (customVideoView != null) {
                binding.topBar.visibility = View.GONE
                binding.topBarDivider.visibility = View.GONE
                binding.fullscreenCustomViewContainer.visibility = View.VISIBLE
                binding.webViewContainer.visibility = View.GONE
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                onRestoreUiFromPip(true)
                onFullscreenUserInteraction()
            } else {
                binding.fullscreenCustomViewContainer.visibility = View.GONE
                binding.fullscreenControlsOverlay.visibility = View.GONE
                binding.topBar.visibility = View.VISIBLE
                binding.topBarDivider.visibility = View.VISIBLE
                binding.webViewContainer.setBackgroundColor(Color.TRANSPARENT)
                binding.webViewContainer.visibility = View.VISIBLE
                onRestoreUiFromPip(false)
                activeWv?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
                activeWv?.requestLayout()
            }
            binding.webViewContainer.requestLayout()
            binding.root.requestLayout()
            updatePipParams(shouldAutoEnter = false)
        }
    }
}
