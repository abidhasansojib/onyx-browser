package com.onyx.browser.ui.home

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.ui.theme.OnyxTheme

class EditShortcutDialog @JvmOverloads constructor(
    private var shortcut: ShortcutItem = ShortcutItem(title = "", url = ""),
    private var onShortcutSaved: ((ShortcutItem) -> Unit)? = null
) : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
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
                    EditShortcutContent()
                }
            }
        }
    }

    @Composable
    private fun EditShortcutContent() {
        val context = LocalContext.current
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current

        val isNew = remember(shortcut.url) { shortcut.url.isBlank() }
        var title by remember { mutableStateOf(shortcut.title) }
        var url by remember { mutableStateOf(shortcut.url) }
        var urlError by remember { mutableStateOf<String?>(null) }

        val firstLetter = remember(title) {
            title.trim().firstOrNull()?.uppercase()
        }

        val isSystem = shortcut.isSystemShortcut
        val favicon = if (!isSystem) rememberFavicon(url) else null
        val brandIcon = remember(url) {
            when (ShortcutItem.inferIconType(url)) {
                ShortcutItem.ICON_GOOGLE -> R.drawable.ic_brand_google
                ShortcutItem.ICON_YOUTUBE -> R.drawable.ic_brand_youtube
                ShortcutItem.ICON_GITHUB -> R.drawable.ic_brand_github
                ShortcutItem.ICON_WIKIPEDIA -> R.drawable.ic_brand_wikipedia
                ShortcutItem.ICON_FACEBOOK -> R.drawable.ic_brand_facebook
                ShortcutItem.ICON_REDDIT -> R.drawable.ic_brand_reddit
                else -> null
            }
        }

        val onSaveAction = {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isBlank()) {
                urlError = context.getString(R.string.shortcut_url_empty_error)
            } else if (trimmedUrl.contains(" ") && !trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://") && !trimmedUrl.startsWith("onyx://")) {
                urlError = context.getString(R.string.shortcut_url_invalid_error)
            } else {
                val validUrl = when {
                    trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://") || trimmedUrl.startsWith("onyx://") -> trimmedUrl
                    else -> "https://$trimmedUrl"
                }

                val finalTitle = title.trim().ifBlank {
                    try {
                        android.net.Uri.parse(validUrl).host?.removePrefix("www.") ?: validUrl
                    } catch (_: Exception) {
                        validUrl
                    }
                }

                val inferredIcon = if (isSystem) shortcut.iconType else ShortcutItem.inferIconType(validUrl)
                val updated = shortcut.copy(
                    title = finalTitle,
                    url = validUrl,
                    iconType = inferredIcon
                )
                keyboardController?.hide()
                onShortcutSaved?.invoke(updated)
                dismiss()
            }
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Header with Preview Icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(if (isSystem) RoundedCornerShape(14.dp) else CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isSystem -> {
                                val sysRes = when (shortcut.iconType) {
                                    ShortcutItem.ICON_BOOKMARKS -> R.drawable.ic_bookmark
                                    ShortcutItem.ICON_HISTORY -> R.drawable.ic_history
                                    ShortcutItem.ICON_DOWNLOADS -> R.drawable.ic_download
                                    ShortcutItem.ICON_QR_SCAN -> R.drawable.ic_qr_code
                                    else -> when {
                                        shortcut.url.contains("bookmarks") -> R.drawable.ic_bookmark
                                        shortcut.url.contains("history") -> R.drawable.ic_history
                                        shortcut.url.contains("downloads") -> R.drawable.ic_download
                                        shortcut.url.contains("qr") -> R.drawable.ic_qr_code
                                        else -> R.drawable.ic_bookmark
                                    }
                                }
                                Icon(
                                    painter = painterResource(sysRes),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            favicon != null -> {
                                Image(
                                    bitmap = favicon,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                )
                            }
                            brandIcon != null -> {
                                Icon(
                                    painter = painterResource(brandIcon),
                                    contentDescription = null,
                                    tint = ComposeColor.Unspecified,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            else -> {
                                if (firstLetter != null && firstLetter.matches(Regex("[A-Za-z0-9]"))) {
                                    Text(
                                        text = firstLetter,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_web),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = if (isNew) stringResource(R.string.add_shortcut) else stringResource(R.string.edit_shortcut),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Title Input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
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

                Spacer(modifier = Modifier.height(12.dp))

                // URL Input
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
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
                        onDone = { onSaveAction() }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { dismiss() }) {
                        Text(
                            text = stringResource(R.string.cancel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onSaveAction,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            text = if (isNew) stringResource(R.string.add) else stringResource(R.string.save),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val maxDialogWidth = (420 * displayMetrics.density).toInt()
            val targetWidth = (displayMetrics.widthPixels * 0.90).toInt().coerceAtMost(maxDialogWidth)
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    companion object {
        const val TAG = "EditShortcutDialog"
    }
}
