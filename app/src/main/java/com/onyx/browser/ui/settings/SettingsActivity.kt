package com.onyx.browser.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
            val themes = arrayOf(
                getString(R.string.theme_system),
                getString(R.string.theme_dark),
                getString(R.string.theme_light)
            )

            val currentIndex = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> 1
                BrowserPreferences.THEME_LIGHT -> 2
                else -> 0
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.pref_category_appearance)
                .setSingleChoiceItems(themes, currentIndex) { dialog, which ->
                    val selectedMode = when (which) {
                        1 -> BrowserPreferences.THEME_DARK
                        2 -> BrowserPreferences.THEME_LIGHT
                        else -> BrowserPreferences.THEME_SYSTEM
                    }
                    preferences.themeMode = selectedMode
                    preferences.applyTheme()
                    updateThemeDisplay()
                    dialog.dismiss()
                    recreate()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
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
            AlertDialog.Builder(this)
                .setTitle("Download Manager")
                .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                    preferences.downloadManagerBehavior = which
                    updateLabel()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
