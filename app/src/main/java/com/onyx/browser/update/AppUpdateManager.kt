package com.onyx.browser.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Xml
import com.onyx.browser.BuildConfig
import kotlinx.coroutines.Dispatchers
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

    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val currentVersion = getInstalledVersionName(context)
        val isPrereleaseApp = isPrerelease(context)

        if (isPrereleaseApp) {
            checkPrereleaseUpdate(currentVersion)
        } else {
            checkReleaseUpdate(currentVersion)
        }
    }

    // ── Release Update Flow ──────────────────────────────────────────────────

    private fun checkReleaseUpdate(currentVersion: String): UpdateCheckResult {
        // Tier 1: Public Atom RSS Feed (Zero rate limits, filters out prerelease entries)
        try {
            val atomResult = fetchReleaseFromAtomFeed(currentVersion)
            if (atomResult != null) {
                return atomResult
            }
        } catch (_: Exception) {
            // Fall through to Tier 2
        }

        // Tier 2: Public GitHub Web Redirect (/releases/latest is always stable release)
        try {
            val redirectResult = fetchReleaseFromWebRedirect(currentVersion)
            if (redirectResult != null) {
                return redirectResult
            }
        } catch (_: Exception) {
            // Fall through to Tier 3
        }

        // Tier 3: GitHub REST API (/releases/latest)
        try {
            val apiResult = fetchReleaseFromRestApi(currentVersion)
            if (apiResult != null) {
                return apiResult
            }
        } catch (_: Exception) {
            // Fall through
        }

        return UpdateCheckResult.Error("Unable to check for updates. Please check your internet connection.")
    }

    private fun fetchReleaseFromAtomFeed(currentVersion: String): UpdateCheckResult? {
        val request = Request.Builder()
            .url(GITHUB_ATOM_URL)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
            .header("Accept", "application/atom+xml, text/xml, */*")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val xmlBody = response.body?.string() ?: return null
        val entries = parseAtomEntries(xmlBody)
        val releaseEntry = entries.firstOrNull { !isPrereleaseTag(it.tag) } ?: return null

        val tagName = releaseEntry.tag
        val hasUpdate = isUpdateAvailable(currentVersion = currentVersion, remoteTag = tagName)
        if (!hasUpdate) {
            return UpdateCheckResult.UpToDate(currentVersion = currentVersion, latestTag = tagName)
        }

        val assets = fetchAssetsFromExpandedWeb(tagName).ifEmpty {
            buildDefaultAssetsForTag(tagName, isPrerelease = false)
        }

        val bestAsset = selectBestAsset(assets) ?: return null
        val sizedAsset = resolveAssetSize(bestAsset, "Mozilla/5.0")

        return UpdateCheckResult.Available(
            UpdateInfo(
                tagName = tagName,
                versionName = tagName.removePrefix("v"),
                releaseTitle = releaseEntry.title,
                releaseNotes = releaseEntry.contentHtml,
                matchingAsset = sizedAsset,
                allAssets = assets,
                isUpdateAvailable = true
            )
        )
    }

    private fun fetchReleaseFromWebRedirect(currentVersion: String): UpdateCheckResult? {
        val request = Request.Builder()
            .url(GITHUB_LATEST_WEB_URL)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
            .head()
            .build()

        val response = noRedirectClient.newCall(request).execute()
        val location = response.header("Location") ?: ""
        if (!location.contains("/releases/tag/")) return null

        val tagName = location.substringAfterLast("/releases/tag/").trim()
        if (tagName.isBlank() || isPrereleaseTag(tagName)) return null

        val hasUpdate = isUpdateAvailable(currentVersion = currentVersion, remoteTag = tagName)
        if (!hasUpdate) {
            return UpdateCheckResult.UpToDate(currentVersion = currentVersion, latestTag = tagName)
        }

        val assets = fetchAssetsFromExpandedWeb(tagName).ifEmpty {
            buildDefaultAssetsForTag(tagName, isPrerelease = false)
        }

        val bestAsset = selectBestAsset(assets) ?: return null
        val sizedAsset = resolveAssetSize(bestAsset, "Mozilla/5.0")

        return UpdateCheckResult.Available(
            UpdateInfo(
                tagName = tagName,
                versionName = tagName.removePrefix("v"),
                releaseTitle = "Onyx Browser $tagName",
                releaseNotes = "A new version of Onyx Browser ($tagName) is available on GitHub.",
                matchingAsset = sizedAsset,
                allAssets = assets,
                isUpdateAvailable = true
            )
        )
    }

    private fun fetchReleaseFromRestApi(currentVersion: String): UpdateCheckResult? {
        val request = Request.Builder()
            .url(GITHUB_LATEST_API_URL)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "OnyxBrowser/$currentVersion")
            .build()

        val response = client.newCall(request).execute()
        if (response.code == 403 || !response.isSuccessful) {
            return null
        }

        val bodyString = response.body?.string() ?: return null
        val json = JSONObject(bodyString)
        val tagName = json.optString("tag_name", "").trim()
        if (tagName.isBlank() || isPrereleaseTag(tagName)) return null

        val releaseTitle = json.optString("name", "Onyx Browser $tagName")
        val releaseNotes = json.optString("body", "")

        val assetsJson = json.optJSONArray("assets")
        val assetsList = mutableListOf<ReleaseAsset>()
        if (assetsJson != null) {
            for (i in 0 until assetsJson.length()) {
                val assetObj = assetsJson.getJSONObject(i)
                val assetName = assetObj.optString("name", "")
                val assetSize = assetObj.optLong("size", 0L)
                val downloadUrl = assetObj.optString("browser_download_url", "")
                if (assetName.endsWith(".apk", ignoreCase = true) &&
                    downloadUrl.isNotBlank() &&
                    isAllowedUpdateUrl(downloadUrl)) {
                    assetsList.add(ReleaseAsset(name = assetName, size = assetSize, downloadUrl = downloadUrl))
                }
            }
        }

        val hasUpdate = isUpdateAvailable(currentVersion = currentVersion, remoteTag = tagName)
        if (!hasUpdate) {
            return UpdateCheckResult.UpToDate(currentVersion = currentVersion, latestTag = tagName)
        }

        val matchedAsset = selectBestAsset(assetsList.ifEmpty { buildDefaultAssetsForTag(tagName, isPrerelease = false) })
            ?: return null

        return UpdateCheckResult.Available(
            UpdateInfo(
                tagName = tagName,
                versionName = tagName.removePrefix("v"),
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                matchingAsset = matchedAsset,
                allAssets = assetsList,
                isUpdateAvailable = true
            )
        )
    }

    // ── Prerelease Update Flow ───────────────────────────────────────────────

    private fun checkPrereleaseUpdate(currentVersion: String): UpdateCheckResult {
        // Tier 1: Public Atom RSS Feed (Zero rate limits, filters for prerelease entries)
        try {
            val atomResult = fetchPrereleaseFromAtomFeed(currentVersion)
            if (atomResult != null) {
                return atomResult
            }
        } catch (_: Exception) {
            // Fall through to Tier 2
        }

        // Tier 2: GitHub REST API (all releases, finding latest prerelease)
        try {
            val apiResult = fetchPrereleaseFromRestApi(currentVersion)
            if (apiResult != null) {
                return apiResult
            }
        } catch (_: Exception) {
            // Fall through to Tier 3
        }

        // Tier 3: Public GitHub Releases Web page (find latest prerelease tag)
        try {
            val webResult = fetchPrereleaseFromWeb(currentVersion)
            if (webResult != null) {
                return webResult
            }
        } catch (_: Exception) {
            // Fall through
        }

        // If no prerelease release exists on GitHub yet, app is up to date
        return UpdateCheckResult.UpToDate(currentVersion = currentVersion, latestTag = currentVersion)
    }

    private fun fetchPrereleaseFromAtomFeed(currentVersion: String): UpdateCheckResult? {
        val request = Request.Builder()
            .url(GITHUB_ATOM_URL)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
            .header("Accept", "application/atom+xml, text/xml, */*")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val xmlBody = response.body?.string() ?: return null
        val entries = parseAtomEntries(xmlBody)
        val prereleaseEntry = entries.firstOrNull { isPrereleaseTag(it.tag) } ?: return null

        val tagName = prereleaseEntry.tag
        val hasUpdate = isUpdateAvailable(currentVersion = currentVersion, remoteTag = tagName)
        if (!hasUpdate) {
            return UpdateCheckResult.UpToDate(currentVersion = currentVersion, latestTag = tagName)
        }

        val assets = fetchAssetsFromExpandedWeb(tagName).ifEmpty {
            buildDefaultAssetsForTag(tagName, isPrerelease = true)
        }

        val bestAsset = selectBestAsset(assets) ?: return null
        val sizedAsset = resolveAssetSize(bestAsset, "Mozilla/5.0")

        return UpdateCheckResult.Available(
            UpdateInfo(
                tagName = tagName,
                versionName = tagName.removePrefix("v"),
                releaseTitle = prereleaseEntry.title,
                releaseNotes = prereleaseEntry.contentHtml,
                matchingAsset = sizedAsset,
                allAssets = assets,
                isUpdateAvailable = true
            )
        )
    }

    private fun fetchPrereleaseFromRestApi(currentVersion: String): UpdateCheckResult? {
        val request = Request.Builder()
            .url("$GITHUB_RELEASES_API_URL?per_page=15")
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "OnyxBrowser/$currentVersion")
            .build()

        val response = client.newCall(request).execute()
        if (response.code == 403 || !response.isSuccessful) {
            return null
        }

        val bodyString = response.body?.string() ?: return null
        val jsonArray = JSONArray(bodyString)

        var prereleaseObj: JSONObject? = null
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val isPre = obj.optBoolean("prerelease", false)
            val isDraft = obj.optBoolean("draft", false)
            val tag = obj.optString("tag_name", "")
            if (!isDraft && (isPre || isPrereleaseTag(tag))) {
                prereleaseObj = obj
                break
            }
        }

        if (prereleaseObj == null) {
            return UpdateCheckResult.UpToDate(currentVersion = currentVersion, latestTag = currentVersion)
        }

        val tagName = prereleaseObj.optString("tag_name", "").trim()
        if (tagName.isBlank()) return null

        val releaseTitle = prereleaseObj.optString("name", "Onyx Browser $tagName (Pre-release)")
        val releaseNotes = prereleaseObj.optString("body", "")

        val assetsJson = prereleaseObj.optJSONArray("assets")
        val assetsList = mutableListOf<ReleaseAsset>()
        if (assetsJson != null) {
            for (i in 0 until assetsJson.length()) {
                val assetObj = assetsJson.getJSONObject(i)
                val assetName = assetObj.optString("name", "")
                val assetSize = assetObj.optLong("size", 0L)
                val downloadUrl = assetObj.optString("browser_download_url", "")
                if (assetName.endsWith(".apk", ignoreCase = true) &&
                    downloadUrl.isNotBlank() &&
                    isAllowedUpdateUrl(downloadUrl)) {
                    assetsList.add(ReleaseAsset(name = assetName, size = assetSize, downloadUrl = downloadUrl))
                }
            }
        }

        val hasUpdate = isUpdateAvailable(currentVersion = currentVersion, remoteTag = tagName)
        if (!hasUpdate) {
            return UpdateCheckResult.UpToDate(currentVersion = currentVersion, latestTag = tagName)
        }

        val matchedAsset = selectBestAsset(assetsList.ifEmpty { buildDefaultAssetsForTag(tagName, isPrerelease = true) })
            ?: return null

        return UpdateCheckResult.Available(
            UpdateInfo(
                tagName = tagName,
                versionName = tagName.removePrefix("v"),
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                matchingAsset = matchedAsset,
                allAssets = assetsList,
                isUpdateAvailable = true
            )
        )
    }

    private fun fetchPrereleaseFromWeb(currentVersion: String): UpdateCheckResult? {
        val request = Request.Builder()
            .url(GITHUB_RELEASES_WEB_URL)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
            .header("Accept", "text/html, */*")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val html = response.body?.string() ?: return null
        val pattern = Pattern.compile("""href=["'](/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/tag/([^"'\s>]+))["']""")
        val matcher = pattern.matcher(html)

        var matchedTag: String? = null
        while (matcher.find()) {
            val tag = matcher.group(2)?.trim() ?: continue
            if (isPrereleaseTag(tag)) {
                matchedTag = tag
                break
            }
        }

        if (matchedTag == null) return null

        val hasUpdate = isUpdateAvailable(currentVersion = currentVersion, remoteTag = matchedTag)
        if (!hasUpdate) {
            return UpdateCheckResult.UpToDate(currentVersion = currentVersion, latestTag = matchedTag)
        }

        val assets = fetchAssetsFromExpandedWeb(matchedTag).ifEmpty {
            buildDefaultAssetsForTag(matchedTag, isPrerelease = true)
        }
        val bestAsset = selectBestAsset(assets) ?: return null
        val sizedAsset = resolveAssetSize(bestAsset, "Mozilla/5.0")

        return UpdateCheckResult.Available(
            UpdateInfo(
                tagName = matchedTag,
                versionName = matchedTag.removePrefix("v"),
                releaseTitle = "Onyx Browser $matchedTag (Pre-release)",
                releaseNotes = "A new pre-release of Onyx Browser ($matchedTag) is available on GitHub.",
                matchingAsset = sizedAsset,
                allAssets = assets,
                isUpdateAvailable = true
            )
        )
    }

    // ── Atom Feed Parsing ────────────────────────────────────────────────────

    private data class AtomEntry(val tag: String, val title: String, val contentHtml: String)

    private fun parseAtomEntries(xml: String): List<AtomEntry> {
        val entries = mutableListOf<AtomEntry>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            var insideEntry = false
            var title: String? = null
            var tag: String? = null
            var contentHtml: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name.equals("entry", ignoreCase = true)) {
                            insideEntry = true
                        } else if (insideEntry) {
                            when {
                                name.equals("title", ignoreCase = true) -> {
                                    title = parser.nextText()
                                }
                                name.equals("link", ignoreCase = true) -> {
                                    val href = parser.getAttributeValue(null, "href") ?: ""
                                    if (href.contains("/releases/tag/")) {
                                        tag = href.substringAfterLast("/releases/tag/").trim()
                                    }
                                }
                                name.equals("id", ignoreCase = true) -> {
                                    if (tag == null) {
                                        val idText = parser.nextText()
                                        if (idText.contains("/releases/tag/")) {
                                            tag = idText.substringAfterLast("/releases/tag/").trim()
                                        } else if (idText.contains("/")) {
                                            tag = idText.substringAfterLast("/").trim()
                                        }
                                    }
                                }
                                name.equals("content", ignoreCase = true) -> {
                                    contentHtml = parser.nextText()
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name.equals("entry", ignoreCase = true) && insideEntry) {
                            if (!tag.isNullOrBlank()) {
                                entries.add(
                                    AtomEntry(
                                        tag = tag,
                                        title = title ?: "Onyx Browser $tag",
                                        contentHtml = contentHtml ?: ""
                                    )
                                )
                            }
                            insideEntry = false
                            title = null
                            tag = null
                            contentHtml = null
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            // Fall back to regex parsing
        }

        if (entries.isNotEmpty()) return entries
        return parseAtomEntriesRegex(xml)
    }

    private fun parseAtomEntriesRegex(xml: String): List<AtomEntry> {
        val entries = mutableListOf<AtomEntry>()
        val entryPattern = Pattern.compile("<entry>([\\s\\S]*?)</entry>")
        val entryMatcher = entryPattern.matcher(xml)
        while (entryMatcher.find()) {
            val entryContent = entryMatcher.group(1) ?: continue
            val tagMatcher = Pattern.compile("""/releases/tag/([^"'\s>]+)""").matcher(entryContent)
            val tag = if (tagMatcher.find()) tagMatcher.group(1)?.trim() ?: continue else continue

            val titleMatcher = Pattern.compile("<title[^>]*>([^<]+)</title>").matcher(entryContent)
            val title = if (titleMatcher.find()) titleMatcher.group(1)?.trim() ?: "Onyx Browser $tag" else "Onyx Browser $tag"

            val contentMatcher = Pattern.compile("<content[^>]*>([\\s\\S]*?)</content>").matcher(entryContent)
            val rawHtml = if (contentMatcher.find()) contentMatcher.group(1) ?: "" else ""
            val contentHtml = unescapeXmlEntities(rawHtml)

            entries.add(AtomEntry(tag = tag, title = title, contentHtml = contentHtml))
        }
        return entries
    }

    private fun unescapeXmlEntities(text: String): String {
        return text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
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
            val response = client.newCall(request).execute()
            val len = response.header("Content-Length")?.toLongOrNull() ?: 0L
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
