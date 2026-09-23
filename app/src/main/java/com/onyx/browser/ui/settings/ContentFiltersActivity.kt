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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import com.onyx.browser.data.filter.FilterListEntry
import com.onyx.browser.data.filter.FilterListManager
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityContentFiltersBinding
import com.onyx.browser.databinding.ItemContentFilterBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ContentFiltersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContentFiltersBinding
    private lateinit var adapter: ContentFilterAdapter
    private lateinit var preferences: BrowserPreferences
    private var allLists: List<FilterListEntry> = emptyList()
    private var filteredLists: MutableList<FilterListEntry> = mutableListOf()
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
        // Auto update is always enabled
        preferences.isFilterAutoUpdateEnabled = true

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Setup update button (Logo): single tap updates, long click selects auto-update interval
        binding.btnUpdateFilters.setOnClickListener {
            performUpdateFilters()
        }
        binding.btnUpdateFilters.setOnLongClickListener {
            showUpdateIntervalDialog()
            true
        }

        // Action Buttons: + Add filter via URL & Create custom filters
        binding.btnAddFilterViaUrl.setOnClickListener {
            AddFilterUrlBottomSheet {
                reloadLists()
            }.show(supportFragmentManager, "add_filter_url")
        }

        binding.btnCreateCustomFilter.setOnClickListener {
            CreateCustomFilterBottomSheet {
                reloadLists()
            }.show(supportFragmentManager, "create_custom_filter")
        }

        binding.etSearchFilter.doAfterTextChanged {
            applyFilter()
        }

        adapter = ContentFilterAdapter(filteredLists)
        binding.rvContentFilters.layoutManager = LinearLayoutManager(this)
        binding.rvContentFilters.adapter = adapter

        reloadLists()
    }

    private fun reloadLists() {
        allLists = FilterListManager.getAllLists(this)
        applyFilter()
        updateSubtitleDisplay()
    }

    private fun updateSubtitleDisplay() {
        val hours = preferences.filterAutoUpdateIntervalHours
        val intervalLabel = when (hours) {
            6 -> "Every 6h"
            12 -> "Every 12h"
            24 -> "Every 24h"
            72 -> "Every 3 days"
            168 -> "Every 7 days"
            else -> "Every ${hours}h"
        }
        val lastUpdated = FilterListManager.getLastUpdatedFormatted(this)
        binding.tvSubtitle.text = "Auto-update: $intervalLabel • Updated: $lastUpdated"
    }

    private fun showUpdateIntervalDialog() {
        val intervals = listOf(
            6 to "Every 6 hours",
            12 to "Every 12 hours",
            24 to "Every 1 day (24 hours)",
            72 to "Every 3 days",
            168 to "Every 7 days (1 week)"
        )
        val currentHours = preferences.filterAutoUpdateIntervalHours
        val currentIndex = intervals.indexOfFirst { it.first == currentHours }.let {
            if (it >= 0) it else 2 // default to 24h
        }

        val labels = intervals.map { it.second }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.filter_auto_update_period_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val selectedHours = intervals[which].first
                preferences.filterAutoUpdateIntervalHours = selectedHours
                preferences.isFilterAutoUpdateEnabled = true
                updateSubtitleDisplay()
                Toast.makeText(
                    this,
                    "Auto-update set to ${intervals[which].second.lowercase()}",
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyFilter() {
        val query = binding.etSearchFilter.text?.toString()?.trim()?.lowercase() ?: ""
        filteredLists.clear()

        for (entry in allLists) {
            val matchesQuery = query.isEmpty() ||
                    entry.title.lowercase().contains(query) ||
                    entry.subtitle.lowercase().contains(query)

            if (matchesQuery) {
                filteredLists.add(entry)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun performUpdateFilters() {
        binding.btnUpdateFilters.isEnabled = false
        binding.btnUpdateFilters.animate().rotationBy(360f).setDuration(600).start()
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
            updateSubtitleDisplay()

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
            val binding = holder.itemBinding

            binding.tvFilterTitle.text = item.title
            binding.tvFilterSubtitle.text = item.subtitle

            // Custom Filter UI elements
            if (item.isCustom) {
                binding.tvCustomBadge.visibility = View.VISIBLE
                binding.btnDeleteFilter.visibility = View.VISIBLE

                binding.btnDeleteFilter.setOnClickListener {
                    MaterialAlertDialogBuilder(this@ContentFiltersActivity)
                        .setTitle(R.string.delete_custom_filter_title)
                        .setMessage(getString(R.string.delete_custom_filter_confirm, item.title))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            preferences.deleteCustomFilter(item.id)
                            scheduleRecompile()
                            reloadLists()
                            Toast.makeText(
                                this@ContentFiltersActivity,
                                getString(R.string.custom_filter_deleted, item.title),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }

                // If manual rule filter, tapping opens editor
                if (!item.isUrlType) {
                    binding.root.setOnClickListener {
                        val existing = preferences.getCustomFilters().firstOrNull { it.id == item.id }
                        CreateCustomFilterBottomSheet(existing) {
                            reloadLists()
                        }.show(supportFragmentManager, "edit_custom_filter")
                    }
                } else {
                    binding.root.setOnClickListener {
                        val newState = !binding.switchFilter.isChecked
                        binding.switchFilter.isChecked = newState
                        FilterListManager.setListEnabled(this@ContentFiltersActivity, item.id, newState)
                        scheduleRecompile()
                    }
                }
            } else {
                binding.tvCustomBadge.visibility = View.GONE
                binding.btnDeleteFilter.visibility = View.GONE

                binding.root.setOnClickListener {
                    val newState = !binding.switchFilter.isChecked
                    binding.switchFilter.isChecked = newState
                    FilterListManager.setListEnabled(this@ContentFiltersActivity, item.id, newState)
                    scheduleRecompile()
                }
            }

            val isEnabled = FilterListManager.isListEnabled(this@ContentFiltersActivity, item.id)
            binding.switchFilter.isChecked = isEnabled

            // Ensure switch directly triggers toggle without triggering row click listener
            binding.switchFilter.setOnClickListener {
                val newState = binding.switchFilter.isChecked
                FilterListManager.setListEnabled(this@ContentFiltersActivity, item.id, newState)
                scheduleRecompile()
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
