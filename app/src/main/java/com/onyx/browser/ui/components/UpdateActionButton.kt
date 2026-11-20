package com.onyx.browser.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/** Auto-update period choices in hours; 0 disables auto-update. */
val UPDATE_INTERVAL_OPTIONS: List<Pair<Int, String>> = listOf(
    6 to "Every 6 hours",
    12 to "Every 12 hours",
    24 to "Every 24 hours",
    48 to "Every 2 days",
    0 to "Disable auto-update"
)

fun updateIntervalLabel(hours: Int): String = when {
    hours <= 0 -> "Disabled"
    hours == 6 -> "Every 6h"
    hours == 12 -> "Every 12h"
    hours == 24 -> "Every 24h"
    hours == 48 -> "Every 2 days"
    else -> "Every ${hours}h"
}

/**
 * Refresh action: tap to update now, long-press to choose the auto-update period.
 * [currentHours] is 0 when auto-update is disabled.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UpdateActionButton(
    currentHours: Int,
    contentDescription: String,
    onClick: () -> Unit,
    onSelectHours: (Int) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            UPDATE_INTERVAL_OPTIONS.forEach { (hours, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = {
                        if (hours == currentHours) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        menuOpen = false
                        onSelectHours(hours)
                    }
                )
            }
        }
    }
}
