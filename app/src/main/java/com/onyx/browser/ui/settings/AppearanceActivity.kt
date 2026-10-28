package com.onyx.browser.ui.settings

import android.content.Intent
import android.content.res.Configuration
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
import com.onyx.browser.databinding.ActivityAppearanceBinding
import com.onyx.browser.ui.theme.AccentColorManager

class AppearanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppearanceBinding
    private lateinit var preferences: BrowserPreferences
    private var selectedThemeMode: Int = BrowserPreferences.THEME_SYSTEM

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

        binding = ActivityAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.getInstance(this)
        selectedThemeMode = preferences.themeMode

        ViewCompat.setOnApplyWindowInsetsListener(binding.appearanceRoot) { _, insets ->
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

        updateThemeSelection(selectedThemeMode)

        binding.optionSystem.setOnClickListener {
            selectThemeMode(BrowserPreferences.THEME_SYSTEM)
        }

        binding.optionDark.setOnClickListener {
            selectThemeMode(BrowserPreferences.THEME_DARK)
        }

        binding.optionLight.setOnClickListener {
            selectThemeMode(BrowserPreferences.THEME_LIGHT)
        }

        binding.cardAddressBarCustomization.setOnClickListener {
            startActivity(Intent(this, AddressBarSettingsActivity::class.java))
        }

        binding.switchForceDarkMode.isChecked = preferences.isForceDarkModeEnabled
        binding.cardForceDarkMode.setOnClickListener {
            val newState = !binding.switchForceDarkMode.isChecked
            binding.switchForceDarkMode.isChecked = newState
            preferences.isForceDarkModeEnabled = newState
        }
    }

    private fun selectThemeMode(mode: Int) {
        if (preferences.themeMode != mode) {
            preferences.themeMode = mode
            preferences.applyTheme()
            selectedThemeMode = mode
            updateThemeSelection(mode)
            recreate()
        }
    }

    private fun updateThemeSelection(selectedTheme: Int) {
        val defaultStroke = ContextCompat.getColor(this, R.color.settings_card_stroke)
        val defaultIcon = ContextCompat.getColor(this, R.color.settings_title_text)
        val activeColor = ContextCompat.getColor(this, R.color.primary)

        val activeStrokeWidth = dpToPx(2f)
        val defaultStrokeWidth = dpToPx(1f)

        // System
        val isSystem = selectedTheme == BrowserPreferences.THEME_SYSTEM
        binding.ivCheckSystem.visibility = if (isSystem) View.VISIBLE else View.INVISIBLE
        binding.ivCheckSystem.setColorFilter(activeColor)
        binding.optionSystem.strokeColor = if (isSystem) activeColor else defaultStroke
        binding.optionSystem.strokeWidth = if (isSystem) activeStrokeWidth else defaultStrokeWidth
        binding.ivIconSystem.setColorFilter(if (isSystem) activeColor else defaultIcon)

        // Dark
        val isDark = selectedTheme == BrowserPreferences.THEME_DARK
        binding.ivCheckDark.visibility = if (isDark) View.VISIBLE else View.INVISIBLE
        binding.ivCheckDark.setColorFilter(activeColor)
        binding.optionDark.strokeColor = if (isDark) activeColor else defaultStroke
        binding.optionDark.strokeWidth = if (isDark) activeStrokeWidth else defaultStrokeWidth
        binding.ivIconDark.setColorFilter(if (isDark) activeColor else defaultIcon)

        // Light
        val isLight = selectedTheme == BrowserPreferences.THEME_LIGHT
        binding.ivCheckLight.visibility = if (isLight) View.VISIBLE else View.INVISIBLE
        binding.ivCheckLight.setColorFilter(activeColor)
        binding.optionLight.strokeColor = if (isLight) activeColor else defaultStroke
        binding.optionLight.strokeWidth = if (isLight) activeStrokeWidth else defaultStrokeWidth
        binding.ivIconLight.setColorFilter(if (isLight) activeColor else defaultIcon)
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
