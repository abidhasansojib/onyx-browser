package com.onyx.browser.web

import com.onyx.browser.data.model.TabItem

/**
 * Thin callback interface so [OnyxWebViewClient] can trigger tab-level actions
 * without holding a direct reference to [com.onyx.browser.MainActivity].
 *
 * Previously [OnyxWebViewClient] walked the Context wrapper chain via
 * `findMainActivity()` to call MainActivity directly — a circular dependency.
 * [MainActivity] implements this interface and passes itself to [OnyxWebViewClient]
 * as the constructor parameter, breaking the cycle cleanly.
 */
interface TabActionCallback {
    /** Close the tab with the given ID. */
    fun closeTab(tabId: String)

    /** Make a popup tab visible to the user. */
    fun displayPopupTab(tab: TabItem)

    /** Return the [TabItem] for the given ID, or null if not found. */
    fun getTabById(tabId: String): TabItem?
}
