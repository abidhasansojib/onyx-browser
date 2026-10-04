package com.onyx.browser.ui.history

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import com.onyx.browser.databinding.DialogConfirmClearHistoryBinding

class ClearHistoryDialog(
    private val onConfirmClear: () -> Unit
) : DialogFragment() {

    private var _binding: DialogConfirmClearHistoryBinding? = null
    private val binding get() = _binding!!

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
        _binding = DialogConfirmClearHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCancelClearHistory.setOnClickListener {
            dismiss()
        }

        binding.btnConfirmClearHistory.setOnClickListener {
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ClearHistoryDialog"
    }
}
