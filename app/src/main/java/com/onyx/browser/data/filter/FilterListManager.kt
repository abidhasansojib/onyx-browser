package com.onyx.browser.data.filter

import android.content.Context
import android.util.Log
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.nativebridge.AdBlockEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class FilterListEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val category: String = "general",
    val defaultEnabled: Boolean = false,
    val isCustom: Boolean = false,
    val isUrlType: Boolean = false
)

object FilterListManager {
    private const val TAG = "FilterListManager"
    private const val FILTER_DIR = "filter_cache"
    private const val BINARY_CACHE_FILE = "onyx_filters.bin"
    private const val DEFAULT_ASSET_RULES = "easylist_rules.txt"

    /**
     * All 54 filter lists corresponding to Brave Android's Content Filters screen
     * (matching user screenshots 1.jpg through 5.jpg).
     */
    val ALL_FILTER_LISTS: List<FilterListEntry> = listOf(
        // ── Core & Famous Filter Lists (Essential Ad & Tracker Blocking) ─────
        FilterListEntry(
            id = "easylist",
            title = "EasyList",
            subtitle = "Primary ad-blocking filter list worldwide",
            url = "https://easylist.to/easylist/easylist.txt",
            category = "core",
            defaultEnabled = true
        ),
        FilterListEntry(
            id = "easyprivacy",
            title = "EasyPrivacy",
            subtitle = "Primary tracking, telemetry, and analytics protection",
            url = "https://easylist.to/easylist/easyprivacy.txt",
            category = "core",
            defaultEnabled = true
        ),
        FilterListEntry(
            id = "ublock_filters",
            title = "uBlock Origin – Base Filters",
            subtitle = "Core ad and content filtering rules by Raymond Hill (gorhill)",
            url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
            category = "core",
            defaultEnabled = true
        ),
        FilterListEntry(
            id = "ublock_privacy",
            title = "uBlock Origin – Privacy",
            subtitle = "Tracking servers, web beacons, and mobile telemetry",
            url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
            category = "core",
            defaultEnabled = true
        ),
        FilterListEntry(
            id = "ublock_badware",
            title = "uBlock Origin – Badware Risks",
            subtitle = "Malicious websites, fake download traps, and popunder networks",
            url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt",
            category = "core",
            defaultEnabled = true
        ),
        FilterListEntry(
            id = "ublock_quick_fixes",
            title = "uBlock Origin – Quick Fixes",
            subtitle = "Real-time anti-adblock defusers and urgent site patches",
            url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/quick-fixes.txt",
            category = "core",
            defaultEnabled = true
        ),
        FilterListEntry(
            id = "ublock_unbreak",
            title = "uBlock Origin – Unbreak",
            subtitle = "Fixes legitimate website breakage caused by filter rules",
            url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt",
            category = "core",
            defaultEnabled = true
        ),
        FilterListEntry(
            id = "brave_default",
            title = "Brave Shields – Default Filters",
            subtitle = "Brave's curated ad and cosmetic blocking rules",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-default.txt",
            category = "core",
            defaultEnabled = true
        ),
        FilterListEntry(
            id = "brave_firstparty",
            title = "Brave Shields – First Party Filters",
            subtitle = "Blocks first-party tracking scripts and ad injection",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-firstparty.txt",
            category = "core",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "adguard_base",
            title = "AdGuard – Base Filter",
            subtitle = "Comprehensive ad blocking list by the AdGuard team",
            url = "https://filters.adtidy.org/extension/ublock/filters/2.txt",
            category = "core",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "adguard_mobile",
            title = "AdGuard – Mobile Ads",
            subtitle = "Optimized ad blocking specifically tuned for mobile browsers",
            url = "https://filters.adtidy.org/extension/ublock/filters/11.txt",
            category = "core",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "adguard_tracking",
            title = "AdGuard – Tracking Protection",
            subtitle = "Blocks web trackers, analytics, and telemetry systems",
            url = "https://filters.adtidy.org/extension/ublock/filters/3.txt",
            category = "core",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "adguard_annoyances",
            title = "AdGuard – Annoyances Filter",
            subtitle = "Blocks cookie notices, widgets, popups, and app banners",
            url = "https://filters.adtidy.org/extension/ublock/filters/14.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "peter_lowes",
            title = "Peter Lowe's Blocklist",
            subtitle = "Ad and tracking server list curated by Peter Lowe since 2001",
            url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext",
            category = "core",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "fanboy_annoyance",
            title = "Fanboy's Annoyance List",
            subtitle = "In-page popups, newsletter overlays, and floating notices",
            url = "https://secure.fanboy.co.nz/fanboy-annoyance.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "fanboy_social",
            title = "Fanboy's Anti-Social List",
            subtitle = "Social media share buttons, tracking widgets, and scripts",
            url = "https://secure.fanboy.co.nz/fanboy-antifacebook.txt",
            category = "social",
            defaultEnabled = false
        ),

        // ── Screenshot 1.jpg ──────────────────────────────────────────────────
        FilterListEntry(
            id = "cookie_notice",
            title = "Cookie notice blocker",
            subtitle = "EasyList Cookie",
            url = "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "annoying_distractions",
            title = "Annoying distractions blocker",
            subtitle = "Fanboy's Annoyances + uBO Annoyances",
            url = "https://secure.fanboy.co.nz/fanboy-annoyance.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "ai_suggestions",
            title = "AI suggestions blocker",
            subtitle = "Anti-AI suggestions Filters",
            url = "https://raw.githubusercontent.com/laylavish/uBlockOrigin-HUGE-AI-Blocklist/main/list.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "newsletter_popup",
            title = "Newsletter popup blocker",
            subtitle = "Fanboy's Anti-Newsletter",
            url = "https://secure.fanboy.co.nz/fanboy-newsletter.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "mobile_app_promo",
            title = "Mobile app promo blocker",
            subtitle = "Fanboy's Mobile Notifications",
            url = "https://secure.fanboy.co.nz/fanboy-notifications.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "social_media",
            title = "Social media blocker",
            subtitle = "Fanboy's Social",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-social.txt",
            category = "social",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_shorts",
            title = "YouTube Shorts blocker",
            subtitle = "YouTube Anti-Shorts",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-shorts.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_playables",
            title = "YouTube Playables blocker",
            subtitle = "Remove Youtube Playables",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-distracting.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_recommendations",
            title = "YouTube recommendations blocker (mobile-only)",
            subtitle = "YouTube Mobile Recommendations",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-recommended.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_autodubbed",
            title = "YouTube autodubbed videos blocker",
            subtitle = "Remove Youtube Autodubbed videos",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-distracting.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_end_video",
            title = "YouTube end video elements blocker",
            subtitle = "YouTube End Video Elements",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-distracting.txt",
            category = "annoyance",
            defaultEnabled = false
        ),

        // ── Screenshot 2.jpg ──────────────────────────────────────────────────
        FilterListEntry(
            id = "yt_members_only",
            title = "YouTube members-only video blocker",
            subtitle = "Remove Youtube Members video advertisements",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-distracting.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_distractions",
            title = "YouTube distractions elements blocker (mobile-only)",
            subtitle = "YouTube Mobile Distractions",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-distracting.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_thumbnails",
            title = "YouTube thumbnail image blocker",
            subtitle = "Remove Youtube thumbnail images",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-distracting.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "tracking_url",
            title = "Tracking URL blocker",
            subtitle = "AdGuard URL Tracking Protection Filters",
            url = "https://filters.adtidy.org/extension/ublock/filters/17.txt",
            category = "privacy",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "chat_app",
            title = "Chat app blocker",
            subtitle = "Fanboy's Anti-chat Apps",
            url = "https://secure.fanboy.co.nz/fanboy-antichat.txt",
            category = "social",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "paywall",
            title = "Paywall blocker",
            subtitle = "Bypass Paywalls Clean Filters",
            url = "https://raw.githubusercontent.com/bpc-clone/bpc_updates/master/filters.txt",
            category = "annoyance",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "porn",
            title = "Porn blocker",
            subtitle = "Blocklists Anti-Porn",
            url = "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/porn-hosts",
            category = "other",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "regional_arabic",
            title = "Arabic website ad blocker",
            subtitle = "Liste AR",
            url = "https://raw.githubusercontent.com/easylist/easylist/master/easylist_regional/arab.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_bulgarian",
            title = "Bulgarian website ad blocker",
            subtitle = "Bulgarian Adblock list",
            url = "https://stanev.org/abp/adblock_bg.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_chinese",
            title = "Chinese website ad blocker",
            subtitle = "AdGuard Chinese",
            url = "https://filters.adtidy.org/extension/ublock/filters/224.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_chinese_annoy",
            title = "Chinese website annoyances",
            subtitle = "Chinese website annoyances",
            url = "https://filters.adtidy.org/extension/ublock/filters/224_annoyances.txt",
            category = "regional"
        ),

        // ── Screenshot 3.jpg ──────────────────────────────────────────────────
        FilterListEntry(
            id = "regional_czech",
            title = "Czech and Slovak website ad blocker",
            subtitle = "EasyList Czech and Slovak",
            url = "https://easylist-downloads.adblockplus.org/easylistczechandslovak.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_dutch",
            title = "Dutch website ad blocker",
            subtitle = "AdGuard Dutch",
            url = "https://filters.adtidy.org/extension/ublock/filters/14.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_estonian",
            title = "Estonian website ad blocker",
            subtitle = "Eesti saitidele kohandatud filter",
            url = "https://gurud.ee/abp.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_finnish",
            title = "Finnish website ad blocker",
            subtitle = "EasyList Finnish",
            url = "https://raw.githubusercontent.com/finnish-easylist-addition/finnish-easylist-addition/master/Finland_adb.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_french",
            title = "French website ad blocker",
            subtitle = "AdGuard Français",
            url = "https://filters.adtidy.org/extension/ublock/filters/16.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_german",
            title = "German website ad blocker",
            subtitle = "EasyList Germany",
            url = "https://easylist.to/easylistgermany/easylistgermany.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_greek",
            title = "Greek website ad blocker",
            subtitle = "Greek AdBlock Filter",
            url = "https://raw.githubusercontent.com/kargig/greek-adblockplus-filter/master/filters.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_hebrew",
            title = "Hebrew website ad blocker",
            subtitle = "EasyList Hebrew",
            url = "https://raw.githubusercontent.com/easylist/easylisthebrew/master/EasyListHebrew.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_hindi",
            title = "Hindi website ad blocker",
            subtitle = "IndianList",
            url = "https://easylist-downloads.adblockplus.org/indianlist.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_hungarian",
            title = "Hungarian website ad blocker",
            subtitle = "Hufilter",
            url = "https://raw.githubusercontent.com/hufilter/hufilter/master/hufilter.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_icelandic",
            title = "Icelandic website ad blocker",
            subtitle = "Icelandic ABP List",
            url = "https://adblock.gardar.net/is.abp.txt",
            category = "regional"
        ),

        // ── Screenshot 4.jpg ──────────────────────────────────────────────────
        FilterListEntry(
            id = "regional_indonesian",
            title = "Indonesian website ad blocker",
            subtitle = "ABPindo",
            url = "https://raw.githubusercontent.com/abpindo/indonesianadblockrules/master/subscriptions/abpindo.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_italian",
            title = "Italian website ad blocker",
            subtitle = "EasyList Italy",
            url = "https://easylist-downloads.adblockplus.org/easylistitaly.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_japanese",
            title = "Japanese website ad blocker",
            subtitle = "Adguard Japanese filters 日本用フィルタ",
            url = "https://filters.adtidy.org/extension/ublock/filters/7.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_korean",
            title = "Korean website ad blocker",
            subtitle = "List KR",
            url = "https://raw.githubusercontent.com/List-KR/List-KR/master/List-KR.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_latvian",
            title = "Latvian website ad blocker",
            subtitle = "Latvian List",
            url = "https://notabug.org/latvian-list/adblock-latvian/raw/master/lists/latvian-list.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_lithuanian",
            title = "Lithuanian website ad blocker",
            subtitle = "EasyList Lithuania",
            url = "https://raw.githubusercontent.com/EasyList-Lithuania/easylistlithuania/master/easylistlithuania.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_macedonian",
            title = "Macedonian website ad blocker",
            subtitle = "Macedonian adBlock Filters",
            url = "https://raw.githubusercontent.com/fael/adblock-macedonian/master/filters.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_nordic",
            title = "Nordic website ad blocker",
            subtitle = "Dandelion Sprout's Nordic filters",
            url = "https://raw.githubusercontent.com/DandelionSprout/adfilt/master/NorwegianList.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_persian",
            title = "Persian website ad blocker",
            subtitle = "PersianBlocker",
            url = "https://raw.githubusercontent.com/farrokhi/adblock-iran/master/list.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_polish",
            title = "Polish website ad blocker",
            subtitle = "Oficjalne Polskie Filtry do AdBlocka",
            url = "https://raw.githubusercontent.com/olegwukr/polish-privacy-filters/master/antiadblock.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_romanian",
            title = "Romanian website ad blocker",
            subtitle = "Romanian Ad (ROad) Block List Light",
            url = "https://raw.githubusercontent.com/tcptomato/ROad-Block/master/road-block-filters.txt",
            category = "regional"
        ),

        // ── Screenshot 5.jpg ──────────────────────────────────────────────────
        FilterListEntry(
            id = "regional_russian_adguard",
            title = "Russian website ad blocker - AdGuard Russian",
            subtitle = "AdGuard Russian filter",
            url = "https://filters.adtidy.org/extension/ublock/filters/1.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_russian_ru",
            title = "Russian website ad blocker - RU AdList",
            subtitle = "RU List",
            url = "https://easylist-downloads.adblockplus.org/ruadlist.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_slovenian",
            title = "Slovenian website ad blocker",
            subtitle = "Slovenian List",
            url = "https://raw.githubusercontent.com/gorhill/uBlock/master/docs/tests/slovenian-list.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_spanish",
            title = "Spanish website ad blocker",
            subtitle = "EasyList Spanish",
            url = "https://easylist-downloads.adblockplus.org/easylistspanish.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_spanish_portuguese",
            title = "Spanish and Portuguese website ad blocker",
            subtitle = "AdGuard Spanish/Portuguese",
            url = "https://filters.adtidy.org/extension/ublock/filters/9.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_swedish",
            title = "Swedish website ad blocker",
            subtitle = "Frellwit's Swedish Filter",
            url = "https://raw.githubusercontent.com/lassekongo83/Frellwits-filter-lists/master/Frellwits-Swedish-Hosts-File.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_thai",
            title = "Thai website ad blocker",
            subtitle = "EasyList Thailand",
            url = "https://raw.githubusercontent.com/easylist/easylistthailand/master/easylistthailand.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_turkish",
            title = "Turkish website ad blocker",
            subtitle = "AdGuard Turkish",
            url = "https://filters.adtidy.org/extension/ublock/filters/13.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "regional_vietnamese",
            title = "Vietnamese website ad blocker",
            subtitle = "ABPVN",
            url = "https://raw.githubusercontent.com/abpvn/abpvn/master/filter/abpvn.txt",
            category = "regional"
        ),
        FilterListEntry(
            id = "experimental",
            title = "Experimental ad blocker",
            subtitle = "Brave Experimental Adblock Rules",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/experimental.txt",
            defaultEnabled = false
        )
    )

