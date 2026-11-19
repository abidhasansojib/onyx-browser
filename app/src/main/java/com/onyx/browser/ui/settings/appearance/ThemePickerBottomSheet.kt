package com.onyx.browser.ui.settings.appearance

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.theme.OnyxTheme

class ThemePickerBottomSheet @JvmOverloads constructor(
    private val onAppearanceChanged: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    // Secondary constructor for compatibility
    constructor(currentTheme: Int, onThemeSelected: (Int) -> Unit) : this({
        onThemeSelected(currentTheme)
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_OnyxBrowser_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val prefs = remember { BrowserPreferences.getInstance(requireContext()) }
                val isDark = when (prefs.themeMode) {
                    BrowserPreferences.THEME_DARK -> true
                    BrowserPreferences.THEME_LIGHT -> false
                    else -> androidx.compose.foundation.isSystemInDarkTheme()
                }
                OnyxTheme(darkTheme = isDark, isAmoled = false) {
                    ThemePickerContent()
                }
            }
        }
    }

    @Composable
    private fun ThemePickerContent() {
        val prefs = remember { BrowserPreferences.getInstance(requireContext()) }
        var selectedMode by remember { mutableIntStateOf(prefs.themeMode) }
        var forceDark by remember { mutableStateOf(prefs.isForceDarkModeEnabled) }

        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .navigationBarsPadding()
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 16.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                Text(
                    text = stringResource(R.string.theme_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Theme Options Row (System, Dark, Light)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ThemeOptionCard(
                        title = "System",
                        icon = Icons.Default.PhoneAndroid,
                        isSelected = selectedMode == BrowserPreferences.THEME_SYSTEM,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectThemeMode(prefs, BrowserPreferences.THEME_SYSTEM)
                        }
                    )

                    ThemeOptionCard(
                        title = "Dark",
                        icon = Icons.Default.DarkMode,
                        isSelected = selectedMode == BrowserPreferences.THEME_DARK,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectThemeMode(prefs, BrowserPreferences.THEME_DARK)
                        }
                    )

                    ThemeOptionCard(
                        title = "Light",
                        icon = Icons.Default.LightMode,
                        isSelected = selectedMode == BrowserPreferences.THEME_LIGHT,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectThemeMode(prefs, BrowserPreferences.THEME_LIGHT)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Force Dark Mode Switch Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            val newState = !forceDark
                            forceDark = newState
                            prefs.isForceDarkModeEnabled = newState
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Force dark mode for web",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Inverts light web pages when dark theme is active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Switch(
                            checked = forceDark,
                            onCheckedChange = { newState ->
                                forceDark = newState
                                prefs.isForceDarkModeEnabled = newState
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Dismiss Button
                TextButton(
                    onClick = { dismiss() },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }

    @Composable
    private fun ThemeOptionCard(
        title: String,
        icon: ImageVector,
        isSelected: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        val accent = MaterialTheme.colorScheme.primary
        val outline = if (isSelected) accent else MaterialTheme.colorScheme.outlineVariant
        val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = bg,
            border = androidx.compose.foundation.BorderStroke(
                width = if (isSelected) 2.dp else 1.dp,
                color = outline
            ),
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable { onClick() }
        ) {
            Column(
                modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) accent else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    private fun selectThemeMode(prefs: BrowserPreferences, mode: Int) {
        if (mode != prefs.themeMode) {
            prefs.themeMode = mode
            prefs.applyTheme()
            onAppearanceChanged?.invoke()
        }
        dismiss()
    }

    companion object {
        const val TAG = "ThemePickerSheet"
    }
}

typealias ThemePickerSheet = ThemePickerBottomSheet
