package com.onyx.browser.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.common.BiometricAuthHelper
import com.onyx.browser.ui.components.OnyxCardContainer
import com.onyx.browser.ui.components.OnyxPreferenceItem
import com.onyx.browser.ui.components.OnyxSwitchPreference
import com.onyx.browser.ui.components.OnyxTopAppBar
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.ui.widget.SearchWidgetManager
import com.onyx.browser.ui.widget.SearchWidgetProvider

class AccessibilitySettingsActivity : FragmentActivity() {

    private lateinit var preferences: BrowserPreferences

    private var isAddToHomeScreenEnabled by mutableStateOf(false)
    private var isScrollToTopEnabled by mutableStateOf(false)
    private var isAutoHideToolbarEnabled by mutableStateOf(false)
    private var isPullToRefreshEnabled by mutableStateOf(false)
    private var isForceDarkModeEnabled by mutableStateOf(false)
    private var isBiometricIncognitoEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        preferences = BrowserPreferences.getInstance(this)
        loadPreferencesState()

        val isAmoled = preferences.themeMode == BrowserPreferences.THEME_DARK

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = isAmoled) {
                AccessibilitySettingsScreen(
                    onBackClick = { finish() },
                    isAddToHomeScreen = isAddToHomeScreenEnabled,
                    onAddToHomeScreenChange = { enabled ->
                        isAddToHomeScreenEnabled = enabled
                        preferences.isAddToHomeScreenEnabled = enabled
                    },
                    isScrollToTop = isScrollToTopEnabled,
                    onScrollToTopChange = { enabled ->
                        isScrollToTopEnabled = enabled
                        preferences.isScrollToTopEnabled = enabled
                    },
                    isAutoHideToolbar = isAutoHideToolbarEnabled,
                    onAutoHideToolbarChange = { enabled ->
                        isAutoHideToolbarEnabled = enabled
                        preferences.isAutoHideToolbarEnabled = enabled
                    },
                    isPullToRefresh = isPullToRefreshEnabled,
                    onPullToRefreshChange = { enabled ->
                        isPullToRefreshEnabled = enabled
                        preferences.isPullToRefreshEnabled = enabled
                    },
                    isForceDarkMode = isForceDarkModeEnabled,
                    onForceDarkModeChange = { enabled ->
                        isForceDarkModeEnabled = enabled
                        preferences.isForceDarkModeEnabled = enabled
                    },
                    isBiometric = isBiometricIncognitoEnabled,
                    onBiometricRowClick = { handleBiometricToggle() },
                    onPinWidgetClick = { SearchWidgetManager.requestPinSearchWidget(this) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadPreferencesState()
        SearchWidgetProvider.updateAllWidgets(this)
    }

    private fun loadPreferencesState() {
        isAddToHomeScreenEnabled = preferences.isAddToHomeScreenEnabled
        isScrollToTopEnabled = preferences.isScrollToTopEnabled
        isAutoHideToolbarEnabled = preferences.isAutoHideToolbarEnabled
        isPullToRefreshEnabled = preferences.isPullToRefreshEnabled
        isForceDarkModeEnabled = preferences.isForceDarkModeEnabled
        isBiometricIncognitoEnabled = preferences.isBiometricIncognitoEnabled
    }

    private fun handleBiometricToggle() {
        val currentlyEnabled = preferences.isBiometricIncognitoEnabled
        val executor = ContextCompat.getMainExecutor(this)

        if (!currentlyEnabled) {
            if (BiometricAuthHelper.canAuthenticate(this)) {
                val prompt = androidx.biometric.BiometricPrompt(
                    this,
                    executor,
                    object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            preferences.isBiometricIncognitoEnabled = true
                            isBiometricIncognitoEnabled = true
                            Toast.makeText(this@AccessibilitySettingsActivity, "Biometric Incognito Protection enabled", Toast.LENGTH_SHORT).show()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            isBiometricIncognitoEnabled = preferences.isBiometricIncognitoEnabled
                        }
                    }
                )
                val promptInfo = BiometricAuthHelper.createPromptInfo(
                    title = "Biometric Incognito Setup",
                    subtitle = "Confirm fingerprint or screen lock to enable"
                )
                prompt.authenticate(promptInfo)
            } else {
                Toast.makeText(this, "Please set up a screen lock or fingerprint in Android Settings first", Toast.LENGTH_LONG).show()
            }
        } else {
            val prompt = androidx.biometric.BiometricPrompt(
                this,
                executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        preferences.isBiometricIncognitoEnabled = false
                        isBiometricIncognitoEnabled = false
                        Toast.makeText(this@AccessibilitySettingsActivity, "Biometric Incognito Protection disabled", Toast.LENGTH_SHORT).show()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        isBiometricIncognitoEnabled = preferences.isBiometricIncognitoEnabled
                    }
                }
            )
            val promptInfo = BiometricAuthHelper.createPromptInfo(
                title = "Confirm Identity",
                subtitle = "Confirm identity to disable Biometric Incognito Protection"
            )
            prompt.authenticate(promptInfo)
        }
    }
}

