package com.onyx.browser.ui.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityShieldsBinding

class ShieldsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShieldsBinding
    private lateinit var prefs: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
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

    // ── TRACKERS & ADS ────────────────────────────────────────────────────────

    private fun setupTrackersAds() {
        binding.switchAdBlock.isChecked = prefs.isAdBlockEnabled
        binding.rowAdBlock.setOnClickListener {
            prefs.isAdBlockEnabled = !prefs.isAdBlockEnabled
            binding.switchAdBlock.isChecked = prefs.isAdBlockEnabled
        }

        updateBlockingLevelDisplay()
        binding.rowBlockingLevel.setOnClickListener {
            val options = arrayOf(
                getString(R.string.blocking_standard),
                getString(R.string.blocking_aggressive)
            )
            val current = prefs.blockingLevel.coerceIn(0, 1)
            AlertDialog.Builder(this)
                .setTitle(R.string.blocking_level_title)
                .setSingleChoiceItems(options, current) { dialog, which ->
                    prefs.blockingLevel = which
                    updateBlockingLevelDisplay()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun updateBlockingLevelDisplay() {
        binding.tvBlockingLevelValue.text = when (prefs.blockingLevel) {
            BrowserPreferences.BLOCKING_AGGRESSIVE -> getString(R.string.blocking_aggressive)
            else -> getString(R.string.blocking_standard)
        }
    }

    // ── CONNECTIONS ───────────────────────────────────────────────────────────

    private fun setupConnections() {
        binding.switchAmpRedirect.isChecked = prefs.isAutoRedirectAmpEnabled
        binding.rowAmpRedirect.setOnClickListener {
            prefs.isAutoRedirectAmpEnabled = !prefs.isAutoRedirectAmpEnabled
            binding.switchAmpRedirect.isChecked = prefs.isAutoRedirectAmpEnabled
        }

        binding.switchTrackingRedirect.isChecked = prefs.isAutoRedirectTrackingUrlsEnabled
        binding.rowTrackingRedirect.setOnClickListener {
            prefs.isAutoRedirectTrackingUrlsEnabled = !prefs.isAutoRedirectTrackingUrlsEnabled
            binding.switchTrackingRedirect.isChecked = prefs.isAutoRedirectTrackingUrlsEnabled
        }

        updateHttpsModeDisplay()
        binding.rowHttpsMode.setOnClickListener {
            val options = arrayOf(
                getString(R.string.https_mode_disabled),
                getString(R.string.https_mode_when_possible),
                getString(R.string.https_mode_strict)
            )
            val current = prefs.httpsUpgradeMode.coerceIn(0, 2)
            AlertDialog.Builder(this)
                .setTitle(R.string.https_mode_title)
                .setSingleChoiceItems(options, current) { dialog, which ->
                    prefs.httpsUpgradeMode = which
                    updateHttpsModeDisplay()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
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
        binding.switchGlobalScriptBlock.isChecked = prefs.isGlobalScriptBlockingEnabled
        binding.rowGlobalScriptBlock.setOnClickListener {
            prefs.isGlobalScriptBlockingEnabled = !prefs.isGlobalScriptBlockingEnabled
            binding.switchGlobalScriptBlock.isChecked = prefs.isGlobalScriptBlockingEnabled
        }
    }

    // ── COOKIES ───────────────────────────────────────────────────────────────

    private fun setupCookies() {
        updateCookieModeDisplay()
        binding.rowCookieMode.setOnClickListener {
            val options = arrayOf(
                getString(R.string.cookie_allow_all),
                getString(R.string.cookie_block_third_party),
                getString(R.string.cookie_block_all)
            )
            val current = prefs.cookieBlockingMode.coerceIn(0, 2)
            AlertDialog.Builder(this)
                .setTitle(R.string.cookie_blocking_title)
                .setSingleChoiceItems(options, current) { dialog, which ->
                    prefs.cookieBlockingMode = which
                    updateCookieModeDisplay()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
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
        binding.switchFingerprintProtection.isChecked = prefs.isFingerprintProtectionEnabled
        binding.rowFingerprintProtection.setOnClickListener {
            prefs.isFingerprintProtectionEnabled = !prefs.isFingerprintProtectionEnabled
            binding.switchFingerprintProtection.isChecked = prefs.isFingerprintProtectionEnabled
        }

        binding.switchFingerprintLang.isChecked = prefs.isFingerprintLangEnabled
        binding.rowFingerprintLang.setOnClickListener {
            prefs.isFingerprintLangEnabled = !prefs.isFingerprintLangEnabled
            binding.switchFingerprintLang.isChecked = prefs.isFingerprintLangEnabled
        }
    }

    // ── CONTENT FILTERING ─────────────────────────────────────────────────────

    private fun setupContentFiltering() {
        binding.rowCustomRules.setOnClickListener {
            CustomRulesDialog.newInstance()
                .show(supportFragmentManager, "custom_rules")
        }

        binding.rowFilterLists.setOnClickListener {
            startActivity(Intent(this, ContentFiltersActivity::class.java))
        }
    }

    // ── ELEMENT BLOCKING ──────────────────────────────────────────────────────

    private fun setupElementBlocking() {
        binding.switchElementBlockingPrivate.isChecked = prefs.isElementBlockingInPrivateEnabled
        binding.rowElementBlockingPrivate.setOnClickListener {
            prefs.isElementBlockingInPrivateEnabled = !prefs.isElementBlockingInPrivateEnabled
            binding.switchElementBlockingPrivate.isChecked = prefs.isElementBlockingInPrivateEnabled
        }
    }

    // ── SOCIAL MEDIA ──────────────────────────────────────────────────────────

    private fun setupSocialMedia() {
        fun updateSubRowVisibility() {
            val show = prefs.isSocialMediaBlockingEnabled
            binding.rowAllowFbLogins.alpha = if (show) 1f else 0.4f
            binding.rowAllowFbLogins.isEnabled = show
            binding.rowAllowTwitterEmbeds.alpha = if (show) 1f else 0.4f
            binding.rowAllowTwitterEmbeds.isEnabled = show
            binding.rowAllowLinkedInEmbeds.alpha = if (show) 1f else 0.4f
            binding.rowAllowLinkedInEmbeds.isEnabled = show
        }

        binding.switchSocialMediaBlocking.isChecked = prefs.isSocialMediaBlockingEnabled
        binding.rowSocialMediaBlocking.setOnClickListener {
            prefs.isSocialMediaBlockingEnabled = !prefs.isSocialMediaBlockingEnabled
            binding.switchSocialMediaBlocking.isChecked = prefs.isSocialMediaBlockingEnabled
            updateSubRowVisibility()
        }

        binding.switchAllowFbLogins.isChecked = prefs.allowFacebookLogins
        binding.rowAllowFbLogins.setOnClickListener {
            prefs.allowFacebookLogins = !prefs.allowFacebookLogins
            binding.switchAllowFbLogins.isChecked = prefs.allowFacebookLogins
        }

        binding.switchAllowTwitterEmbeds.isChecked = prefs.allowTwitterEmbeds
        binding.rowAllowTwitterEmbeds.setOnClickListener {
            prefs.allowTwitterEmbeds = !prefs.allowTwitterEmbeds
            binding.switchAllowTwitterEmbeds.isChecked = prefs.allowTwitterEmbeds
        }

        binding.switchAllowLinkedInEmbeds.isChecked = prefs.allowLinkedInEmbeds
        binding.rowAllowLinkedInEmbeds.setOnClickListener {
            prefs.allowLinkedInEmbeds = !prefs.allowLinkedInEmbeds
            binding.switchAllowLinkedInEmbeds.isChecked = prefs.allowLinkedInEmbeds
        }

        updateSubRowVisibility()
    }

    // ── LINKS ─────────────────────────────────────────────────────────────────

    private fun setupLinks() {
        binding.switchOpenLinksInApp.isChecked = prefs.isOpenLinksInAppEnabled
        binding.rowOpenLinksInApp.setOnClickListener {
            prefs.isOpenLinksInAppEnabled = !prefs.isOpenLinksInAppEnabled
            binding.switchOpenLinksInApp.isChecked = prefs.isOpenLinksInAppEnabled
        }
    }

    // ── SECURE DNS ────────────────────────────────────────────────────────────

    private val dnsProviders = linkedMapOf(
        "https://cloudflare-dns.com/dns-query" to "Cloudflare (1.1.1.1)",
        "https://dns.google/dns-query" to "Google (8.8.8.8)",
        "https://dns.nextdns.io" to "NextDNS",
        "__custom__" to "Custom URL"
    )

    private fun setupSecureDns() {
        binding.switchSecureDns.isChecked = prefs.isSecureDnsEnabled
        binding.rowSecureDns.setOnClickListener {
            prefs.isSecureDnsEnabled = !prefs.isSecureDnsEnabled
            binding.switchSecureDns.isChecked = prefs.isSecureDnsEnabled
        }

        updateDnsProviderDisplay()
        binding.rowDnsProvider.setOnClickListener {
            val keys = dnsProviders.keys.toList()
            val labels = dnsProviders.values.toTypedArray()
            val currentUrl = prefs.secureDnsProvider
            val currentIndex = keys.indexOfFirst { it == currentUrl }.takeIf { it >= 0 }
                ?: (keys.size - 1) // default to custom

            AlertDialog.Builder(this)
                .setTitle(R.string.secure_dns_provider_title)
                .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                    val selectedKey = keys[which]
                    if (selectedKey == "__custom__") {
                        dialog.dismiss()
                        showCustomDnsDialog()
                    } else {
                        prefs.secureDnsProvider = selectedKey
                        updateDnsProviderDisplay()
                        dialog.dismiss()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showCustomDnsDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.secureDnsProvider)
            hint = "https://your-doh-provider.com/dns-query"
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dns_provider_custom))
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val url = input.text?.toString()?.trim() ?: ""
                if (url.startsWith("https://")) {
                    prefs.secureDnsProvider = url
                    updateDnsProviderDisplay()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateDnsProviderDisplay() {
        val currentUrl = prefs.secureDnsProvider
        binding.tvDnsProviderValue.text = dnsProviders[currentUrl]
            ?: getString(R.string.dns_provider_custom)
    }

    // ── PRIVACY ───────────────────────────────────────────────────────────────

    private fun setupPrivacy() {
        binding.switchBlockAppBanner.isChecked = prefs.isBlockAppBannerEnabled
        binding.rowBlockAppBanner.setOnClickListener {
            prefs.isBlockAppBannerEnabled = !prefs.isBlockAppBannerEnabled
            binding.switchBlockAppBanner.isChecked = prefs.isBlockAppBannerEnabled
        }

        binding.switchDoNotTrack.isChecked = prefs.isDoNotTrackEnabled
        binding.rowDoNotTrack.setOnClickListener {
            prefs.isDoNotTrackEnabled = !prefs.isDoNotTrackEnabled
            binding.switchDoNotTrack.isChecked = prefs.isDoNotTrackEnabled
        }
    }
}
