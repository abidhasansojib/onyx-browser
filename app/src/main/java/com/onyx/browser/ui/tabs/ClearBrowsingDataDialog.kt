package com.onyx.browser.ui.tabs

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.databinding.DialogClearBrowsingDataBinding
import com.onyx.browser.ui.browser.TabManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

class ClearBrowsingDataDialog @JvmOverloads constructor(
    private var tabManager: TabManager? = TabManager.activeInstance,
    private var onDataCleared: () -> Unit = {}
) : DialogFragment() {

    private var _binding: DialogClearBrowsingDataBinding? = null
    private val binding get() = _binding

    private fun getCutoffTime(selectedIndex: Int): Long {
        val now = System.currentTimeMillis()
        return when (selectedIndex) {
            0 -> now - 15 * 60 * 1000L           // Last 15 minutes
            1 -> now - 60 * 60 * 1000L           // Last hour
            2 -> now - 24 * 60 * 60 * 1000L      // Last 24 hours
            3 -> now - 7 * 24 * 60 * 60 * 1000L  // Last 7 days
            4 -> now - 28L * 24 * 60 * 60 * 1000L // Last 4 weeks
            else -> 0L                           // All time
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
        if (tabManager == null) {
            tabManager = TabManager.activeInstance
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        _binding = DialogClearBrowsingDataBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val maxAllowedWidth = (440 * displayMetrics.density).toInt()
            val targetWidth = (displayMetrics.widthPixels * 0.92).toInt().coerceAtMost(maxAllowedWidth)
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = _binding ?: return

        val timeRanges = listOf(
            getString(R.string.time_range_last_15_mins),
            getString(R.string.time_range_last_hour),
            getString(R.string.time_range_last_24_hours),
            getString(R.string.time_range_last_7_days),
            getString(R.string.time_range_last_4_weeks),
            getString(R.string.time_range_all_time)
        )

        val adapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_time_range, timeRanges)
        b.actvTimeRange.setAdapter(adapter)

        // Default to Last 24 hours (index 2)
        var selectedIndex = 2
        b.actvTimeRange.setText(timeRanges[selectedIndex], false)
        updatePreview(selectedIndex)

        b.actvTimeRange.setOnItemClickListener { _, _, position, _ ->
            selectedIndex = position
            updatePreview(selectedIndex)
        }

        // Setup granular checkbox toggle rows
        b.rowHistory.setOnClickListener {
            b.cbClearHistory.isChecked = !b.cbClearHistory.isChecked
            updateClearButtonState()
        }
        b.cbClearHistory.setOnCheckedChangeListener { _, _ -> updateClearButtonState() }

        b.rowTabs.setOnClickListener {
            b.cbClearTabs.isChecked = !b.cbClearTabs.isChecked
            updateClearButtonState()
        }
        b.cbClearTabs.setOnCheckedChangeListener { _, _ -> updateClearButtonState() }

        b.rowCookies.setOnClickListener {
            b.cbClearCookies.isChecked = !b.cbClearCookies.isChecked
            updateClearButtonState()
        }
        b.cbClearCookies.setOnCheckedChangeListener { _, _ -> updateClearButtonState() }

        b.rowCache.setOnClickListener {
            b.cbClearCache.isChecked = !b.cbClearCache.isChecked
            updateClearButtonState()
        }
        b.cbClearCache.setOnCheckedChangeListener { _, _ -> updateClearButtonState() }

        updateClearButtonState()

        b.btnCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }

        b.btnClearData.setOnClickListener {
            performClearData(
                selectedIndex = selectedIndex,
                clearHistory = b.cbClearHistory.isChecked,
                clearTabs = b.cbClearTabs.isChecked,
                clearCookies = b.cbClearCookies.isChecked,
                clearCache = b.cbClearCache.isChecked
            )
        }
    }

    private fun updateClearButtonState() {
        val b = _binding ?: return
        val hasSelection = b.cbClearHistory.isChecked || b.cbClearTabs.isChecked ||
                           b.cbClearCookies.isChecked || b.cbClearCache.isChecked
        b.btnClearData.isEnabled = hasSelection
        b.btnClearData.alpha = if (hasSelection) 1.0f else 0.5f
    }

    private fun updatePreview(selectedIndex: Int) {
        val cutoff = getCutoffTime(selectedIndex)
        val ctx = context ?: return
        val database = AppDatabase.getInstance(ctx)

        viewLifecycleOwner.lifecycleScope.launch {
            // 1. Browsing history sites count and examples
            val historyItems = withContext(Dispatchers.IO) {
                try {
                    if (cutoff == 0L) {
                        database.historyDao().getHistorySince(0L)
                    } else {
                        database.historyDao().getHistorySince(cutoff)
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }

            val count = historyItems.size
            val distinctHosts = historyItems.mapNotNull { item ->
                try {
                    URI(item.url).host?.removePrefix("www.")
                } catch (_: Exception) {
                    item.title.takeIf { it.isNotBlank() }
                }
            }.distinct().take(3)

            val exampleString = if (distinctHosts.isNotEmpty()) {
                "e.g. " + distinctHosts.joinToString(", ")
            } else {
                "None recorded in this period"
            }

            val b = _binding ?: return@launch
            b.tvHistoryCount.text = getString(R.string.browsing_history_preview, count, "")
            b.tvHistoryExample.text = exampleString

            // 2. Open tabs count and examples
            val tm = tabManager ?: TabManager.activeInstance
            val normalTabs = tm?.normalTabs?.value ?: emptyList()
            val incognitoTabs = tm?.incognitoTabs?.value ?: emptyList()
            val allTabs = normalTabs + incognitoTabs

            val matchingTabs = if (cutoff == 0L) {
                allTabs
            } else {
                allTabs.filter { it.createdAt >= cutoff }
            }

            val tabsCount = matchingTabs.size
            val tabTitles = matchingTabs.map { it.title.ifBlank { "New Tab" } }.take(2)
            val tabExampleString = if (tabTitles.isNotEmpty()) {
                "e.g. " + tabTitles.joinToString(", ")
            } else {
                "None opened in this period"
            }

            b.tvTabsCount.text = getString(R.string.open_tabs_preview, tabsCount, "")
            b.tvTabsExample.text = tabExampleString
        }
    }

    private fun performClearData(
        selectedIndex: Int,
        clearHistory: Boolean = true,
        clearTabs: Boolean = false,
        clearCookies: Boolean = false,
        clearCache: Boolean = false
    ) {
        val cutoff = getCutoffTime(selectedIndex)
        val appContext = context?.applicationContext ?: return
        val database = AppDatabase.getInstance(appContext)
        val tm = tabManager ?: TabManager.activeInstance

        viewLifecycleOwner.lifecycleScope.launch {
            // 1. Delete history (if selected)
            if (clearHistory) {
                withContext(Dispatchers.IO) {
                    try {
                        if (cutoff == 0L) {
                            database.historyDao().clearAllHistory()
                        } else {
                            database.historyDao().deleteHistorySince(cutoff)
                        }
                    } catch (_: Exception) {}
                }
            }

            // 2. Close tabs created/active in this timeframe (if selected)
            if (clearTabs) {
                try {
                    if (cutoff == 0L) {
                        tm?.closeAllTabs(incognitoOnly = true)
                        tm?.closeAllTabs(incognitoOnly = false)
                    } else {
                        tm?.closeTabsCreatedSince(cutoff)
                    }
                } catch (_: Exception) {}
            }

            // 3. Clear WebView cookies and web storage across all profiles (if selected)
            if (clearCookies) {
                try {
                    CookieManager.getInstance().removeAllCookies {
                        CookieManager.getInstance().flush()
                    }
                    CookieManager.getInstance().removeSessionCookies {
                        CookieManager.getInstance().flush()
                    }
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()

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

                try {
                    val db = WebViewDatabase.getInstance(appContext)
                    db.clearHttpAuthUsernamePassword()
                    db.clearFormData()
                } catch (_: Exception) {}

                try {
                    val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(appContext)
                    prefs.clearAllCustomBlockedRules()
                } catch (_: Exception) {}
            }

            // 4. Clear disk cache & preview files (if selected)
            if (clearCache) {
                withContext(Dispatchers.IO) {
                    try {
                        val archivesDir = File(appContext.cacheDir, "web_archives")
                        if (archivesDir.exists()) {
                            if (cutoff == 0L) {
                                archivesDir.deleteRecursively()
                                com.onyx.browser.web.LocalFileLoader.cleanupAllPreviews(appContext)
                            } else {
                                archivesDir.listFiles()?.forEach { f ->
                                    if (f.lastModified() >= cutoff) {
                                        f.delete()
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    if (cutoff == 0L) {
                        try {
                            com.onyx.browser.data.favicon.FaviconManager.clearCache(appContext)
                        } catch (_: Exception) {}
                        try {
                            tm?.clearAllThumbnailsAndCache()
                        } catch (_: Exception) {}
                        try {
                            val apk = File(appContext.cacheDir, "install_pending.apk")
                            if (apk.exists() && System.currentTimeMillis() - apk.lastModified() > 60_000L) {
                                apk.delete()
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            // 5. Clear cache on active WebViews safely on Main thread
            withContext(Dispatchers.Main) {
                if (clearCache) {
                    try {
                        tm?.getAllWebViews()?.forEach { wv ->
                            wv.clearCache(true)
                            wv.clearFormData()
                            wv.clearSslPreferences()
                            // Reset OAuth grace period so cookie state is re-evaluated cleanly
                            wv.lastOAuthInteractionTimestamp = 0L
                        }
                    } catch (_: Exception) {}
                }

                // If cookies were cleared, re-apply cookie settings to restore first-party acceptance
                if (clearCookies) {
                    try {
                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                        android.webkit.CookieManager.getInstance().flush()
                    } catch (_: Exception) {}
                }

                try {
                    Toast.makeText(appContext, getString(R.string.browsing_data_cleared), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}

                try {
                    onDataCleared()
                } catch (_: Exception) {}

                try {
                    dismissAllowingStateLoss()
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ClearBrowsingDataDialog"
    }
}
