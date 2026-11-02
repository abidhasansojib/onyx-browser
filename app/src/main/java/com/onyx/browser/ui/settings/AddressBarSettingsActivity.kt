package com.onyx.browser.ui.settings

import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityAddressBarSettingsBinding
import com.onyx.browser.ui.theme.AccentColorManager

class AddressBarSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddressBarSettingsBinding
    private lateinit var preferences: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)

        // Edge-to-edge system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val canvasBg = ContextCompat.getColor(this, R.color.settings_canvas_bg)
        @Suppress("DEPRECATION")
        window.statusBarColor = canvasBg
        @Suppress("DEPRECATION")
        window.navigationBarColor = canvasBg

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode

        binding = ActivityAddressBarSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.getInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.addressBarSettingsRoot) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updatePadding(top = statusBarInsets.top)
            binding.scrollView.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.appBarLayout.apply {
            isLiftOnScroll = false
            setLiftable(false)
            setLifted(false)
        }

        binding.switchReplaceHomeWithRefresh.isChecked = preferences.isHomeReplacedWithRefresh
        binding.cardReplaceHomeWithRefresh.setOnClickListener {
            val newState = !binding.switchReplaceHomeWithRefresh.isChecked
            binding.switchReplaceHomeWithRefresh.isChecked = newState
            preferences.isHomeReplacedWithRefresh = newState
        }

        // Dual-Choice Grid: Edit Address Bar Position (Top vs Bottom)
        updateAddressBarPositionUI(preferences.isAddressBarAtBottom, animated = false)

        binding.optTopAddressBar.setOnClickListener {
            if (preferences.isAddressBarAtBottom) {
                preferences.isAddressBarAtBottom = false
                updateAddressBarPositionUI(isBottom = false, animated = true)
            }
        }

        binding.optBottomAddressBar.setOnClickListener {
            if (!preferences.isAddressBarAtBottom) {
                preferences.isAddressBarAtBottom = true
                updateAddressBarPositionUI(isBottom = true, animated = true)
            }
        }

        binding.switchAutoHideToolbar.isChecked = preferences.isAutoHideToolbarEnabled
        binding.cardAutoHideToolbar.setOnClickListener {
            val newState = !binding.switchAutoHideToolbar.isChecked
            binding.switchAutoHideToolbar.isChecked = newState
            preferences.isAutoHideToolbarEnabled = newState
        }
    }

    private val springInterpolator = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)

    private fun updateAddressBarPositionUI(isBottom: Boolean, animated: Boolean) {
        val topOption = binding.optTopAddressBar
        val bottomOption = binding.optBottomAddressBar
        val topPill = binding.pillTopPreview
        val bottomPill = binding.pillBottomPreview
        val badgeTop = binding.badgeTopSelected
        val radioTop = binding.radioTopUnselected
        val badgeBottom = binding.badgeBottomSelected
        val radioBottom = binding.radioBottomUnselected

        val floatPx = -2f * resources.displayMetrics.density

        if (animated) {
            if (!isBottom) {
                // Top selected
                topOption.setBackgroundResource(R.drawable.bg_address_bar_option_selected)
                bottomOption.setBackgroundResource(R.drawable.bg_address_bar_option_unselected)

                topPill.animate()
                    .translationY(floatPx)
                    .setInterpolator(springInterpolator)
                    .setDuration(300)
                    .start()
                bottomPill.animate()
                    .translationY(0f)
                    .setInterpolator(springInterpolator)
                    .setDuration(300)
                    .start()

                badgeTop.visibility = android.view.View.VISIBLE
                badgeTop.alpha = 0f
                badgeTop.scaleX = 0.6f
                badgeTop.scaleY = 0.6f
                badgeTop.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(springInterpolator)
                    .setDuration(250)
                    .start()
                radioTop.visibility = android.view.View.GONE

                badgeBottom.visibility = android.view.View.GONE
                radioBottom.visibility = android.view.View.VISIBLE
                radioBottom.alpha = 0f
                radioBottom.animate().alpha(1f).setDuration(200).start()
            } else {
                // Bottom selected
                topOption.setBackgroundResource(R.drawable.bg_address_bar_option_unselected)
                bottomOption.setBackgroundResource(R.drawable.bg_address_bar_option_selected)

                bottomPill.animate()
                    .translationY(floatPx)
                    .setInterpolator(springInterpolator)
                    .setDuration(300)
                    .start()
                topPill.animate()
                    .translationY(0f)
                    .setInterpolator(springInterpolator)
                    .setDuration(300)
                    .start()

                badgeBottom.visibility = android.view.View.VISIBLE
                badgeBottom.alpha = 0f
                badgeBottom.scaleX = 0.6f
                badgeBottom.scaleY = 0.6f
                badgeBottom.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(springInterpolator)
                    .setDuration(250)
                    .start()
                radioBottom.visibility = android.view.View.GONE

                badgeTop.visibility = android.view.View.GONE
                radioTop.visibility = android.view.View.VISIBLE
                radioTop.alpha = 0f
                radioTop.animate().alpha(1f).setDuration(200).start()
            }
        } else {
            if (!isBottom) {
                topOption.setBackgroundResource(R.drawable.bg_address_bar_option_selected)
                bottomOption.setBackgroundResource(R.drawable.bg_address_bar_option_unselected)
                topPill.translationY = floatPx
                bottomPill.translationY = 0f
                badgeTop.visibility = android.view.View.VISIBLE
                badgeTop.alpha = 1f
                badgeTop.scaleX = 1f
                badgeTop.scaleY = 1f
                radioTop.visibility = android.view.View.GONE
                badgeBottom.visibility = android.view.View.GONE
                radioBottom.visibility = android.view.View.VISIBLE
            } else {
                topOption.setBackgroundResource(R.drawable.bg_address_bar_option_unselected)
                bottomOption.setBackgroundResource(R.drawable.bg_address_bar_option_selected)
                bottomPill.translationY = floatPx
                topPill.translationY = 0f
                badgeBottom.visibility = android.view.View.VISIBLE
                badgeBottom.alpha = 1f
                badgeBottom.scaleX = 1f
                badgeBottom.scaleY = 1f
                radioBottom.visibility = android.view.View.GONE
                badgeTop.visibility = android.view.View.GONE
                radioTop.visibility = android.view.View.VISIBLE
            }
        }
    }
}
