package com.onyx.browser.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.onyx.browser.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object AppUpdateManager {

    private const val GITHUB_REPO_OWNER = "abidhasansojib"
    private const val GITHUB_REPO_NAME = "onyx-browser"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val currentVersion = getInstalledVersionName(context)

        try {
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "OnyxBrowser/$currentVersion")
                .build()

            val response = client.newCall(request).execute()

            if (response.code == 403) {
                val remaining = response.header("X-RateLimit-Remaining")
                if (remaining == "0") {
                    return@withContext UpdateCheckResult.RateLimited(
                        "GitHub API rate limit reached. Please try again later or visit GitHub directly."
                    )
                }
            }

            if (!response.isSuccessful) {
                return@withContext UpdateCheckResult.Error(
                    "GitHub returned HTTP ${response.code} (${response.message})"
                )
            }

            val bodyString = response.body?.string()
                ?: return@withContext UpdateCheckResult.Error("Empty response from GitHub")

            val json = JSONObject(bodyString)
            val tagName = json.optString("tag_name", "").trim()
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
                    if (assetName.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                        assetsList.add(ReleaseAsset(name = assetName, size = assetSize, downloadUrl = downloadUrl))
                    }
                }
            }

            if (tagName.isBlank()) {
                return@withContext UpdateCheckResult.Error("Invalid release tag received from GitHub")
            }

            val hasUpdate = isUpdateAvailable(currentVersion = currentVersion, remoteTag = tagName)
            if (!hasUpdate) {
                return@withContext UpdateCheckResult.UpToDate(
                    currentVersion = currentVersion,
                    latestTag = tagName
                )
            }

            val matchedAsset = selectBestAsset(assetsList)
                ?: return@withContext UpdateCheckResult.Error("No compatible APK found in release $tagName")

            val updateInfo = UpdateInfo(
                tagName = tagName,
                versionName = tagName.removePrefix("v"),
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                matchingAsset = matchedAsset,
                allAssets = assetsList,
                isUpdateAvailable = true
            )

            UpdateCheckResult.Available(updateInfo)
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Failed to connect to GitHub")
        }
    }

    fun isUpdateAvailable(currentVersion: String, remoteTag: String): Boolean {
        val cleanCurrent = currentVersion.trim().removePrefix("v").substringBefore("-")
        val cleanRemote = remoteTag.trim().removePrefix("v").substringBefore("-")

        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(currentParts.size, remoteParts.size)
        for (i in 0 until maxLength) {
            val curr = currentParts.getOrElse(i) { 0 }
            val rem = remoteParts.getOrElse(i) { 0 }
            if (rem > curr) return true
            if (rem < curr) return false
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
