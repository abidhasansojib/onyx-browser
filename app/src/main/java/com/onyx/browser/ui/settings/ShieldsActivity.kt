package com.onyx.browser.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityShieldsBinding
import com.onyx.browser.ui.theme.AccentColorManager

class ShieldsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShieldsBinding
    private lateinit var prefs: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)
        binding = ActivityShieldsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = BrowserPreferences.getInstance(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupAll()
    }

    private fun setupAll() {
        setupTrackersAds()
        setupConnections()
        setupScripts()
        setupCookies()
        setupFingerprinting()
        setupContentFiltering()
        setupElementBlocking()
        setupSocialMedia()
        setupLinks()
        setupSecureDns()
        setupPrivacy()
    }

    private fun setupSwitchRow(
        row: android.view.View,
        switch: com.google.android.material.materialswitch.MaterialSwitch,
        getter: () -> Boolean,
        setter: (Boolean) -> Unit,
        onChanged: (() -> Unit)? = null
    ) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = getter()
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (getter() != isChecked) {
                setter(isChecked)
                onChanged?.invoke()
            }
        }
        row.setOnClickListener {
            switch.toggle()
        }
    }

    // ── TRACKERS & ADS ────────────────────────────────────────────────────────

    private fun setupTrackersAds() {
        setupSwitchRow(
            binding.rowAdBlock,
            binding.switchAdBlock,
            { prefs.isAdBlockEnabled },
            { prefs.isAdBlockEnabled = it }
        )

        updateBlockingLevelDisplay()
        binding.rowBlockingLevel.setOnClickListener {
            val sheet = BlockingLevelPickerSheet(prefs.blockingLevel) { selectedLevel ->
                prefs.blockingLevel = selectedLevel
                updateBlockingLevelDisplay()
            }
            sheet.show(supportFragmentManager, BlockingLevelPickerSheet.TAG)
        }

        setupSwitchRow(
            binding.rowAntiAdblockDetection,
            binding.switchAntiAdblockDetection,
            { prefs.isAntiAdblockDetectionEnabled },
            { prefs.isAntiAdblockDetectionEnabled = it }
        )
    }

    private fun updateBlockingLevelDisplay() {
        binding.tvBlockingLevelValue.text = when (prefs.blockingLevel) {
            BrowserPreferences.BLOCKING_AGGRESSIVE -> getString(R.string.blocking_aggressive)
            else -> getString(R.string.blocking_standard)
        }
    }

    // ── CONNECTIONS ───────────────────────────────────────────────────────────

    private fun setupConnections() {
        setupSwitchRow(
            binding.rowAmpRedirect,
            binding.switchAmpRedirect,
            { prefs.isAutoRedirectAmpEnabled },
            { prefs.isAutoRedirectAmpEnabled = it }
        )

        setupSwitchRow(
            binding.rowTrackingRedirect,
            binding.switchTrackingRedirect,
            { prefs.isAutoRedirectTrackingUrlsEnabled },
            { prefs.isAutoRedirectTrackingUrlsEnabled = it }
        )

        updateHttpsModeDisplay()
        binding.rowHttpsMode.setOnClickListener {
            val sheet = HttpsModePickerSheet(prefs.httpsUpgradeMode) { selectedMode ->
                prefs.httpsUpgradeMode = selectedMode
                updateHttpsModeDisplay()
            }
            sheet.show(supportFragmentManager, HttpsModePickerSheet.TAG)
        }
    }

    private fun updateHttpsModeDisplay() {
        binding.tvHttpsModeValue.text = when (prefs.httpsUpgradeMode) {
            BrowserPreferences.HTTPS_MODE_DISABLED -> getString(R.string.https_mode_disabled)
            BrowserPreferences.HTTPS_MODE_STRICT -> getString(R.string.https_mode_strict)
            else -> getString(R.string.https_mode_when_possible)
        }
    }

    // ── SCRIPTS ───────────────────────────────────────────────────────────────

    private fun setupScripts() {
        setupSwitchRow(
            binding.rowGlobalScriptBlock,
            binding.switchGlobalScriptBlock,
            { prefs.isGlobalScriptBlockingEnabled },
            {
                prefs.isGlobalScriptBlockingEnabled = it
                updateUserScriptsSubtitle()
            }
        )

        binding.rowManageUserScripts.setOnClickListener {
            startActivity(Intent(this, UserScriptsActivity::class.java))
        }
        updateUserScriptsSubtitle()
    }

    private fun updateUserScriptsSubtitle() {
        if (prefs.isGlobalScriptBlockingEnabled) {
            binding.tvUserScriptsSubtitle.text = getString(R.string.userscripts_disabled_scripts_blocked)
        } else {
            binding.tvUserScriptsSubtitle.text = getString(R.string.userscripts_desc)
        }
    }

    // ── COOKIES ───────────────────────────────────────────────────────────────

    private fun setupCookies() {
        updateCookieModeDisplay()
        binding.rowCookieMode.setOnClickListener {
            val sheet = CookieModePickerSheet(prefs.cookieBlockingMode) { selectedMode ->
                prefs.cookieBlockingMode = selectedMode
                updateCookieModeDisplay()
            }
            sheet.show(supportFragmentManager, CookieModePickerSheet.TAG)
        }

        setupSwitchRow(
            binding.rowCookieAutoclear,
            binding.switchCookieAutoclear,
            { prefs.isCookieAutoclearOnCloseEnabled },
            { prefs.isCookieAutoclearOnCloseEnabled = it }
        )

        setupSwitchRow(
            binding.rowClearOnExit,
            binding.switchClearOnExit,
            { prefs.isClearOnExitEnabled },
            { prefs.isClearOnExitEnabled = it }
        )
    }

    private fun updateCookieModeDisplay() {
        binding.tvCookieModeValue.text = when (prefs.cookieBlockingMode) {
            BrowserPreferences.COOKIE_BLOCK_NONE -> getString(R.string.cookie_allow_all)
            BrowserPreferences.COOKIE_BLOCK_ALL -> getString(R.string.cookie_block_all)
            else -> getString(R.string.cookie_block_third_party)
        }
    }

    // ── FINGERPRINTING ────────────────────────────────────────────────────────

    private fun setupFingerprinting() {
        setupSwitchRow(
            binding.rowFingerprintProtection,
            binding.switchFingerprintProtection,
            { prefs.isFingerprintProtectionEnabled },
            { prefs.isFingerprintProtectionEnabled = it }
        )

        setupSwitchRow(
            binding.rowFingerprintLang,
            binding.switchFingerprintLang,
            { prefs.isFingerprintLangEnabled },
            { prefs.isFingerprintLangEnabled = it }
        )

        setupSwitchRow(
            binding.rowWebRtcProtection,
            binding.switchWebRtcProtection,
            { prefs.isWebRtcProtectedGlobal },
            { prefs.isWebRtcProtectedGlobal = it }
        )
    }

    // ── CONTENT FILTERING ─────────────────────────────────────────────────────

    private fun setupContentFiltering() {
        binding.rowFilterLists.setOnClickListener {
            startActivity(Intent(this, ContentFiltersActivity::class.java))
        }
    }

    // ── ELEMENT BLOCKING ──────────────────────────────────────────────────────

    private fun setupElementBlocking() {
        fun updateElementSubRowVisibility() {
            val enabled = prefs.isBlockElementEnabled
            binding.rowElementBlockingPrivate.alpha = if (enabled) 1f else 0.4f
            binding.rowElementBlockingPrivate.isEnabled = enabled
        }

        setupSwitchRow(
            binding.rowBlockElement,
            binding.switchBlockElement,
            { prefs.isBlockElementEnabled },
            { prefs.isBlockElementEnabled = it },
            { updateElementSubRowVisibility() }
        )

        setupSwitchRow(
            binding.rowElementBlockingPrivate,
            binding.switchElementBlockingPrivate,
            { prefs.isElementBlockingInPrivateEnabled },
            { prefs.isElementBlockingInPrivateEnabled = it }
        )

        updateElementSubRowVisibility()
    }

    // ── SOCIAL MEDIA ──────────────────────────────────────────────────────────

    private fun setupSocialMedia() {
        fun updateSubRowVisibility() {
            val show = prefs.isSocialMediaBlockingEnabled
            binding.rowAllowGoogleSignIn.alpha = if (show) 1f else 0.4f
            binding.rowAllowGoogleSignIn.isEnabled = show
            binding.rowAllowFbLogins.alpha = if (show) 1f else 0.4f
            binding.rowAllowFbLogins.isEnabled = show
            binding.rowAllowTwitterEmbeds.alpha = if (show) 1f else 0.4f
            binding.rowAllowTwitterEmbeds.isEnabled = show
            binding.rowAllowLinkedInEmbeds.alpha = if (show) 1f else 0.4f
            binding.rowAllowLinkedInEmbeds.isEnabled = show
        }

        setupSwitchRow(
            binding.rowSocialMediaBlocking,
            binding.switchSocialMediaBlocking,
            { prefs.isSocialMediaBlockingEnabled },
            { prefs.isSocialMediaBlockingEnabled = it },
            { updateSubRowVisibility() }
        )

        setupSwitchRow(
            binding.rowAllowGoogleSignIn,
            binding.switchAllowGoogleSignIn,
            { !prefs.isGoogleSignInBlockedGlobal },
            { prefs.isGoogleSignInBlockedGlobal = !it }
        )

        setupSwitchRow(
            binding.rowAllowFbLogins,
            binding.switchAllowFbLogins,
            { prefs.allowFacebookLogins },
            { prefs.allowFacebookLogins = it }
        )

        setupSwitchRow(
            binding.rowAllowTwitterEmbeds,
            binding.switchAllowTwitterEmbeds,
            { prefs.allowTwitterEmbeds },
            { prefs.allowTwitterEmbeds = it }
        )

        setupSwitchRow(
            binding.rowAllowLinkedInEmbeds,
            binding.switchAllowLinkedInEmbeds,
            { prefs.allowLinkedInEmbeds },
            { prefs.allowLinkedInEmbeds = it }
        )

        updateSubRowVisibility()
    }

    // ── LINKS ─────────────────────────────────────────────────────────────────

    private fun setupLinks() {
        setupSwitchRow(
            binding.rowOpenLinksInApp,
            binding.switchOpenLinksInApp,
            { prefs.isOpenLinksInAppEnabled },
            { prefs.isOpenLinksInAppEnabled = it }
        )
    }

    // ── SECURE DNS ────────────────────────────────────────────────────────────

    private val dnsProviders = linkedMapOf(
        "https://cloudflare-dns.com/dns-query" to "Cloudflare (1.1.1.1)",
        "https://dns.google/dns-query" to "Google (8.8.8.8)",
        "https://dns.nextdns.io" to "NextDNS",
        "__custom__" to "Custom URL"
    )

    private fun setupSecureDns() {
        setupSwitchRow(
            binding.rowSecureDns,
            binding.switchSecureDns,
            { prefs.isSecureDnsEnabled },
            { prefs.isSecureDnsEnabled = it }
        )

        updateDnsProviderDisplay()
        binding.rowDnsProvider.setOnClickListener {
            val sheet = DnsProviderPickerSheet(prefs.secureDnsProvider) { selectedProvider ->
                prefs.secureDnsProvider = selectedProvider
                updateDnsProviderDisplay()
            }
            sheet.show(supportFragmentManager, DnsProviderPickerSheet.TAG)
        }
    }

    private fun updateDnsProviderDisplay() {
        val currentUrl = prefs.secureDnsProvider
        binding.tvDnsProviderValue.text = dnsProviders[currentUrl]
            ?: getString(R.string.dns_provider_custom)
    }

    // ── PRIVACY ───────────────────────────────────────────────────────────────

    private fun setupPrivacy() {
        setupSwitchRow(
            binding.rowBlockAppBanner,
            binding.switchBlockAppBanner,
            { prefs.isBlockAppBannerEnabled },
            { prefs.isBlockAppBannerEnabled = it }
        )

        setupSwitchRow(
            binding.rowDoNotTrack,
            binding.switchDoNotTrack,
            { prefs.isDoNotTrackEnabled },
            { prefs.isDoNotTrackEnabled = it }
        )
    }
}
