package com.onyx.browser.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.browser.TabCoordinator
import com.onyx.browser.ui.browser.TabManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = BrowserPreferences.getInstance(application)

    private val _uiState = MutableStateFlow(
        BrowserUiState(
            isAddressBarAtBottom = prefs.isAddressBarAtBottom
        )
    )
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private var tabManagerJob: Job? = null

    init {
        viewModelScope.launch {
            prefs.blockedRequestsFlow.collectLatest { count ->
                _uiState.update { state ->
                    state.copy(
                        blockedAdsCount = count,
                        isAddressBarAtBottom = prefs.isAddressBarAtBottom
                    )
                }
            }
        }
    }

    fun bindTabManager(tabManager: TabCoordinator) {
        tabManagerJob?.cancel()
        tabManagerJob = viewModelScope.launch {
            combine(
                tabManager.activeTab,
                tabManager.normalTabs,
                tabManager.incognitoTabs,
                tabManager.archivedTabs,
                tabManager.tabGroups
            ) { active, normal, incognito, archived, groups ->
                listOf(active, normal.size, incognito.size, archived.size, groups.size)
            }.collectLatest { values ->
                val active = values[0] as? com.onyx.browser.data.model.TabItem
                val normalCount = values[1] as Int
                val incognitoCount = values[2] as Int
                val archivedCount = values[3] as Int
                val groupCount = values[4] as Int
                _uiState.update { state ->
                    state.copy(
                        activeTab = active,
                        normalTabCount = normalCount,
                        incognitoTabCount = incognitoCount,
                        archivedTabCount = archivedCount,
                        tabGroupCount = groupCount,
                        isIncognito = active?.isIncognito ?: state.isIncognito
                    )
                }
            }
        }
    }

    fun bindTabCoordinator(tabCoordinator: TabCoordinator) = bindTabManager(tabCoordinator)

    fun updateLoadingState(isLoading: Boolean, progress: Int = _uiState.value.progress) {
        _uiState.update { state ->
            state.copy(
                isLoading = isLoading,
                progress = progress.coerceIn(0, 100)
            )
        }
    }

    fun updateNavigationState(canGoBack: Boolean, canGoForward: Boolean, isDesktopMode: Boolean) {
        _uiState.update { state ->
            state.copy(
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                isDesktopMode = isDesktopMode
            )
        }
    }

    fun updateSearchOverlayVisible(visible: Boolean) {
        _uiState.update { state ->
            state.copy(isSearchOverlayVisible = visible)
        }
    }

    fun refreshPreferences() {
        _uiState.update { state ->
            state.copy(isAddressBarAtBottom = prefs.isAddressBarAtBottom)
        }
    }
}
