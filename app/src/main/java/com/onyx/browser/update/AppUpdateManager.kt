package com.onyx.browser.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Xml
import com.onyx.browser.BuildConfig
import com.onyx.browser.data.preferences.BrowserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object AppUpdateManager {

    private const val GITHUB_REPO_OWNER = "abidhasansojib"
    private const val GITHUB_REPO_NAME = "onyx-browser"
    private const val GITHUB_ATOM_URL = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases.atom"
    private const val GITHUB_LATEST_WEB_URL = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
    private const val GITHUB_RELEASES_WEB_URL = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases"
    private const val GITHUB_LATEST_API_URL = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
    private const val GITHUB_RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases"

    private const val WEB_USER_AGENT = "Mozilla/5.0 (Android; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .build()

    fun isPrerelease(context: Context): Boolean {
        return BuildConfig.IS_PRERELEASE || context.packageName.endsWith(".prerelease")
    }

    fun isPrereleaseTag(tag: String): Boolean {
        val lower = tag.lowercase()
        return lower.contains("-") && (
            lower.contains("prerelease") ||
            lower.contains("pre") ||
            lower.contains("alpha") ||
            lower.contains("beta") ||
            lower.contains("rc") ||
            lower.contains("dev")
        )
    }

    private val _availableUpdateFlow = MutableStateFlow<UpdateInfo?>(null)
    val availableUpdateFlow: StateFlow<UpdateInfo?> = _availableUpdateFlow.asStateFlow()

    fun getCachedUpdate(context: Context): UpdateInfo? {
        val currentInstalled = getInstalledVersionName(context)
        val prefs = BrowserPreferences.getInstance(context)
        val tag = prefs.updates.cachedUpdateTag
        if (tag.isBlank()) {
            _availableUpdateFlow.value = null
            return null
        }

        if (!isUpdateAvailable(currentVersion = currentInstalled, remoteTag = tag)) {
            // Already updated to this or newer version; clear outdated cache
            prefs.updates.clearCachedUpdate()
            _availableUpdateFlow.value = null
            return null
        }

        val matchingAsset = if (prefs.updates.cachedUpdateAssetUrl.isNotBlank() && prefs.updates.cachedUpdateAssetName.isNotBlank()) {
            ReleaseAsset(
                name = prefs.updates.cachedUpdateAssetName,
                size = prefs.updates.cachedUpdateAssetSize,
                downloadUrl = prefs.updates.cachedUpdateAssetUrl
            )
        } else {
            val isPre = isPrerelease(context)
            selectBestAsset(buildDefaultAssetsForTag(tag, isPre)) ?: ReleaseAsset(
                name = "Onyx-Browser-$tag-${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}-${if (isPre) "prerelease" else "release"}.apk",
                size = 0L,
                downloadUrl = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/download/$tag/Onyx-Browser-$tag-${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}-${if (isPre) "prerelease" else "release"}.apk"
            )
        }

        val info = UpdateInfo(
            tagName = tag,
            versionName = tag.removePrefix("v"),
            releaseTitle = prefs.updates.cachedUpdateTitle.ifBlank { "Onyx Browser $tag" },
            releaseNotes = prefs.updates.cachedUpdateNotes,
            matchingAsset = matchingAsset,
            allAssets = listOf(matchingAsset),
            isUpdateAvailable = true
        )
        _availableUpdateFlow.value = info
        return info
    }

    private fun persistAvailableUpdate(context: Context, updateInfo: UpdateInfo) {
        try {
            val prefs = BrowserPreferences.getInstance(context)
            prefs.updates.cachedUpdateTag = updateInfo.tagName
            prefs.updates.cachedUpdateTitle = updateInfo.releaseTitle
            prefs.updates.cachedUpdateNotes = updateInfo.releaseNotes
            prefs.updates.cachedUpdateAssetName = updateInfo.matchingAsset.name
            prefs.updates.cachedUpdateAssetUrl = updateInfo.matchingAsset.downloadUrl
            prefs.updates.cachedUpdateAssetSize = updateInfo.matchingAsset.size
            _availableUpdateFlow.value = updateInfo
        } catch (_: Exception) {}
    }

    private fun clearPersistedUpdate(context: Context) {
        try {
            val prefs = BrowserPreferences.getInstance(context)
            prefs.updates.clearCachedUpdate()
            _availableUpdateFlow.value = null
        } catch (_: Exception) {}
    }

    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val currentVersion = getInstalledVersionName(context)
        val isPrereleaseApp = isPrerelease(context)

        val result = checkChannelUpdate(currentVersion, prerelease = isPrereleaseApp)

        when (result) {
            is UpdateCheckResult.Available -> persistAvailableUpdate(context, result.updateInfo)
            is UpdateCheckResult.UpToDate -> clearPersistedUpdate(context)
            else -> {}
        }

        result
    }

    const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    /**
     * Checks for an update when the 12-hour interval has elapsed. Failed checks do not
     * consume the interval, so they are retried on the next call.
     */
    suspend fun checkSilentlyIfDue(context: Context, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        val prefs = BrowserPreferences.getInstance(context)
        val now = System.currentTimeMillis()
        val lastCheck = prefs.updates.lastAppUpdateCheckTime

        if (!force && lastCheck > 0L && now >= lastCheck && now - lastCheck < CHECK_INTERVAL_MS) {
            return@withContext getCachedUpdate(context)
        }

        val result = checkForUpdate(context)
        if (result !is UpdateCheckResult.Error && result !is UpdateCheckResult.RateLimited) {
            prefs.updates.lastAppUpdateCheckTime = now
        }
        if (result is UpdateCheckResult.Available) {
            result.updateInfo
        } else {
            null
        }
    }

    // ── Update Flow (release and prerelease share one implementation) ────────

    private fun checkChannelUpdate(currentVersion: String, prerelease: Boolean): UpdateCheckResult {
        var anyEndpointSucceeded = false
        var wasRateLimited = false

        val tiers: List<() -> Pair<Boolean, UpdateCheckResult?>> = if (prerelease) {
            listOf(
                { fetchFromAtomFeed(currentVersion, true) },
                { fetchFromRestApi(currentVersion, true).also { if (it.second is UpdateCheckResult.RateLimited) wasRateLimited = true } },
                { fetchFromReleasesPage(currentVersion, true) }
            )
        } else {
            listOf(
                { fetchFromAtomFeed(currentVersion, false) },
                { fetchFromWebRedirect(currentVersion) },
                { fetchFromRestApi(currentVersion, false).also { if (it.second is UpdateCheckResult.RateLimited) wasRateLimited = true } },
                { fetchFromReleasesPage(currentVersion, false) }
            )
        }
        for (tier in tiers) {
            try {
                val (reachable, result) = tier()
                if (reachable) {
                    anyEndpointSucceeded = true
                }
                if (result != null && result !is UpdateCheckResult.RateLimited) {
                    return result
                }
            } catch (_: Exception) {
                // Fall through to the next tier
            }
        }
        return when {
            anyEndpointSucceeded -> UpdateCheckResult.UpToDate(currentVersion = currentVersion, latestTag = currentVersion)
            wasRateLimited -> UpdateCheckResult.RateLimited("GitHub API rate limit reached. Please try again later or visit GitHub directly.")
            else -> UpdateCheckResult.Error("Unable to check for updates. Please check your internet connection.")
        }
    }

    private fun webRequest(url: String, accept: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", WEB_USER_AGENT)
        .header("Accept", accept)
        .build()

    /** Shared tail of every tier: compares versions and resolves the best APK asset. */
    private fun resultForTag(
        currentVersion: String,
        tagName: String,
        prerelease: Boolean,
        title: String,
        notes: String,
        knownAssets: List<ReleaseAsset>?
    ): UpdateCheckResult? {
        if (!isUpdateAvailable(currentVersion = currentVersion, remoteTag = tagName)) {
            return UpdateCheckResult.UpToDate(currentVersion = currentVersion, latestTag = tagName)
        }
        val assets = (knownAssets ?: fetchAssetsFromExpandedWeb(tagName)).ifEmpty {
            buildDefaultAssetsForTag(tagName, isPrerelease = prerelease)
        }
        val best = selectBestAsset(assets) ?: return null
        return UpdateCheckResult.Available(
            UpdateInfo(
                tagName = tagName,
                versionName = tagName.removePrefix("v"),
                releaseTitle = title,
                releaseNotes = notes,
                matchingAsset = resolveAssetSize(best, "Mozilla/5.0"),
                allAssets = assets,
                isUpdateAvailable = true
            )
        )
    }

    private fun fetchFromAtomFeed(currentVersion: String, prerelease: Boolean): Pair<Boolean, UpdateCheckResult?> {
        val xmlBody = client.newCall(webRequest(GITHUB_ATOM_URL, "application/atom+xml, text/xml, */*"))
            .execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return false to null
        val isValidFeed = xmlBody.contains("<feed", ignoreCase = true)
        val entry = ReleaseFeedParser.parseAtomEntries(xmlBody)
            .firstOrNull { isPrereleaseTag(it.tag) == prerelease } ?: return isValidFeed to null
        return true to resultForTag(currentVersion, entry.tag, prerelease, entry.title, entry.contentHtml, null)
    }

    /** /releases/latest redirects to `/releases/tag/<tag>` when a stable release exists, or `/releases` when none exist. */
    private fun fetchFromWebRedirect(currentVersion: String): Pair<Boolean, UpdateCheckResult?> {
        val request = Request.Builder()
            .url(GITHUB_LATEST_WEB_URL)
            .header("User-Agent", WEB_USER_AGENT)
            .head()
            .build()
        val (code, location) = noRedirectClient.newCall(request).execute().use {
            it.code to (it.header("Location") ?: "")
        }
        val reachable = code in 200..399
        if (!location.contains("/releases/tag/")) return reachable to null
        val tagName = location.substringAfterLast("/releases/tag/").trim()
        if (tagName.isBlank() || isPrereleaseTag(tagName)) return reachable to null
        return true to resultForTag(
            currentVersion, tagName, false,
            "Onyx Browser $tagName",
            "A new version of Onyx Browser ($tagName) is available on GitHub.",
            null
        )
    }

    private fun fetchFromRestApi(currentVersion: String, prerelease: Boolean): Pair<Boolean, UpdateCheckResult?> {
        val request = Request.Builder()
            .url("$GITHUB_RELEASES_API_URL?per_page=15")
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "OnyxBrowser/$currentVersion")
            .build()
        val (code, body) = client.newCall(request).execute().use { response ->
            response.code to (if (response.isSuccessful) response.body?.string() else null)
        }
        if (code == 403 || code == 429) {
            return false to UpdateCheckResult.RateLimited("GitHub API rate limit reached. Please try again later or visit GitHub directly.")
        }
        if (body == null) return false to null

        val array = JSONArray(body)
        var release: JSONObject? = null
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val tag = obj.optString("tag_name", "")
            val isPre = obj.optBoolean("prerelease", false) || isPrereleaseTag(tag)
            if (!obj.optBoolean("draft", false) && isPre == prerelease) {
                release = obj
                break
            }
        }
        if (release == null) {
            return true to UpdateCheckResult.UpToDate(currentVersion, currentVersion)
        }

        val tagName = release.optString("tag_name", "").trim()
        if (tagName.isBlank()) return true to null

        val assets = mutableListOf<ReleaseAsset>()
        release.optJSONArray("assets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val name = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank() && isAllowedUpdateUrl(url)) {
                    assets.add(ReleaseAsset(name = name, size = a.optLong("size", 0L), downloadUrl = url))
                }
            }
        }
        return true to resultForTag(
            currentVersion, tagName, prerelease,
            release.optString("name", "Onyx Browser $tagName"),
            release.optString("body", ""),
            assets
        )
    }

    /** Last resort: scrape tag links from the public releases page. */
    private fun fetchFromReleasesPage(currentVersion: String, prerelease: Boolean): Pair<Boolean, UpdateCheckResult?> {
        val html = client.newCall(webRequest(GITHUB_RELEASES_WEB_URL, "text/html, */*"))
            .execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return false to null
        val matcher = Pattern.compile(
            """href=["'](/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/tag/([^"'\s>]+))["']"""
        ).matcher(html)
        var tag: String? = null
        while (matcher.find()) {
            val candidate = matcher.group(2)?.trim() ?: continue
            if (isPrereleaseTag(candidate) == prerelease) {
                tag = candidate
                break
            }
        }
        val matched = tag ?: return true to null
        return true to resultForTag(
            currentVersion, matched, prerelease,
            "Onyx Browser $matched" + if (prerelease) " (Pre-release)" else "",
            "A new ${if (prerelease) "pre-release" else "version"} of Onyx Browser ($matched) is available on GitHub.",
            null
        )
    }

    // ── Asset Discovery & Matching ───────────────────────────────────────────

    private fun fetchAssetsFromExpandedWeb(tagName: String): List<ReleaseAsset> {
        return try {
            val url = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/expanded_assets/$tagName"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
                .header("Accept", "text/html, */*")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val html = response.body?.string() ?: return emptyList()
            val pattern = Pattern.compile("""href=["'](/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/download/[^"']+/([^"']+\.apk))["']""")
            val matcher = pattern.matcher(html)
            val assets = mutableListOf<ReleaseAsset>()
            val seenNames = mutableSetOf<String>()

            while (matcher.find()) {
                val path = matcher.group(1) ?: continue
                val name = matcher.group(2) ?: continue
                if (seenNames.add(name)) {
                    val fullUrl = "https://github.com$path"
                    if (isAllowedUpdateUrl(fullUrl)) {
                        assets.add(ReleaseAsset(name = name, size = 0L, downloadUrl = fullUrl))
                    }
                }
            }
            assets
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun buildDefaultAssetsForTag(tagName: String, isPrerelease: Boolean = false): List<ReleaseAsset> {
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "universal")
        val suffix = if (isPrerelease) "prerelease" else "release"
        return abis.map { abi ->
            val fileName = "Onyx-Browser-$tagName-$abi-$suffix.apk"
            val downloadUrl = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/download/$tagName/$fileName"
            ReleaseAsset(name = fileName, size = 0L, downloadUrl = downloadUrl)
        }
    }

    private fun isAllowedUpdateUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val host = android.net.Uri.parse(url).host?.lowercase() ?: return false
            host == "github.com" ||
                host.endsWith(".github.com") ||
                host == "objects.githubusercontent.com" ||
                host.endsWith(".githubusercontent.com")
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveAssetSize(asset: ReleaseAsset, userAgent: String): ReleaseAsset {
        if (asset.size > 0L) return asset
        return try {
            val request = Request.Builder()
                .url(asset.downloadUrl)
                .head()
                .header("User-Agent", userAgent)
                .build()
            val len = client.newCall(request).execute().use { it.header("Content-Length")?.toLongOrNull() } ?: 0L
            if (len > 0L) {
                asset.copy(size = len)
            } else {
                asset
            }
        } catch (_: Exception) {
            asset
        }
    }

    fun isUpdateAvailable(currentVersion: String, remoteTag: String): Boolean {
        val cleanCurrent = currentVersion.trim().removePrefix("v")
        val cleanRemote = remoteTag.trim().removePrefix("v")

        val currentBase = cleanCurrent.substringBefore("-")
        val remoteBase = cleanRemote.substringBefore("-")

        val currentParts = currentBase.split(".").mapNotNull { it.toIntOrNull() }
        val remoteParts = remoteBase.split(".").mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(currentParts.size, remoteParts.size)
        for (i in 0 until maxLength) {
            val curr = currentParts.getOrElse(i) { 0 }
            val rem = remoteParts.getOrElse(i) { 0 }
            if (rem > curr) return true
            if (rem < curr) return false
        }

        val currentHasSuffix = cleanCurrent.contains("-")
        val remoteHasSuffix = cleanRemote.contains("-")

        // Stable release of the same base version is newer than its prerelease
        if (currentHasSuffix && !remoteHasSuffix) {
            return true
        }

        // Compare prerelease build numbers if both share the same base version
        if (currentHasSuffix && remoteHasSuffix) {
            val currentSuffix = cleanCurrent.substringAfter("-")
            val remoteSuffix = cleanRemote.substringAfter("-")
            if (currentSuffix != remoteSuffix) {
                val currentSuffixNum = currentSuffix.filter { it.isDigit() }.toIntOrNull() ?: 0
                val remoteSuffixNum = remoteSuffix.filter { it.isDigit() }.toIntOrNull() ?: 0
                if (remoteSuffixNum > currentSuffixNum) return true
            }
        }

        return false
    }

    fun selectBestAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
        if (assets.isEmpty()) return null

        val supportedAbis = Build.SUPPORTED_ABIS
        for (abi in supportedAbis) {
            val match = assets.firstOrNull { asset ->
                val lower = asset.name.lowercase()
                lower.endsWith(".apk") && (lower.contains("-$abi-") || lower.contains("-$abi."))
            }
            if (match != null) return match
        }

        // Fall back to universal build
        val universal = assets.firstOrNull {
            it.name.lowercase().contains("universal") && it.name.endsWith(".apk", ignoreCase = true)
        }
        if (universal != null) return universal

        // Fall back to first APK
        return assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    }

    fun getInstalledVersionName(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: BuildConfig.VERSION_NAME
        } catch (_: Exception) {
            BuildConfig.VERSION_NAME
        }
    }
}
