package com.onyx.browser.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.R
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivitySettingsBinding
import com.onyx.browser.nativebridge.AdBlockEngine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferences: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.getInstance(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupSearchEnginePreference()
        setupAppearancePreference()
        setupPrivacySettings()
        setupAutofillSettings()
        setupDownloadPreferences()
        setupMediaPreferences()
        setupAboutPreference()
    }

    override fun onResume() {
        super.onResume()
        updateSearchEngineDisplay()
    }

    private fun setupSearchEnginePreference() {
        updateSearchEngineDisplay()

        binding.settingSearchEngineRow.setOnClickListener {
            startActivity(android.content.Intent(this, SearchEngineSettingsActivity::class.java))
        }
    }

    private fun updateSearchEngineDisplay() {
        val current = preferences.searchEngine
        binding.ivCurrentSearchEngineIcon.setImageResource(current.iconResId)
        binding.tvCurrentSearchEngineName.text = current.displayName
    }

    private fun setupAutofillSettings() {
        binding.settingAutofillRow.setOnClickListener {
            startActivity(android.content.Intent(this, AutofillSettingsActivity::class.java))
        }
    }

    private fun setupAppearancePreference() {
        updateThemeDisplay()

        binding.settingThemeRow.setOnClickListener {
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
    }

    private fun updateThemeDisplay() {
        val themeText = when (preferences.themeMode) {
            BrowserPreferences.THEME_DARK -> getString(R.string.theme_dark)
            BrowserPreferences.THEME_LIGHT -> getString(R.string.theme_light)
            else -> getString(R.string.theme_system)
        }
        binding.tvCurrentTheme.text = themeText
    }

    private fun setupPrivacySettings() {
        binding.settingShieldsRow.setOnClickListener {
            startActivity(android.content.Intent(this, ShieldsActivity::class.java))
        }
    }

    private fun setupDownloadPreferences() {
        val options = arrayOf(
            "Ask before download",
            "Internal downloader",
            "External download manager"
        )
        
        fun updateLabel() {
            val idx = preferences.downloadManagerBehavior.coerceIn(0, 2)
            binding.tvCurrentDownloadManager.text = options[idx]
        }
        
        updateLabel()

        binding.settingDownloadManagerRow.setOnClickListener {
            val currentIndex = preferences.downloadManagerBehavior.coerceIn(0, 2)
            val sheet = DownloadManagerPickerSheet(currentIndex) { which ->
                preferences.downloadManagerBehavior = which
                updateLabel()
            }
            sheet.show(supportFragmentManager, DownloadManagerPickerSheet.TAG)
        }
    }

    private fun setupMediaPreferences() {
        binding.settingBackgroundPlaySwitch.isChecked = preferences.isBackgroundPlayEnabled
        binding.settingBackgroundPlayRow.setOnClickListener {
            binding.settingBackgroundPlaySwitch.toggle()
        }
        binding.settingBackgroundPlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.isBackgroundPlayEnabled = isChecked
        }

        binding.settingPipSwitch.isChecked = preferences.isPipEnabled
        binding.settingPipRow.setOnClickListener {
            binding.settingPipSwitch.toggle()
        }
        binding.settingPipSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.isPipEnabled = isChecked
        }

        binding.settingDisplayOverOtherAppsRow.setOnClickListener {
            openDisplayOverOtherAppsSettings()
        }
    }

    private fun openDisplayOverOtherAppsSettings() {
        val pm = packageManager
        var launched = false

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val pipIntent = android.content.Intent(
                    android.provider.Settings.ACTION_PICTURE_IN_PICTURE_SETTINGS,
                    android.net.Uri.parse("package:$packageName")
                )
                if (pipIntent.resolveActivity(pm) != null) {
                    startActivity(pipIntent)
                    launched = true
                }
            } catch (_: Exception) {}
        }

        if (!launched) {
            try {
                val appDetailsIntent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(appDetailsIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open system settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAboutPreference() {
        binding.settingAboutRow.setOnClickListener {
            startActivity(android.content.Intent(this, AboutActivity::class.java))
        }
    }
}

