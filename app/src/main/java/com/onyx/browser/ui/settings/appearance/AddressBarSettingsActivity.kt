package com.onyx.browser.ui.settings.appearance

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
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

    private fun applySelectedOptionBackground(optionView: View, accentColor: Int) {
        optionView.setBackgroundResource(R.drawable.bg_address_bar_option_selected)
        val drawable = optionView.background?.mutate() as? GradientDrawable
        drawable?.setStroke((1.5f * resources.displayMetrics.density).toInt(), accentColor)
    }

    private fun updateAddressBarPositionUI(isBottom: Boolean, animated: Boolean) {
        val topOption = binding.optTopAddressBar
        val bottomOption = binding.optBottomAddressBar
        val topPill = binding.pillTopPreview
        val bottomPill = binding.pillBottomPreview
        val badgeTop = binding.badgeTopSelected
        val radioTop = binding.radioTopUnselected
        val badgeBottom = binding.badgeBottomSelected
        val radioBottom = binding.radioBottomUnselected
        val labelTop = binding.tvLabelTop
        val labelBottom = binding.tvLabelBottom

        val accentColor = AccentColorManager.getAccentColor(preferences)
        badgeTop.backgroundTintList = ColorStateList.valueOf(accentColor)
        badgeBottom.backgroundTintList = ColorStateList.valueOf(accentColor)

        val titleColor = ContextCompat.getColor(this, R.color.settings_title_text)
        val subtitleColor = ContextCompat.getColor(this, R.color.settings_subtitle_text)

        val floatPx = -2f * resources.displayMetrics.density

        if (animated) {
            if (!isBottom) {
                // Top selected
                applySelectedOptionBackground(topOption, accentColor)
                bottomOption.setBackgroundResource(R.drawable.bg_address_bar_option_unselected)
                labelTop.setTextColor(titleColor)
                labelBottom.setTextColor(subtitleColor)

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

                badgeTop.visibility = View.VISIBLE
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
                radioTop.visibility = View.GONE

                badgeBottom.visibility = View.GONE
                radioBottom.visibility = View.VISIBLE
                radioBottom.alpha = 0f
                radioBottom.animate().alpha(1f).setDuration(200).start()
            } else {
                // Bottom selected
                topOption.setBackgroundResource(R.drawable.bg_address_bar_option_unselected)
                applySelectedOptionBackground(bottomOption, accentColor)
                labelTop.setTextColor(subtitleColor)
                labelBottom.setTextColor(titleColor)

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

                badgeBottom.visibility = View.VISIBLE
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
                radioBottom.visibility = View.GONE

                badgeTop.visibility = View.GONE
                radioTop.visibility = View.VISIBLE
                radioTop.alpha = 0f
                radioTop.animate().alpha(1f).setDuration(200).start()
            }
        } else {
            if (!isBottom) {
                applySelectedOptionBackground(topOption, accentColor)
                bottomOption.setBackgroundResource(R.drawable.bg_address_bar_option_unselected)
                labelTop.setTextColor(titleColor)
                labelBottom.setTextColor(subtitleColor)
                topPill.translationY = floatPx
                bottomPill.translationY = 0f
                badgeTop.visibility = View.VISIBLE
                badgeTop.alpha = 1f
                badgeTop.scaleX = 1f
                badgeTop.scaleY = 1f
                radioTop.visibility = View.GONE
                badgeBottom.visibility = View.GONE
                radioBottom.visibility = View.VISIBLE
            } else {
                topOption.setBackgroundResource(R.drawable.bg_address_bar_option_unselected)
                applySelectedOptionBackground(bottomOption, accentColor)
                labelTop.setTextColor(subtitleColor)
                labelBottom.setTextColor(titleColor)
                bottomPill.translationY = floatPx
                topPill.translationY = 0f
                badgeBottom.visibility = View.VISIBLE
                badgeBottom.alpha = 1f
                badgeBottom.scaleX = 1f
                badgeBottom.scaleY = 1f
                radioBottom.visibility = View.GONE
                badgeTop.visibility = View.GONE
                radioTop.visibility = View.VISIBLE
            }
        }
    }
}
