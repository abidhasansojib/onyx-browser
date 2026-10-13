package com.onyx.browser.ui.menu

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.data.preferences.SitePermissionState
import com.onyx.browser.databinding.BottomSheetSiteShieldBinding
import com.onyx.browser.web.SiteDataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Integrated Page Info & Shields panel — an expandable BottomSheet with:
 *  - Connection Security & HTTPS state
 *  - Per-site Hardware Permissions (Camera, Microphone, Location) with 3-state control
 *  - Per-site Cookie & WebStorage Purging
 *  - Shields ON/OFF (master whitelist toggle)
 *  - Ad & Tracker Blocking level (Standard / Aggressive)
 *  - Fingerprint Protection
 *  - HTTPS Upgrade
 *  - Script Blocking per domain
 *  - Cosmetic Element Hiding
 *  - Live blocked requests counter
 */
class SiteShieldBottomSheetDialog(
    private val currentUrl: String,
    private val pageBlockedCount: Int = -1,
    private val isIncognito: Boolean = false,
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

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = BrowserPreferences.getInstance(requireContext())
        domain = prefs.cleanDomain(currentUrl)

        binding.tvShieldDomain.text = if (domain.isNotBlank()) domain else "This page"

        setupConnectionSecurity()
        setupSitePermissions()
        setupSiteDataPurge()
        setupShieldsMasterToggle()
        setupBlockingLevelSelector()
        setupFingerprintToggle()
        setupHttpsUpgradeToggle()
        setupScriptBlockingToggle()
        setupCosmeticFilteringToggle()
        setupCustomBlockedElements()
        setupBlockedCounter()

        binding.btnShieldSettings.setOnClickListener {
            startActivity(Intent(requireContext(), com.onyx.browser.ui.settings.SiteSettingsActivity::class.java))
            dismiss()
        }

        binding.btnShieldDone.setOnClickListener { dismiss() }
    }

    // ── Connection Security ────────────────────────────────────────────────────

    private fun setupConnectionSecurity() {
        val isHttps = currentUrl.startsWith("https://", ignoreCase = true)
        val isHttp = currentUrl.startsWith("http://", ignoreCase = true)
        if (isHttps) {
            binding.cardConnectionSecurity.visibility = View.VISIBLE
            binding.ivConnectionSecurityIcon.setImageResource(R.drawable.ic_lock)
            binding.ivConnectionSecurityIcon.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.green_success)
            )
            binding.tvConnectionSecurityTitle.text = getString(R.string.connection_secure)
            binding.tvConnectionSecurityDesc.text = getString(R.string.connection_secure_desc)
        } else if (isHttp) {
            binding.cardConnectionSecurity.visibility = View.VISIBLE
            binding.ivConnectionSecurityIcon.setImageResource(R.drawable.ic_lock)
            binding.ivConnectionSecurityIcon.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.red_danger)
            )
            binding.tvConnectionSecurityTitle.text = getString(R.string.connection_not_secure)
            binding.tvConnectionSecurityDesc.text = getString(R.string.connection_not_secure_desc)
        } else {
            binding.cardConnectionSecurity.visibility = View.GONE
        }
    }

    // ── Per-Site Hardware Permissions ──────────────────────────────────────────

    private fun setupSitePermissions() {
        updatePermissionBadges()
        updateResetPermissionsVisibility()

        binding.layoutCameraPermission.setOnClickListener {
            val current = prefs.getSiteCameraPermission(domain)
            showPermissionPicker(
                title = "${getString(R.string.permission_camera)} — $domain",
                currentState = current
            ) { newState ->
                prefs.setSiteCameraPermission(domain, newState)
                updatePermissionBadges()
                updateResetPermissionsVisibility()
                onShieldSettingsChanged()
            }
        }

        binding.layoutMicPermission.setOnClickListener {
            val current = prefs.getSiteMicPermission(domain)
            showPermissionPicker(
                title = "${getString(R.string.permission_microphone)} — $domain",
                currentState = current
            ) { newState ->
                prefs.setSiteMicPermission(domain, newState)
                updatePermissionBadges()
                updateResetPermissionsVisibility()
                onShieldSettingsChanged()
            }
        }

        binding.layoutLocationPermission.setOnClickListener {
            val current = prefs.getSiteLocationPermission(domain)
            showPermissionPicker(
                title = "${getString(R.string.permission_location)} — $domain",
                currentState = current
            ) { newState ->
                prefs.setSiteLocationPermission(domain, newState)
                updatePermissionBadges()
                updateResetPermissionsVisibility()
                onShieldSettingsChanged()
            }
        }

        binding.layoutNotificationsPermission.setOnClickListener {
            val current = prefs.getSiteNotificationsPermission(domain)
            showPermissionPicker(
                title = "${getString(R.string.permission_notifications)} — $domain",
                currentState = current
            ) { newState ->
                prefs.setSiteNotificationsPermission(domain, newState)
                updatePermissionBadges()
                updateResetPermissionsVisibility()
                onShieldSettingsChanged()
            }
        }

        binding.btnResetPermissions.setOnClickListener {
            prefs.resetSitePermissions(domain)
            updatePermissionBadges()
            updateResetPermissionsVisibility()
            Toast.makeText(
                requireContext(),
                getString(R.string.permission_reset_done, domain),
                Toast.LENGTH_SHORT
            ).show()
            onShieldSettingsChanged()
        }
    }

    private fun updatePermissionBadges() {
        updatePermissionBadge(binding.tvCameraPermissionState, prefs.getSiteCameraPermission(domain))
        updatePermissionBadge(binding.tvMicPermissionState, prefs.getSiteMicPermission(domain))
        updatePermissionBadge(binding.tvLocationPermissionState, prefs.getSiteLocationPermission(domain))
        updatePermissionBadge(binding.tvNotificationsPermissionState, prefs.getSiteNotificationsPermission(domain))
    }

    private fun updatePermissionBadge(textView: TextView, state: SitePermissionState) {
        when (state) {
            SitePermissionState.ALLOWED -> {
                textView.text = getString(R.string.permission_allowed)
                textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_success))
            }
            SitePermissionState.BLOCKED -> {
                textView.text = getString(R.string.permission_blocked)
                textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.red_danger))
            }
            SitePermissionState.ASK -> {
                textView.text = getString(R.string.permission_ask)
                textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.settings_subtitle_text))
            }
        }
    }

    private fun updateResetPermissionsVisibility() {
        binding.btnResetPermissions.visibility = if (prefs.hasCustomSitePermissions(domain)) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun showPermissionPicker(
        title: String,
        currentState: SitePermissionState,
        onStateSelected: (SitePermissionState) -> Unit
    ) {
        val options = arrayOf(
            getString(R.string.permission_allowed),
            getString(R.string.permission_blocked),
            getString(R.string.permission_ask)
        )
        val selectedIndex = when (currentState) {
            SitePermissionState.ALLOWED -> 0
            SitePermissionState.BLOCKED -> 1
            SitePermissionState.ASK -> 2
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val newState = when (which) {
                    0 -> SitePermissionState.ALLOWED
                    1 -> SitePermissionState.BLOCKED
                    else -> SitePermissionState.ASK
                }
                onStateSelected(newState)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Site Data & Storage Purging ───────────────────────────────────────────

    private fun setupSiteDataPurge() {
        binding.btnClearSiteData.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.clear_site_data_dialog_title, domain))
                .setMessage(getString(R.string.clear_site_data_dialog_message))
                .setPositiveButton(R.string.clear_site_data) { _, _ ->
                    val ctx = requireContext().applicationContext
                    SiteDataManager.clearSiteData(ctx, domain, isIncognito) {
                        Toast.makeText(
                            ctx,
                            getString(R.string.clear_site_data_done, domain),
                            Toast.LENGTH_SHORT
                        ).show()
                        onShieldSettingsChanged()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ── Shields Master Toggle ──────────────────────────────────────────────────

    private fun setupShieldsMasterToggle() {
        val isWhitelisted = prefs.isDomainWhitelisted(domain)
        updateMasterShieldUi(!isWhitelisted)

        binding.layoutShieldsMaster.setOnClickListener {
            val currentlyWhitelisted = prefs.isDomainWhitelisted(domain)
            val newShieldsUp = currentlyWhitelisted
            prefs.setDomainWhitelisted(domain, !newShieldsUp)
            updateMasterShieldUi(newShieldsUp)
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
                ContextCompat.getColor(requireContext(), R.color.green_success)
            )
            binding.ivShieldIcon.setColorFilter(
                com.onyx.browser.ui.theme.AccentColorManager.getAccentColor(requireContext())
            )
            binding.switchShieldsMaster.isChecked = true
        } else {
            binding.tvShieldsStatus.text = getString(R.string.shields_down)
            binding.tvShieldsStatusSub.text = getString(R.string.shields_down_desc)
            binding.ivShieldsStatusDot.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.settings_subtitle_text)
            )
            binding.ivShieldIcon.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.settings_subtitle_text)
            )
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
                com.onyx.browser.ui.theme.AccentColorManager.getAccentColor(requireContext())
            )
        } else {
            binding.tvBlockingLevelValue.text = getString(R.string.blocking_standard)
            binding.tvBlockingLevelValue.setTextColor(
                ContextCompat.getColor(requireContext(), android.R.color.tab_indicator_text)
            )
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

    // ── Custom Blocked Elements ────────────────────────────────────────────────

    private fun setupCustomBlockedElements() {
        if (domain.isBlank()) {
            binding.layoutCustomBlockedElements.visibility = View.GONE
            return
        }
        val blockedSelectors = prefs.getCustomBlockedSelectorsForDomain(domain)
        if (blockedSelectors.isEmpty()) {
            binding.layoutCustomBlockedElements.visibility = View.GONE
        } else {
            binding.layoutCustomBlockedElements.visibility = View.VISIBLE
            val count = blockedSelectors.size
            binding.tvCustomBlockedCount.text = if (count == 1) "1 element blocked on this site" else "$count elements blocked on this site"
            binding.btnClearBlockedElements.setOnClickListener {
                prefs.clearCustomBlockedRulesForDomain(domain)
                val ctx = requireContext().applicationContext
                scope.launch(Dispatchers.IO) {
                    try {
                        com.onyx.browser.data.filter.FilterListManager.recompileFilters(ctx)
                    } catch (_: Exception) {}
                }
                binding.layoutCustomBlockedElements.visibility = View.GONE
                Toast.makeText(requireContext(), "Reset blocked elements on $domain", Toast.LENGTH_SHORT).show()
                onShieldSettingsChanged()
            }
        }
    }

    // ── Live Blocked Counter ──────────────────────────────────────────────────

    private fun setupBlockedCounter() {
        if (pageBlockedCount >= 0) {
            binding.tvBlockedCount.text = pageBlockedCount.toString()
            binding.tvBlockedCounterScope.text = getString(R.string.blocked_on_this_page)
        } else {
            scope.launch {
                prefs.blockedRequestsFlow.collectLatest { count ->
                    _binding?.tvBlockedCount?.text = count.toString()
                    _binding?.tvBlockedCounterScope?.text = getString(R.string.blocked_all_time)
                }
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
