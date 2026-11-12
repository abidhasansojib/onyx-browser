package com.onyx.browser.data.preferences

import com.onyx.browser.data.model.SearchEngine

/**
 * Domain-scoped preference store for search engine selection, custom search engines,
 * and omnibox suggestion toggles.
 */
class SearchPreferences(private val browserPrefs: BrowserPreferences) {

    var searchEngine: SearchEngine
        get() = browserPrefs.searchEngine
        set(value) {
            browserPrefs.searchEngine = value
        }

    var isSearchSuggestionsEnabled: Boolean
        get() = browserPrefs.isSearchSuggestionsEnabled
        set(value) {
            browserPrefs.isSearchSuggestionsEnabled = value
        }

    var isShowClipboardSuggestions: Boolean
        get() = browserPrefs.isShowClipboardSuggestions
        set(value) {
            browserPrefs.isShowClipboardSuggestions = value
        }

    var isShowHistorySuggestions: Boolean
        get() = browserPrefs.isShowHistorySuggestions
        set(value) {
            browserPrefs.isShowHistorySuggestions = value
        }

    fun getCustomSearchEngines(): List<SearchEngine> = browserPrefs.getCustomSearchEngines()

    fun saveCustomSearchEngines(engines: List<SearchEngine>) {
        browserPrefs.saveCustomSearchEngines(engines)
    }

    fun addCustomSearchEngine(engine: SearchEngine) {
        browserPrefs.addCustomSearchEngine(engine)
    }

    fun updateCustomSearchEngine(engine: SearchEngine) {
        browserPrefs.updateCustomSearchEngine(engine)
    }

    fun deleteCustomSearchEngine(id: String) {
        browserPrefs.deleteCustomSearchEngine(id)
    }

    fun getAllSearchEngines(): List<SearchEngine> = browserPrefs.getAllSearchEngines()
}
