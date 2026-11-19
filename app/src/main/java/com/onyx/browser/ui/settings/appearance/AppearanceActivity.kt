package com.onyx.browser.ui.settings.appearance

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.OnyxCardContainer
import com.onyx.browser.ui.components.OnyxPreferenceItem
import com.onyx.browser.ui.components.OnyxSwitchPreference
import com.onyx.browser.ui.components.OnyxTopAppBar
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.ui.theme.OnyxTheme

class AppearanceActivity : ComponentActivity() {

    private lateinit var preferences: BrowserPreferences
    private var selectedThemeMode by mutableIntStateOf(BrowserPreferences.THEME_SYSTEM)
    private var isForceDarkMode by mutableStateOf(false)
    private var selectedAccentHex by mutableStateOf("#DC4B64")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        preferences = BrowserPreferences.getInstance(this)
        selectedThemeMode = preferences.themeMode
        isForceDarkMode = preferences.isForceDarkModeEnabled
        selectedAccentHex = preferences.accentColorHex

        setContent {
            val isDark = when (selectedThemeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            val accentColor = try {
                Color(android.graphics.Color.parseColor(selectedAccentHex))
            } catch (_: Exception) {
                null
            }

            OnyxTheme(darkTheme = isDark, isAmoled = isDark, accentColor = accentColor) {
                AppearanceScreen(
                    onBackClick = { finish() },
                    selectedTheme = selectedThemeMode,
                    onThemeSelect = { mode ->
                        if (preferences.themeMode != mode) {
                            preferences.themeMode = mode
                            preferences.applyTheme()
                            selectedThemeMode = mode
                        }
                    },
                    selectedAccent = selectedAccentHex,
                    onAccentSelect = { preset ->
                        preferences.accentColorPreset = preset.id
                        preferences.accentColorHex = preset.hex
                        selectedAccentHex = preset.hex
                    },
                    onAddressBarSettingsClick = {
                        startActivity(Intent(this, AddressBarSettingsActivity::class.java))
                    },
                    isForceDarkMode = isForceDarkMode,
                    onForceDarkModeChange = { enabled ->
                        isForceDarkMode = enabled
                        preferences.isForceDarkModeEnabled = enabled
                    }
                )
            }
        }
    }
}

@Composable
fun AppearanceScreen(
    onBackClick: () -> Unit,
    selectedTheme: Int,
    onThemeSelect: (Int) -> Unit,
    selectedAccent: String,
    onAccentSelect: (com.onyx.browser.ui.theme.AccentPreset) -> Unit,
    onAddressBarSettingsClick: () -> Unit,
    isForceDarkMode: Boolean,
    onForceDarkModeChange: (Boolean) -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            OnyxTopAppBar(
                title = "Appearance & Theme",
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
            CategoryHeader("Theme Mode")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ThemeCard(
                    title = "System",
                    icon = Icons.Default.BrightnessAuto,
                    isSelected = selectedTheme == BrowserPreferences.THEME_SYSTEM,
                    onClick = { onThemeSelect(BrowserPreferences.THEME_SYSTEM) },
                    modifier = Modifier.weight(1f)
                )

                ThemeCard(
                    title = "Dark",
                    icon = Icons.Default.DarkMode,
                    isSelected = selectedTheme == BrowserPreferences.THEME_DARK,
                    onClick = { onThemeSelect(BrowserPreferences.THEME_DARK) },
                    modifier = Modifier.weight(1f)
                )

                ThemeCard(
                    title = "Light",
                    icon = Icons.Default.LightMode,
                    isSelected = selectedTheme == BrowserPreferences.THEME_LIGHT,
                    onClick = { onThemeSelect(BrowserPreferences.THEME_LIGHT) },
                    modifier = Modifier.weight(1f)
                )
            }

            CategoryHeader("Accent Color")
            OnyxCardContainer {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccentColorManager.PRESETS.forEach { preset ->
                        val presetColor = Color(android.graphics.Color.parseColor(preset.hex))
                        val isSelected = selectedAccent.equals(preset.hex, ignoreCase = true)

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(presetColor)
                                .clickable { onAccentSelect(preset) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = preset.name,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            CategoryHeader("Layout & Navigation")
            OnyxCardContainer {
                OnyxPreferenceItem(
                    title = "Address Bar Customization",
                    subtitle = "Adjust address bar position (Top / Bottom) and toolbar behavior",
                    icon = Icons.Default.ViewAgenda,
                    onClick = onAddressBarSettingsClick
                )
            }

            CategoryHeader("Web Content")
            OnyxCardContainer {
                OnyxSwitchPreference(
                    title = "Force Dark Mode for Web Contents",
                    subtitle = "Automatically convert light websites to dark theme",
                    checked = isForceDarkMode,
                    onCheckedChange = onForceDarkModeChange
                )
            }
        }
    }
}

@Composable
private fun ThemeCard(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val borderColor = if (isSelected) activeColor else MaterialTheme.colorScheme.outline
    val strokeWidth = if (isSelected) 2.dp else 1.dp

    Card(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(strokeWidth, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) activeColor else MaterialTheme.colorScheme.onSurface
            )
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
