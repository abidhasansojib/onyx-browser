package com.onyx.browser.ui.tabs

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.DialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.browser.TabCoordinator
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.ui.theme.OnyxTheme

class CloseAllTabsDialog @JvmOverloads constructor(
    private var tabManager: TabCoordinator? = null,
    private var isIncognito: Boolean = false,
    private var onTabsClosed: ((closedCount: Int) -> Unit)? = null
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
                    val tm = tabManager ?: TabCoordinator.activeInstance
                    val tabList = if (isIncognito) {
                        tm?.incognitoTabs?.value ?: emptyList()
                    } else {
                        tm?.normalTabs?.value ?: emptyList()
                    }
                    val count = tabList.size

                    val title = if (isIncognito) {
                        stringResource(R.string.close_all_incognito_tabs_title)
                    } else {
                        stringResource(R.string.close_all_tabs_title)
                    }

                    val message = if (isIncognito) {
                        stringResource(R.string.close_all_incognito_tabs_desc, count)
                    } else if (count == 1) {
                        stringResource(R.string.close_single_tab_desc)
                    } else {
                        stringResource(R.string.close_all_tabs_desc, count)
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
                                .padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Danger / Action Icon Badge (56dp circle matching old branch bg_circle_danger)
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_tab_close),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            // Title
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 18.dp)
                            )

                            // Subtitle / Warning Message
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp)
                            )

                            // Tab Count Summary Pill / Badge (matching old branch tabCountPill)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                                modifier = Modifier.padding(top = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_tabs),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (count == 1) {
                                            stringResource(R.string.single_tab_to_be_closed_count)
                                        } else {
                                            stringResource(R.string.tabs_to_be_closed_count, count)
                                        },
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            // Action Buttons (Cancel & Close All pill buttons)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { dismissAllowingStateLoss() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    )
                                ) {
                                    Text(
                                        text = stringResource(R.string.cancel),
                                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Button(
                                    onClick = {
                                        tm?.closeAllTabs(incognitoOnly = isIncognito, canUndo = true)
                                        onTabsClosed?.invoke(count)
                                        dismissAllowingStateLoss()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Text(
                                        text = stringResource(R.string.close_all_action),
                                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val maxAllowedWidth = (420 * displayMetrics.density).toInt()
            val targetWidth = (displayMetrics.widthPixels * 0.90).toInt().coerceAtMost(maxAllowedWidth)
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    companion object {
        const val TAG = "CloseAllTabsDialog"
    }
}
