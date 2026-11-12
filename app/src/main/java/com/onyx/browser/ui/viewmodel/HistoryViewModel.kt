package com.onyx.browser.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onyx.browser.data.model.HistoryItem
import com.onyx.browser.data.repository.HistoryRepository
import com.onyx.browser.web.LocalFileLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: HistoryRepository = HistoryRepository.getInstance(application)
) : AndroidViewModel(application) {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _collapsedDayKeys = MutableStateFlow<Set<String>>(emptySet())
    val collapsedDayKeys: StateFlow<Set<String>> = _collapsedDayKeys.asStateFlow()

    private val _rawHistory = MutableStateFlow<List<HistoryItem>>(emptyList())
    val rawHistory: StateFlow<List<HistoryItem>> = _rawHistory.asStateFlow()

    var isInitialCollapseConfigured: Boolean = false
        private set

    init {
        viewModelScope.launch {
            _searchQuery
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        repository.getAllHistoryFlow()
                    } else {
                        repository.searchHistoryFlow(query)
                    }
                }
                .collectLatest { list ->
                    _rawHistory.value = list
                }
        }
    }

    fun setSearchQuery(query: String) {
        val trimmed = query.trim()
        if (_searchQuery.value != trimmed) {
            _searchQuery.value = trimmed
        }
    }

    fun toggleDayHeader(dayKey: String) {
        _collapsedDayKeys.update { current ->
            if (current.contains(dayKey)) current - dayKey else current + dayKey
        }
    }

    fun configureInitialCollapsedDays(keys: Set<String>) {
        if (!isInitialCollapseConfigured) {
            isInitialCollapseConfigured = true
            _collapsedDayKeys.value = keys
        }
    }

    fun deleteHistoryItem(item: HistoryItem) {
        val appContext = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            repository.deleteHistory(item)
            if (LocalFileLoader.isPreviewUrl(item.url) ||
                LocalFileLoader.isLocalFile(appContext, item.url)
            ) {
                LocalFileLoader.cleanupAllPreviews(appContext)
            }
        }
    }
}
