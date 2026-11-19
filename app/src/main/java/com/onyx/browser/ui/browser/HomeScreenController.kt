package com.onyx.browser.ui.browser

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleCoroutineScope
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityMainBinding
import com.onyx.browser.ui.home.AdjustQuickActionsBottomSheet
import com.onyx.browser.ui.home.EditShortcutDialog
import com.onyx.browser.ui.home.HomeScreenContent
import com.onyx.browser.ui.home.ManageShortcutsBottomSheet
import com.onyx.browser.ui.theme.OnyxTheme

/**
 * Manages the browser home screen shortcuts grid, quick actions, and incognito branding state
 * using Jetpack Compose and Material 3.
 */
class HomeScreenController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val preferences: BrowserPreferences,
    private val coroutineScope: LifecycleCoroutineScope,
    private val onNavigateToUrl: (String) -> Unit
) {
    private val isIncognitoState = mutableStateOf(false)

    fun setup() {
        setupShortcuts()
    }

    fun setupShortcuts() {
        binding.homeLayout.composeHomeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                OnyxTheme {
                    HomeScreenContent(
                        isIncognito = isIncognitoState.value,
                        preferences = preferences,
                        onNavigateToUrl = onNavigateToUrl,
                        onAddShortcut = { showAddShortcutDialog() },
                        onEditShortcut = { showEditShortcutDialog(it) }
                    )
                }
            }
        }
    }

    fun refreshShortcuts() {
        // Compose automatically re-renders when shortcutsFlow emits a new list.
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
        // Quick actions are integrated with ManageShortcutsBottomSheet
    }

    fun showAdjustQuickActionsSheet() {
        if (activity.isFinishing || activity.isDestroyed) return
        val sheet = AdjustQuickActionsBottomSheet()
        sheet.show(activity.supportFragmentManager, AdjustQuickActionsBottomSheet.TAG)
    }

    fun updateIncognitoUI(isIncognito: Boolean) {
        isIncognitoState.value = isIncognito
        if (isIncognito) {
            binding.ivIncognitoIndicator.visibility = View.VISIBLE
            binding.etUrl.hint = activity.getString(R.string.search_privately)
            binding.etUrl.imeOptions = binding.etUrl.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        } else {
            binding.ivIncognitoIndicator.visibility = View.GONE
            binding.etUrl.hint = activity.getString(R.string.search_or_type_url)
            binding.etUrl.imeOptions = binding.etUrl.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
        }
    }
}
