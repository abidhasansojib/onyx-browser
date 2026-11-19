package com.onyx.browser.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.AppAlertDialog
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.components.PreferenceItem
import com.onyx.browser.ui.components.AppTopBar
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.update.AppUpdateManager
import com.onyx.browser.update.AppUpdatePromptHelper
import com.onyx.browser.update.UpdateCheckResult
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AboutActivity : AppCompatActivity() {

    private var isCheckingUpdate by mutableStateOf(false)
    private var updateStatusText by mutableStateOf("Check for updates")
    private var showUpToDateDialog by mutableStateOf(false)
    private var currentVersionTag by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val prefs = BrowserPreferences.getInstance(this)
        val isAmoled = prefs.themeMode == BrowserPreferences.THEME_DARK && prefs.isAmoledMode()

        setContent {
            val isDark = when (prefs.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = isAmoled) {
                AboutScreen(
                    onBackClick = { finish() },
                    isChecking = isCheckingUpdate,
                    updateStatus = updateStatusText,
                    onCheckUpdateClick = { performUpdateCheck(manual = true) },
                    onCopyText = { label, text -> copyToClipboard(label, text) },
                    onOpenUrl = { url -> openUrl(url) }
                )

                if (showUpToDateDialog) {
                    AppAlertDialog(
                        title = getString(R.string.app_up_to_date),
                        text = {
                            Text(getString(R.string.app_up_to_date_desc, "v$currentVersionTag"))
                        },
                        confirmButtonText = "OK",
                        dismissButtonText = null,
                        onConfirm = { showUpToDateDialog = false },
                        onDismissRequest = { showUpToDateDialog = false }
                    )
                }
            }
        }

        // Silent background check on open
        performUpdateCheck(manual = false)
    }

    override fun onResume() {
        super.onResume()
        AppUpdatePromptHelper.handleResumeInstall(this)
    }

    private fun performUpdateCheck(manual: Boolean) {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        updateStatusText = getString(R.string.checking_for_updates)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AppUpdateManager.checkForUpdate(this@AboutActivity)
            }

            isCheckingUpdate = false

            when (result) {
                is UpdateCheckResult.Available -> {
                    updateStatusText = "${getString(R.string.update_available)} (${result.updateInfo.tagName})"
                    AppUpdatePromptHelper.showUpdateAvailableDialog(this@AboutActivity, result.updateInfo)
                }
                is UpdateCheckResult.UpToDate -> {
                    updateStatusText = getString(R.string.app_up_to_date)
                    currentVersionTag = result.currentVersion
                    if (manual) {
                        showUpToDateDialog = true
                    }
                }
                is UpdateCheckResult.RateLimited -> {
                    updateStatusText = "Rate limit reached"
                    if (manual) {
                        Toast.makeText(this@AboutActivity, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
                is UpdateCheckResult.Error -> {
                    updateStatusText = getString(R.string.check_for_updates_desc)
                    if (manual) {
                        Toast.makeText(this@AboutActivity, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.copied_to_clipboard, label), Toast.LENGTH_SHORT).show()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open link: $url", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun BrowserPreferences.isAmoledMode(): Boolean {
    return false
}

@Composable
fun AboutScreen(
    onBackClick: () -> Unit,
    isChecking: Boolean,
    updateStatus: String,
    onCheckUpdateClick: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "About Onyx",
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
            // App Branding Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher),
                        contentDescription = "Onyx Logo",
                        modifier = Modifier.size(56.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Onyx Browser",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text = "Fast, private & lightweight browser for Android",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Native Compose Edition",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Updates Section
            CategoryHeader(title = "Updates")
            CardContainer {
                PreferenceItem(
                    title = "Check for Updates",
                    subtitle = updateStatus,
                    icon = Icons.Default.Refresh,
                    showChevron = !isChecking,
                    trailingContent = if (isChecking) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null,
                    onClick = onCheckUpdateClick
                )
            }

            // App & System Section
            CategoryHeader(title = "Application & System")
            CardContainer {
                val osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                val deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
                val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Universal"

                PreferenceItem(
                    title = "Application Architecture",
                    subtitle = "100% Jetpack Compose • $abi",
                    icon = Icons.Default.Info,
                    showChevron = false,
                    onClick = { onCopyText("Architecture", "Jetpack Compose • $abi") }
                )

                PreferenceItem(
                    title = "Operating System",
                    subtitle = "$osVersion • $deviceModel",
                    showChevron = false,
                    onClick = { onCopyText("OS Version", "$osVersion • $deviceModel") }
                )

                PreferenceItem(
                    title = "WebView Engine",
                    subtitle = getWebViewSummary(),
                    showChevron = false,
                    onClick = { onCopyText("WebView Engine", getWebViewSummary()) }
                )
            }

            // Community & Support
            CategoryHeader(title = "Community & Support")
            CardContainer {
                PreferenceItem(
                    title = "Telegram Channel",
                    subtitle = "Chat with developer & community",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = { onOpenUrl("https://t.me/abidhasansojib") }
                )

                PreferenceItem(
                    title = "GitHub Repository",
                    subtitle = "Source code, releases & star the repo",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = { onOpenUrl("https://github.com/abidhasansojib/onyx-browser") }
                )

                PreferenceItem(
                    title = "Report an Issue",
                    subtitle = "Submit bugs or feature requests",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = { onOpenUrl("https://github.com/abidhasansojib/onyx-browser/issues") }
                )
            }
        }
    }
}

private fun getWebViewSummary(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val pkg = WebView.getCurrentWebViewPackage()
        "${pkg?.versionName ?: "System"} (${pkg?.packageName ?: "Android System WebView"})"
    } else {
        "Android System WebView"
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
