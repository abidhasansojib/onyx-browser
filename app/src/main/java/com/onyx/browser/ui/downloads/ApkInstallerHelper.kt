package com.onyx.browser.ui.downloads

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import java.io.File

object ApkInstallerHelper {

    fun isApkFile(fileName: String, mimeType: String = ""): Boolean {
        val lowerName = fileName.lowercase().trim()
        val lowerMime = mimeType.lowercase().trim()
        return lowerName.endsWith(".apk") ||
                lowerName.contains(".apk?") ||
                lowerMime == "application/vnd.android.package-archive" ||
                lowerMime.contains("package-archive")
    }

    /**
     * Handles opening and installing an APK file with full Android 8.0+ Unknown Sources support.
     * Shows a confirmation dialog: "Do you want to install [App Name]?"
     * When confirmed:
     * - If unknown app install permission is not granted on Android 8.0+, prompts user with a
     *   security dialog and redirects to system Settings.
     * - If permitted (or pre-Oreo), launches the Android Package Installer.
     */
    fun installApk(
        activity: Activity,
        filePath: String,
        fileName: String = if (filePath.startsWith("content://", ignoreCase = true)) "application.apk" else File(filePath).name,
        onPermissionNeeded: ((pendingPath: String) -> Unit)? = null
    ) {
        if (!filePath.startsWith("content://", ignoreCase = true)) {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(activity, "APK file not found: $fileName", Toast.LENGTH_SHORT).show()
                return
            }
        }

        promptAndHandleInstall(activity, filePath, fileName, onPermissionNeeded)
    }

    fun promptAndHandleInstall(
        activity: Activity,
        filePath: String,
        fileName: String,
        onPermissionNeeded: ((pendingPath: String) -> Unit)? = null
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.install_app_confirm_title)
            .setMessage(activity.getString(R.string.install_app_confirm_desc, fileName))
            .setPositiveButton(R.string.install) { _, _ ->
                proceedWithInstall(activity, filePath, onPermissionNeeded)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun proceedWithInstall(
        activity: Activity,
        filePath: String,
        onPermissionNeeded: ((pendingPath: String) -> Unit)? = null
    ) {
        // 1. Android 8.0+ Unknown App Sources Permission Check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pm = activity.packageManager
            if (!pm.canRequestPackageInstalls()) {
                onPermissionNeeded?.invoke(filePath)
                showUnknownSourcesPermissionDialog(activity)
                return
            }
        }

        // 2. Permission is granted (or pre-Oreo). Launch package installer directly.
        launchPackageInstaller(activity, filePath)
    }

    fun showUnknownSourcesPermissionDialog(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.install_unknown_apps_title)
            .setMessage(R.string.install_unknown_apps_desc)
            .setPositiveButton(R.string.settings) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                        } catch (_: Exception) {
                            Toast.makeText(activity, "Cannot open settings: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun launchPackageInstaller(activity: Activity, filePath: String) {
        try {
            val uri: Uri = if (filePath.startsWith("content://", ignoreCase = true)) {
                if (filePath.contains(".fileprovider")) {
                    Uri.parse(filePath)
                } else {
                    // Copy to temp cache file for reliable PackageInstaller cross-process access
                    val tempApk = File(activity.cacheDir, "install_pending.apk")
                    val parsed = Uri.parse(filePath)
                    activity.contentResolver.openInputStream(parsed)?.use { input ->
                        tempApk.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    FileProvider.getUriForFile(
                        activity,
                        "${activity.applicationContext.packageName}.fileprovider",
                        tempApk
                    )
                }
            } else {
                val file = File(filePath)
                if (!file.exists()) {
                    Toast.makeText(activity, "APK file not found: ${file.name}", Toast.LENGTH_SHORT).show()
                    return
                }
                FileProvider.getUriForFile(
                    activity,
                    "${activity.applicationContext.packageName}.fileprovider",
                    file
                )
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(activity, "Cannot start installer: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
