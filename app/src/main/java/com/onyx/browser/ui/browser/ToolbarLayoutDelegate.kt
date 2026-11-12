package com.onyx.browser.ui.browser

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.constraintlayout.widget.ConstraintSet
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityMainBinding
import com.onyx.browser.web.OnyxWebView

/**
 * Delegate responsible for address bar ConstraintSet layout transitions (Top vs. Bottom),
 * scroll-based auto-hide/show toolbar animations, and scroll-to-top FAB visibility.
 */
class ToolbarLayoutDelegate(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val preferences: BrowserPreferences
) {
    var isToolbarHidden: Boolean = false
    var lastKnownTopBarHeight: Int = 0
    var lastAddressBarAtBottom: Boolean? = null
    private var toolbarAnimator: ValueAnimator? = null

    fun applyAddressBarPositionLayout(
        isBottom: Boolean = preferences.isAddressBarAtBottom,
        lastNavBarBottomInset: Int = 0,
        initial: Boolean = false
    ) {
        applyToolbarPosition(isBottom, lastNavBarBottomInset, initial)
    }

    fun applyToolbarPosition(
        isBottom: Boolean,
        lastNavBarBottomInset: Int,
        @Suppress("UNUSED_PARAMETER") initial: Boolean = false
    ) {
        lastAddressBarAtBottom = isBottom

        val constraintSet = ConstraintSet()
        constraintSet.clone(binding.mainRoot)

        if (isBottom) {
            // Anchor topBar to bottom of parent
            constraintSet.clear(R.id.topBar, ConstraintSet.TOP)
            constraintSet.connect(R.id.topBar, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)
            constraintSet.connect(R.id.topBar, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            constraintSet.connect(R.id.topBar, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)

            // Anchor topBarDivider above topBar
            constraintSet.clear(R.id.topBarDivider, ConstraintSet.TOP)
            constraintSet.connect(R.id.topBarDivider, ConstraintSet.BOTTOM, R.id.topBar, ConstraintSet.TOP, 0)
            constraintSet.connect(R.id.topBarDivider, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            constraintSet.connect(R.id.topBarDivider, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)

            // Anchor progressBar above topBar
            constraintSet.clear(R.id.progressBar, ConstraintSet.TOP)
            constraintSet.connect(R.id.progressBar, ConstraintSet.BOTTOM, R.id.topBar, ConstraintSet.TOP, 0)
            constraintSet.connect(R.id.progressBar, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            constraintSet.connect(R.id.progressBar, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)

            // Anchor contentContainer between tvNetworkStatus.bottom and topBar.top
            constraintSet.connect(R.id.contentContainer, ConstraintSet.TOP, R.id.tvNetworkStatus, ConstraintSet.BOTTOM, 0)
            constraintSet.connect(R.id.contentContainer, ConstraintSet.BOTTOM, R.id.topBar, ConstraintSet.TOP, 0)

            // Anchor searchOverlay between tvNetworkStatus.bottom and topBar.top
            constraintSet.connect(R.id.searchOverlay, ConstraintSet.TOP, R.id.tvNetworkStatus, ConstraintSet.BOTTOM, 0)
            constraintSet.connect(R.id.searchOverlay, ConstraintSet.BOTTOM, R.id.topBar, ConstraintSet.TOP, 0)

            // Anchor findInPageBar to tvNetworkStatus.bottom
            constraintSet.clear(R.id.findInPageBar, ConstraintSet.BOTTOM)
            constraintSet.connect(R.id.findInPageBar, ConstraintSet.TOP, R.id.tvNetworkStatus, ConstraintSet.BOTTOM, 0)
        } else {
            // Anchor topBar to bottom of tvNetworkStatus
            constraintSet.clear(R.id.topBar, ConstraintSet.BOTTOM)
            constraintSet.connect(R.id.topBar, ConstraintSet.TOP, R.id.tvNetworkStatus, ConstraintSet.BOTTOM, 0)
            constraintSet.connect(R.id.topBar, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            constraintSet.connect(R.id.topBar, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)

            // Anchor topBarDivider below topBar
            constraintSet.clear(R.id.topBarDivider, ConstraintSet.BOTTOM)
            constraintSet.connect(R.id.topBarDivider, ConstraintSet.TOP, R.id.topBar, ConstraintSet.BOTTOM, 0)
            constraintSet.connect(R.id.topBarDivider, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            constraintSet.connect(R.id.topBarDivider, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)

            // Anchor progressBar below topBarDivider
            constraintSet.clear(R.id.progressBar, ConstraintSet.BOTTOM)
            constraintSet.connect(R.id.progressBar, ConstraintSet.TOP, R.id.topBarDivider, ConstraintSet.BOTTOM, 0)
            constraintSet.connect(R.id.progressBar, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            constraintSet.connect(R.id.progressBar, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)

            // Anchor contentContainer between topBarDivider.bottom and parent.bottom
            constraintSet.connect(R.id.contentContainer, ConstraintSet.TOP, R.id.topBarDivider, ConstraintSet.BOTTOM, 0)
            constraintSet.connect(R.id.contentContainer, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)

            // Anchor searchOverlay between topBarDivider.bottom and parent.bottom
            constraintSet.connect(R.id.searchOverlay, ConstraintSet.TOP, R.id.topBarDivider, ConstraintSet.BOTTOM, 0)
            constraintSet.connect(R.id.searchOverlay, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)

            // Anchor findInPageBar to topBarDivider.bottom
            constraintSet.clear(R.id.findInPageBar, ConstraintSet.BOTTOM)
            constraintSet.connect(R.id.findInPageBar, ConstraintSet.TOP, R.id.topBarDivider, ConstraintSet.BOTTOM, 0)
        }

        constraintSet.applyTo(binding.mainRoot)

        // Reset any margin translation on toolbar
        val lp = binding.topBar.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            lp.topMargin = 0
            lp.bottomMargin = 0
            binding.topBar.layoutParams = lp
        }

        val density = activity.resources.displayMetrics.density
        val baseBottomPaddingPx = (8 * density).toInt()
        val topPaddingPx = (6 * density).toInt()

        if (isBottom) {
            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                topPaddingPx,
                binding.topBar.paddingRight,
                baseBottomPaddingPx + lastNavBarBottomInset
            )
            binding.contentContainer.setPadding(
                binding.contentContainer.paddingLeft,
                binding.contentContainer.paddingTop,
                binding.contentContainer.paddingRight,
                0
            )
            binding.searchOverlay.setPadding(0, 0, 0, 0)
            val fabLp = binding.fabScrollToTop.layoutParams as? ViewGroup.MarginLayoutParams
            if (fabLp != null) {
                val barH = if (lastKnownTopBarHeight > 0) lastKnownTopBarHeight else (56 * density).toInt()
                fabLp.bottomMargin = barH + (16 * density).toInt()
                binding.fabScrollToTop.layoutParams = fabLp
            }
        } else {
            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                topPaddingPx,
                binding.topBar.paddingRight,
                baseBottomPaddingPx
            )
            binding.contentContainer.setPadding(
                binding.contentContainer.paddingLeft,
                binding.contentContainer.paddingTop,
                binding.contentContainer.paddingRight,
                lastNavBarBottomInset
            )
            binding.searchOverlay.setPadding(0, 0, 0, lastNavBarBottomInset)
            val fabLp = binding.fabScrollToTop.layoutParams as? ViewGroup.MarginLayoutParams
            if (fabLp != null) {
                fabLp.bottomMargin = (72 * density).toInt()
                binding.fabScrollToTop.layoutParams = fabLp
            }
        }

        binding.topBar.post {
            if (binding.topBar.height > 0) {
                lastKnownTopBarHeight = binding.topBar.height
            }
            if (isBottom) {
                val fabLp = binding.fabScrollToTop.layoutParams as? ViewGroup.MarginLayoutParams
                if (fabLp != null) {
                    val barH = if (lastKnownTopBarHeight > 0) lastKnownTopBarHeight else (56 * density).toInt()
                    val targetFabMargin = barH + (16 * density).toInt()
                    if (fabLp.bottomMargin != targetFabMargin) {
                        fabLp.bottomMargin = targetFabMargin
                        binding.fabScrollToTop.layoutParams = fabLp
                    }
                }
            }
        }
    }

    fun hideToolbar(
        animated: Boolean = true,
        hasCustomVideoView: Boolean = false
    ) {
        if (isToolbarHidden) return
        if (!preferences.isAutoHideToolbarEnabled) return
        if (binding.homeLayout.root.visibility == View.VISIBLE) return
        if (binding.searchOverlay.visibility == View.VISIBLE || binding.etUrl.hasFocus()) return
        if (binding.findInPageBar.visibility == View.VISIBLE) return
        if (binding.translateBar.visibility == View.VISIBLE) return
        if (hasCustomVideoView) return

        isToolbarHidden = true
        if (binding.topBar.height > 0) {
            lastKnownTopBarHeight = binding.topBar.height
        }
        val barHeight = if (lastKnownTopBarHeight > 0) {
            lastKnownTopBarHeight
        } else {
            (56 * activity.resources.displayMetrics.density).toInt()
        }
        val targetMargin = -barHeight

        val isBottom = preferences.isAddressBarAtBottom
        toolbarAnimator?.cancel()
        if (animated) {
            val lp = binding.topBar.layoutParams as? ViewGroup.MarginLayoutParams
            val currentMargin = if (isBottom) (lp?.bottomMargin ?: 0) else (lp?.topMargin ?: 0)
            toolbarAnimator = ValueAnimator.ofInt(currentMargin, targetMargin).apply {
                duration = 180L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val margin = animator.animatedValue as Int
                    val currentLp = binding.topBar.layoutParams as? ViewGroup.MarginLayoutParams ?: return@addUpdateListener
                    if (isBottom) {
                        if (currentLp.bottomMargin != margin) {
                            currentLp.bottomMargin = margin
                            binding.topBar.layoutParams = currentLp
                        }
                    } else {
                        if (currentLp.topMargin != margin) {
                            currentLp.topMargin = margin
                            binding.topBar.layoutParams = currentLp
                        }
                    }
                }
                start()
            }
        } else {
            val lp = binding.topBar.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                if (isBottom) {
                    if (lp.bottomMargin != targetMargin) {
                        lp.bottomMargin = targetMargin
                        binding.topBar.layoutParams = lp
                    }
                } else {
                    if (lp.topMargin != targetMargin) {
                        lp.topMargin = targetMargin
                        binding.topBar.layoutParams = lp
                    }
                }
            }
        }
    }

    fun showToolbar(
        animated: Boolean = true,
        isInPipMode: Boolean = false,
        hasCustomVideoView: Boolean = false
    ) {
        if (isInPipMode || hasCustomVideoView) return
        val isBottom = preferences.isAddressBarAtBottom
        val lp = binding.topBar.layoutParams as? ViewGroup.MarginLayoutParams
        val currentMargin = if (isBottom) (lp?.bottomMargin ?: 0) else (lp?.topMargin ?: 0)
        if (!isToolbarHidden && currentMargin == 0) return

        isToolbarHidden = false
        toolbarAnimator?.cancel()
        if (animated) {
            toolbarAnimator = ValueAnimator.ofInt(currentMargin, 0).apply {
                duration = 180L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val margin = animator.animatedValue as Int
                    val currentLp = binding.topBar.layoutParams as? ViewGroup.MarginLayoutParams ?: return@addUpdateListener
                    if (isBottom) {
                        if (currentLp.bottomMargin != margin) {
                            currentLp.bottomMargin = margin
                            binding.topBar.layoutParams = currentLp
                        }
                    } else {
                        if (currentLp.topMargin != margin) {
                            currentLp.topMargin = margin
                            binding.topBar.layoutParams = currentLp
                        }
                    }
                }
                start()
            }
        } else {
            if (lp != null) {
                if (isBottom) {
                    if (lp.bottomMargin != 0) {
                        lp.bottomMargin = 0
                        binding.topBar.layoutParams = lp
                    }
                } else {
                    if (lp.topMargin != 0) {
                        lp.topMargin = 0
                        binding.topBar.layoutParams = lp
                    }
                }
            }
        }
    }

    fun attachWebViewScrollAutoHide(
        webView: OnyxWebView,
        isTargetActiveTab: () -> Boolean,
        isInPipMode: () -> Boolean,
        hasCustomVideoView: () -> Boolean
    ) {
        webView.onScrollChangedCallback = { _, t, _, oldt ->
            if (isTargetActiveTab()) {
                if (preferences.isScrollToTopEnabled) {
                    if (t > 500 && t > oldt) {
                        binding.fabScrollToTop.visibility = View.VISIBLE
                    } else if (t < 10) {
                        binding.fabScrollToTop.visibility = View.GONE
                    }
                }

                if (preferences.isAutoHideToolbarEnabled && binding.homeLayout.root.visibility != View.VISIBLE) {
                    val deltaY = t - oldt
                    val threshold = (12 * activity.resources.displayMetrics.density).toInt()
                    if (t <= 5) {
                        showToolbar(animated = true, isInPipMode = isInPipMode(), hasCustomVideoView = hasCustomVideoView())
                    } else if (deltaY > threshold && t > (lastKnownTopBarHeight.takeIf { it > 0 } ?: binding.topBar.height)) {
                        hideToolbar(animated = true, hasCustomVideoView = hasCustomVideoView())
                    } else if (deltaY < -threshold) {
                        showToolbar(animated = true, isInPipMode = isInPipMode(), hasCustomVideoView = hasCustomVideoView())
                    }
                }
            }
        }
    }
}
