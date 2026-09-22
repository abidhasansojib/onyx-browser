package com.onyx.browser.ui.settings

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.R
import com.onyx.browser.data.filter.FilterListEntry
import com.onyx.browser.data.filter.FilterListManager
import com.onyx.browser.databinding.ActivityContentFiltersBinding
import com.onyx.browser.databinding.ItemContentFilterBinding
import com.onyx.browser.data.preferences.BrowserPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ContentFiltersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContentFiltersBinding
    private lateinit var adapter: ContentFilterAdapter
    private lateinit var preferences: BrowserPreferences
    private var allLists: List<FilterListEntry> = emptyList()
    private var filteredLists: MutableList<FilterListEntry> = mutableListOf()
    private var selectedCategory: String = "all"
    private var recompileJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge transparent system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode

        binding = ActivityContentFiltersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply WindowInsets to avoid collision with status bar, camera hole, and navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updatePadding(top = statusBarInsets.top)
            val defaultBottomPadding = (24 * resources.displayMetrics.density).toInt()
            binding.rvContentFilters.updatePadding(bottom = navBarInsets.bottom + defaultBottomPadding)
            insets
        }

        preferences = BrowserPreferences.getInstance(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        allLists = FilterListManager.ALL_FILTER_LISTS
        filteredLists = allLists.toMutableList()

        adapter = ContentFilterAdapter(filteredLists)
        binding.rvContentFilters.layoutManager = LinearLayoutManager(this)
        binding.rvContentFilters.adapter = adapter

        setupAutoUpdateCard()
        setupCategoryChips()

        binding.btnUpdateFilters.setOnClickListener {
            performUpdateFilters()
        }

        binding.etSearchFilter.doAfterTextChanged {
            applyFilter()
        }
    }

    private fun setupAutoUpdateCard() {
        binding.switchAutoUpdate.isChecked = preferences.isFilterAutoUpdateEnabled
        updateLastUpdatedDisplay()

        binding.cardAutoUpdate.setOnClickListener {
            val newState = !binding.switchAutoUpdate.isChecked
            binding.switchAutoUpdate.isChecked = newState
            preferences.isFilterAutoUpdateEnabled = newState
        }
    }

    private fun updateLastUpdatedDisplay() {
        val lastUpdated = FilterListManager.getLastUpdatedFormatted(this)
        binding.tvLastUpdated.text = getString(R.string.filter_last_updated, lastUpdated)
    }

    private fun setupCategoryChips() {
        binding.chipGroupCategories.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedCategory = when {
                checkedIds.contains(R.id.chipCore) -> "core"
                checkedIds.contains(R.id.chipPrivacy) -> "privacy"
                checkedIds.contains(R.id.chipAnnoyance) -> "annoyance"
                checkedIds.contains(R.id.chipSocial) -> "social"
                checkedIds.contains(R.id.chipRegional) -> "regional"
                else -> "all"
            }
            applyFilter()
        }
    }

    private fun applyFilter() {
        val query = binding.etSearchFilter.text?.toString()?.trim()?.lowercase() ?: ""
        filteredLists.clear()

        for (entry in allLists) {
            val matchesCategory = selectedCategory == "all" ||
                    entry.category.equals(selectedCategory, ignoreCase = true)
            val matchesQuery = query.isEmpty() ||
                    entry.title.lowercase().contains(query) ||
                    entry.subtitle.lowercase().contains(query)

            if (matchesCategory && matchesQuery) {
                filteredLists.add(entry)
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
            updateLastUpdatedDisplay()

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
