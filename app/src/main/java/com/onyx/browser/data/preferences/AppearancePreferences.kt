package com.onyx.browser.data.preferences

import com.onyx.browser.data.model.ShortcutItem
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain-scoped preference store for browser appearance, theme, toolbar layout,
 * and start page customization.
 */
class AppearancePreferences(private val browserPrefs: BrowserPreferences) {

    var themeMode: Int
        get() = browserPrefs.themeMode
        set(value) {
            browserPrefs.themeMode = value
        }

    var accentColorPreset: String
        get() = browserPrefs.accentColorPreset
        set(value) {
            browserPrefs.accentColorPreset = value
        }

    var accentColorHex: String
        get() = browserPrefs.accentColorHex
        set(value) {
            browserPrefs.accentColorHex = value
        }

    var isAddressBarAtBottom: Boolean
        get() = browserPrefs.isAddressBarAtBottom
        set(value) {
            browserPrefs.isAddressBarAtBottom = value
        }

    var isAddressBarScrollHideEnabled: Boolean
        get() = browserPrefs.isAddressBarScrollHideEnabled
        set(value) {
            browserPrefs.isAddressBarScrollHideEnabled = value
        }

    var isAutoHideToolbarEnabled: Boolean
        get() = browserPrefs.isAutoHideToolbarEnabled
        set(value) {
            browserPrefs.isAutoHideToolbarEnabled = value
        }

    var isHomeReplacedWithRefresh: Boolean
        get() = browserPrefs.isHomeReplacedWithRefresh
        set(value) {
            browserPrefs.isHomeReplacedWithRefresh = value
        }

    var isShowShortcutsOnHome: Boolean
        get() = browserPrefs.isShowShortcutsOnHome
        set(value) {
            browserPrefs.isShowShortcutsOnHome = value
        }

    var isShowQuickActionsOnHome: Boolean
        get() = browserPrefs.isShowQuickActionsOnHome
        set(value) {
            browserPrefs.isShowQuickActionsOnHome = value
        }

    var pageZoomPercent: Int
        get() = browserPrefs.pageZoomPercent
        set(value) {
            browserPrefs.pageZoomPercent = value
        }

    var isForceDarkModeEnabled: Boolean
        get() = browserPrefs.isForceDarkModeEnabled
        set(value) {
            browserPrefs.isForceDarkModeEnabled = value
        }

    var isPullToRefreshEnabled: Boolean
        get() = browserPrefs.isPullToRefreshEnabled
        set(value) {
            browserPrefs.isPullToRefreshEnabled = value
        }

    var isScrollToTopEnabled: Boolean
        get() = browserPrefs.isScrollToTopEnabled
        set(value) {
            browserPrefs.isScrollToTopEnabled = value
        }

    val shortcutsFlow: StateFlow<List<ShortcutItem>>
        get() = browserPrefs.shortcutsFlow

    fun applyTheme() {
        browserPrefs.applyTheme()
    }

    fun isDarkMode(): Boolean = browserPrefs.isDarkMode()

    fun getShortcuts(): List<ShortcutItem> = browserPrefs.getShortcuts()

    fun saveShortcuts(shortcuts: List<ShortcutItem>) {
        browserPrefs.saveShortcuts(shortcuts)
    }

    fun getQuickActionOrder(): List<String> = browserPrefs.getQuickActionOrder()

    fun saveQuickActionOrder(order: List<String>) {
        browserPrefs.saveQuickActionOrder(order)
    }
}
