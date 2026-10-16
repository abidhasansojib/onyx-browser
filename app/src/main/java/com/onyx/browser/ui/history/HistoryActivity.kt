package com.onyx.browser.ui.history

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.HistoryItem
import com.onyx.browser.databinding.ActivityHistoryBinding
import com.onyx.browser.ui.browser.TabManager
import com.onyx.browser.ui.tabs.ClearBrowsingDataDialog
import com.onyx.browser.ui.theme.AccentColorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter
    private lateinit var database: AppDatabase

    private var historyJob: Job? = null
    private var currentQuery: String = ""
    private var currentRawHistory: List<HistoryItem> = emptyList()

    private val collapsedDayKeys = mutableSetOf<String>()
    private var isInitialCollapseConfigured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(this)

        binding.toolbarHistory.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        setupSearch()
        setupClearDataButton()
        loadHistory()
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onItemClicked = { item ->
                val resultIntent = Intent().apply { putExtra(EXTRA_URL, item.url) }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            },
            onItemDeleted = { item ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.historyDao().deleteHistory(item)
                    if (com.onyx.browser.web.LocalFileLoader.isPreviewUrl(item.url) ||
                        com.onyx.browser.web.LocalFileLoader.isLocalFile(this@HistoryActivity, item.url)) {
                        com.onyx.browser.web.LocalFileLoader.cleanupAllPreviews(this@HistoryActivity)
                    }
                }
            },
            onHeaderToggled = { dayKey ->
                if (collapsedDayKeys.contains(dayKey)) {
                    collapsedDayKeys.remove(dayKey)
                } else {
                    collapsedDayKeys.add(dayKey)
                }
                updateDisplayList()
            }
        )

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearchHistory.doAfterTextChanged { text ->
            currentQuery = text?.toString()?.trim() ?: ""
            binding.btnClearHistorySearch.visibility = if (currentQuery.isNotEmpty()) View.VISIBLE else View.GONE
            loadHistory()
        }

        binding.btnClearHistorySearch.setOnClickListener {
            binding.etSearchHistory.setText("")
        }
    }

    private fun setupClearDataButton() {
        binding.btnClearBrowsingData.setOnClickListener {
            if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved || supportFragmentManager.isDestroyed) {
                return@setOnClickListener
            }
            val tm = TabManager.activeInstance
            val dialog = ClearBrowsingDataDialog(
                tabManager = tm,
                onDataCleared = {
                    loadHistory()
                }
            )
            dialog.show(supportFragmentManager, ClearBrowsingDataDialog.TAG)
        }
    }

    private fun loadHistory() {
        historyJob?.cancel()
        historyJob = lifecycleScope.launch {
            val flow = if (currentQuery.isBlank()) {
                database.historyDao().getAllHistoryFlow()
            } else {
                database.historyDao().searchHistoryFlow(currentQuery)
            }

            flow.collectLatest { list ->
                currentRawHistory = list
                updateDisplayList()
            }
        }
    }

    private fun updateDisplayList() {
        val isSearch = currentQuery.isNotBlank()

        if (!isInitialCollapseConfigured && currentRawHistory.isNotEmpty() && !isSearch) {
            isInitialCollapseConfigured = true
            val nowCal = Calendar.getInstance()
            val todayKey = HistoryDateUtils.getDayKey(nowCal.timeInMillis)
            val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val yesterdayKey = HistoryDateUtils.getDayKey(yesterdayCal.timeInMillis)

            val groupedKeys = currentRawHistory.map { HistoryDateUtils.getDayKey(it.visitTime) }.distinct()
            groupedKeys.forEachIndexed { index, key ->
                if (index > 0 && key != todayKey && key != yesterdayKey) {
                    collapsedDayKeys.add(key)
                }
            }
        }

        val displayList = buildDisplayList(this, currentRawHistory, collapsedDayKeys, isSearch)
        adapter.submitList(displayList)

        val isEmpty = currentRawHistory.isEmpty()
        binding.emptyHistoryView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.tvHistorySectionTitle.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun buildDisplayList(
        context: Context,
        rawHistory: List<HistoryItem>,
        collapsedKeys: Set<String>,
        isSearch: Boolean
    ): List<HistoryListItem> {
        if (rawHistory.isEmpty()) return emptyList()

        val displayList = mutableListOf<HistoryListItem>()
        val grouped = LinkedHashMap<String, MutableList<HistoryItem>>()

        for (item in rawHistory) {
            val key = HistoryDateUtils.getDayKey(item.visitTime)
            grouped.getOrPut(key) { mutableListOf() }.add(item)
        }

        for ((dayKey, items) in grouped) {
            val title = HistoryDateUtils.formatDayGroupTitle(context, items.first().visitTime)
            val isExpanded = if (isSearch) true else !collapsedKeys.contains(dayKey)

            displayList.add(
                HistoryListItem.Header(
                    dayKey = dayKey,
                    title = title,
                    isExpanded = isExpanded,
                    count = items.size
                )
            )

            if (isExpanded) {
                items.forEachIndexed { index, item ->
                    val isLast = (index == items.size - 1)
                    displayList.add(
                        HistoryListItem.Item(
                            historyItem = item,
                            dayKey = dayKey,
                            isLastInGroup = isLast
                        )
                    )
                }
            }
        }

        return displayList
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }
}
