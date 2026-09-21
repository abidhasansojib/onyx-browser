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
    val defaultEnabled: Boolean = false
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
        // ── Screenshot 1.jpg ──────────────────────────────────────────────────
        FilterListEntry(
            id = "cookie_notice",
            title = "Cookie notice blocker",
            subtitle = "EasyList Cookie",
            url = "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "annoying_distractions",
            title = "Annoying distractions blocker",
            subtitle = "Fanboy's Annoyances + uBO Annoyances",
            url = "https://secure.fanboy.co.nz/fanboy-annoyance.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "ai_suggestions",
            title = "AI suggestions blocker",
            subtitle = "Anti-AI suggestions Filters",
            url = "https://raw.githubusercontent.com/laylavish/uBlockOrigin-HUGE-AI-Blocklist/main/list.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "newsletter_popup",
            title = "Newsletter popup blocker",
            subtitle = "Fanboy's Anti-Newsletter",
            url = "https://secure.fanboy.co.nz/fanboy-newsletter.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "mobile_app_promo",
            title = "Mobile app promo blocker",
            subtitle = "Fanboy's Mobile Notifications",
            url = "https://secure.fanboy.co.nz/fanboy-notifications.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "social_media",
            title = "Social media blocker",
            subtitle = "Fanboy's Social",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-social.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_shorts",
            title = "YouTube Shorts blocker",
            subtitle = "YouTube Anti-Shorts",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-shorts.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_playables",
            title = "YouTube Playables blocker",
            subtitle = "Remove Youtube Playables",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-distracting.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_recommendations",
            title = "YouTube recommendations blocker (mobile-only)",
            subtitle = "YouTube Mobile Recommendations",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-recommended.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_autodubbed",
            title = "YouTube autodubbed videos blocker",
            subtitle = "Remove Youtube Autodubbed videos",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-distracting.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_end_video",
            title = "YouTube end video elements blocker",
            subtitle = "YouTube End Video Elements",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-distracting.txt",
            defaultEnabled = false
        ),

        // ── Screenshot 2.jpg ──────────────────────────────────────────────────
        FilterListEntry(
            id = "yt_members_only",
            title = "YouTube members-only video blocker",
            subtitle = "Remove Youtube Members video advertisements",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-distracting.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_distractions",
            title = "YouTube distractions elements blocker (mobile-only)",
            subtitle = "YouTube Mobile Distractions",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-distracting.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "yt_thumbnails",
            title = "YouTube thumbnail image blocker",
            subtitle = "Remove Youtube thumbnail images",
            url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/yt-distracting.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "tracking_url",
            title = "Tracking URL blocker",
            subtitle = "AdGuard URL Tracking Protection Filters",
            url = "https://filters.adtidy.org/extension/ublock/filters/17.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "chat_app",
            title = "Chat app blocker",
            subtitle = "Fanboy's Anti-chat Apps",
            url = "https://secure.fanboy.co.nz/fanboy-antichat.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "paywall",
            title = "Paywall blocker",
            subtitle = "Bypass Paywalls Clean Filters",
            url = "https://raw.githubusercontent.com/bpc-clone/bpc_updates/master/filters.txt",
            defaultEnabled = false
        ),
        FilterListEntry(
            id = "porn",
            title = "Porn blocker",
            subtitle = "Blocklists Anti-Porn",
            url = "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/porn-hosts",
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

    fun isListEnabled(context: Context, id: String): Boolean {
        return BrowserPreferences.getInstance(context).isFilterListEnabled(id)
    }

    fun setListEnabled(context: Context, id: String, enabled: Boolean) {
        BrowserPreferences.getInstance(context).setFilterListEnabled(id, enabled)
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
        val enabledLists = ALL_FILTER_LISTS.filter { prefs.isFilterListEnabled(it.id) }
        val cacheDir = getCacheDir(context)

        var downloadedCount = 0
        val totalToDownload = enabledLists.size

        for ((index, entry) in enabledLists.withIndex()) {
            onProgress(index + 1, totalToDownload, entry.title)
            try {
                val targetFile = File(cacheDir, "${entry.id}.txt")
                downloadFilterList(entry.url, targetFile)
                downloadedCount++
            } catch (e: Exception) {
                Log.w(TAG, "Failed downloading filter list ${entry.id}: ${e.message}")
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

            // 3. User custom filter rules
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
                Log.i(TAG, "Successfully compiled filter database with ${compiledBytes.size} bytes cache")

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
