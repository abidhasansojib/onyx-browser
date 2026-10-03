package com.onyx.browser.ui.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.onyx.browser.R
import com.onyx.browser.data.favicon.FaviconManager
import com.onyx.browser.data.model.SearchSuggestion
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.data.search.SearchSuggestionRepository
import com.onyx.browser.databinding.ActivityMainBinding
import com.onyx.browser.ui.search.SuggestionsAdapter
import com.onyx.browser.web.LocalFileLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Encapsulates the search overlay lifecycle, suggestions queries with debounce,
 * clipboard suggestions, webpage info card (share/copy/edit), and toolbar search-mode transitions.
 */
class SearchController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val preferences: BrowserPreferences,
    private val suggestionRepository: SearchSuggestionRepository,
    private val coroutineScope: CoroutineScope,
    private val getActivePageUrl: () -> String,
    private val getActiveTab: () -> TabItem?,
    private val performSearchOrLoad: (String) -> Unit,
    private val updateAddressBarDisplay: (String) -> Unit,
    private val showSoftKeyboard: () -> Unit,
    private val hideSoftKeyboard: () -> Unit,
    private val isLikelyUrl: (String) -> Boolean
) {

    var isSearchMode: Boolean = false
        private set

    lateinit var suggestionsAdapter: SuggestionsAdapter
        private set

    private var suggestionJob: Job? = null

    fun setup() {
        suggestionsAdapter = SuggestionsAdapter(
            onSuggestionClicked = { suggestion ->
                val target = if (suggestion.isDomain || suggestion.isUrl) {
                    val raw = suggestion.queryOrUrl.trim()
                    if (raw.startsWith("http://", ignoreCase = true) ||
                        raw.startsWith("https://", ignoreCase = true) ||
                        raw.startsWith("file://", ignoreCase = true) ||
                        raw.startsWith("about:", ignoreCase = true) ||
                        raw.startsWith("data:", ignoreCase = true)
                    ) {
                        raw
                    } else {
                        "https://$raw"
                    }
                } else {
                    suggestion.queryOrUrl
                }
                performSearchOrLoad(target)
                hideSoftKeyboard()
            },
            onInsertClicked = { suggestion ->
                binding.etUrl.setText(suggestion.queryOrUrl)
                binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
            }
        )
        binding.rvSearchSuggestions.layoutManager = LinearLayoutManager(activity)
        binding.rvSearchSuggestions.adapter = suggestionsAdapter

        // Current Webpage Card Actions: Share, Copy, Edit
        binding.btnCurrentPageShare.setOnClickListener {
            val url = getActivePageUrl()
            if (url.isNotBlank()) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, url)
                    type = "text/plain"
                }
                activity.startActivity(Intent.createChooser(sendIntent, activity.getString(R.string.share)))
            }
        }

        binding.btnCurrentPageCopy.setOnClickListener {
            val url = getActivePageUrl()
            if (url.isNotBlank()) {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("URL", url)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(activity, activity.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
            }
        }

        // Edit button populates the clean search bar with this URL so user can customize it
        binding.btnCurrentPageEdit.setOnClickListener {
            val url = getActivePageUrl()
            if (url.isNotBlank()) {
                val cleanUrl = if (LocalFileLoader.isLocalFile(activity, url)) {
                    try {
                        val parsed = Uri.parse(url)
                        if (parsed.scheme == "file" && parsed.path != null) parsed.path!! else url
                    } catch (_: Exception) { url }
                } else url
                binding.etUrl.setText(cleanUrl)
                binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
                binding.etUrl.requestFocus()
                showSoftKeyboard()
            }
        }

        // Clicking the webpage card directly opens/reloads the page in one tap (matching Brave/Chromium)
        binding.containerPageInfo.setOnClickListener {
            val url = getActivePageUrl()
            if (url.isNotBlank()) {
                performSearchOrLoad(url)
                hideSoftKeyboard()
            }
        }

        binding.cardCurrentPage.setOnClickListener {
            val url = getActivePageUrl()
            if (url.isNotBlank()) {
                performSearchOrLoad(url)
                hideSoftKeyboard()
            }
        }
    }

    fun enterSearchMode() {
        if (isSearchMode) return
        isSearchMode = true

        // 1. Transform top toolbar into search mode
        binding.btnHome.visibility = View.GONE
        binding.btnSearchBack.visibility = View.VISIBLE
        binding.btnTabSwitcher.visibility = View.GONE
        binding.btnMenu.visibility = View.GONE
        binding.ivSslLock.visibility = View.GONE

        // 2. Open Search Overlay Page
        binding.searchOverlay.visibility = View.VISIBLE

        // 3. Configure Current Webpage Card under search bar
        val curUrl = getActivePageUrl()
        updateCurrentPageCard(curUrl)

        // Clean search bar for fresh input as requested
        binding.etUrl.setText("")
        binding.etUrl.hint = activity.getString(R.string.search_or_type_url)
        binding.btnClearUrl.visibility = View.GONE
        binding.btnQrScanner.visibility = View.VISIBLE
        binding.btnVoiceSearch.visibility = View.VISIBLE

        // 4. Focus search bar & show keyboard
        binding.etUrl.requestFocus()
        showSoftKeyboard()

        // 5. Inject clipboard suggestion if available
        val clipboardOpt = getClipboardSuggestion()
        suggestionsAdapter.submitList(if (clipboardOpt != null) listOf(clipboardOpt) else emptyList())
    }

    fun exitSearchMode() {
        if (!isSearchMode) return
        isSearchMode = false

        suggestionJob?.cancel()

        // 1. Restore top toolbar
        binding.btnSearchBack.visibility = View.GONE
        binding.btnHome.visibility = View.VISIBLE
        binding.btnTabSwitcher.visibility = View.VISIBLE
        binding.btnMenu.visibility = View.VISIBLE
        binding.btnClearUrl.visibility = View.GONE
        binding.btnQrScanner.visibility = View.GONE
        binding.btnVoiceSearch.visibility = View.VISIBLE

        // 2. Hide search overlay
        binding.searchOverlay.visibility = View.GONE
        binding.cardCurrentPage.visibility = View.GONE
        suggestionsAdapter.submitList(emptyList())

        // 3. Hide keyboard & clear focus
        hideSoftKeyboard()
        binding.etUrl.clearFocus()

        // 4. Restore address bar host display
        updateAddressBarDisplay(getActivePageUrl())
    }

    fun onQueryTextChanged(text: CharSequence?) {
        val query = text?.toString()?.trim() ?: ""
        val hasText = query.isNotEmpty()
        binding.btnClearUrl.visibility = if (hasText) View.VISIBLE else View.GONE
        binding.btnQrScanner.visibility = View.VISIBLE
        binding.btnVoiceSearch.visibility = if (hasText) View.GONE else View.VISIBLE

        if (hasText) {
            // Hide current-page card while typing so search results have more room
            binding.cardCurrentPage.visibility = View.GONE
            // fetchSearchSuggestions handles debounce, clipboard blending, and adapter updates
            fetchSearchSuggestions(query)
        } else {
            // Empty query: cancel any pending job, show current-page card + clipboard only
            suggestionJob?.cancel()
            val curUrl = getActivePageUrl()
            updateCurrentPageCard(curUrl)
            val clipboardOpt = getClipboardSuggestion()
            suggestionsAdapter.submitList(if (clipboardOpt != null) listOf(clipboardOpt) else emptyList())
        }
    }

    fun updateCurrentPageCard(curUrl: String) {
        if (curUrl.isNotBlank()) {
            binding.cardCurrentPage.visibility = View.VISIBLE
            val isLocal = LocalFileLoader.isLocalFile(activity, curUrl) || LocalFileLoader.isPreviewUrl(curUrl)
            val host = if (isLocal) {
                LocalFileLoader.getDisplayName(activity, LocalFileLoader.parseUri(curUrl))
            } else {
                try { Uri.parse(curUrl).host?.removePrefix("www.") ?: curUrl } catch (_: Exception) { curUrl }
            }
            val currentTab = getActiveTab()
            val displayTitle = currentTab?.title?.takeIf {
                it.isNotBlank() && !it.startsWith("data:") && !it.startsWith("net::") && it != "Page Not Available"
            } ?: host
            binding.tvCurrentPageTitle.text = displayTitle
            val displayUrlText = if (isLocal) {
                displayTitle
            } else {
                curUrl.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            }
            binding.tvCurrentPageUrl.text = displayUrlText
            val isIncog = getActiveTab()?.isIncognito == true
            FaviconManager.loadFavicon(
                context = activity,
                imageView = binding.ivCurrentPageFavicon,
                urlOrHost = curUrl,
                isCircular = true,
                saveToDisk = !isIncog
            )
        } else {
            binding.cardCurrentPage.visibility = View.GONE
        }
    }

    fun fetchSearchSuggestions(query: String) {
        suggestionJob?.cancel()
        val trimmed = query.trim()
        // Require at least 2 chars — single char gives irrelevant results and
        // wastes network bandwidth. Repository enforces the same guard.
        if (trimmed.length < 2) {
            val clipboardOpt = getClipboardSuggestion()
            val matches = if (trimmed.isEmpty()) {
                clipboardOpt
            } else {
                if (clipboardOpt != null && clipboardOpt.queryOrUrl.contains(trimmed, ignoreCase = true)) clipboardOpt else null
            }
            suggestionsAdapter.submitList(if (matches != null) listOf(matches) else emptyList())
            return
        }

        // Fast-path: When typing a domain or URL, display it immediately without waiting for debounce
        if (SearchSuggestionRepository.isLikelyDomainOrUrl(trimmed)) {
            val isCompleteUrl = trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("file://", ignoreCase = true)
            val fullNavUrl = if (isCompleteUrl) trimmed else "https://$trimmed"
            val instantDomain = SearchSuggestion(
                title = trimmed,
                queryOrUrl = fullNavUrl,
                isDomain = !isCompleteUrl,
                isUrl = true
            )
            val clipboardOpt = getClipboardSuggestion()
            val includeClipboard = clipboardOpt != null && clipboardOpt.queryOrUrl.contains(trimmed, ignoreCase = true)
            val initialList = if (includeClipboard && clipboardOpt != null) listOf(clipboardOpt, instantDomain) else listOf(instantDomain)
            suggestionsAdapter.submitList(initialList)
        }

        suggestionJob = coroutineScope.launch {
            // 300ms debounce: waits for user to briefly pause typing
            // before firing the network request and DB query.
            delay(300)
            val isIncog = getActiveTab()?.isIncognito == true
            val suggestions = suggestionRepository.getSuggestions(trimmed, preferences.searchEngine, isIncognito = isIncog)
            if (isSearchMode) {
                val clipboardOpt = getClipboardSuggestion()
                val includeClipboard = clipboardOpt != null && clipboardOpt.queryOrUrl.contains(trimmed, ignoreCase = true)
                val finalList = if (includeClipboard && clipboardOpt != null) {
                    val list = suggestions.toMutableList()
                    list.add(0, clipboardOpt)
                    list
                } else {
                    suggestions
                }
                suggestionsAdapter.submitList(finalList)
            }
        }
    }

    private fun cleanUrlForComparison(url: String): String {
        return url.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
    }

    fun getClipboardSuggestion(): SearchSuggestion? {
        try {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
            if (clipboard.hasPrimaryClip()) {
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val rawText = clipData.getItemAt(0).text?.toString()?.trim()
                    if (!rawText.isNullOrBlank()) {
                        // Suppress if identical to active page URL (Brave/Chromium standard)
                        val activeUrl = getActivePageUrl().trim()
                        if (activeUrl.isNotBlank()) {
                            val cleanActive = cleanUrlForComparison(activeUrl)
                            val cleanRaw = cleanUrlForComparison(rawText)
                            if (cleanActive.equals(cleanRaw, ignoreCase = true)) {
                                return null
                            }
                        }

                        val isLink = android.util.Patterns.WEB_URL.matcher(rawText).matches() ||
                                rawText.startsWith("http://", ignoreCase = true) ||
                                rawText.startsWith("https://", ignoreCase = true) ||
                                rawText.startsWith("www.", ignoreCase = true) ||
                                isLikelyUrl(rawText)

                        val title = if (isLink) activity.getString(R.string.link_you_copied) else activity.getString(R.string.text_you_copied)
                        return SearchSuggestion(
                            title = title,
                            queryOrUrl = rawText,
                            isUrl = isLink,
                            isDomain = isLink && !rawText.startsWith("http://", ignoreCase = true) && !rawText.startsWith("https://", ignoreCase = true),
                            isClipboard = true
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
