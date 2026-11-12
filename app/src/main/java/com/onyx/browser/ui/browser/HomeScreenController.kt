package com.onyx.browser.ui.browser

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityMainBinding
import com.onyx.browser.ui.home.AdjustQuickActionsBottomSheet
import com.onyx.browser.ui.home.EditShortcutDialog
import com.onyx.browser.ui.home.ManageShortcutsBottomSheet
import com.onyx.browser.ui.home.ShortcutsAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Manages the browser home screen shortcuts grid, drag-and-drop reordering,
 * shortcut add/edit dialogs, quick actions, and incognito branding state.
 */
class HomeScreenController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val preferences: BrowserPreferences,
    private val coroutineScope: LifecycleCoroutineScope,
    private val onNavigateToUrl: (String) -> Unit
) {
    lateinit var shortcutsAdapter: ShortcutsAdapter
        private set

    fun setup() {
        setupQuickActions()
        setupShortcuts()
    }

    fun setupShortcuts() {
        val home = binding.homeLayout

        shortcutsAdapter = ShortcutsAdapter(
            onShortcutClick = { shortcut ->
                if (shortcut.id == ShortcutItem.ID_ADD_SHORTCUT || shortcut.url == ShortcutItem.URL_ADD_SHORTCUT) {
                    showAddShortcutDialog()
                } else {
                    onNavigateToUrl(shortcut.url)
                }
            }
        )

        val shortcutSpanCount = if (activity.resources.configuration.screenWidthDp >= 600) 6 else 4
        home.rvShortcuts.layoutManager = GridLayoutManager(activity, shortcutSpanCount)
        home.rvShortcuts.adapter = shortcutsAdapter

        var hasDragged = false
        var draggedShortcut: ShortcutItem? = null

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
            0
        ) {
            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= shortcutsAdapter.getItems().size) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val targetPos = target.bindingAdapterPosition
                return targetPos != RecyclerView.NO_POSITION && targetPos < shortcutsAdapter.getItems().size
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION) {
                    hasDragged = true
                    return shortcutsAdapter.onItemMove(from, to)
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    val pos = viewHolder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                    if (pos != RecyclerView.NO_POSITION && pos < shortcutsAdapter.getItems().size) {
                        draggedShortcut = shortcutsAdapter.getItems()[pos]
                    }
                    viewHolder?.itemView?.animate()?.scaleX(1.10f)?.scaleY(1.10f)?.setDuration(150)?.start()
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                if (hasDragged) {
                    preferences.saveShortcuts(shortcutsAdapter.getItems())
                }
                draggedShortcut = null
                hasDragged = false
            }
        })
        touchHelper.attachToRecyclerView(home.rvShortcuts)

        coroutineScope.launch {
            preferences.shortcutsFlow.collectLatest { shortcuts ->
                shortcutsAdapter.submitList(shortcuts)
            }
        }
    }

    fun refreshShortcuts() {
        if (::shortcutsAdapter.isInitialized) {
            shortcutsAdapter.submitList(preferences.getShortcuts())
        }
    }

    fun showAddShortcutDialog() {
        if (activity.isFinishing || activity.isDestroyed) return
        val sheet = ManageShortcutsBottomSheet()
        sheet.show(activity.supportFragmentManager, ManageShortcutsBottomSheet.TAG)
    }

    fun showEditShortcutDialog(shortcut: ShortcutItem) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = EditShortcutDialog(shortcut) { updated ->
            preferences.updateShortcut(updated)
            Toast.makeText(activity, R.string.shortcut_updated, Toast.LENGTH_SHORT).show()
        }
        dialog.show(activity.supportFragmentManager, EditShortcutDialog.TAG)
    }

    fun setupQuickActions() {
        // Quick actions are integrated with the home shortcuts grid and ManageShortcutsBottomSheet /
        // AdjustQuickActionsBottomSheet.
    }

    fun showAdjustQuickActionsSheet() {
        if (activity.isFinishing || activity.isDestroyed) return
        val sheet = AdjustQuickActionsBottomSheet()
        sheet.show(activity.supportFragmentManager, AdjustQuickActionsBottomSheet.TAG)
    }

    fun updateIncognitoUI(isIncognito: Boolean) {
        val home = binding.homeLayout
        if (isIncognito) {
            home.ivBrandLogo.visibility = View.GONE
            home.tvTagline.visibility = View.GONE
            home.incognitoHeader.visibility = View.VISIBLE
            binding.ivIncognitoIndicator.visibility = View.VISIBLE
            binding.etUrl.hint = activity.getString(R.string.search_privately)
            binding.etUrl.imeOptions = binding.etUrl.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        } else {
            home.ivBrandLogo.visibility = View.VISIBLE
            home.tvTagline.visibility = View.VISIBLE
            home.incognitoHeader.visibility = View.GONE
            binding.ivIncognitoIndicator.visibility = View.GONE
            binding.etUrl.hint = activity.getString(R.string.search_or_type_url)
            binding.etUrl.imeOptions = binding.etUrl.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
        }
    }
}
