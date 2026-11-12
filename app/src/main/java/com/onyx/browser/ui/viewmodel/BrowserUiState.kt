package com.onyx.browser.ui.viewmodel

import com.onyx.browser.data.model.TabItem

data class BrowserUiState(
    val activeTab: TabItem? = null,
    val normalTabCount: Int = 0,
    val incognitoTabCount: Int = 0,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isIncognito: Boolean = false,
    val isDesktopMode: Boolean = false,
    val blockedAdsCount: Long = 0L,
    val isSearchOverlayVisible: Boolean = false,
    val isAddressBarAtBottom: Boolean = false
)