@Composable
fun AccessibilitySettingsScreen(
    onBackClick: () -> Unit,
    isAddToHomeScreen: Boolean,
    onAddToHomeScreenChange: (Boolean) -> Unit,
    isScrollToTop: Boolean,
    onScrollToTopChange: (Boolean) -> Unit,
    isAutoHideToolbar: Boolean,
    onAutoHideToolbarChange: (Boolean) -> Unit,
    isPullToRefresh: Boolean,
    onPullToRefreshChange: (Boolean) -> Unit,
    isForceDarkMode: Boolean,
    onForceDarkModeChange: (Boolean) -> Unit,
    isBiometric: Boolean,
    onBiometricRowClick: () -> Unit,
    onPinWidgetClick: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            OnyxTopAppBar(
                title = "Accessibility & Gestures",
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
            CategoryHeader("Home & Widgets")
            OnyxCardContainer {
                OnyxPreferenceItem(
                    title = "Pin Search Widget",
                    subtitle = "Add Onyx quick search bar to your Android home screen",
                    icon = Icons.Default.Widgets,
                    onClick = onPinWidgetClick
                )

                OnyxSwitchPreference(
                    title = "Add to Home Screen in Menu",
                    subtitle = "Show shortcut option in browser menu for quick access",
                    checked = isAddToHomeScreen,
                    onCheckedChange = onAddToHomeScreenChange
                )
            }

            CategoryHeader("Browsing Controls & Gestures")
            OnyxCardContainer {
                OnyxSwitchPreference(
                    title = "Scroll to Top Button",
                    subtitle = "Quickly jump back to page top with floating indicator",
                    checked = isScrollToTop,
                    onCheckedChange = onScrollToTopChange
                )

                OnyxSwitchPreference(
                    title = "Auto-Hide Address Bar",
                    subtitle = "Collapse address bar when scrolling down web pages",
                    checked = isAutoHideToolbar,
                    onCheckedChange = onAutoHideToolbarChange
                )

                OnyxSwitchPreference(
                    title = "Pull to Refresh",
                    subtitle = "Swipe down from top of webpage to reload",
                    checked = isPullToRefresh,
                    onCheckedChange = onPullToRefreshChange
                )
            }

            CategoryHeader("Visual & Display")
            OnyxCardContainer {
                OnyxSwitchPreference(
                    title = "Force Dark Mode for Web Contents",
                    subtitle = "Invert background and colors on websites without dark theme",
                    checked = isForceDarkMode,
                    onCheckedChange = onForceDarkModeChange
                )
            }

            CategoryHeader("Privacy & Lock")
            OnyxCardContainer {
                OnyxSwitchPreference(
                    title = "Biometric Incognito Protection",
                    subtitle = "Require fingerprint or screen lock to open private tabs",
                    checked = isBiometric,
                    onCheckedChange = { onBiometricRowClick() }
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
