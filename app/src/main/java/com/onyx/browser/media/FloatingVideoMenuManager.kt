package com.onyx.browser.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ViewFloatingVideoMenuBinding
import kotlin.math.abs

/**
 * Manages the floating video action menu overlay that appears when an HTML5 video is actively playing.
 * Provides quick one-tap actions for:
 * 1. Download video (btnFloatingDownload)
 * 2. Toggle Background Playback with active status indicator (btnFloatingHeadphones)
 * 3. Enter Picture-in-Picture (btnFloatingPip)
 *
 * Supports drag-to-reposition anywhere along the viewport so it never obstructs web content.
 */
class FloatingVideoMenuManager(
    private val context: Context,
    private val parentContainer: ViewGroup
) {
    private val preferences = BrowserPreferences.getInstance(context)
    private var binding: ViewFloatingVideoMenuBinding? = null

    var onDownloadClickListener: (() -> Unit)? = null
    var onHeadphonesClickListener: (() -> Unit)? = null
    var onPipClickListener: (() -> Unit)? = null

    private var isDragging = false
    private var dX = 0f
    private var dY = 0f
    private var startX = 0f
    private var startY = 0f
    private val CLICK_DRAG_TOLERANCE = 10f

    init {
        inflateView()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun inflateView() {
        val inflater = LayoutInflater.from(context)
        val menuBinding = ViewFloatingVideoMenuBinding.inflate(inflater, parentContainer, false)
        binding = menuBinding

        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            bottomMargin = (80 * context.resources.displayMetrics.density).toInt()
            marginEnd = (16 * context.resources.displayMetrics.density).toInt()
        }
        menuBinding.root.layoutParams = layoutParams
        menuBinding.root.visibility = View.GONE
        menuBinding.root.alpha = 0f

        // Wire Action Buttons
        menuBinding.btnFloatingDownload.setOnClickListener {
            onDownloadClickListener?.invoke()
        }

        menuBinding.btnFloatingHeadphones.setOnClickListener {
            onHeadphonesClickListener?.invoke()
        }

        menuBinding.btnFloatingPip.setOnClickListener {
            onPipClickListener?.invoke()
        }

        updateHeadphonesState(preferences.isBackgroundPlayEnabled)

        // Draggable Floating Pill Touch Listener
        menuBinding.root.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    startX = event.rawX
                    startY = event.rawY
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = abs(event.rawX - startX)
                    val deltaY = abs(event.rawY - startY)
                    if (deltaX > CLICK_DRAG_TOLERANCE || deltaY > CLICK_DRAG_TOLERANCE) {
                        isDragging = true
                        val parentWidth = parentContainer.width
                        val parentHeight = parentContainer.height
                        val newX = (event.rawX + dX).coerceIn(0f, (parentWidth - view.width).toFloat())
                        val newY = (event.rawY + dY).coerceIn(0f, (parentHeight - view.height).toFloat())
                        view.x = newX
                        view.y = newY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        parentContainer.addView(menuBinding.root)
    }

    /**
     * Updates the headphone button icon tint and state according to whether
     * background playback is enabled or disabled.
     */
    fun updateHeadphonesState(enabled: Boolean) {
        val btn = binding?.btnFloatingHeadphones ?: return
        ImageViewCompat.setImageTintMode(btn, android.graphics.PorterDuff.Mode.SRC_IN)
        if (enabled) {
            val activeColor = ContextCompat.getColor(context, R.color.google_blue)
            ImageViewCompat.setImageTintList(btn, ColorStateList.valueOf(activeColor))
            btn.contentDescription = "Background Playback: On"
        } else {
            val typedValue = TypedValue()
            val defaultColor = if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)) {
                typedValue.data
            } else if (preferences.isDarkMode()) {
                ContextCompat.getColor(context, R.color.text_primary_dark)
            } else {
                ContextCompat.getColor(context, R.color.text_primary_light)
            }
            ImageViewCompat.setImageTintList(btn, ColorStateList.valueOf(defaultColor))
            btn.contentDescription = "Background Playback: Off"
        }
    }

    /**
     * Updates visibility of the floating menu based on video presence/playback and user preference.
     */
    fun onVideoStateChanged(hasVideo: Boolean, isWebViewVisible: Boolean) {
        val shouldShow = hasVideo && isWebViewVisible && preferences.isFloatingVideoMenuEnabled
        val view = binding?.root ?: return

        val isBgActive = MediaPlaybackBridge.isBackgroundPlayActive(preferences)
        updateHeadphonesState(isBgActive)

        if (shouldShow) {
            view.bringToFront()
            parentContainer.bringChildToFront(view)
            view.translationZ = 100f
            if (!view.isVisible || view.alpha < 1f) {
                view.visibility = View.VISIBLE
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(250)
                    .setListener(null)
                    .start()
            }
        } else {
            if (view.isVisible) {
                view.animate()
                    .alpha(0f)
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .setDuration(200)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            view.visibility = View.GONE
                        }
                    })
                    .start()
            }
        }
    }

    /**
     * Compatibility overload for legacy playback-only updates.
     */
    fun onVideoPlaybackStateChanged(isVideoPlaying: Boolean, isWebViewVisible: Boolean) {
        onVideoStateChanged(isVideoPlaying, isWebViewVisible)
    }

    fun hideImmediately() {
        binding?.root?.visibility = View.GONE
        binding?.root?.alpha = 0f
    }

    /**
     * Resets all floating menu options, position, and visibility to default.
     * Called when switching tabs or closing a tab so temporary actions do not persist.
     */
    fun resetToDefault(isBackgroundPlayEnabled: Boolean = preferences.isBackgroundPlayEnabled) {
        hideImmediately()
        val view = binding?.root ?: return
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        isDragging = false
        updateHeadphonesState(isBackgroundPlayEnabled)
    }
}
