package com.onyx.browser.ui.tabs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.PopupWindow
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
import com.onyx.browser.databinding.PopupTabSwitcherMenuBinding
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
    private val binding get() = _binding

    private lateinit var adapter: TabsAdapter
    private var isViewingIncognito = false
    private var isDismissing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            statusBarColor = android.graphics.Color.parseColor("#131314")
            navigationBarColor = android.graphics.Color.parseColor("#131314")
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetTabSwitcherBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = _binding ?: return
        isDismissing = false

        isViewingIncognito = tabManager.activeTab.value?.isIncognito ?: false

        // Apply Window Insets for topBar and bottomBar
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val currentBinding = _binding ?: return@setOnApplyWindowInsetsListener insets
            val statusBars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            currentBinding.tabSwitcherTopBar.setPadding(
                currentBinding.tabSwitcherTopBar.paddingLeft,
                statusBars.top,
                currentBinding.tabSwitcherTopBar.paddingRight,
                currentBinding.tabSwitcherTopBar.paddingBottom
            )
            currentBinding.bottomBar.setPadding(
                currentBinding.bottomBar.paddingLeft,
                currentBinding.bottomBar.paddingTop,
                currentBinding.bottomBar.paddingRight,
                navBars.bottom
            )
            insets
        }

        setupRecyclerView(b)
        setupTopControls(b)
        setupBottomControls(b)
        observeTabs()
    }

    private fun setupRecyclerView(b: BottomSheetTabSwitcherBinding) {
        val context = context ?: return
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        adapter = TabsAdapter(
            onTabClicked = { tab ->
                if (isDismissing) return@TabsAdapter
                if (tab.isIncognito && prefs.isBiometricIncognitoEnabled && !tabManager.isIncognitoUnlocked) {
                    promptBiometricAuth {
                        isDismissing = true
                        tabManager.selectTab(tab)
                        onTabSelected(tab)
                        dismissAllowingStateLoss()
                    }
                } else {
                    isDismissing = true
                    tabManager.selectTab(tab)
                    onTabSelected(tab)
                    dismissAllowingStateLoss()
                }
            },
            onTabClosed = { tab ->
                if (isDismissing) return@TabsAdapter
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
        b.rvTabs.layoutManager = GridLayoutManager(requireContext(), spanCount)
        b.rvTabs.adapter = adapter

        // Swipe-to-dismiss gesture support
        val swipeHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                if (isDismissing) return
                val position = viewHolder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && position < adapter.currentList.size) {
                    val tab = adapter.currentList[position]
                    tabManager.closeTab(tab)
                }
            }
        })
        swipeHelper.attachToRecyclerView(b.rvTabs)
    }

    private fun setupTopControls(b: BottomSheetTabSwitcherBinding) {
        b.btnSearchTabSwitcher.setOnClickListener {
            if (isDismissing) return@setOnClickListener
            isDismissing = true
            dismissAllowingStateLoss()
            onSearchRequested?.invoke()
        }

        b.btnNormalTabs.setOnClickListener {
            if (isViewingIncognito && !isDismissing) {
                isViewingIncognito = false
                updateTabModePillUI()
                refreshTabsList()
            }
        }

        b.btnIncognitoTabs.setOnClickListener {
            if (!isViewingIncognito && !isDismissing) {
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

        b.btnTabSwitcherOverflow.setOnClickListener { v ->
            if (!isDismissing) {
                showOverflowMenu(v)
            }
        }
    }

    private fun updateTabModePillUI() {
        val b = _binding ?: return
        val normalCount = tabManager.normalTabs.value.size
        b.tvNormalTabCount.text = if (normalCount > 99) "99+" else normalCount.toString()

        val context = context ?: return
        val normalActive = !isViewingIncognito

        if (normalActive) {
            // Normal tab button selected: vibrant pink accent squircle + white tab box
            b.btnNormalTabs.setBackgroundResource(R.drawable.bg_tab_pill_selected)
            b.boxTabCount.setBackgroundResource(R.drawable.bg_tab_count_box)
            b.tvNormalTabCount.setTextColor(android.graphics.Color.WHITE)

            // Incognito tab unselected: rounded ripple + subtle muted icon
            b.btnIncognitoTabs.setBackgroundResource(R.drawable.bg_tab_pill_unselected)
            b.ivIncognitoToggle.setColorFilter(android.graphics.Color.parseColor("#9AA0A6"))
        } else {
            // Incognito tab button selected: vibrant pink accent squircle + white fedora/glasses icon
            b.btnIncognitoTabs.setBackgroundResource(R.drawable.bg_tab_pill_selected)
            b.ivIncognitoToggle.setColorFilter(android.graphics.Color.WHITE)

            // Normal tab unselected: rounded ripple + subtle muted tab box
            b.btnNormalTabs.setBackgroundResource(R.drawable.bg_tab_pill_unselected)
            val boxDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_tab_count_box)?.mutate()
            if (boxDrawable != null) {
                androidx.core.graphics.drawable.DrawableCompat.setTint(boxDrawable, android.graphics.Color.parseColor("#9AA0A6"))
                b.boxTabCount.background = boxDrawable
            }
            b.tvNormalTabCount.setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
        }
    }

    private fun promptBiometricAuth(onSuccess: (() -> Unit)? = null) {
        val context = context ?: return
        val executor = androidx.core.content.ContextCompat.getMainExecutor(context)
        val biometricPrompt = androidx.biometric.BiometricPrompt(this, executor,
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    tabManager.isIncognitoUnlocked = true
                    if (isDismissing || _binding == null) return
                    onSuccess?.invoke() ?: run {
                        isViewingIncognito = true
                        updateTabModePillUI()
                        refreshTabsList()
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (isDismissing || _binding == null) return
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
                        if (isDismissing || _binding == null) return@collectLatest
                        updateTabModePillUI()
                        if (!isViewingIncognito) {
                            renderTabs(list)
                        }
                    }
                }
                launch {
                    tabManager.incognitoTabs.collectLatest { list ->
                        if (isDismissing || _binding == null) return@collectLatest
                        updateTabModePillUI()
                        if (isViewingIncognito) {
                            renderTabs(list)
                        }
                    }
                }
                launch {
                    tabManager.activeTab.collectLatest { active ->
                        if (isDismissing || _binding == null) return@collectLatest
                        adapter.activeTabId = active?.id
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    fun refreshTabsList() {
        if (isDismissing || _binding == null) return
        updateTabModePillUI()
        val list = if (isViewingIncognito) {
            tabManager.incognitoTabs.value
        } else {
            tabManager.normalTabs.value
        }
        renderTabs(list)
    }

    private fun renderTabs(list: List<TabItem>) {
        if (isDismissing) return
        val b = _binding ?: return
        adapter.activeTabId = tabManager.activeTab.value?.id
        adapter.submitList(ArrayList(list)) {
            val currentBinding = _binding ?: return@submitList
            val isEmpty = list.isEmpty()
            currentBinding.emptyTabsView.visibility = if (isEmpty) View.VISIBLE else View.GONE
            currentBinding.rvTabs.visibility = if (isEmpty) View.GONE else View.VISIBLE

            // Background watermark is ONLY shown when viewing incognito tabs AND tabs exist in the grid.
            // When there are no tabs (isEmpty == true), emptyTabsView displays the single central incognito icon,
            // completely eliminating the duplicate incognito logo.
            currentBinding.ivIncognitoBackground.visibility = if (isViewingIncognito && !isEmpty) View.VISIBLE else View.GONE

            if (isEmpty) {
                if (isViewingIncognito) {
                    currentBinding.ivEmptyIcon.setImageResource(R.drawable.ic_incognito)
                    currentBinding.ivEmptyIcon.alpha = 0.40f
                    currentBinding.tvEmptyTitle.text = getString(R.string.incognito_tabs)
                    currentBinding.tvEmptySubtitle.text = getString(R.string.incognito_privacy_desc)
                } else {
                    currentBinding.ivEmptyIcon.setImageResource(R.drawable.ic_logo_onyx)
                    currentBinding.ivEmptyIcon.alpha = 0.22f
                    currentBinding.tvEmptyTitle.text = "No tabs open"
                    currentBinding.tvEmptySubtitle.text = "Tap + to open a new tab"
                }
            }
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val context = context ?: return
        val inflater = LayoutInflater.from(context)
        val menuBinding = PopupTabSwitcherMenuBinding.inflate(inflater)

        val density = context.resources.displayMetrics.density
        val popupWidth = (180 * density).toInt()

        val popup = PopupWindow(
            menuBinding.root,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 24f
        }

        menuBinding.itemNewTab.setOnClickListener {
            popup.dismiss()
            if (!isDismissing) {
                isDismissing = true
                onNewTabRequested(false)
                dismissAllowingStateLoss()
            }
        }

        menuBinding.itemNewIncognitoTab.setOnClickListener {
            popup.dismiss()
            if (!isDismissing) {
                isDismissing = true
                onNewTabRequested(true)
                dismissAllowingStateLoss()
            }
        }

        menuBinding.itemCloseAllTabs.setOnClickListener {
            popup.dismiss()
            if (!isDismissing) confirmCloseAllTabs()
        }

        menuBinding.itemClearBrowsingData.setOnClickListener {
            popup.dismiss()
            if (!isDismissing) showClearBrowsingDataDialog()
        }

        val anchorWidth = if (anchor.width > 0) anchor.width else (40 * density).toInt()
        val xOffset = anchorWidth - popupWidth
        val yOffset = (4 * density).toInt()
        popup.showAsDropDown(anchor, xOffset, yOffset)
    }

    private fun setupBottomControls(b: BottomSheetTabSwitcherBinding) {
        // Left Button: Clear Browsing Data (Brush icon)
        b.btnClearHistory.setOnClickListener {
            if (!isDismissing) showClearBrowsingDataDialog()
        }

        // Center Button: New Tab
        b.fabNewTab.setOnClickListener {
            if (isDismissing) return@setOnClickListener
            isDismissing = true
            onNewTabRequested(isViewingIncognito)
            dismissAllowingStateLoss()
        }

        // Right Button: Close All Open Tabs in current mode
        b.btnCloseAllTabs.setOnClickListener {
            if (!isDismissing) confirmCloseAllTabs()
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
        isDismissing = true
        _binding?.rvTabs?.adapter = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "TabSwitcherBottomSheet"
    }
}
