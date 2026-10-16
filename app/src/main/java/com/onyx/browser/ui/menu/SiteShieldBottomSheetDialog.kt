package com.onyx.browser.ui.menu

import android.content.Intent
import android.net.http.SslCertificate
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
import java.text.SimpleDateFormat
import java.util.Locale

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
    private val sslCertificate: SslCertificate? = null,
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
        setupGoogleSignInToggle()
        setupAutoShredToggle()
        setupDesktopModeToggle()
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
            binding.cardConnectionSecurity.setOnClickListener {
                showCertificateDetailsDialog(isHttps = true)
            }
        } else if (isHttp) {
            binding.cardConnectionSecurity.visibility = View.VISIBLE
            binding.ivConnectionSecurityIcon.setImageResource(R.drawable.ic_lock)
            binding.ivConnectionSecurityIcon.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.red_danger)
            )
            binding.tvConnectionSecurityTitle.text = getString(R.string.connection_not_secure)
            binding.tvConnectionSecurityDesc.text = getString(R.string.connection_not_secure_desc)
            binding.cardConnectionSecurity.setOnClickListener {
                showCertificateDetailsDialog(isHttps = false)
            }
        } else {
            binding.cardConnectionSecurity.visibility = View.GONE
        }
    }

    private fun showCertificateDetailsDialog(isHttps: Boolean) {
        val messageBuilder = StringBuilder()
        if (isHttps) {
            messageBuilder.append(getString(R.string.certificate_secure_explanation)).append("\n\n")
            val cert = sslCertificate
            if (cert != null) {
                val issuedTo = cert.issuedTo
                if (issuedTo != null) {
                    messageBuilder.append("• ").append(getString(R.string.certificate_issued_to)).append(":\n")
                    if (!issuedTo.cName.isNullOrBlank()) messageBuilder.append("  ").append(getString(R.string.certificate_common_name, issuedTo.cName)).append("\n")
                    if (!issuedTo.oName.isNullOrBlank()) messageBuilder.append("  ").append(getString(R.string.certificate_organization, issuedTo.oName)).append("\n")
                    if (!issuedTo.uName.isNullOrBlank()) messageBuilder.append("  ").append(getString(R.string.certificate_organizational_unit, issuedTo.uName)).append("\n")
                }
                val issuedBy = cert.issuedBy
                if (issuedBy != null) {
                    messageBuilder.append("\n• ").append(getString(R.string.certificate_issued_by)).append(":\n")
                    if (!issuedBy.cName.isNullOrBlank()) messageBuilder.append("  ").append(getString(R.string.certificate_common_name, issuedBy.cName)).append("\n")
                    if (!issuedBy.oName.isNullOrBlank()) messageBuilder.append("  ").append(getString(R.string.certificate_organization, issuedBy.oName)).append("\n")
                }
                val validFrom = cert.validNotBeforeDate
                val validTo = cert.validNotAfterDate
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                messageBuilder.append("\n• ").append(getString(R.string.certificate_validity)).append(":\n")
                if (validFrom != null) messageBuilder.append("  ").append(getString(R.string.certificate_valid_from, sdf.format(validFrom))).append("\n")
                if (validTo != null) messageBuilder.append("  ").append(getString(R.string.certificate_valid_to, sdf.format(validTo))).append("\n")
            } else {
                messageBuilder.append("Host: ").append(domain.ifBlank { currentUrl }).append("\n")
                messageBuilder.append("Protocol: HTTPS / TLS\n")
                messageBuilder.append("Status: Verified & Encrypted")
            }
        } else {
            messageBuilder.append(getString(R.string.certificate_insecure_explanation)).append("\n\n")
            messageBuilder.append("Host: ").append(domain.ifBlank { currentUrl }).append("\n")
            messageBuilder.append("Protocol: HTTP (Plaintext)\n")
            messageBuilder.append("Status: Unencrypted / Not Private")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.certificate_info_title))
            .setMessage(messageBuilder.toString().trim())
            .setPositiveButton(R.string.close, null)
            .show()
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
            val ctx = requireContext().applicationContext
            scope.launch(Dispatchers.IO) {
                try {
                    com.onyx.browser.data.filter.FilterListManager.recompileFilters(ctx)
                } catch (_: Exception) {}
            }
            refreshAllSiteShieldControls()
            Toast.makeText(
                requireContext(),
                getString(R.string.permission_reset_done, domain),
                Toast.LENGTH_SHORT
            ).show()
            onShieldSettingsChanged()
        }
    }

    private fun refreshAllSiteShieldControls() {
        updatePermissionBadges()
        val isWhitelisted = prefs.isDomainWhitelisted(domain)
        val shieldsUp = prefs.isAdBlockEnabled && !isWhitelisted
        updateMasterShieldUi(shieldsUp)
        updateSubControlsEnabled(shieldsUp)
        updateBlockingLevelUi(prefs.getEffectiveBlockingLevel(domain))
        val isNetworkBlocked = prefs.isScriptBlockingEnabledForDomain(domain)
        val isJsBlocked = prefs.getSiteJavaScriptPermission(domain) == SitePermissionState.BLOCKED
        binding.switchScriptBlocking.isChecked = isNetworkBlocked || isJsBlocked
        binding.switchGoogleSignIn.isChecked = prefs.isWebGoogleSignInAllowed(domain) ?: !prefs.isGoogleSignInBlockedGlobal
        binding.switchAutoShred.isChecked = prefs.isSiteAutoShredEnabled(domain)
        binding.switchDesktopMode.isChecked = prefs.getEffectiveDesktopMode(domain)
        setupCustomBlockedElements()
        updateResetPermissionsVisibility()
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
        val shieldsUp = prefs.isAdBlockEnabled && !prefs.isDomainWhitelisted(domain)
        updateMasterShieldUi(shieldsUp)

        val toggleAction = {
            if (!prefs.isAdBlockEnabled) {
                // If adblocker was globally disabled, tapping master shield turns it on
                prefs.isAdBlockEnabled = true
                prefs.setDomainWhitelisted(domain, false)
            } else {
                val currentlyWhitelisted = prefs.isDomainWhitelisted(domain)
                val newShieldsUp = currentlyWhitelisted
                prefs.setDomainWhitelisted(domain, !newShieldsUp)
            }
            refreshAllSiteShieldControls()
            onShieldSettingsChanged()
        }

        binding.layoutShieldsMaster.setOnClickListener { toggleAction() }
        binding.switchShieldsMaster.setOnClickListener { toggleAction() }

        updateSubControlsEnabled(shieldsUp)
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
        // Independent site controls (Desktop Mode, Auto-shred, Google Sign-in) always remain enabled
        binding.layoutGoogleSignIn.isEnabled = true
        binding.layoutGoogleSignIn.alpha = 1.0f
        binding.switchGoogleSignIn.isEnabled = true
        binding.layoutAutoShred.isEnabled = true
        binding.layoutAutoShred.alpha = 1.0f
        binding.switchAutoShred.isEnabled = true
        binding.layoutDesktopMode.isEnabled = true
        binding.layoutDesktopMode.alpha = 1.0f
        binding.switchDesktopMode.isEnabled = true
    }

    // ── Blocking Level (Per-Domain with Global Fallback) ───────────────────────

    private fun setupBlockingLevelSelector() {
        val currentLevel = prefs.getEffectiveBlockingLevel(domain)
        updateBlockingLevelUi(currentLevel)

        binding.layoutBlockingLevel.setOnClickListener {
            if (!it.isEnabled) return@setOnClickListener
            val current = prefs.getEffectiveBlockingLevel(domain)
            val newLevel = if (current == BrowserPreferences.BLOCKING_STANDARD)
                BrowserPreferences.BLOCKING_AGGRESSIVE
            else BrowserPreferences.BLOCKING_STANDARD
            prefs.setSiteBlockingLevel(domain, newLevel)
            updateBlockingLevelUi(newLevel)
            updateResetPermissionsVisibility()
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
        binding.switchHttpsUpgrade.setOnCheckedChangeListener { _, isChecked ->
            if (prefs.isHttpsUpgradeEnabled != isChecked) {
                prefs.isHttpsUpgradeEnabled = isChecked
                onShieldSettingsChanged()
            }
        }
    }

    // ── Script Blocking (per-domain) ─────────────────────────────────────────

    private fun setupScriptBlockingToggle() {
        // A domain blocks scripts if either the network-level interceptor is set
        // OR the per-site JS permission is explicitly BLOCKED (set via SingleSiteSettings).
        val isNetworkBlocked = prefs.isScriptBlockingEnabledForDomain(domain)
        val isJsBlocked = prefs.getSiteJavaScriptPermission(domain) == com.onyx.browser.data.preferences.SitePermissionState.BLOCKED
        val isBlocked = isNetworkBlocked || isJsBlocked
        binding.switchScriptBlocking.isChecked = isBlocked
        binding.layoutScriptBlocking.setOnClickListener {
            if (!it.isEnabled) return@setOnClickListener
            val newBlocked = !binding.switchScriptBlocking.isChecked
            binding.switchScriptBlocking.isChecked = newBlocked
            // Sync both mechanisms: network-level interceptor and WebSettings JS disable
            prefs.setScriptBlockingForDomain(domain, newBlocked)
            prefs.setSiteJavaScriptPermission(
                domain,
                if (newBlocked) com.onyx.browser.data.preferences.SitePermissionState.BLOCKED
                else com.onyx.browser.data.preferences.SitePermissionState.ASK
            )
            updateResetPermissionsVisibility()
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

    // ── Google Sign-In Prompts ───────────────────────────────────────────────

    private fun setupGoogleSignInToggle() {
        val isAllowed = prefs.isWebGoogleSignInAllowed(domain) ?: !prefs.isGoogleSignInBlockedGlobal
        binding.switchGoogleSignIn.isChecked = isAllowed
        binding.layoutGoogleSignIn.setOnClickListener {
            if (!it.isEnabled) return@setOnClickListener
            val newState = !binding.switchGoogleSignIn.isChecked
            binding.switchGoogleSignIn.isChecked = newState
            prefs.setSiteGoogleSignInPermission(
                domain,
                if (newState) SitePermissionState.ALLOWED else SitePermissionState.BLOCKED
            )
            updateResetPermissionsVisibility()
            onShieldSettingsChanged()
        }
    }

    // ── Auto-Shred Site Data on Tab Close ────────────────────────────────────

    private fun setupAutoShredToggle() {
        val isAutoShred = prefs.isSiteAutoShredEnabled(domain)
        binding.switchAutoShred.isChecked = isAutoShred
        binding.layoutAutoShred.setOnClickListener {
            if (!it.isEnabled) return@setOnClickListener
            val newState = !binding.switchAutoShred.isChecked
            binding.switchAutoShred.isChecked = newState
            prefs.setSiteAutoShredEnabled(domain, newState)
            updateResetPermissionsVisibility()
            onShieldSettingsChanged()
        }
    }

    // ── Desktop Site ──────────────────────────────────────────────────────────

    private fun setupDesktopModeToggle() {
        val isDesktop = prefs.getEffectiveDesktopMode(domain)
        binding.switchDesktopMode.isChecked = isDesktop
        binding.layoutDesktopMode.setOnClickListener {
            if (!it.isEnabled) return@setOnClickListener
            val currentEffective = prefs.getEffectiveDesktopMode(domain)
            val newState = if (currentEffective) SitePermissionState.BLOCKED else SitePermissionState.ALLOWED
            prefs.setSiteDesktopModeState(domain, newState)
            binding.switchDesktopMode.isChecked = !currentEffective
            updateResetPermissionsVisibility()
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
            binding.tvCustomBlockedCount.text = if (count == 1) {
                getString(R.string.custom_blocked_elements_single)
            } else {
                getString(R.string.custom_blocked_elements_plural, count)
            }
            binding.btnClearBlockedElements.setOnClickListener {
                prefs.clearCustomBlockedRulesForDomain(domain)
                val ctx = requireContext().applicationContext
                scope.launch(Dispatchers.IO) {
                    try {
                        com.onyx.browser.data.filter.FilterListManager.recompileFilters(ctx)
                    } catch (_: Exception) {}
                }
                binding.layoutCustomBlockedElements.visibility = View.GONE
                Toast.makeText(
                    requireContext(),
                    getString(R.string.custom_blocked_elements_reset_done, domain),
                    Toast.LENGTH_SHORT
                ).show()
                updateResetPermissionsVisibility()
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