    fun getAllLists(context: Context): List<FilterListEntry> {
        val prefs = BrowserPreferences.getInstance(context)
        val customEntries = prefs.getCustomFilters().map { item ->
            val subtitle = if (item.isUrlType) {
                item.url
            } else {
                val lines = item.rules.lineSequence().count { it.isNotBlank() && !it.startsWith("!") && !it.startsWith("[") }
                "$lines custom rules"
            }
            FilterListEntry(
                id = item.id,
                title = item.name,
                subtitle = subtitle,
                url = item.url,
                category = "custom",
                defaultEnabled = item.isEnabled,
                isCustom = true,
                isUrlType = item.isUrlType
            )
        }
        return customEntries + ALL_FILTER_LISTS
    }

    fun isListEnabled(context: Context, id: String): Boolean {
        val prefs = BrowserPreferences.getInstance(context)
        if (id.startsWith("custom_")) {
            return prefs.getCustomFilters().firstOrNull { it.id == id }?.isEnabled ?: false
        }
        return prefs.isFilterListEnabled(id)
    }

    fun setListEnabled(context: Context, id: String, enabled: Boolean) {
        val prefs = BrowserPreferences.getInstance(context)
        if (id.startsWith("custom_")) {
            val filters = prefs.getCustomFilters().toMutableList()
            val index = filters.indexOfFirst { it.id == id }
            if (index != -1) {
                filters[index] = filters[index].copy(isEnabled = enabled)
                prefs.saveCustomFilters(filters)
            }
        } else {
            prefs.setFilterListEnabled(id, enabled)
        }
    }

