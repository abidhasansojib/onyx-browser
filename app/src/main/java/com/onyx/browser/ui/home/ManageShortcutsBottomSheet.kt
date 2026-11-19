package com.onyx.browser.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.data.preferences.BrowserPreferences
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
                OnyxTheme {
                    ManageShortcutsContent()
                }
            }
        }
    }

    @Composable
    private fun ManageShortcutsContent() {
        val context = LocalContext.current
        val prefs = remember { BrowserPreferences.getInstance(context) }
        val shortcuts by prefs.shortcutsFlow.collectAsStateWithLifecycle(initialValue = prefs.getShortcuts())

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

        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
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
                    text = "Manage Shortcuts",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Add New Shortcut Section
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Add Shortcut",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = newTitle,
                            onValueChange = { newTitle = it },
                            label = { Text("Name (optional)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = newUrl,
                            onValueChange = {
                                newUrl = it
                                urlError = null
                            },
                            label = { Text("URL (e.g. example.com)") },
                            isError = urlError != null,
                            supportingText = urlError?.let { { Text(it) } },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                val trimmedUrl = newUrl.trim()
                                if (trimmedUrl.isBlank()) {
                                    urlError = "URL cannot be empty"
                                    return@Button
                                }

                                val validUrl = if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
                                    "https://$trimmedUrl"
                                } else {
                                    trimmedUrl
                                }

                                val finalTitle = newTitle.trim().ifBlank {
                                    try {
                                        android.net.Uri.parse(validUrl).host?.removePrefix("www.") ?: validUrl
                                    } catch (_: Exception) {
                                        validUrl
                                    }
                                }

                                prefs.addShortcut(ShortcutItem(title = finalTitle, url = validUrl))
                                newTitle = ""
                                newUrl = ""
                                Toast.makeText(context, "$finalTitle added", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Suggestions Section
                Text(
                    text = "SUGGESTIONS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(suggestedItems) { (title, url, iconType) ->
                        val favicon = rememberFavicon(url)

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    val existing = prefs.getShortcuts()
                                    if (existing.any { it.url == url }) {
                                        Toast.makeText(context, "Already in shortcuts", Toast.LENGTH_SHORT).show()
                                    } else {
                                        prefs.addShortcut(ShortcutItem(title = title, url = url, iconType = iconType))
                                        Toast.makeText(context, "$title added", Toast.LENGTH_SHORT).show()
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
                                                .clip(CircleShape)
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

                                Spacer(modifier = Modifier.width(6.dp))

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

                Spacer(modifier = Modifier.height(16.dp))

                // My Shortcuts Header
                Text(
                    text = "MY SHORTCUTS (${shortcuts.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Shortcuts List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(shortcuts, key = { it.id }) { item ->
                        val isSystem = item.isSystemShortcut
                        val favicon = if (!isSystem) rememberFavicon(item.url) else null

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(if (isSystem) RoundedCornerShape(10.dp) else CircleShape)
                                        .background(
                                            if (isSystem) MaterialTheme.colorScheme.primaryContainer
                                             else MaterialTheme.colorScheme.surfaceVariant
                                        ),
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
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
                                                    .clip(CircleShape)
                                            )
                                        }
                                        else -> {
                                            val first = item.title.trim().firstOrNull()?.uppercase()
                                            if (first != null && first.matches(Regex("[A-Za-z0-9]"))) {
                                                Text(
                                                    text = first,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            } else {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_web),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

                                IconButton(
                                    onClick = {
                                        showEditDialog(item)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit",
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
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(
                    onClick = { dismiss() },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
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
