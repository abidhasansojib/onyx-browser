package com.onyx.browser.update

data class ReleaseAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String
)

data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val matchingAsset: ReleaseAsset,
    val allAssets: List<ReleaseAsset>,
    val isUpdateAvailable: Boolean
)

sealed interface UpdateCheckResult {
    data class Available(val updateInfo: UpdateInfo) : UpdateCheckResult
    data class UpToDate(val currentVersion: String, val latestTag: String) : UpdateCheckResult
    data class RateLimited(val message: String) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}
