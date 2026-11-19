package com.onyx.browser.ui.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.media.MediaPlaybackBridge
import com.onyx.browser.media.MediaPlaybackService
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.components.SwitchPreference
import com.onyx.browser.ui.components.AppTopBar
import com.onyx.browser.ui.theme.OnyxTheme

class VideoSettingsActivity : ComponentActivity() {

    private lateinit var preferences: BrowserPreferences
    private var pendingPipEnable: Boolean = false

    private var isBackgroundPlayEnabled by mutableStateOf(false)
    private var isPipEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        preferences = BrowserPreferences.getInstance(this)
        isBackgroundPlayEnabled = preferences.isBackgroundPlayEnabled
        updatePipSwitchState()

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = false) {
                VideoSettingsScreen(
                    onBackClick = { finish() },
                    isBackgroundPlay = isBackgroundPlayEnabled,
                    onBackgroundPlayChange = { enabled ->
                        isBackgroundPlayEnabled = enabled
                        preferences.isBackgroundPlayEnabled = enabled
                        if (!enabled) {
                            MediaPlaybackService.stopNotificationOnly(this)
                        }
                    },
                    isPip = isPipEnabled,
                    onPipChange = { enabled ->
                        if (enabled) {
                            if (!isPipPermissionAllowed()) {
                                Toast.makeText(this, "Please enable Picture-in-Picture permission", Toast.LENGTH_SHORT).show()
                                pendingPipEnable = true
                                openPipSystemSettings()
                            } else {
                                preferences.isPipEnabled = true
                                isPipEnabled = true
                            }
                        } else {
                            preferences.isPipEnabled = false
                            isPipEnabled = false
                            MediaPlaybackBridge.isVideoPlaying = false
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePipSwitchState()
    }

    private fun updatePipSwitchState() {
        val hasPipPerm = isPipPermissionAllowed()
        if (pendingPipEnable) {
            pendingPipEnable = false
            if (hasPipPerm) {
                preferences.isPipEnabled = true
                Toast.makeText(this, "Picture-in-Picture enabled", Toast.LENGTH_SHORT).show()
            } else {
                preferences.isPipEnabled = false
            }
        } else if (preferences.isPipEnabled && !hasPipPerm) {
            preferences.isPipEnabled = false
        }
        isPipEnabled = preferences.isPipEnabled && hasPipPerm
    }

    private fun isPipPermissionAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        }
    }

    private fun openPipSystemSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val pipIntent = Intent(
                    "android.settings.PICTURE_IN_PICTURE_SETTINGS",
                    Uri.parse("package:$packageName")
                )
                startActivity(pipIntent)
                return
            } catch (_: Exception) {}

            try {
                val pipIntent = Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS")
                startActivity(pipIntent)
                return
            } catch (_: Exception) {}
        }

        try {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(overlayIntent)
            return
        } catch (_: Exception) {}

        try {
            val appDetailsIntent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(appDetailsIntent)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open system settings", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun VideoSettingsScreen(
    onBackClick: () -> Unit,
    isBackgroundPlay: Boolean,
    onBackgroundPlayChange: (Boolean) -> Unit,
    isPip: Boolean,
    onPipChange: (Boolean) -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Video & Media Playback",
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
            CategoryHeader("Playback Mode")
            CardContainer {
                SwitchPreference(
                    title = "Background Audio / Video Playback",
                    subtitle = "Continue playing media when minimizing the app or locking the screen",
                    checked = isBackgroundPlay,
                    onCheckedChange = onBackgroundPlayChange
                )

                SwitchPreference(
                    title = "Picture-in-Picture (PiP)",
                    subtitle = "Automatically pop out video into a floating window when navigating home",
                    checked = isPip,
                    onCheckedChange = onPipChange
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
