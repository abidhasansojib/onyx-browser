package com.onyx.browser.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.AppTopBar
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.components.PreferenceItem
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.update.AppUpdateManager
import com.onyx.browser.web.UserAgentManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var preferences: BrowserPreferences

    private var currentSearchEngineName by mutableStateOf("")
    private var currentThemeName by mutableStateOf("")
    private var userAgentText by mutableStateOf("")
    private var userScriptsStatus by mutableStateOf("")
    private var aboutSubtitle by mutableStateOf("")
    private var hasUpdateBadge by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        preferences = BrowserPreferences.getInstance(this)
        loadState()

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = false) {
                SettingsScreen(
                    onBackClick = { finish() },
                    searchEngineName = currentSearchEngineName,
                    onSearchEngineClick = { SettingsNavigator.navigate(this, SettingsDestination.SEARCH_ENGINE) },
                    themeName = currentThemeName,
                    onAppearanceClick = { SettingsNavigator.navigate(this, SettingsDestination.APPEARANCE) },
                    onAutofillClick = { SettingsNavigator.navigate(this, SettingsDestination.AUTOFILL) },
                    onVideoOptionsClick = { SettingsNavigator.navigate(this, SettingsDestination.VIDEO_PLAYBACK) },
                    onDownloadSettingsClick = { SettingsNavigator.navigate(this, SettingsDestination.DOWNLOADS) },
                    onAccessibilityClick = { SettingsNavigator.navigate(this, SettingsDestination.ACCESSIBILITY) },
                    onShieldsClick = { SettingsNavigator.navigate(this, SettingsDestination.SHIELDS) },
                    userAgentText = userAgentText,
                    onUserAgentClick = {
                        UserAgentPickerBottomSheet { selectedKey ->
                            userAgentText = UserAgentManager.getDisplayName(selectedKey, preferences)
                        }.show(supportFragmentManager, UserAgentPickerBottomSheet.TAG)
                    },
                    onSitePermissionsClick = { SettingsNavigator.navigate(this, SettingsDestination.SITE_SETTINGS) },
                    userScriptsStatus = userScriptsStatus,
                    onUserScriptsClick = { SettingsNavigator.navigate(this, SettingsDestination.USER_SCRIPTS) },
                    aboutSubtitle = aboutSubtitle,
                    hasUpdateBadge = hasUpdateBadge,
                    onAboutClick = { SettingsNavigator.navigate(this, SettingsDestination.ABOUT) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadState()
    }

    private fun loadState() {
        currentSearchEngineName = preferences.searchEngine.displayName
        currentThemeName = when (preferences.themeMode) {
            BrowserPreferences.THEME_DARK -> "Dark"
            BrowserPreferences.THEME_LIGHT -> "Light"
            else -> "System Default"
        }
        userAgentText = UserAgentManager.getDisplayName(preferences.userAgentSpoofTemplate, preferences)
        userScriptsStatus = if (preferences.isGlobalScriptBlockingEnabled) {
            "Disabled (JavaScript blocked)"
        } else {
            "Manage custom userscripts & extensions"
        }

        val cachedUpdate = AppUpdateManager.getCachedUpdate(this)
        if (cachedUpdate != null) {
            aboutSubtitle = "Update available: ${cachedUpdate.tagName}"
            hasUpdateBadge = true
        } else {
            aboutSubtitle = "Version, release notes & update checker"
            hasUpdateBadge = false
        }
    }
}

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    searchEngineName: String,
    onSearchEngineClick: () -> Unit,
    themeName: String,
    onAppearanceClick: () -> Unit,
    onAutofillClick: () -> Unit,
    onVideoOptionsClick: () -> Unit,
    onDownloadSettingsClick: () -> Unit,
    onAccessibilityClick: () -> Unit,
    onShieldsClick: () -> Unit,
    userAgentText: String,
    onUserAgentClick: () -> Unit,
    onSitePermissionsClick: () -> Unit,
    userScriptsStatus: String,
    onUserScriptsClick: () -> Unit,
    aboutSubtitle: String,
    hasUpdateBadge: Boolean,
    onAboutClick: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Settings",
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
            CategoryHeader("General")
            CardContainer {
                PreferenceItem(
                    title = "Search Engine",
                    subtitle = searchEngineName,
                    icon = Icons.Default.Search,
                    onClick = onSearchEngineClick
                )

                PreferenceItem(
                    title = "Appearance & Theme",
                    subtitle = themeName,
                    icon = Icons.Default.Palette,
                    onClick = onAppearanceClick
                )

                PreferenceItem(
                    title = "Autofill & Passwords",
                    subtitle = "System password manager and WebAuthn passkeys",
                    icon = Icons.Default.Lock,
                    onClick = onAutofillClick
                )

                PreferenceItem(
                    title = "Video & Media Playback",
                    subtitle = "Background playback and Picture-in-Picture",
                    icon = Icons.Default.PlayCircle,
                    onClick = onVideoOptionsClick
                )

                PreferenceItem(
                    title = "Downloads Settings",
                    subtitle = "Internal downloader and download behavior",
                    icon = Icons.Default.Download,
                    onClick = onDownloadSettingsClick
                )

                PreferenceItem(
                    title = "Accessibility & Gestures",
                    subtitle = "Pull to refresh, auto-hide toolbar, and biometric lock",
                    icon = Icons.Default.Accessibility,
                    onClick = onAccessibilityClick
                )
            }

            CategoryHeader("Privacy & Security")
            CardContainer {
                PreferenceItem(
                    title = "Shields & Privacy",
                    subtitle = "Adblock-rust, tracker blocking, HTTPS, and DoH",
                    icon = Icons.Default.Shield,
                    onClick = onShieldsClick
                )

                PreferenceItem(
                    title = "User-Agent Client",
                    subtitle = userAgentText,
                    icon = Icons.Default.Devices,
                    onClick = onUserAgentClick
                )

                PreferenceItem(
                    title = "Site Permissions",
                    subtitle = "Camera, microphone, location, and data per site",
                    icon = Icons.Default.Security,
                    onClick = onSitePermissionsClick
                )

                PreferenceItem(
                    title = "User Scripts",
                    subtitle = userScriptsStatus,
                    icon = Icons.Default.Code,
                    onClick = onUserScriptsClick
                )
            }

            CategoryHeader("About")
            CardContainer {
                PreferenceItem(
                    title = "About Onyx Browser",
                    subtitle = aboutSubtitle,
                    icon = Icons.Default.Info,
                    trailingContent = if (hasUpdateBadge) {
                        {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Update",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else null,
                    onClick = onAboutClick
                )
            }
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
