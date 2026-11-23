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
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.onyx.browser.ui.home.rememberFavicon
import com.onyx.browser.web.LocalFileLoader
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onyx.browser.R
import com.onyx.browser.data.model.TabGroupItem
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.browser.TabCoordinator
import com.onyx.browser.ui.common.BiometricAuthHelper
import com.onyx.browser.ui.components.AppAlertDialog
import com.onyx.browser.ui.components.RadioPreference
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.ui.theme.OnyxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

class TabSwitcherBottomSheet @JvmOverloads constructor(
    tabManager: TabCoordinator? = TabCoordinator.activeInstance,
    private val coroutineScope: CoroutineScope? = null,
    private val onTabSelected: (TabItem) -> Unit = {},
    private val onNewTabRequested: (Boolean) -> Unit = {},
    private val onSearchRequested: (() -> Unit)? = null,
    private val initialOpenedGroupId: String? = null,
    private val isDirectGroupPreviewOnly: Boolean = false
) : DialogFragment() {

    private val tabManager: TabCoordinator by lazy {
        tabManager ?: TabCoordinator.activeInstance ?: error("TabCoordinator activeInstance is null")
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
        if (TabCoordinator.activeInstance == null) {
            dismissAllowingStateLoss()
            return
        }
        isViewingIncognitoState.value = this.tabManager.activeTab.value?.isIncognito ?: false
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
            val bgColor = if (isDirectGroupPreviewOnly) {
                android.graphics.Color.TRANSPARENT
            } else if (isDark) {
                0xFF161616.toInt()
            } else {
                0xFFF1F3F4.toInt()
            }
            setBackgroundDrawable(ColorDrawable(bgColor))
            statusBarColor = if (isDirectGroupPreviewOnly) android.graphics.Color.TRANSPARENT else bgColor
            navigationBarColor = if (isDirectGroupPreviewOnly) android.graphics.Color.TRANSPARENT else bgColor
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
        tabManager.autoArchiveIdleTabs()
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

    private fun parseHexColor(hex: String, fallback: Color): Color {
        return try {
            val formatted = if (hex.startsWith("#")) hex else "#$hex"
            Color(android.graphics.Color.parseColor(formatted))
        } catch (_: Exception) {
            fallback
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
        val archivedTabs by tabManager.archivedTabs.collectAsStateWithLifecycle()
        val tabGroups by tabManager.tabGroups.collectAsStateWithLifecycle()
        val activeTab by tabManager.activeTab.collectAsStateWithLifecycle()

        var isViewingIncognito by isViewingIncognitoState
        val undoMessage by undoMessageState
        val rev = snapshotRevision.intValue

        val currentTabs = if (isViewingIncognito) incognitoTabs else normalTabs
        val prefs = remember { BrowserPreferences.getInstance(context) }

        var overflowMenuExpanded by remember { mutableStateOf(false) }
        var selectedTabForMenu by remember { mutableStateOf<TabItem?>(null) }
        var selectedTabForMoveGroup by remember { mutableStateOf<TabItem?>(null) }
        var openedGroupId by remember { mutableStateOf(initialOpenedGroupId) }
        var groupForAddingTabs by remember { mutableStateOf<TabGroupItem?>(null) }
        var showArchivedSheet by remember { mutableStateOf(false) }
        var showAutoArchiveConfigSheet by remember { mutableStateOf(false) }
        var autoArchiveHoursState by remember { mutableIntStateOf(prefs.tabAutoArchiveHours) }

        // Group creation / editing dialog state
        var showGroupEditDialog by remember { mutableStateOf(false) }
        var editingGroup by remember { mutableStateOf<TabGroupItem?>(null) }
        var initialTabForNewGroup by remember { mutableStateOf<TabItem?>(null) }

        // Group options bottom sheet state
        var selectedGroupForOptions by remember { mutableStateOf<TabGroupItem?>(null) }

        // Close group preview if the opened group was deleted or user switched to incognito
        LaunchedEffect(tabGroups, isViewingIncognito) {
            if (isViewingIncognito || (openedGroupId != null && tabGroups.none { it.id == openedGroupId })) {
                openedGroupId = null
                if (isDirectGroupPreviewOnly && !isDismissing) {
                    isDismissing = true
                    dismissAllowingStateLoss()
                }
            }
        }

        val groupMap = remember(tabGroups) { tabGroups.associateBy { it.id } }
        val openedGroup = openedGroupId?.let { groupMap[it] }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!isDirectGroupPreviewOnly) {
            Scaffold(
                topBar = {
                    TabSwitcherTopBar(
                        normalCount = normalTabs.size,
                        incognitoCount = incognitoTabs.size,
                        archivedCount = archivedTabs.size,
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
                        onNewTabGroupClick = {
                            overflowMenuExpanded = false
                            editingGroup = null
                            initialTabForNewGroup = null
                            showGroupEditDialog = true
                        },
                        onArchivedTabsClick = {
                            overflowMenuExpanded = false
                            showArchivedSheet = true
                        },
                        onAutoArchiveSettingsClick = {
                            overflowMenuExpanded = false
                            autoArchiveHoursState = prefs.tabAutoArchiveHours
                            showAutoArchiveConfigSheet = true
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    val ungroupedTabs = remember(currentTabs, tabGroups, isViewingIncognito) {
                        if (isViewingIncognito) {
                            currentTabs
                        } else {
                            val validGroupIds = tabGroups.map { it.id }.toSet()
                            currentTabs.filter { it.groupId == null || it.groupId !in validGroupIds }
                        }
                    }

                    val hasVisibleContent = if (isViewingIncognito) {
                        currentTabs.isNotEmpty()
                    } else {
                        currentTabs.isNotEmpty() || tabGroups.isNotEmpty()
                    }

                    if (!hasVisibleContent) {
                        EmptyTabsView(
                            isViewingIncognito = isViewingIncognito,
                            archivedCount = if (isViewingIncognito) 0 else archivedTabs.size,
                            onOpenArchived = { showArchivedSheet = true }
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(spanCount),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            if (!isViewingIncognito) {
                                items(tabGroups, key = { "group_card_${it.id}" }) { group ->
                                    val groupTabs = remember(currentTabs, group.id) {
                                        currentTabs.filter { it.groupId == group.id }
                                    }
                                    val isGroupActive = remember(groupTabs, activeTab?.id) {
                                        groupTabs.any { it.id == activeTab?.id }
                                    }
                                    TabGroupCard(
                                        group = group,
                                        groupTabs = groupTabs,
                                        isGroupActive = isGroupActive,
                                        snapshotRevision = rev,
                                        onGroupClick = {
                                            openedGroupId = group.id
                                        },
                                        onGroupOptionsClick = {
                                            selectedGroupForOptions = group
                                        },
                                        onCloseGroup = {
                                            if (isDismissing) return@TabGroupCard
                                            val closedCount = tabManager.closeTabGroup(group.id, closeTabs = true)
                                            if (closedCount > 0) {
                                                val batch = tabManager.peekLastClosedBatch(false)
                                                if (batch != null) {
                                                    showUndoToastForBatch(batch)
                                                }
                                            }
                                            refreshTabsList()
                                        }
                                    )
                                }
                            }

                            items(ungroupedTabs, key = { it.id }) { tab ->
                                val isLocked = tab.isIncognito && prefs.isBiometricIncognitoEnabled && !tabManager.isIncognitoUnlocked
                                val isActive = tab.id == activeTab?.id
                                val snapshot = remember(tab.id, isLocked, rev) {
                                    if (isLocked) null else tabManager.getSnapshot(tab.id)
                                }

                                TabCard(
                                    tab = tab,
                                    group = null,
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
                }
            }
            }

            // Full-screen Tab Group Preview Overlay (2nd image: header with back + color dot + title + 3-dot, grid of group tabs, bottom-center + FAB)
            if (!isViewingIncognito && openedGroup != null) {
                val groupTabs = remember(normalTabs, openedGroup.id) {
                    normalTabs.filter { it.groupId == openedGroup.id }
                }
                TabGroupPreviewOverlay(
                    group = openedGroup,
                    groupTabs = groupTabs,
                    activeTabId = activeTab?.id,
                    spanCount = spanCount,
                    snapshotRevision = rev,
                    onBack = {
                        if (isDirectGroupPreviewOnly) {
                            if (!isDismissing) {
                                isDismissing = true
                                dismissAllowingStateLoss()
                            }
                        } else {
                            openedGroupId = null
                        }
                    },
                    onEditGroupClick = {
                        editingGroup = openedGroup
                        initialTabForNewGroup = null
                        showGroupEditDialog = true
                    },
                    onGroupOptionsClick = {
                        selectedGroupForOptions = openedGroup
                    },
                    onTabClick = { tab ->
                        if (!isDismissing) {
                            isDismissing = true
                            tabManager.selectTab(tab)
                            onTabSelected(tab)
                            dismissAllowingStateLoss()
                        }
                    },
                    onTabLongClick = { tab ->
                        if (!isDismissing) {
                            selectedTabForMenu = tab
                        }
                    },
                    onCloseTabClick = { tab ->
                        if (!isDismissing) {
                            val wasLastInGroup = groupTabs.size <= 1
                            tabManager.closeTab(tab)
                            showUndoToastForTab(tab)
                            if (wasLastInGroup) {
                                if (isDirectGroupPreviewOnly) {
                                    isDismissing = true
                                    dismissAllowingStateLoss()
                                } else {
                                    openedGroupId = null
                                }
                            }
                        }
                    },
                    onAddTabInGroup = {
                        if (!isDismissing) {
                            isDismissing = true
                            val newTab = tabManager.createNewTab(
                                isIncognito = false,
                                groupId = openedGroup.id
                            )
                            onTabSelected(newTab)
                            dismissAllowingStateLoss()
                        }
                    }
                )
            }

                // Floating Undo Toast (Compact, sleek pill matching old branch bg_undo_toast)
                AnimatedVisibility(
                    visible = undoMessage != null,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(220)),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(180)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp, start = 16.dp, end = 16.dp)
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                ) {
                    undoMessage?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = colorResource(R.color.tab_card_header_bg),
                            border = BorderStroke(1.dp, colorResource(R.color.tab_card_stroke)),
                            shadowElevation = 8.dp,
                            tonalElevation = 0.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = colorResource(R.color.tab_text_primary),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
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
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.undo),
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Tab Long-Press Context Menu ModalBottomSheet
                selectedTabForMenu?.let { tab ->
                    val currentGroup = tab.groupId?.let { groupMap[it] }
                    ModalBottomSheet(
                        onDismissRequest = { selectedTabForMenu = null },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
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

                            // Group actions (for normal tabs)
                            if (!tab.isIncognito) {
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            if (currentGroup != null) "Change tab group (${currentGroup.title})"
                                            else stringResource(R.string.move_to_group)
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        val t = tab
                                        selectedTabForMenu = null
                                        selectedTabForMoveGroup = t
                                    }
                                )

                                if (currentGroup != null) {
                                    ListItem(
                                        headlineContent = { Text(stringResource(R.string.remove_from_group)) },
                                        leadingContent = {
                                            Icon(
                                                imageVector = Icons.Default.FolderOff,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        modifier = Modifier.clickable {
                                            val t = tab
                                            selectedTabForMenu = null
                                            tabManager.assignTabToGroup(t.id, null)
                                            refreshTabsList()
                                        }
                                    )
                                }

                                // Archive Tab
                                if (tab.url.isNotBlank() && !tab.url.equals("about:blank", ignoreCase = true)) {
                                    ListItem(
                                        headlineContent = { Text(stringResource(R.string.archive_tab)) },
                                        leadingContent = {
                                            Icon(
                                                imageVector = Icons.Default.Archive,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        modifier = Modifier.clickable {
                                            val t = tab
                                            selectedTabForMenu = null
                                            tabManager.archiveTab(t)
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.tab_archived_toast, t.title.ifBlank { "Tab" }),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            refreshTabsList()
                                        }
                                    )
                                }
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
                        }
                    }
                }

                // Move Tab to Group Picker Sheet
                selectedTabForMoveGroup?.let { targetTab ->
                    MoveTabToGroupBottomSheet(
                        tab = targetTab,
                        groups = tabGroups,
                        onSelectGroup = { group ->
                            tabManager.assignTabToGroup(targetTab.id, group?.id)
                            selectedTabForMoveGroup = null
                            refreshTabsList()
                        },
                        onCreateNewGroupWithTab = {
                            val t = targetTab
                            selectedTabForMoveGroup = null
                            editingGroup = null
                            initialTabForNewGroup = t
                            showGroupEditDialog = true
                        },
                        onDismiss = { selectedTabForMoveGroup = null }
                    )
                }

                // Group Options Bottom Sheet (Edit, New Tab in Group, Add Existing Tabs, Ungroup, Close Group)
                selectedGroupForOptions?.let { group ->
                    val availableToAddCount = normalTabs.count { it.groupId != group.id }
                    TabGroupOptionsBottomSheet(
                        group = group,
                        tabCount = normalTabs.count { it.groupId == group.id },
                        hasAvailableTabsToAdd = availableToAddCount > 0,
                        onNewTabInGroup = {
                            selectedGroupForOptions = null
                            if (!isDismissing) {
                                isDismissing = true
                                val newTab = tabManager.createNewTab(
                                    isIncognito = false,
                                    groupId = group.id
                                )
                                onTabSelected(newTab)
                                dismissAllowingStateLoss()
                            }
                        },
                        onAddExistingTabsToGroup = {
                            val g = group
                            selectedGroupForOptions = null
                            groupForAddingTabs = g
                        },
                        onEditGroup = {
                            val g = group
                            selectedGroupForOptions = null
                            editingGroup = g
                            initialTabForNewGroup = null
                            showGroupEditDialog = true
                        },
                        onUngroup = {
                            val g = group
                            selectedGroupForOptions = null
                            if (openedGroupId == g.id) {
                                openedGroupId = null
                            }
                            tabManager.ungroupAllInGroup(g.id)
                            refreshTabsList()
                        },
                        onCloseGroup = {
                            val g = group
                            selectedGroupForOptions = null
                            if (openedGroupId == g.id) {
                                openedGroupId = null
                            }
                            val closedCount = tabManager.closeTabGroup(g.id, closeTabs = true)
                            if (closedCount > 0) {
                                val batch = tabManager.peekLastClosedBatch(false)
                                if (batch != null) {
                                    showUndoToastForBatch(batch)
                                }
                            }
                            refreshTabsList()
                        },
                        onDismiss = { selectedGroupForOptions = null }
                    )
                }

                // Add Existing Tabs to Group Dialog
                groupForAddingTabs?.let { targetGroup ->
                    val candidateTabs = remember(normalTabs, targetGroup.id) {
                        normalTabs.filter { it.groupId != targetGroup.id }
                    }
                    AddTabsToGroupDialog(
                        group = targetGroup,
                        candidateTabs = candidateTabs,
                        onConfirm = { selectedTabIds ->
                            selectedTabIds.forEach { tabId ->
                                tabManager.assignTabToGroup(tabId, targetGroup.id)
                            }
                            groupForAddingTabs = null
                            refreshTabsList()
                        },
                        onDismiss = { groupForAddingTabs = null }
                    )
                }

                // Create / Edit Tab Group Dialog
                if (showGroupEditDialog) {
                    CreateOrEditTabGroupDialog(
                        existingGroup = editingGroup,
                        normalTabs = normalTabs,
                        initialTabForNewGroup = initialTabForNewGroup,
                        onConfirm = { title, colorHex, selectedTabIds ->
                            val existing = editingGroup
                            if (existing != null) {
                                tabManager.updateTabGroup(existing.id, title, colorHex)
                            } else {
                                val createdGroup = tabManager.createTabGroup(title, colorHex, selectedTabIds)
                                if (selectedTabIds.isEmpty()) {
                                    tabManager.createNewTab(
                                        isIncognito = false,
                                        groupId = createdGroup.id
                                    )
                                }
                            }
                            showGroupEditDialog = false
                            editingGroup = null
                            initialTabForNewGroup = null
                            refreshTabsList()
                        },
                        onDismiss = {
                            showGroupEditDialog = false
                            editingGroup = null
                            initialTabForNewGroup = null
                        }
                    )
                }

                // Archived Tabs Bottom Sheet
                if (showArchivedSheet) {
                    ArchivedTabsBottomSheet(
                        archivedTabs = archivedTabs,
                        autoArchiveLabel = prefs.getTabAutoArchiveDisplayLabel(),
                        onRestoreTab = { tab, openNow ->
                            val restored = tabManager.unarchiveTab(tab, makeActive = openNow)
                            if (openNow && !isDismissing) {
                                showArchivedSheet = false
                                isDismissing = true
                                onTabSelected(restored)
                                dismissAllowingStateLoss()
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.tab_restored_toast, restored.title.ifBlank { "Tab" }),
                                    Toast.LENGTH_SHORT
                                ).show()
                                refreshTabsList()
                            }
                        },
                        onRestoreAll = {
                            tabManager.unarchiveAllTabs()
                            showArchivedSheet = false
                            refreshTabsList()
                        },
                        onDeleteArchivedTab = { tab ->
                            tabManager.closeTab(tab)
                            showUndoToastForTab(tab)
                        },
                        onClearAllArchived = {
                            val count = archivedTabs.size
                            tabManager.closeAllArchivedTabs(canUndo = true)
                            showArchivedSheet = false
                            if (count > 0) {
                                val batch = tabManager.peekLastClosedBatch(false)
                                if (batch != null) showUndoToastForBatch(batch)
                            }
                        },
                        onConfigureAutoArchive = {
                            autoArchiveHoursState = prefs.tabAutoArchiveHours
                            showAutoArchiveConfigSheet = true
                        },
                        onDismiss = { showArchivedSheet = false }
                    )
                }

                // Auto-Archive Threshold Configuration Sheet
                if (showAutoArchiveConfigSheet) {
                    AutoArchiveConfigBottomSheet(
                        selectedHours = autoArchiveHoursState,
                        onSelectHours = { hours ->
                            autoArchiveHoursState = hours
                            prefs.tabAutoArchiveHours = hours
                            val newlyArchived = tabManager.autoArchiveIdleTabs()
                            showAutoArchiveConfigSheet = false
                            if (newlyArchived > 0) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.tabs_archived_toast, newlyArchived),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            refreshTabsList()
                        },
                        onDismiss = { showAutoArchiveConfigSheet = false }
                    )
                }
        }
    }

    /**
     * Grid card representing a Tab Group with a 2x2 thumbnail preview of its member tabs
     * (matching the 1st reference image).
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun TabGroupCard(
        group: TabGroupItem,
        groupTabs: List<TabItem>,
        isGroupActive: Boolean,
        snapshotRevision: Int,
        onGroupClick: () -> Unit,
        onGroupOptionsClick: () -> Unit,
        onCloseGroup: () -> Unit
    ) {
        val accentColor = MaterialTheme.colorScheme.primary
        val groupColor = parseHexColor(group.colorHex, Color(0xFF8AB4F8))
        val containerBgColor = if (isGroupActive) accentColor else MaterialTheme.colorScheme.surfaceVariant
        val titleColor = if (isGroupActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        val outlineColor = if (isGroupActive) accentColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        val emptySlotColor = if (isGroupActive) {
            Color.White.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        }

        val coroutineScope = rememberCoroutineScope()
        var dragOffsetX by remember(group.id) { mutableFloatStateOf(0f) }
        var cardAlpha by remember(group.id) { mutableFloatStateOf(1f) }
        var cardWidthPx by remember(group.id) { mutableFloatStateOf(0f) }
        var isDismissingGroup by remember(group.id) { mutableStateOf(false) }
        var settleJob by remember(group.id) { mutableStateOf<Job?>(null) }

        val draggableState = rememberDraggableState { delta ->
            settleJob?.cancel()
            dragOffsetX += delta
            val width = if (cardWidthPx > 0f) cardWidthPx else 300f
            cardAlpha = (1f - (abs(dragOffsetX) / width).coerceIn(0f, 0.7f))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
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
                    enabled = !isDismissingGroup,
                    onDragStarted = {
                        settleJob?.cancel()
                    },
                    onDragStopped = { velocity ->
                        if (isDismissingGroup) return@draggable
                        val width = if (cardWidthPx > 0f) cardWidthPx else 300f
                        val dismissThreshold = width * 0.35f
                        val flingThreshold = 800f

                        val shouldDismiss = (abs(dragOffsetX) > dismissThreshold) ||
                                (abs(velocity) > flingThreshold && abs(dragOffsetX) > 20f)

                        if (shouldDismiss) {
                            isDismissingGroup = true
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
                                        animationSpec = tween(durationMillis = 180, easing = LinearEasing)
                                    ) { value, _ -> cardAlpha = value }
                                }
                                animate(
                                    initialValue = dragOffsetX,
                                    targetValue = targetOffset,
                                    initialVelocity = velocity,
                                    animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing)
                                ) { value, _ -> dragOffsetX = value }
                                onCloseGroup()
                            }
                        } else {
                            settleJob = coroutineScope.launch {
                                launch {
                                    animate(
                                        initialValue = cardAlpha,
                                        targetValue = 1f,
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                    ) { value, _ -> cardAlpha = value }
                                }
                                animate(
                                    initialValue = dragOffsetX,
                                    targetValue = 0f,
                                    initialVelocity = velocity,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                ) { value, _ -> dragOffsetX = value }
                            }
                        }
                    }
                )
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = containerBgColor,
                border = BorderStroke(
                    width = if (isGroupActive) 2.dp else 1.dp,
                    color = outlineColor
                ),
                shadowElevation = if (isGroupActive) 4.dp else 0.dp,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .combinedClickable(
                        onClick = {
                            if (!isDismissingGroup) onGroupClick()
                        },
                        onLongClick = {
                            if (!isDismissingGroup) onGroupOptionsClick()
                        }
                    )
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Group Header Row (Color Dot with White Ring + Title + 3-dot Menu)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(groupColor)
                                .border(2.dp, Color.White, CircleShape)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = titleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = {
                                if (!isDismissingGroup) onGroupOptionsClick()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Group options",
                                tint = titleColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // 2x2 Mini Thumbnail Preview Grid
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (row in 0..1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (col in 0..1) {
                                    val slotIndex = row * 2 + col
                                    val slotTab = groupTabs.getOrNull(slotIndex)
                                    val extraCount = if (slotIndex == 3 && groupTabs.size > 4) {
                                        groupTabs.size - 3
                                    } else {
                                        0
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (slotTab != null) MaterialTheme.colorScheme.surface
                                                else emptySlotColor
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (slotTab != null) {
                                            val miniSnapshot = remember(slotTab.id, snapshotRevision) {
                                                tabManager.getSnapshot(slotTab.id)
                                            }
                                            if (miniSnapshot != null) {
                                                Image(
                                                    bitmap = miniSnapshot.asImageBitmap(),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    alignment = Alignment.TopCenter,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                val miniFavicon = rememberFavicon(slotTab.url)
                                                if (miniFavicon != null) {
                                                    Image(
                                                        bitmap = miniFavicon,
                                                        contentDescription = null,
                                                        modifier = Modifier
                                                            .size(22.dp)
                                                            .clip(CircleShape)
                                                    )
                                                } else {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_app_emblem),
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                            }

                                            if (extraCount > 0) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.55f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "+$extraCount",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Full-screen overlay showing all tabs inside a selected Tab Group with a bottom-center '+' FAB
     * to create a new tab directly in that group (matching the 2nd reference image).
     */
    @Composable
    private fun TabGroupPreviewOverlay(
        group: TabGroupItem,
        groupTabs: List<TabItem>,
        activeTabId: String?,
        spanCount: Int,
        snapshotRevision: Int,
        onBack: () -> Unit,
        onEditGroupClick: () -> Unit,
        onGroupOptionsClick: () -> Unit,
        onTabClick: (TabItem) -> Unit,
        onTabLongClick: (TabItem) -> Unit,
        onCloseTabClick: (TabItem) -> Unit,
        onAddTabInGroup: () -> Unit
    ) {
        BackHandler(onBack = onBack)

        val groupColor = parseHexColor(group.colorHex, Color(0xFF8AB4F8))
        val isDark = isSystemInDarkTheme()
        val containerColor = if (isDark) Color(0xFF212124) else MaterialTheme.colorScheme.surface
        val fabBgColor = if (isDark) Color(0xFF171717) else MaterialTheme.colorScheme.surfaceVariant

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onBack
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 6.dp, vertical = 14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = containerColor,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)),
                shadowElevation = 16.dp,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(28.dp))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top Header Bar: Back Arrow | Centered Color Circle + Group Title | 3-dot Menu
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(onClick = onEditGroupClick)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(groupColor)
                                )

                                Spacer(modifier = Modifier.width(10.dp))

                                Text(
                                    text = group.title,
                                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = onGroupOptionsClick,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Group options",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        if (groupTabs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No tabs in this group. Tap + below to add one.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(spanCount),
                                contentPadding = PaddingValues(
                                    start = 14.dp,
                                    end = 14.dp,
                                    top = 6.dp,
                                    bottom = 92.dp
                                ),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                items(groupTabs, key = { "preview_${it.id}" }) { tab ->
                                    val isActive = tab.id == activeTabId
                                    val snapshot = remember(tab.id, snapshotRevision) {
                                        tabManager.getSnapshot(tab.id)
                                    }
                                    TabCard(
                                        tab = tab,
                                        group = null,
                                        isActive = isActive,
                                        isLocked = false,
                                        snapshot = snapshot,
                                        onTabClick = { onTabClick(tab) },
                                        onTabLongClick = { onTabLongClick(tab) },
                                        onCloseClick = { onCloseTabClick(tab) }
                                    )
                                }
                            }
                        }
                    }

                    // Bottom-Center Circular '+' Button to create a new tab inside this group
                    Surface(
                        shape = CircleShape,
                        color = fabBgColor,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                        shadowElevation = 8.dp,
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 18.dp)
                            .size(56.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onAddTabInGroup)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = "New tab in group",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
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
        archivedCount: Int,
        isViewingIncognito: Boolean,
        onToggleIncognito: (Boolean) -> Unit,
        onSearchClick: () -> Unit,
        onOverflowClick: () -> Unit,
        overflowMenuExpanded: Boolean,
        onDismissOverflow: () -> Unit,
        onNewTabClick: () -> Unit,
        onNewIncognitoTabClick: () -> Unit,
        onNewTabGroupClick: () -> Unit,
        onArchivedTabsClick: () -> Unit,
        onAutoArchiveSettingsClick: () -> Unit,
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
            IconButton(
                onClick = onSearchClick,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = stringResource(R.string.search),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Normal / Incognito Segmented Control (Square buttons with smooth corners, equal size)
            Row(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Normal Tabs Button (Square with smooth corners)
                val normalBg = if (!isViewingIncognito) MaterialTheme.colorScheme.primary else Color.Transparent
                val normalContentColor = if (!isViewingIncognito) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(normalBg)
                        .clickable { onToggleIncognito(false) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .border(1.5.dp, normalContentColor, RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (normalCount > 99) "99+" else normalCount.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = FontWeight.Bold,
                            color = normalContentColor
                        )
                    }
                }

                // Incognito Tabs Button (Square with smooth corners, equal size, no tab count)
                val incognitoBg = if (isViewingIncognito) MaterialTheme.colorScheme.primary else Color.Transparent
                val incognitoContentColor = if (isViewingIncognito) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(incognitoBg)
                        .clickable { onToggleIncognito(true) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_incognito),
                        contentDescription = stringResource(R.string.incognito_mode),
                        tint = incognitoContentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Overflow Menu
            Box {
                IconButton(
                    onClick = onOverflowClick,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }

                DropdownMenu(
                    expanded = overflowMenuExpanded,
                    onDismissRequest = onDismissOverflow,
                    modifier = Modifier.width(235.dp),
                    shape = RoundedCornerShape(14.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
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

                    if (!isViewingIncognito) {
                        // 3. New Tab Group
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.new_tab_group),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.height(42.dp),
                            onClick = onNewTabGroupClick
                        )

                        // 4. Archived Tabs
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (archivedCount > 0) "Archived Tabs ($archivedCount)" else stringResource(R.string.archived_tabs),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Archive,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.height(42.dp),
                            onClick = onArchivedTabsClick
                        )

                        // 5. Auto-Archive Settings
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.auto_archive_settings),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.height(42.dp),
                            onClick = onAutoArchiveSettingsClick
                        )
                    }

                    // Divider
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 3.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )

                    // 6. Close All Tabs
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

                    // 7. Delete Browsing Data
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
        group: TabGroupItem? = null,
        isActive: Boolean,
        isLocked: Boolean,
        snapshot: Bitmap?,
        onTabClick: () -> Unit,
        onTabLongClick: () -> Unit,
        onCloseClick: () -> Unit
    ) {
        val accentColor = MaterialTheme.colorScheme.primary
        val groupColor = group?.let { parseHexColor(it.colorHex, accentColor) }
        val outlineColor = when {
            isActive -> accentColor
            groupColor != null -> groupColor.copy(alpha = 0.65f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        }
        val headerColor = if (isActive) accentColor else MaterialTheme.colorScheme.surfaceVariant
        val titleColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        val closeColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

        val context = LocalContext.current
        val faviconBitmap = if (!isLocked && !tab.isIncognito) rememberFavicon(tab.url) else null
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
                .height(230.dp)
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
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isActive) 2.dp else 1.dp,
                    color = outlineColor
                ),
                shadowElevation = if (isActive) 4.dp else 0.dp,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
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
                    // Tab Header (Fixed 42dp height matching screenshot and standard proportions)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .background(headerColor)
                            .padding(start = 12.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Group color indicator dot when tab is in a group
                        if (groupColor != null && !isActive) {
                            Box(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(groupColor)
                            )
                        }

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
                        } else if (faviconBitmap != null) {
                            Image(
                                bitmap = faviconBitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            val isLocal = remember(tab.url) {
                                LocalFileLoader.isLocalFile(context, tab.url) ||
                                LocalFileLoader.isPreviewUrl(tab.url)
                            }
                            Icon(
                                painter = painterResource(if (isLocal) R.drawable.ic_file else R.drawable.ic_web),
                                contentDescription = null,
                                tint = closeColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Title (Single line, clear & medium bold)
                        Text(
                            text = if (isLocked) "Protected Tab" else tab.title.ifBlank { stringResource(R.string.new_tab) },
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = titleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

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
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.close_tab),
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
                            .background(MaterialTheme.colorScheme.surface),
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
                                alignment = Alignment.TopCenter,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_app_emblem),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                    modifier = Modifier.size(44.dp)
                                )
                                if (domain.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = domain,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
    private fun EmptyTabsView(
        isViewingIncognito: Boolean,
        archivedCount: Int = 0,
        onOpenArchived: () -> Unit = {}
    ) {
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

            if (!isViewingIncognito && archivedCount > 0) {
                Spacer(modifier = Modifier.height(18.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onOpenArchived)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Archive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "View $archivedCount archived tab${if (archivedCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
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

    @Composable
    private fun CreateOrEditTabGroupDialog(
        existingGroup: TabGroupItem?,
        normalTabs: List<TabItem> = emptyList(),
        initialTabForNewGroup: TabItem? = null,
        onConfirm: (title: String, colorHex: String, selectedTabIds: List<String>) -> Unit,
        onDismiss: () -> Unit
    ) {
        var titleInput by remember(existingGroup) {
            mutableStateOf(existingGroup?.title ?: "")
        }
        var selectedColorHex by remember(existingGroup) {
            mutableStateOf(existingGroup?.colorHex ?: TabGroupItem.COLOR_BLUE)
        }
        val selectableTabs = remember(existingGroup, normalTabs) {
            if (existingGroup != null) emptyList() else normalTabs
        }
        val selectedTabIds = remember(existingGroup, initialTabForNewGroup, selectableTabs) {
            val initialIds = when {
                existingGroup != null -> emptyList()
                initialTabForNewGroup != null -> listOf(initialTabForNewGroup.id)
                else -> selectableTabs.filter { it.groupId == null }.map { it.id }
            }
            mutableStateListOf<String>().apply { addAll(initialIds) }
        }

        AppAlertDialog(
            title = if (existingGroup != null) stringResource(R.string.edit_tab_group) else stringResource(R.string.new_tab_group),
            confirmButtonText = if (existingGroup != null) stringResource(R.string.save) else "Create",
            onConfirm = {
                onConfirm(titleInput.trim().ifBlank { "Tab Group" }, selectedColorHex, selectedTabIds.toList())
            },
            onDismissRequest = onDismiss,
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        placeholder = { Text("Tab Group") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "GROUP COLOR",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TabGroupItem.GROUP_COLORS.forEach { hex ->
                            val swatchColor = parseHexColor(hex, MaterialTheme.colorScheme.primary)
                            val isSelected = selectedColorHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable { selectedColorHex = hex },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (existingGroup == null && selectableTabs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TABS IN GROUP (${selectedTabIds.size})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val allSelected = selectedTabIds.size == selectableTabs.size
                            Text(
                                text = if (allSelected) "Clear all" else "Select all",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        if (allSelected) {
                                            selectedTabIds.clear()
                                        } else {
                                            selectedTabIds.clear()
                                            selectedTabIds.addAll(selectableTabs.map { it.id })
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 190.dp)
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            selectableTabs.forEach { tab ->
                                val isChecked = tab.id in selectedTabIds
                                val favicon = rememberFavicon(tab.url)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isChecked) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (isChecked) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    ),
                                    tonalElevation = 0.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (isChecked) selectedTabIds.remove(tab.id)
                                            else selectedTabIds.add(tab.id)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (favicon != null) {
                                            Image(
                                                bitmap = favicon,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                            )
                                        } else {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_web),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Text(
                                            text = tab.title.ifBlank { tab.url.ifBlank { stringResource(R.string.new_tab) } },
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                            fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    @Composable
    private fun AddTabsToGroupDialog(
        group: TabGroupItem,
        candidateTabs: List<TabItem>,
        onConfirm: (selectedTabIds: List<String>) -> Unit,
        onDismiss: () -> Unit
    ) {
        val selectedTabIds = remember(candidateTabs) {
            mutableStateListOf<String>()
        }

        AppAlertDialog(
            title = "Add Tabs to ${group.title}",
            confirmButtonText = "Add",
            onConfirm = {
                onConfirm(selectedTabIds.toList())
            },
            onDismissRequest = onDismiss,
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (candidateTabs.isEmpty()) {
                        Text(
                            text = "All open tabs are already in this group.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            candidateTabs.forEach { tab ->
                                val isChecked = tab.id in selectedTabIds
                                val favicon = rememberFavicon(tab.url)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isChecked) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (isChecked) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    ),
                                    tonalElevation = 0.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (isChecked) selectedTabIds.remove(tab.id)
                                            else selectedTabIds.add(tab.id)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (favicon != null) {
                                            Image(
                                                bitmap = favicon,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                            )
                                        } else {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_web),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Text(
                                            text = tab.title.ifBlank { tab.url.ifBlank { stringResource(R.string.new_tab) } },
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                            fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MoveTabToGroupBottomSheet(
        tab: TabItem,
        groups: List<TabGroupItem>,
        onSelectGroup: (TabGroupItem?) -> Unit,
        onCreateNewGroupWithTab: () -> Unit,
        onDismiss: () -> Unit
    ) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
            ) {
                Text(
                    text = stringResource(R.string.move_to_group),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )

                Text(
                    text = tab.title.ifBlank { stringResource(R.string.new_tab) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                ListItem(
                    headlineContent = {
                        Text(
                            text = "+ Create new group with this tab",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.clickable(onClick = onCreateNewGroupWithTab)
                )

                if (tab.groupId != null) {
                    ListItem(
                        headlineContent = { Text("None (Ungrouped)") },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Default.FolderOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable { onSelectGroup(null) }
                    )
                }

                groups.forEach { group ->
                    val groupColor = parseHexColor(group.colorHex, MaterialTheme.colorScheme.primary)
                    val isCurrent = tab.groupId == group.id
                    ListItem(
                        headlineContent = {
                            Text(
                                text = group.title,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(groupColor)
                            )
                        },
                        trailingContent = if (isCurrent) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null,
                        modifier = Modifier.clickable { onSelectGroup(group) }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TabGroupOptionsBottomSheet(
        group: TabGroupItem,
        tabCount: Int,
        hasAvailableTabsToAdd: Boolean = false,
        onNewTabInGroup: () -> Unit,
        onAddExistingTabsToGroup: () -> Unit = {},
        onEditGroup: () -> Unit,
        onUngroup: () -> Unit,
        onCloseGroup: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val groupColor = parseHexColor(group.colorHex, Color(0xFF8AB4F8))
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(groupColor)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "($tabCount tab${if (tabCount == 1) "" else "s"})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                ListItem(
                    headlineContent = { Text("New tab in group") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable(onClick = onNewTabInGroup)
                )

                if (hasAvailableTabsToAdd) {
                    ListItem(
                        headlineContent = { Text("Add existing tabs to group") },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        modifier = Modifier.clickable(onClick = onAddExistingTabsToGroup)
                    )
                }

                ListItem(
                    headlineContent = { Text("Rename or change color") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable(onClick = onEditGroup)
                )

                ListItem(
                    headlineContent = { Text("Ungroup tabs (keep tabs open)") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.FolderOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable(onClick = onUngroup)
                )

                ListItem(
                    headlineContent = {
                        Text(
                            text = "Close group and all its tabs",
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.clickable(onClick = onCloseGroup)
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ArchivedTabsBottomSheet(
        archivedTabs: List<TabItem>,
        autoArchiveLabel: String,
        onRestoreTab: (TabItem, Boolean) -> Unit,
        onRestoreAll: () -> Unit,
        onDeleteArchivedTab: (TabItem) -> Unit,
        onClearAllArchived: () -> Unit,
        onConfigureAutoArchive: () -> Unit,
        onDismiss: () -> Unit
    ) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Archived Tabs (${archivedTabs.size})",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Auto-archive: $autoArchiveLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable(onClick = onConfigureAutoArchive)
                        )
                    }

                    IconButton(onClick = onConfigureAutoArchive) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.auto_archive_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (archivedTabs.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRestoreAll,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Unarchive,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restore All")
                        }

                        OutlinedButton(
                            onClick = onClearAllArchived,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Close All")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (archivedTabs.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Archive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No archived tabs",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Inactive tabs are automatically moved here to free memory and keep your tab switcher tidy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else {
                    val listScroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(listScroll),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        archivedTabs.forEach { tab ->
                            val favicon = rememberFavicon(tab.url)
                            val relativeIdle = remember(tab.archivedAt, tab.lastAccessedAt) {
                                val refTime = if (tab.archivedAt > 0L) tab.archivedAt else tab.lastAccessedAt
                                DateUtils.getRelativeTimeSpanString(
                                    refTime,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS,
                                    DateUtils.FORMAT_ABBREV_RELATIVE
                                ).toString()
                            }

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                                tonalElevation = 0.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onRestoreTab(tab, true) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (favicon != null) {
                                        Image(
                                            bitmap = favicon,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                        )
                                    } else {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_web),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = tab.title.ifBlank { tab.url.ifBlank { "Archived Tab" } },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${tab.url} • Archived $relativeIdle",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    IconButton(
                                        onClick = { onRestoreTab(tab, false) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Unarchive,
                                            contentDescription = stringResource(R.string.restore_tab),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { onDeleteArchivedTab(tab) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.close_tab),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AutoArchiveConfigBottomSheet(
        selectedHours: Int,
        onSelectHours: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {
        val options = listOf(
            BrowserPreferences.TAB_ARCHIVE_NEVER to "Never (Keep all tabs active)",
            BrowserPreferences.TAB_ARCHIVE_1_DAY to "After 1 day of inactivity",
            BrowserPreferences.TAB_ARCHIVE_3_DAYS to "After 3 days of inactivity",
            BrowserPreferences.TAB_ARCHIVE_7_DAYS to "After 7 days of inactivity (Recommended)",
            BrowserPreferences.TAB_ARCHIVE_14_DAYS to "After 14 days of inactivity",
            BrowserPreferences.TAB_ARCHIVE_30_DAYS to "After 30 days of inactivity"
        )

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = "Auto-Archive Inactive Tabs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Text(
                    text = "Tabs that have not been opened for the selected period are automatically hibernated and moved to Archived Tabs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                options.forEach { (hours, label) ->
                    RadioPreference(
                        title = label,
                        selected = selectedHours == hours,
                        onClick = { onSelectHours(hours) }
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
