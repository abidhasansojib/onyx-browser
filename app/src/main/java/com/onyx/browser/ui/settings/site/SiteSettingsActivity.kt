package com.onyx.browser.ui.settings.site

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.data.preferences.SitePermissionState
import com.onyx.browser.ui.components.AppAlertDialog
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.components.PreferenceItem
import com.onyx.browser.ui.components.RadioPreference
import com.onyx.browser.ui.components.AppTopBar
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.web.SiteDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SiteSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: BrowserPreferences

    private var allSitesCount by mutableStateOf(0)
    private var cameraStatus by mutableStateOf("Ask before accessing")
    private var micStatus by mutableStateOf("Ask before accessing")
    private var locationStatus by mutableStateOf("Ask before accessing")
    private var notificationsStatus by mutableStateOf("Ask before accessing")
    private var sensorsStatus by mutableStateOf("Allowed")
    private var clipboardStatus by mutableStateOf("Ask before accessing")
    private var javascriptStatus by mutableStateOf("Allowed")
    private var popupsStatus by mutableStateOf("Blocked (Recommended)")
    private var autoplayStatus by mutableStateOf("Allowed")
    private var imagesStatus by mutableStateOf("Allowed")
    private var soundStatus by mutableStateOf("Allowed")
    private var desktopStatus by mutableStateOf("Mobile (Default)")
    private var autoDownloadsStatus by mutableStateOf("Ask first")
    private var protectedContentStatus by mutableStateOf("Allowed")

    // Active Category Sheet State
    private var activeCategorySheet by mutableStateOf<String?>(null)
    private var showClearAllDataDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        prefs = BrowserPreferences.getInstance(this)
        loadStatuses()

        setContent {
            val isDark = when (prefs.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = false) {
                SiteSettingsScreen(
                    onBackClick = { finish() },
                    allSitesCount = allSitesCount,
                    onAllSitesClick = {
                        AllSitesBottomSheet { loadStatuses() }.show(supportFragmentManager, AllSitesBottomSheet.TAG)
                    },
                    onClearAllDataClick = { showClearAllDataDialog = true },

                    cameraStatus = cameraStatus,
                    onCameraClick = { activeCategorySheet = "Camera" },
                    micStatus = micStatus,
                    onMicClick = { activeCategorySheet = "Microphone" },
                    locationStatus = locationStatus,
                    onLocationClick = { activeCategorySheet = "Location" },
                    notificationsStatus = notificationsStatus,
                    onNotificationsClick = { activeCategorySheet = "Notifications" },
                    sensorsStatus = sensorsStatus,
                    onSensorsClick = { activeCategorySheet = "Sensors" },
                    clipboardStatus = clipboardStatus,
                    onClipboardClick = { activeCategorySheet = "Clipboard" },

                    javascriptStatus = javascriptStatus,
                    onJavascriptClick = { activeCategorySheet = "JavaScript" },
                    popupsStatus = popupsStatus,
                    onPopupsClick = { activeCategorySheet = "Popups" },
                    autoplayStatus = autoplayStatus,
                    onAutoplayClick = { activeCategorySheet = "Autoplay" },
                    imagesStatus = imagesStatus,
                    onImagesClick = { activeCategorySheet = "Images" },
                    soundStatus = soundStatus,
                    onSoundClick = { activeCategorySheet = "Sound" },
                    desktopStatus = desktopStatus,
                    onDesktopClick = { activeCategorySheet = "Desktop" },
                    autoDownloadsStatus = autoDownloadsStatus,
                    onAutoDownloadsClick = { activeCategorySheet = "AutoDownloads" },
                    protectedContentStatus = protectedContentStatus,
                    onProtectedContentClick = { activeCategorySheet = "ProtectedContent" }
                )

                if (showClearAllDataDialog) {
                    val configuredCount = prefs.getAllConfiguredDomains().size
                    val message = if (configuredCount == 0) {
                        "Clear all cookies and browsing data stored on this device?"
                    } else {
                        "Clear all cookies and browsing data for $configuredCount sites on this device? All site permission exceptions will also be reset."
                    }

                    AppAlertDialog(
                        title = "Clear Site Data",
                        text = { Text(message) },
                        confirmButtonText = "Clear Data",
                        isDestructive = true,
                        onConfirm = {
                            clearAllStoredSiteData()
                            showClearAllDataDialog = false
                        },
                        onDismissRequest = { showClearAllDataDialog = false }
                    )
                }

                if (activeCategorySheet != null) {
                    val category = activeCategorySheet!!
                    PermissionCategorySheet(
                        category = category,
                        selectedIndex = getSelectedOptionIndex(category),
                        prefs = prefs,
                        onDismiss = { activeCategorySheet = null },
                        onOptionSelected = {
                            handleCategoryOptionChange(category, it)
                            loadStatuses()
                        },
                        onExceptionsChanged = {
                            loadStatuses()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadStatuses()
    }

    private fun loadStatuses() {
        allSitesCount = prefs.getAllConfiguredDomains().size
        cameraStatus = if (prefs.isCameraEnabledGlobal) "Ask before accessing" else "Blocked globally"
        micStatus = if (prefs.isMicEnabledGlobal) "Ask before accessing" else "Blocked globally"
        locationStatus = if (prefs.isLocationEnabledGlobal) "Ask before accessing" else "Blocked globally"
        notificationsStatus = if (prefs.isNotificationsEnabledGlobal) "Ask before accessing" else "Blocked globally"
        sensorsStatus = if (!prefs.isSensorsBlockedGlobal) "Allowed" else "Blocked"
        clipboardStatus = if (!prefs.isClipboardBlockedGlobal) "Ask before accessing" else "Blocked"
        javascriptStatus = if (prefs.isJavaScriptEnabledGlobal) "Allowed (Recommended)" else "Blocked globally"
        popupsStatus = if (prefs.isPopupsBlockedGlobal) "Blocked (Recommended)" else "Allowed"
        autoplayStatus = if (prefs.isAutoplayBlockedGlobal) "Blocked" else "Allowed"
        imagesStatus = if (prefs.isImagesEnabledGlobal) "Allowed" else "Blocked"
        soundStatus = if (!prefs.isSoundMutedGlobal) "Allowed" else "Blocked"
        desktopStatus = if (prefs.isDesktopMode) "Desktop" else "Mobile (Default)"
        autoDownloadsStatus = if (prefs.isAutoDownloadsBlockedGlobal) "Blocked" else "Ask first"
        protectedContentStatus = if (prefs.isProtectedContentEnabledGlobal) "Allowed" else "Blocked"
    }

    private fun getSelectedOptionIndex(category: String): Int {
        return when (category) {
            "Camera" -> if (prefs.isCameraEnabledGlobal) 0 else 1
            "Microphone" -> if (prefs.isMicEnabledGlobal) 0 else 1
            "Location" -> if (prefs.isLocationEnabledGlobal) 0 else 1
            "Notifications" -> if (prefs.isNotificationsEnabledGlobal) 0 else 1
            "Sensors" -> if (!prefs.isSensorsBlockedGlobal) 0 else 1
            "Clipboard" -> if (!prefs.isClipboardBlockedGlobal) 0 else 1
            "JavaScript" -> if (prefs.isJavaScriptEnabledGlobal) 0 else 1
            "Popups" -> if (prefs.isPopupsBlockedGlobal) 0 else 1
            "Autoplay" -> if (prefs.isAutoplayBlockedGlobal) 1 else 0
            "Images" -> if (prefs.isImagesEnabledGlobal) 0 else 1
            "Sound" -> if (!prefs.isSoundMutedGlobal) 0 else 1
            "Desktop" -> if (prefs.isDesktopMode) 1 else 0
            "AutoDownloads" -> if (prefs.isAutoDownloadsBlockedGlobal) 1 else 0
            "ProtectedContent" -> if (prefs.isProtectedContentEnabledGlobal) 0 else 1
            else -> 0
        }
    }

    private fun handleCategoryOptionChange(category: String, optionIndex: Int) {
        when (category) {
            "Camera" -> prefs.isCameraEnabledGlobal = (optionIndex == 0)
            "Microphone" -> prefs.isMicEnabledGlobal = (optionIndex == 0)
            "Location" -> prefs.isLocationEnabledGlobal = (optionIndex == 0)
            "Notifications" -> prefs.isNotificationsEnabledGlobal = (optionIndex == 0)
            "Sensors" -> prefs.isSensorsBlockedGlobal = (optionIndex != 0)
            "Clipboard" -> prefs.isClipboardBlockedGlobal = (optionIndex != 0)
            "JavaScript" -> prefs.isJavaScriptEnabledGlobal = (optionIndex == 0)
            "Popups" -> prefs.isPopupsBlockedGlobal = (optionIndex == 0)
            "Autoplay" -> prefs.isAutoplayBlockedGlobal = (optionIndex == 1)
            "Images" -> prefs.isImagesEnabledGlobal = (optionIndex == 0)
            "Sound" -> prefs.isSoundMutedGlobal = (optionIndex != 0)
            "Desktop" -> prefs.isDesktopMode = (optionIndex == 1)
            "AutoDownloads" -> prefs.isAutoDownloadsBlockedGlobal = (optionIndex == 1)
            "ProtectedContent" -> prefs.isProtectedContentEnabledGlobal = (optionIndex == 0)
        }
    }

    private fun clearAllStoredSiteData() {
        val domains = prefs.getAllConfiguredDomains()
        val ctx = applicationContext

        for (domain in domains) {
            prefs.resetSitePermissions(domain)
            prefs.setDomainWhitelisted(domain, false)
        }

        prefs.desktopDomains = emptySet()
        prefs.mobileDomains = emptySet()
        prefs.autoShredDomains = emptySet()
        prefs.autoKeepDomains = emptySet()
        prefs.clearAllCustomBlockedRules()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                com.onyx.browser.data.filter.FilterListManager.recompileFilters(ctx)
            } catch (_: Exception) {}
        }

        android.webkit.WebStorage.getInstance().deleteAllData()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.CookieManager.getInstance().flush()

        for (domain in domains) {
            SiteDataManager.clearSiteData(ctx, domain)
        }

        Toast.makeText(this, "Cleared all cookies and site data", Toast.LENGTH_SHORT).show()
        loadStatuses()
    }
}

@Composable
fun SiteSettingsScreen(
    onBackClick: () -> Unit,
    allSitesCount: Int,
    onAllSitesClick: () -> Unit,
    onClearAllDataClick: () -> Unit,
    cameraStatus: String,
    onCameraClick: () -> Unit,
    micStatus: String,
    onMicClick: () -> Unit,
    locationStatus: String,
    onLocationClick: () -> Unit,
    notificationsStatus: String,
    onNotificationsClick: () -> Unit,
    sensorsStatus: String,
    onSensorsClick: () -> Unit,
    clipboardStatus: String,
    onClipboardClick: () -> Unit,
    javascriptStatus: String,
    onJavascriptClick: () -> Unit,
    popupsStatus: String,
    onPopupsClick: () -> Unit,
    autoplayStatus: String,
    onAutoplayClick: () -> Unit,
    imagesStatus: String,
    onImagesClick: () -> Unit,
    soundStatus: String,
    onSoundClick: () -> Unit,
    desktopStatus: String,
    onDesktopClick: () -> Unit,
    autoDownloadsStatus: String,
    onAutoDownloadsClick: () -> Unit,
    protectedContentStatus: String,
    onProtectedContentClick: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Site Settings",
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
            CategoryHeader("All Sites & Storage")
            CardContainer {
                val subtitle = if (allSitesCount == 0) "Manage saved data and exceptions" else "$allSitesCount sites with custom permissions"
                PreferenceItem(
                    title = "All Sites",
                    subtitle = subtitle,
                    icon = Icons.Default.Storage,
                    onClick = onAllSitesClick
                )

                PreferenceItem(
                    title = "Clear All Cookies & Site Data",
                    subtitle = "Wipe all stored cookies, local storage, and exceptions",
                    icon = Icons.Default.DeleteSweep,
                    onClick = onClearAllDataClick
                )
            }

            CategoryHeader("Hardware & Sensors")
            CardContainer {
                PreferenceItem(
                    title = "Camera",
                    subtitle = cameraStatus,
                    icon = Icons.Default.CameraAlt,
                    onClick = onCameraClick
                )

                PreferenceItem(
                    title = "Microphone",
                    subtitle = micStatus,
                    icon = Icons.Default.Mic,
                    onClick = onMicClick
                )

                PreferenceItem(
                    title = "Location",
                    subtitle = locationStatus,
                    icon = Icons.Default.LocationOn,
                    onClick = onLocationClick
                )

                PreferenceItem(
                    title = "Motion & Sensors",
                    subtitle = sensorsStatus,
                    icon = Icons.Default.Sensors,
                    onClick = onSensorsClick
                )
            }

            CategoryHeader("Content & Permissions")
            CardContainer {
                PreferenceItem(
                    title = "Notifications",
                    subtitle = notificationsStatus,
                    icon = Icons.Default.Notifications,
                    onClick = onNotificationsClick
                )

                PreferenceItem(
                    title = "Clipboard Access",
                    subtitle = clipboardStatus,
                    icon = Icons.Default.ContentPaste,
                    onClick = onClipboardClick
                )

                PreferenceItem(
                    title = "Popups and Redirects",
                    subtitle = popupsStatus,
                    icon = Icons.Default.OpenInBrowser,
                    onClick = onPopupsClick
                )

                PreferenceItem(
                    title = "Video Autoplay",
                    subtitle = autoplayStatus,
                    icon = Icons.Default.Videocam,
                    onClick = onAutoplayClick
                )

                PreferenceItem(
                    title = "Images",
                    subtitle = imagesStatus,
                    icon = Icons.Default.Image,
                    onClick = onImagesClick
                )

                PreferenceItem(
                    title = "Sound",
                    subtitle = soundStatus,
                    icon = Icons.Default.Audiotrack,
                    onClick = onSoundClick
                )
            }

            CategoryHeader("Web Technology & Browsing")
            CardContainer {
                PreferenceItem(
                    title = "JavaScript Execution",
                    subtitle = javascriptStatus,
                    icon = Icons.Default.Javascript,
                    onClick = onJavascriptClick
                )

                PreferenceItem(
                    title = "Desktop Site Mode",
                    subtitle = desktopStatus,
                    icon = Icons.Default.DesktopWindows,
                    onClick = onDesktopClick
                )

                PreferenceItem(
                    title = "Automatic Downloads",
                    subtitle = autoDownloadsStatus,
                    icon = Icons.Default.Download,
                    onClick = onAutoDownloadsClick
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionCategorySheet(
    category: String,
    selectedIndex: Int,
    prefs: BrowserPreferences,
    onDismiss: () -> Unit,
    onOptionSelected: (Int) -> Unit,
    onExceptionsChanged: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var currentSelectedIndex by remember(selectedIndex) { mutableIntStateOf(selectedIndex) }

    val options = when (category) {
        "Camera", "Microphone", "Location", "Notifications", "Clipboard" -> listOf("Ask first (Recommended)", "Blocked globally")
        "Sensors", "Images", "Sound", "JavaScript" -> listOf("Allowed (Recommended)", "Blocked globally")
        "Popups" -> listOf("Blocked (Recommended)", "Allowed")
        "Autoplay" -> listOf("Allow video autoplay", "Block autoplay")
        "Desktop" -> listOf("Mobile layout (Default)", "Desktop mode (Request desktop site)")
        "AutoDownloads" -> listOf("Ask first (Recommended)", "Blocked")
        "ProtectedContent" -> listOf("Allowed (Play protected DRM content)", "Blocked")
        else -> listOf("Allowed", "Blocked")
    }

    val scrollState = rememberScrollState()

    var desktopExceptions by remember { mutableStateOf(prefs.desktopDomains) }
    var mobileExceptions by remember { mutableStateOf(prefs.mobileDomains) }
    var showAddExceptionDialog by remember { mutableStateOf(false) }
    var newDomainInput by remember { mutableStateOf("") }
    var newDomainModeDesktop by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = if (category == "Desktop") "Desktop Site Mode" else "$category Permission",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 14.dp)
            )

            options.forEachIndexed { index, option ->
                RadioPreference(
                    title = option,
                    selected = index == currentSelectedIndex,
                    onClick = {
                        currentSelectedIndex = index
                        onOptionSelected(index)
                    }
                )
            }

            if (category == "Desktop") {
                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Site Exceptions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(onClick = { showAddExceptionDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add exception")
                    }
                }

                if (desktopExceptions.isNotEmpty()) {
                    CategoryHeader(title = "ALWAYS DESKTOP")
                    CardContainer {
                        desktopExceptions.sorted().forEach { domain ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = domain,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Desktop mode forced",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    prefs.setSiteDesktopModeState(domain, SitePermissionState.ASK)
                                    desktopExceptions = prefs.desktopDomains
                                    mobileExceptions = prefs.mobileDomains
                                    onExceptionsChanged()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove exception",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                if (mobileExceptions.isNotEmpty()) {
                    CategoryHeader(title = "ALWAYS MOBILE")
                    CardContainer {
                        mobileExceptions.sorted().forEach { domain ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = domain,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Mobile layout forced",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    prefs.setSiteDesktopModeState(domain, SitePermissionState.ASK)
                                    desktopExceptions = prefs.desktopDomains
                                    mobileExceptions = prefs.mobileDomains
                                    onExceptionsChanged()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove exception",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                if (desktopExceptions.isEmpty() && mobileExceptions.isEmpty()) {
                    Text(
                        text = "No site exceptions configured",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        if (showAddExceptionDialog) {
            AppAlertDialog(
                title = "Add Desktop Exception",
                confirmButtonText = "Add",
                onConfirm = {
                    val clean = prefs.cleanDomain(newDomainInput.trim())
                    if (clean.isNotBlank()) {
                        val state = if (newDomainModeDesktop) SitePermissionState.ALLOWED else SitePermissionState.BLOCKED
                        prefs.setSiteDesktopModeState(clean, state)
                        desktopExceptions = prefs.desktopDomains
                        mobileExceptions = prefs.mobileDomains
                        onExceptionsChanged()
                    }
                    showAddExceptionDialog = false
                    newDomainInput = ""
                },
                onDismissRequest = {
                    showAddExceptionDialog = false
                    newDomainInput = ""
                },
                text = {
                    Column {
                        Text(
                            text = "Enter a website domain to override the global desktop site setting:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = newDomainInput,
                            onValueChange = { newDomainInput = it },
                            placeholder = { Text("example.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { newDomainModeDesktop = true }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = newDomainModeDesktop,
                                onClick = { newDomainModeDesktop = true }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Always Desktop",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { newDomainModeDesktop = false }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = !newDomainModeDesktop,
                                onClick = { newDomainModeDesktop = false }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Always Mobile",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
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
