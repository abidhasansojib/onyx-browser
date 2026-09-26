package com.onyx.browser.ui.settings

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.databinding.ActivityManagePersonalDataBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

class ManagePersonalDataActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagePersonalDataBinding
    private var selectedIndex: Int = 2 // Default: Last 24 hours

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.parseColor("#0B0305")
        window.navigationBarColor = android.graphics.Color.parseColor("#0B0305")

        binding = ActivityManagePersonalDataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.manageDataRoot) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updatePadding(top = statusBarInsets.top)
            binding.scrollView.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupDropdown()
        setupClearAction()
    }

    override fun onResume() {
        super.onResume()
        updateLiveStatistics(selectedIndex)
    }

    private fun getCutoffTime(index: Int): Long {
        val now = System.currentTimeMillis()
        return when (index) {
            0 -> now - 15 * 60 * 1000L           // Last 15 minutes
            1 -> now - 60 * 60 * 1000L           // Last hour
            2 -> now - 24 * 60 * 60 * 1000L      // Last 24 hours
            3 -> now - 7 * 24 * 60 * 60 * 1000L  // Last 7 days
            4 -> now - 28L * 24 * 60 * 60 * 1000L // Last 4 weeks
            else -> 0L                           // All time
        }
    }

    private fun setupDropdown() {
        val timeRanges = listOf(
            getString(R.string.time_range_last_15_mins),
            getString(R.string.time_range_last_hour),
            getString(R.string.time_range_last_24_hours),
            getString(R.string.time_range_last_7_days),
            getString(R.string.time_range_last_4_weeks),
            getString(R.string.time_range_all_time)
        )

        val adapter = ArrayAdapter(this, R.layout.item_dropdown_time_range, timeRanges)
        binding.actvTimeRange.setAdapter(adapter)
        binding.actvTimeRange.setText(timeRanges[selectedIndex], false)

        binding.actvTimeRange.setOnItemClickListener { _, _, position, _ ->
            selectedIndex = position
            updateLiveStatistics(selectedIndex)
        }
    }

    private fun updateLiveStatistics(index: Int) {
        val cutoff = getCutoffTime(index)
        val database = AppDatabase.getInstance(this)

        lifecycleScope.launch {
            val historyItems = withContext(Dispatchers.IO) {
                if (cutoff == 0L) {
                    database.historyDao().getHistorySince(0L)
                } else {
                    database.historyDao().getHistorySince(cutoff)
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

            binding.tvHistoryCount.text = getString(R.string.browsing_history_preview, count, "")
            binding.tvHistoryExample.text = exampleString

            // Tabs breakdown
            val tabs = withContext(Dispatchers.IO) {
                database.tabDao().getAllNormalTabs()
            }
            val filteredTabs = if (cutoff == 0L) {
                tabs
            } else {
                tabs.filter { it.createdAt >= cutoff }
            }
            val tabTitles = filteredTabs.map { it.title.take(24) }.take(2)
            val tabExampleString = if (tabTitles.isNotEmpty()) {
                "e.g. " + tabTitles.joinToString(", ")
            } else {
                "None created in this period"
            }

            binding.tvTabsCount.text = getString(R.string.open_tabs_preview, filteredTabs.size, "")
            binding.tvTabsExample.text = tabExampleString
        }
    }

    private fun setupClearAction() {
        binding.btnClearData.setOnClickListener {
            val clearHistory = binding.cbHistory.isChecked
            val clearCookies = binding.cbCookies.isChecked
            val clearCache = binding.cbCache.isChecked
            val clearTabs = binding.cbTabs.isChecked

            if (!clearHistory && !clearCookies && !clearCache && !clearTabs) {
                Toast.makeText(this, "Please select at least one data type to clear", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val cutoff = getCutoffTime(selectedIndex)
            val database = AppDatabase.getInstance(this)

            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    if (clearHistory) {
                        if (cutoff == 0L) {
                            database.historyDao().clearAllHistory()
                        } else {
                            database.historyDao().deleteHistorySince(cutoff)
                        }
                    }

                    if (clearTabs) {
                        if (cutoff == 0L) {
                            database.tabDao().clearNormalTabs()
                        } else {
                            val tabs = database.tabDao().getAllNormalTabs()
                            for (tab in tabs) {
                                if (tab.createdAt >= cutoff) {
                                    database.tabDao().deleteTab(tab)
                                }
                            }
                        }
                    }
                }

                if (clearCookies) {
                    try {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                    } catch (_: Exception) {}
                }

                if (clearCache) {
                    try {
                        WebStorage.getInstance().deleteAllData()
                    } catch (_: Exception) {}
                }

                Toast.makeText(this@ManagePersonalDataActivity, getString(R.string.browsing_data_cleared), Toast.LENGTH_SHORT).show()
                updateLiveStatistics(selectedIndex)
            }
        }
    }
}
