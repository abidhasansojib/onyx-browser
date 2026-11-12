package com.onyx.browser.di

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.onyx.browser.OnyxApplication
import com.onyx.browser.data.repository.BookmarkRepository
import com.onyx.browser.data.repository.DownloadRepository
import com.onyx.browser.data.repository.HistoryRepository
import com.onyx.browser.ui.viewmodel.BookmarksViewModel
import com.onyx.browser.ui.viewmodel.BrowserViewModel
import com.onyx.browser.ui.viewmodel.DownloadsViewModel
import com.onyx.browser.ui.viewmodel.HistoryViewModel

/**
 * Reflection-free ViewModel factory for Onyx Browser presentation ViewModels.
 */
class OnyxViewModelFactory @JvmOverloads constructor(
    private val application: Application,
    private val container: AppContainer? = (application as? OnyxApplication)?.appContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(BrowserViewModel::class.java) ->
                BrowserViewModel(application) as T
            modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
                HistoryViewModel(
                    application,
                    container?.historyRepository ?: HistoryRepository.getInstance(application)
                ) as T
            modelClass.isAssignableFrom(BookmarksViewModel::class.java) ->
                BookmarksViewModel(
                    application,
                    container?.bookmarkRepository ?: BookmarkRepository.getInstance(application)
                ) as T
            modelClass.isAssignableFrom(DownloadsViewModel::class.java) ->
                DownloadsViewModel(
                    application,
                    container?.downloadRepository ?: DownloadRepository.getInstance(application)
                ) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
