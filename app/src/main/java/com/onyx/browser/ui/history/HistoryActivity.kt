package com.onyx.browser.ui.history

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.HistoryItem
import com.onyx.browser.databinding.ActivityHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter
    private lateinit var database: AppDatabase

    private var historyJob: Job? = null
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
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
                }
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
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_browsing_data)
                .setMessage(R.string.confirm_clear_history)
                .setPositiveButton(R.string.clear) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        database.historyDao().clearAllHistory()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
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
                adapter.submitList(list)
                binding.emptyHistoryView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.rvHistory.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }
}
