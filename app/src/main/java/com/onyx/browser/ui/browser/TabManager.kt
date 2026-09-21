package com.onyx.browser.ui.browser

import android.content.Context
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.web.OnyxWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class TabManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    private val database = AppDatabase.getInstance(context)

    private val _normalTabs = MutableStateFlow<List<TabItem>>(emptyList())
    val normalTabs: StateFlow<List<TabItem>> = _normalTabs.asStateFlow()

    private val _incognitoTabs = MutableStateFlow<List<TabItem>>(emptyList())
    val incognitoTabs: StateFlow<List<TabItem>> = _incognitoTabs.asStateFlow()

    private val _activeTab = MutableStateFlow<TabItem?>(null)
    val activeTab: StateFlow<TabItem?> = _activeTab.asStateFlow()

    private val webViewPool = mutableMapOf<String, OnyxWebView>()

    suspend fun restoreTabs() = withContext(Dispatchers.IO) {
        val savedTabs = database.tabDao().getAllNormalTabs()
        if (savedTabs.isNotEmpty()) {
            _normalTabs.value = savedTabs
            val firstTab = savedTabs.first()
            _activeTab.value = firstTab
        } else {
            // Create default initial tab
            val defaultTab = TabItem(
                id = UUID.randomUUID().toString(),
                url = "",
                title = "New Tab",
                isIncognito = false,
                position = 0
            )
            database.tabDao().insertTab(defaultTab)
            _normalTabs.value = listOf(defaultTab)
            _activeTab.value = defaultTab
        }
    }

    fun getOrCreateWebView(tab: TabItem): OnyxWebView {
        var webView = webViewPool[tab.id]
        if (webView == null) {
            webView = OnyxWebView(context).apply {
                tabId = tab.id
                setIncognitoMode(tab.isIncognito)
            }
            webViewPool[tab.id] = webView
        }
        return webView
    }

    fun getActiveWebView(): OnyxWebView? {
        val current = _activeTab.value ?: return null
        return webViewPool[current.id]
    }

    fun createNewTab(url: String = "", isIncognito: Boolean = false): TabItem {
        val newTab = TabItem(
            id = UUID.randomUUID().toString(),
            url = url,
            title = if (url.isBlank()) "New Tab" else url,
            isIncognito = isIncognito,
            position = if (isIncognito) _incognitoTabs.value.size else _normalTabs.value.size
        )

        if (isIncognito) {
            _incognitoTabs.value = _incognitoTabs.value + newTab
        } else {
            _normalTabs.value = _normalTabs.value + newTab
            coroutineScope.launch(Dispatchers.IO) {
                database.tabDao().insertTab(newTab)
            }
        }

        _activeTab.value = newTab
        return newTab
    }

    fun selectTab(tab: TabItem) {
        _activeTab.value = tab
    }

    fun closeTab(tab: TabItem) {
        // Safe destruction of associated WebView
        val webView = webViewPool.remove(tab.id)
        webView?.destroySafely()

        if (tab.isIncognito) {
            val updated = _incognitoTabs.value.filter { it.id != tab.id }
            _incognitoTabs.value = updated
            if (_activeTab.value?.id == tab.id) {
                _activeTab.value = updated.lastOrNull() ?: _normalTabs.value.lastOrNull()
            }
        } else {
            val updated = _normalTabs.value.filter { it.id != tab.id }
            _normalTabs.value = updated
            coroutineScope.launch(Dispatchers.IO) {
                database.tabDao().deleteTabById(tab.id)
            }
            if (_activeTab.value?.id == tab.id) {
                if (updated.isNotEmpty()) {
                    _activeTab.value = updated.last()
                } else {
                    // Always maintain at least one tab
                    createNewTab(isIncognito = false)
                }
            }
        }
    }

    fun closeAllTabs(incognitoOnly: Boolean) {
        if (incognitoOnly) {
            _incognitoTabs.value.forEach { tab ->
                webViewPool.remove(tab.id)?.destroySafely()
            }
            _incognitoTabs.value = emptyList()
            if (_activeTab.value?.isIncognito == true) {
                _activeTab.value = _normalTabs.value.firstOrNull() ?: createNewTab(isIncognito = false)
            }
        } else {
            _normalTabs.value.forEach { tab ->
                webViewPool.remove(tab.id)?.destroySafely()
            }
            _normalTabs.value = emptyList()
            coroutineScope.launch(Dispatchers.IO) {
                database.tabDao().clearNormalTabs()
            }
            val newTab = createNewTab(isIncognito = false)
            _activeTab.value = newTab
        }
    }

    fun closeTabsCreatedSince(sinceTime: Long) {
        val normalToClose = _normalTabs.value.filter { it.createdAt >= sinceTime }
        val incognitoToClose = _incognitoTabs.value.filter { it.createdAt >= sinceTime }

        for (tab in normalToClose + incognitoToClose) {
            webViewPool.remove(tab.id)?.destroySafely()
        }

        val remainingNormal = _normalTabs.value.filter { it.createdAt < sinceTime }
        val remainingIncognito = _incognitoTabs.value.filter { it.createdAt < sinceTime }

        _normalTabs.value = remainingNormal
        _incognitoTabs.value = remainingIncognito

        coroutineScope.launch(Dispatchers.IO) {
            for (tab in normalToClose) {
                database.tabDao().deleteTabById(tab.id)
            }
        }

        if (remainingNormal.isEmpty()) {
            val newTab = createNewTab(isIncognito = false)
            _activeTab.value = newTab
        } else if (_activeTab.value == null || normalToClose.any { it.id == _activeTab.value?.id } || incognitoToClose.any { it.id == _activeTab.value?.id }) {
            _activeTab.value = remainingNormal.lastOrNull()
        }
    }

    fun updateActiveTab(url: String, title: String) {
        val current = _activeTab.value ?: return
        val updatedTab = current.copy(url = url, title = title.ifBlank { url })
        _activeTab.value = updatedTab

        if (updatedTab.isIncognito) {
            _incognitoTabs.value = _incognitoTabs.value.map { if (it.id == updatedTab.id) updatedTab else it }
        } else {
            _normalTabs.value = _normalTabs.value.map { if (it.id == updatedTab.id) updatedTab else it }
            coroutineScope.launch(Dispatchers.IO) {
                database.tabDao().updateTab(updatedTab)
            }
        }
    }

    fun getOpenTabCount(): Int {
        val active = _activeTab.value
        return if (active?.isIncognito == true) {
            _incognitoTabs.value.size
        } else {
            _normalTabs.value.size
        }
    }

    fun clearAllWebViews() {
        webViewPool.values.forEach { it.destroySafely() }
        webViewPool.clear()
    }
}
