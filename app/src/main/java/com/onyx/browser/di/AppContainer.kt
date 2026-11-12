package com.onyx.browser.di

import android.content.Context
import com.onyx.browser.OnyxApplication
import com.onyx.browser.core.common.DefaultDispatcherProvider
import com.onyx.browser.core.common.DispatcherProvider
import com.onyx.browser.data.favicon.FaviconManager
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.data.repository.BookmarkRepository
import com.onyx.browser.data.repository.DownloadRepository
import com.onyx.browser.data.repository.HistoryRepository
import com.onyx.browser.data.repository.SitePermissionRepository
import com.onyx.browser.data.search.SearchSuggestionRepository
import com.onyx.browser.userscript.UserScriptManager

/**
 * Application-scoped dependency injection container providing core singletons,
 * repositories, and coroutine dispatchers without annotation-processor overhead.
 */
interface AppContainer {
    val dispatchers: DispatcherProvider
    val database: AppDatabase
    val preferences: BrowserPreferences
    val sitePermissionRepository: SitePermissionRepository
    val historyRepository: HistoryRepository
    val bookmarkRepository: BookmarkRepository
    val downloadRepository: DownloadRepository
    val userScriptManager: UserScriptManager
    val faviconManager: FaviconManager
    val searchSuggestionRepository: SearchSuggestionRepository
}

class DefaultAppContainer(context: Context) : AppContainer {

    private val appContext: Context = context.applicationContext

    override val dispatchers: DispatcherProvider = DefaultDispatcherProvider

    override val database: AppDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppDatabase.getInstance(appContext)
    }

    override val preferences: BrowserPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BrowserPreferences.getInstance(appContext)
    }

    override val sitePermissionRepository: SitePermissionRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SitePermissionRepository.getInstance(appContext)
    }

    override val historyRepository: HistoryRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HistoryRepository.getInstance(appContext)
    }

    override val bookmarkRepository: BookmarkRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BookmarkRepository.getInstance(appContext)
    }

    override val downloadRepository: DownloadRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DownloadRepository.getInstance(appContext)
    }

    override val userScriptManager: UserScriptManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        UserScriptManager.getInstance(appContext)
    }

    override val faviconManager: FaviconManager = FaviconManager

    override val searchSuggestionRepository: SearchSuggestionRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SearchSuggestionRepository(
            historyDao = database.historyDao(),
            bookmarkDao = database.bookmarkDao()
        )
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as OnyxApplication).appContainer
