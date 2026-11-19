package com.onyx.browser.ui.home

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onyx.browser.R
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.data.preferences.BrowserPreferences

@OptIn(ExperimentalFoundationApi::class)
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

    val displayShortcuts = remember(shortcutsList) {
        val list = shortcutsList.filter { it.id != ShortcutItem.ID_ADD_SHORTCUT && it.url != ShortcutItem.URL_ADD_SHORTCUT }.toMutableList()
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(36.dp))

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
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_incognito),
                        contentDescription = stringResource(R.string.incognito_mode),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // Normal Onyx Header
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

        Spacer(modifier = Modifier.height(24.dp))

        // Shortcuts Grid Container
        Box(
            modifier = Modifier
                .widthIn(max = 500.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(spanCount),
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
            ) {
                items(displayShortcuts, key = { it.id }) { item ->
                    val isAddSentinel = item.id == ShortcutItem.ID_ADD_SHORTCUT || item.url == ShortcutItem.URL_ADD_SHORTCUT

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (isAddSentinel) {
                                        onAddShortcut()
                                    } else {
                                        onNavigateToUrl(item.url)
                                    }
                                },
                                onLongClick = {
                                    if (!isAddSentinel) {
                                        onEditShortcut(item)
                                    }
                                }
                            )
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isAddSentinel) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shadowElevation = if (isAddSentinel) 0.dp else 2.dp,
                            modifier = Modifier.size(54.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isAddSentinel) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add shortcut",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else {
                                    val iconRes = when (item.iconType) {
                                        ShortcutItem.ICON_GOOGLE -> R.drawable.ic_brand_google
                                        ShortcutItem.ICON_YOUTUBE -> R.drawable.ic_brand_youtube
                                        ShortcutItem.ICON_GITHUB -> R.drawable.ic_brand_github
                                        ShortcutItem.ICON_WIKIPEDIA -> R.drawable.ic_brand_wikipedia
                                        ShortcutItem.ICON_FACEBOOK -> R.drawable.ic_brand_facebook
                                        ShortcutItem.ICON_REDDIT -> R.drawable.ic_brand_reddit
                                        else -> null
                                    }

                                    if (iconRes != null) {
                                        Icon(
                                            painter = painterResource(iconRes),
                                            contentDescription = null,
                                            tint = Color.Unspecified,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    } else {
                                        val firstLetter = item.title.trim().firstOrNull()?.uppercase()
                                        if (firstLetter != null && firstLetter.matches(Regex("[A-Za-z0-9]"))) {
                                            Text(
                                                text = firstLetter,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        } else {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_web),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(22.dp)
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
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}
