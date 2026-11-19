package com.onyx.browser.ui.menu

import android.content.Intent
import android.net.http.SslCertificate
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Https
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.SitePermissionState
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.AppAlertDialog
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.components.PreferenceItem
import com.onyx.browser.ui.components.SwitchPreference
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.web.SiteDataManager

class SiteShieldBottomSheet(
    private val currentUrl: String,
    private val pageBlockedCount: Int = -1,
    private val isIncognito: Boolean = false,
    private val sslCertificate: SslCertificate? = null,
    private val onShieldSettingsChanged: () -> Unit
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "SiteShieldBottomSheet"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val prefs = BrowserPreferences.getInstance(requireContext())
                val domain = prefs.cleanDomain(currentUrl).ifBlank { "This Page" }

                val isDark = when (prefs.themeMode) {
                    BrowserPreferences.THEME_DARK -> true
                    BrowserPreferences.THEME_LIGHT -> false
                    else -> isSystemInDarkTheme()
                }
                val isAmoled = prefs.themeMode == BrowserPreferences.THEME_DARK

                var isShieldsActive by remember { mutableStateOf(!prefs.isDomainWhitelisted(domain)) }
                var isFingerprint by remember { mutableStateOf(prefs.isFingerprintProtectionEnabled) }
                var isScriptBlocked by remember { mutableStateOf(prefs.isScriptBlockingEnabledForDomain(domain)) }
                var isCosmeticBlocked by remember { mutableStateOf(prefs.isBlockElementEnabled) }
                var isDesktopMode by remember { mutableStateOf(prefs.getEffectiveDesktopMode(domain)) }
                var showClearDataDialog by remember { mutableStateOf(false) }

                val userscriptManager = remember { com.onyx.browser.userscript.UserScriptManager.getInstance(requireContext()) }
                val activeScriptCount = remember(currentUrl) {
                    userscriptManager.scripts.count {
                        it.script.enabled && com.onyx.browser.userscript.UserScriptUrlMatcher.matches(currentUrl, it.metadata.matches, it.metadata.includes) &&
                                !com.onyx.browser.userscript.UserScriptUrlMatcher.isExcluded(currentUrl, it.metadata.excludes)
                    }
                }

                OnyxTheme(darkTheme = isDark, isAmoled = isAmoled) {
                    SiteShieldSheetLayout(
                        domain = domain,
                        isHttps = currentUrl.startsWith("https://", ignoreCase = true),
                        blockedCount = pageBlockedCount.coerceAtLeast(0),
                        activeScriptCount = activeScriptCount,
                        isShieldsActive = isShieldsActive,
                        onShieldsToggle = { enabled ->
                            isShieldsActive = enabled
                            prefs.setDomainWhitelisted(domain, !enabled)
                            onShieldSettingsChanged()
                        },
                        isFingerprint = isFingerprint,
                        onFingerprintToggle = {
                            isFingerprint = it
                            prefs.isFingerprintProtectionEnabled = it
                            onShieldSettingsChanged()
                        },
                        isScriptBlocked = isScriptBlocked,
                        onScriptBlockedToggle = {
                            isScriptBlocked = it
                            prefs.setScriptBlockingForDomain(domain, it)
                            onShieldSettingsChanged()
                        },
                        isCosmeticBlocked = isCosmeticBlocked,
                        onCosmeticBlockedToggle = {
                            isCosmeticBlocked = it
                            prefs.isBlockElementEnabled = it
                            onShieldSettingsChanged()
                        },
                        isDesktopMode = isDesktopMode,
                        onDesktopModeToggle = { enabled ->
                            isDesktopMode = enabled
                            prefs.setSiteDesktopModeState(domain, if (enabled) SitePermissionState.ALLOWED else SitePermissionState.BLOCKED)
                            onShieldSettingsChanged()
                        },
                        onClearSiteDataClick = {
                            showClearDataDialog = true
                        },
                        onOpenUserScripts = {
                            startActivity(Intent(requireContext(), com.onyx.browser.ui.settings.userscript.UserScriptsActivity::class.java))
                            dismiss()
                        },
                        onOpenSettings = {
                            startActivity(Intent(requireContext(), com.onyx.browser.ui.settings.site.SiteSettingsActivity::class.java))
                            dismiss()
                        },
                        onDoneClick = { dismiss() }
                    )

                    if (showClearDataDialog) {
                        AppAlertDialog(
                            title = "Clear data for $domain?",
                            text = { Text("Cookies, cache, and local storage for this domain will be removed.") },
                            confirmButtonText = "Clear",
                            isDestructive = true,
                            onConfirm = {
                                SiteDataManager.clearSiteData(requireContext().applicationContext, domain)
                                Toast.makeText(requireContext(), "Cleared data for $domain", Toast.LENGTH_SHORT).show()
                                showClearDataDialog = false
                            },
                            onDismissRequest = { showClearDataDialog = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SiteShieldSheetLayout(
    domain: String,
    isHttps: Boolean,
    blockedCount: Int,
    activeScriptCount: Int = 0,
    isShieldsActive: Boolean,
    onShieldsToggle: (Boolean) -> Unit,
    isFingerprint: Boolean,
    onFingerprintToggle: (Boolean) -> Unit,
    isScriptBlocked: Boolean,
    onScriptBlockedToggle: (Boolean) -> Unit,
    isCosmeticBlocked: Boolean,
    onCosmeticBlockedToggle: (Boolean) -> Unit,
    isDesktopMode: Boolean,
    onDesktopModeToggle: (Boolean) -> Unit,
    onClearSiteDataClick: () -> Unit,
    onOpenUserScripts: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onDoneClick: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 16.dp)
            .verticalScroll(scrollState)
    ) {
        // Domain Header Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isShieldsActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = if (isShieldsActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = domain,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isHttps) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (isHttps) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isHttps) "Connection is secure" else "Connection is not secure",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isHttps) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }

            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Master Shields Card with Counter
        CardContainer {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isShieldsActive) "Onyx Shields UP" else "Onyx Shields DOWN",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = if (isShieldsActive) "$blockedCount trackers & ads blocked" else "Protection paused for this site",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Switch(
                    checked = isShieldsActive,
                    onCheckedChange = onShieldsToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }

        // Advanced Protections Card
        Text(
            text = "Advanced Controls",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp)
        )

        CardContainer {
            SwitchPreference(
                title = "Fingerprint Protection",
                subtitle = "Randomize canvas, WebGL, and audio hardware signatures",
                checked = isFingerprint,
                onCheckedChange = onFingerprintToggle,
                enabled = isShieldsActive
            )

            SwitchPreference(
                title = "Block JavaScript",
                subtitle = "Prevent scripts from running on $domain",
                checked = isScriptBlocked,
                onCheckedChange = onScriptBlockedToggle
            )

            SwitchPreference(
                title = "Cosmetic Element Hiding",
                subtitle = "Collapse empty ad slots and banners",
                checked = isCosmeticBlocked,
                onCheckedChange = onCosmeticBlockedToggle,
                enabled = isShieldsActive
            )

            SwitchPreference(
                title = "Desktop Site",
                subtitle = "Request the desktop version of this website",
                iconResId = R.drawable.ic_desktop,
                checked = isDesktopMode,
                onCheckedChange = onDesktopModeToggle
            )

            PreferenceItem(
                title = "User Scripts",
                subtitle = if (activeScriptCount > 0) "$activeScriptCount active on this site" else "Manage userscripts",
                icon = Icons.Default.Code,
                onClick = onOpenUserScripts
            )

            PreferenceItem(
                title = "Clear Cookies & Storage",
                subtitle = "Purge saved login sessions and cached data for this site",
                icon = Icons.Default.DeleteSweep,
                onClick = onClearSiteDataClick
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Button(
                onClick = onDoneClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done")
            }
        }
    }
}
