package com.onyx.browser.ui.tabs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.databinding.BottomSheetTabSwitcherBinding
import com.onyx.browser.ui.browser.TabManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TabSwitcherBottomSheet(
    private val tabManager: TabManager,
    private val coroutineScope: CoroutineScope,
    private val onTabSelected: (TabItem) -> Unit,
    private val onNewTabRequested: (Boolean) -> Unit,
    private val onSearchRequested: (() -> Unit)? = null
) : DialogFragment() {

    private var _binding: BottomSheetTabSwitcherBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TabsAdapter
    private var isViewingIncognito = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
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

        // Apply Window Insets for topBar and bottomBar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.tabSwitcherTopBar.setPadding(
                binding.tabSwitcherTopBar.paddingLeft,
                statusBars.top,
                binding.tabSwitcherTopBar.paddingRight,
                binding.tabSwitcherTopBar.paddingBottom
            )
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                binding.bottomBar.paddingTop,
                binding.bottomBar.paddingRight,
                navBars.bottom
            )
            insets
        }

        setupRecyclerView()
        setupTopControls()
        setupBottomControls()
        observeTabs()
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
            },
            getSnapshot = { tabId ->
                tabManager.snapshotCache.get(tabId)
            }
        )

        val spanCount = if (resources.configuration.screenWidthDp >= 600) 3 else 2
        binding.rvTabs.layoutManager = GridLayoutManager(requireContext(), spanCount)
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
                if (position != RecyclerView.NO_POSITION && position < adapter.currentList.size) {
                    val tab = adapter.currentList[position]
                    tabManager.closeTab(tab)
                }
            }
        })
        swipeHelper.attachToRecyclerView(binding.rvTabs)
    }

    private fun setupTopControls() {
        binding.btnSearchTabSwitcher.setOnClickListener {
            dismiss()
            onSearchRequested?.invoke()
        }

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

    private fun observeTabs() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    tabManager.normalTabs.collectLatest { list ->
                        if (!isViewingIncognito) {
                            renderTabs(list)
                        }
                    }
                }
                launch {
                    tabManager.incognitoTabs.collectLatest { list ->
                        if (isViewingIncognito) {
                            renderTabs(list)
                        }
                    }
                }
                launch {
                    tabManager.activeTab.collectLatest { active ->
                        adapter.activeTabId = active?.id
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun refreshTabsList() {
        val list = if (isViewingIncognito) {
            tabManager.incognitoTabs.value
        } else {
            tabManager.normalTabs.value
        }
        renderTabs(list)
    }

    private fun renderTabs(list: List<TabItem>) {
        adapter.activeTabId = tabManager.activeTab.value?.id
        adapter.submitList(ArrayList(list)) {
            val isEmpty = list.isEmpty()
            binding.emptyTabsView.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.rvTabs.visibility = if (isEmpty) View.GONE else View.VISIBLE
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
                    showClearBrowsingDataDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun setupBottomControls() {
        // Left Button: Clear Browsing Data (Brush icon)
        binding.btnClearHistory.setOnClickListener {
            showClearBrowsingDataDialog()
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

    private fun showClearBrowsingDataDialog() {
        val dialog = ClearBrowsingDataDialog(
            tabManager = tabManager,
            onDataCleared = {
                refreshTabsList()
            }
        )
        dialog.show(childFragmentManager, ClearBrowsingDataDialog.TAG)
    }

    private fun confirmCloseAllTabs() {
        val tabList = if (isViewingIncognito) {
            tabManager.incognitoTabs.value
        } else {
            tabManager.normalTabs.value
        }
        if (tabList.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_tabs_to_close, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = CloseAllTabsDialog(
            tabManager = tabManager,
            isIncognito = isViewingIncognito,
            onTabsClosed = {
                refreshTabsList()
            }
        )
        dialog.show(childFragmentManager, CloseAllTabsDialog.TAG)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "TabSwitcherBottomSheet"
    }
}
