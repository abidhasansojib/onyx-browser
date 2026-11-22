package com.onyx.browser.ui.home

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.ui.theme.OnyxTheme

class ManageShortcutsBottomSheet : BottomSheetDialogFragment() {

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
                    else -> isSystemInDarkTheme()
                }
                val accentColor = remember(prefs.accentColorHex, prefs.accentColorPreset) {
                    androidx.compose.ui.graphics.Color(AccentColorManager.getAccentColor(prefs))
                }

                OnyxTheme(darkTheme = isDark, isAmoled = false, accentColor = accentColor) {
                    ManageShortcutsContent()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog ?: return
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val behavior = BottomSheetBehavior.from(sheet)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        // The Compose LazyColumn is not a NestedScrollingChild for BottomSheetBehavior, so
        // scrolling up dragged the sheet down and dismissed it. Dismiss via close button/back/scrim.
        behavior.isDraggable = false
    }

    @Composable
    private fun ManageShortcutsContent() {
        val context = LocalContext.current
        val prefs = remember { BrowserPreferences.getInstance(context) }
        val shortcuts by prefs.shortcutsFlow.collectAsStateWithLifecycle(initialValue = prefs.getShortcuts())
        val haptic = LocalHapticFeedback.current
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current

        var localShortcutsList by remember(shortcuts) { mutableStateOf(shortcuts) }

        LaunchedEffect(shortcuts) {
            if (localShortcutsList != shortcuts) {
                localShortcutsList = shortcuts
            }
        }

        var draggedRowId by remember { mutableStateOf<String?>(null) }
        var dragRowOffsetY by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val rowHeightPx = remember { with(density) { 56.dp.toPx() } }

        var newTitle by remember { mutableStateOf("") }
        var newUrl by remember { mutableStateOf("") }
        var urlError by remember { mutableStateOf<String?>(null) }

        val suggestedItems = remember {
            listOf(
                Triple("Google", "https://www.google.com", ShortcutItem.ICON_GOOGLE),
                Triple("YouTube", "https://www.youtube.com", ShortcutItem.ICON_YOUTUBE),
                Triple("GitHub", "https://github.com", ShortcutItem.ICON_GITHUB),
                Triple("Wikipedia", "https://www.wikipedia.org", ShortcutItem.ICON_WIKIPEDIA),
                Triple("Facebook", "https://www.facebook.com", ShortcutItem.ICON_FACEBOOK),
                Triple("Reddit", "https://www.reddit.com", ShortcutItem.ICON_REDDIT),
                Triple("X (Twitter)", "https://x.com", ShortcutItem.DEFAULT_ICON),
                Triple("Instagram", "https://www.instagram.com", ShortcutItem.DEFAULT_ICON),
                Triple("Amazon", "https://www.amazon.com", ShortcutItem.DEFAULT_ICON),
                Triple("Netflix", "https://www.netflix.com", ShortcutItem.DEFAULT_ICON)
            )
        }

        val onAddAction = {
            val trimmedUrl = newUrl.trim()
            val candidateUrl = when {
                trimmedUrl.startsWith("http://", ignoreCase = true) ||
                trimmedUrl.startsWith("https://", ignoreCase = true) ||
                trimmedUrl.startsWith("onyx://", ignoreCase = true) -> trimmedUrl
                else -> "https://$trimmedUrl"
            }

            val parsedUri = try { android.net.Uri.parse(candidateUrl) } catch (_: Exception) { null }
            val scheme = parsedUri?.scheme?.lowercase()
            val host = parsedUri?.host
            val schemeSpecific = parsedUri?.schemeSpecificPart

            val isValid = when {
                trimmedUrl.isBlank() -> false
                scheme == "onyx" -> !schemeSpecific.isNullOrBlank() && schemeSpecific != "//"
                scheme in listOf("http", "https") -> !host.isNullOrBlank() && !host.contains(" ")
                else -> false
            }

            if (trimmedUrl.isBlank()) {
                urlError = context.getString(R.string.shortcut_url_empty_error)
            } else if (!isValid) {
                urlError = context.getString(R.string.shortcut_url_invalid_error)
            } else {
                val validUrl = if (candidateUrl.contains(" ")) candidateUrl.replace(" ", "%20") else candidateUrl

                val existing = prefs.getShortcuts()
                val isDuplicate = existing.any {
                    it.url.equals(validUrl, ignoreCase = true) ||
                    it.url.trimEnd('/').equals(validUrl.trimEnd('/'), ignoreCase = true)
                }

                if (isDuplicate) {
                    urlError = context.getString(R.string.shortcut_already_exists)
                    Toast.makeText(context, R.string.shortcut_already_exists, Toast.LENGTH_SHORT).show()
                } else {
                    val inferredIcon = ShortcutItem.inferIconType(validUrl)
                    val finalTitle = newTitle.trim().ifBlank {
                        try {
                            android.net.Uri.parse(validUrl).host?.removePrefix("www.") ?: validUrl
                        } catch (_: Exception) {
                            validUrl
                        }
                    }

                    val added = prefs.addShortcut(ShortcutItem(title = finalTitle, url = validUrl, iconType = inferredIcon))
                    if (added) {
                        newTitle = ""
                        newUrl = ""
                        urlError = null
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        Toast.makeText(context, context.getString(R.string.shortcut_added), Toast.LENGTH_SHORT).show()
                    } else {
                        urlError = context.getString(R.string.shortcut_already_exists)
                        Toast.makeText(context, R.string.shortcut_already_exists, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // Drag handle
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }

                // Header with Title & Close button
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 12.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.shortcuts),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = { dismiss() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // ── SUGGESTED ──────────────────────────────────────────
                item {
                    CategoryHeader(stringResource(R.string.suggested))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(suggestedItems) { (title, url, iconType) ->
                            val favicon = rememberFavicon(url)
                            val brandIconRes = when (iconType) {
                                ShortcutItem.ICON_GOOGLE -> R.drawable.ic_brand_google
                                ShortcutItem.ICON_YOUTUBE -> R.drawable.ic_brand_youtube
                                ShortcutItem.ICON_GITHUB -> R.drawable.ic_brand_github
                                ShortcutItem.ICON_WIKIPEDIA -> R.drawable.ic_brand_wikipedia
                                ShortcutItem.ICON_FACEBOOK -> R.drawable.ic_brand_facebook
                                ShortcutItem.ICON_REDDIT -> R.drawable.ic_brand_reddit
                                else -> null
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        val existing = prefs.getShortcuts()
                                        val isDuplicate = existing.any {
                                            it.url.equals(url, ignoreCase = true) ||
                                            it.url.trimEnd('/').equals(url.trimEnd('/'), ignoreCase = true)
                                        }
                                        if (isDuplicate) {
                                            Toast.makeText(context, R.string.shortcut_already_exists, Toast.LENGTH_SHORT).show()
                                        } else {
                                            prefs.addShortcut(ShortcutItem(title = title, url = url, iconType = iconType))
                                            Toast.makeText(context, context.getString(R.string.shortcut_added), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    when {
                                        favicon != null -> {
                                            Image(
                                                bitmap = favicon,
                                                contentDescription = null,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                            )
                                        }
                                        brandIconRes != null -> {
                                            Icon(
                                                painter = painterResource(brandIconRes),
                                                contentDescription = null,
                                                tint = Color.Unspecified,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        else -> {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_web),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // ── ADD CUSTOM ──────────────────────────────────────────
                item {
                    CategoryHeader(stringResource(R.string.add_custom))

                    CardContainer(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            OutlinedTextField(
                                value = newTitle,
                                onValueChange = { newTitle = it },
                                label = { Text(stringResource(R.string.shortcut_title)) },
                                placeholder = { Text(stringResource(R.string.shortcut_title_hint)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Words,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = newUrl,
                                onValueChange = {
                                    newUrl = it
                                    urlError = null
                                },
                                label = { Text(stringResource(R.string.shortcut_url)) },
                                placeholder = { Text(stringResource(R.string.shortcut_url_hint)) },
                                isError = urlError != null,
                                supportingText = urlError?.let { { Text(it) } },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { onAddAction() }
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = onAddAction,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.add_shortcut), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // ── MY SHORTCUTS ──────────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${stringResource(R.string.my_shortcuts)} (${localShortcutsList.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = stringResource(R.string.drag_to_reorder),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                // Shortcuts List
                items(localShortcutsList, key = { it.id }) { item ->
                    val isSystem = item.isSystemShortcut
                    val favicon = if (!isSystem) rememberFavicon(item.url) else null
                    val brandIconRes = remember(item.iconType) {
                        when (item.iconType) {
                            ShortcutItem.ICON_GOOGLE -> R.drawable.ic_brand_google
                            ShortcutItem.ICON_YOUTUBE -> R.drawable.ic_brand_youtube
                            ShortcutItem.ICON_GITHUB -> R.drawable.ic_brand_github
                            ShortcutItem.ICON_WIKIPEDIA -> R.drawable.ic_brand_wikipedia
                            ShortcutItem.ICON_FACEBOOK -> R.drawable.ic_brand_facebook
                            ShortcutItem.ICON_REDDIT -> R.drawable.ic_brand_reddit
                            else -> null
                        }
                    }
                    val isRowDragging = draggedRowId == item.id

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 3.dp)
                            .zIndex(if (isRowDragging) 10f else 1f)
                            .graphicsLayer {
                                if (isRowDragging) {
                                    translationY = dragRowOffsetY
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                    shadowElevation = 16f
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Drag Handle Icon
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .pointerInput(item.id) {
                                        detectDragGestures(
                                            onDragStart = {
                                                draggedRowId = item.id
                                                dragRowOffsetY = 0f
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragRowOffsetY += dragAmount.y
                                                val threshold = rowHeightPx * 0.6f
                                                if (dragRowOffsetY > threshold) {
                                                    val fromIdx = localShortcutsList.indexOfFirst { it.id == item.id }
                                                    if (fromIdx != -1 && fromIdx < localShortcutsList.lastIndex) {
                                                        val updated = localShortcutsList.toMutableList()
                                                        val moved = updated.removeAt(fromIdx)
                                                        updated.add(fromIdx + 1, moved)
                                                        localShortcutsList = updated
                                                        dragRowOffsetY -= rowHeightPx
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                } else if (dragRowOffsetY < -threshold) {
                                                    val fromIdx = localShortcutsList.indexOfFirst { it.id == item.id }
                                                    if (fromIdx > 0) {
                                                        val updated = localShortcutsList.toMutableList()
                                                        val moved = updated.removeAt(fromIdx)
                                                        updated.add(fromIdx - 1, moved)
                                                        localShortcutsList = updated
                                                        dragRowOffsetY += rowHeightPx
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                prefs.saveShortcuts(localShortcutsList)
                                                draggedRowId = null
                                                dragRowOffsetY = 0f
                                            },
                                            onDragCancel = {
                                                prefs.saveShortcuts(localShortcutsList)
                                                draggedRowId = null
                                                dragRowOffsetY = 0f
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_more_vert),
                                    contentDescription = stringResource(R.string.drag_to_reorder),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                when {
                                    isSystem -> {
                                        val sysRes = when (item.iconType) {
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
                                            painter = painterResource(sysRes),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    favicon != null -> {
                                        Image(
                                            bitmap = favicon,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                    }
                                    brandIconRes != null -> {
                                        Icon(
                                            painter = painterResource(brandIconRes),
                                            contentDescription = null,
                                            tint = Color.Unspecified,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    else -> {
                                        val first = item.title.trim().firstOrNull()?.uppercase()
                                        if (first != null && first.matches(Regex("[A-Za-z0-9]"))) {
                                            Text(
                                                text = first,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        } else {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_web),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (!isSystem) {
                                IconButton(
                                    onClick = {
                                        showEditDialog(item)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.edit_shortcut),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        prefs.deleteShortcut(item.id)
                                        Toast.makeText(context, R.string.shortcut_deleted, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.delete_shortcut),
                                        tint = MaterialTheme.colorScheme.error,
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

    @Composable
    private fun CategoryHeader(title: String) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp)
        )
    }

    private fun showEditDialog(shortcut: ShortcutItem) {
        val dialog = EditShortcutDialog(shortcut) { updated ->
            val prefs = BrowserPreferences.getInstance(requireContext())
            prefs.updateShortcut(updated)
            Toast.makeText(requireContext(), R.string.shortcut_updated, Toast.LENGTH_SHORT).show()
        }
        dialog.show(parentFragmentManager, EditShortcutDialog.TAG)
    }

    companion object {
        const val TAG = "ManageShortcutsBottomSheet"
    }
}
