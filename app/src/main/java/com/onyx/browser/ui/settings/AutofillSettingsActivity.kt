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
import com.onyx.browser.ui.components.AppTopBar
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.components.PreferenceItem
import com.onyx.browser.ui.theme.OnyxTheme

class AutofillSettingsActivity : ComponentActivity() {

    private lateinit var preferences: BrowserPreferences
    private var activeManager by mutableStateOf<ActivePasswordManagerInfo?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
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
                AutofillSettingsScreen(
                    onBackClick = { finish() },
                    manager = activeManager,
                    onChangeAutofillService = {
                        AutofillHelper.openAutofillServiceSettings(this, activeManager?.packageName)
                    },
                    onOpenPasswordManager = {
                        AutofillHelper.openActivePasswordManager(this, activeManager)
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
        activeManager = AutofillHelper.getActivePasswordManager(this)
    }
}

@Composable
fun AutofillSettingsScreen(
    onBackClick: () -> Unit,
    manager: ActivePasswordManagerInfo?,
    onChangeAutofillService: () -> Unit,
    onOpenPasswordManager: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            AppTopBar(
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
            CardContainer {
                val title = if (manager != null) "${manager.appName} Active" else "No Autofill Service Selected"
                val subtitle = if (manager != null) {
                    "${manager.appName} is configured as your active Android password manager."
                } else {
                    "Choose Bitwarden, Proton Pass, 1Password, KeePassDX, or your preferred manager."
                }

                PreferenceItem(
                    title = title,
                    subtitle = subtitle,
                    icon = Icons.Default.Lock,
                    onClick = onOpenPasswordManager
                )

                PreferenceItem(
                    title = "Change Autofill Service",
                    subtitle = "Configure Android system autofill & passkey provider in settings",
                    icon = Icons.Default.Settings,
                    onClick = onChangeAutofillService
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
