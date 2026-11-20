package com.onyx.browser.ui.settings.shields

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.theme.OnyxTheme

/**
 * BottomSheetDialogFragment wrapper for legacy or fragment-based invocations.
 */
class ClearOnExitBottomSheet @JvmOverloads constructor(
    private val onUpdated: () -> Unit = {}
) : BottomSheetDialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_OnyxBrowser_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val prefs = remember { BrowserPreferences.getInstance(requireContext()) }
                OnyxTheme {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        ClearOnExitSheetContent(
                            prefs = prefs,
                            onDismiss = { dismiss() },
                            onUpdated = onUpdated
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "ClearOnExitBottomSheet"
    }
}

/**
 * Modern Compose ModalBottomSheet for granular Clear Data on Exit configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearOnExitSheet(
    prefs: BrowserPreferences,
    onDismiss: () -> Unit,
    onUpdated: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        ClearOnExitSheetContent(
            prefs = prefs,
            onDismiss = onDismiss,
            onUpdated = onUpdated
        )
    }
}

@Composable
fun ClearOnExitSheetContent(
    prefs: BrowserPreferences,
    onDismiss: () -> Unit,
    onUpdated: () -> Unit = {}
) {
    val context = LocalContext.current

    var isMasterEnabled by remember { mutableStateOf(prefs.isClearOnExitEnabled) }
    var isTabsEnabled by remember { mutableStateOf(prefs.isClearOnExitTabsEnabled) }
    var isHistoryEnabled by remember { mutableStateOf(prefs.isClearOnExitHistoryEnabled) }
    var isCookiesEnabled by remember { mutableStateOf(prefs.isClearOnExitCookiesEnabled) }
    var isCacheEnabled by remember { mutableStateOf(prefs.isClearOnExitCacheEnabled) }
    var summaryText by remember { mutableStateOf(prefs.getClearOnExitSummary(context)) }

    fun refreshSummary() {
        summaryText = prefs.getClearOnExitSummary(context)
        onUpdated()
    }

    fun onMasterToggle(checked: Boolean) {
        isMasterEnabled = checked
        prefs.isClearOnExitEnabled = checked
        if (checked && !isTabsEnabled && !isHistoryEnabled && !isCookiesEnabled && !isCacheEnabled) {
            isTabsEnabled = true
            isHistoryEnabled = true
            isCookiesEnabled = true
            isCacheEnabled = true
            prefs.isClearOnExitTabsEnabled = true
            prefs.isClearOnExitHistoryEnabled = true
            prefs.isClearOnExitCookiesEnabled = true
            prefs.isClearOnExitCacheEnabled = true
        }
        refreshSummary()
    }

    fun onOptionChanged() {
        val anySelected = isTabsEnabled || isHistoryEnabled || isCookiesEnabled || isCacheEnabled
        if (!anySelected && isMasterEnabled) {
            isMasterEnabled = false
            prefs.isClearOnExitEnabled = false
        }
        refreshSummary()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = stringResource(R.string.clear_on_exit_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = stringResource(R.string.clear_on_exit_sheet_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        // Master Toggle Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onMasterToggle(!isMasterEnabled) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_broom),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.clear_on_exit_master_label),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isMasterEnabled) summaryText else stringResource(R.string.clear_on_exit_disabled),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isMasterEnabled,
                    onCheckedChange = { onMasterToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Granular Items Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (isMasterEnabled) 1.0f else 0.45f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 1. Open tabs
                ClearOnExitOptionRow(
                    iconRes = R.drawable.ic_tabs,
                    title = stringResource(R.string.clear_data_tabs),
                    subtitle = stringResource(R.string.clear_on_exit_tabs_desc),
                    checked = isTabsEnabled,
                    enabled = isMasterEnabled,
                    onCheckedChange = { checked ->
                        if (!isMasterEnabled) return@ClearOnExitOptionRow
                        isTabsEnabled = checked
                        prefs.isClearOnExitTabsEnabled = checked
                        onOptionChanged()
                    }
                )

                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )

                // 2. Browsing history
                ClearOnExitOptionRow(
                    iconRes = R.drawable.ic_history,
                    title = stringResource(R.string.clear_data_history),
                    subtitle = stringResource(R.string.clear_on_exit_history_desc),
                    checked = isHistoryEnabled,
                    enabled = isMasterEnabled,
                    onCheckedChange = { checked ->
                        if (!isMasterEnabled) return@ClearOnExitOptionRow
                        isHistoryEnabled = checked
                        prefs.isClearOnExitHistoryEnabled = checked
                        onOptionChanged()
                    }
                )

                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )

                // 3. Cookies and site data
                ClearOnExitOptionRow(
                    iconRes = R.drawable.ic_shield_lock,
                    title = stringResource(R.string.clear_data_cookies),
                    subtitle = stringResource(R.string.clear_on_exit_cookies_desc),
                    checked = isCookiesEnabled,
                    enabled = isMasterEnabled,
                    onCheckedChange = { checked ->
                        if (!isMasterEnabled) return@ClearOnExitOptionRow
                        isCookiesEnabled = checked
                        prefs.isClearOnExitCookiesEnabled = checked
                        onOptionChanged()
                    }
                )

                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )

                // 4. Cached images and files
                ClearOnExitOptionRow(
                    iconRes = R.drawable.ic_broom,
                    title = stringResource(R.string.clear_data_cache),
                    subtitle = stringResource(R.string.clear_on_exit_cache_desc),
                    checked = isCacheEnabled,
                    enabled = isMasterEnabled,
                    onCheckedChange = { checked ->
                        if (!isMasterEnabled) return@ClearOnExitOptionRow
                        isCacheEnabled = checked
                        prefs.isClearOnExitCacheEnabled = checked
                        onOptionChanged()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Done Button
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                text = stringResource(R.string.done),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ClearOnExitOptionRow(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Checkbox(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}
