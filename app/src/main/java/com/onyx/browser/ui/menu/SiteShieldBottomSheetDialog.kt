package com.onyx.browser.ui.menu

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.BottomSheetSiteShieldBinding
import com.onyx.browser.ui.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Brave-style Shields panel — a BottomSheet (matching the rest of the UI)
 * with per-site controls for:
 *  - Shields ON/OFF (master whitelist toggle)
 *  - Ad & Tracker Blocking level (Standard / Aggressive)
 *  - Fingerprint Protection
 *  - HTTPS Upgrade
 *  - Script Blocking per domain
 *  - Cosmetic Element Hiding
 *  - Live blocked requests counter (all-time)
 */
class SiteShieldBottomSheetDialog(
    private val currentUrl: String,
    private val onShieldSettingsChanged: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSiteShieldBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: BrowserPreferences
    private lateinit var domain: String
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun getTheme(): Int = R.style.Theme_OnyxBrowser_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSiteShieldBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = BrowserPreferences.getInstance(requireContext())
        domain = prefs.cleanDomain(currentUrl)

        binding.tvShieldDomain.text = if (domain.isNotBlank()) domain else "This page"

        setupShieldsMasterToggle()
        setupBlockingLevelSelector()
        setupFingerprintToggle()
        setupHttpsUpgradeToggle()
        setupScriptBlockingToggle()
        setupCosmeticFilteringToggle()
        setupBlockedCounter()

        binding.btnShieldSettings.setOnClickListener {
            startActivity(Intent(requireContext(), com.onyx.browser.ui.settings.SettingsPrivacyActivity::class.java))
            dismiss()
        }

        binding.btnShieldDone.setOnClickListener { dismiss() }
    }

    // ── Shields Master Toggle ──────────────────────────────────────────────────

    private fun setupShieldsMasterToggle() {
        val isWhitelisted = prefs.isDomainWhitelisted(domain)
        updateMasterShieldUi(!isWhitelisted)

        binding.layoutShieldsMaster.setOnClickListener {
            val currentlyWhitelisted = prefs.isDomainWhitelisted(domain)
            val newShieldsUp = currentlyWhitelisted // toggling: if was OFF→turn ON
            prefs.setDomainWhitelisted(domain, !newShieldsUp)
            updateMasterShieldUi(newShieldsUp)
            // Enable/disable sub-controls based on shields state
            updateSubControlsEnabled(newShieldsUp)
            onShieldSettingsChanged()
        }

        updateSubControlsEnabled(!isWhitelisted)
    }

    private fun updateMasterShieldUi(shieldsUp: Boolean) {
        if (shieldsUp) {
            binding.tvShieldsStatus.text = getString(R.string.shields_up)
            binding.tvShieldsStatusSub.text = getString(R.string.shields_up_desc)
            binding.ivShieldsStatusDot.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.green_success))
            binding.ivShieldIcon.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.primary))
            binding.switchShieldsMaster.isChecked = true
        } else {
            binding.tvShieldsStatus.text = getString(R.string.shields_down)
            binding.tvShieldsStatusSub.text = getString(R.string.shields_down_desc)
            binding.ivShieldsStatusDot.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.text_secondary_dark))
            binding.ivShieldIcon.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.text_secondary_dark))
            binding.switchShieldsMaster.isChecked = false
        }
    }

    private fun updateSubControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.4f
        listOf(
            binding.layoutBlockingLevel,
            binding.layoutFingerprintProtection,
            binding.layoutHttpsUpgrade,
            binding.layoutScriptBlocking,
            binding.layoutCosmeticFiltering
        ).forEach {
            it.isEnabled = enabled
            it.alpha = alpha
        }
        binding.switchFingerprintProtection.isEnabled = enabled
        binding.switchHttpsUpgrade.isEnabled = enabled
        binding.switchScriptBlocking.isEnabled = enabled
        binding.switchCosmeticFiltering.isEnabled = enabled
    }

    // ── Blocking Level ────────────────────────────────────────────────────────

    private fun setupBlockingLevelSelector() {
        updateBlockingLevelUi(prefs.blockingLevel)

        binding.layoutBlockingLevel.setOnClickListener {
            if (!it.isEnabled) return@setOnClickListener
            // Toggle between Standard and Aggressive
            val newLevel = if (prefs.blockingLevel == BrowserPreferences.BLOCKING_STANDARD)
                BrowserPreferences.BLOCKING_AGGRESSIVE
            else BrowserPreferences.BLOCKING_STANDARD
            prefs.blockingLevel = newLevel
            updateBlockingLevelUi(newLevel)
            onShieldSettingsChanged()
        }
    }

    private fun updateBlockingLevelUi(level: Int) {
        if (level == BrowserPreferences.BLOCKING_AGGRESSIVE) {
            binding.tvBlockingLevelValue.text = getString(R.string.blocking_aggressive)
            binding.tvBlockingLevelValue.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.primary))
        } else {
            binding.tvBlockingLevelValue.text = getString(R.string.blocking_standard)
            binding.tvBlockingLevelValue.setTextColor(
                ContextCompat.getColor(requireContext(), android.R.color.tab_indicator_text))
        }
    }

    // ── Fingerprint Protection ────────────────────────────────────────────────

    private fun setupFingerprintToggle() {
        binding.switchFingerprintProtection.isChecked = prefs.isFingerprintProtectionEnabled
        binding.layoutFingerprintProtection.setOnClickListener {
            if (!it.isEnabled) return@setOnClickListener
            val newState = !binding.switchFingerprintProtection.isChecked
            binding.switchFingerprintProtection.isChecked = newState
            prefs.isFingerprintProtectionEnabled = newState
            onShieldSettingsChanged()
        }
    }

    // ── HTTPS Upgrade ─────────────────────────────────────────────────────────

    private fun setupHttpsUpgradeToggle() {
        binding.switchHttpsUpgrade.isChecked = prefs.isHttpsUpgradeEnabled
        binding.layoutHttpsUpgrade.setOnClickListener {
            if (!it.isEnabled) return@setOnClickListener
            val newState = !binding.switchHttpsUpgrade.isChecked
            binding.switchHttpsUpgrade.isChecked = newState
            prefs.isHttpsUpgradeEnabled = newState
            onShieldSettingsChanged()
        }
    }

    // ── Script Blocking (per-domain) ─────────────────────────────────────────

    private fun setupScriptBlockingToggle() {
        binding.switchScriptBlocking.isChecked = prefs.isScriptBlockingEnabledForDomain(domain)
        binding.layoutScriptBlocking.setOnClickListener {
            if (!it.isEnabled) return@setOnClickListener
            val newState = !binding.switchScriptBlocking.isChecked
            binding.switchScriptBlocking.isChecked = newState
            prefs.setScriptBlockingForDomain(domain, newState)
            onShieldSettingsChanged()
        }
    }

    // ── Cosmetic Filtering ────────────────────────────────────────────────────

    private fun setupCosmeticFilteringToggle() {
        binding.switchCosmeticFiltering.isChecked = prefs.isCosmeticFilteringEnabled
        binding.layoutCosmeticFiltering.setOnClickListener {
            if (!it.isEnabled) return@setOnClickListener
            val newState = !binding.switchCosmeticFiltering.isChecked
            binding.switchCosmeticFiltering.isChecked = newState
            prefs.isCosmeticFilteringEnabled = newState
            onShieldSettingsChanged()
        }
    }

    // ── Live Blocked Counter ──────────────────────────────────────────────────

    private fun setupBlockedCounter() {
        scope.launch {
            prefs.blockedRequestsFlow.collectLatest { count ->
                _binding?.tvBlockedCount?.text = count.toString()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        _binding = null
    }

    companion object {
        const val TAG = "SiteShieldDialog"
    }
}
