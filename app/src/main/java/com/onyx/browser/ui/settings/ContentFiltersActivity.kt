package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.R
import com.onyx.browser.data.filter.FilterListEntry
import com.onyx.browser.data.filter.FilterListManager
import com.onyx.browser.databinding.ActivityContentFiltersBinding
import com.onyx.browser.databinding.ItemContentFilterBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ContentFiltersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContentFiltersBinding
    private lateinit var adapter: ContentFilterAdapter
    private var allLists: List<FilterListEntry> = emptyList()
    private var filteredLists: MutableList<FilterListEntry> = mutableListOf()
    private var recompileJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContentFiltersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        allLists = FilterListManager.ALL_FILTER_LISTS
        filteredLists = allLists.toMutableList()

        adapter = ContentFilterAdapter(filteredLists)
        binding.rvContentFilters.layoutManager = LinearLayoutManager(this)
        binding.rvContentFilters.adapter = adapter

        binding.btnUpdateFilters.setOnClickListener {
            performUpdateFilters()
        }

        binding.etSearchFilter.doAfterTextChanged { text ->
            filterList(text?.toString()?.trim() ?: "")
        }
    }

    private fun filterList(query: String) {
        filteredLists.clear()
        if (query.isBlank()) {
            filteredLists.addAll(allLists)
        } else {
            val lower = query.lowercase()
            for (entry in allLists) {
                if (entry.title.lowercase().contains(lower) || entry.subtitle.lowercase().contains(lower)) {
                    filteredLists.add(entry)
                }
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun performUpdateFilters() {
        binding.btnUpdateFilters.isEnabled = false
        binding.progressUpdate.visibility = View.VISIBLE
        binding.tvUpdateStatus.visibility = View.VISIBLE
        binding.tvUpdateStatus.text = getString(R.string.updating_filters)

        lifecycleScope.launch {
            val result = FilterListManager.updateAllFilters(this@ContentFiltersActivity) { current, total, name ->
                binding.tvUpdateStatus.text = "Updating $current of $total: $name"
            }

            binding.btnUpdateFilters.isEnabled = true
            binding.progressUpdate.visibility = View.GONE
            binding.tvUpdateStatus.visibility = View.GONE

            if (result.isSuccess) {
                val rulesCount = result.getOrNull() ?: 0
                Toast.makeText(
                    this@ContentFiltersActivity,
                    getString(R.string.filters_updated, rulesCount),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@ContentFiltersActivity,
                    getString(R.string.filters_update_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun scheduleRecompile() {
        recompileJob?.cancel()
        recompileJob = lifecycleScope.launch {
            delay(500) // Debounce toggles
            FilterListManager.recompileFilters(this@ContentFiltersActivity)
        }
    }

    inner class ContentFilterAdapter(
        private val items: List<FilterListEntry>
    ) : RecyclerView.Adapter<ContentFilterAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemContentFilterBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val itemBinding = ItemContentFilterBinding.inflate(inflater, parent, false)
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.itemBinding.tvFilterTitle.text = item.title
            holder.itemBinding.tvFilterSubtitle.text = item.subtitle

            val isEnabled = FilterListManager.isListEnabled(this@ContentFiltersActivity, item.id)
            holder.itemBinding.switchFilter.isChecked = isEnabled

            holder.itemBinding.root.setOnClickListener {
                val newState = !holder.itemBinding.switchFilter.isChecked
                holder.itemBinding.switchFilter.isChecked = newState
                FilterListManager.setListEnabled(this@ContentFiltersActivity, item.id, newState)
                scheduleRecompile()
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
