package com.onyx.browser.ui.tabs

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.DialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.browser.TabCoordinator
import com.onyx.browser.ui.components.AppAlertDialog
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
                val isAmoled = prefs.themeMode == BrowserPreferences.THEME_DARK

                OnyxTheme(darkTheme = isDark, isAmoled = isAmoled) {
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

                    val confirmText = if (isIncognito) {
                        stringResource(R.string.close_all_incognito_tabs)
                    } else {
                        stringResource(R.string.close_all_tabs)
                    }

                    AppAlertDialog(
                        title = title,
                        onDismissRequest = { dismissAllowingStateLoss() },
                        confirmButtonText = confirmText,
                        onConfirm = {
                            tm?.closeAllTabs(incognitoOnly = isIncognito, canUndo = true)
                            onTabsClosed?.invoke(count)
                            dismissAllowingStateLoss()
                        },
                        dismissButtonText = stringResource(R.string.cancel),
                        isDestructive = true,
                        text = {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
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
