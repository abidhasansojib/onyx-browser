package com.onyx.browser.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.BookmarkItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<BookmarkItem>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkItem>> = _bookmarks.asStateFlow()

    init {
        viewModelScope.launch {
            _searchQuery
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        database.bookmarkDao().getAllBookmarksFlow()
                    } else {
                        database.bookmarkDao().searchAllBookmarksFlow(query)
                    }
                }
                .collectLatest { list ->
                    _bookmarks.value = list
                }
        }
    }

    fun setSearchQuery(query: String) {
        val trimmed = query.trim()
        if (_searchQuery.value != trimmed) {
            _searchQuery.value = trimmed
        }
    }

    fun addBookmark(title: String, url: String, onSaved: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            database.bookmarkDao().insertBookmark(
                BookmarkItem(
                    title = title.ifBlank {
                        if (url.startsWith("javascript:", ignoreCase = true)) "Bookmarklet" else url
                    },
                    url = url
                )
            )
            withContext(Dispatchers.Main) {
                onSaved()
            }
        }
    }

    fun updateBookmark(id: Long, title: String, url: String, onSaved: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            database.bookmarkDao().updateBookmark(
                id = id,
                title = title.ifBlank {
                    if (url.startsWith("javascript:", ignoreCase = true)) "Bookmarklet" else url
                },
                url = url
            )
            withContext(Dispatchers.Main) {
                onSaved()
            }
        }
    }

    fun deleteBookmark(item: BookmarkItem) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            database.bookmarkDao().deleteBookmark(item)
        }
    }
}
