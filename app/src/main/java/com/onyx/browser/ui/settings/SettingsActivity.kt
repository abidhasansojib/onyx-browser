package com.onyx.browser.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivitySettingsBinding
import com.onyx.browser.ui.common.SearchEngineIconHelper
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.web.UserAgentManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferences: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)

        // Edge-to-edge system bars matching settings_canvas_bg
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val canvasBg = ContextCompat.getColor(this, R.color.settings_canvas_bg)
        window.statusBarColor = canvasBg
        window.navigationBarColor = canvasBg

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.getInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updatePadding(top = statusBarInsets.top)
            binding.scrollView.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        // Down arrow navigation finishes activity
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Pin top heading color statically to canvasBg (disable lift-on-scroll elevation tint)
        binding.appBarLayout.apply {
            isLiftOnScroll = false
            setLiftable(false)
            setLifted(false)
            elevation = 0f
            stateListAnimator = null
            outlineProvider = null
            statusBarForeground = null
            setBackgroundColor(canvasBg)
            backgroundTintList = ColorStateList.valueOf(canvasBg)
        }
        binding.toolbar.apply {
            elevation = 0f
            outlineProvider = null
            setBackgroundColor(canvasBg)
            backgroundTintList = ColorStateList.valueOf(canvasBg)
        }

        setupCategoryGeneral()
        setupCategoryPrivacyAndSecurity()
        setupCategoryAbout()
    }

    override fun onResume() {
        super.onResume()
        updateSearchEngineDisplay()
        updateThemeDisplay()
        updateUaSpooferText()
    }

    private fun setupCategoryGeneral() {
        // 1. Search engine
        updateSearchEngineDisplay()
        binding.settingSearchEngineRow.setOnClickListener {
            startActivity(Intent(this, SearchEngineSettingsActivity::class.java))
        }

        // 2. Appearance
        updateThemeDisplay()
        binding.settingAppearanceRow.setOnClickListener {
            val sheet = ThemePickerSheet {
                preferences.applyTheme()
                updateThemeDisplay()
                recreate()
            }
            sheet.show(supportFragmentManager, ThemePickerSheet.TAG)
        }

        // 3. Auto fill & passwords
        binding.settingAutofillRow.setOnClickListener {
            startActivity(Intent(this, AutofillSettingsActivity::class.java))
        }

        // 4. Video Options
        binding.settingVideoOptionsRow.setOnClickListener {
            startActivity(Intent(this, VideoSettingsActivity::class.java))
        }

        // 5. Download settings
        binding.settingDownloadSettingsRow.setOnClickListener {
            startActivity(Intent(this, DownloadSettingsActivity::class.java))
        }

        // 6. Accessibility
        binding.settingAccessibilityRow.setOnClickListener {
            startActivity(Intent(this, AccessibilitySettingsActivity::class.java))
        }
    }

    private fun setupCategoryPrivacyAndSecurity() {
        // 1. Privacy & shields
        binding.settingShieldsRow.setOnClickListener {
            startActivity(Intent(this, ShieldsActivity::class.java))
        }

        // 2. User agent spoofer
        updateUaSpooferText()
        binding.settingUaSpooferRow.setOnClickListener {
            showUaSpooferDialog()
        }

        // 3. Site permissions
        binding.settingSitePermissionsRow.setOnClickListener {
            startActivity(Intent(this, SiteSettingsActivity::class.java))
        }
    }

    private fun setupCategoryAbout() {
        // About Onyx
        binding.settingAboutRow.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun updateSearchEngineDisplay() {
        val current = preferences.searchEngine
        SearchEngineIconHelper.loadSearchEngineIcon(this, binding.ivCurrentSearchEngineIcon, current)
        binding.tvCurrentSearchEngineName.text = current.displayName
    }

    private fun updateThemeDisplay() {
        val themeText = when (preferences.themeMode) {
            BrowserPreferences.THEME_DARK -> getString(R.string.theme_dark)
            BrowserPreferences.THEME_LIGHT -> getString(R.string.theme_light)
            else -> getString(R.string.theme_system)
        }
        val accentName = AccentColorManager.getAccentName(preferences)
        binding.tvCurrentTheme.text = "$themeText • $accentName"
    }

    private fun updateUaSpooferText() {
        binding.tvUaSpooferStatus.text = UserAgentManager.getDisplayName(
            preferences.userAgentSpoofTemplate,
            preferences
        )
    }

    private fun showUaSpooferDialog() {
        UserAgentPickerSheet {
            updateUaSpooferText()
        }.show(supportFragmentManager, "UserAgentPickerSheet")
    }
}
