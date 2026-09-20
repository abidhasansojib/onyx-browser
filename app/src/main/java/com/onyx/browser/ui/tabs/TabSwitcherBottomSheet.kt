package com.onyx.browser.ui.tabs

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.databinding.BottomSheetTabSwitcherBinding
import com.onyx.browser.ui.browser.TabManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TabSwitcherBottomSheet(
    private val tabManager: TabManager,
    private val coroutineScope: CoroutineScope,
    private val onTabSelected: (TabItem) -> Unit,
    private val onNewTabRequested: (Boolean) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetTabSwitcherBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TabsAdapter
    private var isViewingIncognito = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetTabSwitcherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isViewingIncognito = tabManager.activeTab.value?.isIncognito ?: false

        setupRecyclerView()
        setupTopControls()
        setupBottomControls()
        refreshTabsList()
    }

    private fun setupRecyclerView() {
        adapter = TabsAdapter(
            onTabClicked = { tab ->
                tabManager.selectTab(tab)
                onTabSelected(tab)
                dismiss()
            },
            onTabClosed = { tab ->
                tabManager.closeTab(tab)
                refreshTabsList()
            }
        )

        binding.rvTabs.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvTabs.adapter = adapter

        // Swipe-to-dismiss gesture support
        val swipeHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val tab = adapter.currentList[position]
                    tabManager.closeTab(tab)
                    refreshTabsList()
                }
            }
        })
        swipeHelper.attachToRecyclerView(binding.rvTabs)
    }

    private fun setupTopControls() {
        binding.tabModeLayout.getTabAt(if (isViewingIncognito) 1 else 0)?.select()

        binding.tabModeLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                isViewingIncognito = (tab?.position == 1)
                refreshTabsList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.btnTabSwitcherOverflow.setOnClickListener { v ->
            showOverflowMenu(v)
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.apply {
            add(0, 1, 0, getString(R.string.new_tab))
            add(0, 2, 1, getString(R.string.new_incognito_tab))
            add(0, 3, 2, getString(R.string.close_all_tabs))
            add(0, 4, 3, getString(R.string.delete_browsing_history))
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    onNewTabRequested(false)
                    dismiss()
                    true
                }
                2 -> {
                    onNewTabRequested(true)
                    dismiss()
                    true
                }
                3 -> {
                    confirmCloseAllTabs()
                    true
                }
                4 -> {
                    confirmClearHistory()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun setupBottomControls() {
        // Left Button: Clear History
        binding.btnClearHistory.setOnClickListener {
            confirmClearHistory()
        }

        // Center Button: New Tab
        binding.fabNewTab.setOnClickListener {
            onNewTabRequested(isViewingIncognito)
            dismiss()
        }

        // Right Button: Close All Open Tabs in current mode
        binding.btnCloseAllTabs.setOnClickListener {
            confirmCloseAllTabs()
        }
    }

    private fun refreshTabsList() {
        val list = if (isViewingIncognito) {
            tabManager.incognitoTabs.value
        } else {
            tabManager.normalTabs.value
        }

        adapter.activeTabId = tabManager.activeTab.value?.id
        adapter.submitList(list)

        binding.emptyTabsView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.rvTabs.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_browsing_history)
            .setMessage(R.string.confirm_clear_history)
            .setPositiveButton(R.string.clear) { _, _ ->
                coroutineScope.launch(Dispatchers.IO) {
                    AppDatabase.getInstance(requireContext()).historyDao().clearAllHistory()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmCloseAllTabs() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.close_all_tabs)
            .setMessage(R.string.confirm_close_all_tabs)
            .setPositiveButton(R.string.close) { _, _ ->
                tabManager.closeAllTabs(incognitoOnly = isViewingIncognito)
                refreshTabsList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "TabSwitcherBottomSheet"
    }
}
