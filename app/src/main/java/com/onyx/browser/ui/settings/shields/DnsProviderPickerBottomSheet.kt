package com.onyx.browser.ui.settings.shields

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.theme.OnyxTheme

/**
 * BottomSheetDialogFragment wrapper for legacy or fragment-based invocations.
 */
class DnsProviderPickerBottomSheet @JvmOverloads constructor(
    private val currentProviderUrl: String = "",
    private val onSelected: (String) -> Unit = {}
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
                val initialUrl = currentProviderUrl.ifBlank { prefs.secureDnsProvider }
                OnyxTheme {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        DnsProviderSheetContent(
                            currentProviderUrl = initialUrl,
                            currentCustomUrl = prefs.customDnsUrl,
                            onSelectProvider = { selectedUrl, isCustom ->
                                prefs.secureDnsProvider = selectedUrl
                                if (isCustom) {
                                    prefs.customDnsUrl = selectedUrl
                                }
                                onSelected(selectedUrl)
                                dismiss()
                            },
                            onDismiss = { dismiss() }
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "DnsProviderPickerSheet"
        const val DNS_CLOUDFLARE = "https://cloudflare-dns.com/dns-query"
        const val DNS_GOOGLE = "https://dns.google/dns-query"
        const val DNS_NEXTDNS = "https://dns.nextdns.io"
        const val DNS_QUAD9 = "https://dns.quad9.net/dns-query"
        const val DNS_ADGUARD = "https://dns.adguard-dns.com/dns-query"
        const val DNS_CUSTOM = "__custom__"
    }
}

typealias DnsProviderPickerSheet = DnsProviderPickerBottomSheet

/**
 * Modern Compose ModalBottomSheet for picking or customizing Secure DNS (DoH) providers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsProviderBottomSheet(
    currentProviderUrl: String,
    currentCustomUrl: String = "",
    onSelectProvider: (url: String, isCustom: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = null
    ) {
        DnsProviderSheetContent(
            currentProviderUrl = currentProviderUrl,
            currentCustomUrl = currentCustomUrl,
            onSelectProvider = onSelectProvider,
            onDismiss = onDismiss
        )
    }
}

private data class DnsPreset(
    val title: String,
    val subtitle: String,
    val url: String,
    val icon: ImageVector
)

private val DNS_PRESETS = listOf(
    DnsPreset(
        title = "Cloudflare (1.1.1.1)",
        subtitle = "Fast, privacy-first encrypted DNS with zero query logging",
        url = DnsProviderPickerBottomSheet.DNS_CLOUDFLARE,
        icon = Icons.Default.Public
    ),
    DnsPreset(
        title = "Google (8.8.8.8)",
        subtitle = "Google Public DNS with Anycast network routing",
        url = DnsProviderPickerBottomSheet.DNS_GOOGLE,
        icon = Icons.Default.Dns
    ),
    DnsPreset(
        title = "NextDNS",
        subtitle = "Configurable DNS with built-in ad, tracker, and malware protection",
        url = DnsProviderPickerBottomSheet.DNS_NEXTDNS,
        icon = Icons.Default.Security
    ),
    DnsPreset(
        title = "Quad9 (9.9.9.9)",
        subtitle = "Security-focused DNS blocking known malicious domains",
        url = DnsProviderPickerBottomSheet.DNS_QUAD9,
        icon = Icons.Default.Shield
    ),
    DnsPreset(
        title = "AdGuard DNS",
        subtitle = "Ad-blocking and tracking protection DNS resolver",
        url = DnsProviderPickerBottomSheet.DNS_ADGUARD,
        icon = Icons.Default.Security
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsProviderSheetContent(
    currentProviderUrl: String,
    currentCustomUrl: String = "",
    onSelectProvider: (url: String, isCustom: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val isPreset = DNS_PRESETS.any { it.url.equals(currentProviderUrl, ignoreCase = true) }
    var selectedKey by remember {
        mutableStateOf(if (isPreset) currentProviderUrl else DnsProviderPickerBottomSheet.DNS_CUSTOM)
    }

    val defaultCustomText = if (!isPreset && currentProviderUrl.isNotBlank() && currentProviderUrl != DnsProviderPickerBottomSheet.DNS_CUSTOM) {
        currentProviderUrl
    } else {
        currentCustomUrl.ifBlank { "" }
    }

    var customUrlText by remember { mutableStateOf(defaultCustomText) }
    var customUrlError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState())
            .imePadding()
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            BottomSheetDefaults.DragHandle()
        }

        Text(
            text = stringResource(R.string.secure_dns_provider_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "Choose your encrypted DNS-over-HTTPS (DoH) resolver",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        // Presets
        DNS_PRESETS.forEach { preset ->
            val isSelected = selectedKey == preset.url
            DnsOptionCard(
                title = preset.title,
                subtitle = preset.subtitle,
                icon = preset.icon,
                isSelected = isSelected,
                onClick = {
                    selectedKey = preset.url
                    customUrlError = null
                    onSelectProvider(preset.url, false)
                    onDismiss()
                },
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Custom DoH URL option
        val isCustomSelected = selectedKey == DnsProviderPickerBottomSheet.DNS_CUSTOM
        val customSummary = if (!isPreset && customUrlText.isNotBlank()) {
            customUrlText
        } else if (currentCustomUrl.isNotBlank()) {
            currentCustomUrl
        } else {
            "Enter your custom DNS-over-HTTPS endpoint URL"
        }

        DnsOptionCard(
            title = stringResource(R.string.dns_provider_custom),
            subtitle = customSummary,
            icon = Icons.Default.Edit,
            isSelected = isCustomSelected,
            onClick = {
                selectedKey = DnsProviderPickerBottomSheet.DNS_CUSTOM
            },
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Expandable Custom URL input field
        AnimatedVisibility(
            visible = isCustomSelected,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp)
            ) {
                OutlinedTextField(
                    value = customUrlText,
                    onValueChange = {
                        customUrlText = it
                        customUrlError = null
                    },
                    label = { Text("DoH Endpoint URL") },
                    placeholder = { Text("https://example.com/dns-query") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        if (customUrlText.isNotEmpty()) {
                            IconButton(onClick = { customUrlText = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear"
                                )
                            }
                        }
                    },
                    isError = customUrlError != null,
                    supportingText = {
                        if (customUrlError != null) {
                            Text(text = customUrlError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(
                                text = "Must start with https://",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        val trimmed = customUrlText.trim()
                        if (trimmed.isBlank()) {
                            customUrlError = "URL cannot be empty"
                        } else if (!trimmed.startsWith("https://", ignoreCase = true)) {
                            customUrlError = "URL must start with https://"
                        } else {
                            customUrlError = null
                            onSelectProvider(trimmed, true)
                            onDismiss()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "Save Custom URL",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.cancel),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DnsOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val borderWidth = if (isSelected) 1.5.dp else 1.dp
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(borderWidth, borderColor),
        color = backgroundColor,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
