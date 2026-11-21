package com.onyx.browser.ui.settings.shields

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.filter.FilterListManager
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.AppAlertDialog
import com.onyx.browser.ui.theme.OnyxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

class ManageBlockedElementsBottomSheet @JvmOverloads constructor(
    private val highlightDomain: String? = null,
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
                val isDark = when (prefs.themeMode) {
                    BrowserPreferences.THEME_DARK -> true
                    BrowserPreferences.THEME_LIGHT -> false
                    else -> isSystemInDarkTheme()
                }
                OnyxTheme(darkTheme = isDark, isAmoled = false) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        ManageBlockedElementsContent(
                            prefs = prefs,
                            highlightDomain = highlightDomain,
                            onDismiss = { dismiss() },
                            onUpdated = onUpdated
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "ManageBlockedElementsBottomSheet"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageBlockedElementsSheet(
    prefs: BrowserPreferences,
    highlightDomain: String? = null,
    onDismiss: () -> Unit,
    onUpdated: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        ManageBlockedElementsContent(
            prefs = prefs,
            highlightDomain = highlightDomain,
            onDismiss = onDismiss,
            onUpdated = onUpdated
        )
    }
}

@Composable
fun ManageBlockedElementsContent(
    prefs: BrowserPreferences,
    highlightDomain: String? = null,
    onDismiss: () -> Unit,
    onUpdated: () -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val cleanHighlight = remember(highlightDomain) {
        highlightDomain?.let { prefs.cleanDomain(it).lowercase() }?.takeIf { it.isNotBlank() }
    }

    var rulesByDomain by remember {
        mutableStateOf(prefs.getAllDomainsWithCustomBlockedRules())
    }
    val expandedDomains = remember {
        mutableStateMapOf<String, Boolean>().apply {
            if (cleanHighlight != null) {
                put(cleanHighlight, true)
            }
        }
    }
    var domainPendingReset by remember { mutableStateOf<String?>(null) }
    var showResetAllDialog by remember { mutableStateOf(false) }

    fun refreshRulesAndRecompile() {
        rulesByDomain = prefs.getAllDomainsWithCustomBlockedRules()
        onUpdated()
        scope.launch(Dispatchers.IO + NonCancellable) {
            try {
                FilterListManager.recompileFilters(appContext)
            } catch (_: Exception) {}
        }
    }

    val sortedEntries = remember(rulesByDomain, cleanHighlight) {
        val list = rulesByDomain.entries.toList()
        if (cleanHighlight == null) {
            list
        } else {
            list.sortedWith(
                compareByDescending<Map.Entry<String, List<String>>> { entry ->
                    entry.key == cleanHighlight || cleanHighlight.endsWith(".${entry.key}")
                }.thenBy { it.key }
            )
        }
    }

    val totalRulesCount = remember(rulesByDomain) {
        rulesByDomain.values.sumOf { it.size }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Blocked Elements",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (sortedEntries.isEmpty()) {
                        "No websites have custom blocked elements"
                    } else {
                        "${sortedEntries.size} ${if (sortedEntries.size == 1) "website" else "websites"} • $totalRulesCount ${if (totalRulesCount == 1) "element" else "elements"} hidden"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (sortedEntries.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showResetAllDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Reset All",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (sortedEntries.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No Blocked Elements Yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Use 'Block Element' from the browser menu on any webpage to hide distracting banners or sections. Websites you customize will appear here so you can restore them anytime.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sortedEntries, key = { it.key }) { (domain, selectors) ->
                    val isCurrentSite = cleanHighlight != null &&
                            (domain == cleanHighlight || cleanHighlight.endsWith(".$domain"))
                    val isExpanded = expandedDomains[domain] ?: isCurrentSite

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isCurrentSite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            }
                        ),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedDomains[domain] = !isExpanded
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isCurrentSite) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Language,
                                        contentDescription = null,
                                        tint = if (isCurrentSite) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = domain,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isCurrentSite) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Current site",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${selectors.size} ${if (selectors.size == 1) "element" else "elements"} blocked • Tap to view rules",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(
                                    onClick = { domainPendingReset = domain }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Restore,
                                        contentDescription = "Restore $domain",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }

                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            AnimatedVisibility(visible = isExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                                ) {
                                    HorizontalDivider(
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                    )

                                    selectors.forEachIndexed { idx, selector ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = selector,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 12.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )

                                            IconButton(
                                                onClick = {
                                                    prefs.removeCustomBlockedSelector(domain, selector)
                                                    Toast.makeText(
                                                        context,
                                                        "Restored element on $domain",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    refreshRulesAndRecompile()
                                                },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Remove rule",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }

                                        if (idx < selectors.lastIndex) {
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                                                modifier = Modifier.padding(horizontal = 16.dp)
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        OutlinedButton(
                                            onClick = { domainPendingReset = domain },
                                            shape = RoundedCornerShape(18.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error
                                            )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Restore Default Look for $domain",
                                                style = MaterialTheme.typography.labelMedium
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

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(
                text = "Done",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    if (domainPendingReset != null) {
        val targetDomain = domainPendingReset!!
        AppAlertDialog(
            title = "Restore $targetDomain?",
            text = {
                Text("All custom blocked elements for $targetDomain will be removed and the website will return to its original layout.")
            },
            confirmButtonText = "Restore",
            isDestructive = true,
            onConfirm = {
                prefs.clearCustomBlockedRulesForDomain(targetDomain)
                Toast.makeText(
                    context,
                    context.getString(R.string.custom_blocked_elements_reset_done, targetDomain),
                    Toast.LENGTH_SHORT
                ).show()
                domainPendingReset = null
                refreshRulesAndRecompile()
            },
            onDismissRequest = { domainPendingReset = null }
        )
    }

    if (showResetAllDialog) {
        AppAlertDialog(
            title = "Reset all blocked elements?",
            text = {
                Text("This will remove all custom blocked element rules across all ${sortedEntries.size} websites and restore their original look.")
            },
            confirmButtonText = "Reset All",
            isDestructive = true,
            onConfirm = {
                prefs.clearAllCustomBlockedRules()
                Toast.makeText(
                    context,
                    "Restored all websites to their original layout",
                    Toast.LENGTH_SHORT
                ).show()
                showResetAllDialog = false
                refreshRulesAndRecompile()
            },
            onDismissRequest = { showResetAllDialog = false }
        )
    }
}
