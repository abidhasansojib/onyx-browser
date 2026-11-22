package com.onyx.browser.ui.settings.shields

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Https
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.R
import com.onyx.browser.data.filter.FilterListManager
import com.onyx.browser.data.preferences.BrowserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.components.PreferenceItem
import com.onyx.browser.ui.components.RadioPreference
import com.onyx.browser.ui.components.SwitchPreference
import com.onyx.browser.ui.components.AppTopBar
import com.onyx.browser.ui.settings.userscript.UserScriptsActivity
import com.onyx.browser.ui.theme.OnyxTheme

class ShieldsActivity : ComponentActivity() {

    private lateinit var prefs: BrowserPreferences

    private var isAdBlockEnabled by mutableStateOf(true)

    private var isAmpRedirectEnabled by mutableStateOf(true)
    private var isTrackingRedirectEnabled by mutableStateOf(true)
    private var httpsUpgradeMode by mutableIntStateOf(1)

    private var isGlobalScriptBlocked by mutableStateOf(false)
    private var cookieMode by mutableIntStateOf(1)
    private var isClearOnExitEnabled by mutableStateOf(false)
    private var clearOnExitSummary by mutableStateOf("")

    private var isFingerprintEnabled by mutableStateOf(true)
    private var isFingerprintLangEnabled by mutableStateOf(true)
    private var isWebRtcProtected by mutableStateOf(true)

    private var isBlockElementEnabled by mutableStateOf(true)
    private var isElementInPrivateEnabled by mutableStateOf(true)
    private var blockedElementsSummary by mutableStateOf("")

    private var isSocialMediaBlockingEnabled by mutableStateOf(false)
    private var isGoogleSignInAllowed by mutableStateOf(true)
    private var isFacebookAllowed by mutableStateOf(false)
    private var isTwitterAllowed by mutableStateOf(false)
    private var isLinkedInAllowed by mutableStateOf(false)

    private var isSecureDnsEnabled by mutableStateOf(false)
    private var secureDnsProvider by mutableStateOf("https://cloudflare-dns.com/dns-query")

    private var isBlockAppBannerEnabled by mutableStateOf(true)
    private var isDoNotTrackEnabled by mutableStateOf(true)

    // BottomSheet states
    private var showHttpsModePicker by mutableStateOf(false)
    private var showCookieModePicker by mutableStateOf(false)
    private var showDnsProviderPicker by mutableStateOf(false)
    private var showClearOnExitPicker by mutableStateOf(false)
    private var showManageBlockedElementsSheet by mutableStateOf(false)
    private var recompileJob: Job? = null

