package com.onyx.browser.ui.tabs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
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
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.ui.theme.OnyxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

class TabSwitcherBottomSheet @JvmOverloads constructor(
    tabManagerParam: TabCoordinator? = TabCoordinator.activeInstance,
    private val coroutineScope: CoroutineScope? = null,
    private val onTabSelected: (TabItem) -> Unit = {},
    private val onNewTabRequested: (Boolean) -> Unit = {},
    private val onSearchRequested: (() -> Unit)? = null
) : DialogFragment() {

    private val tabManager: TabCoordinator by lazy {
        tabManagerParam ?: TabCoordinator.activeInstance ?: error("TabCoordinator activeInstance is null")
    }

    private var isDismissing = false
    private val undoToastHandler = Handler(Looper.getMainLooper())
    private var undoDismissRunnable: Runnable? = null

    // State holders for Compose
    private val isViewingIncognitoState = mutableStateOf(false)
    private val undoMessageState = mutableStateOf<String?>(null)
    private val snapshotRevision = mutableIntStateOf(0)

    fun refreshTabsList() {
        snapshotRevision.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_OnyxBrowser_TabSwitcher)
        if (TabCoordinator.activeInstance == null && tabManagerParam == null) {
            dismissAllowingStateLoss()
            return
        }
        isViewingIncognitoState.value = tabManager.activeTab.value?.isIncognito ?: false
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            val prefs = BrowserPreferences.getInstance(context)
            val isDark = when (prefs.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
            val bgColor = if (isDark) 0xFF161616.toInt() else 0xFFF1F3F4.toInt()
            setBackgroundDrawable(ColorDrawable(bgColor))
            statusBarColor = bgColor
            navigationBarColor = bgColor
            WindowCompat.setDecorFitsSystemWindows(this, false)
            val controller = WindowCompat.getInsetsController(this, decorView)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!isDismissing) {
            isDismissing = true
            val currentActive = tabManager.activeTab.value
            if (currentActive != null) {
                onTabSelected(currentActive)
            } else {
                val newTab = tabManager.createNewTab(isIncognito = isViewingIncognitoState.value)
                onTabSelected(newTab)
            }
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
                val isDark = when (prefs.themeMode) {
                    BrowserPreferences.THEME_DARK -> true
                    BrowserPreferences.THEME_LIGHT -> false
                    else -> isSystemInDarkTheme()
                }
                val accentColor = remember(prefs.accentColorHex, prefs.accentColorPreset) {
                    androidx.compose.ui.graphics.Color(AccentColorManager.getAccentColor(prefs))
                }

                OnyxTheme(darkTheme = isDark, isAmoled = false, accentColor = accentColor) {
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
        val rev = snapshotRevision.intValue

        val currentTabs = if (isViewingIncognito) incognitoTabs else normalTabs
        val prefs = remember { BrowserPreferences.getInstance(context) }
        var overflowMenuExpanded by remember { mutableStateOf(false) }
        var selectedTabForMenu by remember { mutableStateOf<TabItem?>(null) }

        Scaffold(
            topBar = {
                TabSwitcherTopBar(
                    normalCount = normalTabs.size,
                    incognitoCount = incognitoTabs.size,
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
            containerColor = MaterialTheme.colorScheme.background
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
                            val snapshot = remember(tab.id, isLocked, rev) {
                                if (isLocked) null else tabManager.getSnapshot(tab.id)
                            }

                            TabCard(
                                tab = tab,
                                isActive = isActive,
                                isLocked = isLocked,
                                snapshot = snapshot,
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
                                onTabLongClick = {
                                    if (!isDismissing && !isLocked) {
                                        selectedTabForMenu = tab
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
                                            refreshTabsList()
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

                // Tab Long-Press Context Menu ModalBottomSheet
                selectedTabForMenu?.let { tab ->
                    ModalBottomSheet(
                        onDismissRequest = { selectedTabForMenu = null },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(bottom = 16.dp)
                        ) {
                            Text(
                                text = tab.title.ifBlank { stringResource(R.string.new_tab) },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                            )
                            if (tab.url.isNotBlank()) {
                                Text(
                                    text = tab.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            // Close Tab
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.close_tab)) },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                modifier = Modifier.clickable {
                                    val t = tab
                                    selectedTabForMenu = null
                                    tabManager.closeTab(t)
                                    showUndoToastForTab(t)
                                }
                            )

                            // Close Other Tabs
                            if (currentTabs.size > 1) {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.close_other_tabs)) },
                                    leadingContent = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_delete),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        val t = tab
                                        selectedTabForMenu = null
                                        tabManager.closeOtherTabs(t)
                                        showUndoToastWithMessage(getString(R.string.other_tabs_closed))
                                    }
                                )
                            }

                            // Duplicate Tab
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.duplicate_tab)) },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                modifier = Modifier.clickable {
                                    val t = tab
                                    selectedTabForMenu = null
                                    tabManager.duplicateTab(t)
                                    refreshTabsList()
                                }
                            )

                            // Copy Link
                            if (tab.url.isNotBlank()) {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.copy_link)) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        val url = tab.url
                                        selectedTabForMenu = null
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
                                        Toast.makeText(context, R.string.url_copied, Toast.LENGTH_SHORT).show()
                                    }
                                )

                                // Share Link
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.share)) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        val url = tab.url
                                        selectedTabForMenu = null
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, url)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share)))
                                    }
                                )
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
        incognitoCount: Int,
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_incognito),
                            contentDescription = stringResource(R.string.incognito_mode),
                            tint = incognitoTint,
                            modifier = Modifier.size(18.dp)
                        )
                        if (incognitoCount > 0) {
                            Text(
                                text = if (incognitoCount > 99) "99+" else incognitoCount.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = incognitoTint
                            )
                        }
                    }
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
                    onDismissRequest = onDismissOverflow,
                    modifier = Modifier.width(220.dp),
                    shape = RoundedCornerShape(14.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    // 1. New Tab
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.new_tab),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(42.dp),
                        onClick = onNewTabClick
                    )

                    // 2. New Incognito Tab
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.new_incognito_tab),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_incognito),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(42.dp),
                        onClick = onNewIncognitoTabClick
                    )

                    // Divider
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 3.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )

                    // 3. Close All Tabs
                    val closeAllTextColor = if (hasTabs) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                    val closeAllIconColor = if (hasTabs) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (isViewingIncognito) {
                                    stringResource(R.string.close_all_incognito_tabs)
                                } else {
                                    stringResource(R.string.close_all_tabs)
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = closeAllTextColor
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_tab_close),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = closeAllIconColor
                            )
                        },
                        enabled = hasTabs,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(42.dp),
                        onClick = onCloseAllTabsClick
                    )

                    // 4. Delete Browsing Data
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.delete_browsing_data),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(42.dp),
                        onClick = onClearBrowsingDataClick
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun TabCard(
        tab: TabItem,
        isActive: Boolean,
        isLocked: Boolean,
        snapshot: Bitmap?,
        onTabClick: () -> Unit,
        onTabLongClick: () -> Unit,
        onCloseClick: () -> Unit
    ) {
        val accentColor = MaterialTheme.colorScheme.primary
        val outlineColor = if (isActive) accentColor else MaterialTheme.colorScheme.outlineVariant
        val headerColor = if (isActive) accentColor else MaterialTheme.colorScheme.surfaceVariant
        val titleColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        val closeColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

        val domain = remember(tab.url) {
            if (tab.url.isBlank()) ""
            else try {
                Uri.parse(tab.url).host ?: tab.url
            } catch (_: Exception) {
                tab.url
            }
        }

        val coroutineScope = rememberCoroutineScope()
        var dragOffsetX by remember(tab.id) { mutableFloatStateOf(0f) }
        var cardAlpha by remember(tab.id) { mutableFloatStateOf(1f) }
        var cardWidthPx by remember(tab.id) { mutableFloatStateOf(0f) }
        var isDismissingTab by remember(tab.id) { mutableStateOf(false) }
        var settleJob by remember(tab.id) { mutableStateOf<Job?>(null) }

        val draggableState = rememberDraggableState { delta ->
            settleJob?.cancel()
            dragOffsetX += delta
            val width = if (cardWidthPx > 0f) cardWidthPx else 300f
            cardAlpha = (1f - (abs(dragOffsetX) / width).coerceIn(0f, 0.7f))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .onSizeChanged { size ->
                    cardWidthPx = size.width.toFloat()
                }
                .graphicsLayer {
                    translationX = dragOffsetX
                    alpha = cardAlpha
                }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    enabled = !isDismissingTab,
                    onDragStarted = {
                        settleJob?.cancel()
                    },
                    onDragStopped = { velocity ->
                        if (isDismissingTab) return@draggable
                        val width = if (cardWidthPx > 0f) cardWidthPx else 300f
                        val dismissThreshold = width * 0.35f
                        val flingThreshold = 800f

                        val shouldDismiss = (abs(dragOffsetX) > dismissThreshold) ||
                                (abs(velocity) > flingThreshold && abs(dragOffsetX) > 20f)

                        if (shouldDismiss) {
                            isDismissingTab = true
                            val targetOffset = if (velocity > flingThreshold) {
                                width * 1.25f
                            } else if (velocity < -flingThreshold) {
                                -width * 1.25f
                            } else if (dragOffsetX > 0) {
                                width * 1.25f
                            } else {
                                -width * 1.25f
                            }

                            settleJob = coroutineScope.launch {
                                launch {
                                    animate(
                                        initialValue = cardAlpha,
                                        targetValue = 0f,
                                        animationSpec = tween(
                                            durationMillis = 180,
                                            easing = LinearEasing
                                        )
                                    ) { value, _ ->
                                        cardAlpha = value
                                    }
                                }
                                animate(
                                    initialValue = dragOffsetX,
                                    targetValue = targetOffset,
                                    initialVelocity = velocity,
                                    animationSpec = tween(
                                        durationMillis = 180,
                                        easing = FastOutLinearInEasing
                                    )
                                ) { value, _ ->
                                    dragOffsetX = value
                                }
                                onCloseClick()
                            }
                        } else {
                            settleJob = coroutineScope.launch {
                                launch {
                                    animate(
                                        initialValue = cardAlpha,
                                        targetValue = 1f,
                                        animationSpec = spring(
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    ) { value, _ ->
                                        cardAlpha = value
                                    }
                                }
                                animate(
                                    initialValue = dragOffsetX,
                                    targetValue = 0f,
                                    initialVelocity = velocity,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                ) { value, _ ->
                                    dragOffsetX = value
                                }
                            }
                        }
                    }
                )
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isActive) 2.5.dp else 1.dp,
                    color = outlineColor
                ),
                shadowElevation = if (isActive) 4.dp else 1.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClick = {
                            if (!isDismissingTab) onTabClick()
                        },
                        onLongClick = {
                            if (!isDismissingTab) onTabLongClick()
                        }
                    )
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
                                text = if (isLocked) "Protected Tab" else tab.title.ifBlank { stringResource(R.string.new_tab) },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = titleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!isLocked && domain.isNotBlank()) {
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
                            onClick = {
                                if (!isDismissingTab) {
                                    isDismissingTab = true
                                    onCloseClick()
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.close_tab),
                                tint = closeColor,
                                modifier = Modifier.size(18.dp)
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
                                painter = painterResource(R.drawable.ic_app_emblem),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                modifier = Modifier.size(48.dp)
                            )
                        }
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
                    if (isViewingIncognito) R.drawable.ic_incognito else R.drawable.ic_app_emblem
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clear History / Browsing Data — Solid squircle button (44dp x 44dp, radius 12dp)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onClearDataClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.delete_browsing_data),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // New Tab FAB — Solid circular FAB (48dp x 48dp) with primary accent color
                FloatingActionButton(
                    onClick = onNewTabClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 2.dp,
                        pressedElevation = 4.dp
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.new_tab),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Close All Tabs — Solid squircle button (44dp x 44dp, radius 12dp)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (hasTabs) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                        .then(
                            if (hasTabs) Modifier.clickable(onClick = onCloseAllTabsClick)
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tab_close),
                        contentDescription = if (isViewingIncognito) {
                            stringResource(R.string.close_all_incognito_tabs)
                        } else {
                            stringResource(R.string.close_all_tabs)
                        },
                        tint = if (hasTabs) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                        modifier = Modifier.size(22.dp)
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
