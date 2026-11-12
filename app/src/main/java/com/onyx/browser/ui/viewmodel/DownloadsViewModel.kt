package com.onyx.browser.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.data.repository.DownloadRepository
import com.onyx.browser.ui.downloads.ApkInstallerHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DownloadsViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: DownloadRepository = DownloadRepository.getInstance(application)
) : AndroidViewModel(application) {

    companion object {
        const val FILTER_ALL = 0
        const val FILTER_APKS = 1
        const val FILTER_IMAGES = 2
        const val FILTER_VIDEOS = 3
        const val FILTER_DOCUMENTS = 4

        fun isImageFile(fileName: String, mimeType: String): Boolean {
            val lower = fileName.lowercase()
            return mimeType.startsWith("image/", ignoreCase = true) ||
                    lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".bmp")
        }

        fun isVideoFile(fileName: String, mimeType: String): Boolean {
            val lower = fileName.lowercase()
            return mimeType.startsWith("video/", ignoreCase = true) ||
                    lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                    lower.endsWith(".mov") || lower.endsWith(".avi") || lower.endsWith(".flv")
        }

        fun isDocumentFile(fileName: String, mimeType: String): Boolean {
            val lower = fileName.lowercase()
            return mimeType.startsWith("text/", ignoreCase = true) ||
                    mimeType.contains("pdf") || mimeType.contains("document") || mimeType.contains("sheet") ||
                    lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") ||
                    lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt") ||
                    lower.endsWith(".pptx") || lower.endsWith(".txt") || lower.endsWith(".html") ||
                    lower.endsWith(".mht") || lower.endsWith(".mhtml") || lower.endsWith(".epub") ||
                    lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".gz")
        }
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _categoryFilter = MutableStateFlow(FILTER_ALL)
    val categoryFilter: StateFlow<Int> = _categoryFilter.asStateFlow()

    private val _allDownloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val allDownloads: StateFlow<List<DownloadItem>> = _allDownloads.asStateFlow()

    private val _filteredDownloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val filteredDownloads: StateFlow<List<DownloadItem>> = _filteredDownloads.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAllDownloadsFlow().collectLatest { list ->
                _allDownloads.value = list
            }
        }

        viewModelScope.launch {
            combine(_allDownloads, _searchQuery, _categoryFilter) { list, rawQuery, category ->
                val query = rawQuery.trim().lowercase()
                list.filter { item ->
                    val matchesQuery = query.isEmpty() ||
                            item.fileName.lowercase().contains(query) ||
                            item.url.lowercase().contains(query)
                    if (!matchesQuery) return@filter false

                    when (category) {
                        FILTER_APKS -> ApkInstallerHelper.isApkFile(item.fileName, item.mimeType)
                        FILTER_IMAGES -> isImageFile(item.fileName, item.mimeType)
                        FILTER_VIDEOS -> isVideoFile(item.fileName, item.mimeType)
                        FILTER_DOCUMENTS -> isDocumentFile(item.fileName, item.mimeType)
                        else -> true
                    }
                }
            }.collectLatest { filtered ->
                _filteredDownloads.value = filtered
            }
        }
    }

    fun setSearchQuery(query: String) {
        if (_searchQuery.value != query) {
            _searchQuery.value = query
        }
    }

    fun setCategoryFilter(filter: Int) {
        if (_categoryFilter.value != filter) {
            _categoryFilter.value = filter
        }
    }
}
