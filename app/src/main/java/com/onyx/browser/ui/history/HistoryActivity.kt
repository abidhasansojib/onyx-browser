package com.onyx.browser.ui.history

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.onyx.browser.data.model.HistoryItem
import com.onyx.browser.databinding.ActivityHistoryBinding
import com.onyx.browser.ui.browser.TabCoordinator
import com.onyx.browser.ui.tabs.ClearBrowsingDataDialog
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.ui.viewmodel.HistoryViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Calendar

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter
    private lateinit var viewModel: HistoryViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[HistoryViewModel::class.java]

        binding.toolbarHistory.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        setupSearch()
        setupClearDataButton()
        observeHistory()
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onItemClicked = { item ->
                val resultIntent = Intent().apply { putExtra(EXTRA_URL, item.url) }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            },
            onItemDeleted = { item ->
                viewModel.deleteHistoryItem(item)
            },
            onHeaderToggled = { dayKey ->
                viewModel.toggleDayHeader(dayKey)
            }
        )

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter
    }

    private fun setupSearch() {
        val existingQuery = viewModel.searchQuery.value
        if (existingQuery.isNotEmpty() && binding.etSearchHistory.text?.toString() != existingQuery) {
            binding.etSearchHistory.setText(existingQuery)
            binding.etSearchHistory.setSelection(existingQuery.length)
        }
        binding.btnClearHistorySearch.visibility = if (existingQuery.isNotEmpty()) View.VISIBLE else View.GONE

        binding.etSearchHistory.doAfterTextChanged { text ->
            val query = text?.toString()?.trim() ?: ""
            binding.btnClearHistorySearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            viewModel.setSearchQuery(query)
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
                onDataCleared = {}
            )
            dialog.show(supportFragmentManager, ClearBrowsingDataDialog.TAG)
        }
    }

    private fun observeHistory() {
        lifecycleScope.launch {
            combine(
                viewModel.rawHistory,
                viewModel.collapsedDayKeys,
                viewModel.searchQuery
            ) { rawHistory, collapsedKeys, query ->
                Triple(rawHistory, collapsedKeys, query)
            }.collectLatest { (rawHistory, collapsedKeys, query) ->
                updateDisplayList(rawHistory, collapsedKeys, query)
            }
        }
    }

    private fun updateDisplayList(
        currentRawHistory: List<HistoryItem>,
        currentCollapsedKeys: Set<String>,
        currentQuery: String
    ) {
        val isSearch = currentQuery.isNotBlank()

        var effectiveCollapsedKeys = currentCollapsedKeys
        if (!viewModel.isInitialCollapseConfigured && currentRawHistory.isNotEmpty() && !isSearch) {
            val nowCal = Calendar.getInstance()
            val todayKey = HistoryDateUtils.getDayKey(nowCal.timeInMillis)
            val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val yesterdayKey = HistoryDateUtils.getDayKey(yesterdayCal.timeInMillis)

            val initialKeys = mutableSetOf<String>()
            val groupedKeys = currentRawHistory.map { HistoryDateUtils.getDayKey(it.visitTime) }.distinct()
            groupedKeys.forEachIndexed { index, key ->
                if (index > 0 && key != todayKey && key != yesterdayKey) {
                    initialKeys.add(key)
                }
            }
            viewModel.configureInitialCollapsedDays(initialKeys)
            effectiveCollapsedKeys = initialKeys
        }

        val displayList = buildDisplayList(this, currentRawHistory, effectiveCollapsedKeys, isSearch)
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