    suspend fun downloadCustomFilter(context: Context, item: CustomFilterItem): Boolean = withContext(Dispatchers.IO) {
        if (!item.isUrlType || item.url.isBlank()) return@withContext true
        try {
            val cacheDir = getCacheDir(context)
            val targetFile = File(cacheDir, "${item.id}.txt")
            downloadFilterList(item.url, targetFile)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed downloading custom filter ${item.name}: ${e.message}")
            false
        }
    }

    private fun getCacheDir(context: Context): File {
        val dir = File(context.filesDir, FILTER_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Downloads and compiles all enabled filter lists into AdBlockEngine, saving the compiled binary.
     */
    suspend fun updateAllFilters(
        context: Context,
        onProgress: (current: Int, total: Int, name: String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        val prefs = BrowserPreferences.getInstance(context)
        val enabledBuiltIn = ALL_FILTER_LISTS.filter { prefs.isFilterListEnabled(it.id) }
        val enabledCustomUrl = prefs.getCustomFilters().filter { it.isUrlType && it.isEnabled }
        val cacheDir = getCacheDir(context)

        var downloadedCount = 0
        val totalToDownload = enabledBuiltIn.size + enabledCustomUrl.size
        var currentIndex = 0

        for (entry in enabledBuiltIn) {
            currentIndex++
            onProgress(currentIndex, totalToDownload, entry.title)
            try {
                val targetFile = File(cacheDir, "${entry.id}.txt")
                downloadFilterList(entry.url, targetFile)
                downloadedCount++
            } catch (e: Exception) {
                Log.w(TAG, "Failed downloading filter list ${entry.id}: ${e.message}")
            }
        }

        for (custom in enabledCustomUrl) {
            currentIndex++
            onProgress(currentIndex, totalToDownload, custom.name)
            try {
                val targetFile = File(cacheDir, "${custom.id}.txt")
                downloadFilterList(custom.url, targetFile)
                downloadedCount++
            } catch (e: Exception) {
                Log.w(TAG, "Failed downloading custom filter ${custom.name}: ${e.message}")
            }
        }

        // Now merge and compile
        val ruleCount = compileFilters(context)
        if (ruleCount > 0) {
            prefs.filterLastUpdatedTime = System.currentTimeMillis()
            Result.success(ruleCount)
        } else {
            Result.failure(Exception("Failed to compile filter rules"))
        }
    }

    /**
     * Fast recompilation from cached files + assets + custom rules (triggered on toggle).
     */
    suspend fun recompileFilters(context: Context): Int = withContext(Dispatchers.IO) {
        return@withContext compileFilters(context)
    }

    private fun compileFilters(context: Context): Int {
        try {
            val prefs = BrowserPreferences.getInstance(context)
            val cacheDir = getCacheDir(context)
            val sb = StringBuilder()

            // 1. Base bundled asset rules
            try {
                val assetStream: InputStream = context.assets.open(DEFAULT_ASSET_RULES)
                val assetContent = assetStream.bufferedReader().use { it.readText() }
                sb.append(assetContent).append("\n")
            } catch (e: Exception) {
                Log.w(TAG, "Error reading base asset rules", e)
            }

            // 2. Enabled cached filter lists
            val enabledIds = prefs.enabledFilterLists
            for (id in enabledIds) {
                val cachedFile = File(cacheDir, "$id.txt")
                if (cachedFile.exists() && cachedFile.length() > 0) {
                    try {
                        val text = cachedFile.readText()
                        sb.append("\n! Filter List: ").append(id).append("\n")
                        sb.append(text).append("\n")
                    } catch (_: Exception) {}
                }
            }

            // 2b. Enabled custom filter lists (URL & Manual Rules)
            val customFilters = prefs.getCustomFilters().filter { it.isEnabled }
            for (custom in customFilters) {
                if (custom.isUrlType) {
                    val cachedFile = File(cacheDir, "${custom.id}.txt")
                    if (cachedFile.exists() && cachedFile.length() > 0) {
                        try {
                            val text = cachedFile.readText()
                            sb.append("\n! Custom URL List: ").append(custom.name).append("\n")
                            sb.append(text).append("\n")
                        } catch (_: Exception) {}
                    }
                } else if (custom.rules.isNotBlank()) {
                    sb.append("\n! Custom Rule Set: ").append(custom.name).append("\n")
                    sb.append(custom.rules).append("\n")
                }
            }

            // 3. User custom filter rules (legacy)
            val customRules = prefs.customFilterRules
            if (customRules.isNotBlank()) {
                sb.append("\n! User Custom Rules\n")
                sb.append(customRules).append("\n")
            }

            val mergedRules = sb.toString()
            if (mergedRules.isBlank()) return 0

            val compiledBytes = AdBlockEngine.initFromRules(mergedRules)
            if (compiledBytes != null && compiledBytes.isNotEmpty()) {
                val cacheFile = File(context.filesDir, BINARY_CACHE_FILE)
                FileOutputStream(cacheFile).use { it.write(compiledBytes) }
                // Immediately apply new rules to the active in-memory Rust engine!
                AdBlockEngine.initEngine(compiledBytes)
                Log.i(TAG, "Successfully compiled filter database with ${compiledBytes.size} bytes cache and reinitialized engine")

                // Count active rules
                val ruleCount = mergedRules.lineSequence().count {
                    it.isNotBlank() && !it.startsWith("!") && !it.startsWith("[")
                }
                return ruleCount
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in compileFilters", e)
        }
        return 0
    }

    /**
     * Checks if auto-update is due (every 24h by default) and updates/compiles filter lists in background.
     */
    suspend fun checkAndAutoUpdateFilters(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val prefs = BrowserPreferences.getInstance(context)
        if (!force && !prefs.isFilterAutoUpdateEnabled) {
            return@withContext false
        }
        val lastUpdated = prefs.filterLastUpdatedTime
        val intervalMillis = prefs.filterAutoUpdateIntervalHours * 3600_000L
        val now = System.currentTimeMillis()
        if (!force && (now - lastUpdated < intervalMillis)) {
            Log.d(TAG, "Filter lists are up to date (last updated ${((now - lastUpdated) / 3600000)}h ago)")
            return@withContext false
        }

        Log.i(TAG, "Starting filter lists auto-update in background...")
        val result = updateAllFilters(context) { current, total, name ->
            Log.d(TAG, "Auto-updating filter ($current/$total): $name")
        }
        return@withContext result.isSuccess
    }

    fun getLastUpdatedFormatted(context: Context): String {
        val prefs = BrowserPreferences.getInstance(context)
        val lastUpdated = prefs.filterLastUpdatedTime
        if (lastUpdated <= 0) return "Never"
        val diff = System.currentTimeMillis() - lastUpdated
        val hours = diff / (3600 * 1000)
        val days = hours / 24
        return when {
            days > 1 -> "$days days ago"
            days == 1L -> "Yesterday"
            hours > 1 -> "$hours hours ago"
            hours == 1L -> "1 hour ago"
            diff > 60_000 -> "${diff / 60_000} mins ago"
            else -> "Just now"
        }
    }

    private fun downloadFilterList(sourceUrl: String, targetFile: File) {
        val url = URL(sourceUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 20000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; OnyxBrowser)")
        conn.connect()

        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            throw Exception("HTTP ${conn.responseCode}")
        }
    }
}
