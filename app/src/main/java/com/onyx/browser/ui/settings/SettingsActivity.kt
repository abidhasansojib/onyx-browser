package com.onyx.browser.ui.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivitySettingsBinding
import com.onyx.browser.web.UserAgentManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferences: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge system bars matching #0B0305 canvas
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.parseColor("#0B0305")
        window.navigationBarColor = Color.parseColor("#0B0305")

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
            val sheet = ThemePickerSheet(preferences.themeMode) { selectedMode ->
                if (preferences.themeMode != selectedMode) {
                    preferences.themeMode = selectedMode
                    preferences.applyTheme()
                    updateThemeDisplay()
                    recreate()
                }
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

        // 3. Manage personal data
        binding.settingManageDataRow.setOnClickListener {
            startActivity(Intent(this, ManagePersonalDataActivity::class.java))
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
        binding.ivCurrentSearchEngineIcon.setImageResource(current.iconResId)
        binding.tvCurrentSearchEngineName.text = current.displayName
    }

    private fun updateThemeDisplay() {
        val themeText = when (preferences.themeMode) {
            BrowserPreferences.THEME_DARK -> getString(R.string.theme_dark)
            BrowserPreferences.THEME_LIGHT -> getString(R.string.theme_light)
            else -> getString(R.string.theme_system)
        }
        binding.tvCurrentTheme.text = themeText
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
