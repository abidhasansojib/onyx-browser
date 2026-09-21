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
        setupAdBlockPreferences()
        setupDownloadPreferences()
    }

    private fun setupSearchEnginePreference() {
        updateSearchEngineDisplay()

        binding.settingSearchEngineRow.setOnClickListener {
            val engines = SearchEngine.entries.toTypedArray()
            val engineNames = engines.map { it.displayName }.toTypedArray()
            val currentIndex = engines.indexOf(preferences.searchEngine).coerceAtLeast(0)

            AlertDialog.Builder(this)
                .setTitle(R.string.pref_category_search)
                .setSingleChoiceItems(engineNames, currentIndex) { dialog, which ->
                    preferences.searchEngine = engines[which]
                    updateSearchEngineDisplay()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun updateSearchEngineDisplay() {
        val current = preferences.searchEngine
        binding.ivCurrentSearchEngineIcon.setImageResource(current.iconResId)
        binding.tvCurrentSearchEngineName.text = current.displayName
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

    private fun setupAdBlockPreferences() {
        // Master Toggle
        binding.switchAdblockMaster.isChecked = preferences.isAdBlockEnabled
        binding.settingAdblockMasterRow.setOnClickListener {
            val newState = !binding.switchAdblockMaster.isChecked
            binding.switchAdblockMaster.isChecked = newState
            preferences.isAdBlockEnabled = newState
        }

        // Cosmetic Filtering Toggle
        binding.switchCosmeticFiltering.isChecked = preferences.isCosmeticFilteringEnabled
        binding.settingCosmeticFilteringRow.setOnClickListener {
            val newState = !binding.switchCosmeticFiltering.isChecked
            binding.switchCosmeticFiltering.isChecked = newState
            preferences.isCosmeticFilteringEnabled = newState
        }

        // Blocking Level (Standard / Aggressive) — alert dialog picker
        fun updateBlockingLevelDisplay() {
            binding.tvBlockingLevelValue.text = if (preferences.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE)
                getString(R.string.blocking_aggressive) else getString(R.string.blocking_standard)
        }
        updateBlockingLevelDisplay()
        binding.settingBlockingLevelRow.setOnClickListener {
            val levels = arrayOf(
                getString(R.string.blocking_standard),
                getString(R.string.blocking_aggressive)
            )
            val current = preferences.blockingLevel
            AlertDialog.Builder(this)
                .setTitle(R.string.pref_blocking_level_title)
                .setSingleChoiceItems(levels, current) { dialog, which ->
                    preferences.blockingLevel = which
                    updateBlockingLevelDisplay()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // Fingerprint Protection Toggle
        binding.switchFingerprintProtection.isChecked = preferences.isFingerprintProtectionEnabled
        binding.settingFingerprintRow.setOnClickListener {
            val newState = !binding.switchFingerprintProtection.isChecked
            binding.switchFingerprintProtection.isChecked = newState
            preferences.isFingerprintProtectionEnabled = newState
        }

        // HTTPS Upgrade Toggle
        binding.switchHttpsUpgrade.isChecked = preferences.isHttpsUpgradeEnabled
        binding.settingHttpsUpgradeRow.setOnClickListener {
            val newState = !binding.switchHttpsUpgrade.isChecked
            binding.switchHttpsUpgrade.isChecked = newState
            preferences.isHttpsUpgradeEnabled = newState
        }

        // Live Blocked Counter
        lifecycleScope.launch {
            preferences.blockedRequestsFlow.collectLatest { count ->
                binding.tvBlockedCounter.text = getString(R.string.blocked_requests_counter, count)
            }
        }

        // Reset Counter
        binding.settingBlockedCounterRow.setOnClickListener {
            preferences.resetBlockedRequests()
            Toast.makeText(this, "Counter reset", Toast.LENGTH_SHORT).show()
        }

        // Update Filters Button
        binding.settingUpdateFiltersRow.setOnClickListener {
            binding.tvFilterUpdateStatus.text = "Downloading latest filters…"
            lifecycleScope.launch {
                val success = AdBlockEngine.updateRulesFromNetwork(
                    context = applicationContext,
                    rulesUrl = "https://easylist.to/easylist/easylist.txt"
                )
                if (success) {
                    binding.tvFilterUpdateStatus.text = getString(R.string.filters_updated_success)
                    Toast.makeText(this@SettingsActivity, R.string.filters_updated_success, Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvFilterUpdateStatus.text = getString(R.string.filters_update_failed)
                    Toast.makeText(this@SettingsActivity, R.string.filters_update_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupDownloadPreferences() {
        binding.switchAskBeforeDownload.isChecked = preferences.askBeforeDownload
        binding.settingAskBeforeDownloadRow.setOnClickListener {
            val newState = !binding.switchAskBeforeDownload.isChecked
            binding.switchAskBeforeDownload.isChecked = newState
            preferences.askBeforeDownload = newState
        }
    }
}
