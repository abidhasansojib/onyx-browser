package com.onyx.browser.ui.settings

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
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.components.PreferenceItem
import com.onyx.browser.ui.components.RadioPreference
import com.onyx.browser.ui.components.SwitchPreference
import com.onyx.browser.ui.components.AppTopBar
import com.onyx.browser.ui.theme.OnyxTheme

class DownloadSettingsActivity : ComponentActivity() {

    private lateinit var preferences: BrowserPreferences

    private var downloadBehavior by mutableIntStateOf(0)
    private var isDownloadWifiOnly by mutableStateOf(false)
    private var showBehaviorPicker by mutableStateOf(false)

    private val downloadOptions = arrayOf(
        "Ask before download",
        "Internal downloader",
        "External download manager"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        preferences = BrowserPreferences.getInstance(this)
        downloadBehavior = preferences.downloadManagerBehavior.coerceIn(0, 2)
        isDownloadWifiOnly = preferences.isDownloadWifiOnly

        val isAmoled = preferences.themeMode == BrowserPreferences.THEME_DARK

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = isAmoled) {
                DownloadSettingsScreen(
                    onBackClick = { finish() },
                    currentBehaviorText = downloadOptions[downloadBehavior],
                    onBehaviorClick = { showBehaviorPicker = true },
                    isWifiOnly = isDownloadWifiOnly,
                    onWifiOnlyChange = { enabled ->
                        isDownloadWifiOnly = enabled
                        preferences.isDownloadWifiOnly = enabled
                    }
                )

                if (showBehaviorPicker) {
                    DownloadBehaviorBottomSheet(
                        selectedIndex = downloadBehavior,
                        options = downloadOptions,
                        onOptionSelected = { idx ->
                            downloadBehavior = idx
                            preferences.downloadManagerBehavior = idx
                            showBehaviorPicker = false
                        },
                        onDismissRequest = { showBehaviorPicker = false }
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadSettingsScreen(
    onBackClick: () -> Unit,
    currentBehaviorText: String,
    onBehaviorClick: () -> Unit,
    isWifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Downloads Settings",
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
            CategoryHeader("Download Engine")
            CardContainer {
                PreferenceItem(
                    title = "Default Downloader",
                    subtitle = currentBehaviorText,
                    onClick = onBehaviorClick
                )

                SwitchPreference(
                    title = "Download over Wi-Fi only",
                    subtitle = "Pause or prevent downloads when connected to mobile data",
                    checked = isWifiOnly,
                    onCheckedChange = onWifiOnlyChange
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadBehaviorBottomSheet(
    selectedIndex: Int,
    options: Array<String>,
    onOptionSelected: (Int) -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Text(
                text = "Choose Downloader",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            options.forEachIndexed { index, option ->
                RadioPreference(
                    title = option,
                    selected = index == selectedIndex,
                    onClick = { onOptionSelected(index) }
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
