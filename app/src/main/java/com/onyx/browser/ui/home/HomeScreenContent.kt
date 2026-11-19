package com.onyx.browser.ui.home

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onyx.browser.R
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.data.preferences.BrowserPreferences

@Composable
fun HomeScreenContent(
    isIncognito: Boolean,
    preferences: BrowserPreferences,
    onNavigateToUrl: (String) -> Unit,
    onAddShortcut: () -> Unit,
    onEditShortcut: (ShortcutItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val spanCount = if (configuration.screenWidthDp >= 600) 6 else 4

    val shortcutsList by preferences.shortcutsFlow.collectAsStateWithLifecycle(initialValue = preferences.getShortcuts())

    var localShortcuts by remember(shortcutsList) {
        mutableStateOf(shortcutsList.filter { it.id != ShortcutItem.ID_ADD_SHORTCUT && it.url != ShortcutItem.URL_ADD_SHORTCUT })
    }

    LaunchedEffect(shortcutsList) {
        val filtered = shortcutsList.filter { it.id != ShortcutItem.ID_ADD_SHORTCUT && it.url != ShortcutItem.URL_ADD_SHORTCUT }
        if (localShortcuts.map { it.id } != filtered.map { it.id }) {
            localShortcuts = filtered
        }
    }

    val displayShortcuts = remember(localShortcuts) {
        val list = localShortcuts.toMutableList()
        list.add(
            ShortcutItem(
                id = ShortcutItem.ID_ADD_SHORTCUT,
                title = "Add",
                url = ShortcutItem.URL_ADD_SHORTCUT,
                iconType = ShortcutItem.ICON_ADD
            )
        )
        list
    }

    val haptic = LocalHapticFeedback.current
    var draggedItemId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var hasDraggedEnough by remember { mutableStateOf(false) }
    val itemBounds = remember { mutableStateMapOf<String, Rect>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(72.dp))

        // Branding / Hero Section
        if (isIncognito) {
            // Incognito Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(vertical = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(colorResource(R.color.shortcut_tile_bg)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_incognito),
                        contentDescription = stringResource(R.string.incognito_mode),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.incognito_mode),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = stringResource(R.string.incognito_privacy_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 300.dp)
                )
            }
        } else {
            // Normal Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(vertical = 24.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_app_logo),
                    contentDescription = "Onyx Browser",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .width(180.dp)
                        .height(58.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.tagline_fast_and_private),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.5.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Shortcuts Grid Container (chunked into responsive rows for drag-to-reorder)
        Box(
            modifier = Modifier
                .widthIn(max = 500.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val rows = remember(displayShortcuts, spanCount) {
                displayShortcuts.chunked(spanCount)
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                rows.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowItems.forEach { item ->
                            val isAddSentinel = item.id == ShortcutItem.ID_ADD_SHORTCUT || item.url == ShortcutItem.URL_ADD_SHORTCUT
                            val isSystem = item.isSystemShortcut
                            val favicon = if (!isAddSentinel && !isSystem) rememberFavicon(item.url) else null
                            val isDragging = draggedItemId == item.id

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .onGloballyPositioned { coords ->
                                        itemBounds[item.id] = coords.boundsInRoot()
                                    }
                                    .zIndex(if (isDragging) 10f else 1f)
                                    .graphicsLayer {
                                        if (isDragging) {
                                            translationX = dragOffset.x
                                            translationY = dragOffset.y
                                            scaleX = 1.15f
                                            scaleY = 1.15f
                                            shadowElevation = 24f
                                        }
                                    }
                                    .then(
                                        if (!isAddSentinel) {
                                            Modifier.pointerInput(item.id) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        draggedItemId = item.id
                                                        dragOffset = Offset.Zero
                                                        hasDraggedEnough = false
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragOffset += dragAmount
                                                        if (dragOffset.getDistance() > 14f) {
                                                            hasDraggedEnough = true
                                                        }
                                                        val origBounds = itemBounds[item.id]
                                                        if (origBounds != null) {
                                                            val currentCenter = origBounds.center + dragOffset
                                                            val targetItem = localShortcuts.firstOrNull { other ->
                                                                other.id != item.id && (itemBounds[other.id]?.contains(currentCenter) == true)
                                                            }
                                                            if (targetItem != null) {
                                                                val fromIdx = localShortcuts.indexOfFirst { it.id == item.id }
                                                                val toIdx = localShortcuts.indexOfFirst { it.id == targetItem.id }
                                                                if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                                                                    val targetBounds = itemBounds[targetItem.id]
                                                                    if (targetBounds != null) {
                                                                        dragOffset -= (targetBounds.topLeft - origBounds.topLeft)
                                                                        val updated = localShortcuts.toMutableList()
                                                                        val moved = updated.removeAt(fromIdx)
                                                                        updated.add(toIdx, moved)
                                                                        localShortcuts = updated
                                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        if (hasDraggedEnough) {
                                                            preferences.saveShortcuts(localShortcuts)
                                                        } else {
                                                            onEditShortcut(item)
                                                        }
                                                        draggedItemId = null
                                                        dragOffset = Offset.Zero
                                                        hasDraggedEnough = false
                                                    },
                                                    onDragCancel = {
                                                        draggedItemId = null
                                                        dragOffset = Offset.Zero
                                                        hasDraggedEnough = false
                                                    }
                                                )
                                            }
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable {
                                        if (isAddSentinel) {
                                            onAddShortcut()
                                        } else {
                                            onNavigateToUrl(item.url)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = colorResource(R.color.shortcut_tile_bg),
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        modifier = Modifier.size(54.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            when {
                                                // 1. Add Shortcut Sentinel Button
                                                isAddSentinel -> {
                                                    Icon(
                                                        imageVector = Icons.Default.Add,
                                                        contentDescription = "Add shortcut",
                                                        tint = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }

                                                // 2. System Browser Tools (Bookmarks, History, Downloads, QR)
                                                isSystem -> {
                                                    val sysIconRes = when (item.iconType) {
                                                        ShortcutItem.ICON_BOOKMARKS -> R.drawable.ic_bookmark
                                                        ShortcutItem.ICON_HISTORY -> R.drawable.ic_history
                                                        ShortcutItem.ICON_DOWNLOADS -> R.drawable.ic_download
                                                        ShortcutItem.ICON_QR_SCAN -> R.drawable.ic_qr_code
                                                        else -> when {
                                                            item.url.contains("bookmarks") -> R.drawable.ic_bookmark
                                                            item.url.contains("history") -> R.drawable.ic_history
                                                            item.url.contains("downloads") -> R.drawable.ic_download
                                                            item.url.contains("qr") -> R.drawable.ic_qr_code
                                                            else -> R.drawable.ic_bookmark
                                                        }
                                                    }
                                                    Icon(
                                                        painter = painterResource(sysIconRes),
                                                        contentDescription = item.title,
                                                        tint = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }

                                                // 3. Dynamic Live Cached Website Favicons (Squircle shape)
                                                favicon != null -> {
                                                    Image(
                                                        bitmap = favicon,
                                                        contentDescription = item.title,
                                                        contentScale = ContentScale.Fit,
                                                        modifier = Modifier
                                                            .size(28.dp)
                                                            .clip(RoundedCornerShape(6.dp))
                                                    )
                                                }

                                                // 4. Fallback for unstyled websites (first letter or web globe)
                                                else -> {
                                                    val firstLetter = item.title.trim().firstOrNull()?.uppercase()
                                                    if (firstLetter != null && firstLetter.matches(Regex("[A-Za-z0-9]"))) {
                                                        Text(
                                                            text = firstLetter,
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    } else {
                                                        Icon(
                                                            painter = painterResource(R.drawable.ic_web),
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.onSurface,
                                                            modifier = Modifier.size(24.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.widthIn(max = 68.dp)
                                    )
                                }
                            }
                        }

                        // Fill remaining spaces in the last row
                        val remaining = spanCount - rowItems.size
                        if (remaining > 0) {
                            repeat(remaining) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}