    private fun scheduleFilterRecompile() {
        val appCtx = applicationContext
        recompileJob?.cancel()
        recompileJob = lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            delay(350)
            try {
                FilterListManager.recompileFilters(appCtx)
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        prefs = BrowserPreferences.getInstance(this)
        loadState()

        setContent {
            val isDark = when (prefs.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = false) {
                ShieldsScreen(
                    onBackClick = { finish() },

                    // Trackers & Ads
                    isAdBlock = isAdBlockEnabled,
                    onAdBlockChange = { isAdBlockEnabled = it; prefs.isAdBlockEnabled = it },
                    onFilterListsClick = { startActivity(Intent(this, ContentFiltersActivity::class.java)) },

                    // Connections
                    isAmpRedirect = isAmpRedirectEnabled,
                    onAmpRedirectChange = { isAmpRedirectEnabled = it; prefs.isAutoRedirectAmpEnabled = it },
                    isTrackingRedirect = isTrackingRedirectEnabled,
                    onTrackingRedirectChange = { isTrackingRedirectEnabled = it; prefs.isAutoRedirectTrackingUrlsEnabled = it },
                    httpsModeText = when (httpsUpgradeMode) {
                        BrowserPreferences.HTTPS_MODE_DISABLED -> "Disabled"
                        BrowserPreferences.HTTPS_MODE_STRICT -> "Strict (HTTPS Only)"
                        else -> "When Possible"
                    },
                    onHttpsModeClick = { showHttpsModePicker = true },

                    // Scripts
                    isGlobalScriptBlocked = isGlobalScriptBlocked,
                    onGlobalScriptBlockedChange = { isGlobalScriptBlocked = it; prefs.isGlobalScriptBlockingEnabled = it },
                    onManageUserScriptsClick = { startActivity(Intent(this, UserScriptsActivity::class.java)) },

                    // Cookies & Exit
                    cookieModeText = when (cookieMode) {
                        BrowserPreferences.COOKIE_BLOCK_NONE -> "Allow All Cookies"
                        BrowserPreferences.COOKIE_BLOCK_ALL -> "Block All Cookies"
                        else -> "Block 3rd-Party Cookies"
                    },
                    onCookieModeClick = { showCookieModePicker = true },
                    clearOnExitSummary = clearOnExitSummary,
                    onClearOnExitClick = { showClearOnExitPicker = true },

                    // Fingerprinting
                    isFingerprint = isFingerprintEnabled,
                    onFingerprintChange = { isFingerprintEnabled = it; prefs.isFingerprintProtectionEnabled = it },
                    isFingerprintLang = isFingerprintLangEnabled,
                    onFingerprintLangChange = { isFingerprintLangEnabled = it; prefs.isFingerprintLangEnabled = it },
                    isWebRtc = isWebRtcProtected,
                    onWebRtcChange = { isWebRtcProtected = it; prefs.isWebRtcProtectedGlobal = it },

                    // Element Blocking
                    isBlockElement = isBlockElementEnabled,
                    onBlockElementChange = { isBlockElementEnabled = it; prefs.isBlockElementEnabled = it },
                    isElementInPrivate = isElementInPrivateEnabled,
                    onElementInPrivateChange = { isElementInPrivateEnabled = it; prefs.isElementBlockingInPrivateEnabled = it },
                    blockedElementsSummary = blockedElementsSummary,
                    onManageBlockedElementsClick = { showManageBlockedElementsSheet = true },

                    // Social Media
                    isSocialMedia = isSocialMediaBlockingEnabled,
                    onSocialMediaChange = { isSocialMediaBlockingEnabled = it; prefs.isSocialMediaBlockingEnabled = it; scheduleFilterRecompile() },
                    isGoogleSignIn = isGoogleSignInAllowed,
                    onGoogleSignInChange = { isGoogleSignInAllowed = it; prefs.isGoogleSignInBlockedGlobal = !it },
                    isFacebook = isFacebookAllowed,
                    onFacebookChange = { isFacebookAllowed = it; prefs.allowFacebookLogins = it; scheduleFilterRecompile() },
                    isTwitter = isTwitterAllowed,
                    onTwitterChange = { isTwitterAllowed = it; prefs.allowTwitterEmbeds = it; scheduleFilterRecompile() },
                    isLinkedIn = isLinkedInAllowed,
                    onLinkedInChange = { isLinkedInAllowed = it; prefs.allowLinkedInEmbeds = it; scheduleFilterRecompile() },

                    // DNS & Privacy
                    isSecureDns = isSecureDnsEnabled,
                    onSecureDnsChange = { isSecureDnsEnabled = it; prefs.isSecureDnsEnabled = it },
                    dnsProviderText = getDnsDisplayName(secureDnsProvider),
                    onDnsProviderClick = { showDnsProviderPicker = true },
                    isBlockAppBanner = isBlockAppBannerEnabled,
                    onBlockAppBannerChange = { isBlockAppBannerEnabled = it; prefs.isBlockAppBannerEnabled = it; scheduleFilterRecompile() },
                    isDoNotTrack = isDoNotTrackEnabled,
                    onDoNotTrackChange = { isDoNotTrackEnabled = it; prefs.isDoNotTrackEnabled = it }
                )

                if (showHttpsModePicker) {
                    SingleChoiceBottomSheet(
                        title = "HTTPS Mode",
                        options = listOf("When Possible (Upgrade HTTP)", "Strict (HTTPS Only)", "Disabled"),
                        selectedIndex = when (httpsUpgradeMode) {
                            BrowserPreferences.HTTPS_MODE_STRICT -> 1
                            BrowserPreferences.HTTPS_MODE_DISABLED -> 2
                            else -> 0
                        },
                        onSelect = { idx ->
                            httpsUpgradeMode = when (idx) {
                                1 -> BrowserPreferences.HTTPS_MODE_STRICT
                                2 -> BrowserPreferences.HTTPS_MODE_DISABLED
                                else -> BrowserPreferences.HTTPS_MODE_WHEN_POSSIBLE
                            }
                            prefs.httpsUpgradeMode = httpsUpgradeMode
                            showHttpsModePicker = false
                        },
                        onDismiss = { showHttpsModePicker = false }
                    )
                }

                if (showCookieModePicker) {
                    SingleChoiceBottomSheet(
                        title = "Cookie Blocking Mode",
                        options = listOf("Block 3rd-Party Cookies (Recommended)", "Allow All Cookies", "Block All Cookies"),
                        selectedIndex = when (cookieMode) {
                            BrowserPreferences.COOKIE_BLOCK_NONE -> 1
                            BrowserPreferences.COOKIE_BLOCK_ALL -> 2
                            else -> 0
                        },
                        onSelect = { idx ->
                            cookieMode = when (idx) {
                                1 -> BrowserPreferences.COOKIE_BLOCK_NONE
                                2 -> BrowserPreferences.COOKIE_BLOCK_ALL
                                else -> BrowserPreferences.COOKIE_BLOCK_THIRD_PARTY
                            }
                            prefs.cookieBlockingMode = cookieMode
                            showCookieModePicker = false
                        },
                        onDismiss = { showCookieModePicker = false }
                    )
                }

                if (showDnsProviderPicker) {
                    DnsProviderBottomSheet(
                        currentProviderUrl = secureDnsProvider,
                        currentCustomUrl = prefs.customDnsUrl,
                        onSelectProvider = { selectedUrl, isCustom ->
                            secureDnsProvider = selectedUrl
                            prefs.secureDnsProvider = selectedUrl
                            if (isCustom) {
                                prefs.customDnsUrl = selectedUrl
                            }
                            showDnsProviderPicker = false
                        },
                        onDismiss = { showDnsProviderPicker = false }
                    )
                }

                if (showClearOnExitPicker) {
                    ClearOnExitSheet(
                        prefs = prefs,
                        onDismiss = { showClearOnExitPicker = false },
                        onUpdated = {
                            isClearOnExitEnabled = prefs.isClearOnExitEnabled
                            clearOnExitSummary = prefs.getClearOnExitSummary(this@ShieldsActivity)
                        }
                    )
                }

                if (showManageBlockedElementsSheet) {
                    ManageBlockedElementsSheet(
                        prefs = prefs,
                        onDismiss = { showManageBlockedElementsSheet = false },
                        onUpdated = { updateBlockedElementsSummary() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadState()
    }

    private fun updateBlockedElementsSummary() {
        val map = prefs.getAllDomainsWithCustomBlockedRules()
        val siteCount = map.size
        val ruleCount = map.values.sumOf { it.size }
        blockedElementsSummary = if (siteCount == 0) {
            "No websites with blocked elements"
        } else {
            "$siteCount ${if (siteCount == 1) "website" else "websites"} ($ruleCount ${if (ruleCount == 1) "element" else "elements"} blocked) • Tap to restore"
        }
    }

    private fun loadState() {
        isAdBlockEnabled = prefs.isAdBlockEnabled

        isAmpRedirectEnabled = prefs.isAutoRedirectAmpEnabled
        isTrackingRedirectEnabled = prefs.isAutoRedirectTrackingUrlsEnabled
        httpsUpgradeMode = prefs.httpsUpgradeMode

        isGlobalScriptBlocked = prefs.isGlobalScriptBlockingEnabled
        cookieMode = prefs.cookieBlockingMode
        isClearOnExitEnabled = prefs.isClearOnExitEnabled
        clearOnExitSummary = prefs.getClearOnExitSummary(this)

        isFingerprintEnabled = prefs.isFingerprintProtectionEnabled
        isFingerprintLangEnabled = prefs.isFingerprintLangEnabled
        isWebRtcProtected = prefs.isWebRtcProtectedGlobal

        isBlockElementEnabled = prefs.isBlockElementEnabled
        isElementInPrivateEnabled = prefs.isElementBlockingInPrivateEnabled
        updateBlockedElementsSummary()

        isSocialMediaBlockingEnabled = prefs.isSocialMediaBlockingEnabled
        isGoogleSignInAllowed = !prefs.isGoogleSignInBlockedGlobal
        isFacebookAllowed = prefs.allowFacebookLogins
        isTwitterAllowed = prefs.allowTwitterEmbeds
        isLinkedInAllowed = prefs.allowLinkedInEmbeds

        isSecureDnsEnabled = prefs.isSecureDnsEnabled
        secureDnsProvider = prefs.secureDnsProvider

        isBlockAppBannerEnabled = prefs.isBlockAppBannerEnabled
        isDoNotTrackEnabled = prefs.isDoNotTrackEnabled
    }

    private fun getDnsDisplayName(url: String): String {
        return when (url) {
            DnsProviderPickerBottomSheet.DNS_CLOUDFLARE -> "Cloudflare (1.1.1.1)"
            DnsProviderPickerBottomSheet.DNS_GOOGLE -> "Google (8.8.8.8)"
            DnsProviderPickerBottomSheet.DNS_NEXTDNS -> "NextDNS"
            DnsProviderPickerBottomSheet.DNS_QUAD9 -> "Quad9 (9.9.9.9)"
            DnsProviderPickerBottomSheet.DNS_ADGUARD -> "AdGuard DNS"
            else -> if (url.isNotBlank() && url.startsWith("https://", ignoreCase = true)) {
                url
            } else {
                "Custom URL"
            }
        }
    }
}

@Composable
fun ShieldsScreen(
    onBackClick: () -> Unit,
    isAdBlock: Boolean,
    onAdBlockChange: (Boolean) -> Unit,
    onFilterListsClick: () -> Unit,
    isAmpRedirect: Boolean,
    onAmpRedirectChange: (Boolean) -> Unit,
    isTrackingRedirect: Boolean,
    onTrackingRedirectChange: (Boolean) -> Unit,
    httpsModeText: String,
    onHttpsModeClick: () -> Unit,
    isGlobalScriptBlocked: Boolean,
    onGlobalScriptBlockedChange: (Boolean) -> Unit,
    onManageUserScriptsClick: () -> Unit,
    cookieModeText: String,
    onCookieModeClick: () -> Unit,
    clearOnExitSummary: String,
    onClearOnExitClick: () -> Unit,
    isFingerprint: Boolean,
    onFingerprintChange: (Boolean) -> Unit,
    isFingerprintLang: Boolean,
    onFingerprintLangChange: (Boolean) -> Unit,
    isWebRtc: Boolean,
    onWebRtcChange: (Boolean) -> Unit,
    isBlockElement: Boolean,
    onBlockElementChange: (Boolean) -> Unit,
    isElementInPrivate: Boolean,
    onElementInPrivateChange: (Boolean) -> Unit,
    blockedElementsSummary: String,
    onManageBlockedElementsClick: () -> Unit,
    isSocialMedia: Boolean,
    onSocialMediaChange: (Boolean) -> Unit,
    isGoogleSignIn: Boolean,
    onGoogleSignInChange: (Boolean) -> Unit,
    isFacebook: Boolean,
    onFacebookChange: (Boolean) -> Unit,
    isTwitter: Boolean,
    onTwitterChange: (Boolean) -> Unit,
    isLinkedIn: Boolean,
    onLinkedInChange: (Boolean) -> Unit,
    isSecureDns: Boolean,
    onSecureDnsChange: (Boolean) -> Unit,
    dnsProviderText: String,
    onDnsProviderClick: () -> Unit,
    isBlockAppBanner: Boolean,
    onBlockAppBannerChange: (Boolean) -> Unit,
    isDoNotTrack: Boolean,
    onDoNotTrackChange: (Boolean) -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Shields & Privacy",
                onBackClick = onBackClick
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp)
        ) {
            CategoryHeader("Trackers & Ads")
            CardContainer {
                SwitchPreference(
                    title = "Onyx Shields (AdBlock)",
                    subtitle = "Block ads, malicious trackers, and popups natively via Brave adblock-rust",
                    icon = Icons.Default.Shield,
                    checked = isAdBlock,
                    onCheckedChange = onAdBlockChange
                )

                PreferenceItem(
                    title = "Content Filter Lists & Rules",
                    subtitle = "Manage EasyList, EasyPrivacy, uBlock filters, and custom rules",
                    icon = Icons.Default.FilterList,
                    onClick = onFilterListsClick
                )
            }

            CategoryHeader("Connections & HTTPS")
            CardContainer {
                SwitchPreference(
                    title = "Auto-Redirect AMP Pages",
                    subtitle = "Bypass Google AMP cache to load canonical websites directly",
                    checked = isAmpRedirect,
                    onCheckedChange = onAmpRedirectChange
                )

                SwitchPreference(
                    title = "De-AMP Tracking Links",
                    subtitle = "Strip click-tracking query parameters from outgoing URLs",
                    checked = isTrackingRedirect,
                    onCheckedChange = onTrackingRedirectChange
                )

                PreferenceItem(
                    title = "HTTPS Upgrade Mode",
                    subtitle = httpsModeText,
                    icon = Icons.Default.Https,
                    onClick = onHttpsModeClick
                )
            }

            CategoryHeader("Scripts & Extensions")
            CardContainer {
                SwitchPreference(
                    title = "Block All JavaScript",
                    subtitle = "Disable JavaScript execution globally (can break interactive web pages)",
                    checked = isGlobalScriptBlocked,
                    onCheckedChange = onGlobalScriptBlockedChange
                )

                PreferenceItem(
                    title = "User Scripts Manager",
                    subtitle = "Inject custom Tampermonkey & Violentmonkey scripts into web pages",
                    icon = Icons.Default.Code,
                    onClick = onManageUserScriptsClick
                )
            }

            CategoryHeader("Cookies & Data Retention")
            CardContainer {
                PreferenceItem(
                    title = "Cookie Blocking Policy",
                    subtitle = cookieModeText,
                    onClick = onCookieModeClick
                )

                PreferenceItem(
                    title = "Clear Data on Exit",
                    subtitle = clearOnExitSummary,
                    onClick = onClearOnExitClick
                )
            }

            CategoryHeader("Fingerprinting & Device Protection")
            CardContainer {
                SwitchPreference(
                    title = "Fingerprinting Protection",
                    subtitle = "Randomize canvas, audio context, and hardware sensor signatures",
                    icon = Icons.Default.Security,
                    checked = isFingerprint,
                    onCheckedChange = onFingerprintChange
                )

                SwitchPreference(
                    title = "Language Fingerprint Protection",
                    subtitle = "Standardize navigator.languages to mask regional device identity",
                    checked = isFingerprintLang,
                    onCheckedChange = onFingerprintLangChange
                )

                SwitchPreference(
                    title = "WebRTC IP Leak Protection",
                    subtitle = "Prevent local and public IP discovery during WebRTC calls",
                    checked = isWebRtc,
                    onCheckedChange = onWebRtcChange
                )
            }

            CategoryHeader("Element Blocking")
            CardContainer {
                SwitchPreference(
                    title = "Cosmetic Element Blocking",
                    subtitle = "Collapse ad placeholders and empty banners cleanly",
                    checked = isBlockElement,
                    onCheckedChange = onBlockElementChange
                )

                SwitchPreference(
                    title = "Element Blocking in Private Tabs",
                    subtitle = "Apply cosmetic hides in incognito browsing sessions",
                    checked = isElementInPrivate,
                    onCheckedChange = onElementInPrivateChange
                )

                PreferenceItem(
                    title = "Manage Blocked Elements",
                    subtitle = blockedElementsSummary,
                    onClick = onManageBlockedElementsClick
                )
            }

            CategoryHeader("Social Media Trackers & Logins")
            CardContainer {
                SwitchPreference(
                    title = "Block Social Trackers",
                    subtitle = "Block third-party social buttons and cross-site telemetry",
                    checked = isSocialMedia,
                    onCheckedChange = onSocialMediaChange
                )

                if (isSocialMedia) {
                    SwitchPreference(
                        title = "Allow Google Sign-In",
                        subtitle = "Permit Google authentication dialogs on external sites",
                        checked = isGoogleSignIn,
                        onCheckedChange = onGoogleSignInChange
                    )

                    SwitchPreference(
                        title = "Allow Facebook Logins",
                        subtitle = "Permit Facebook login SDK dialogs",
                        checked = isFacebook,
                        onCheckedChange = onFacebookChange
                    )

                    SwitchPreference(
                        title = "Allow Twitter / X Embeds",
                        subtitle = "Display embedded tweets in articles",
                        checked = isTwitter,
                        onCheckedChange = onTwitterChange
                    )

                    SwitchPreference(
                        title = "Allow LinkedIn Embeds",
                        subtitle = "Display embedded LinkedIn posts",
                        checked = isLinkedIn,
                        onCheckedChange = onLinkedInChange
                    )
                }
            }

            CategoryHeader("Secure DNS (DNS over HTTPS)")
            CardContainer {
                SwitchPreference(
                    title = "Enable Secure DNS",
                    subtitle = "Encrypt domain queries with DNS over HTTPS (DoH)",
                    icon = Icons.Default.Dns,
                    checked = isSecureDns,
                    onCheckedChange = onSecureDnsChange
                )

                if (isSecureDns) {
                    PreferenceItem(
                        title = "DNS Provider",
                        subtitle = dnsProviderText,
                        onClick = onDnsProviderClick
                    )
                }
            }

            CategoryHeader("Privacy Flags")
            CardContainer {
                SwitchPreference(
                    title = "Block 'Open in App' Banners",
                    subtitle = "Defuse annoying prompts asking to open native apps like Reddit or Quora",
                    checked = isBlockAppBanner,
                    onCheckedChange = onBlockAppBannerChange
                )

                SwitchPreference(
                    title = "Send 'Do Not Track' Request",
                    subtitle = "Include DNT: 1 header with outgoing HTTP requests",
                    checked = isDoNotTrack,
                    onCheckedChange = onDoNotTrackChange
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleChoiceBottomSheet(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            options.forEachIndexed { index, option ->
                RadioPreference(
                    title = option,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp)
    )
}
