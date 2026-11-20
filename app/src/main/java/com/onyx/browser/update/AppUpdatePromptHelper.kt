package com.onyx.browser.update

import android.os.Build
import android.text.Html
import android.text.format.Formatter
import android.text.method.LinkMovementMethod
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.DialogUpdateAvailableBinding
import com.onyx.browser.databinding.DialogUpdateProgressBinding
import com.onyx.browser.ui.downloads.ApkInstallerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object AppUpdatePromptHelper {

    var pendingApkInstallPath: String? = null
    private var activeDownloader: AppUpdateDownloader? = null
    private var progressDialog: AlertDialog? = null

    /**
     * Checks if a pending APK install is awaiting unknown sources permission.
     * Call this in onResume() of the hosting Activity.
     */
    fun handleResumeInstall(activity: AppCompatActivity) {
        val pendingPath = pendingApkInstallPath ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.packageManager.canRequestPackageInstalls()) {
            pendingApkInstallPath = null
            ApkInstallerHelper.launchPackageInstaller(activity, pendingPath)
        }
    }

    /**
     * Shows the styled Material update available dialog with changelog, version diff, and download button.
     */
    fun showUpdateAvailableDialog(
        activity: AppCompatActivity,
        updateInfo: UpdateInfo,
        onDismiss: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialogBinding = DialogUpdateAvailableBinding.inflate(activity.layoutInflater)
        dialogBinding.tvUpdateDialogSubtitle.text = updateInfo.releaseTitle
        val currentVer = AppUpdateManager.getInstalledVersionName(activity).removePrefix("v")
        dialogBinding.tvCurrentVersionBadge.text = "v$currentVer"
        dialogBinding.tvNewVersionBadge.text = updateInfo.tagName

        val formattedSize = if (updateInfo.matchingAsset.size > 0L) {
            Formatter.formatFileSize(activity, updateInfo.matchingAsset.size)
        } else {
            ""
        }
        dialogBinding.tvUpdateAssetInfo.text = if (formattedSize.isNotBlank()) {
            "${updateInfo.matchingAsset.name} • $formattedSize"
        } else {
            updateInfo.matchingAsset.name
        }

        if (updateInfo.releaseNotes.isNotBlank()) {
            val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(updateInfo.releaseNotes, Html.FROM_HTML_MODE_LEGACY)
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(updateInfo.releaseNotes)
            }
            dialogBinding.tvChangelogContent.text = spanned
            dialogBinding.tvChangelogContent.movementMethod = LinkMovementMethod.getInstance()
        } else {
            dialogBinding.tvChangelogContent.text = activity.getString(R.string.no_release_notes)
        }

        // Cap the changelog height so long notes scroll instead of pushing buttons off-screen
        val maxChangelogPx = (activity.resources.displayMetrics.heightPixels * 0.28f).toInt()
        dialogBinding.scrollChangelog.post {
            val tv = dialogBinding.tvChangelogContent
            if (tv.height > maxChangelogPx) {
                dialogBinding.scrollChangelog.layoutParams =
                    dialogBinding.scrollChangelog.layoutParams.apply { height = maxChangelogPx }
            }
        }

        MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.download_and_install) { _, _ ->
                startUpdateDownload(activity, updateInfo)
            }
            .setNegativeButton(R.string.later) { _, _ ->
                val prefs = BrowserPreferences.getInstance(activity)
                prefs.dismissedUpdateTag = updateInfo.tagName
                onDismiss?.invoke()
            }
            .setOnCancelListener {
                onDismiss?.invoke()
            }
            .show()
    }

    /**
     * Shows download progress dialog, downloads APK using AppUpdateDownloader, and initiates installation.
     */
    fun startUpdateDownload(
        activity: AppCompatActivity,
        updateInfo: UpdateInfo,
        onComplete: ((File) -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val progressBinding = DialogUpdateProgressBinding.inflate(activity.layoutInflater)
        progressBinding.tvProgressSubtitle.text = updateInfo.releaseTitle
        progressBinding.pbDownloadProgress.isIndeterminate = false
        progressBinding.pbDownloadProgress.progress = 0
        progressBinding.tvProgressDetails.text = "Connecting…"
        progressBinding.tvProgressSpeed.text = ""

        val downloader = AppUpdateDownloader(activity.applicationContext)
        activeDownloader = downloader

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(progressBinding.root)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ ->
                downloader.cancel()
            }
            .create()

        progressDialog = dialog
        dialog.show()

        activity.lifecycleScope.launch {
            val result = downloader.downloadApk(
                downloadUrl = updateInfo.matchingAsset.downloadUrl,
                targetFileName = updateInfo.matchingAsset.name
            ) { bytesRead, totalBytes, speedBytesPerSec ->
                activity.runOnUiThread {
                    if (totalBytes > 0L) {
                        val progress = ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                        progressBinding.pbDownloadProgress.progress = progress
                        val readFormatted = Formatter.formatFileSize(activity, bytesRead)
                        val totalFormatted = Formatter.formatFileSize(activity, totalBytes)
                        progressBinding.tvProgressDetails.text = "$readFormatted / $totalFormatted ($progress%)"
                    } else {
                        progressBinding.pbDownloadProgress.isIndeterminate = true
                        val readFormatted = Formatter.formatFileSize(activity, bytesRead)
                        progressBinding.tvProgressDetails.text = readFormatted
                    }

                    if (speedBytesPerSec > 0L) {
                        progressBinding.tvProgressSpeed.text = "${Formatter.formatFileSize(activity, speedBytesPerSec)}/s"
                    } else {
                        progressBinding.tvProgressSpeed.text = ""
                    }
                }
            }

            withContext(Dispatchers.Main) {
                dialog.dismiss()
                activeDownloader = null
                progressDialog = null

                if (result.isSuccess) {
                    val apkFile = result.getOrThrow()
                    onComplete?.invoke(apkFile)
                    ApkInstallerHelper.installApk(
                        activity = activity,
                        filePath = apkFile.absolutePath,
                        fileName = apkFile.name,
                        onPermissionNeeded = { path ->
                            pendingApkInstallPath = path
                        }
                    )
                } else {
                    val error = result.exceptionOrNull()
                    if (error?.message?.contains("cancelled", ignoreCase = true) == true) {
                        Toast.makeText(activity, "Update download cancelled", Toast.LENGTH_SHORT).show()
                    } else {
                        val msg = error?.message ?: "Unknown error"
                        Toast.makeText(activity, activity.getString(R.string.update_download_failed, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
