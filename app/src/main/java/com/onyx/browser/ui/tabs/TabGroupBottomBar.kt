package com.onyx.browser.ui.tabs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onyx.browser.R
import com.onyx.browser.data.model.TabGroupItem
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.ui.browser.TabCoordinator
import com.onyx.browser.ui.home.rememberFavicon

/**
 * Persistent bottom bar displayed while browsing a tab that belongs to a Tab Group.
 * - Left button (^): opens the Tab Group Preview overlay for the active group.
 * - Center strip: horizontally scrollable favicon circles for all tabs in the group;
 *   tapping an inactive icon switches to that tab, while the active tab displays an
 *   accent ring and an '×' badge to close it.
 * - Right button (+): creates and switches to a new tab within the same Tab Group.
 */
@Composable
fun TabGroupBottomBar(
    tabManager: TabCoordinator,
    onExpandGroupClick: (TabGroupItem) -> Unit,
    onSelectTabClick: (TabItem) -> Unit,
    onCloseTabClick: (TabItem) -> Unit,
    onNewTabInGroupClick: (TabGroupItem) -> Unit
) {
    val activeTab by tabManager.activeTab.collectAsStateWithLifecycle()
    val normalTabs by tabManager.normalTabs.collectAsStateWithLifecycle()
    val tabGroups by tabManager.tabGroups.collectAsStateWithLifecycle()

    val currentActive = activeTab
    if (currentActive == null || currentActive.isIncognito) return

    val groupId = currentActive.groupId ?: return
    val currentGroup = tabGroups.firstOrNull { it.id == groupId } ?: return
    val groupTabs = normalTabs.filter { it.groupId == currentGroup.id }
    if (groupTabs.isEmpty()) return

    val isDark = isSystemInDarkTheme()
    val barBgColor = colorResource(R.color.homepage_bg)
    val pillBgColor = if (isDark) Color(0xFF28282C) else MaterialTheme.colorScheme.surfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary
    val closeIconTint = if (isDark) Color(0xFF161616) else Color.White

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBgColor)
    ) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Button (^): Open Tab Group Preview Dialog
            IconButton(
                onClick = { onExpandGroupClick(currentGroup) },
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard_arrow_up),
                    contentDescription = "Open tab group",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Middle: Horizontally Scrollable Grouped Tab Favicon Circles
            val scrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                groupTabs.forEach { tab ->
                    val isSelected = tab.id == currentActive.id
                    val favicon = rememberFavicon(tab.url)

                    Box(
                        modifier = Modifier.size(42.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Outer Circular Tab Pill
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(pillBgColor)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(2.dp, accentColor, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable {
                                    if (isSelected) {
                                        onCloseTabClick(tab)
                                    } else {
                                        onSelectTabClick(tab)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            // Inner White Favicon Badge
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                if (favicon != null) {
                                    Image(
                                        bitmap = favicon,
                                        contentDescription = tab.title,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_web),
                                        contentDescription = tab.title,
                                        tint = Color(0xFF444444),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        // Active Tab Top-Right '×' Close Badge
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                                    .clickable { onCloseTabClick(tab) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.close_tab),
                                    tint = closeIconTint,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Right Button (+): Create New Tab in the Same Group
            IconButton(
                onClick = { onNewTabInGroupClick(currentGroup) },
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.new_tab),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
