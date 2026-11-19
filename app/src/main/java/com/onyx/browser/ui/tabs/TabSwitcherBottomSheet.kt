package com.onyx.browser.ui.tabs

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onyx.browser.R
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.browser.TabCoordinator
import com.onyx.browser.ui.common.BiometricAuthHelper
import com.onyx.browser.ui.theme.OnyxTheme
import kotlinx.coroutines.CoroutineScope

class TabSwitcherBottomSheet(
    private val tabManager: TabCoordinator,
    private val coroutineScope: CoroutineScope,
    private val onTabSelected: (TabItem) -> Unit,
    private val onNewTabRequested: (Boolean) -> Unit,
    private val onSearchRequested: (() -> Unit)? = null
) : DialogFragment() {

    private var isDismissing = false
    private val undoToastHandler = Handler(Looper.getMainLooper())
    private var undoDismissRunnable: Runnable? = null

    // State holders for Compose
    private val isViewingIncognitoState = mutableStateOf(false)
    private val undoMessageState = mutableStateOf<String?>(null)

    fun refreshTabsList() {
        // Compose automatically collects tabManager.normalTabs and incognitoTabs
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_OnyxBrowser_TabSwitcher)
        isViewingIncognitoState.value = tabManager.activeTab.value?.isIncognito ?: false
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            val bgColor = ContextCompat.getColor(context, R.color.tab_switcher_bg)
            statusBarColor = bgColor
            navigationBarColor = bgColor
            WindowCompat.setDecorFitsSystemWindows(this, false)
            val controller = WindowCompat.getInsetsController(this, decorView)
            val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            controller.isAppearanceLightStatusBars = !isNightMode
            controller.isAppearanceLightNavigationBars = !isNightMode
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        isDismissing = false
        val prefs = BrowserPreferences.getInstance(requireContext())
        if (isViewingIncognitoState.value && prefs.isBiometricIncognitoEnabled && !tabManager.isIncognitoUnlocked) {
            isViewingIncognitoState.value = false
            promptBiometricAuth {
                isViewingIncognitoState.value = true
            }
        }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                OnyxTheme {
                    TabSwitcherScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TabSwitcherScreen() {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val spanCount = if (configuration.screenWidthDp >= 600) 3 else 2

        val normalTabs by tabManager.normalTabs.collectAsStateWithLifecycle()
        val incognitoTabs by tabManager.incognitoTabs.collectAsStateWithLifecycle()
        val activeTab by tabManager.activeTab.collectAsStateWithLifecycle()

        var isViewingIncognito by isViewingIncognitoState
        val undoMessage by undoMessageState

        val currentTabs = if (isViewingIncognito) incognitoTabs else normalTabs
        val prefs = remember { BrowserPreferences.getInstance(context) }
        var overflowMenuExpanded by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TabSwitcherTopBar(
                    normalCount = normalTabs.size,
                    isViewingIncognito = isViewingIncognito,
                    onToggleIncognito = { targetIncognito ->
                        if (targetIncognito != isViewingIncognito && !isDismissing) {
                            if (targetIncognito) {
                                if (prefs.isBiometricIncognitoEnabled && !tabManager.isIncognitoUnlocked) {
                                    promptBiometricAuth {
                                        dismissUndoToast(animate = false)
                                        isViewingIncognito = true
                                    }
                                } else {
                                    dismissUndoToast(animate = false)
                                    isViewingIncognito = true
                                }
                            } else {
                                dismissUndoToast(animate = false)
                                isViewingIncognito = false
                            }
                        }
                    },
                    onSearchClick = {
                        if (!isDismissing) {
                            isDismissing = true
                            dismissAllowingStateLoss()
                            onSearchRequested?.invoke()
                        }
                    },
                    onOverflowClick = { overflowMenuExpanded = true },
                    overflowMenuExpanded = overflowMenuExpanded,
                    onDismissOverflow = { overflowMenuExpanded = false },
                    onNewTabClick = {
                        overflowMenuExpanded = false
                        if (!isDismissing) {
                            isDismissing = true
                            onNewTabRequested(false)
                            dismissAllowingStateLoss()
                        }
                    },
                    onNewIncognitoTabClick = {
                        overflowMenuExpanded = false
                        if (!isDismissing) {
                            isDismissing = true
                            onNewTabRequested(true)
                            dismissAllowingStateLoss()
                        }
                    },
                    onCloseAllTabsClick = {
                        overflowMenuExpanded = false
                        if (!isDismissing) confirmCloseAllTabs()
                    },
                    onClearBrowsingDataClick = {
                        overflowMenuExpanded = false
                        if (!isDismissing) showClearBrowsingDataDialog()
                    },
                    hasTabs = currentTabs.isNotEmpty()
                )
            },
            bottomBar = {
                TabSwitcherBottomBar(
                    hasTabs = currentTabs.isNotEmpty(),
                    isViewingIncognito = isViewingIncognito,
                    onClearDataClick = {
                        if (!isDismissing) showClearBrowsingDataDialog()
                    },
                    onNewTabClick = {
                        if (!isDismissing) {
                            isDismissing = true
                            onNewTabRequested(isViewingIncognito)
                            dismissAllowingStateLoss()
                        }
                    },
                    onCloseAllTabsClick = {
                        if (!isDismissing) confirmCloseAllTabs()
                    }
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (currentTabs.isEmpty()) {
                    EmptyTabsView(isViewingIncognito = isViewingIncognito)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(spanCount),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(currentTabs, key = { it.id }) { tab ->
                            val isLocked = tab.isIncognito && prefs.isBiometricIncognitoEnabled && !tabManager.isIncognitoUnlocked
                            val isActive = tab.id == activeTab?.id

                            TabCard(
                                tab = tab,
                                isActive = isActive,
                                isLocked = isLocked,
                                snapshot = if (isLocked) null else tabManager.getSnapshot(tab.id),
                                onTabClick = {
                                    if (isDismissing) return@TabCard
                                    if (isLocked) {
                                        promptBiometricAuth {
                                            isDismissing = true
                                            tabManager.selectTab(tab)
                                            onTabSelected(tab)
                                            dismissAllowingStateLoss()
                                        }
                                    } else {
                                        isDismissing = true
                                        tabManager.selectTab(tab)
                                        onTabSelected(tab)
                                        dismissAllowingStateLoss()
                                    }
                                },
                                onCloseClick = {
                                    if (isDismissing) return@TabCard
                                    tabManager.closeTab(tab)
                                    showUndoToastForTab(tab)
                                }
                            )
                        }
                    }
                }

                // Floating Undo Toast
                AnimatedVisibility(
                    visible = undoMessage != null,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                ) {
                    undoMessage?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 6.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                TextButton(
                                    onClick = {
                                        val restoredBatch = tabManager.undoCloseTab()
                                        if (restoredBatch != null && restoredBatch.states.isNotEmpty()) {
                                            val nextBatch = tabManager.peekLastClosedBatch(isViewingIncognitoState.value)
                                            if (nextBatch != null) {
                                                showUndoToastForBatch(nextBatch)
                                            } else {
                                                dismissUndoToast(animate = true)
                                            }
                                        } else {
                                            dismissUndoToast(animate = true)
                                        }
                                    }
                                ) {
                                    Text(
                                        text = stringResource(R.string.undo),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TabSwitcherTopBar(
        normalCount: Int,
        isViewingIncognito: Boolean,
        onToggleIncognito: (Boolean) -> Unit,
        onSearchClick: () -> Unit,
        onOverflowClick: () -> Unit,
        overflowMenuExpanded: Boolean,
        onDismissOverflow: () -> Unit,
        onNewTabClick: () -> Unit,
        onNewIncognitoTabClick: () -> Unit,
        onCloseAllTabsClick: () -> Unit,
        onClearBrowsingDataClick: () -> Unit,
        hasTabs: Boolean
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.search),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            // Normal / Incognito Pill Toggle
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Normal Tabs Button
                val normalBg = if (!isViewingIncognito) MaterialTheme.colorScheme.primary else Color.Transparent
                val normalText = if (!isViewingIncognito) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(normalBg)
                        .clickable { onToggleIncognito(false) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (normalCount > 99) "99+" else normalCount.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = normalText
                    )
                }

                // Incognito Tabs Button
                val incognitoBg = if (isViewingIncognito) MaterialTheme.colorScheme.primary else Color.Transparent
                val incognitoTint = if (isViewingIncognito) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(incognitoBg)
                        .clickable { onToggleIncognito(true) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_incognito),
                        contentDescription = stringResource(R.string.incognito_mode),
                        tint = incognitoTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Overflow Menu
            Box {
                IconButton(onClick = onOverflowClick) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                DropdownMenu(
                    expanded = overflowMenuExpanded,
                    onDismissRequest = onDismissOverflow
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_tab)) },
                        onClick = onNewTabClick
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_incognito_tab)) },
                        onClick = onNewIncognitoTabClick
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isViewingIncognito) stringResource(R.string.close_all_incognito_tabs)
                                else stringResource(R.string.close_all_tabs)
                            )
                        },
                        enabled = hasTabs,
                        onClick = onCloseAllTabsClick
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_browsing_data)) },
                        onClick = onClearBrowsingDataClick
                    )
                }
            }
        }
    }

    @Composable
    private fun TabCard(
        tab: TabItem,
        isActive: Boolean,
        isLocked: Boolean,
        snapshot: Bitmap?,
        onTabClick: () -> Unit,
        onCloseClick: () -> Unit
    ) {
        val accentColor = MaterialTheme.colorScheme.primary
        val outlineColor = if (isActive) accentColor else MaterialTheme.colorScheme.outlineVariant
        val headerColor = if (isActive) accentColor else MaterialTheme.colorScheme.surfaceVariant
        val titleColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        val closeColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

        val domain = remember(tab.url) {
            try {
                if (tab.url.isNotBlank()) Uri.parse(tab.url).host ?: tab.url else "New Tab"
            } catch (_: Exception) {
                tab.url
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                width = if (isActive) 2.5.dp else 1.dp,
                color = outlineColor
            ),
            shadowElevation = if (isActive) 4.dp else 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clickable { onTabClick() }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Tab Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(headerColor)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Favicon / Lock Icon
                    if (isLocked) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = closeColor,
                            modifier = Modifier.size(16.dp)
                        )
                    } else if (tab.isIncognito) {
                        Icon(
                            painter = painterResource(R.drawable.ic_incognito),
                            contentDescription = null,
                            tint = closeColor,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_web),
                            contentDescription = null,
                            tint = closeColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Title & Domain
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isLocked) "Protected Tab" else tab.title.ifBlank { "New Tab" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = titleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!isLocked) {
                            Text(
                                text = domain,
                                style = MaterialTheme.typography.labelSmall,
                                color = titleColor.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Close Button
                    IconButton(
                        onClick = onCloseClick,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close tab",
                            tint = closeColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Tab Content / Thumbnail
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLocked) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Locked",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    } else if (snapshot != null) {
                        Image(
                            bitmap = snapshot.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_onyx_emblem),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyTabsView(isViewingIncognito: Boolean) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(
                    if (isViewingIncognito) R.drawable.ic_incognito else R.drawable.ic_onyx_emblem
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isViewingIncognito) stringResource(R.string.incognito_tabs) else "No tabs open",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isViewingIncognito) stringResource(R.string.incognito_privacy_desc) else "Tap + to open a new tab",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun TabSwitcherBottomBar(
        hasTabs: Boolean,
        isViewingIncognito: Boolean,
        onClearDataClick: () -> Unit,
        onNewTabClick: () -> Unit,
        onCloseAllTabsClick: () -> Unit
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clear History / Browsing Data
                IconButton(onClick = onClearDataClick) {
                    Icon(
                        painter = painterResource(R.drawable.ic_brush),
                        contentDescription = stringResource(R.string.delete_browsing_data),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // New Tab FAB
                FloatingActionButton(
                    onClick = onNewTabClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier.size(54.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.new_tab),
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Close All Tabs
                IconButton(
                    onClick = onCloseAllTabsClick,
                    enabled = hasTabs
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = if (isViewingIncognito) stringResource(R.string.close_all_incognito_tabs) else stringResource(R.string.close_all_tabs),
                        tint = if (hasTabs) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }

    private fun promptBiometricAuth(onSuccess: (() -> Unit)? = null) {
        val context = context ?: return
        val executor = ContextCompat.getMainExecutor(context)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    tabManager.isIncognitoUnlocked = true
                    if (isDismissing) return
                    onSuccess?.invoke() ?: run {
                        isViewingIncognitoState.value = true
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (isDismissing) return
                    isViewingIncognitoState.value = false
                }
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })

        val promptInfo = BiometricAuthHelper.createPromptInfo(
            title = "Unlock Incognito Tabs",
            subtitle = "Confirm identity to access private tabs"
        )
        biometricPrompt.authenticate(promptInfo)
    }

    private fun showUndoToastForTab(tab: TabItem) {
        val rawTitle = tab.title.trim()
        val displayTitle = if (rawTitle.isNotBlank() && rawTitle != "New Tab") {
            rawTitle
        } else if (tab.url.isNotBlank()) {
            try {
                val host = Uri.parse(tab.url).host
                if (!host.isNullOrBlank()) host else tab.url
            } catch (_: Exception) {
                tab.url
            }
        } else {
            getString(R.string.new_tab)
        }
        showUndoToastWithMessage(getString(R.string.tab_closed_toast, displayTitle))
    }

    private fun showUndoToastForBatch(batch: TabCoordinator.ClosedTabBatch) {
        if (batch.states.size == 1) {
            showUndoToastForTab(batch.states.first().tab)
        } else {
            showUndoToastWithMessage(getString(R.string.tabs_closed_toast, batch.states.size))
        }
    }

    private fun showUndoToastWithMessage(message: String) {
        undoDismissRunnable?.let { undoToastHandler.removeCallbacks(it) }
        undoMessageState.value = message

        val runnable = Runnable {
            dismissUndoToast(animate = true)
        }
        undoDismissRunnable = runnable
        undoToastHandler.postDelayed(runnable, 4500)
    }

    private fun dismissUndoToast(animate: Boolean) {
        undoDismissRunnable?.let {
            undoToastHandler.removeCallbacks(it)
            undoDismissRunnable = null
        }
        undoMessageState.value = null
        tabManager.finalizeAllClosedTabs()
    }

    private fun showClearBrowsingDataDialog() {
        val fm = try { parentFragmentManager } catch (_: Throwable) { null } ?: return
        if (fm.isStateSaved || fm.isDestroyed) return
        val dialog = ClearBrowsingDataDialog(
            tabManager = tabManager,
            onDataCleared = {}
        )
        dialog.show(fm, ClearBrowsingDataDialog.TAG)
    }

    private fun confirmCloseAllTabs() {
        val tabList = if (isViewingIncognitoState.value) {
            tabManager.incognitoTabs.value
        } else {
            tabManager.normalTabs.value
        }
        if (tabList.isEmpty()) {
            val ctx = context ?: return
            Toast.makeText(ctx, R.string.no_tabs_to_close, Toast.LENGTH_SHORT).show()
            return
        }
        val fm = try { parentFragmentManager } catch (_: Throwable) { null } ?: return
        if (fm.isStateSaved || fm.isDestroyed) return
        val dialog = CloseAllTabsDialog(
            tabManager = tabManager,
            isIncognito = isViewingIncognitoState.value,
            onTabsClosed = { count ->
                val batch = tabManager.peekLastClosedBatch(isViewingIncognitoState.value)
                if (batch != null) {
                    showUndoToastForBatch(batch)
                }
            }
        )
        dialog.show(fm, CloseAllTabsDialog.TAG)
    }

    override fun onDestroyView() {
        isDismissing = true
        undoDismissRunnable?.let { undoToastHandler.removeCallbacks(it) }
        undoDismissRunnable = null
        dismissUndoToast(animate = false)
        super.onDestroyView()
    }

    companion object {
        const val TAG = "TabSwitcherBottomSheet"
    }
}
