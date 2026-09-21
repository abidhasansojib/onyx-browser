package com.onyx.browser.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivitySettingsPrivacyBinding
import com.onyx.browser.nativebridge.AdBlockEngine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsPrivacyActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsPrivacyBinding
    private lateinit var preferences: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsPrivacyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.getInstance(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupPrivacyPreferences()
    }

    private fun setupPrivacyPreferences() {
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

        // Block Third-Party Cookies Toggle
        binding.switchBlockCookies.isChecked = preferences.isBlockThirdPartyCookiesEnabled
        binding.settingBlockCookiesRow.setOnClickListener {
            val newState = !binding.switchBlockCookies.isChecked
            binding.switchBlockCookies.isChecked = newState
            preferences.isBlockThirdPartyCookiesEnabled = newState
        }

        // Do Not Track Toggle
        binding.switchDoNotTrack.isChecked = preferences.isDoNotTrackEnabled
        binding.settingDoNotTrackRow.setOnClickListener {
            val newState = !binding.switchDoNotTrack.isChecked
            binding.switchDoNotTrack.isChecked = newState
            preferences.isDoNotTrackEnabled = newState
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
                    Toast.makeText(this@SettingsPrivacyActivity, R.string.filters_updated_success, Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvFilterUpdateStatus.text = getString(R.string.filters_update_failed)
                    Toast.makeText(this@SettingsPrivacyActivity, R.string.filters_update_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
