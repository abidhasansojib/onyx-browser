package com.onyx.browser.ui.history

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.DialogFragment
import com.onyx.browser.R
import com.onyx.browser.ui.components.OnyxAlertDialog
import com.onyx.browser.ui.theme.OnyxTheme

class ClearHistoryDialog(
    private val onConfirmClear: () -> Unit
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
                OnyxTheme {
                    OnyxAlertDialog(
                        title = stringResource(R.string.confirm_clear_history),
                        onDismissRequest = { dismiss() },
                        confirmButtonText = stringResource(R.string.clear),
                        onConfirm = {
                            try {
                                android.webkit.CookieManager.getInstance().removeAllCookies {
                                    android.webkit.CookieManager.getInstance().flush()
                                }
                                android.webkit.CookieManager.getInstance().removeSessionCookies {
                                    android.webkit.CookieManager.getInstance().flush()
                                }
                                android.webkit.CookieManager.getInstance().flush()
                                android.webkit.WebStorage.getInstance().deleteAllData()

                                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
                                    try {
                                        val store = androidx.webkit.ProfileStore.getInstance()
                                        val incognitoProfile = store.getProfile(com.onyx.browser.web.OnyxWebView.INCOGNITO_PROFILE_NAME)
                                        if (incognitoProfile != null) {
                                            incognitoProfile.cookieManager.removeAllCookies(null)
                                            incognitoProfile.cookieManager.flush()
                                            incognitoProfile.webStorage.deleteAllData()
                                        }
                                    } catch (_: Throwable) {}
                                }
                            } catch (_: Exception) {}
                            onConfirmClear()
                            dismiss()
                        },
                        dismissButtonText = stringResource(R.string.cancel),
                        isDestructive = true,
                        text = {
                            Text(
                                text = "This will clear all browsing history from this device.",
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
            val width = (displayMetrics.widthPixels * 0.88).toInt().coerceAtMost((400 * displayMetrics.density).toInt())
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    companion object {
        const val TAG = "ClearHistoryDialog"
    }
}
