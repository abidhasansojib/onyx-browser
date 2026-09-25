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
import com.google.android.material.button.MaterialButtonToggleGroup
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
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(requireContext())
        adapter = TabsAdapter(
            onTabClicked = { tab ->
                if (tab.isIncognito && prefs.isBiometricIncognitoEnabled && !tabManager.isIncognitoUnlocked) {
                    promptBiometricAuth {
                        tabManager.selectTab(tab)
                        onTabSelected(tab)
                        dismiss()
                    }
                } else {
                    tabManager.selectTab(tab)
                    onTabSelected(tab)
                    dismiss()
                }
            },
            onTabClosed = { tab ->
                tabManager.closeTab(tab)
            },
            getSnapshot = { tabId ->
                tabManager.getSnapshot(tabId)
            },
            isTabLocked = { tab ->
                tab.isIncognito && prefs.isBiometricIncognitoEnabled && !tabManager.isIncognitoUnlocked
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

        binding.btnNormalTabs.setOnClickListener {
            if (isViewingIncognito) {
                isViewingIncognito = false
                updateTabModePillUI()
                refreshTabsList()
            }
        }

        binding.btnIncognitoTabs.setOnClickListener {
            if (!isViewingIncognito) {
                val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(requireContext())
                if (prefs.isBiometricIncognitoEnabled && !tabManager.isIncognitoUnlocked) {
                    promptBiometricAuth {
                        isViewingIncognito = true
                        updateTabModePillUI()
                        refreshTabsList()
                    }
                } else {
                    isViewingIncognito = true
                    updateTabModePillUI()
                    refreshTabsList()
                }
            }
        }

        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(requireContext())
        if (isViewingIncognito && prefs.isBiometricIncognitoEnabled && !tabManager.isIncognitoUnlocked) {
            isViewingIncognito = false
            updateTabModePillUI()
            promptBiometricAuth {
                isViewingIncognito = true
                updateTabModePillUI()
                refreshTabsList()
            }
        } else {
            updateTabModePillUI()
        }

        binding.btnTabSwitcherOverflow.setOnClickListener { v ->
            showOverflowMenu(v)
        }
    }

    private fun updateTabModePillUI() {
        val normalCount = tabManager.normalTabs.value.size
        binding.tvNormalTabCount.text = if (normalCount > 99) "99+" else normalCount.toString()

        val context = context ?: return
        val normalActive = !isViewingIncognito

        if (normalActive) {
            // Normal tab button selected: vibrant accent squircle + white tab box
            binding.btnNormalTabs.setBackgroundResource(R.drawable.bg_tab_pill_selected)
            binding.boxTabCount.setBackgroundResource(R.drawable.bg_tab_count_box)
            binding.tvNormalTabCount.setTextColor(android.graphics.Color.WHITE)

            // Incognito tab unselected: transparent + subtle gray icon
            binding.btnIncognitoTabs.background = null
            binding.ivIncognitoToggle.setColorFilter(android.graphics.Color.parseColor("#B0B3B8"))
        } else {
            // Incognito tab button selected: vibrant accent squircle + white sunglasses
            binding.btnIncognitoTabs.setBackgroundResource(R.drawable.bg_tab_pill_selected)
            binding.ivIncognitoToggle.setColorFilter(android.graphics.Color.WHITE)

            // Normal tab unselected: transparent + subtle gray tab box
            binding.btnNormalTabs.background = null
            val boxDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_tab_count_box)?.mutate()
            if (boxDrawable != null) {
                androidx.core.graphics.drawable.DrawableCompat.setTint(boxDrawable, android.graphics.Color.parseColor("#B0B3B8"))
                binding.boxTabCount.background = boxDrawable
            }
            binding.tvNormalTabCount.setTextColor(android.graphics.Color.parseColor("#B0B3B8"))
        }
    }

    private fun promptBiometricAuth(onSuccess: (() -> Unit)? = null) {
        val executor = androidx.core.content.ContextCompat.getMainExecutor(requireContext())
        val biometricPrompt = androidx.biometric.BiometricPrompt(this, executor,
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    tabManager.isIncognitoUnlocked = true
                    onSuccess?.invoke() ?: run {
                        isViewingIncognito = true
                        updateTabModePillUI()
                        refreshTabsList()
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    isViewingIncognito = false
                    updateTabModePillUI()
                    refreshTabsList()
                }
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Allow user retry on biometric prompt UI
                }
            })

        val promptInfo = com.onyx.browser.ui.common.BiometricAuthHelper.createPromptInfo(
            title = "Unlock Incognito Tabs",
            subtitle = "Confirm identity to access private tabs"
        )
        biometricPrompt.authenticate(promptInfo)
    }

    private fun observeTabs() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    tabManager.normalTabs.collectLatest { list ->
                        updateTabModePillUI()
                        if (!isViewingIncognito) {
                            renderTabs(list)
                        }
                    }
                }
                launch {
                    tabManager.incognitoTabs.collectLatest { list ->
                        updateTabModePillUI()
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

    fun refreshTabsList() {
        updateTabModePillUI()
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
