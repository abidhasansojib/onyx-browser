package com.onyx.browser.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.onyx.browser.autofill.ActivePasswordManagerInfo
import com.onyx.browser.autofill.AutofillHelper
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.OnyxCardContainer
import com.onyx.browser.ui.components.OnyxPreferenceItem
import com.onyx.browser.ui.components.OnyxSwitchPreference
import com.onyx.browser.ui.components.OnyxTopAppBar
import com.onyx.browser.ui.theme.OnyxTheme

class AutofillSettingsActivity : ComponentActivity() {

    private lateinit var preferences: BrowserPreferences
    private var activeManager by mutableStateOf<ActivePasswordManagerInfo?>(null)
    private var isSupported by mutableStateOf(true)

    private var isAutofillEnabled by mutableStateOf(false)
    private var isSavePasswordsPromptEnabled by mutableStateOf(false)
    private var isPasskeysEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        preferences = BrowserPreferences.getInstance(this)
        loadState()

        val isAmoled = preferences.themeMode == BrowserPreferences.THEME_DARK

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = isAmoled) {
                AutofillSettingsScreen(
                    onBackClick = { finish() },
                    isSupported = isSupported,
                    manager = activeManager,
                    onChangeAutofillService = {
                        AutofillHelper.openAutofillServiceSettings(this, activeManager?.packageName)
                    },
                    onConfigurePasskeys = {
                        AutofillHelper.openPasskeyProviderSettings(this, activeManager)
                    },
                    onOpenPasswordManager = {
                        AutofillHelper.openActivePasswordManager(this, activeManager)
                    },
                    isAutofillForms = isAutofillEnabled,
                    onAutofillFormsChange = { enabled ->
                        isAutofillEnabled = enabled
                        preferences.isAutofillEnabled = enabled
                    },
                    isSavePasswords = isSavePasswordsPromptEnabled,
                    onSavePasswordsChange = { enabled ->
                        isSavePasswordsPromptEnabled = enabled
                        preferences.isSavePasswordsPromptEnabled = enabled
                    },
                    isPasskeys = isPasskeysEnabled,
                    onPasskeysChange = { enabled ->
                        isPasskeysEnabled = enabled
                        preferences.isPasskeysEnabled = enabled
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadState()
    }

    private fun loadState() {
        isSupported = AutofillHelper.isAutofillSupported(this)
        activeManager = AutofillHelper.getActivePasswordManager(this)
        isAutofillEnabled = preferences.isAutofillEnabled
        isSavePasswordsPromptEnabled = preferences.isSavePasswordsPromptEnabled
        isPasskeysEnabled = preferences.isPasskeysEnabled
    }
}

@Composable
fun AutofillSettingsScreen(
    onBackClick: () -> Unit,
    isSupported: Boolean,
    manager: ActivePasswordManagerInfo?,
    onChangeAutofillService: () -> Unit,
    onConfigurePasskeys: () -> Unit,
    onOpenPasswordManager: () -> Unit,
    isAutofillForms: Boolean,
    onAutofillFormsChange: (Boolean) -> Unit,
    isSavePasswords: Boolean,
    onSavePasswordsChange: (Boolean) -> Unit,
    isPasskeys: Boolean,
    onPasskeysChange: (Boolean) -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            OnyxTopAppBar(
                title = "Autofill & Passwords",
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
            CategoryHeader("Password Manager & Credential Providers")
            OnyxCardContainer {
                val title = if (manager != null) "${manager.appName} Active" else "No Autofill Service Selected"
                val subtitle = if (manager != null) {
                    "${manager.appName} is configured as your active Android password manager."
                } else {
                    "Choose Bitwarden, Proton Pass, 1Password, KeePassDX, or your preferred manager."
                }

                OnyxPreferenceItem(
                    title = title,
                    subtitle = subtitle,
                    icon = Icons.Default.Lock,
                    onClick = onOpenPasswordManager
                )

                OnyxPreferenceItem(
                    title = "Change Autofill Service",
                    subtitle = "Configure Android system autofill provider in settings",
                    icon = Icons.Default.Settings,
                    onClick = onChangeAutofillService
                )

                OnyxPreferenceItem(
                    title = "Configure Passkey Providers",
                    subtitle = "Manage FIDO2 passkeys & Credential Manager settings",
                    icon = Icons.Default.Key,
                    onClick = onConfigurePasskeys
                )
            }

            CategoryHeader("Web Autofill & Credentials")
            OnyxCardContainer {
                OnyxSwitchPreference(
                    title = "Autofill Forms & Credentials",
                    subtitle = "Automatically fill saved addresses, payment methods, and logins",
                    checked = isAutofillForms,
                    onCheckedChange = onAutofillFormsChange
                )

                OnyxSwitchPreference(
                    title = "Ask to Save Passwords",
                    subtitle = "Prompt to save entered passwords to your active password manager",
                    checked = isSavePasswords,
                    onCheckedChange = onSavePasswordsChange
                )

                OnyxSwitchPreference(
                    title = "Passkeys Support",
                    subtitle = "Enable passwordless WebAuthn / FIDO2 authentication on supported sites",
                    checked = isPasskeys,
                    onCheckedChange = onPasskeysChange
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
